package com.piccolosystems.riscos.asmindex.lsp;

import com.piccolosystems.riscos.asmindex.run.EntryPoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An immutable snapshot of the latest successful run of every entry point.
 * A re-run produces a new snapshot that shares the unchanged entries, so
 * requests are always answered from a consistent set of results.
 */
final class Model {
    static final Model EMPTY = new Model(Map.of());

    final Map<EntryPoint, EntryIndex> entries;
    /** File → entry points that read it. */
    private final Map<String, List<EntryIndex>> byFile = new HashMap<>();
    /** Real path → file name as the engine saw it, built on first need. */
    private volatile Map<String, String> aliases;
    /** Per-file results computed on demand. */
    final Map<String, Object> cache = new ConcurrentHashMap<>();

    Model(Map<EntryPoint, EntryIndex> entries) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        for (EntryIndex e : this.entries.values())
            for (String f : e.files) byFile.computeIfAbsent(f, k -> new ArrayList<>()).add(e);
    }

    Model with(Map<EntryPoint, EntryIndex> replaced) {
        Map<EntryPoint, EntryIndex> m = new LinkedHashMap<>(entries);
        m.putAll(replaced);
        return new Model(m);
    }

    /** Entry points that read {@code file}. */
    List<EntryIndex> readers(String file) {
        return byFile.getOrDefault(file, List.of());
    }

    boolean knows(String file) { return byFile.containsKey(file); }

    Iterable<String> files() { return byFile.keySet(); }

    /**
     * The engine's name for a file the editor names {@code file}. Usually the
     * same; if the editor opened a file by its real path rather than through
     * the objs/ link farm, the link farm name is returned.
     */
    String canonical(String file) {
        if (byFile.containsKey(file)) return file;
        Map<String, String> a = aliases;
        if (a == null) {
            a = new HashMap<>();
            for (String f : byFile.keySet()) {
                try {
                    String r = Path.of(f).toRealPath().toString();
                    if (!r.equals(f)) a.putIfAbsent(r, f);
                } catch (IOException | RuntimeException ignored) {
                    // a file that has gone: no alias
                }
            }
            aliases = a;
        }
        String c = a.get(file);
        if (c != null) return c;
        try {
            Path p = Path.of(file);
            if (Files.exists(p)) {
                c = a.get(p.toRealPath().toString());
                if (c != null) return c;
            }
        } catch (IOException | RuntimeException ignored) {
            // fall through
        }
        return file;
    }
}
