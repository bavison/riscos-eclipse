package com.piccolosystems.riscos.asmindex.run;

import com.piccolosystems.riscos.asmindex.source.FileResolver;
import com.piccolosystems.riscos.asmindex.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One configuration's settings (or several trees' configurations', combined), as buildscan writes them: the Env file's
 * variables and path variables ({@code environment.json}), and every
 * component's objasm invocations ({@code <Component>/objasm.json}).
 */
public final class Settings {
    public final Map<String, String> envVars;
    public final Map<String, List<String>> pathVars;
    /** Entry points in the order given, each with its component name. */
    public final Map<EntryPoint, String> entries;

    /** For combined settings: the settings each entry point came from. */
    private final Map<EntryPoint, Settings> from;

    private Settings(Map<String, String> envVars, Map<String, List<String>> pathVars, Map<EntryPoint, String> entries) {
        this(envVars, pathVars, entries, Map.of());
    }

    private Settings(Map<String, String> envVars, Map<String, List<String>> pathVars, Map<EntryPoint, String> entries,
            Map<EntryPoint, Settings> from) {
        this.envVars = envVars;
        this.pathVars = pathVars;
        this.entries = Collections.unmodifiableMap(entries);
        this.from = from;
    }

    /**
     * Several configurations' settings as one, each entry point still
     * resolving files with its own configuration's variables: one per build
     * tree. Component names are made distinct by a suffix, {@code #2} for the
     * second's and so on, as {@link #componentSuffix} gives it. The first's
     * variables are the combination's.
     */
    public static Settings combine(List<Settings> all) {
        Map<EntryPoint, String> entries = new LinkedHashMap<>();
        Map<EntryPoint, Settings> from = new HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            Settings s = all.get(i);
            for (Map.Entry<EntryPoint, String> e : s.entries.entrySet()) {
                if (entries.containsKey(e.getKey())) continue;
                entries.put(e.getKey(), e.getValue() + componentSuffix(i));
                from.put(e.getKey(), s);
            }
        }
        Settings first = all.get(0);
        return new Settings(first.envVars, first.pathVars, entries, from);
    }

    /** What distinguishes the component names of the i'th (from 0) of several configurations. */
    public static String componentSuffix(int i) {
        return i == 0 ? "" : "#" + (i + 1);
    }

    /** Reads a buildscan output directory: {@code environment.json} and every {@code * /objasm.json}. */
    public static Settings fromDirectory(Path dir) throws IOException {
        List<Path> objasm = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.sorted().forEach(c -> {
                Path j = c.resolve("objasm.json");
                if (Files.isRegularFile(j)) objasm.add(j);
            });
        }
        return load(dir.resolve("environment.json"), objasm, null);
    }

    /**
     * @param environment buildscan's environment.json
     * @param objasm      objasm.json files; each one's directory name is its component
     * @param only        if not null, keep only entry points whose path ends with this
     */
    @SuppressWarnings("unchecked")
    public static Settings load(Path environment, List<Path> objasm, String only) throws IOException {
        Map<String, Object> env = (Map<String, Object>) Json.parse(Files.readString(environment));
        Map<String, String> envVars = new HashMap<>();
        Map<String, Object> set = (Map<String, Object>) env.getOrDefault("set_by_env_file", Map.of());
        for (Map.Entry<String, Object> e : set.entrySet()) envVars.put(e.getKey(), String.valueOf(e.getValue()));
        Map<String, List<String>> pathVars = new HashMap<>();
        Map<String, Object> pv = (Map<String, Object>) env.getOrDefault("path_variables", Map.of());
        for (Map.Entry<String, Object> e : pv.entrySet()) pathVars.put(e.getKey(), strings(e.getValue()));

        Map<EntryPoint, String> entries = new LinkedHashMap<>();
        for (Path p : objasm) {
            String comp = p.toAbsolutePath().getParent().getFileName().toString();
            for (Object o : (List<?>) Json.parse(Files.readString(p))) {
                Map<String, Object> m = (Map<String, Object>) o;
                EntryPoint e = new EntryPoint(Path.of((String) m.get("file")), Path.of((String) m.get("directory")),
                        strings(m.get("includes")), strings(m.get("predefines")), strings(m.get("options")));
                if (only != null && !e.file.toString().endsWith(only)) continue;
                entries.put(e, comp);
            }
        }
        return new Settings(envVars, pathVars, entries);
    }

    /** The file resolver for an entry point. */
    public FileResolver resolver(EntryPoint e) {
        Settings origin = from.get(e);
        if (origin != null) return origin.resolver(e);
        List<Path> inc = new ArrayList<>();
        for (String s : e.includes) inc.add(Path.of(s));
        return new FileResolver(envVars, pathVars, inc, e.directory);
    }

    private static List<String> strings(Object o) {
        List<String> l = new ArrayList<>();
        if (o instanceof List) for (Object x : (List<?>) o) l.add(String.valueOf(x));
        return l;
    }
}
