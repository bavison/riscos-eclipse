package com.piccolosystems.riscos.asmindex.lsp;

import com.piccolosystems.riscos.asmindex.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON-RPC 2.0 framed as the Language Server Protocol frames it: an ASCII
 * header block with {@code Content-Length}, a blank line, then that many bytes
 * of UTF-8 JSON. Writing is thread-safe.
 */
final class Transport {
    private final InputStream in;
    private final OutputStream out;

    /**
     * If the environment variable {@code OBJASM_INDEX_TRACE} is set, every
     * message in and out is summarised on stderr (which a client may keep in
     * a log), for finding out what a client and the server said to each other.
     */
    private static final boolean TRACE = System.getenv("OBJASM_INDEX_TRACE") != null;

    private static void trace(String dir, String json) {
        if (!TRACE) return;
        String s = json.length() > 300 ? json.substring(0, 300) + "..." : json;
        System.err.println(java.time.LocalTime.now() + " " + dir + " " + s);
    }

    Transport(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    /** The next message, or null at end of input. */
    @SuppressWarnings("unchecked")
    Map<String, Object> read() throws IOException {
        int length = -1;
        for (;;) {
            String h = header();
            if (h == null) return null;
            if (h.isEmpty()) {
                if (length >= 0) break;
                continue;
            }
            int c = h.indexOf(':');
            if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("Content-Length"))
                length = Integer.parseInt(h.substring(c + 1).trim());
        }
        byte[] body = in.readNBytes(length);
        if (body.length < length) return null;
        String text = new String(body, StandardCharsets.UTF_8);
        trace("<-", text);
        Object o = Json.parse(text);
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    /** One header line without its CR LF; null at end of input. */
    private String header() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (;;) {
            int c = in.read();
            if (c < 0) return b.size() == 0 ? null : b.toString(StandardCharsets.US_ASCII);
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        return b.toString(StandardCharsets.US_ASCII);
    }

    synchronized void write(Map<String, Object> message) throws IOException {
        // Json escapes everything outside printable ASCII, so chars are bytes.
        String text = Json.toJson(message);
        trace("->", text);
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    void respond(Object id, Object result) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        write(m);
    }

    void error(Object id, int code, String message) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message));
        write(m);
    }

    void notify(String method, Object params) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("method", method);
        m.put("params", params);
        write(m);
    }
}
