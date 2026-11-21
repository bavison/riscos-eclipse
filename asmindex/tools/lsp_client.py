"""A minimal LSP client for scripted tests of the objasm-index language server.

Runs the server as a subprocess and speaks JSON-RPC to it over stdio.
"""

import json
import pathlib
import queue
import subprocess
import threading
import time


class Client:
    def __init__(self, cmd, log=None):
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=log or subprocess.DEVNULL)
        self.next_id = 0
        self.responses = {}
        self.notifications = queue.Queue()
        self.cond = threading.Condition()
        threading.Thread(target=self._reader, daemon=True).start()

    def _reader(self):
        out = self.proc.stdout
        while True:
            length = None
            while True:
                line = out.readline()
                if not line:
                    return
                line = line.strip()
                if not line:
                    break
                k, _, v = line.decode('ascii').partition(':')
                if k.lower() == 'content-length':
                    length = int(v)
            msg = json.loads(out.read(length))
            if 'method' in msg and 'id' in msg:
                # A request from the server (e.g. workDoneProgress/create): accept it.
                self._send({'jsonrpc': '2.0', 'id': msg['id'], 'result': None})
            elif 'method' in msg:
                self.notifications.put(msg)
            else:
                with self.cond:
                    self.responses[msg['id']] = msg
                    self.cond.notify_all()

    def _send(self, msg):
        body = json.dumps(msg).encode('utf-8')
        self.proc.stdin.write(b'Content-Length: %d\r\n\r\n' % len(body) + body)
        self.proc.stdin.flush()

    def request(self, method, params, timeout=60):
        self.next_id += 1
        i = self.next_id
        self._send({'jsonrpc': '2.0', 'id': i, 'method': method, 'params': params})
        end = time.time() + timeout
        with self.cond:
            while i not in self.responses:
                left = end - time.time()
                if left <= 0:
                    raise TimeoutError(method)
                self.cond.wait(left)
            r = self.responses.pop(i)
        if 'error' in r:
            raise RuntimeError(f"{method}: {r['error']}")
        return r['result']

    def notify(self, method, params):
        self._send({'jsonrpc': '2.0', 'method': method, 'params': params})

    def wait_for(self, pred, timeout=120):
        """Returns the first notification satisfying pred; earlier ones are kept for later waits."""
        end = time.time() + timeout
        kept = []
        try:
            while True:
                left = end - time.time()
                if left <= 0:
                    raise TimeoutError('notification')
                m = self.notifications.get(timeout=left)
                if pred(m):
                    return m
                kept.append(m)
        finally:
            for m in kept:
                self.notifications.put(m)

    def drain(self):
        while not self.notifications.empty():
            self.notifications.get_nowait()

    def open(self, path, text=None):
        p = pathlib.Path(path)
        if text is None:
            text = p.read_bytes().decode('latin-1')
        uri = p.absolute().as_uri()
        self.notify('textDocument/didOpen', {'textDocument': {
            'uri': uri, 'languageId': 'objasm', 'version': 1, 'text': text}})
        return uri

    def close(self):
        self.request('shutdown', None)
        self.notify('exit', None)
        return self.proc.wait(10)


def pos(uri, line, ch):
    """A text document position; line is 1-based as in an editor, ch 0-based."""
    return {'textDocument': {'uri': uri}, 'position': {'line': line - 1, 'character': ch}}
