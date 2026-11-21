package com.piccolosystems.riscos.asmindex.lsp;

import com.piccolosystems.riscos.asmindex.expr.Value;
import com.piccolosystems.riscos.asmindex.lsp.EntryIndex.Get;
import com.piccolosystems.riscos.asmindex.lsp.EntryIndex.Local;
import com.piccolosystems.riscos.asmindex.lsp.EntryIndex.Mac;
import com.piccolosystems.riscos.asmindex.lsp.EntryIndex.Occ;
import com.piccolosystems.riscos.asmindex.lsp.EntryIndex.Sym;
import com.piccolosystems.riscos.asmindex.parse.Directive;
import com.piccolosystems.riscos.asmindex.run.Diagnostic;
import com.piccolosystems.riscos.asmindex.run.EntryPoint;
import com.piccolosystems.riscos.asmindex.run.Settings;
import com.piccolosystems.riscos.asmindex.source.SourceFile;
import com.piccolosystems.riscos.asmindex.sym.Site;
import com.piccolosystems.riscos.asmindex.sym.Symbol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A language server for objasm sources, speaking LSP (JSON-RPC over stdio)
 * with no dependencies. The settings come from buildscan's output directory,
 * given as {@code initializationOptions.settings} or {@code --settings DIR}.
 *
 * <p>Besides the standard requests it sends two notifications of its own:
 * <ul>
 * <li>{@code riscos/inactiveRegions}: {@code {textDocument: {uri, version},
 *     regions: Range[], skippedInEveryExpansion: Range[]}} for each open
 *     document. A line is in {@code regions} only if every entry point that
 *     reads the file skips it.</li>
 * <li>{@code riscos/status}: {@code {state: "indexing" | "idle", entryPoints, files}}.</li>
 * <li>{@code riscos/sourceFiles}: {@code {files: string[]}}, as indexing goes:
 *     each file read for the first time, by objasm's name for it and its real path.</li>
 * </ul>
 */
public final class Server {
    private static final int MAX_COMPLETIONS = 1000;
    private static final int MAX_LOCATIONS = 200;
    private static final int MAX_WORKSPACE_SYMBOLS = 500;

    private final Transport transport;
    private Settings settings;
    private Indexer indexer;
    private boolean shutdown;
    private boolean showInfo;
    private boolean clientProgress;
    private int nextRequestId;
    private String progressToken;
    /** Each build tree's RiscOS directory (or its entry points' common directory), longest first. */
    private List<Path> treeRoots = List.of();
    /** The settings of each tree, as read. */
    private List<Settings> perTree = List.of();
    /**
     * Whether the client has opened a document yet. Until then diagnostics are
     * held back: a client may still be setting up after {@code initialized}
     * (LSP4E installs its diagnostics handler separately), and diagnostics
     * sent too early are lost. They are all sent at the first didOpen.
     */
    private volatile boolean clientReady;
    /** Why the settings couldn't be read, if they couldn't. */
    private String settingsError;
    private final PrintStream log;

    /** Open documents by engine file name. */
    private final Map<String, Doc> docs = new java.util.concurrent.ConcurrentHashMap<>();
    /** Files we have sent non-empty diagnostics for. */
    private final Set<String> diagnosed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final class Doc {
        final String uri, file;
        String text;
        int version;
        boolean overlaid;     // the engine has been given this text
        Doc(String uri, String file, String text, int version) { this.uri = uri; this.file = file; this.text = text; this.version = version; }
    }

    public Server(InputStream in, OutputStream out, Settings settings, PrintStream log) {
        this.transport = new Transport(in, out);
        this.settings = settings;
        this.log = log;
    }

    /** {@code objasm-index lsp [--settings DIR] [--log FILE]} */
    public static void main(String[] args) throws IOException {
        Settings s = null;
        PrintStream log = System.err;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--settings")) s = Settings.fromDirectory(Path.of(args[++i]));
            else if (args[i].equals("--log")) log = new PrintStream(Files.newOutputStream(Path.of(args[++i])), true, StandardCharsets.UTF_8);
        }
        // stdout carries the protocol; nothing else may write to it.
        OutputStream out = System.out;
        System.setOut(System.err);
        int code = new Server(System.in, out, s, log).run();
        System.exit(code);
    }

    /** Serves until {@code exit} or end of input; returns the exit code. */
    public int run() throws IOException {
        for (;;) {
            Map<String, Object> m = transport.read();
            if (m == null) return shutdown ? 0 : 1;
            String method = (String) m.get("method");
            Object id = m.get("id");
            if (method == null) continue;                 // a response to one of our requests
            if (method.equals("exit")) {
                if (indexer != null) indexer.stop();
                return shutdown ? 0 : 1;
            }
            try {
                synchronized (this) {
                    if (id != null && defer(id, method, obj(m.get("params")))) continue;
                    Object result = handle(method, obj(m.get("params")), id != null);
                    if (id != null) transport.respond(id, result);
                }
            } catch (Unhandled u) {
                if (id != null) transport.error(id, -32601, "unhandled method " + method);
            } catch (RuntimeException | IOException e) {
                log.println("error in " + method + ": " + e);
                e.printStackTrace(log);
                if (id != null) transport.error(id, -32603, e.toString());
            }
        }
    }

    private static final class Unhandled extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    // =====================================================================
    // Dispatch
    // =====================================================================

    /** A request held until its file has been indexed. */
    private record Held(Object id, String method, Map<String, Object> params) {}

    private final List<Held> held = new ArrayList<>();

    /** Whether the configuration is being indexed for the first time: until then, files may not be indexed yet. */
    private boolean firstIndexing;

    /** Requests answered once, from what is indexed then: an empty answer isn't asked for again. */
    private static final Set<String> ONCE = Set.of("textDocument/documentSymbol", "textDocument/foldingRange");

    /**
     * Holds a request for the symbols or folding of an assembler file that
     * indexing hasn't reached yet, rather than answering it with nothing:
     * editors ask for those once, when they open, and an editor open when
     * Eclipse starts asks while the tree is still being indexed.
     */
    private boolean defer(Object id, String method, Map<String, Object> p) {
        if (!ONCE.contains(method) || !firstIndexing || basicUri(p) != null) return false;
        Object uri = obj(p.get("textDocument")).get("uri");
        if (!(uri instanceof String u)) return false;
        Model m = model();
        if (!m.readers(m.canonical(fileOf(u))).isEmpty()) return false;
        held.add(new Held(id, method, p));
        return true;
    }

    /** Answers the held requests whose files are now indexed, or all of them. */
    private synchronized void release(boolean all) {
        for (Held h : new ArrayList<>(held)) {
            Model m = model();
            String u = String.valueOf(obj(h.params.get("textDocument")).get("uri"));
            if (!all && m.readers(m.canonical(fileOf(u))).isEmpty()) continue;
            held.remove(h);
            try {
                transport.respond(h.id, handle(h.method, h.params, true));
            } catch (IOException | RuntimeException e) {
                log.println("error in held " + h.method + ": " + e);
                try { transport.error(h.id, -32603, e.toString()); } catch (IOException ignored) { /* client gone */ }
            }
        }
    }

    /** BBC BASIC files, each indexed on its own. */
    private final Basic basic = new Basic(this::swis);

    /** The request's document URI, if it is an open BASIC file. */
    private String basicUri(Map<String, Object> p) {
        Object u = obj(p.get("textDocument")).get("uri");
        return u instanceof String s ? basic.opened(s) : null;
    }

    private static boolean isBasic(Map<String, Object> td) {
        String lang = String.valueOf(td.get("languageId"));
        String uri = String.valueOf(td.get("uri"));
        return lang.equals("bbcbasic") || uri.toLowerCase(Locale.ROOT).endsWith(".bas") && !lang.equals("objasm");
    }

    private void publishBasic(String uri) throws IOException {
        transport.notify("textDocument/publishDiagnostics", Map.of("uri", uri, "diagnostics", basic.diagnostics(uri, showInfo)));
    }

    /** A request on a BASIC file, or null if it isn't one. */
    private Object handleBasic(String method, Map<String, Object> p) throws IOException {
        String uri = basicUri(p);
        if (uri == null) return null;
        Map<String, Object> pos = obj(p.get("position"));
        int line = intOf(pos.get("line")), col = intOf(pos.get("character"));
        switch (method) {
            case "textDocument/didChange": {
                Doc d = docs.get(fileOf(uri));
                if (d != null) {
                    didChangeText(d, p);
                    basic.update(uri, d.text);
                    publishBasic(uri);
                }
                return List.of();
            }
            case "textDocument/didClose":
                docs.remove(fileOf(uri));
                basic.close(uri);
                transport.notify("textDocument/publishDiagnostics", Map.of("uri", uri, "diagnostics", List.of()));
                return List.of();
            case "textDocument/definition": return basic.definition(uri, line, col);
            case "textDocument/declaration": return basic.declaration(uri, line, col);
            case "textDocument/references":
                return basic.references(uri, line, col, Boolean.TRUE.equals(obj(p.get("context")).get("includeDeclaration")));
            case "textDocument/documentHighlight": return basic.highlights(uri, line, col);
            case "textDocument/hover": {
                String h = basic.hover(uri, line, col);
                return h == null ? List.of() : Map.of("contents", Map.of("kind", "markdown", "value", h));
            }
            case "textDocument/completion": return basic.completion(uri, line, col);
            case "textDocument/documentSymbol": return basic.documentSymbols(uri);
            case "textDocument/foldingRange": return basic.foldingRanges(uri);
            default: return null;
        }
    }

    private Object handle(String method, Map<String, Object> p, boolean isRequest) throws IOException {
        if (method.startsWith("textDocument/") && !method.equals("textDocument/didOpen")) {
            Object r = handleBasic(method, p);
            if (r != null) {
                // An empty hover is no hover; notifications have no result.
                if (method.equals("textDocument/hover") && r instanceof List<?> l && l.isEmpty()) return null;
                return method.startsWith("textDocument/did") ? null : r;
            }
        }
        switch (method) {
            case "initialize": return initialize(p);
            case "initialized": initialized(); return null;
            case "shutdown": shutdown = true; return null;
            case "textDocument/didOpen": didOpen(p); return null;
            case "textDocument/didChange": didChange(p); return null;
            case "textDocument/didClose": didClose(p); return null;
            case "textDocument/didSave": return null;
            case "workspace/didChangeWatchedFiles": watchedFiles(p); return null;
            case "workspace/didChangeConfiguration": changeConfiguration(p); return null;
            case "$/cancelRequest": case "$/setTrace": return null;
            case "textDocument/definition": return definition(p);
            case "textDocument/declaration": return declaration(p);
            case "workspace/executeCommand": return executeCommand(p);
            case "textDocument/references": return references(p);
            case "textDocument/documentHighlight": return highlights(p);
            case "textDocument/hover": return hover(p);
            case "textDocument/completion": return completion(p);
            case "textDocument/documentSymbol": return documentSymbols(p);
            case "workspace/symbol": return workspaceSymbols(p);
            case "textDocument/foldingRange": return foldingRanges(p);
            default:
                if (!isRequest) return null;               // unknown notifications are ignored
                throw new Unhandled();
        }
    }

    /** The settings directories in use, one per build tree, as the client named them. */
    private List<String> settingsDirs = List.of();

    /** {@code "settings"}: one directory, or a list of them (one per build tree). */
    private static List<String> dirs(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof String s && !s.isBlank()) out.add(s);
        if (o instanceof List<?> l) for (Object x : l) if (x instanceof String s && !s.isBlank()) out.add(s);
        return out;
    }

    /**
     * Reads the settings of every build tree's active configuration, as many
     * as can be read. Those that can't be are reported (settingsError) and
     * left out.
     */
    private void readSettings(List<String> dirs) {
        settingsDirs = dirs;
        settingsError = null;
        settings = null;
        List<Settings> each = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        for (String dir : dirs) {
            try {
                each.add(Settings.fromDirectory(Path.of(dir)));
            } catch (IOException | RuntimeException e) {
                bad.add(dir + " (" + e + ")");
            }
        }
        if (!bad.isEmpty()) {
            settingsError = "Can't read the RISC OS indexer settings in " + String.join(", ", bad)
                    + ". Rescan the build tree (RISC OS › Rescan Build Tree), then reopen the file.";
            log.println(settingsError);
        }
        if (!each.isEmpty()) settings = each.size() == 1 ? each.get(0) : Settings.combine(each);
        perTree = each;
    }

    /**
     * {@code workspace/didChangeConfiguration} with {@code {"riscos": {"settings": DIRS}}}
     * (and {@code "reload": true} if DIRS are the same but their contents changed):
     * another configuration of a tree (another Env file's buildscan output)
     * is now the active one, or a tree has been added. Everything is indexed
     * again from those settings; open documents keep their unsaved text.
     */
    private void changeConfiguration(Map<String, Object> p) throws IOException {
        Map<String, Object> riscos = obj(obj(p.get("settings")).get("riscos"));
        List<String> dirs = dirs(riscos.get("settings"));
        // "reload": the same directories, written again (after a rescan, say).
        if (dirs.isEmpty() || dirs.equals(settingsDirs) && !Boolean.TRUE.equals(riscos.get("reload"))) return;
        log.println("switching to the settings in " + String.join(", ", dirs));
        release(true);
        if (indexer != null) indexer.stop();
        indexer = null;
        // Forget what the old configuration reported.
        for (String f : new ArrayList<>(diagnosed)) clearDiagnostics(f);
        diagnosed.clear();
        synchronized (this) { sentFiles.clear(); }
        for (Doc d : docs.values()) d.overlaid = false;
        readSettings(dirs);
        initialized();
        if (indexer == null) return;
        for (Doc d : docs.values()) {
            SourceFile disk = null;
            try { disk = SourceFile.read(Path.of(d.file)); } catch (IOException ignored) { /* new file */ }
            if (disk == null || !sameText(disk, d.text)) {
                d.overlaid = true;
                indexer.setText(engineFile(d), d.text);
            }
        }
    }

    private Object initialize(Map<String, Object> p) throws IOException {
        Map<String, Object> opts = obj(p.get("initializationOptions"));
        // Keep running if they can't be read: the client should be told, not see the server vanish.
        List<String> dirs = dirs(opts.get("settings"));
        if (!dirs.isEmpty()) readSettings(dirs);
        showInfo = Boolean.TRUE.equals(opts.get("showInfoDiagnostics"));
        if (opts.get("uriStyle") instanceof String style && Set.of("plain", "real", "links").contains(style))
            uriStyle = style;
        Map<String, Object> window = obj(obj(p.get("capabilities")).get("window"));
        clientProgress = Boolean.TRUE.equals(window.get("workDoneProgress"));

        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("textDocumentSync", Map.of("openClose", true, "change", 2, "save", Map.of("includeText", false)));
        caps.put("definitionProvider", true);
        caps.put("referencesProvider", true);
        caps.put("documentHighlightProvider", true);
        caps.put("hoverProvider", true);
        caps.put("completionProvider", Map.of("triggerCharacters", List.of("%", "$", "\"")));
        caps.put("documentSymbolProvider", true);
        caps.put("workspaceSymbolProvider", true);
        caps.put("foldingRangeProvider", true);
        caps.put("declarationProvider", true);
        caps.put("executeCommandProvider", Map.of("commands", List.of(CMD_ASM_DEFINITIONS, CMD_ASM_REFERENCES, CMD_ASSIGNMENTS, CMD_BASIC_SYMBOLS)));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("capabilities", caps);
        r.put("serverInfo", Map.of("name", "objasm-index", "version", "1.0"));
        return r;
    }

    private void initialized() throws IOException {
        if (settingsError != null) {
            transport.notify("window/showMessage", Map.of("type", settings == null ? 1 : 2, "message", settingsError));
            if (settings == null) return;
        }
        if (settings == null) {
            logMessage(1, "objasm-index: no settings. Pass initializationOptions.settings (buildscan's output directory) or --settings DIR.");
            return;
        }
        List<Path> roots = new ArrayList<>();
        for (Settings s1 : perTree.isEmpty() ? List.of(settings) : perTree) {
            Path r = treeRootOf(commonRoot(s1.entries.keySet()));
            if (r != null) roots.add(r);
        }
        roots.sort((x, y) -> y.getNameCount() - x.getNameCount());
        treeRoots = roots;
        List<Path> linked = new ArrayList<>();
        for (String d : settingsDirs) linked.add(Path.of(d));
        linkage = Linkage.load(linked);
        indexer = new Indexer(settings, this::updated, msg -> {
            log.println(msg);
            try { logMessage(2, msg); } catch (IOException ignored) { /* client gone */ }
        });
        firstIndexing = true;
        startProgress("Indexing " + settings.entries.size() + " assembler entry points");
        status("indexing");
        indexer.indexAll();
    }

    // =====================================================================
    // Documents
    // =====================================================================

    /** Sends the diagnostics held back until the client was ready. */
    private void clientNowReady() throws IOException {
        if (clientReady) return;
        clientReady = true;
        if (indexer == null) return;
        Model m = indexer.model();
        Set<String> files = new java.util.TreeSet<>();
        for (EntryIndex e : m.entries.values()) files.addAll(e.diagsByFile.keySet());
        for (String f : files) publishDiagnostics(f, m);
    }

    private void didOpen(Map<String, Object> p) throws IOException {
        Map<String, Object> td = obj(p.get("textDocument"));
        String uri = (String) td.get("uri");
        String file = fileOf(uri);
        Doc d = new Doc(uri, file, (String) td.get("text"), intOf(td.get("version")));
        docs.put(file, d);
        if (isBasic(td)) {
            basic.update(uri, d.text);
            clientNowReady();
            publishBasic(uri);
            return;
        }
        if (indexer == null) return;
        clientNowReady();
        SourceFile disk = null;
        try { disk = SourceFile.read(Path.of(file)); } catch (IOException ignored) { /* new file */ }
        if (disk == null || !sameText(disk, d.text)) {
            d.overlaid = true;
            indexer.setText(engineFile(d), d.text);
        }
        publishFor(d, indexer.model());
    }

    private static boolean sameText(SourceFile f, String text) {
        try {
            String disk = new String(Files.readAllBytes(f.path), StandardCharsets.ISO_8859_1);
            return disk.equals(text);
        } catch (IOException e) {
            return false;
        }
    }

    private void didChange(Map<String, Object> p) {
        Map<String, Object> td = obj(p.get("textDocument"));
        Doc d = docs.get(fileOf((String) td.get("uri")));
        if (d == null) return;
        didChangeText(d, p);
        if (indexer != null) {
            d.overlaid = true;
            indexer.setText(engineFile(d), d.text);
        }
    }

    /** Applies a didChange's edits to a document's text. */
    private static void didChangeText(Doc d, Map<String, Object> p) {
        d.version = intOf(obj(p.get("textDocument")).get("version"));
        for (Object o : (List<?>) p.get("contentChanges")) {
            Map<String, Object> c = obj(o);
            String t = (String) c.get("text");
            if (c.get("range") == null) { d.text = t; continue; }
            Map<String, Object> r = obj(c.get("range"));
            int a = offset(d.text, obj(r.get("start"))), b = offset(d.text, obj(r.get("end")));
            d.text = d.text.substring(0, a) + t + d.text.substring(Math.max(a, b));
        }
    }

    private void didClose(Map<String, Object> p) throws IOException {
        Doc d = docs.remove(fileOf((String) obj(p.get("textDocument")).get("uri")));
        if (d != null && d.overlaid && indexer != null) indexer.setText(engineFile(d), null);
    }

    /**
     * The name the engine reads a document by. An editor may open a file by
     * its real path (Eclipse resolves the objs/ link farm's links), while
     * objasm, and so the engine, reads it through the link.
     */
    private Path engineFile(Doc d) {
        return Path.of(indexer.model().canonical(d.file));
    }

    private void watchedFiles(Map<String, Object> p) {
        if (indexer == null) return;
        for (Object o : (List<?>) p.getOrDefault("changes", List.of())) {
            String file = fileOf((String) obj(o).get("uri"));
            if (!docs.containsKey(file)) indexer.setText(Path.of(file), null);
        }
    }

    /** UTF-16 offset in {@code text} of an LSP position. */
    static int offset(String text, Map<String, Object> pos) {
        int line = intOf(pos.get("line")), ch = intOf(pos.get("character"));
        int i = 0, n = text.length();
        for (int l = 0; l < line && i < n; ) {
            char c = text.charAt(i++);
            if (c == '\n') l++;
            else if (c == '\r') { l++; if (i < n && text.charAt(i) == '\n') i++; }
        }
        int start = i;
        while (i < n && i - start < ch && text.charAt(i) != '\n' && text.charAt(i) != '\r') i++;
        return i;
    }

    // =====================================================================
    // Publishing
    // =====================================================================

    private int indexedSoFar;

    private void updated(Indexer.Update u) {
        try {
            indexedSoFar += u.changed.size();
            Set<String> files = new LinkedHashSet<>();
            for (EntryIndex e : u.changed) {
                files.addAll(e.diagsByFile.keySet());
                for (String f : diagnosed) if (e.files.contains(f)) files.add(f);
                for (String f : docs.keySet()) if (e.files.contains(f)) files.add(f);
            }
            sourceFiles(u.changed);
            if (clientReady) for (String f : files) publishDiagnostics(f, u.model);
            for (Doc d : new ArrayList<>(docs.values()))
                if (files.contains(d.file) || files.contains(u.model.canonical(d.file))) publishRegions(d, u.model);
            synchronized (this) {
                if (u.complete) firstIndexing = false;
                release(u.complete);
            }
            if (u.complete) {
                endProgress(indexedSoFar + " entry points indexed");
                indexedSoFar = 0;
                status("idle");
            } else {
                reportProgress(indexedSoFar + " of " + u.model.entries.size() + " entry points");
            }
        } catch (IOException e) {
            log.println("publishing failed: " + e);
        }
    }

    private synchronized void publishFor(Doc d, Model m) throws IOException {
        publishDiagnostics(m.canonical(d.file), m);
        publishRegions(d, m);
    }

    private synchronized void publishDiagnostics(String file, Model m) throws IOException {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (EntryIndex e : m.readers(file)) {
            for (Diagnostic d : e.diagsByFile.getOrDefault(file, List.of())) {
                if (d.severity == Diagnostic.Severity.INFO && !showInfo) continue;
                Site s = d.site == null ? new Site(file, 1, 0, 0, null) : d.site;
                Site at = EntryIndex.outermost(s);
                String key = d.severity + "|" + d.message + "|" + at;
                if (out.containsKey(key)) continue;
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("range", lineRange(at));
                x.put("severity", d.severity == Diagnostic.Severity.ERROR ? 1 : d.severity == Diagnostic.Severity.WARNING ? 2 : 3);
                x.put("code", d.code);
                x.put("source", "objasm-index");
                x.put("message", d.message);
                if (s.via != null) x.put("relatedInformation", List.of(Map.of(
                        "location", location(s), "message", "in this macro's expansion")));
                out.put(key, x);
            }
        }
        if (out.isEmpty() && !diagnosed.contains(file)) return;
        if (out.isEmpty()) diagnosed.remove(file); else diagnosed.add(file);
        Doc d = docs.get(file);
        transport.notify("textDocument/publishDiagnostics", Map.of(
                "uri", uriOf(file), "diagnostics", new ArrayList<>(out.values())));
    }

    private synchronized void clearDiagnostics(String file) throws IOException {
        transport.notify("textDocument/publishDiagnostics", Map.of("uri", uriOf(file), "diagnostics", List.of()));
    }

    private synchronized void publishRegions(Doc d, Model m) throws IOException {
        String file = m.canonical(d.file);
        List<EntryIndex> readers = m.readers(file);
        List<Object> shaded = new ArrayList<>(), macro = new ArrayList<>();
        if (!readers.isEmpty()) {
            int n = 0;
            for (EntryIndex e : readers) {
                byte[] b = e.lineClass.get(file);
                if (b != null) n = Math.max(n, b.length);
            }
            String[] lines = d.text.split("\r\n|\r|\n", -1);
            int shStart = -1, msStart = -1;
            for (int line = 1; line <= n; line++) {
                // An entry point that never reached the line (a macro it never
                // expanded, say) says nothing about it either way.
                boolean any = false, allShade = true, allSkip = true, anyMacro = false;
                for (EntryIndex e : readers) {
                    int c = line < n ? e.classify(file, line) : 0;
                    if (c == 0) continue;
                    any = true;
                    if (c != 1) allShade = false;
                    if (c != 1 && c != 3) allSkip = false;
                    if (c == 3) anyMacro = true;
                }
                allShade &= any;
                boolean ms = any && allSkip && anyMacro;
                if (allShade && shStart < 0) shStart = line;
                if (!allShade && shStart >= 0) { shaded.add(linesRange(shStart, line - 1, lines)); shStart = -1; }
                if (ms && msStart < 0) msStart = line;
                if (!ms && msStart >= 0) { macro.add(linesRange(msStart, line - 1, lines)); msStart = -1; }
            }
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("textDocument", Map.of("uri", d.uri, "version", d.version));
        p.put("regions", shaded);
        p.put("skippedInEveryExpansion", macro);
        transport.notify("riscos/inactiveRegions", p);
    }

    private static Map<String, Object> linesRange(int first, int last, String[] lines) {
        int len = last - 1 < lines.length ? lines[last - 1].length() : 0;
        return range(first - 1, 0, last - 1, len);
    }

    /** Every file already sent in riscos/sourceFiles. */
    private final Set<String> sentFiles = new java.util.HashSet<>();

    /**
     * {@code riscos/sourceFiles}: files the engine has read that haven't been
     * sent before, by the names objasm used and, where different, by their
     * real paths. Sent as indexing goes, so that a client gets them even if it
     * stops the server part-way. A client can use them to treat extensionless
     * headers as assembler.
     */
    private void sourceFiles(java.util.Collection<EntryIndex> changed) throws IOException {
        Set<String> files = new java.util.TreeSet<>();
        for (EntryIndex e : changed) {
            for (String f : e.files) {
                if (!sentFiles.add(f)) continue;
                files.add(f);
                try {
                    String real = Path.of(f).toRealPath().toString();
                    if (sentFiles.add(real)) files.add(real);
                } catch (IOException | RuntimeException ignored) {
                    // gone since: the name objasm used is enough
                }
            }
        }
        if (!files.isEmpty()) transport.notify("riscos/sourceFiles", Map.of("files", new ArrayList<>(files)));
    }

    private void status(String state) throws IOException {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("state", state);
        p.put("entryPoints", indexer == null ? 0 : indexer.model().entries.size());
        transport.notify("riscos/status", p);
    }

    private void logMessage(int type, String message) throws IOException {
        transport.notify("window/logMessage", Map.of("type", type, "message", message));
    }

    private void startProgress(String title) throws IOException {
        if (!clientProgress) return;
        progressToken = "objasm-index-" + (++nextRequestId);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", "objasm-index-req-" + nextRequestId);
        req.put("method", "window/workDoneProgress/create");
        req.put("params", Map.of("token", progressToken));
        transport.write(req);
        transport.notify("$/progress", Map.of("token", progressToken,
                "value", Map.of("kind", "begin", "title", title, "cancellable", false)));
    }

    private void reportProgress(String message) throws IOException {
        if (progressToken != null)
            transport.notify("$/progress", Map.of("token", progressToken, "value", Map.of("kind", "report", "message", message)));
    }

    private void endProgress(String message) throws IOException {
        if (progressToken != null)
            transport.notify("$/progress", Map.of("token", progressToken, "value", Map.of("kind", "end", "message", message)));
        progressToken = null;
    }

    // =====================================================================
    // Finding what is at a position
    // =====================================================================

    /** Where a request points: the engine's file name, and 1-based line and 0-based column. */
    private static final class At {
        final String file;
        final int line, col;
        At(String file, int line, int col) { this.file = file; this.line = line; this.col = col; }
    }

    private At at(Map<String, Object> p, Model m) {
        String file = m.canonical(fileOf((String) obj(p.get("textDocument")).get("uri")));
        Map<String, Object> pos = obj(p.get("position"));
        return new At(file, intOf(pos.get("line")) + 1, intOf(pos.get("character")));
    }

    /** All occurrences in a file, from every entry point that reads it, by line. */
    @SuppressWarnings("unchecked")
    private static Map<Integer, List<Occ>> occurrences(Model m, String file) {
        return (Map<Integer, List<Occ>>) m.cache.computeIfAbsent("occ:" + file, k -> {
            Map<Integer, List<Occ>> by = new HashMap<>();
            for (EntryIndex e : m.readers(file))
                for (Occ o : e.occurrences.getOrDefault(file, List.of()))
                    by.computeIfAbsent(o.line, x -> new ArrayList<>()).add(o);
            return by;
        });
    }

    /** Occurrences at a position, the narrowest first. */
    private static List<Occ> occurrencesAt(Model m, At a) {
        List<Occ> out = new ArrayList<>();
        for (Occ o : occurrences(m, a.file).getOrDefault(a.line, List.of())) if (o.contains(a.line, a.col)) out.add(o);
        out.sort((x, y) -> Integer.compare(x.len, y.len));
        if (!out.isEmpty()) {
            int len = out.get(0).len;
            out.removeIf(o -> o.len != len);
        }
        return out;
    }

    /** Identity of a target across entry points. */
    private static String key(Object t) {
        if (t instanceof Sym) return "S:" + ((Sym) t).name;
        if (t instanceof Mac) return "M:" + ((Mac) t).name;
        if (t instanceof Local) return "L:" + ((Local) t).def;
        return "G:" + ((Get) t).file;
    }

    /**
     * Distinct targets at a position, keyed. Where nothing is recorded, an
     * argument of a macro call stands for the symbols that call defined whose
     * names contain it: {@code WriteC} in {@code AddSWI WriteC} is OS_WriteC
     * and XOS_WriteC.
     */
    private Map<String, List<Object>> targetsAt(Model m, At a) {
        Map<String, List<Object>> t = new LinkedHashMap<>();
        for (Occ o : occurrencesAt(m, a)) t.computeIfAbsent(key(o.target), k -> new ArrayList<>()).add(o.target);
        if (!t.isEmpty()) return t;
        for (Occ o : occurrences(m, a.file).getOrDefault(a.line, List.of())) {
            if (!(o.target instanceof Mac) || o.def || a.col < o.col + o.len) continue;
            for (EntryIndex e : m.readers(a.file))
                for (Sym s : e.definedByCall.getOrDefault(o.site, List.of())) {
                    Site r = refine(o.site, s.name);
                    if (r.col != o.col && a.col >= r.col && a.col <= r.col + r.len)
                        t.computeIfAbsent(key(s), k -> new ArrayList<>()).add(s);
                }
        }
        return t;
    }

    /** Every symbol in the model by name, built on first need. */
    @SuppressWarnings("unchecked")
    private static Map<String, List<Sym>> symbolsByName(Model m) {
        return (Map<String, List<Sym>>) m.cache.computeIfAbsent("syms", k -> {
            Map<String, List<Sym>> by = new HashMap<>();
            for (EntryIndex e : m.entries.values())
                for (Sym s : e.symbols.values()) by.computeIfAbsent(s.name, x -> new ArrayList<>()).add(s);
            return by;
        });
    }

    /**
     * Everything in the model that is the same thing as one of {@code found}:
     * a symbol or macro of the same name sharing a definition site, or a local
     * label with the same definition. A symbol with no definition matches by name.
     */
    private static List<Object> sameAs(Model m, List<Object> found) {
        Object first = found.get(0);
        List<Object> out = new ArrayList<>();
        if (first instanceof Sym) {
            Set<Site> defs = new HashSet<>();
            for (Object o : found) defs.addAll(((Sym) o).defs);
            Set<Sym> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Sym s : symbolsByName(m).getOrDefault(((Sym) first).name, List.of())) {
                boolean same = defs.isEmpty() ? s.defs.isEmpty() : s.defs.stream().anyMatch(defs::contains);
                if (same && seen.add(s)) out.add(s);
            }
        } else if (first instanceof Mac) {
            Set<Site> defs = new HashSet<>();
            for (Object o : found) defs.add(((Mac) o).def);
            for (EntryIndex e : m.entries.values()) {
                Mac x = e.macros.get(((Mac) first).name);
                if (x != null && defs.contains(x.def)) out.add(x);
            }
        } else if (first instanceof Local) {
            Site def = ((Local) first).def;
            for (EntryIndex e : m.readers(def.file))
                for (Local l : e.locals) if (l.def.equals(def)) out.add(l);
        } else out.addAll(found);
        if (out.isEmpty()) out.addAll(found);
        return out;
    }

    // =====================================================================
    // Requests
    // =====================================================================

    private Object definition(Map<String, Object> p) {
        Model m = model();
        At a = at(p, m);
        Map<String, List<Object>> t = targetsAt(m, a);
        LinkedHashSet<Map<String, Object>> locs = new LinkedHashSet<>();
        for (List<Object> found : t.values()) {
            Object first = found.get(0);
            if (first instanceof Get) {
                locs.add(Map.of("uri", uriOf(((Get) first).file), "range", range(0, 0, 0, 0)));
                continue;
            }
            for (Object o : sameAs(m, found)) {
                if (o instanceof Sym s && s.kind == Symbol.Kind.EXTERNAL) {
                    // IMPORTed: defined in C, or in the assembler of a library.
                    List<Map<String, Object>> c = externalDefinitions(m, s.name, componentOfAsm(m, a.file));
                    if (c.isEmpty()) for (Site d : s.defs) addDef(locs, d, s.name);
                    else locs.addAll(c);
                } else if (o instanceof Sym s && isVariable(s.kind)) {
                    // A variable's GBLA, LCLS... is its declaration; its assignments are CMD_ASSIGNMENTS.
                } else if (o instanceof Sym) {
                    for (Site d : ((Sym) o).defs) addDef(locs, d, ((Sym) o).name);
                } else if (o instanceof Mac) {
                    if (((Mac) o).def != null) locs.add(location(((Mac) o).def));
                } else if (o instanceof Local) locs.add(location(((Local) o).def));
                if (locs.size() >= MAX_LOCATIONS) break;
            }
        }
        return new ArrayList<>(locs);
    }

    private static boolean isVariable(Symbol.Kind k) {
        return k == Symbol.Kind.VAR_A || k == Symbol.Kind.VAR_L || k == Symbol.Kind.VAR_S;
    }

    /** A definition: for one made by a macro, the outermost call first, then the macro body. */
    private void addDef(Set<Map<String, Object>> locs, Site d, String name) {
        if (d.via == null) { locs.add(location(d)); return; }
        locs.add(location(refine(EntryIndex.outermost(d), name)));
        locs.add(location(new Site(d.file, d.line, d.col, d.len, null)));
    }

    /** A use or definition as the user sees it: the text itself if it names the symbol, and the call that made it. */
    private void addSite(Set<Map<String, Object>> locs, Site s, String name) {
        if (s.via == null) { locs.add(location(s)); return; }
        if (name == null || name.equals(textAt(s))) locs.add(location(new Site(s.file, s.line, s.col, s.len, null)));
        locs.add(location(refine(EntryIndex.outermost(s), name)));
    }

    // =====================================================================
    // Assembler and C, joined by name as the linker joins them
    // =====================================================================

    static final String CMD_ASM_DEFINITIONS = "riscos.asmDefinitions", CMD_ASM_REFERENCES = "riscos.asmReferences";
    /** [uri, line, character]: where the variable there is assigned (SETA, SETL, SETS), in every entry point. */
    static final String CMD_ASSIGNMENTS = "riscos.assignments";
    /** [uri]: a BASIC file's document symbols, whether or not it's open (read from disk if not). */
    static final String CMD_BASIC_SYMBOLS = "riscos.basicSymbols";

    private Object assignments(String uri, int line, int character) {
        Model m = model();
        At a = at(Map.of("textDocument", Map.of("uri", uri), "position", Map.of("line", line, "character", character)), m);
        LinkedHashSet<Map<String, Object>> locs = new LinkedHashSet<>();
        for (List<Object> found : targetsAt(m, a).values())
            for (Object o : sameAs(m, found)) {
                if (o instanceof Sym s && isVariable(s.kind))
                    for (Site set : s.sets) addSite(locs, set, s.name);
                if (locs.size() >= MAX_LOCATIONS) break;
            }
        return new ArrayList<>(locs);
    }

    /** The configuration's components, C files and libraries. */
    private Linkage linkage = Linkage.NONE;

    private static String componentOfAsm(Model m, String file) {
        List<EntryIndex> r = m.readers(file);
        return r.isEmpty() ? null : r.get(0).component;
    }

    private Map<String, Object> cLocation(Path file, CSource.Occ o) {
        return Map.of("uri", uriOf(file.toString()), "range", range(o.line(), o.col(), o.line(), o.col() + o.len()));
    }

    /**
     * Where a symbol a component IMPORTs is defined: C functions and variables
     * with external linkage in it and the libraries it links with, and
     * assembler labels those EXPORT.
     */
    private List<Map<String, Object>> externalDefinitions(Model m, String name, String component) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (component == null) return out;
        Set<String> where = linkage.definers(component);
        for (String c : where)
            for (Path f : linkage.cFiles.getOrDefault(c, List.of())) {
                CSource src = linkage.source(f);
                if (src != null) for (CSource.Occ o : src.definitions.getOrDefault(name, List.of())) out.add(cLocation(f, o));
            }
        Set<Map<String, Object>> asm = new LinkedHashSet<>();
        for (EntryIndex e : m.entries.values()) {
            if (!where.contains(e.component)) continue;
            Sym s = e.symbols.get(name);
            if (s != null && s.kind != Symbol.Kind.EXTERNAL && s.flags.contains("exported"))
                for (Site d : s.defs) addDef(asm, d, name);
        }
        out.addAll(asm);
        return out;
    }

    /** Where C declares a symbol (prototypes, extern): in the headers and C files of a component and its libraries. */
    private List<Map<String, Object>> cDeclarations(String name, String component) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (component == null) return out;
        Set<Path> files = new LinkedHashSet<>();
        for (String c : linkage.definers(component)) {
            files.addAll(linkage.headers(c));
            files.addAll(linkage.cFiles.getOrDefault(c, List.of()));
        }
        for (Path f : files) {
            CSource src = linkage.source(f);
            if (src != null) for (CSource.Occ o : src.declarations.getOrDefault(name, List.of())) out.add(cLocation(f, o));
        }
        return out;
    }

    /** Where C uses a symbol: in a component and the components that link with it (definitions only if asked). */
    private List<Map<String, Object>> cUses(String name, String component, boolean definitionsToo) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (component == null) return out;
        for (String c : linkage.users(component))
            for (Path f : linkage.cFiles.getOrDefault(c, List.of())) {
                CSource src = linkage.source(f);
                if (src == null) continue;
                List<CSource.Occ> defs = src.definitions.getOrDefault(name, List.of());
                for (CSource.Occ o : src.uses.getOrDefault(name, List.of()))
                    if (definitionsToo || !defs.contains(o)) out.add(cLocation(f, o));
            }
        return out;
    }

    /**
     * {@code textDocument/declaration}: for a symbol shared with C, its IMPORT
     * or EXPORT and the C prototypes; otherwise nothing, the definition being
     * the declaration.
     */
    private Object declaration(Map<String, Object> p) {
        Model m = model();
        At a = at(p, m);
        LinkedHashSet<Map<String, Object>> locs = new LinkedHashSet<>();
        for (List<Object> found : targetsAt(m, a).values())
            for (Object o : sameAs(m, found)) {
                if (o instanceof Sym v && isVariable(v.kind)) {
                    for (Site d : v.defs) addDef(locs, d, v.name);       // GBLA, LCLS...
                    continue;
                }
                if (!(o instanceof Sym s) || !(s.kind == Symbol.Kind.EXTERNAL || s.flags.contains("exported"))) continue;
                if (s.kind == Symbol.Kind.EXTERNAL) for (Site d : s.defs) addDef(locs, d, s.name);
                else for (Site u : s.uses) if (isExportLine(u)) addSite(locs, u, s.name);
                locs.addAll(cDeclarations(s.name, componentOfAsm(m, a.file)));
            }
        return new ArrayList<>(locs);
    }

    private boolean isExportLine(Site s) {
        String t = lineText(s.file, s.line);
        return t != null && t.matches("(?i)^\\s*\\S*\\s+(EXPORT|GLOBAL|EXPORTAS)\\b.*");
    }

    /**
     * {@code workspace/executeCommand}, for a C editor: {@code riscos.asmDefinitions}
     * [name, uri of a C file] gives where the assembler of that file's
     * component, or of the libraries it links with, EXPORTs and defines the
     * name; {@code riscos.asmReferences} [name, uri] where assembler there,
     * or in the components that link with it, uses or defines it.
     */
    private Object executeCommand(Map<String, Object> p) {
        String cmd = String.valueOf(p.get("command"));
        List<?> args = p.get("arguments") instanceof List<?> l ? l : List.of();
        if (cmd.equals(CMD_BASIC_SYMBOLS)) {
            if (args.isEmpty()) return List.of();
            String u = String.valueOf(args.get(0)), b = basic.opened(u);
            return b != null ? basic.documentSymbols(b) : basic.documentSymbolsOnDisk(u);
        }
        if (cmd.equals(CMD_ASSIGNMENTS)) {
            if (args.size() < 3) return List.of();
            String b = basic.opened(String.valueOf(args.get(0)));
            if (b != null) return basic.assignments(b, intOf(args.get(1)), intOf(args.get(2)));
            return assignments(String.valueOf(args.get(0)), intOf(args.get(1)), intOf(args.get(2)));
        }
        if (args.size() < 2 || indexer == null) return List.of();
        String name = String.valueOf(args.get(0)), comp;
        try {
            comp = linkage.componentOf(Path.of(fileOf(String.valueOf(args.get(1)))));
        } catch (RuntimeException e) {
            return List.of();
        }
        if (comp == null) return List.of();
        Model m = model();
        LinkedHashSet<Map<String, Object>> locs = new LinkedHashSet<>();
        if (cmd.equals(CMD_ASM_DEFINITIONS)) {
            for (EntryIndex e : m.entries.values()) {
                if (!linkage.definers(comp).contains(e.component)) continue;
                Sym s = e.symbols.get(name);
                if (s != null && s.kind != Symbol.Kind.EXTERNAL && s.flags.contains("exported"))
                    for (Site d : s.defs) addDef(locs, d, name);
            }
        } else if (cmd.equals(CMD_ASM_REFERENCES)) {
            Set<String> where = new LinkedHashSet<>(linkage.definers(comp));
            where.addAll(linkage.users(comp));
            for (EntryIndex e : m.entries.values()) {
                if (!where.contains(e.component)) continue;
                Sym s = e.symbols.get(name);
                if (s == null || !(s.kind == Symbol.Kind.EXTERNAL || s.flags.contains("exported"))) continue;
                for (Site d : s.defs) addSite(locs, d, name);
                for (Site u : s.uses) addSite(locs, u, name);
            }
        }
        return new ArrayList<>(locs);
    }

    private Object references(Map<String, Object> p) {
        Model m = model();
        At a = at(p, m);
        boolean decl = Boolean.TRUE.equals(obj(p.get("context")).get("includeDeclaration"));
        LinkedHashSet<Map<String, Object>> locs = new LinkedHashSet<>();
        for (List<Object> found : targetsAt(m, a).values()) {
            for (Object o : sameAs(m, found)) {
                if (o instanceof Sym) {
                    Sym s = (Sym) o;
                    if (decl) for (Site d : s.defs) addSite(locs, d, s.name);
                    for (Site u : s.uses) addSite(locs, u, s.name);
                    if (s.kind == Symbol.Kind.EXTERNAL || s.flags.contains("exported"))
                        locs.addAll(cUses(s.name, componentOfAsm(m, a.file), decl));
                } else if (o instanceof Mac) {
                    Mac x = (Mac) o;
                    if (decl && x.def != null) locs.add(location(x.def));
                    for (Site u : x.uses) addSite(locs, u, null);
                } else if (o instanceof Local) {
                    Local l = (Local) o;
                    if (decl) locs.add(location(l.def));
                    for (Site r : l.refs) addSite(locs, r, null);
                }
            }
        }
        return new ArrayList<>(locs);
    }

    private Object highlights(Map<String, Object> p) {
        Model m = model();
        At a = at(p, m);
        Set<String> keys = targetsAt(m, a).keySet();
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (keys.size() != 1) return List.of();
        for (List<Occ> l : occurrences(m, a.file).values())
            for (Occ o : l) {
                if (!keys.contains(key(o.target))) continue;
                String k = o.line + ":" + o.col;
                Map<String, Object> h = out.get(k);
                if (h != null && !o.def) continue;
                out.put(k, Map.of("range", range(o.line - 1, o.col, o.line - 1, o.col + o.len), "kind", o.def ? 3 : 2));
            }
        return new ArrayList<>(out.values());
    }

    private Object hover(Map<String, Object> p) {
        Model m = model();
        At a = at(p, m);
        Map<String, List<Object>> t = targetsAt(m, a);
        String subst = onDollar(a) ? substitution(m, a) : null;
        if (t.isEmpty() && subst == null) return null;
        StringBuilder b = new StringBuilder();
        if (subst != null) b.append(subst);
        if (t.size() > 1 && t.values().iterator().next().get(0) instanceof Sym) {
            // A templated name in a macro body: one symbol per expansion.
            b.append(t.size()).append(" symbols are made here:\n\n```\n");
            int n = 0;
            for (List<Object> found : t.values()) {
                if (n++ == 10) { b.append("…\n"); break; }
                b.append(symbolLine(found)).append('\n');
            }
            b.append("```");
        } else {
            for (List<Object> found : t.values()) {
                if (b.length() > 0) b.append("\n\n---\n\n");
                b.append(describe(m, a, found));
            }
        }
        if (b.length() == 0) return null;
        return Map.of("contents", Map.of("kind", "markdown", "value", b.toString()));
    }

    /** {@code NAME: kind = value}, with each distinct value if entry points differ. */
    private static String symbolLine(List<Object> found) {
        Sym s = (Sym) found.get(0);
        Map<String, Integer> values = new LinkedHashMap<>();
        for (Object o : found) values.merge(kindAndValue((Sym) o), 1, Integer::sum);
        if (values.size() == 1) return s.name + ": " + values.keySet().iterator().next();
        StringBuilder b = new StringBuilder(s.name).append(":");
        for (Map.Entry<String, Integer> v : values.entrySet())
            b.append("\n  ").append(v.getKey()).append("   (").append(v.getValue())
                    .append(v.getValue() == 1 ? " entry point)" : " entry points)");
        return b.toString();
    }

    private static String kindAndValue(Sym s) {
        String k = s.kind.description;
        if (s.kind == Symbol.Kind.LABEL && s.flags.contains("routine")) k = "routine label";
        if (s.kind == Symbol.Kind.EXTERNAL || s.value == null) return k;
        return k + " = " + s.value;
    }

    private String describe(Model m, At a, List<Object> found) {
        Object first = found.get(0);
        StringBuilder b = new StringBuilder();
        if (first instanceof Sym) {
            Sym s = (Sym) first;
            b.append("```\n").append(symbolLine(found)).append("\n```");
            Set<String> flags = new LinkedHashSet<>();
            for (Object o : found) flags.addAll(((Sym) o).flags);
            flags.remove("routine");
            if (s.size != 0) b.append("\n\nSize ").append(s.size).append('.');
            if (!flags.isEmpty()) b.append("\n\n*").append(String.join(", ", flags)).append("*");
            if (flags.contains("conditional"))
                b.append(" — defined where the indexer couldn't decide whether the source is assembled.");
            if (!s.defs.isEmpty()) {
                Site d = s.defs.get(0);
                Site o = EntryIndex.outermost(d);
                b.append("\n\nDefined at ").append(where(o));
                if (o != d) b.append(" by `").append(withoutComment(textOfLine(o))).append('`');
                if (s.defs.size() > 1) b.append(" (and ").append(s.defs.size() - 1).append(" more)");
            } else if (s.kind == Symbol.Kind.EXTERNAL) b.append("\n\nImported.");
        } else if (first instanceof Mac) {
            Mac x = (Mac) first;
            b.append("```\nMACRO ").append(x.signature).append("\n```");
            if (x.def != null) b.append("\n\nDefined at ").append(where(x.def));
            // At a call: what this call defined.
            Map<String, String> made = new TreeMap<>();
            for (Occ o : occurrencesAt(m, a)) {
                if (o.def || o.target != first && !(o.target instanceof Mac)) continue;
                for (EntryIndex e : m.readers(a.file))
                    for (Sym s : e.definedByCall.getOrDefault(o.site, List.of())) made.putIfAbsent(s.name, kindAndValue(s));
            }
            String ex = expansion(m, a);
            if (ex != null) b.append(ex);
            if (!made.isEmpty()) {
                b.append("\n\nThis call defines:\n\n```\n");
                int n = 0;
                for (Map.Entry<String, String> e : made.entrySet()) {
                    if (n++ == 20) { b.append("… and ").append(made.size() - 20).append(" more\n"); break; }
                    b.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
                }
                b.append("```");
            }
        } else if (first instanceof Local) {
            Local l = (Local) first;
            b.append("```\nlocal label ").append(l.number);
            if (l.routine != null) b.append(" in ").append(l.routine);
            b.append(" = ").append(l.value).append("\n```\n\nDefined at ").append(where(l.def));
        } else {
            b.append("`").append(((Get) first).file).append('`');
        }
        return b.toString();
    }

    /** Whether the cursor is on a {@code $name} (or the '$' of one). */
    private boolean onDollar(At a) {
        String s = lineText(a.file, a.line);
        if (s == null || a.col > s.length()) return false;
        int i = Math.min(a.col, s.length() - 1);
        if (i < 0) return false;
        if (s.charAt(i) == '$') return i + 1 < s.length() && (Character.isLetter(s.charAt(i + 1)) || s.charAt(i + 1) == '_');
        while (i >= 0 && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i--;
        return i >= 0 && s.charAt(i) == '$' && (i == 0 || s.charAt(i - 1) != '$');
    }

    /**
     * How the line at {@code a} reads once '$' substitution is done, each
     * different way it is assembled: the first entry point that reads the file
     * is assembled again, with the unsaved text.
     */
    private String substitution(Model m, At a) {
        if (indexer == null) return null;
        List<EntryIndex> readers = m.readers(a.file);
        if (readers.isEmpty()) return null;
        EntryPoint e = readers.get(0).entry;
        com.piccolosystems.riscos.asmindex.run.Assembler.LineCapture c =
                new com.piccolosystems.riscos.asmindex.run.Assembler.LineCapture(a.file, a.line);
        try {
            com.piccolosystems.riscos.asmindex.run.Assembler asm = new com.piccolosystems.riscos.asmindex.run.Assembler(
                    e, indexer.settings().resolver(e), indexer.cache());
            asm.lineCapture = c;
            asm.run();
        } catch (IOException | RuntimeException | StackOverflowError x) {
            log.println("substituting the line at " + a.file + ":" + a.line + " failed: " + x);
            return null;
        }
        if (c.results.isEmpty()) return "This line isn't assembled.";
        StringBuilder b = new StringBuilder(c.results.size() == 1 ? "After substitution:\n\n```\n"
                : "After substitution, each way it is assembled:\n\n```\n");
        for (Map.Entry<String, Integer> r : c.results.entrySet()) {
            b.append(r.getKey().strip());
            if (c.results.size() > 1 && r.getValue() > 1) b.append("      ; ×").append(r.getValue());
            b.append('\n');
        }
        if (c.results.size() >= com.piccolosystems.riscos.asmindex.run.Assembler.LineCapture.LIMIT) b.append("…\n");
        return b.append("```").toString();
    }

    private static final int EXPANSION_LINES = 60;

    /**
     * The expansion of the macro call at {@code a}, if one is written there
     * (not inside another macro): the first entry point that reads the file is
     * assembled again, stopping once that call's expansion ends. Lines not
     * assembled are marked.
     */
    private String expansion(Model m, At a) {
        if (indexer == null) return null;
        List<EntryIndex> readers = m.readers(a.file);
        if (readers.isEmpty()) return null;
        EntryPoint e = readers.get(0).entry;
        com.piccolosystems.riscos.asmindex.run.Assembler.Capture c =
                new com.piccolosystems.riscos.asmindex.run.Assembler.Capture(a.file, a.line);
        try {
            com.piccolosystems.riscos.asmindex.run.Assembler asm = new com.piccolosystems.riscos.asmindex.run.Assembler(
                    e, indexer.settings().resolver(e), indexer.cache());
            asm.capture = c;
            asm.run();
        } catch (IOException | RuntimeException | StackOverflowError x) {
            log.println("expanding the call at " + a.file + ":" + a.line + " failed: " + x);
            return null;
        }
        if (!c.started) return null;
        StringBuilder b = new StringBuilder();
        if (!c.output.isEmpty()) {
            b.append("\n\nOutput, with the macros it calls expanded too:\n\n```\n");
            int n = 0;
            for (String l : c.output) {
                if (n++ == EXPANSION_LINES) {
                    b.append("… and ").append(c.output.size() - EXPANSION_LINES).append(" more lines\n");
                    break;
                }
                b.append(l).append('\n');
            }
            b.append("```");
        } else {
            b.append("\n\nNo code or data is output.");
        }
        b.append("\n\nExpansion");
        boolean skips = c.lines.stream().anyMatch(l -> !l[1].isEmpty());
        if (skips) b.append(" (lines marked ✗ aren't assembled, ? may not be)");
        b.append(":\n\n```\n");
        int n = 0;
        for (String[] l : c.lines) {
            if (n++ == EXPANSION_LINES) {
                b.append("… and ").append(c.lines.size() - EXPANSION_LINES).append(" more lines\n");
                break;
            }
            if (skips) b.append(l[1].equals("skip") ? "✗ " : l[1].equals("?") ? "? " : "  ");
            b.append(l[0].stripTrailing()).append('\n');
        }
        return b.append("```").toString();
    }

    // =====================================================================
    // SWI names, for BASIC's SYS "..."
    // =====================================================================

    private Map<String, String> swis;
    private Model swisFor;
    private final Map<Path, List<String[]>> cmhgSwis = new HashMap<>();
    private static final Pattern ADD_SWI = Pattern.compile("^\\S*\\s+AddSWI\\b");

    /**
     * Every SWI the build trees name, and its number where known ({@code &400C0}):
     * the constants an {@code AddSWI} call defines in the headers the
     * configurations assemble (with their X forms), and the names a CMHG
     * file's {@code swi-decoding-table} gives, with an X form each too.
     * Worked out again when the index has changed.
     */
    synchronized Map<String, String> swis() {
        Model m = model();
        if (swis != null && m == swisFor) return swis;
        Map<String, String> out = new TreeMap<>();
        Map<String, List<String>> lines = new HashMap<>();
        Map<String, Boolean> isAddSwi = new HashMap<>();
        for (EntryIndex e : m.entries.values())
            for (Sym s : e.symbols.values()) {
                if (s.defs.isEmpty() || out.containsKey(s.name)) continue;
                Site via = s.defs.get(0).via;
                if (via == null) continue;
                boolean add = isAddSwi.computeIfAbsent(via.file + ":" + via.line, k -> {
                    List<String> text = lines.computeIfAbsent(via.file, f -> {
                        try {
                            return Files.readAllLines(Path.of(f), StandardCharsets.ISO_8859_1);
                        } catch (IOException | RuntimeException x) {
                            return List.of();
                        }
                    });
                    return via.line >= 1 && via.line <= text.size() && ADD_SWI.matcher(text.get(via.line - 1)).find();
                });
                if (add) out.put(s.name, number(s.value));
            }
        // CMHG's, the prefix resolved if it's a string variable the headers set
        // (swi-decoding-table: SoundCtrlSWI_Name, ..., with SoundCtrlSWI_Name SETS "SoundCtrl").
        Map<String, String> strings = new HashMap<>();
        for (Path root : treeRoots)
            for (String[] c : cmhgSwis.computeIfAbsent(root, Server::cmhgSwis)) {
                String prefix = strings.computeIfAbsent(c[0], k -> {
                    for (EntryIndex e : m.entries.values()) {
                        Sym v = e.symbols.get(k);
                        if (v != null && v.kind == Symbol.Kind.VAR_S && v.value != null) {
                            String t = v.value.strip();
                            if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) return t.substring(1, t.length() - 1);
                            return t;
                        }
                    }
                    return k;
                });
                out.putIfAbsent(prefix + "_" + c[1], c[2]);
                out.putIfAbsent("X" + prefix + "_" + c[1], c[3]);
            }
        swis = out;
        swisFor = m;
        return out;
    }

    /** A value's number, as {@code &400C0}, from its display form; or null. */
    private static String number(String value) {
        if (value == null) return null;
        Matcher x = Pattern.compile("^&[0-9A-Fa-f]+").matcher(value);
        return x.find() ? x.group() : null;
    }

    /** The SWIs the CMHG files of a tree's components declare: {prefix, name, number, X number}. */
    static List<String[]> cmhgSwis(Path root) {
        List<String[]> out = new ArrayList<>();
        Path sources = root.resolve("Sources");
        if (!Files.isDirectory(sources)) return out;
        try (var walk = Files.walk(sources)) {
            walk.filter(f -> Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(f -> f.getParent() != null && f.getParent().getFileName().toString().equalsIgnoreCase("cmhg")
                            || f.getFileName().toString().endsWith(".cmhg"))
                    .forEach(f -> {
                        try {
                            cmhgSwis(Files.readString(f, StandardCharsets.ISO_8859_1), out);
                        } catch (IOException | RuntimeException e) {
                            // not readable: none from it
                        }
                    });
        } catch (IOException | RuntimeException e) {
            // as many as were found
        }
        return out;
    }

    /**
     * A CMHG file's SWIs: {@code swi-decoding-table: Prefix Name1 Name2/handler ...}
     * gives {@code Prefix_Name1}, {@code Prefix_Name2} and so on, numbered from
     * {@code swi-chunk-base-number} if that is a number.
     */
    static void cmhgSwis(String text, List<String[]> out) {
        StringBuilder b = new StringBuilder();
        for (String l : text.split("\r?\n", -1)) {
            if (l.stripLeading().startsWith("#")) continue;        // the C preprocessor's
            int c = l.indexOf(';');
            b.append(c >= 0 ? l.substring(0, c) : l).append('\n');
        }
        Map<String, String> fields = new HashMap<>();
        Matcher k = Pattern.compile("(?m)^([a-z][a-z0-9-]*):").matcher(b);
        int start = -1;
        String key = null;
        while (k.find()) {
            if (key != null) fields.put(key, b.substring(start, k.start()));
            key = k.group(1);
            start = k.end();
        }
        if (key != null) fields.put(key, b.substring(start));
        String table = fields.get("swi-decoding-table");
        if (table == null) return;
        Long base = null;
        String bn = fields.getOrDefault("swi-chunk-base-number", "").strip();
        try {
            if (bn.startsWith("0x") || bn.startsWith("0X")) base = Long.parseLong(bn.substring(2), 16);
            else if (bn.startsWith("&")) base = Long.parseLong(bn.substring(1), 16);
            else if (!bn.isEmpty()) base = Long.parseLong(bn);
        } catch (NumberFormatException e) {
            base = null;
        }
        String[] t = table.strip().split("[\\s,]+");
        if (t.length < 2) return;
        String prefix = t[0];
        for (int i = 1; i < t.length; i++) {
            String name = t[i];
            int slash = name.indexOf('/');
            if (slash >= 0) name = name.substring(0, slash);
            if (name.isEmpty() || !name.matches("[A-Za-z0-9_]+")) continue;
            String num = base == null ? null : "&" + Long.toHexString(base + i - 1).toUpperCase(Locale.ROOT);
            String xnum = base == null ? null : "&" + Long.toHexString((base + i - 1) | 0x20000).toUpperCase(Locale.ROOT);
            out.add(new String[] {prefix, name, num, xnum});
        }
    }

    private Object completion(Map<String, Object> p) {
        Model m = model();
        At a = at(p, m);
        Doc d = docs.get(a.file);
        if (d == null) d = docs.get(fileOf((String) obj(p.get("textDocument")).get("uri")));
        String line = d == null ? lineText(a.file, a.line) : lineOf(d.text, a.line - 1);
        String before = line.substring(0, Math.min(a.col, line.length()));
        if (inComment(before)) return List.of();
        // A quote is a trigger for BASIC's SYS "...; in assembler, nothing to offer.
        if ("\"".equals(obj(p.get("context")).get("triggerCharacter"))) return List.of();
        int ps = before.length();
        while (ps > 0 && isIdent(before.charAt(ps - 1))) ps--;
        String prefix = before.substring(ps);
        List<Object> items = new ArrayList<>();
        boolean[] incomplete = {false};

        Matcher ll = LOCAL_REF.matcher(before);
        if (ll.find()) {
            completeLocals(m, a, ll.group(1), items);
        } else if (ps > 0 && before.charAt(ps - 1) == '$') {
            completeVariables(m, a, prefix, items, incomplete);
        } else if (!before.isEmpty() && !Character.isWhitespace(before.charAt(0)) && before.indexOf(' ') < 0) {
            return List.of();                                  // writing a label
        } else if (isOperationField(before, ps)) {
            completeOperations(m, a, prefix, items, incomplete);
        } else {
            completeSymbols(m, a, prefix, items, incomplete);
        }
        return Map.of("isIncomplete", incomplete[0], "items", items);
    }

    private static final Pattern LOCAL_REF = Pattern.compile("%[FBfb]?[ATat]?(\\d*)$");

    private static boolean isIdent(char c) { return Character.isLetterOrDigit(c) || c == '_'; }

    private static boolean inComment(String s) {
        boolean str = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') str = !str;
            else if (c == ';' && !str) return true;
        }
        return false;
    }

    /** Whether the word ending at the cursor is the operation: the second field. */
    private static boolean isOperationField(String before, int ps) {
        int i = 0, n = before.length();
        while (i < n && !Character.isWhitespace(before.charAt(i))) i++;   // label, possibly empty
        while (i < n && Character.isWhitespace(before.charAt(i))) i++;
        return i == ps;
    }

    private void completeLocals(Model m, At a, String digits, List<Object> items) {
        String typed = digits.replaceFirst("^0+", "");
        int[] scope = enclosingFold(m, a, "rout");
        Map<Integer, Local> best = new TreeMap<>();
        for (EntryIndex e : m.readers(a.file))
            for (Local l : e.locals) {
                if (!l.def.file.equals(a.file) || l.def.via != null) continue;
                if (scope != null && (l.def.line < scope[0] || l.def.line > scope[1])) continue;
                if (!String.valueOf(l.number).startsWith(typed)) continue;
                best.putIfAbsent(l.number, l);
            }
        for (Local l : best.values()) {
            // As written at the definition, e.g. "01", replacing the digits typed.
            String text = textAt(l.def);
            if (text.isEmpty() || !Character.isDigit(text.charAt(0))) text = String.valueOf(l.number);
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("label", text);
            it.put("filterText", text);
            it.put("textEdit", Map.of("range", range(a.line - 1, a.col - digits.length(), a.line - 1, a.col), "newText", text));
            it.put("kind", 18);
            it.put("detail", "local label" + (l.routine == null ? "" : " in " + l.routine) + ", line " + l.def.line);
            items.add(it);
        }
    }

    private void completeVariables(Model m, At a, String prefix, List<Object> items, boolean[] incomplete) {
        Set<String> seen = new HashSet<>();
        int[] macro = enclosingFold(m, a, "macro");
        if (macro != null)
            for (EntryIndex e : m.readers(a.file))
                for (Mac x : e.macros.values())
                    if (x.def != null && x.def.file.equals(a.file) && x.def.line >= macro[0] && x.def.line <= macro[0] + 1)
                        for (String n : x.params)
                            if (n.startsWith(prefix) && seen.add(n)) items.add(item(n, 25, "parameter of " + x.name, null));
        for (Map.Entry<String, Sym> e : names(m, a.file).entrySet()) {
            Sym s = e.getValue();
            if (!s.kind.isVariable() || !matches(s.name, prefix) || !seen.add(s.name)) continue;
            if (items.size() >= MAX_COMPLETIONS) { incomplete[0] = true; break; }
            items.add(item(s.name, 6, kindAndValue(s), null));
        }
    }

    private void completeOperations(Model m, At a, String prefix, List<Object> items, boolean[] incomplete) {
        Set<String> seen = new HashSet<>();
        for (Directive d : Directive.values())
            if (matches(d.spelling, prefix) && seen.add(d.spelling)) items.add(item(d.spelling, 14, "directive", null));
        for (EntryIndex e : m.readers(a.file))
            for (Mac x : e.macros.values()) {
                if (!matches(x.name, prefix) || !seen.add(x.name)) continue;
                if (items.size() >= MAX_COMPLETIONS) { incomplete[0] = true; return; }
                items.add(item(x.name, 3, x.signature, x.def == null ? null : "Defined at " + where(x.def)));
            }
    }

    private void completeSymbols(Model m, At a, String prefix, List<Object> items, boolean[] incomplete) {
        for (Map.Entry<String, Sym> e : names(m, a.file).entrySet()) {
            Sym s = e.getValue();
            if (s.kind.isVariable() || !matches(s.name, prefix)) continue;
            if (items.size() >= MAX_COMPLETIONS) { incomplete[0] = true; break; }
            items.add(item(s.name, completionKind(s), kindAndValue(s), null));
        }
    }

    private static boolean matches(String name, String prefix) {
        return name.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static Map<String, Object> item(String label, int kind, String detail, String doc) {
        Map<String, Object> it = new LinkedHashMap<>();
        it.put("label", label);
        it.put("kind", kind);
        if (detail != null) it.put("detail", detail);
        if (doc != null) it.put("documentation", doc);
        return it;
    }

    /** Symbols visible in a file: from every entry point that reads it, by name. */
    @SuppressWarnings("unchecked")
    private static Map<String, Sym> names(Model m, String file) {
        return (Map<String, Sym>) m.cache.computeIfAbsent("names:" + file, k -> {
            Map<String, Sym> by = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            Map<String, Sym> exact = new HashMap<>();
            for (EntryIndex e : m.readers(file))
                for (Sym s : e.symbols.values()) if (!s.defs.isEmpty() && exact.putIfAbsent(s.name, s) == null) by.put(s.name, s);
            // A case-insensitive map would merge names differing only in case; keep them all.
            Map<String, Sym> out = new LinkedHashMap<>();
            List<String> names = new ArrayList<>(exact.keySet());
            names.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(x -> x));
            for (String n : names) out.put(n, exact.get(n));
            return out;
        });
    }

    /** The innermost fold of {@code kind} containing the position, as {start, end}; null if none. */
    private static int[] enclosingFold(Model m, At a, String kind) {
        int[] best = null;
        for (EntryIndex e : m.readers(a.file))
            for (Object[] f : e.folds.getOrDefault(a.file, List.of())) {
                if (!kind.equals(f[2])) continue;
                int s = (Integer) f[0], t = (Integer) f[1];
                if (a.line < s || a.line > t) continue;
                if (best == null || t - s < best[1] - best[0]) best = new int[] {s, t};
            }
        return best;
    }

    private Object documentSymbols(Map<String, Object> p) {
        Model m = model();
        String file = m.canonical(fileOf((String) obj(p.get("textDocument")).get("uri")));
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (EntryIndex e : m.readers(file))
            for (Object[] d : e.defsByFile.getOrDefault(file, List.of())) {
                String name;
                int kind;
                String detail;
                Site placed;
                if (d[0] instanceof Sym) {
                    Sym s = (Sym) d[0];
                    if (s.kind.isVariable()) continue;
                    name = s.name; kind = symbolKind(s);
                    // An EXPORTed label is one of the component's entry points: say so.
                    detail = (s.flags.contains("exported") ? "exported " : "") + kindAndValue(s);
                    placed = refine(EntryIndex.outermost((Site) d[1]), s.name);
                } else {
                    Mac x = (Mac) d[0];
                    name = x.name; kind = 25; detail = "MACRO " + x.signature; placed = (Site) d[1];
                }
                String k = name + "@" + placed.line + ":" + placed.col;
                if (out.containsKey(k)) continue;
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("name", name);
                s.put("detail", detail);
                s.put("kind", kind);
                Map<String, Object> sel = range(placed.line - 1, placed.col, placed.line - 1, placed.col + placed.len);
                s.put("range", range(placed.line - 1, 0, placed.line - 1, Math.max(placed.col + placed.len, lineText(placed.file, placed.line).length())));
                s.put("selectionRange", sel);
                out.put(k, s);
            }
        // The files it GETs, INCLUDEs or LNKs, as a C file's outline lists its #includes.
        for (EntryIndex e : m.readers(file))
            for (EntryIndex.Occ o : e.occurrences.getOrDefault(file, List.of())) {
                if (!(o.target instanceof EntryIndex.Get)) continue;
                String text = lineText(file, o.line);
                if (o.col + o.len > text.length()) continue;
                String name = text.substring(o.col, o.col + o.len);
                Matcher dm = INCLUDE_DIRECTIVE.matcher(text.substring(0, o.col));
                String directive = dm.find() ? dm.group(1).toUpperCase(Locale.ROOT) : "GET";
                String k = name + "@" + o.line + ":" + o.col;
                if (out.containsKey(k)) continue;
                Map<String, Object> sym = new LinkedHashMap<>();
                sym.put("name", name);
                sym.put("detail", directive);
                sym.put("kind", 1);                                     // File
                sym.put("range", range(o.line - 1, 0, o.line - 1, text.length()));
                sym.put("selectionRange", range(o.line - 1, o.col, o.line - 1, o.col + o.len));
                out.put(k, sym);
            }
        List<Map<String, Object>> l = new ArrayList<>(out.values());
        l.sort((x, y) -> Integer.compare(line(x), line(y)));
        return l;
    }

    /** The directive before a GET's file name. */
    private static final Pattern INCLUDE_DIRECTIVE = Pattern.compile("(?i)\\b(GET|INCLUDE|LNK)\\s+$");

    @SuppressWarnings("unchecked")
    private static int line(Map<String, Object> s) {
        return (Integer) ((Map<String, Object>) ((Map<String, Object>) s.get("selectionRange")).get("start")).get("line");
    }

    private Object workspaceSymbols(Map<String, Object> p) {
        List<Object> out = basic.workspaceSymbols(String.valueOf(p.getOrDefault("query", "")));
        if (indexer == null) return out;
        Model m = model();
        String q = String.valueOf(p.getOrDefault("query", "")).toLowerCase(Locale.ROOT);
        Set<String> seen = new HashSet<>();
        List<String> names = new ArrayList<>(symbolsByName(m).keySet());
        names.sort(null);
        outer:
        for (String n : names) {
            if (!n.toLowerCase(Locale.ROOT).contains(q)) continue;
            for (Sym s : symbolsByName(m).get(n)) {
                if (s.defs.isEmpty() || s.kind.isVariable()) continue;
                Site d = refine(EntryIndex.outermost(s.defs.get(0)), s.name);
                if (!seen.add(n + "@" + d)) continue;
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("name", n);
                x.put("kind", symbolKind(s));
                x.put("location", location(d));
                x.put("containerName", kindAndValue(s));
                out.add(x);
                if (out.size() >= MAX_WORKSPACE_SYMBOLS) break outer;
            }
        }
        return out;
    }

    private Object foldingRanges(Map<String, Object> p) {
        Model m = model();
        String file = m.canonical(fileOf((String) obj(p.get("textDocument")).get("uri")));
        Map<String, Object> out = new TreeMap<>();
        if (m.readers(file).isEmpty()) {
            // No entry point reads this file, so fold by its text alone.
            Doc d = docs.get(fileOf((String) obj(p.get("textDocument")).get("uri")));
            String text = d != null ? d.text : null;
            if (text == null) {
                try {
                    text = new String(Files.readAllBytes(Path.of(file)), StandardCharsets.ISO_8859_1);
                } catch (IOException e) {
                    return List.of();
                }
            }
            List<Object> l = new ArrayList<>();
            for (int[] r : SyntaxFolds.of(Path.of(file), text))
                l.add(Map.of("startLine", r[0] - 1, "endLine", r[1] - 1, "kind", "region"));
            return l;
        }
        for (EntryIndex e : m.readers(file))
            for (Object[] f : e.folds.getOrDefault(file, List.of())) {
                int s = (Integer) f[0], t = (Integer) f[1];
                String k = String.format("%08d:%08d", s, t);
                if (out.containsKey(k)) continue;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("startLine", s - 1);
                r.put("endLine", t - 1);
                r.put("kind", "region");
                out.put(k, r);
            }
        return new ArrayList<>(out.values());
    }

    // =====================================================================
    // Kinds
    // =====================================================================

    /** LSP SymbolKind. */
    private static int symbolKind(Sym s) {
        switch (s.kind) {
            case LABEL: return s.flags.contains("routine") ? 12 : 6;     // Function, Method
            case FIELD: return 8;                                         // Field
            case REGISTER: case FP_REGISTER: case EXT_REGISTER: case COPROC_REGISTER: case COPROCESSOR:
            case REGISTER_LIST: return 22;                                // EnumMember
            case VAR_A: case VAR_L: case VAR_S: return 13;                // Variable
            case EXTERNAL: return 11;                                     // Interface
            default: return 14;                                           // Constant
        }
    }

    /** LSP CompletionItemKind. */
    private static int completionKind(Sym s) {
        switch (s.kind) {
            case LABEL: return s.flags.contains("routine") ? 3 : 18;     // Function, Reference
            case FIELD: return 5;
            case REGISTER: case FP_REGISTER: case EXT_REGISTER: case COPROC_REGISTER: case COPROCESSOR:
            case REGISTER_LIST: return 20;
            case VAR_A: case VAR_L: case VAR_S: return 6;
            case EXTERNAL: return 8;
            default: return 21;
        }
    }

    /** A value for display: hex with decimal for larger numbers, and labels relative to their area. */
    public static String formatValue(Value v, String area) {
        if (v == null) return null;
        switch (v.kind) {
            case NUM: {
                String h = "&" + Integer.toHexString(v.num).toUpperCase(Locale.ROOT);
                return v.num >= 0 && v.num < 10 ? h : h + " (" + v.num + ")";
            }
            case PROGREL:
                if (area != null) return area + " + &" + Integer.toHexString(v.num).toUpperCase(Locale.ROOT);
                return v.display();
            default:
                return v.display();
        }
    }

    // =====================================================================
    // Sites, text and locations
    // =====================================================================

    private Model model() { return indexer == null ? Model.EMPTY : indexer.model(); }

    /**
     * A macro call site narrowed to the argument that names {@code name}: the
     * longest identifier in the operands that the name contains, e.g. WriteC
     * in {@code AddSWI WriteC} for OS_WriteC. Otherwise the call itself.
     */
    private Site refine(Site call, String name) {
        if (name == null) return call;
        String t = lineText(call.file, call.line);
        int best = -1, bestLen = 1;
        int i = Math.min(t.length(), call.col + call.len);
        while (i < t.length()) {
            char c = t.charAt(i);
            if (c == ';') break;
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < t.length() && isIdent(t.charAt(j))) j++;
                String w = t.substring(i, j);
                if (w.length() > bestLen && name.contains(w)) { best = i; bestLen = w.length(); }
                i = j;
            } else i++;
        }
        return best < 0 ? new Site(call.file, call.line, call.col, call.len, null) : new Site(call.file, call.line, best, bestLen, null);
    }

    private String textAt(Site s) {
        String t = lineText(s.file, s.line);
        return s.col + s.len <= t.length() ? t.substring(s.col, s.col + s.len) : "";
    }

    private String textOfLine(Site s) { return lineText(s.file, s.line); }

    /** A source line without its comment, and with runs of spaces collapsed. */
    static String withoutComment(String line) {
        boolean str = false;
        int i = 0;
        for (; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') str = !str;
            else if (c == ';' && !str) break;
        }
        return line.substring(0, i).trim().replaceAll(" +", " ");
    }

    private String lineText(String file, int line) {
        Doc d = docs.get(file);
        if (d != null) return lineOf(d.text, line - 1);
        SourceFile f = indexer == null ? null : indexer.source(file);
        return f == null ? "" : f.lineText(line);
    }

    private static String lineOf(String text, int line) {
        int a = offset(text, Map.of("line", (long) line, "character", 0L));
        int b = a;
        while (b < text.length() && text.charAt(b) != '\n' && text.charAt(b) != '\r') b++;
        return text.substring(a, b).replace('\t', ' ');
    }

    /** {@code path:line}, relative to its tree when inside one. */
    private String where(Site s) {
        String f = s.file;
        Path p = Path.of(f);
        for (Path root : treeRoots)
            if (p.startsWith(root)) {
                f = root.relativize(p).toString();
                break;
            }
        return "`" + f + "` line " + s.line;
    }

    /** The RiscOS directory (with Env and BuildSys) above a tree's entry points, or their common directory. */
    private static Path treeRootOf(Path common) {
        for (Path d = common; d != null; d = d.getParent())
            if (Files.isDirectory(d.resolve("Env")) && Files.isDirectory(d.resolve("BuildSys"))) return d;
        return common;
    }

    /** The deepest directory containing every entry point. */
    private static Path commonRoot(Collection<EntryPoint> entries) {
        Path root = null;
        for (EntryPoint e : entries) {
            Path p = e.file.toAbsolutePath().normalize().getParent();
            if (root == null) root = p;
            else while (root != null && !p.startsWith(root)) root = root.getParent();
        }
        return root;
    }

    private Map<String, Object> location(Site s) {
        return Map.of("uri", uriOf(s.file), "range", range(s.line - 1, s.col, s.line - 1, s.col + s.len));
    }

    /** A diagnostic's range: the site, or the rest of its line if it has no length. */
    private Map<String, Object> lineRange(Site s) {
        int end = s.col + s.len;
        if (s.len == 0) end = Math.max(s.col, lineText(s.file, s.line).length());
        return range(s.line - 1, s.col, s.line - 1, end);
    }

    static Map<String, Object> range(int l0, int c0, int l1, int c1) {
        return Map.of("start", Map.of("line", l0, "character", c0), "end", Map.of("line", l1, "character", c1));
    }

    /** The scheme of {@link #uriStyle} {@code links} URIs: a file named by a link, not by what it points to. */
    static final String LINK_SCHEME = "riscos-link";

    /**
     * How file locations are written, from {@code initializationOptions.uriStyle}.
     * A client's own lookup may resolve symbolic links, which makes it open a
     * link farm file ({@code objs/Wimp10.s}) by its real name ({@code s/Wimp10}):
     * <ul>
     * <li>{@code plain} (the default): {@code file:} URIs, by objasm's names;
     * <li>{@code real}: {@code file:} URIs of the real files, links resolved;
     * <li>{@code links}: as {@code plain}, except that a file named through a
     *     link is given as {@code riscos-link:/path}, for a client that maps
     *     that scheme to the link itself.
     * </ul>
     */
    private String uriStyle = "plain";

    private String uriOf(String file) {
        Doc d = docs.get(file);
        // An open document is normally named as its editor names it, but in
        // links style a client's lookup of that file: URI would resolve the link.
        if (d != null && !(uriStyle.equals("links") && throughLink(Path.of(file)))) return d.uri;
        Path p = Path.of(file);
        switch (uriStyle) {
            case "real":
                try {
                    return p.toRealPath().toUri().toString();
                } catch (IOException | RuntimeException e) {
                    return p.toUri().toString();
                }
            case "links":
                if (throughLink(p)) {
                    try {
                        return new URI(LINK_SCHEME, null, p.toAbsolutePath().normalize().toUri().getPath(), null).toString();
                    } catch (java.net.URISyntaxException e) {
                        return p.toUri().toString();
                    }
                }
                return p.toUri().toString();
            default:
                return p.toUri().toString();
        }
    }

    /** Whether a path goes through a symbolic link: the file itself or a directory on the way. */
    private static boolean throughLink(Path p) {
        try {
            Path abs = p.toAbsolutePath().normalize();
            return !abs.toRealPath().equals(abs);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static String fileOf(String uri) {
        URI u = URI.create(uri);
        // riscos-text: a file edited converted (tokenised BASIC as text), by its own name.
        if (LINK_SCHEME.equals(u.getScheme()) || "riscos-text".equals(u.getScheme()))
            return Path.of(URI.create("file:" + u.getRawPath())).toAbsolutePath().normalize().toString();
        return Path.of(u).toAbsolutePath().normalize().toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    static int intOf(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }
}
