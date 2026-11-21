package com.piccolosystems.riscos.asmindex.buildscan;

import com.piccolosystems.riscos.asmindex.util.Host;
import com.piccolosystems.riscos.asmindex.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Derives indexer settings for a cross-compilation RISC OS build tree, for one
 * Env file, without the ROOL tools: it sources the Bash Env file, reads the
 * Components file and the ModuleDB, and dry-runs each component's build
 * ({@code make -n -B}), which prints the armcc and armasm command lines.
 *
 * <p>It writes, in the output directory:
 * <ul>
 * <li>{@code environment.json}: the Env file's variables, and the RISC OS path
 *     variables ({@code HDR_PATH} gives {@code Hdr:}) split on {@code :};
 * <li>{@code <component>/compile_commands.json}: each armcc command in clang form;
 * <li>{@code <component>/objasm.json}: each armasm command's include path,
 *     predefines and options, for the objasm indexer;
 * <li>{@code summary.json}, and {@code norcroft-compat.h}, which every
 *     compile command includes.
 * </ul>
 * Paths refer to the {@code objs/} link farm, which is what the editor opens.
 * The only build-system knowledge here is the ROOL conventions in
 * {@link #CODE_TYPES} and {@link #TARGETS}, and the file formats read.
 *
 * <p>A tree works wherever it was unpacked. A built tree's dependency files
 * ({@code *.d}, and others such as the Kernel's {@code *.dk}) name the
 * absolute paths it was built at, and make wants those files to exist. So
 * the dry runs give make one extra rule, {@code %:: ;} (through
 * {@code --eval}, which make passes on to recursive makes), saying that a
 * file with no other rule needs nothing doing. make prefers the most specific
 * rule that applies, so this only matters where it would otherwise stop with
 * "No rule to make target". The makefiles themselves are untouched. (Naming
 * a makefile in {@code MAKEFILES} would not do: the shared makefiles recurse
 * with {@code -f ../$(firstword $(MAKEFILE_LIST))}.) This needs GNU make 4.0
 * or later. Only the command lines are wanted here; the indexers trace
 * includes themselves.
 *
 * <p>The ROOL build runs each component's make from srcbuild, which first
 * sets some variables of its own: {@code BUILDLOCALELISTNUMERIC}, {@code LOCALE}
 * and {@code RESOURCEDIR} (see {@link #srcbuildVariables}). The dry runs set
 * them the same way, so that, for example, Messages finds its resources.
 *
 * <p>A tree whose path contains a space is refused. make splits words at
 * spaces, and the shared makefiles' test for being in {@code objs/} then
 * fails, so each level re-runs make one {@code objs/} deeper, without end.
 * (The ROOL build can't work there either.) As a guard against anything
 * similar, each dry run is stopped after {@link #DRY_RUN_LIMIT_SECONDS}.
 */
public final class BuildScan implements AutoCloseable {
    /** The longest a single dry run may take; even a large component's takes a few seconds. */
    static final int DRY_RUN_LIMIT_SECONDS = 120;
    /** Component types (from the ModuleDB, or a -type override) that compile code. */
    static final Set<String> CODE_TYPES = Set.of("C", "ASM", "HAL", "KERNEL", "Kernel");
    /**
     * Make targets to dry-run, in order of preference: the first command seen
     * for a source file wins, so ROM settings take priority over library variants.
     */
    static final List<List<String>> TARGETS = List.of(List.of("rom"), List.of("export", "PHASE=libs"));
    static final String COMPAT = "norcroft-compat.h";

    /** A component to dry-run. */
    /**
     * A component to dry-run. A library (type {@code EXP}) is built only by
     * its {@code export PHASE=libs} target, for other components to link with.
     */
    public record Component(String name, Path dir, List<String> makeVars, boolean library) {
        public Component(String name, Path dir, List<String> makeVars) {
            this(name, dir, makeVars, false);
        }
    }

    /** What a component's dry run found. */
    /**
     * What a component's dry run found: its C and assembler files, make's
     * errors, the library archives it links with ({@code links}) and the ones
     * it exports for others to link with ({@code exports}), as absolute paths.
     */
    public record Result(String name, Path dir, int cFiles, int asmFiles, List<String> errors, boolean library,
            List<String> links, List<String> exports) {
        public Result(String name, Path dir, int cFiles, int asmFiles, List<String> errors) {
            this(name, dir, cFiles, asmFiles, errors, false, List.of(), List.of());
        }
    }

    /** Told about each component as it finishes, from any thread. */
    public interface Listener {
        void finished(Result r, int done, int total);
    }

    private final Path tree;
    /** The rule that lets make carry on past files named by stale dependency files. */
    static final String ANYTHING_RULE = "--eval=%:: ;";
    private final String envName;
    private final Map<String, String> env;
    /** The Components file's {@code %Messages} directory, if it names one, as a path relative to the tree. */
    private String messagesDir;
    /** The environment without the Env file, read the same way, to see what the Env file set. */
    private final Map<String, String> outer;

    /** Sources {@code Env/<envName>.sh} in the tree. */
    public BuildScan(Path tree, String envName) throws IOException {
        this.tree = tree.toAbsolutePath().normalize();
        if (!Files.isDirectory(this.tree.resolve("Env")) || !Files.isRegularFile(this.tree.resolve("BuildSys/ModuleDB")))
            throw new IOException(this.tree + " isn't the RiscOS directory of a build tree: it has no Env directory "
                    + "or no BuildSys/ModuleDB");
        if (!Files.isRegularFile(this.tree.resolve("Env").resolve(envName + ".sh")))
            throw new IOException("there is no Env file " + envName + " in " + this.tree + " (looked for Env/"
                    + envName + ".sh); the Env files there are: " + String.join(", ", envFiles(this.tree)));
        // The real path matters: it's what make sees.
        Path real = this.tree.toRealPath();
        if (hasSpace(real))
            throw new IOException("the path of the tree, " + real + ", contains a space. make, and so the RISC OS "
                    + "build, can't work in a directory like that. Move or unpack the tree somewhere without spaces "
                    + "in its path.");
        this.envName = envName;
        this.env = loadEnv(this.tree, envName);
        this.outer = bashEnv(null);
    }

    /** The Env files in a tree, as names such as {@code ROOL/BCM2835}: those that set {@code BUILD}. */
    public static List<String> envFiles(Path tree) throws IOException {
        Path dir = tree.resolve("Env");
        List<String> names = new ArrayList<>();
        try (var s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                String f = p.getFileName().toString();
                if (!f.endsWith(".sh") || !Files.isRegularFile(p)) continue;
                String text = Files.readString(p, StandardCharsets.ISO_8859_1);
                if (!Pattern.compile("(?m)^\\s*(export\\s+)?BUILD=").matcher(text).find()) continue;
                String rel = dir.relativize(p).toString().replace('\\', '/');
                names.add(rel.substring(0, rel.length() - 3));
            }
        }
        return names;
    }

    public Map<String, String> environment() {
        return env;
    }

    /**
     * The variables srcbuild sets before it runs each component's make, as far
     * as a dry run needs them. It numbers the territories in the locale list
     * ({@code LOCALELIST}, or else {@code LOCALE}) from the exported
     * {@code Global/Countries.h}, where entries such as {@code 1=UK} or
     * {@code UK=1} in the list can add to or override the table, and sets:
     * <ul>
     * <li>{@code BUILDLOCALELISTNUMERIC}: each number as two digits, in order;
     * <li>{@code LOCALE}: the first territory;
     * <li>{@code RESOURCEDIR}: the Components file's {@code %Messages} directory
     *     followed by {@code Resources/<BUILD>/<numbers>/<first territory>}.
     * </ul>
     * Problems (an unknown territory, say) go to {@code problems}, and the
     * variables they affect are left out.
     */
    Map<String, String> srcbuildVariables(List<String> problems) throws IOException {
        Map<String, String> v = new LinkedHashMap<>();
        String list = env.getOrDefault("LOCALELIST", "").strip();
        if (list.isEmpty()) list = env.getOrDefault("LOCALE", "").strip();
        if (list.isEmpty()) return v;

        Map<String, Integer> numbers = territoryNumbers(problems);
        List<String> names = new ArrayList<>();
        for (String entry : list.split(",")) {
            entry = entry.strip();
            int eq = entry.indexOf('=');
            if (eq >= 0) {
                String a = entry.substring(0, eq).strip(), b = entry.substring(eq + 1).strip();
                if (a.matches("\\d+")) {
                    numbers.put(b.toLowerCase(), Integer.parseInt(a));
                    entry = b;
                } else if (b.matches("\\d+")) {
                    numbers.put(a.toLowerCase(), Integer.parseInt(b));
                    entry = a;
                } else {
                    problems.add("badly formed territory in the locale list: " + entry);
                    return v;
                }
            }
            if (!entry.isEmpty()) names.add(entry);
        }
        StringBuilder numeric = new StringBuilder();
        for (String name : names) {
            Integer n = numbers.get(name.toLowerCase());
            if (n == null || n <= 0 || n > 99) {
                problems.add("territory " + name + " has no number from 1 to 99 in Global/Countries.h, so "
                        + "BUILDLOCALELISTNUMERIC, LOCALE and RESOURCEDIR are not set");
                return v;
            }
            numeric.append(String.format("%02d", n));
        }
        if (names.isEmpty()) return v;
        v.put("BUILDLOCALELISTNUMERIC", numeric.toString());
        v.put("LOCALE", names.get(0));
        String buildDir = env.get("BUILDDIR"), build = env.get("BUILD");
        if (messagesDir != null && buildDir != null && build != null)
            v.put("RESOURCEDIR", String.join("/", buildDir, messagesDir, "Resources", build,
                    numeric.toString(), names.get(0)));
        return v;
    }

    /** Territory numbers by lower-case name, from {@code Global/Countries.h} in the C exports. */
    private Map<String, Integer> territoryNumbers(List<String> problems) throws IOException {
        Map<String, Integer> numbers = new LinkedHashMap<>();
        String cexport = env.get("CEXPORTDIR");
        Path h = cexport == null ? null : Path.of(Host.nativePath(cexport)).resolve("Global/Countries.h");
        if (h == null || !Files.isRegularFile(h)) {
            problems.add("no Global/Countries.h in the C exports" + (h == null ? "" : " (" + h + ")")
                    + ", so territories can only be numbered by the locale list");
            return numbers;
        }
        Pattern def = Pattern.compile("^#define\\s+TerritoryNum_(\\w+)\\s*\\(\\s*(\\d+)\\s*\\).*");
        for (String line : Files.readAllLines(h, StandardCharsets.ISO_8859_1)) {
            var m = def.matcher(line);
            if (!m.matches()) continue;
            String name = m.group(1);
            // The same adjustments as srcbuild's: Countries.h has two South
            // Africas and spells the Netherlands' symbol short.
            if (name.equals("SAfrica")) continue;
            if (name.equals("SAfrica2")) name = "SAfrica";
            if (name.equals("Netherland")) name = "Netherlands";
            numbers.put(name.toLowerCase(), Integer.parseInt(m.group(2)));
        }
        return numbers;
    }

    private static boolean hasSpace(Path p) {
        return p.toString().chars().anyMatch(Character::isWhitespace);
    }

    /** Nothing to release now; kept so that callers can use try-with-resources. */
    @Override
    public void close() {
        // nothing
    }


    static Map<String, String> loadEnv(Path tree, String envName) throws IOException {
        return bashEnv(tree.resolve("Env").resolve(envName + ".sh"));
    }

    /** The environment as bash sees it, after sourcing {@code script} if there is one. */
    private static Map<String, String> bashEnv(Path script) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(Host.bash()));
        cmd.addAll(Host.bashOptions());
        if (script == null) cmd.addAll(List.of("-c", "env -0"));
        // No quotes in the script: Java doesn't escape them on Windows. The path has no spaces (see above).
        else cmd.addAll(List.of("-c", ". $1 >/dev/null 2>&1 && env -0", "bash", Host.posixPath(script.toString())));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Host.prepare(pb.environment());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("can't run bash (" + Host.bash() + "): " + e.getMessage()
                    + (Host.WINDOWS ? ". Install MSYS2, and in it make (pacman -S make)." : ""), e);
        }
        byte[] out = p.getInputStream().readAllBytes();
        int rc = waitFor(p);
        if (rc != 0) throw new IOException("sourcing " + script + " failed (" + rc + ")");
        Map<String, String> env = new LinkedHashMap<>();
        for (String item : new String(out, StandardCharsets.UTF_8).split("\0")) {
            int eq = item.indexOf('=');
            if (eq >= 0) env.put(item.substring(0, eq), item.substring(eq + 1));
        }
        return env;
    }

    private record DbEntry(String type, Path dir, String instdir, String target) {}

    private Map<String, DbEntry> loadModuleDb() throws IOException {
        Map<String, DbEntry> db = new LinkedHashMap<>();
        for (String line : Files.readAllLines(tree.resolve("BuildSys/ModuleDB"), StandardCharsets.ISO_8859_1)) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] f = line.trim().split("\\s+");
            if (f.length < 3) continue;
            Path dir = tree;
            for (String part : f[2].split("\\.")) dir = dir.resolve(part);
            db.put(f[0], new DbEntry(f[1], dir, f.length > 3 ? f[3] : null, f.length > 4 ? f[4] : null));
        }
        return db;
    }

    /**
     * The components of the Env file's Components file that compile code, in
     * file order. Problems (a component not in the ModuleDB) go to {@code problems}.
     */
    public List<Component> components(List<String> problems) throws IOException {
        Map<String, DbEntry> db = loadModuleDb();
        Path file = tree.resolve("BuildSys/Components");
        for (String part : env.getOrDefault("BUILD", "").split("/")) file = file.resolve(part);
        List<Component> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            List<String> words = Shlex.split(line);
            if (line.startsWith("%")) {
                if (words.size() > 1 && words.get(0).equalsIgnoreCase("%messages"))
                    messagesDir = words.get(1).replace('.', '/');
                continue;
            }
            String name = words.get(0), type = null;
            List<String> options = new ArrayList<>();
            for (int i = 1; i < words.size(); ) {
                String w = words.get(i);
                if (w.equals("-type")) {
                    type = words.get(i + 1);
                    i += 2;
                } else if (w.equals("-options")) {
                    i++;
                    while (i < words.size() && !words.get(i).startsWith("-")) options.add(words.get(i++));
                } else if (w.startsWith("-")) {
                    i += 2; // e.g. -at <address>
                } else {
                    i++;
                }
            }
            if (!seen.add(name)) continue;
            DbEntry e = db.get(name);
            if (e == null) {
                problems.add(name + ": not in ModuleDB");
                continue;
            }
            String t = type != null ? type : e.type;
            boolean library = t.equals("EXP");
            if (!CODE_TYPES.contains(t) && !library) continue;
            List<String> vars = new ArrayList<>();
            vars.add("COMPONENT=" + name);
            if (e.target != null) vars.add("TARGET=" + e.target);
            if (e.instdir != null) vars.add("INSTDIR=" + env.get("INSTALLDIR") + "/" + e.instdir);
            vars.addAll(options);
            result.add(new Component(name, e.dir, vars, library));
        }
        return result;
    }

    /** A project outside the tree, built against its exports. */
    public static Component external(Path dir, String name) {
        return new Component(name, dir.toAbsolutePath().normalize(), List.of("COMPONENT=" + name));
    }

    /**
     * Dry-runs every component and writes the settings to {@code out}.
     * @return each component's result, in the order given
     */
    public List<Result> run(List<Component> components, Path out, int threads, Listener listener,
            BooleanSupplier cancelled) throws IOException {
        return run(components, out, threads, listener, cancelled, false);
    }

    /**
     * As {@link #run(List, Path, int, Listener, BooleanSupplier)}. With
     * {@code adding}, the components are added to what {@code out} already
     * holds (an external project added later, say); otherwise {@code out} ends
     * up describing these components only, and the output of any other
     * component there is removed.
     */
    public List<Result> run(List<Component> components, Path out, int threads, Listener listener,
            BooleanSupplier cancelled, boolean adding) throws IOException {
        Files.createDirectories(out);
        try (InputStream in = BuildScan.class.getResourceAsStream(COMPAT)) {
            Files.write(out.resolve(COMPAT), in.readAllBytes());
        }
        List<String> problems = new ArrayList<>();
        Map<String, String> srcbuild = srcbuildVariables(problems);
        writeEnvironment(out, srcbuild, problems);
        String compat = out.toAbsolutePath().resolve(COMPAT).toString();
        Map<String, String> makeEnv = new LinkedHashMap<>(env);
        makeEnv.putAll(srcbuild);

        List<Result> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            List<Future<Result>> futures = new ArrayList<>();
            int total = components.size();
            int[] done = {0};
            for (Component c : components) {
                futures.add(pool.submit(() -> {
                    if (cancelled.getAsBoolean()) return null;
                    Result r = scan(c, out, compat, makeEnv);
                    synchronized (done) {
                        done[0]++;
                        if (listener != null) listener.finished(r, done[0], total);
                    }
                    return r;
                }));
            }
            for (Future<Result> f : futures) {
                Result r = f.get();
                if (r != null) results.add(r);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            if (e.getCause() instanceof UncheckedIOException u) throw u.getCause();
            throw new IOException(e.getCause());
        } finally {
            pool.shutdownNow();
        }

        if (cancelled.getAsBoolean()) throw new IOException("cancelled");
        Map<String, Object> summary = new LinkedHashMap<>();
        Path summaryFile = out.resolve("summary.json");
        if (adding && Files.isRegularFile(summaryFile)) {
            Object old = Json.parse(Files.readString(summaryFile, StandardCharsets.UTF_8));
            if (old instanceof Map<?, ?> m)
                for (Map.Entry<?, ?> e : m.entrySet()) summary.put(String.valueOf(e.getKey()), e.getValue());
        }
        if (!adding) removeOthers(out, components);
        for (Result r : results) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("dir", r.dir.toString());
            s.put("c_files", r.cFiles);
            s.put("asm_files", r.asmFiles);
            s.put("errors", r.errors);
            if (r.library) s.put("library", true);
            s.put("links", r.links);
            s.put("exports", r.exports);
            summary.put(r.name, s);
        }
        usesLibraries(summary);
        writeJson(summaryFile, summary);
        return results;
    }

    /**
     * Adds to each component in a summary the components whose exported
     * archives it links with ({@code uses}): the libraries whose definitions
     * its code can refer to.
     */
    @SuppressWarnings("unchecked")
    static void usesLibraries(Map<String, Object> summary) {
        Map<String, String> owner = new HashMap<>();
        for (Map.Entry<String, Object> e : summary.entrySet())
            if (e.getValue() instanceof Map<?, ?> m && m.get("exports") instanceof List<?> ex)
                for (Object x : ex) owner.putIfAbsent(String.valueOf(x), e.getKey());
        for (Map.Entry<String, Object> e : summary.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> m)) continue;
            Set<String> uses = new java.util.TreeSet<>();
            if (m.get("links") instanceof List<?> ln)
                for (Object x : ln) {
                    String o = owner.get(String.valueOf(x));
                    if (o != null && !o.equals(e.getKey())) uses.add(o);
                }
            ((Map<String, Object>) m).put("uses", new ArrayList<>(uses));
        }
    }

    /** Removes the output of components other than these: directories holding only objasm.json and compile_commands.json. */
    private static void removeOthers(Path out, List<Component> components) throws IOException {
        Set<String> keep = new HashSet<>();
        for (Component c : components) keep.add(c.name);
        try (var dirs = Files.list(out)) {
            for (Path d : (Iterable<Path>) dirs::iterator) {
                if (!Files.isDirectory(d) || keep.contains(d.getFileName().toString())) continue;
                List<String> names = new ArrayList<>();
                try (var fs = Files.list(d)) {
                    fs.forEach(f -> names.add(f.getFileName().toString()));
                }
                if (names.isEmpty() || !Set.of("objasm.json", "compile_commands.json").containsAll(names)) continue;
                for (String n : names) Files.delete(d.resolve(n));
                Files.delete(d);
            }
        }
    }

    private void writeEnvironment(Path out, Map<String, String> srcbuild, List<String> problems) throws IOException {
        // The Env files give each RISC OS path, such as Hdr:, as a variable
        // named in upper case with _PATH added (HDR_PATH), its directories
        // ':'-separated. Record them all, with the rest of what the Env file set.
        Map<String, Object> changed = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getValue().equals(outer.get(e.getKey()))) continue;
            changed.put(e.getKey(), e.getValue());
            if (e.getKey().matches("[A-Z0-9_]+_PATH"))
                pathVars.put(e.getKey().substring(0, e.getKey().length() - 5),
                        java.util.Arrays.stream(e.getValue().split(":", -1)).map(Host::nativePath).toList());
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("env_file", envName);
        o.put("path_variables", pathVars);
        o.put("set_by_env_file", changed);
        o.put("set_as_srcbuild_would", srcbuild);
        if (!problems.isEmpty()) o.put("problems", problems);
        writeJson(out.resolve("environment.json"), o);
    }

    private Result scan(Component c, Path out, String compat, Map<String, String> makeEnv) throws IOException {
        if (!Files.isDirectory(c.dir))
            return new Result(c.name, c.dir, 0, 0, List.of(c.dir + " missing"), c.library, List.of(), List.of());
        List<Object> cdb = new ArrayList<>(), asm = new ArrayList<>();
        Set<String> links = new LinkedHashSet<>(), exports = new LinkedHashSet<>();
        Set<Path> seen = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        Path objs = c.dir.resolve("objs");
        Path defaultCwd = Files.isDirectory(objs) ? objs : c.dir;
        for (List<String> target : TARGETS) {
            String[] io = dryRun(c, target, makeEnv);
            // Record make's own errors ("*** ..."), so a component whose makefile
            // can't be dry-run is reported rather than silently empty. A missing
            // target is expected: not every component has every target.
            for (String line : lines(io[1]))
                if (line.contains("***") && !line.contains("No rule to make target '" + target.get(0) + "'"))
                    errors.add(String.join(" ", target) + ": " + line.strip());
            for (String line : lines(io[0])) {
                List<String> words;
                try {
                    words = Shlex.split(line);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (words.isEmpty()) continue;
                String tool = baseName(words.get(0));
                if (tool.equals("armlink") || tool.equals("link")) {
                    // The archives it links with.
                    for (String w : words.subList(1, words.size()))
                        if (!w.startsWith("-") && w.endsWith(".a")) links.add(defaultCwd.resolve(Host.nativePath(w)).normalize().toString());
                    continue;
                }
                if (tool.equals("cp") && words.size() >= 3) {
                    // An archive exported for others to link with, to a file or into a directory.
                    String dest = Host.nativePath(words.get(words.size() - 1));
                    for (String src : words.subList(1, words.size() - 1)) {
                        if (src.startsWith("-") || !src.endsWith(".a")) continue;
                        Path d = defaultCwd.resolve(dest).normalize();
                        exports.add((dest.endsWith(".a") ? d : d.resolve(Path.of(src).getFileName())).toString());
                    }
                    continue;
                }
                boolean cc = tool.equals("armcc");
                if (!cc && !tool.equals("armasm")) continue;
                Object[] t = cc ? translateCc(words) : translateAsm(words);
                if (t == null) continue;
                String src = Host.nativePath((String) t[1]);
                // Not every component re-invokes make in objs/ (Messages doesn't):
                // use whichever directory the source is actually relative to.
                Path cwd = defaultCwd;
                if (!Files.exists(cwd.resolve(src)) && Files.exists(c.dir.resolve(src))) cwd = c.dir;
                Path path = cwd.resolve(src).normalize();
                if (!seen.add(path)) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("directory", cwd.toString());
                entry.put("file", path.toString());
                if (cc) {
                    @SuppressWarnings("unchecked")
                    List<String> args = (List<String>) t[0];
                    args.addAll(List.of("-include", compat, src));
                    entry.put("arguments", args);
                    cdb.add(entry);
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> s = (Map<String, Object>) t[0];
                    entry.putAll(s);
                    asm.add(entry);
                }
            }
        }
        Path d = out.resolve(c.name);
        Files.createDirectories(d);
        writeJson(d.resolve("compile_commands.json"), cdb);
        writeJson(d.resolve("objasm.json"), asm);
        return new Result(c.name, c.dir, cdb.size(), asm.size(), errors, c.library, List.copyOf(links),
                List.copyOf(exports));
    }

    /** @return stdout and stderr of {@code make -n -B -k} */
    private String[] dryRun(Component c, List<String> target, Map<String, String> env) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(Host.make(), "-I" + env.getOrDefault("MAKEFILEDIR", ""),
                "--no-print-directory", "-n", "-B", "-k", ANYTHING_RULE));
        cmd.addAll(c.makeVars);
        cmd.addAll(target);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(c.dir.toFile());
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.redirectInput(ProcessBuilder.Redirect.from(Host.nullDevice()));
        Process p = pb.start();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread t = new Thread(() -> {
            try {
                p.getErrorStream().transferTo(err);
            } catch (IOException e) {
                // the process has gone; whatever was read is enough
            }
        });
        t.start();
        // Stop the whole process tree if a dry run goes on too long: make
        // carries out recursive make lines even with -n, so a makefile that
        // recurses without end would otherwise do so for ever.
        boolean[] timedOut = {false};
        Thread watchdog = new Thread(() -> {
            try {
                if (!p.waitFor(DRY_RUN_LIMIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                    timedOut[0] = true;
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                    // A process that escaped (as one started by MSYS2 can) may still hold the pipes open:
                    // close them, so that the reads below end.
                    try {
                        p.getInputStream().close();
                        p.getErrorStream().close();
                    } catch (IOException e) {
                        // closed already
                    }
                }
            } catch (InterruptedException e) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();
        java.io.ByteArrayOutputStream outBuf = new java.io.ByteArrayOutputStream();
        try {
            p.getInputStream().transferTo(outBuf);
        } catch (IOException e) {
            if (!timedOut[0]) throw e;          // closed by the watchdog: keep what was read
        }
        byte[] outBytes = outBuf.toByteArray();
        waitFor(p);
        watchdog.interrupt();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String errText = err.toString(StandardCharsets.UTF_8);
        if (timedOut[0]) errText += "\n*** stopped after " + DRY_RUN_LIMIT_SECONDS + " seconds\n";
        return new String[] {new String(outBytes, StandardCharsets.UTF_8), errText};
    }

    /** armcc argv → {clang-style argv, source}, or null for a run that doesn't compile. */
    static Object[] translateCc(List<String> words) {
        // A 32-bit Arm target with unsigned char, Norcroft's default; -undef and
        // -nostdinc stop host macros and headers leaking in (CLib's own headers
        // come from the -I list).
        List<String> args = new ArrayList<>(List.of("clang", "-c", "--target=arm-none-eabi", "-std=gnu11",
                "-funsigned-char", "-undef", "-nostdinc"));
        List<String> defines = new ArrayList<>(), includes = new ArrayList<>();
        String src = null;
        for (int i = 1; i < words.size(); ) {
            String w = words.get(i), lw = w.toLowerCase();
            if (lw.equals("-apcs")) {
                // Norcroft predefines __APCS_32 or __APCS_26 to match the APCS variant
                defines.add(arg(words, i + 1).contains("26") ? "-D__APCS_26" : "-D__APCS_32");
                i += 2;
            } else if (Set.of("-o", "-cpu", "-arch", "-fpu", "-via").contains(lw)) {
                i += 2;
            } else if (w.startsWith("-M")) {
                return null; // a dependency-generation run, not a compile
            } else if (w.startsWith("-D") || w.startsWith("-U")) {
                defines.add(w.length() > 2 ? w : w + arg(words, i + 1));
                i += w.length() > 2 ? 1 : 2;
            } else if (w.length() >= 2 && w.charAt(0) == '-' && "IiJj".indexOf(w.charAt(1)) >= 0) {
                // Norcroft: -I adds a user include directory; -J replaces the
                // system include path. Either letter may be lower case, and the
                // value may be a comma-separated list.
                String flag = "Ii".indexOf(w.charAt(1)) >= 0 ? "-I" : "-isystem";
                for (String d : (w.length() > 2 ? w.substring(2) : arg(words, i + 1)).split(",", -1)) {
                    includes.add(flag);
                    includes.add(Host.nativePath(d));
                }
                i += w.length() > 2 ? 1 : 2;
            } else {
                if (!w.startsWith("-") && w.endsWith(".c")) src = w;
                i++;
            }
        }
        if (src == null) return null;
        args.addAll(includes);
        args.addAll(defines);
        return new Object[] {args, src};
    }

    /** armasm argv → {settings for the objasm indexer, source}, or null. */
    static Object[] translateAsm(List<String> words) {
        List<String> includes = new ArrayList<>(), predefines = new ArrayList<>(), options = new ArrayList<>();
        String src = null;
        for (int i = 1; i < words.size(); ) {
            String w = words.get(i), lw = w.toLowerCase();
            if (Set.of("-o", "-depend", "-d", "-list", "-via").contains(lw)) {
                i += 2;
            } else if (lw.equals("-pd") || lw.equals("-predefine")) {
                predefines.add(arg(words, i + 1));
                i += 2;
            } else if (Set.of("-apcs", "-cpu", "--cpu", "-fpu", "--fpu", "-arch").contains(lw)) {
                options.add(w);
                options.add(arg(words, i + 1));
                i += 2;
            } else if (w.startsWith("-I") || w.startsWith("-i")) {
                for (String d : (w.length() > 2 ? w.substring(2) : arg(words, i + 1)).split(",", -1)) includes.add(Host.nativePath(d));
                i += w.length() > 2 ? 1 : 2;
            } else {
                if (!w.startsWith("-")) {
                    // objasm [options] source [object]: the first positional is the source
                    if (src == null) src = w;
                } else if (!Set.of("-stamp", "-quit", "-throwback", "-g", "-gnu").contains(lw)) {
                    options.add(w);
                }
                i++;
            }
        }
        if (src == null) return null;
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("includes", includes);
        s.put("predefines", predefines);
        s.put("options", options);
        return new Object[] {s, src};
    }

    private static String arg(List<String> words, int i) {
        if (i >= words.size()) throw new IllegalArgumentException("missing argument after " + words.get(i - 1));
        return words.get(i);
    }

    private static String baseName(String s) {
        return s.substring(s.lastIndexOf('/') + 1);
    }

    private static List<String> lines(String s) {
        List<String> l = new ArrayList<>(List.of(s.split("\r\n|\r|\n", -1)));
        if (!l.isEmpty() && l.get(l.size() - 1).isEmpty()) l.remove(l.size() - 1);
        return l;
    }

    private static int waitFor(Process p) {
        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            p.destroy();
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static void writeJson(Path p, Object o) throws IOException {
        Files.writeString(p, Json.toJson(o) + "\n", StandardCharsets.UTF_8);
    }

    /** {@code buildscan TREE ENV OUTDIR [--component NAME]... [--external DIR=NAME]... [--jobs N]} */
    public static void main(String[] args) throws IOException {
        List<String> pos = new ArrayList<>(), only = new ArrayList<>(), ext = new ArrayList<>();
        int jobs = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--component": only.add(args[++i]); break;
                case "--external": ext.add(args[++i]); break;
                case "--jobs": jobs = Integer.parseInt(args[++i]); break;
                default: pos.add(args[i]);
            }
        }
        if (pos.size() != 3) {
            System.err.println("usage: objasm-index buildscan TREE ENV OUTDIR [--component NAME]... "
                    + "[--external DIR=NAME]... [--jobs N]");
            System.err.println("  TREE is the tree's RiscOS directory; ENV an Env file name such as ROOL/BCM2835");
            System.exit(2);
        }
        BuildScan scan;
        try {
            scan = new BuildScan(Path.of(pos.get(0)), pos.get(1));
        } catch (IOException e) {
            System.err.println("buildscan: " + e.getMessage());
            System.exit(1);
            return;
        }
        List<String> problems = new ArrayList<>();
        List<Component> comps = new ArrayList<>();
        for (Component c : scan.components(problems))
            if (only.isEmpty() || only.contains(c.name)) comps.add(c);
        for (String spec : ext) {
            int eq = spec.indexOf('=');
            comps.add(external(Path.of(spec.substring(0, eq)), spec.substring(eq + 1)));
        }
        problems.forEach(System.err::println);
        try (scan) {
            Path out = Path.of(pos.get(2)).toAbsolutePath();
            List<Result> results = scan.run(comps, out, jobs, (r, done, total) -> System.out.println(line(r, done, total)),
                    () -> false);
            long bad = results.stream().filter(r -> !r.errors.isEmpty()).count();
            System.out.println(results.size() + " components scanned" + (bad == 0 ? "" : "; " + bad
                    + " reported make errors, listed in full in " + out.resolve("summary.json")));
        }
    }

    /** One line of progress: the counts, and the first of any errors, shortened. */
    static String line(Result r, int done, int total) {
        String s = String.format("%3d/%d %-24s C %4d  asm %4d", done, total, r.name, r.cFiles, r.asmFiles);
        if (r.errors.isEmpty()) return s;
        String first = r.errors.get(0);
        if (first.length() > 100) first = first.substring(0, 97) + "...";
        return s + "  " + r.errors.size() + (r.errors.size() == 1 ? " error: " : " errors, first: ") + first;
    }

}
