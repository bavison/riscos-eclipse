package com.piccolosystems.riscos.asmindex.lsp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.piccolosystems.riscos.asmindex.util.Json;

/**
 * How a configuration's components are linked, for joining assembler and C by
 * name as the linker does: each component's C files and include paths
 * ({@code <component>/compile_commands.json}), and the libraries each links
 * with ({@code uses} in {@code summary.json}). A symbol is looked for in a
 * component and the libraries it uses (definitions), or in a component and
 * the components that use it (uses).
 */
final class Linkage {
    final Map<String, List<Path>> cFiles = new LinkedHashMap<>();
    final Map<String, List<Path>> includeDirs = new LinkedHashMap<>();
    final Map<String, Set<String>> uses = new HashMap<>();
    final Map<String, Set<String>> usedBy = new HashMap<>();
    private final Map<Path, String> componentOf = new HashMap<>();
    private final Map<Path, List<Path>> fileIncludeDirs = new HashMap<>();

    static final Linkage NONE = new Linkage();

    /**
     * Several configurations' linkage, one per build tree, component names
     * distinguished as {@link com.piccolosystems.riscos.asmindex.run.Settings#combine} does.
     */
    static Linkage load(List<Path> all) {
        Linkage l = new Linkage();
        for (int i = 0; i < all.size(); i++)
            l.read(all.get(i), all.size() == 1 ? "" : com.piccolosystems.riscos.asmindex.run.Settings.componentSuffix(i));
        return l;
    }

    static Linkage load(Path settings) {
        return load(settings == null ? List.of() : List.of(settings));
    }

    private void read(Path settings, String suffix) {
        Linkage l = this;
        if (settings == null || !Files.isDirectory(settings)) return;
        try (var dirs = Files.list(settings)) {
            for (Path d : (Iterable<Path>) dirs.sorted()::iterator) {
                Path f = d.resolve("compile_commands.json");
                if (!Files.isRegularFile(f)) continue;
                String comp = d.getFileName().toString() + suffix;
                Object o = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
                if (!(o instanceof List<?> list)) continue;
                Set<Path> incs = new LinkedHashSet<>();
                List<Path> files = new ArrayList<>();
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> m) || !(m.get("file") instanceof String file)) continue;
                    Path dir = Path.of(String.valueOf(m.get("directory")));
                    Path p = Path.of(file).toAbsolutePath().normalize();
                    files.add(p);
                    l.componentOf.put(p, comp);
                    try {
                        l.componentOf.putIfAbsent(p.toRealPath(), comp);
                    } catch (IOException e) {
                        // gone
                    }
                    List<Path> fi = new ArrayList<>();
                    if (m.get("arguments") instanceof List<?> args)
                        for (int k = 0; k + 1 < args.size(); k++) {
                            String a = String.valueOf(args.get(k));
                            if (a.equals("-I") || a.equals("-isystem")) fi.add(dir.resolve(String.valueOf(args.get(k + 1))).normalize());
                        }
                    l.fileIncludeDirs.put(p, fi);
                    incs.addAll(fi);
                }
                l.cFiles.put(comp, files);
                l.includeDirs.put(comp, new ArrayList<>(incs));
            }
            Path summary = settings.resolve("summary.json");
            if (Files.isRegularFile(summary)
                    && Json.parse(Files.readString(summary, StandardCharsets.UTF_8)) instanceof Map<?, ?> all)
                for (Map.Entry<?, ?> e : all.entrySet())
                    if (e.getValue() instanceof Map<?, ?> m && m.get("uses") instanceof List<?> u)
                        for (Object lib : u) {
                            String c = e.getKey() + suffix, x = lib + suffix;
                            l.uses.computeIfAbsent(c, k -> new LinkedHashSet<>()).add(x);
                            l.usedBy.computeIfAbsent(x, k -> new LinkedHashSet<>()).add(c);
                        }
        } catch (IOException | RuntimeException e) {
            // As much as could be read.
        }
    }

    /** The component a C file belongs to, or null. */
    String componentOf(Path file) {
        Path p = file.toAbsolutePath().normalize();
        String c = componentOf.get(p);
        if (c != null) return c;
        try {
            return componentOf.get(p.toRealPath());
        } catch (IOException e) {
            return null;
        }
    }

    /** Where a component's symbols may be defined: it, and the libraries it links with. */
    Set<String> definers(String component) {
        Set<String> s = new LinkedHashSet<>();
        s.add(component);
        s.addAll(uses.getOrDefault(component, Set.of()));
        return s;
    }

    /** Where a component's symbols may be used: it, and the components that link with it. */
    Set<String> users(String component) {
        Set<String> s = new LinkedHashSet<>();
        s.add(component);
        s.addAll(usedBy.getOrDefault(component, Set.of()));
        return s;
    }

    // ------------------------------------------------------------------ scanned files

    private record Scanned(long modified, CSource source) {}

    private final Map<Path, Scanned> scanned = new ConcurrentHashMap<>();

    /** A C file or header, scanned (cached until it changes); null if unreadable. */
    CSource source(Path p) {
        try {
            long m = Files.getLastModifiedTime(p).toMillis();
            Scanned s = scanned.get(p);
            if (s != null && s.modified == m) return s.source;
            CSource c = CSource.scan(new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1));
            scanned.put(p, new Scanned(m, c));
            return c;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The headers a component's C files include, directly or not (as many as 2,000). */
    List<Path> headers(String component) {
        Set<Path> seen = new LinkedHashSet<>();
        Deque<Path[]> todo = new ArrayDeque<>();     // {file, file whose include paths apply}
        for (Path f : cFiles.getOrDefault(component, List.of())) todo.add(new Path[] {f, f});
        Set<Path> done = new java.util.HashSet<>();
        while (!todo.isEmpty() && seen.size() < 2000) {
            Path[] x = todo.poll();
            if (!done.add(x[0])) continue;
            CSource c = source(x[0]);
            if (c == null) continue;
            List<Path> dirs = fileIncludeDirs.getOrDefault(x[1], includeDirs.getOrDefault(component, List.of()));
            for (CSource.Include inc : c.includes) {
                Path h = resolve(inc, x[0].getParent(), dirs);
                if (h != null && seen.add(h)) todo.add(new Path[] {h, x[1]});
            }
        }
        return new ArrayList<>(seen);
    }

    private static Path resolve(CSource.Include inc, Path from, List<Path> dirs) {
        List<Path> where = new ArrayList<>();
        if (inc.quoted() && from != null) where.add(from);
        where.addAll(dirs);
        for (Path d : where) {
            Path p = d.resolve(inc.name()).normalize();
            if (Files.isRegularFile(p)) return p;
            // RISC OS style: "Global/Services.h" may be h/Services in a source tree.
            String n = inc.name();
            if (n.endsWith(".h")) {
                Path alt = d.resolve(n.substring(0, n.length() - 2)).normalize();
                if (alt.getParent() != null) {
                    Path q = alt.getParent().resolve("h").resolve(alt.getFileName());
                    if (Files.isRegularFile(q)) return q;
                }
            }
        }
        return null;
    }
}
