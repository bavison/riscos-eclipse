package com.piccolosystems.riscos.asmindex.lsp;

import com.piccolosystems.riscos.asmindex.util.Json;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Language server tests: a scripted client talks to an in-process server
 * over pipes, about a small synthetic tree with two components. Run with
 * {@code java -cp build/classes:build/test-classes com.piccolosystems.riscos.asmindex.lsp.LspTests}.
 */
public final class LspTests {
    private static int passed, failed;
    private static String group = "";

    // ------------------------------------------------------------------ the tree

    static final String MACROS = String.join("\n",
            "        GBLS    SWIClass",
            "        MACRO",
            "        AddSWI  $SWIName,$value",
            "  [     \"$value\" = \"\"",
            "$SWIClass._$SWIName # 1",
            "  |",
            "$SWIClass._$SWIName * $value",
            "  ]",
            "X$SWIClass._$SWIName * $SWIClass._$SWIName + Auto_Error_SWI_bit",
            "        MEND",
            "",
            "        MACRO",
            "        Twice   $x",
            "  [     \"$x\" = \"never\"",
            "        MOV     r0, r0          ; skipped in every expansion",
            "  ]",
            "        MOV     r1, r1",
            "        MEND",
            "",
            "        MACRO",
            "        Wrap    $y",
            "        Twice   $y",
            "        DCD     &$y",
            "        MEND",
            "        END", "");

    static final String SWIS = String.join("\n",
            "Auto_Error_SWI_bit * 1 :SHL: 17",
            "SWIClass SETS   \"OS\"",
            "        ^       0",
            "        AddSWI  WriteC",
            "        AddSWI  WriteS",
            "        AddSWI  Byte, 6",
            "  [ :DEF: FeatureA",
            "FeatureVal * 1                  ; A only",
            "  |",
            "FeatureVal * 2                  ; B only",
            "  ]",
            "  [ {FALSE}",
            "Never   * 1                     ; shaded in both",
            "  ]",
            "        GBLA    Count",
            "  [ :DEF: FeatureA",
            "Count   SETA    1",
            "  |",
            "Count   SETA    2",
            "  ]",
            "Both    *       Count",
            "        END", "");

    static final String MAIN = String.join("\n",
            "        GET     Hdr:Macros",
            "        GET     Hdr:SWIs",
            "        AREA    |Code|, CODE",
            "Start   ROUT",
            "        SWI     XOS_WriteC",
            "01      MOV     r0, r0",
            "        B       %BT01",
            "  [ FeatureVal = 2",
            "        MOV     r1, r1          ; shaded in A",
            "  ]",
            "        Twice   a",
            "        Wrap    7",
            "Next    ROUT",
            "        B       Start",
            "        GET     Hdr:Missing",
            "        END", "");

    static final String OTHER = String.join("\n",
            "        GET     Hdr:Macros",
            "        GET     Hdr:SWIs",
            "        AREA    |Code|, CODE",
            "        SWI     OS_WriteC",
            "        END", "");

    /** 1-based line of the first line containing {@code marker}. */
    static int line(String text, String marker) {
        String[] l = text.split("\n", -1);
        for (int i = 0; i < l.length; i++) if (l[i].contains(marker)) return i + 1;
        throw new IllegalArgumentException(marker);
    }

    static int col(String text, String marker, String word) {
        return text.split("\n", -1)[line(text, marker) - 1].indexOf(word);
    }

    // ------------------------------------------------------------------ client

    private final BlockingQueue<Map<String, Object>> notes = new LinkedBlockingQueue<>();
    private final Map<Object, Map<String, Object>> responses = new HashMap<>();
    private final Transport client;
    private final Thread serverThread;
    private int exitCode = -1;
    private long nextId;

    @SuppressWarnings("unchecked")
    LspTests() throws IOException {
        PipedOutputStream toServer = new PipedOutputStream();
        PipedInputStream serverIn = new PipedInputStream(toServer, 1 << 20);
        PipedOutputStream fromServer = new PipedOutputStream();
        PipedInputStream clientIn = new PipedInputStream(fromServer, 1 << 20);
        Server s = new Server(serverIn, fromServer, null, new PrintStream(System.err, true, StandardCharsets.UTF_8));
        serverThread = new Thread(() -> {
            try { exitCode = s.run(); } catch (IOException e) { exitCode = 99; }
        });
        serverThread.start();
        client = new Transport(clientIn, toServer);
        Thread reader = new Thread(() -> {
            try {
                for (Map<String, Object> m; (m = client.read()) != null; ) {
                    if (m.get("method") != null && m.get("id") != null) client.respond(m.get("id"), null);
                    else if (m.get("method") != null) notes.add(m);
                    else synchronized (responses) { responses.put(m.get("id"), m); responses.notifyAll(); }
                }
            } catch (IOException e) {
                // pipe closed
            }
        });
        reader.setDaemon(true);
        reader.start();
    }

    Object request(String method, Object params) throws Exception {
        long id = ++nextId;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("method", method);
        m.put("params", params);
        client.write(m);
        long end = System.currentTimeMillis() + 30000;
        synchronized (responses) {
            while (!responses.containsKey(id)) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) throw new IllegalStateException("no response to " + method);
                responses.wait(left);
            }
            Map<String, Object> r = responses.remove(id);
            if (r.containsKey("error")) throw new IllegalStateException(method + ": " + r.get("error"));
            return r.get("result");
        }
    }

    void notify(String method, Object params) throws IOException { client.notify(method, params); }

    /** The first notification matching; others before it are kept. */
    Map<String, Object> await(Predicate<Map<String, Object>> p) throws Exception {
        List<Map<String, Object>> kept = new ArrayList<>();
        try {
            long end = System.currentTimeMillis() + 30000;
            for (;;) {
                Map<String, Object> m = notes.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (m == null) throw new IllegalStateException("notification not received");
                if (p.test(m)) return m;
                kept.add(m);
            }
        } finally {
            notes.addAll(kept);
        }
    }

    Map<String, Object> awaitNote(String method, String uri) throws Exception {
        return await(m -> method.equals(m.get("method"))
                && (uri == null || uri.equals(uriIn(Server.obj(m.get("params"))))));
    }

    void awaitIdle() throws Exception {
        await(m -> "riscos/status".equals(m.get("method")) && "idle".equals(Server.obj(m.get("params")).get("state")));
    }

    private static String uriIn(Map<String, Object> p) {
        Object u = p.get("uri");
        return u != null ? (String) u : (String) Server.obj(p.get("textDocument")).get("uri");
    }

    String open(Path p, String text) throws IOException {
        String uri = p.toUri().toString();
        notify("textDocument/didOpen", Map.of("textDocument", Map.of("uri", uri, "languageId", "objasm", "version", 1, "text", text)));
        return uri;
    }

    static Map<String, Object> at(String uri, int line, int col) {
        return Map.of("textDocument", Map.of("uri", uri), "position", Map.of("line", line - 1, "character", col));
    }

    // ------------------------------------------------------------------ checks

    private static void group(String g) { group = g; System.out.println("== " + g); }

    private static void check(String what, boolean ok, Object detail) {
        if (ok) passed++;
        else { failed++; System.out.println("FAIL [" + group + "] " + what + ": " + Json.toJson(detail)); }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object o) { return o == null ? List.of() : (List<Map<String, Object>>) o; }

    static int startLine(Map<String, Object> loc) {
        return ((Number) Server.obj(Server.obj(loc.get("range")).get("start")).get("line")).intValue() + 1;
    }

    static int startCol(Map<String, Object> loc) {
        return ((Number) Server.obj(Server.obj(loc.get("range")).get("start")).get("character")).intValue();
    }

    static List<List<Integer>> lines(Object ranges) {
        List<List<Integer>> out = new ArrayList<>();
        for (Map<String, Object> r : list(ranges))
            out.add(List.of(((Number) Server.obj(r.get("start")).get("line")).intValue() + 1,
                    ((Number) Server.obj(r.get("end")).get("line")).intValue() + 1));
        return out;
    }

    static String hoverText(Object h) {
        return h == null ? "" : (String) Server.obj(Server.obj(h).get("contents")).get("value");
    }

    static List<String> labels(Object completion) {
        List<String> l = new ArrayList<>();
        for (Map<String, Object> i : list(Server.obj(completion).get("items"))) l.add((String) i.get("label"));
        return l;
    }

    // ------------------------------------------------------------------ the tests

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("lsptest").toRealPath();
        write(dir.resolve("hdr/Macros"), MACROS);
        write(dir.resolve("hdr/SWIs"), SWIS);
        write(dir.resolve("a/s/Main"), MAIN);
        write(dir.resolve("b/s/Other"), OTHER);
        Path settings = dir.resolve("settings");
        write(settings.resolve("environment.json"), Json.toJson(Map.of(
                "set_by_env_file", Map.of(), "path_variables", Map.of("HDR", List.of(dir.resolve("hdr").toString())))));
        List<String> opts = List.of("-APCS", "3/32bit/fpe3", "-cpu", "6Z");
        write(settings.resolve("A/objasm.json"), Json.toJson(List.of(Map.of("file", dir.resolve("a/s/Main").toString(),
                "directory", dir.resolve("a").toString(), "includes", List.of(),
                "predefines", List.of("FeatureA SETL {TRUE}"), "options", opts))));
        write(settings.resolve("B/objasm.json"), Json.toJson(List.of(Map.of("file", dir.resolve("b/s/Other").toString(),
                "directory", dir.resolve("b").toString(), "includes", List.of(), "predefines", List.of(), "options", opts))));

        LspTests t = new LspTests();
        group("initialize");
        Map<String, Object> init = Server.obj(t.request("initialize", Map.of("capabilities", Map.of(),
                "initializationOptions", Map.of("settings", settings.toString()))));
        check("capabilities", Boolean.TRUE.equals(Server.obj(init.get("capabilities")).get("hoverProvider")), init);
        t.notify("initialized", Map.of());
        t.awaitIdle();

        String main = t.open(dir.resolve("a/s/Main"), MAIN);
        String swis = t.open(dir.resolve("hdr/SWIs"), SWIS);
        String macros = t.open(dir.resolve("hdr/Macros"), MACROS);
        String other = t.open(dir.resolve("b/s/Other"), OTHER);

        group("definition");
        int xl = line(MAIN, "XOS_WriteC"), xc = col(MAIN, "XOS_WriteC", "XOS_WriteC") + 2;
        List<Map<String, Object>> d = list(t.request("textDocument/definition", at(main, xl, xc)));
        check("two locations", d.size() == 2, d);
        check("first the AddSWI call, at WriteC", d.size() == 2 && d.get(0).get("uri").equals(swis)
                && startLine(d.get(0)) == line(SWIS, "AddSWI  WriteC") && startCol(d.get(0)) == col(SWIS, "AddSWI  WriteC", "WriteC"), d);
        check("then the macro body", d.size() == 2 && d.get(1).get("uri").equals(macros)
                && startLine(d.get(1)) == line(MACROS, "X$SWIClass"), d);
        d = list(t.request("textDocument/definition", at(main, line(MAIN, "%BT01"), col(MAIN, "%BT01", "01"))));
        check("local label", d.size() == 1 && startLine(d.get(0)) == line(MAIN, "01      MOV"), d);
        d = list(t.request("textDocument/definition", at(main, 2, 18)));
        check("GET opens the file", d.size() == 1 && d.get(0).get("uri").equals(swis), d);
        d = list(t.request("textDocument/definition", at(main, line(MAIN, "Twice   a"), 9)));
        check("macro", d.size() == 1 && d.get(0).get("uri").equals(macros) && startLine(d.get(0)) == line(MACROS, "Twice   $x"), d);

        group("variable assignments");
        {
            // A variable: its GBLA is its declaration, not its definition; its SETAs come from a command.
            int bl = line(SWIS, "Both    *       Count");
            Map<String, Object> pos = at(swis, bl, col(SWIS, "Both    *       Count", "Count") + 1);
            List<Map<String, Object>> vdef = list(t.request("textDocument/definition", pos));
            check("no definition", vdef.isEmpty(), vdef);
            List<Map<String, Object>> vdecl = list(t.request("textDocument/declaration", pos));
            check("the declaration", vdecl.size() == 1 && startLine(vdecl.get(0)) == line(SWIS, "GBLA    Count"), vdecl);
            Map<String, Object> position = Server.obj(pos.get("position"));
            List<Map<String, Object>> sets = list(t.request("workspace/executeCommand", Map.of("command", "riscos.assignments",
                    "arguments", List.of(swis, position.get("line"), position.get("character")))));
            List<Integer> lines = new ArrayList<>();
            for (Map<String, Object> x : sets) lines.add(startLine(x));
            check("the assignments, in every configuration", lines.equals(List.of(line(SWIS, "Count   SETA    1"),
                    line(SWIS, "Count   SETA    2"))), lines);
            // A constant isn't a variable: its definition, as before.
            List<Map<String, Object>> l = list(t.request("textDocument/definition", at(swis, bl, 1)));
            check("a constant: its definition", l.size() == 1 && startLine(l.get(0)) == bl, l);
        }

        group("hover");
        {
            String hx = hoverText(t.request("textDocument/hover", at(main, line(MAIN, "Twice   a"), col(MAIN, "Twice   a", "Twice") + 1)));
            check("a macro call shows its expansion", hx.contains("Expansion") && hx.contains("MOV     r1, r1"), hx);
            check("with the skipped line marked", hx.contains("✗") && hx.lines().anyMatch(l -> l.startsWith("✗") && l.contains("MOV     r0, r0")), hx);
            check("and the arguments substituted", hx.contains("\"a\" = \"never\""), hx);
            String hw = hoverText(t.request("textDocument/hover", at(main, line(MAIN, "Wrap    7"), 10)));
            int os = hw.indexOf("Output"), es = hw.indexOf("Expansion");
            String out = os < 0 || es < os ? "" : hw.substring(os, es);
            check("the output comes first, with inner macros expanded",
                    out.contains("MOV     r1, r1") && out.contains("DCD     &7") && !out.contains("Twice") && !out.contains("r0, r0"), hw);
            check("then the expansion as written", hw.substring(Math.max(0, es)).contains("Twice   7"), hw);
            String sl = "X$SWIClass._$SWIName * $SWIClass._$SWIName + Auto_Error_SWI_bit";
            String hs = hoverText(t.request("textDocument/hover", at(macros, line(MACROS, sl), sl.indexOf("SWIName") + 2)));
            check("a $ use shows the line after substitution, per expansion", hs.contains("each way it is assembled") && hs.contains("XOS_WriteC * OS_WriteC + Auto_Error_SWI_bit"), hs);
            String hn = hoverText(t.request("textDocument/hover", at(macros, line(MACROS, sl), sl.indexOf("Auto_Error") + 2)));
            check("but not elsewhere on the line", !hn.contains("substitution"), hn);
            check("conditionals not marked", hx.lines().noneMatch(l -> l.startsWith("✗") && l.trim().endsWith("]")), hx);
        }
        String h = hoverText(t.request("textDocument/hover", at(main, xl, xc)));
        check("kind and value", h.contains("XOS_WriteC: constant = &20000 (131072)"), h);
        check("defined by", h.contains("by `AddSWI WriteC`"), h);
        h = hoverText(t.request("textDocument/hover", at(swis, line(SWIS, "FeatureVal * 1"), 2)));
        check("only the definition assembled there", h.contains("constant = &1") && !h.contains("&2"), h);
        h = hoverText(t.request("textDocument/hover", at(swis, line(SWIS, "Both"), 2)));
        check("values that differ between entry points", h.contains("constant = &1   (1 entry point)")
                && h.contains("constant = &2   (1 entry point)"), h);
        h = hoverText(t.request("textDocument/hover", at(swis, line(SWIS, "AddSWI  Byte"), 9)));
        check("macro call: what it defines", h.contains("OS_Byte: constant = &6") && h.contains("XOS_Byte"), h);
        h = hoverText(t.request("textDocument/hover", at(swis, line(SWIS, "AddSWI  WriteS"), 17)));
        check("macro argument", h.contains("OS_WriteS: storage-map field = &1") && h.contains("XOS_WriteS"), h);

        group("references");
        List<Map<String, Object>> r = list(t.request("textDocument/references",
                Map.of("textDocument", Map.of("uri", other), "position", Map.of("line", 3, "character", 18),
                        "context", Map.of("includeDeclaration", true))));
        String swisUri = swis;
        check("across components, with the declaration", r.stream().anyMatch(x -> x.get("uri").equals(other))
                && r.stream().anyMatch(x -> x.get("uri").equals(swisUri) && startLine(x) == line(SWIS, "AddSWI  WriteC")), r);
        List<Map<String, Object>> hl = list(t.request("textDocument/documentHighlight", at(main, line(MAIN, "Start   ROUT"), 1)));
        check("highlights: definition and use", hl.size() == 2, hl);

        group("completion");
        Object c = t.request("textDocument/completion", at(main, xl, col(MAIN, "XOS_WriteC", "XOS_WriteC") + 6));
        check("symbols by prefix", labels(c).equals(List.of("XOS_WriteC", "XOS_WriteS")), labels(c));
        c = t.request("textDocument/completion", at(main, line(MAIN, "%BT01"), col(MAIN, "%BT01", "01")));
        check("local labels after %", labels(c).equals(List.of("01")), labels(c));
        c = t.request("textDocument/completion", at(main, xl, 8));
        check("directives and macros in the operation field", labels(c).contains("AddSWI") && labels(c).contains("Twice")
                && labels(c).contains("MACRO") && !labels(c).contains("XOS_WriteC"), labels(c));
        c = t.request("textDocument/completion", at(macros, line(MACROS, "\"$x\""), col(MACROS, "\"$x\"", "$x") + 1));
        check("macro parameters after $", labels(c).contains("x"), labels(c));
        c = t.request("textDocument/completion", at(main, line(MAIN, "Start   ROUT"), 3));
        check("nothing while writing a label", labels(c).isEmpty(), labels(c));
        String sysUri = dir.resolve("prog.bas").toUri().toString();
        t.notify("textDocument/didOpen", Map.of("textDocument", Map.of("uri", sysUri, "languageId", "bbcbasic",
                "version", 1, "text", "SYS \"XOS_W\nPRINT \"OS_\n")));
        List<Map<String, Object>> sc = list(t.request("textDocument/completion", at(sysUri, 1, 10)));
        check("BASIC: SWI names inside SYS \"...", sc.stream().map(x -> x.get("label")).sorted().toList()
                .equals(List.of("XOS_WriteC", "XOS_WriteS")) && "SWI &20000".equals(sc.stream()
                .filter(x -> x.get("label").equals("XOS_WriteC")).findFirst().get().get("detail")), sc);
        sc = list(t.request("textDocument/completion", at(sysUri, 2, 10)));
        check("BASIC: but not in other strings", sc.isEmpty(), sc);
        {
            List<String[]> cm = new ArrayList<>();
            Server.cmhgSwis(String.join("\n", "; A module", "#include \"Global/Services.h\"",
                    "swi-chunk-base-number:   0x59000",
                    "swi-handler-code:        swi_handler",
                    "swi-decoding-table:      SDIO, Initialise, Control/ctrl_handler ; a comment",
                    "                         Enumerate", "date-string: 1 Jan 2026"), cm);
            List<String> got = new ArrayList<>();
            for (String[] e : cm) got.add(String.join(" ", e));
            check("CMHG: the decoding table, numbered from the chunk base", got.equals(List.of(
                    "SDIO Initialise &59000 &79000", "SDIO Control &59001 &79001", "SDIO Enumerate &59002 &79002")), got);
        }

        group("symbols and folding");
        {
            List<Map<String, Object>> ms = list(t.request("textDocument/documentSymbol", Map.of("textDocument", Map.of("uri", main))));
            check("outline: the file it GETs", ms.stream().anyMatch(x -> "GET".equals(x.get("detail"))
                    && Integer.valueOf(1).equals(((Number) x.get("kind")).intValue())
                    && startLine(Map.of("range", x.get("selectionRange"))) == 2), ms);
        }
        List<Map<String, Object>> s = list(t.request("textDocument/documentSymbol", Map.of("textDocument", Map.of("uri", swis))));
        check("OS_WriteC at its AddSWI", s.stream().anyMatch(x -> "OS_WriteC".equals(x.get("name"))
                && startLine(Map.of("range", x.get("selectionRange"))) == line(SWIS, "AddSWI  WriteC")), s);
        s = list(t.request("workspace/symbol", Map.of("query", "writes")));
        check("workspace symbols", s.size() == 2, s);
        List<Map<String, Object>> f = list(t.request("textDocument/foldingRange", Map.of("textDocument", Map.of("uri", main))));
        int fi = line(MAIN, "[ FeatureVal") - 1;
        check("IF fold, up to the line before ]", f.stream().anyMatch(x -> ((Number) x.get("startLine")).intValue() == fi
                && ((Number) x.get("endLine")).intValue() == fi + 1), f);
        check("ROUT fold", f.stream().anyMatch(x -> ((Number) x.get("startLine")).intValue() == line(MAIN, "Start   ROUT") - 1), f);
        int lastLine = MAIN.split("\n", -1).length - 1;   // the text ends with a newline
        check("last ROUT folds to the end of the file", f.stream().anyMatch(x ->
                ((Number) x.get("startLine")).intValue() == line(MAIN, "Next    ROUT") - 1
                && ((Number) x.get("endLine")).intValue() == lastLine - 1), f);

        group("folding a file no entry point reads");
        String unread = String.join("\n",
                "Alpha   ROUT",
                "  [ Debug",
                "        MOV     r0, r0",
                "  |",
                "        MOV     r1, r1",
                "  ]",
                "        MACRO",
                "        Thing",
                "        NOP",
                "        MEND",
                "Beta    ROUT",
                "        MOV     r2, r2",
                "        END", "");
        String unreadUri = t.open(dir.resolve("hdr/Unread"), unread);
        List<Map<String, Object>> uf = list(t.request("textDocument/foldingRange", Map.of("textDocument", Map.of("uri", unreadUri))));
        java.util.function.BiPredicate<Integer, Integer> has = (a, b) -> uf.stream().anyMatch(x ->
                ((Number) x.get("startLine")).intValue() == a - 1 && ((Number) x.get("endLine")).intValue() == b - 1);
        check("IF clause to the line before |", has.test(2, 3), uf);
        check("ELSE clause to the line before ]", has.test(4, 5), uf);
        check("MACRO to MEND", has.test(7, 10), uf);
        check("routine to the next ROUT", has.test(1, 10), uf);
        check("last routine to the end", has.test(11, 13), uf);

        group("shading");
        t.notes.clear();
        t.notify("textDocument/didClose", Map.of("textDocument", Map.of("uri", swis)));
        swis = t.open(dir.resolve("hdr/SWIs"), SWIS);
        Map<String, Object> ir = Server.obj(t.awaitNote("riscos/inactiveRegions", swis).get("params"));
        int nl = line(SWIS, "Never");
        check("a header: shaded only where every entry point skips it", lines(ir.get("regions")).equals(List.of(List.of(nl, nl))), ir);
        t.notify("textDocument/didClose", Map.of("textDocument", Map.of("uri", main)));
        main = t.open(dir.resolve("a/s/Main"), MAIN);
        ir = Server.obj(t.awaitNote("riscos/inactiveRegions", main).get("params"));
        int sl = line(MAIN, "shaded in A");
        check("one entry point's file", lines(ir.get("regions")).equals(List.of(List.of(sl, sl))), ir);
        t.notify("textDocument/didClose", Map.of("textDocument", Map.of("uri", macros)));
        macros = t.open(dir.resolve("hdr/Macros"), MACROS);
        ir = Server.obj(t.awaitNote("riscos/inactiveRegions", macros).get("params"));
        int ml = line(MACROS, "skipped in every expansion");
        check("macro bodies: not shaded", lines(ir.get("regions")).isEmpty(), ir);
        check("macro bodies: skipped in every expansion", lines(ir.get("skippedInEveryExpansion")).equals(List.of(List.of(ml, ml))), ir);

        group("diagnostics");
        Map<String, Object> diag = Server.obj(t.awaitNote("textDocument/publishDiagnostics", main).get("params"));
        List<Map<String, Object>> dl = list(diag.get("diagnostics"));
        check("unresolved GET", dl.size() == 1 && startLine(dl.get(0)) == line(MAIN, "Hdr:Missing")
                && "get-unresolved".equals(dl.get(0).get("code")), dl);

        group("unsaved edits");
        t.notes.clear();
        int gl = line(MAIN, "Hdr:Missing");
        // Replace the bad GET with a new label and a branch to it: two incremental changes.
        t.notify("textDocument/didChange", Map.of("textDocument", Map.of("uri", main, "version", 2), "contentChanges", List.of(
                Map.of("range", Server.range(gl - 1, 0, gl, 0), "text", ""),
                Map.of("range", Server.range(gl - 1, 0, gl - 1, 0), "text", "        B       Fresh\nFresh   MOV     r0, r0\n"))));
        diag = Server.obj(t.awaitNote("textDocument/publishDiagnostics", main).get("params"));
        check("diagnostics cleared", list(diag.get("diagnostics")).isEmpty(), diag);
        t.awaitIdle();
        d = list(t.request("textDocument/definition", at(main, gl, 18)));
        check("new label from the editor's text", d.size() == 1 && startLine(d.get(0)) == gl + 1, d);
        check("the file on disk is untouched", Files.readString(dir.resolve("a/s/Main")).equals(MAIN), "");

        t.notes.clear();
        int wl = line(SWIS, "AddSWI  WriteS");
        t.notify("textDocument/didChange", Map.of("textDocument", Map.of("uri", swis, "version", 2), "contentChanges", List.of(
                Map.of("range", Server.range(wl - 1, 21, wl - 1, 22), "text", "X"))));
        t.awaitIdle();
        s = list(t.request("workspace/symbol", Map.of("query", "WriteX")));
        check("a header edit re-runs every entry point that reads it", s.size() == 2, s);
        r = list(t.request("textDocument/references", Map.of("textDocument", Map.of("uri", swis),
                "position", Map.of("line", wl - 1, "character", 17), "context", Map.of("includeDeclaration", false))));
        // The only use is in the expansion, which defines XOS_WriteX from it: shown at the call.
        check("a use inside an expansion is shown at the call", r.size() == 1 && startLine(r.get(0)) == wl
                && startCol(r.get(0)) == 16, r);
        t.notes.clear();
        t.notify("textDocument/didClose", Map.of("textDocument", Map.of("uri", swis)));
        t.awaitIdle();
        s = list(t.request("workspace/symbol", Map.of("query", "WriteX")));
        check("closing without saving goes back to the disk's text", s.isEmpty(), s);

        group("shutdown");
        t.request("shutdown", null);
        t.notify("exit", null);
        t.serverThread.join(5000);
        check("exit code", t.exitCode == 0, t.exitCode);

        group("opened by its real path");
        // The engine reads c/objs/Main.s, a link to c/s/Main, as objasm does
        // through a link farm; Eclipse opens the file by its real path.
        write(dir.resolve("c/s/Main"), MAIN);
        Files.createDirectories(dir.resolve("c/objs"));
        Files.createSymbolicLink(dir.resolve("c/objs/Main.s"), Path.of("../s/Main"));
        Path linked = dir.resolve("linked");
        write(linked.resolve("environment.json"), Json.toJson(Map.of(
                "set_by_env_file", Map.of(), "path_variables", Map.of("HDR", List.of(dir.resolve("hdr").toString())))));
        write(linked.resolve("C/objasm.json"), Json.toJson(List.of(Map.of("file", dir.resolve("c/objs/Main.s").toString(),
                "directory", dir.resolve("c").toString(), "includes", List.of(),
                "predefines", List.of("FeatureA SETL {TRUE}"), "options", opts))));
        LspTests l = new LspTests();
        l.request("initialize", Map.of("capabilities", Map.of(), "initializationOptions", Map.of("settings", linked.toString())));
        l.notify("initialized", Map.of());
        l.awaitIdle();
        Map<String, Object> sf = l.await(m -> "riscos/sourceFiles".equals(m.get("method")));
        List<?> sfFiles = (List<?>) Server.obj(sf.get("params")).get("files");
        check("source files: the link and the real path", sfFiles.contains(dir.resolve("c/objs/Main.s").toString())
                && sfFiles.contains(dir.resolve("c/s/Main").toString()) && sfFiles.contains(dir.resolve("hdr/SWIs").toString()), sfFiles);
        String real = l.open(dir.resolve("c/s/Main"), MAIN);
        String lh = hoverText(l.request("textDocument/hover", at(real, xl, xc)));
        check("hover", lh.contains("XOS_WriteC: constant = &20000"), lh);
        List<Map<String, Object>> ld = list(l.request("textDocument/definition", at(real, xl, xc)));
        check("definition", ld.size() == 2, ld);
        Map<String, Object> lr = l.await(m -> "riscos/inactiveRegions".equals(m.get("method")));
        check("shading sent for the URI that was opened",
                real.equals(Server.obj(Server.obj(lr.get("params")).get("textDocument")).get("uri")), lr);
        String edited = MAIN.replace("FeatureA", "FeatureZ");
        l.notify("textDocument/didChange", Map.of("textDocument", Map.of("uri", real, "version", 2),
                "contentChanges", List.of(Map.of("text", edited))));
        Map<String, Object> after = l.await(m -> "riscos/inactiveRegions".equals(m.get("method"))
                && Server.obj(Server.obj(m.get("params")).get("textDocument")).get("version") instanceof Number v
                && v.intValue() == 2);
        check("unsaved edits reach the engine", after != null, after);
        l.request("shutdown", null);
        l.notify("exit", null);
        l.serverThread.join(5000);

        group("URI styles");
        for (String style : List.of("plain", "real", "links")) {
            LspTests u = new LspTests();
            u.request("initialize", Map.of("capabilities", Map.of(),
                    "initializationOptions", Map.of("settings", linked.toString(), "uriStyle", style)));
            u.notify("initialized", Map.of());
            u.awaitIdle();
            String hdr = u.open(dir.resolve("hdr/SWIs"), SWIS);
            List<Map<String, Object>> refs = list(u.request("textDocument/references", Map.of(
                    "textDocument", Map.of("uri", hdr),
                    "position", Map.of("line", line(SWIS, "AddSWI  WriteC") - 1, "character", col(SWIS, "AddSWI  WriteC", "WriteC")),
                    "context", Map.of("includeDeclaration", true))));
            java.util.Set<String> uris = new java.util.TreeSet<>();
            for (Map<String, Object> x : refs) uris.add((String) x.get("uri"));
            String link = dir.resolve("c/objs/Main.s").toString(), realPath = dir.resolve("c/s/Main").toString();
            boolean ok = switch (style) {
                case "plain" -> uris.contains(Path.of(link).toUri().toString());
                case "real" -> uris.contains(Path.of(realPath).toUri().toString());
                default -> uris.contains(Server.LINK_SCHEME + ":" + link);
            };
            check(style + ": the linked file's uses", ok, uris);
            if (style.equals("links")) {
                // Even with the linked file open, by its link, its locations use the scheme.
                u.open(dir.resolve("c/objs/Main.s"), MAIN);
                List<Map<String, Object>> again = list(u.request("textDocument/references", Map.of(
                        "textDocument", Map.of("uri", hdr),
                        "position", Map.of("line", line(SWIS, "AddSWI  WriteC") - 1, "character", col(SWIS, "AddSWI  WriteC", "WriteC")),
                        "context", Map.of("includeDeclaration", true))));
                check("links: an open linked file too", again.stream().anyMatch(x ->
                        (Server.LINK_SCHEME + ":" + link).equals(x.get("uri"))), again);
            }
            u.request("shutdown", null);
            u.notify("exit", null);
            u.serverThread.join(5000);
        }

        group("BBC BASIC");
        {
            String prog = String.join("\n",
                    "REM >Test",
                    "PROCinit",
                    "PRINT FNtwice(total%)",
                    "PROCmissing:PROCelsewhere",
                    "END",
                    "",
                    "REM Sets total% up",
                    "DEF PROCinit",
                    "LOCAL i%",
                    "total%=0",
                    "FOR i%=1 TO 3",
                    "  total%+=i%",
                    "NEXT",
                    "IF total%>5 THEN",
                    "  PRINT \"big\"",
                    "ELSE",
                    "  PRINT \"small\"",
                    "ENDIF",
                    "ENDPROC",
                    "",
                    "DEF FNtwice(n)",
                    "=n*2", "");
            // BASIC works without any assembler settings.
            LspTests bt = new LspTests();
            bt.request("initialize", Map.of("capabilities", Map.of()));
            bt.notify("initialized", Map.of());
            Path bas = dir.resolve("b/Test.bas");
            write(bas, prog);
            write(dir.resolve("b/Lib.bas"), "DEF PROCelsewhere\nENDPROC\n");
            String bu = bas.toUri().toString();
            bt.notify("textDocument/didOpen", Map.of("textDocument", Map.of("uri", bu, "languageId", "bbcbasic", "version", 1, "text", prog)));
            Map<String, Object> diags = bt.await(m -> "textDocument/publishDiagnostics".equals(m.get("method"))
                    && bu.equals(Server.obj(m.get("params")).get("uri")));
            List<Map<String, Object>> ds = list(Server.obj(diags.get("params")).get("diagnostics"));
            check("BASIC: an undefined procedure is reported", ds.size() == 1
                    && String.valueOf(ds.get(0).get("message")).contains("PROCmissing"), ds);
            String eh = hoverText(bt.request("textDocument/hover", at(bu, 4, 15)));
            check("BASIC: but not one defined in a file beside it, which hover names", eh.contains("Lib.bas"), eh);
            List<Map<String, Object>> bd = list(bt.request("textDocument/definition", at(bu, 2, 2)));
            check("BASIC: PROCinit goes to its DEF", bd.size() == 1 && startLine(bd.get(0)) == 8, bd);
            String spelt = "file:" + bas.toAbsolutePath();
            check("BASIC: the same file by another spelling of its URI",
                    list(bt.request("textDocument/definition", at(spelt, 2, 2))).size() == 1, spelt);
            bd = list(bt.request("textDocument/definition", at(bu, 3, 15)));
            check("BASIC: total% goes to where it's assigned", bd.size() == 1 && startLine(bd.get(0)) == 10, bd);
            bd = list(bt.request("textDocument/declaration", at(bu, 22, 1)));
            check("BASIC: n is declared by its parameter", bd.size() == 1 && startLine(bd.get(0)) == 21, bd);
            bd = list(bt.request("textDocument/definition", at(bu, 22, 1)));
            check("BASIC: so has no definition", bd.isEmpty(), bd);
            // i%: declared by LOCAL, assigned by FOR.
            bd = list(bt.request("textDocument/declaration", at(bu, 11, 5)));
            check("BASIC: LOCAL declares", bd.size() == 1 && startLine(bd.get(0)) == 9, bd);
            Map<String, Object> ip = Server.obj(at(bu, 11, 5).get("position"));
            bd = list(bt.request("workspace/executeCommand", Map.of("command", "riscos.assignments",
                    "arguments", List.of(bu, ip.get("line"), ip.get("character")))));
            check("BASIC: FOR assigns", bd.size() == 1 && startLine(bd.get(0)) == 11, bd);
            bd = list(bt.request("workspace/executeCommand", Map.of("command", "riscos.assignments",
                    "arguments", List.of(bu, 9, 1))));
            check("BASIC: total%'s assignments", bd.size() == 1 && startLine(bd.get(0)) == 10, bd);
            String bh = hoverText(bt.request("textDocument/hover", at(bu, 2, 2)));
            check("BASIC: hover on a call shows the DEF and its comment", bh.contains("DEF PROCinit")
                    && bh.contains("Sets total% up"), bh);
            bh = hoverText(bt.request("textDocument/hover", at(bu, 10, 1)));
            check("BASIC: hover on a variable assigned once", bh.contains("integer variable") && bh.contains("global")
                    && bh.contains("Assigned once"), bh);
            bh = hoverText(bt.request("textDocument/hover", at(bu, 12, 11)));
            check("BASIC: hover on a local", bh.contains("local to `PROCinit`"), bh);
            List<Map<String, Object>> br = list(bt.request("textDocument/references", Map.of("textDocument", Map.of("uri", bu),
                    "position", Map.of("line", 9, "character", 1), "context", Map.of("includeDeclaration", true))));
            check("BASIC: references to total%", br.size() == 4, br);
            List<Map<String, Object>> bf = list(bt.request("textDocument/foldingRange", Map.of("textDocument", Map.of("uri", bu))));
            check("BASIC: folds for the routines, FOR and IF clauses", bf.size() == 5, bf);
            List<Map<String, Object>> bs = list(bt.request("textDocument/documentSymbol", Map.of("textDocument", Map.of("uri", bu))));
            check("BASIC: outline", bs.size() == 2 && bs.get(0).get("name").equals("PROCinit"), bs);
            List<Map<String, Object>> bc = list(bt.request("textDocument/completion", Map.of("textDocument", Map.of("uri", bu),
                    "position", Map.of("line", 1, "character", 5))));
            check("BASIC: completion after PROC", bc.stream().anyMatch(x -> "PROCinit".equals(x.get("label"))), bc);
            Path closed = bas.resolveSibling("closed.bas");
            write(closed, "PROCa\nEND\nDEF PROCa\nENDPROC\nDEF FNb=1\n");
            List<Map<String, Object>> cs = list(bt.request("workspace/executeCommand", Map.of("command", "riscos.basicSymbols",
                    "arguments", List.of(closed.toUri().toString()))));
            check("BASIC: symbols of a file that isn't open", cs.stream().map(x -> x.get("name")).toList()
                    .equals(List.of("PROCa", "FNb")), cs);
            cs = list(bt.request("workspace/executeCommand", Map.of("command", "riscos.basicSymbols", "arguments", List.of(bu))));
            check("BASIC: and of one that is", cs.size() == 2, cs);
            List<Map<String, Object>> ws = list(bt.request("workspace/symbol", Map.of("query", "twice")));
            check("BASIC: workspace symbols", ws.stream().anyMatch(x -> "FNtwice".equals(x.get("name"))), ws);
            bt.request("shutdown", null);
            bt.notify("exit", null);
            bt.serverThread.join(5000);
        }

        group("assembler and C");
        {
            String asm = String.join("\n",
                    "        AREA    |Asm$$Code|, CODE, READONLY",
                    "        EXPORT  asm_fn",
                    "        IMPORT  c_fn",
                    "        IMPORT  lib_fn",
                    "asm_fn  BL      c_fn",
                    "        BL      lib_fn",
                    "        MOV     pc, lr",
                    "        END", "");
            String mainC = String.join("\n",
                    "#include \"lib.h\"",
                    "extern int asm_fn(void);",
                    "int c_fn(void) { return asm_fn() + lib_fn(); }",
                    "static int hidden(void) { return 0; }",
                    "int counter = 0;", "");
            write(dir.resolve("x/s/Asm"), asm);
            write(dir.resolve("x/c/main.c"), mainC);
            write(dir.resolve("lib/h/lib.h"), "/* the library */\nint lib_fn(void);\n");
            write(dir.resolve("lib/c/lib.c"), "#include \"lib.h\"\nint lib_fn(void)\n{\n  return 1;\n}\n");
            Path sx = dir.resolve("settings-x");
            write(sx.resolve("environment.json"), Files.readString(settings.resolve("environment.json")));
            write(sx.resolve("X/objasm.json"), Json.toJson(List.of(Map.of("file", dir.resolve("x/s/Asm").toString(),
                    "directory", dir.resolve("x").toString(), "includes", List.of(), "predefines", List.of(), "options", opts))));
            write(sx.resolve("X/compile_commands.json"), Json.toJson(List.of(Map.of("directory", dir.resolve("x/c").toString(),
                    "file", dir.resolve("x/c/main.c").toString(),
                    "arguments", List.of("clang", "-c", "-I", dir.resolve("lib/h").toString(), "main.c")))));
            write(sx.resolve("Lib/compile_commands.json"), Json.toJson(List.of(Map.of("directory", dir.resolve("lib/c").toString(),
                    "file", dir.resolve("lib/c/lib.c").toString(),
                    "arguments", List.of("clang", "-c", "-I", dir.resolve("lib/h").toString(), "lib.c")))));
            write(sx.resolve("summary.json"), "{\"X\": {\"uses\": [\"Lib\"]}, \"Lib\": {\"library\": true, \"uses\": []}}");
            LspTests x = new LspTests();
            Map<String, Object> xi = Server.obj(x.request("initialize", Map.of("capabilities", Map.of(),
                    "initializationOptions", Map.of("settings", sx.toString()))));
            check("asm/C: declarations offered", Boolean.TRUE.equals(Server.obj(xi.get("capabilities")).get("declarationProvider")), xi);
            x.notify("initialized", Map.of());
            x.awaitIdle();
            String au = x.open(dir.resolve("x/s/Asm"), asm);
            String mu = dir.resolve("x/c/main.c").toUri().toString(), lu = dir.resolve("lib/c/lib.c").toUri().toString(),
                    hu = dir.resolve("lib/h/lib.h").toUri().toString();
            List<Map<String, Object>> xd = list(x.request("textDocument/definition", at(au, 5, 17)));
List<Map<String, Object>> xs = list(x.request("textDocument/documentSymbol", Map.of("textDocument", Map.of("uri", au))));
            check("outline: an EXPORTed label says so", xs.stream().anyMatch(o -> "asm_fn".equals(o.get("name"))
                    && String.valueOf(o.get("detail")).startsWith("exported ")), xs);
                        check("asm/C: an IMPORTed function goes to its C definition", xd.size() == 1 && mu.equals(xd.get(0).get("uri"))
                    && startLine(xd.get(0)) == 3, xd);
            xd = list(x.request("textDocument/definition", at(au, 6, 17)));
            check("asm/C: or to the library's", xd.size() == 1 && lu.equals(xd.get(0).get("uri")) && startLine(xd.get(0)) == 2, xd);
            xd = list(x.request("textDocument/declaration", at(au, 6, 17)));
            check("asm/C: declared by the IMPORT and the header's prototype", xd.size() == 2
                    && xd.stream().anyMatch(loc -> au.equals(loc.get("uri")) && startLine(loc) == 4)
                    && xd.stream().anyMatch(loc -> hu.equals(loc.get("uri")) && startLine(loc) == 2), xd);
            List<Map<String, Object>> xr = list(x.request("textDocument/references", Map.of("textDocument", Map.of("uri", au),
                    "position", Map.of("line", 4, "character", 2), "context", Map.of("includeDeclaration", true))));
            check("asm/C: references to an EXPORTed label include C's", xr.stream().filter(loc -> mu.equals(loc.get("uri"))).count() == 2, xr);
            List<Map<String, Object>> xe = list(x.request("workspace/executeCommand", Map.of("command", "riscos.asmDefinitions",
                    "arguments", List.of("asm_fn", mu))));
            check("asm/C: from C, the assembler definition", xe.size() == 1 && au.equals(xe.get(0).get("uri")) && startLine(xe.get(0)) == 5, xe);
            xe = list(x.request("workspace/executeCommand", Map.of("command", "riscos.asmReferences",
                    "arguments", List.of("lib_fn", lu))));
            check("asm/C: from a library's C, the assembler that uses it", xe.stream().anyMatch(loc -> au.equals(loc.get("uri"))
                    && startLine(loc) == 6), xe);
            xd = list(x.request("workspace/executeCommand", Map.of("command", "riscos.asmDefinitions",
                    "arguments", List.of("hidden", mu))));
            check("asm/C: nothing for what assembler doesn't define", xd.isEmpty(), xd);
            x.request("shutdown", null);
            x.notify("exit", null);
            x.serverThread.join(5000);
            CSource cs = CSource.scan(mainC);
            check("C: static functions aren't external definitions", !cs.definitions.containsKey("hidden")
                    && cs.definitions.containsKey("c_fn") && cs.definitions.containsKey("counter"), cs.definitions.keySet());
            check("C: extern and prototypes are declarations", cs.declarations.containsKey("asm_fn"), cs.declarations.keySet());
        }

        group("another configuration");
        {
            // The same tree with only B: what only A read goes.
            Path onlyB = dir.resolve("settings-b");
            write(onlyB.resolve("environment.json"), Files.readString(settings.resolve("environment.json")));
            write(onlyB.resolve("B/objasm.json"), Files.readString(settings.resolve("B/objasm.json")));
            LspTests cfg = new LspTests();
            cfg.request("initialize", Map.of("capabilities", Map.of(), "initializationOptions", Map.of("settings", settings.toString())));
            cfg.notify("initialized", Map.of());
            cfg.awaitIdle();
            String cm = cfg.open(dir.resolve("a/s/Main"), MAIN);
            int tl = line(MAIN, "Twice   a");
            check("before: a definition in A", !list(cfg.request("textDocument/definition", at(cm, tl, 9))).isEmpty(), null);
            cfg.notify("workspace/didChangeConfiguration", Map.of("settings", Map.of("riscos", Map.of("settings", onlyB.toString()))));
            cfg.awaitIdle();
            check("after: A isn't indexed", list(cfg.request("textDocument/definition", at(cm, tl, 9))).isEmpty(), null);
            String co = cfg.open(dir.resolve("b/s/Other"), OTHER);
            check("B still is", !list(cfg.request("textDocument/documentSymbol", Map.of("textDocument", Map.of("uri", co)))).isEmpty(), null);
            cfg.request("shutdown", null);
            cfg.notify("exit", null);
            cfg.serverThread.join(5000);
        }

        group("asked while indexing");
        {
            // An editor open when Eclipse starts asks for its symbols and folding at once, and only once.
            LspTests early = new LspTests();
            early.request("initialize", Map.of("capabilities", Map.of(), "initializationOptions", Map.of("settings", settings.toString())));
            early.notify("initialized", Map.of());
            String em = early.open(dir.resolve("a/s/Main"), MAIN);
            List<Map<String, Object>> syms = list(early.request("textDocument/documentSymbol", Map.of("textDocument", Map.of("uri", em))));
            check("symbols wait for the file to be indexed", syms.stream().anyMatch(x -> "Start".equals(x.get("name"))), syms);
            List<Map<String, Object>> folds = list(early.request("textDocument/foldingRange", Map.of("textDocument", Map.of("uri", em))));
            check("and so does folding", !folds.isEmpty(), folds);
            early.request("shutdown", null);
            early.notify("exit", null);
            early.serverThread.join(5000);
        }

        group("two build trees");
        {
            // One settings directory per tree: here A's and B's, as if from two trees.
            Path onlyA = dir.resolve("settings-a"), onlyB = dir.resolve("settings-b");
            write(onlyA.resolve("environment.json"), Files.readString(settings.resolve("environment.json")));
            write(onlyA.resolve("A/objasm.json"), Files.readString(settings.resolve("A/objasm.json")));
            LspTests two = new LspTests();
            two.request("initialize", Map.of("capabilities", Map.of(), "initializationOptions",
                    Map.of("settings", List.of(onlyA.toString(), onlyB.toString()))));
            two.notify("initialized", Map.of());
            two.awaitIdle();
            String tm = two.open(dir.resolve("a/s/Main"), MAIN);
            int tl = line(MAIN, "Twice   a");
            check("the first tree is indexed", !list(two.request("textDocument/definition", at(tm, tl, 9))).isEmpty(), null);
            String to = two.open(dir.resolve("b/s/Other"), OTHER);
            check("and so is the second", !list(two.request("textDocument/documentSymbol",
                    Map.of("textDocument", Map.of("uri", to)))).isEmpty(), null);
            // A tree whose settings can't be read doesn't stop the others.
            two.notify("workspace/didChangeConfiguration", Map.of("settings", Map.of("riscos",
                    Map.of("settings", List.of(onlyA.toString(), dir.resolve("gone").toString())))));
            Map<String, Object> warned = two.await(m -> "window/showMessage".equals(m.get("method")));
            check("an unreadable tree is a warning", Integer.valueOf(2).equals(((Number) Server.obj(warned.get("params")).get("type")).intValue())
                    && String.valueOf(Server.obj(warned.get("params")).get("message")).contains("gone"), warned);
            two.awaitIdle();
            check("the readable one is still indexed", !list(two.request("textDocument/definition", at(tm, tl, 9))).isEmpty(), null);
            two.request("shutdown", null);
            two.notify("exit", null);
            two.serverThread.join(5000);
        }

        group("unreadable settings");
        LspTests u = new LspTests();
        Map<String, Object> ui = Server.obj(u.request("initialize", Map.of("capabilities", Map.of(),
                "initializationOptions", Map.of("settings", dir.resolve("no-such-dir").toString()))));
        check("still initializes", ui.containsKey("capabilities"), ui);
        u.notify("initialized", Map.of());
        Map<String, Object> shown = u.await(m -> "window/showMessage".equals(m.get("method")));
        check("tells the user", String.valueOf(Server.obj(shown.get("params")).get("message")).contains("no-such-dir"), shown);
        u.request("shutdown", null);
        u.notify("exit", null);
        u.serverThread.join(5000);
        check("keeps running until told to exit", u.exitCode == 0, u.exitCode);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    static void write(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.ISO_8859_1);
    }
}
