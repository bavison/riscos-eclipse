package com.piccolosystems.riscos.asmindex.out;

import com.piccolosystems.riscos.asmindex.loc.LocalLabel;
import com.piccolosystems.riscos.asmindex.macro.MacroDef;
import com.piccolosystems.riscos.asmindex.run.Assembler;
import com.piccolosystems.riscos.asmindex.run.Diagnostic;
import com.piccolosystems.riscos.asmindex.run.EntryPoint;
import com.piccolosystems.riscos.asmindex.source.SourceFile;
import com.piccolosystems.riscos.asmindex.sym.Site;
import com.piccolosystems.riscos.asmindex.sym.Symbol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the JSON results model for one entry point. */
public final class ResultsWriter {
    private ResultsWriter() {}

    public static Map<String, Object> site(Site s) {
        if (s == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", s.file);
        m.put("line", s.line);
        m.put("col", s.col);
        if (s.len > 0) m.put("len", s.len);
        if (s.via != null) m.put("via", site(s.via));
        return m;
    }

    private static List<Object> sites(Iterable<Site> l) {
        List<Object> out = new ArrayList<>();
        for (Site s : l) out.add(site(s));
        return out;
    }

    public static Map<String, Object> build(EntryPoint e, Assembler a, boolean withUses) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entry", e.file.toString());
        m.put("directory", e.directory.toString());
        m.put("metrics", a.metrics);
        Map<String, Object> areas = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> ar : a.finalAreas.entrySet())
            areas.put(ar.getKey(), Map.of("size", ar.getValue()[0], "exact", ar.getValue()[1] == 1));
        m.put("areas", areas);

        List<Object> diags = new ArrayList<>();
        for (Diagnostic d : a.diagnostics()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("severity", d.severity.name().toLowerCase());
            x.put("code", d.code);
            x.put("message", d.message);
            x.put("pass", d.pass);
            if (d.site != null) x.put("site", site(d.site));
            diags.add(x);
        }
        m.put("diagnostics", diags);
        m.put("files", new ArrayList<>(a.filesRead));

        List<Object> inc = new ArrayList<>();
        for (Object[] i : a.includes) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("from", site((Site) i[0]));
            x.put("to", i[1]);
            inc.add(x);
        }
        m.put("includes", inc);

        List<Symbol> syms = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (Symbol s : a.symbols.all()) {
            if (s.kind == Symbol.Kind.UNDEFINED) {
                if (!s.uses.isEmpty()) unresolved.add(s.name);
                continue;
            }
            if (s.predefined && s.uses.isEmpty() && s.defs.isEmpty()) continue;
            if (s.defs.isEmpty() && s.uses.isEmpty()) continue;
            syms.add(s);
        }
        syms.sort(Comparator.comparing(s -> s.name));
        List<Object> sl = new ArrayList<>();
        for (Symbol s : syms) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", s.name);
            x.put("kind", s.kind.name().toLowerCase());
            x.put("value", s.value == null ? null : s.value.display());
            if (s.size != 0) x.put("size", s.size);
            List<String> flags = new ArrayList<>();
            if (s.predefined) flags.add("predefined");
            if (s.exported) flags.add("exported");
            if (s.routine) flags.add("routine");
            if (s.conditional) flags.add("conditional");
            if (s.approximate) flags.add("approximate");
            if (s.status == Symbol.Status.PARTIAL) flags.add("partly-defined");
            if (!flags.isEmpty()) x.put("flags", flags);
            if (s.areaName != null) x.put("area", s.areaName);
            x.put("defs", sites(s.defs));
            if (withUses) x.put("uses", sites(s.uses));
            else x.put("useCount", s.uses.size());
            sl.add(x);
        }
        m.put("symbols", sl);
        unresolved.sort(null);
        m.put("unresolved", unresolved);

        List<Object> ml = new ArrayList<>();
        for (MacroDef d : a.macros.values()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", d.name);
            x.put("signature", d.signature());
            x.put("def", site(d.defSite));
            if (d.conditional) x.put("flags", List.of("conditional"));
            if (withUses) x.put("uses", sites(d.uses));
            else x.put("useCount", d.uses.size());
            ml.add(x);
        }
        m.put("macros", ml);

        List<Object> ll = new ArrayList<>();
        for (LocalLabel l : a.localLabels) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("number", l.number);
            if (l.routine != null) x.put("routine", l.routine);
            x.put("value", l.value.display());
            x.put("def", site(l.site));
            if (withUses) x.put("refs", sites(l.refs));
            else x.put("refCount", l.refs.size());
            ll.add(x);
        }
        m.put("localLabels", ll);

        Map<String, Object> regions = new LinkedHashMap<>();
        for (Map.Entry<SourceFile, int[][]> f : a.lineCounts.entrySet()) {
            List<int[]> sh = Assembler.ranges(f.getValue(), 1);
            List<int[]> un = Assembler.ranges(f.getValue(), 2);
            List<int[]> ms = Assembler.ranges(f.getValue(), 3);
            if (sh.isEmpty() && un.isEmpty() && ms.isEmpty()) continue;
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("shaded", sh);
            if (!un.isEmpty()) x.put("unknown", un);
            if (!ms.isEmpty()) x.put("skippedInEveryExpansion", ms);
            regions.put(f.getKey().name, x);
        }
        m.put("regions", regions);

        Map<String, Object> folds = new LinkedHashMap<>();
        for (Map.Entry<String, Set<List<Object>>> f : a.folds.entrySet()) folds.put(f.getKey(), new ArrayList<>(f.getValue()));
        m.put("folding", folds);

        List<Object> uc = new ArrayList<>();
        for (Map.Entry<Site, String> u : a.unknownConditions.entrySet()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("site", site(u.getKey()));
            x.put("reason", u.getValue());
            uc.add(x);
        }
        m.put("unknownConditions", uc);
        return m;
    }
}
