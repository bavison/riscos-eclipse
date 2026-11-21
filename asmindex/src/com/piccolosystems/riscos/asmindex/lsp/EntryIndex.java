package com.piccolosystems.riscos.asmindex.lsp;

import com.piccolosystems.riscos.asmindex.loc.LocalLabel;
import com.piccolosystems.riscos.asmindex.macro.MacroDef;
import com.piccolosystems.riscos.asmindex.run.Assembler;
import com.piccolosystems.riscos.asmindex.run.Diagnostic;
import com.piccolosystems.riscos.asmindex.run.EntryPoint;
import com.piccolosystems.riscos.asmindex.source.SourceFile;
import com.piccolosystems.riscos.asmindex.sym.Site;
import com.piccolosystems.riscos.asmindex.sym.Symbol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The results of one run of one entry point, kept in the form the language
 * server queries. Immutable once built; the Assembler is not kept.
 */
final class EntryIndex {
    /** A symbol, as one entry point sees it. */
    static final class Sym {
        final String name;
        final Symbol.Kind kind;
        final String value;           // display form, see Server.formatValue
        final String area;
        final int size;
        final List<String> flags;
        final List<Site> defs;
        final List<Site> uses;
        /** For a variable: its assignments (SETA and the like), which are among the uses too. */
        final List<Site> sets;

        Sym(Symbol s, String value, Interner in) {
            name = s.name; kind = s.kind; this.value = value; area = s.areaName; size = s.size;
            List<String> f = new ArrayList<>();
            if (s.exported) f.add("exported");
            if (s.routine) f.add("routine");
            if (s.conditional) f.add("conditional");
            if (s.approximate) f.add("approximate");
            if (s.status == Symbol.Status.PARTIAL) f.add("partly defined");
            flags = f.isEmpty() ? List.of() : List.copyOf(f);
            defs = in.sites(s.defs);
            uses = in.sites(s.uses);
            sets = s.sets.isEmpty() ? List.of() : in.sites(s.sets);
        }
    }

    static final class Mac {
        final String name, signature;
        final Site def;
        final List<Site> uses;
        final List<String> params;    // named parameters, label and suffix included
        Mac(MacroDef d, Interner in) {
            name = d.name; signature = d.signature(); def = in.site(d.defSite); uses = in.sites(d.uses);
            List<String> p = new ArrayList<>();
            for (String n : d.paramNames) if (n != null) p.add(n);
            params = List.copyOf(p);
        }
    }

    static final class Local {
        final int number;
        final String routine, value;
        final Site def;
        final List<Site> refs;
        Local(LocalLabel l, String value, Interner in) {
            number = l.number; routine = l.routine; this.value = value; def = in.site(l.site); refs = in.sites(l.refs);
        }
    }

    /** A GET or INCLUDE of a file. */
    static final class Get {
        final Site site;
        final String file;
        Get(Site site, String file) { this.site = site; this.file = file; }
    }

    /** An occurrence of a symbol, macro, local label or included file name at a site. */
    static final class Occ {
        final int line, col, len;
        final Object target;          // Sym, Mac, Local or Get
        final boolean def;
        final Site site;
        Occ(Site s, Object target, boolean def) {
            line = s.line; col = s.col; len = s.len; this.target = target; this.def = def; site = s;
        }
        boolean contains(int l, int c) { return l == line && c >= col && c <= col + len; }
    }

    final EntryPoint entry;
    final String component;
    final Map<String, Sym> symbols = new HashMap<>();
    final Map<String, Mac> macros = new LinkedHashMap<>();
    final List<Local> locals = new ArrayList<>();
    final Set<String> files;
    final List<Diagnostic> diagnostics;
    /** File → per-line classification (see {@link Assembler#classify}); index is the 1-based line. */
    final Map<String, byte[]> lineClass = new HashMap<>();
    /** File → folds as {start, end, kind}. */
    final Map<String, List<Object[]>> folds = new HashMap<>();
    /** File → occurrences, in no particular order. */
    final Map<String, List<Occ>> occurrences = new HashMap<>();
    /** Macro call site → symbols defined in that expansion (at any depth). */
    final Map<Site, List<Sym>> definedByCall = new HashMap<>();
    /** File → definitions placed in it: {Sym or Mac, site}. A symbol made in a macro is placed at the outermost call. */
    final Map<String, List<Object[]>> defsByFile = new HashMap<>();
    /** File → diagnostics placed in it (at the outermost macro call, if any). */
    final Map<String, List<Diagnostic>> diagsByFile = new HashMap<>();
    final long millis;

    /** Shares equal sites between entry points, since most come from common headers. */
    static final class Interner {
        private final Map<Site, Site> map = new ConcurrentHashMap<>();
        Site site(Site s) {
            if (s == null) return null;
            Site v = site(s.via);
            Site k = v == s.via ? s : new Site(s.file, s.line, s.col, s.len, v);
            Site e = map.putIfAbsent(k, k);
            return e == null ? k : e;
        }
        List<Site> sites(Iterable<Site> l) {
            List<Site> out = new ArrayList<>();
            for (Site s : l) out.add(site(s));
            return out.isEmpty() ? List.of() : Arrays.asList(out.toArray(new Site[0]));
        }
        String file(String f) {
            Site k = new Site(f, 0, 0, 0, null);
            Site e = map.putIfAbsent(k, k);
            return e == null ? f : e.file;
        }
    }

    EntryIndex(EntryPoint entry, String component, Assembler a, long millis, Interner in) {
        this.entry = entry;
        this.component = component;
        this.millis = millis;
        Set<String> fs = new LinkedHashSet<>();
        for (String f : a.filesRead) fs.add(in.file(f));
        files = fs;
        diagnostics = a.diagnostics();
        for (Diagnostic d : diagnostics) {
            String f = d.site == null ? in.file(entry.file.toAbsolutePath().normalize().toString()) : outermost(d.site).file;
            diagsByFile.computeIfAbsent(f, k -> new ArrayList<>()).add(d);
        }

        for (Symbol s : a.symbols.all()) {
            if (s.kind == Symbol.Kind.UNDEFINED) continue;
            if (s.defs.isEmpty() && s.uses.isEmpty()) continue;
            Sym y = new Sym(s, Server.formatValue(s.value, s.areaName), in);
            symbols.put(y.name, y);
            for (Site d : y.defs) {
                occ(d, y, true);
                Site o = outermost(d);
                defsByFile.computeIfAbsent(o.file, k -> new ArrayList<>()).add(new Object[] {y, d});
                for (Site v = d.via; v != null; v = v.via) definedByCall.computeIfAbsent(v, k -> new ArrayList<>()).add(y);
            }
            for (Site u : y.uses) occ(u, y, false);
        }
        for (MacroDef d : a.macros.values()) {
            Mac m = new Mac(d, in);
            macros.put(m.name, m);
            if (m.def != null) {
                occ(m.def, m, true);
                defsByFile.computeIfAbsent(m.def.file, k -> new ArrayList<>()).add(new Object[] {m, m.def});
            }
            for (Site u : m.uses) occ(u, m, false);
        }
        for (LocalLabel l : a.localLabels) {
            Local x = new Local(l, Server.formatValue(l.value, null), in);
            locals.add(x);
            occ(x.def, x, true);
            for (Site r : x.refs) occ(r, x, false);
        }
        for (Object[] i : a.includes) {
            Get g = new Get(in.site((Site) i[0]), in.file((String) i[1]));
            occ(g.site, g, false);
        }
        for (Map.Entry<SourceFile, int[][]> f : a.lineCounts.entrySet()) {
            int[][] c = f.getValue();
            byte[] b = new byte[c.length];
            boolean any = false;
            for (int i = 1; i < c.length; i++) {
                int k = Assembler.classify(c[i]);
                // 0 also means "never processed"; keep "assembled" apart as 4.
                b[i] = (byte) (k == 0 ? (c[i][0] > 0 ? 4 : 0) : k);
                any |= b[i] != 0;
            }
            if (any) lineClass.put(in.file(f.getKey().name), b);
        }
        for (Map.Entry<String, Set<List<Object>>> f : a.folds.entrySet()) {
            List<Object[]> l = new ArrayList<>();
            for (List<Object> x : f.getValue()) l.add(x.toArray());
            folds.put(in.file(f.getKey()), l);
        }
    }

    /** The outermost macro call that produced {@code s}, or {@code s} itself. */
    static Site outermost(Site s) {
        while (s.via != null) s = s.via;
        return s;
    }

    private void occ(Site s, Object target, boolean def) {
        if (s == null || s.len <= 0) return;
        occurrences.computeIfAbsent(s.file, k -> new ArrayList<>()).add(new Occ(s, target, def));
    }

    /** Line classification: 0 none, 1 shade, 2 unknown, 3 skipped in every expansion, 4 assembled. */
    int classify(String file, int line) {
        byte[] b = lineClass.get(file);
        return b == null || line >= b.length ? 0 : b[line];
    }
}
