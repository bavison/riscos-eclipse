package com.piccolosystems.riscos.asmindex.cli;

import com.piccolosystems.riscos.asmindex.out.ResultsWriter;
import com.piccolosystems.riscos.asmindex.run.Assembler;
import com.piccolosystems.riscos.asmindex.run.Diagnostic;
import com.piccolosystems.riscos.asmindex.run.EntryPoint;
import com.piccolosystems.riscos.asmindex.run.Settings;
import com.piccolosystems.riscos.asmindex.source.SourceFile;
import com.piccolosystems.riscos.asmindex.util.Json;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * objasm-index: runs the engine over entry points from buildscan's settings.
 *
 * <pre>
 * objasm-index [options] ENVIRONMENT.json OBJASM.json...
 * objasm-index lsp [--settings DIR] [--log FILE]   run as a language server on stdio
 * objasm-index buildscan TREE ENV OUT   derive the settings from a build tree
 *   --file PATH   only the entry point whose file ends with PATH
 *   --out DIR     write one JSON file per entry point, plus summary.json and shading.json
 *   --uses        include every use site (large)
 *   --quiet       no per-entry lines on stdout
 *   --trace FILE:FIRST-LAST   print the location counter for each pass-2 line in that range
 * </pre>
 */
public final class Main {
    public static void main(String[] args) throws IOException {
        if (args.length > 0 && args[0].equals("lsp")) {
            com.piccolosystems.riscos.asmindex.lsp.Server.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && args[0].equals("buildscan")) {
            com.piccolosystems.riscos.asmindex.buildscan.BuildScan.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        String only = null;
        Path out = null;
        boolean uses = false, quiet = false;
        String[] trace = null;
        List<String> pos = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--file": only = args[++i]; break;
                case "--out": out = Path.of(args[++i]); break;
                case "--uses": uses = true; break;
                case "--quiet": quiet = true; break;
                case "--trace": trace = args[++i].split("[:-]"); break;
                default: pos.add(args[i]);
            }
        }
        if (pos.size() < 2) {
            System.err.println("usage: objasm-index [--file PATH] [--out DIR] [--uses] [--quiet] [--trace FILE:FIRST-LAST] ENVIRONMENT.json OBJASM.json...");
            System.exit(2);
        }
        List<Path> objasm = new ArrayList<>();
        for (int k = 1; k < pos.size(); k++) objasm.add(Path.of(pos.get(k)));
        Settings settings = Settings.load(Path.of(pos.get(0)), objasm, only);
        List<EntryPoint> entries = new ArrayList<>(settings.entries.keySet());
        Map<EntryPoint, String> component = settings.entries;

        Map<Path, SourceFile> cache = new HashMap<>();
        Map<String, Integer> totals = new TreeMap<>();
        Map<String, Integer> diagCounts = new TreeMap<>();
        List<Object> perEntry = new ArrayList<>();
        // Aggregate shading across entry points: file → per-line [assembled, skipped, unknown].
        Map<String, int[][]> agg = new TreeMap<>();
        long t0 = System.nanoTime();
        if (out != null) Files.createDirectories(out);
        int n = 0;
        for (EntryPoint e : entries) {
            n++;
            Assembler a = new Assembler(e, settings.resolver(e), cache);
            a.trace = trace;
            long t1 = System.nanoTime();
            a.run();
            long ms = (System.nanoTime() - t1) / 1_000_000;
            a.metrics.put("ms", (int) ms);
            int errors = 0, warnings = 0;
            Map<String, Integer> codes = new TreeMap<>();
            for (Diagnostic d : a.diagnostics()) {
                if (d.severity == Diagnostic.Severity.ERROR) errors++;
                if (d.severity == Diagnostic.Severity.WARNING) warnings++;
                codes.merge(d.severity.name().charAt(0) + ":" + d.code, 1, Integer::sum);
                diagCounts.merge(d.severity.name().charAt(0) + ":" + d.code, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> m : a.metrics.entrySet()) totals.merge(m.getKey(), m.getValue(), Integer::sum);
            for (Map.Entry<SourceFile, int[][]> f : a.lineCounts.entrySet()) {
                int[][] c = f.getValue();
                int[][] g = agg.computeIfAbsent(f.getKey().name, x -> new int[c.length][4]);
                for (int i = 0; i < c.length && i < g.length; i++) for (int j = 0; j < 4; j++) g[i][j] += c[i][j];
            }
            String comp = component.get(e);
            Map<String, Object> pe = new LinkedHashMap<>();
            pe.put("component", comp);
            pe.put("entry", e.file.toString());
            pe.put("errors", errors);
            pe.put("warnings", warnings);
            pe.put("metrics", a.metrics);
            pe.put("diagnostics", codes);
            perEntry.add(pe);
            if (!quiet)
                System.out.printf("%4d %-20s %-50s %5dms  files %4d  unknown-cond %3d  shaded %6d  E %d W %d%n",
                        n, comp, shorten(e.file.toString(), 50), ms, a.metrics.get("files"),
                        a.metrics.get("unknown-conditions"), a.metrics.get("shaded-lines"), errors, warnings);
            if (out != null) {
                String name = comp + "__" + e.file.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_") + "_" + n + ".json";
                try (Writer w = Files.newBufferedWriter(out.resolve(name), StandardCharsets.UTF_8)) {
                    w.write(Json.toJson(ResultsWriter.build(e, a, uses)));
                }
            }
        }
        int shadedAgg = 0, unknownAgg = 0, macroAgg = 0, filesAgg = agg.size();
        Map<String, Object> shadedFiles = new TreeMap<>();
        for (Map.Entry<String, int[][]> f : agg.entrySet()) {
            List<int[]> sh = Assembler.ranges(f.getValue(), 1);
            for (int[] x : sh) shadedAgg += x[1] - x[0] + 1;
            for (int[] x : Assembler.ranges(f.getValue(), 2)) unknownAgg += x[1] - x[0] + 1;
            for (int[] x : Assembler.ranges(f.getValue(), 3)) macroAgg += x[1] - x[0] + 1;
            if (!sh.isEmpty()) shadedFiles.put(f.getKey(), sh);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("entries", entries.size());
        summary.put("seconds", (System.nanoTime() - t0) / 1e9);
        summary.put("totals", totals);
        summary.put("diagnostics", diagCounts);
        summary.put("aggregate", Map.of("files", filesAgg, "shadedLines", shadedAgg, "unknownLines", unknownAgg,
                "skippedInEveryExpansion", macroAgg));
        summary.put("perEntry", perEntry);
        if (out != null) {
            Files.writeString(out.resolve("summary.json"), Json.toJson(summary));
            Files.writeString(out.resolve("shading.json"), Json.toJson(shadedFiles));
        }
        System.out.println("entries " + entries.size() + ", " + String.format("%.1f", (System.nanoTime() - t0) / 1e9) + "s");
        System.out.println("totals " + totals);
        System.out.println("aggregate: files " + filesAgg + ", shaded lines " + shadedAgg + ", unknown lines " + unknownAgg
                + ", macro lines skipped in every expansion " + macroAgg);
        System.out.println("diagnostics " + diagCounts);
    }

    private static String shorten(String s, int n) {
        return s.length() <= n ? s : "…" + s.substring(s.length() - n + 1);
    }
}
