package com.piccolosystems.riscos.asmindex.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.piccolosystems.riscos.asmindex.basic.BasicFile;
import com.piccolosystems.riscos.asmindex.basic.BasicFile.Call;
import com.piccolosystems.riscos.asmindex.basic.BasicFile.Routine;
import com.piccolosystems.riscos.asmindex.basic.BasicFile.Site;
import com.piccolosystems.riscos.asmindex.basic.BasicFile.Use;
import com.piccolosystems.riscos.asmindex.basic.BasicFile.Var;

/**
 * The language server's BBC BASIC support. Each file is indexed on its own:
 * BASIC programs rarely refer to other files, except through libraries loaded
 * at run time. Positions are 0-based lines and columns.
 */
final class Basic {
    /** Open BASIC documents by the URI they were opened by. */
    private final Map<String, BasicFile> files = new ConcurrentHashMap<>();
    /** The SWIs the build trees name, and their numbers, for {@code SYS "...}. */
    private final java.util.function.Supplier<Map<String, String>> swis;

    Basic() {
        this(Map::of);
    }

    Basic(java.util.function.Supplier<Map<String, String>> swis) {
        this.swis = swis;
    }
    /** That URI by file path: a request may spell a file's URI differently ({@code file:/} or {@code file:///}). */
    private final Map<String, String> uris = new ConcurrentHashMap<>();

    BasicFile update(String uri, String text) {
        BasicFile f = new BasicFile(text);
        files.put(uri, f);
        uris.put(Server.fileOf(uri), uri);
        return f;
    }

    void close(String uri) {
        files.remove(uri);
        uris.remove(Server.fileOf(uri));
    }

    /** The URI an open BASIC file was opened by, given any URI for it; null if it isn't one. */
    String opened(String uri) {
        try {
            return uris.get(Server.fileOf(uri));
        } catch (RuntimeException e) {
            return null;
        }
    }

    BasicFile get(String uri) {
        return files.get(uri);
    }

    // ------------------------------------------------------------------ neighbours

    /**
     * The routines defined by the other BASIC files in a file's directory, by
     * name, with the file that defines each. A program built by joining
     * several files (as Alarm is) calls routines in the others; this is only
     * used to say so, the file still being indexed on its own.
     */
    Map<String, String> neighbours(String uri) {
        Path self;
        try {
            self = Path.of(Server.fileOf(uri));
        } catch (RuntimeException e) {
            return Map.of();
        }
        Path dir = self.getParent();
        Map<String, String> out = new LinkedHashMap<>();
        if (dir == null) return out;
        java.util.List<Path> files = new ArrayList<>();
        try (var list = java.nio.file.Files.list(dir)) {
            list.filter(f -> isBasicName(f.getFileName().toString()) && !f.equals(self)).sorted().limit(200)
                    .forEach(files::add);
        } catch (java.io.IOException e) {
            return out;
        }
        for (Path f : files) for (String r : routinesOf(f)) out.putIfAbsent(r, f.getFileName().toString());
        return out;
    }

    private static boolean isBasicName(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        return l.endsWith(".bas") || l.endsWith(".bbc") || l.endsWith(",fd1") || l.endsWith(",ffb");
    }

    private record Defs(long modified, java.util.List<String> names) {}

    private final Map<Path, Defs> defsCache = new ConcurrentHashMap<>();
    private static final Pattern DEF = Pattern.compile("(?m)^\\s*\\d*\\s*DEF\\s*((?:PROC|FN)[A-Za-z0-9_`]+)");

    /** The routines a file defines, from its DEF lines (cached until it changes). */
    private java.util.List<String> routinesOf(Path f) {
        try {
            long m = java.nio.file.Files.getLastModifiedTime(f).toMillis();
            Defs d = defsCache.get(f);
            if (d != null && d.modified == m) return d.names;
            byte[] data = java.nio.file.Files.readAllBytes(f);
            if (data.length > 4_000_000) return java.util.List.of();
            String text = com.piccolosystems.riscos.asmindex.text.BasicTokens.isTokenised(data)
                    ? com.piccolosystems.riscos.asmindex.text.BasicTokens.detokenise(data).text()
                    : new String(data, java.nio.charset.StandardCharsets.ISO_8859_1);
            java.util.List<String> names = new ArrayList<>();
            Matcher x = DEF.matcher(text);
            while (x.find()) names.add(x.group(1));
            defsCache.put(f, new Defs(m, names));
            return names;
        } catch (java.io.IOException | RuntimeException e) {
            return java.util.List.of();
        }
    }

    // ------------------------------------------------------------------ targets

    /** What a position names: a routine, a variable (in a scope) or a line number. */
    record Target(String routine, String var, int scope, Integer lineNumber) {}

    /** The scope of a variable site: its routine if the variable is local there, else -1 (global). */
    static int scope(BasicFile f, Var v) {
        return v.routine() >= 0 && f.routines.get(v.routine()).locals.contains(v.key()) ? v.routine() : -1;
    }

    static Target at(BasicFile f, int line, int col) {
        for (Routine r : f.routines) if (r.site.contains(line, col)) return new Target(r.name, null, 0, null);
        for (Call c : f.calls) if (c.site().contains(line, col)) return new Target(c.name(), null, 0, null);
        for (Var v : f.vars) if (v.site().contains(line, col)) return new Target(null, v.key(), scope(f, v), null);
        for (BasicFile.LineRef r : f.lineRefs) if (r.site().contains(line, col)) return new Target(null, null, 0, r.number());
        // A line number at the start of its line.
        Matcher m = Pattern.compile("^\\s*(\\d+)").matcher(f.lines.get(line));
        if (m.find() && col >= m.start(1) && col <= m.end(1))
            return new Target(null, null, 0, Integer.parseInt(m.group(1)));
        return null;
    }

    /** A variable's sites in its scope. */
    static List<Var> sites(BasicFile f, Target t) {
        List<Var> out = new ArrayList<>();
        for (Var v : f.vars) if (v.key().equals(t.var()) && scope(f, v) == t.scope()) out.add(v);
        return out;
    }

    /** A variable's declarations: its LOCAL, parameter or DIM. */
    static List<Var> explicitDeclarations(BasicFile f, Target t) {
        List<Var> out = new ArrayList<>();
        for (Var v : sites(f, t))
            if (v.use() == Use.LOCAL || v.use() == Use.PARAMETER || v.use() == Use.DIM) out.add(v);
        return out;
    }

    /** Where a variable is given a value: {@code =}, FOR, READ, INPUT, SWAP, SYS … TO. */
    static List<Var> assignments(BasicFile f, Target t) {
        List<Var> out = new ArrayList<>();
        for (Var v : sites(f, t)) if (v.use() == Use.ASSIGN) out.add(v);
        return out;
    }

    /** Where a variable is declared: its LOCAL, parameter or DIM, else wherever it's assigned. */
    static List<Var> declarations(BasicFile f, Target t) {
        List<Var> out = explicitDeclarations(f, t);
        return out.isEmpty() ? assignments(f, t) : out;
    }

    // ------------------------------------------------------------------ requests

    List<Object> definition(String uri, int line, int col) {
        BasicFile f = files.get(uri);
        Target t = f == null ? null : at(f, line, col);
        List<Object> out = new ArrayList<>();
        if (t == null) return out;
        if (t.routine() != null) {
            for (Routine r : f.routines) if (r.name.equals(t.routine())) out.add(location(uri, r.site));
        } else if (t.var() != null) {
            // A declared variable's declarations are its declaration (see below); one that isn't
            // declared is defined by its assignments, so that Go to Definition has somewhere to go.
            if (explicitDeclarations(f, t).isEmpty())
                for (Var v : assignments(f, t)) out.add(location(uri, v.site()));
        } else {
            Integer l = f.lineNumbers.get(t.lineNumber());
            if (l != null) {
                Matcher m = Pattern.compile("^\\s*(\\d+)").matcher(f.lines.get(l));
                if (m.find()) out.add(location(uri, new Site(l, m.start(1), m.end(1) - m.start(1))));
            }
        }
        return out;
    }

    /** A variable's LOCAL, parameter or DIM. */
    List<Object> declaration(String uri, int line, int col) {
        BasicFile f = files.get(uri);
        Target t = f == null ? null : at(f, line, col);
        List<Object> out = new ArrayList<>();
        if (t != null && t.var() != null) for (Var v : explicitDeclarations(f, t)) out.add(location(uri, v.site()));
        return out;
    }

    /** Where a variable is assigned. */
    List<Object> assignments(String uri, int line, int col) {
        BasicFile f = files.get(uri);
        Target t = f == null ? null : at(f, line, col);
        List<Object> out = new ArrayList<>();
        if (t != null && t.var() != null) for (Var v : assignments(f, t)) out.add(location(uri, v.site()));
        return out;
    }

    List<Object> references(String uri, int line, int col, boolean includeDeclaration) {
        List<Object> out = new ArrayList<>();
        for (Site s : referenceSites(uri, line, col, includeDeclaration).keySet()) out.add(location(uri, s));
        return out;
    }

    /** Each reference, and whether it writes (for highlighting). */
    Map<Site, Boolean> referenceSites(String uri, int line, int col, boolean includeDeclaration) {
        BasicFile f = files.get(uri);
        Target t = f == null ? null : at(f, line, col);
        Map<Site, Boolean> out = new LinkedHashMap<>();
        if (t == null) return out;
        if (t.routine() != null) {
            if (includeDeclaration) for (Routine r : f.routines) if (r.name.equals(t.routine())) out.put(r.site, true);
            for (Call c : f.calls) if (c.name().equals(t.routine())) out.put(c.site(), false);
        } else if (t.var() != null) {
            Set<Var> decl = new java.util.HashSet<>(declarations(f, t));
            for (Var v : sites(f, t))
                if (includeDeclaration || !decl.contains(v)) out.put(v.site(), v.use() != Use.READ);
        } else {
            Integer l = f.lineNumbers.get(t.lineNumber());
            for (BasicFile.LineRef r : f.lineRefs) if (r.number() == t.lineNumber()) out.put(r.site(), false);
            if (includeDeclaration && l != null) {
                Matcher m = Pattern.compile("^\\s*(\\d+)").matcher(f.lines.get(l));
                if (m.find()) out.put(new Site(l, m.start(1), m.end(1) - m.start(1)), true);
            }
        }
        return out;
    }

    List<Object> highlights(String uri, int line, int col) {
        List<Object> out = new ArrayList<>();
        for (Map.Entry<Site, Boolean> e : referenceSites(uri, line, col, true).entrySet())
            out.add(Map.of("range", range(e.getKey()), "kind", e.getValue() ? 3 : 2));
        return out;
    }

    String hover(String uri, int line, int col) {
        BasicFile f = files.get(uri);
        Target t = f == null ? null : at(f, line, col);
        if (t == null) return null;
        StringBuilder b = new StringBuilder();
        if (t.routine() != null) {
            List<Routine> defs = new ArrayList<>();
            for (Routine r : f.routines) if (r.name.equals(t.routine())) defs.add(r);
            int calls = 0;
            for (Call c : f.calls) if (c.name().equals(t.routine())) calls++;
            if (defs.isEmpty()) {
                b.append('`').append(t.routine()).append("` isn't defined in this file");
                String other = neighbours(uri).get(t.routine());
                if (other != null) b.append(", but in `").append(other).append("` beside it");
                else if (f.loadsLibraries) b.append(": it may come from a library the program loads");
                b.append('.');
            } else {
                Routine r = defs.get(0);
                b.append("```\n").append(r.signature).append("\n```");
                String doc = comment(f, r.site.line());
                if (!doc.isEmpty()) b.append("\n\n").append(doc);
                b.append("\n\nDefined at line ").append(r.site.line() + 1);
                if (defs.size() > 1) b.append(", and again at line ").append(defs.get(1).site.line() + 1)
                        .append(" (the first is the one used)");
            }
            b.append(calls == 0 ? "\n\nNot called in this file." : "\n\nCalled " + times(calls) + " in this file.");
        } else if (t.var() != null) {
            String k = t.var();
            boolean array = k.endsWith("(");
            String name = array ? k.substring(0, k.length() - 1) : k;
            String type = name.endsWith("%") ? "integer" : name.endsWith("$") ? "string" : "real";
            b.append('`').append(k).append(array ? ")" : "").append("`: ")
                    .append(array ? "array of " + (type.equals("integer") ? "integers" : type + "s") : type + " variable");
            if (resident(k)) {
                b.append(", one of BASIC's resident integer variables");
            } else if (t.scope() >= 0) {
                Routine r = f.routines.get(t.scope());
                boolean param = false;
                for (Var v : sites(f, t)) if (v.use() == Use.PARAMETER) param = true;
                b.append(", ").append(param ? "a parameter of " : "local to ").append("`").append(r.name).append('`');
            } else {
                b.append(", global");
            }
            List<Var> dims = new ArrayList<>();
            for (Var v : explicitDeclarations(f, t)) if (v.use() == Use.DIM) dims.add(v);
            List<Var> assigns = array || !dims.isEmpty() ? dims : assignments(f, t);
            if (t.scope() >= 0 || resident(k)) {
                // declared by LOCAL or as a parameter; nothing more to say
            } else if (assigns.isEmpty()) {
                b.append("\n\nNever ").append(array ? "dimensioned" : "assigned").append(" in this file.");
            } else if (assigns.size() == 1) {
                Var v = assigns.get(0);
                b.append("\n\n").append(!dims.isEmpty() ? "Dimensioned" : "Assigned once, so declared").append(" at line ")
                        .append(v.site().line() + 1).append(":\n\n```\n").append(f.lines.get(v.site().line()).strip())
                        .append("\n```");
            } else {
                b.append(!dims.isEmpty() ? "\n\nDimensioned in " : "\n\nAssigned in ").append(assigns.size()).append(" places:\n\n```\n");
                int n = 0;
                for (Var v : assigns) {
                    if (n++ == 8) {
                        b.append("…\n");
                        break;
                    }
                    b.append(String.format("%5d  ", v.site().line() + 1)).append(f.lines.get(v.site().line()).strip())
                            .append('\n');
                }
                b.append("```");
            }
        } else {
            Integer l = f.lineNumbers.get(t.lineNumber());
            if (l == null) return "Line " + t.lineNumber() + " isn't in this file.";
            b.append("```\n").append(f.lines.get(l).strip()).append("\n```");
        }
        return b.toString();
    }

    private static String times(int n) {
        return n == 1 ? "once" : n == 2 ? "twice" : n + " times";
    }

    /** BASIC's resident integer variables A% to Z%, and @%. */
    static boolean resident(String key) {
        return key.length() == 2 && key.charAt(1) == '%' && (key.charAt(0) >= 'A' && key.charAt(0) <= 'Z' || key.charAt(0) == '@');
    }

    /** The REM lines just above a line, without REM: a routine's description, as is usual. */
    static String comment(BasicFile f, int line) {
        List<String> out = new ArrayList<>();
        Pattern rem = Pattern.compile("^\\s*\\d*\\s*REM\\s?(.*)$");
        for (int l = line - 1; l >= 0; l--) {
            Matcher m = rem.matcher(f.lines.get(l));
            if (!m.matches()) break;
            String s = m.group(1).replaceAll("^[*=\\-#>]+\\s*|[*=\\-#]+$", "").strip();
            out.add(0, s);
        }
        while (!out.isEmpty() && out.get(0).isEmpty()) out.remove(0);
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return String.join("  \n", out);
    }

    /** The document symbols of a BASIC file that isn't open, read (and detokenised) from disk. */
    List<Object> documentSymbolsOnDisk(String uri) {
        try {
            byte[] data = java.nio.file.Files.readAllBytes(Path.of(Server.fileOf(uri)));
            if (data.length > 4_000_000) return List.of();
            String text = com.piccolosystems.riscos.asmindex.text.BasicTokens.isTokenised(data)
                    ? com.piccolosystems.riscos.asmindex.text.BasicTokens.detokenise(data).text()
                    : new String(data, java.nio.charset.StandardCharsets.ISO_8859_1);
            return documentSymbols(new BasicFile(text));
        } catch (java.io.IOException | RuntimeException e) {
            return List.of();
        }
    }

    List<Object> documentSymbols(String uri) {
        return documentSymbols(files.get(uri));
    }

    private List<Object> documentSymbols(BasicFile f) {
        List<Object> out = new ArrayList<>();
        if (f == null) return out;
        for (Routine r : f.routines) {
            int end = Math.max(r.endLine, r.site.line());
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", r.name);
            s.put("detail", r.signature);
            s.put("kind", r.isFunction() ? 12 : 6);
            s.put("range", Server.range(r.site.line(), 0, end, f.lines.get(end).length()));
            s.put("selectionRange", range(r.site));
            out.add(s);
        }
        return out;
    }

    /** Workspace symbols: the routines of every open BASIC file. */
    List<Object> workspaceSymbols(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, BasicFile> e : files.entrySet())
            for (Routine r : e.getValue().routines)
                if (r.name.toLowerCase(Locale.ROOT).contains(q))
                    out.add(Map.of("name", r.name, "kind", r.isFunction() ? 12 : 6, "location", location(e.getKey(), r.site),
                            "containerName", r.signature));
        return out;
    }

    private static final Pattern ROUTINE_PREFIX = Pattern.compile("(PROC|FN)([A-Za-z0-9_`]*)$");
    /** Inside the string naming a SWI: {@code SYS "OS_Wr}. */
    private static final Pattern SYS_NAME = Pattern.compile("\\bSYS\\s*\"([A-Za-z0-9_]*)$");
    private static final Pattern VAR_PREFIX = Pattern.compile("(?<![A-Za-z0-9_`%$])([A-Za-z_`][A-Za-z0-9_`]*[%$]?)$");

    List<Object> completion(String uri, int line, int col) {
        BasicFile f = files.get(uri);
        List<Object> out = new ArrayList<>();
        if (f == null || line >= f.lines.size()) return out;
        String before = f.lines.get(line).substring(0, Math.min(col, f.lines.get(line).length()));
        Matcher m = SYS_NAME.matcher(before);
        if (m.find()) {
            String typed = m.group(1);
            for (Map.Entry<String, String> e : swis.get().entrySet()) {
                if (!e.getKey().regionMatches(true, 0, typed, 0, typed.length())) continue;
                out.add(item(e.getKey(), 21, e.getValue() == null ? "SWI" : "SWI " + e.getValue(), line, m.start(1), col));
            }
            return out;
        }
        if (before.chars().filter(c -> c == '"').count() % 2 == 1) return out;      // in some other string
        m = ROUTINE_PREFIX.matcher(before);
        if (m.find()) {
            Set<String> seen = new LinkedHashSet<>();
            for (Routine r : f.routines)
                if (r.name.startsWith(m.group(0)) && seen.add(r.name))
                    out.add(item(r.name, r.isFunction() ? 3 : 2, r.signature, line, m.start(), col));
            return out;
        }
        m = VAR_PREFIX.matcher(before);
        if (!m.find()) return out;
        // The routine the cursor is in, for its locals.
        int here = -1;
        for (int i = 0; i < f.routines.size(); i++) {
            Routine r = f.routines.get(i);
            if (line >= r.site.line() && line <= Math.max(r.endLine, r.site.line())) here = i;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Var v : f.vars) {
            int s = scope(f, v);
            if (s != -1 && s != here) continue;
            String k = v.key();
            if (!k.startsWith(m.group(1)) || k.equals(m.group(1)) || !seen.add(k)) continue;
            String shown = k.endsWith("(") ? k.substring(0, k.length() - 1) : k;
            out.add(item(shown, 6, s >= 0 ? "local to " + f.routines.get(s).name : "global", line, m.start(1), col));
        }
        return out;
    }

    private static Map<String, Object> item(String label, int kind, String detail, int line, int from, int to) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("label", label);
        i.put("kind", kind);
        i.put("detail", detail);
        i.put("textEdit", Map.of("range", Server.range(line, from, line, to), "newText", label));
        return i;
    }

    List<Object> foldingRanges(String uri) {
        BasicFile f = files.get(uri);
        List<Object> out = new ArrayList<>();
        if (f == null) return out;
        Set<String> seen = new java.util.HashSet<>();
        for (int[] r : f.folds)
            if (r[1] > r[0] && seen.add(r[0] + ":" + r[1]))
                out.add(Map.of("startLine", r[0], "endLine", r[1]));
        return out;
    }

    /** The file's problems, as LSP diagnostics. Information ones only if wanted. */
    List<Object> diagnostics(String uri, boolean showInfo) {
        BasicFile f = files.get(uri);
        List<Object> out = new ArrayList<>();
        if (f == null) return out;
        for (BasicFile.Diag d : f.diags) out.add(diag(d.site(), d.severity(), d.message()));
        Map<String, Routine> defined = new LinkedHashMap<>();
        for (Routine r : f.routines) {
            Routine first = defined.putIfAbsent(r.name, r);
            if (first != null)
                out.add(diag(r.site, BasicFile.WARNING, r.name + " is already defined at line " + (first.site.line() + 1)
                        + ", which is the one used"));
        }
        Map<String, String> beside = null;
        for (Call c : f.calls) {
            if (defined.containsKey(c.name())) continue;
            if (beside == null) beside = neighbours(uri);
            String other = beside.get(c.name());
            if (other != null) {
                if (showInfo) out.add(diag(c.site(), BasicFile.INFO, c.name() + " isn't defined in this file, but in "
                        + other + " beside it"));
            } else if (f.loadsLibraries) {
                if (showInfo) out.add(diag(c.site(), BasicFile.INFO, c.name() + " isn't defined in this file; "
                        + "it may come from a library the program loads"));
            } else {
                out.add(diag(c.site(), BasicFile.WARNING, c.name() + " isn't defined in this file"));
            }
        }
        for (BasicFile.LineRef r : f.lineRefs)
            if (!f.lineNumbers.containsKey(r.number()))
                out.add(diag(r.site(), BasicFile.WARNING, "There is no line " + r.number()));
        if (showInfo) {
            Map<String, Var> firstRead = new LinkedHashMap<>();
            Set<String> given = new java.util.HashSet<>();
            for (Var v : f.vars) {
                String id = scope(f, v) + ":" + v.key();
                // An element given a value doesn't dimension its array.
                if (v.use() == Use.READ || v.use() == Use.ASSIGN && v.key().endsWith("(")) firstRead.putIfAbsent(id, v);
                else given.add(id);
            }
            for (Map.Entry<String, Var> e : firstRead.entrySet()) {
                Var v = e.getValue();
                if (given.contains(e.getKey()) || resident(v.key())) continue;
                out.add(diag(v.site(), BasicFile.INFO, (v.key().endsWith("(") ? v.key() + ") is never dimensioned"
                        : v.key() + " is never assigned") + " in this file"));
            }
        }
        return out;
    }

    private static Map<String, Object> diag(Site s, int severity, String message) {
        return Map.of("range", range(s), "severity", severity, "source", "bbcbasic", "message", message);
    }

    private static Map<String, Object> location(String uri, Site s) {
        return Map.of("uri", uri, "range", range(s));
    }

    private static Map<String, Object> range(Site s) {
        return Server.range(s.line(), s.col(), s.line(), s.col() + s.len());
    }
}
