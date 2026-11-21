"""Indexes a buildscan output with the language server, then tries a few
requests on one assembler file. Usage: index.py JAR SCAN_OUT FILE"""
import sys, time, os, json
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..', 'asmindex', 'tools'))
from lsp_client import Client
jar, settings, src = sys.argv[1:4]
c = Client(['java', '-Xmx1500m', '-jar', jar, 'lsp'], log=open('lsp.log', 'wb'))
t = time.time()
c.request('initialize', {'processId': None, 'capabilities': {}, 'initializationOptions': {'settings': [settings]}})
c.notify('initialized', {})
st = c.wait_for(lambda m: m['method'] == 'riscos/status' and m['params']['state'] == 'idle', 900)
print('indexed in %.0fs:' % (time.time() - t), json.dumps(st['params'])[:300])
uri = 'file:///' + src.replace('\\', '/')
text = open(src, encoding='latin-1').read()
c.notify('textDocument/didOpen', {'textDocument': {'uri': uri, 'languageId': 'objasm', 'version': 1, 'text': text}})
lines = text.split('\n')
for want in ('GET', 'SWI', 'BL'):
    for i, l in enumerate(lines):
        w = l.split()
        if len(w) >= 2 and (w[0] == want):
            col = l.index(w[1]) + 1
            r = c.request('textDocument/definition', {'textDocument': {'uri': uri}, 'position': {'line': i, 'character': col}})
            print(want, i + 1, l.strip()[:60], '->', [(x['uri'][-60:], x['range']['start']['line'] + 1) for x in (r or [])][:2])
            break
h = c.request('textDocument/hover', {'textDocument': {'uri': uri}, 'position': {'line': 0, 'character': 0}})
d = c.request('textDocument/documentSymbol', {'textDocument': {'uri': uri}})
print('outline entries:', len(d or []))
