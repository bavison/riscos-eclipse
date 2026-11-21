package com.piccolosystems.riscos.asmindex.source;

import com.piccolosystems.riscos.asmindex.util.Host;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves GET/INCLUDE/BIN file names to files.
 *
 * The RISC OS ↔ Unix name conversion is a heuristic: where a name could be
 * either, both readings are tried, RISC OS first for names with only '.'
 * separators. Exact rules are an open question.
 */
public final class FileResolver {
    /** Directories conventionally used as file-type suffixes on RISC OS. */
    private static final Set<String> SUFFIX_DIRS = Set.of(
            "a", "c", "h", "o", "s", "hdr", "x", "l", "y", "cmhg", "c++", "cpp", "hh", "p", "f");

    private final Map<String, String> env;
    private final Map<String, List<String>> pathVars;
    private final List<Path> includeDirs;
    private final Path cwd;
    private final Map<Path, String[]> dirCache = new HashMap<>();

    public static final class Result {
        public final Path path;          // null if not found
        public final String expanded;    // the name after <Var> expansion
        public final boolean caseFolded; // found only by ignoring case
        public final List<String> tried;
        Result(Path p, String e, boolean cf, List<String> tried) {
            path = p; expanded = e; caseFolded = cf; this.tried = tried;
        }
    }

    public FileResolver(Map<String, String> env, Map<String, List<String>> pathVars,
                        List<Path> includeDirs, Path cwd) {
        this.env = env;
        this.pathVars = pathVars;
        this.includeDirs = includeDirs;
        this.cwd = cwd;
    }

    /** RISC OS variable name → environment variable name: upper case, others to '_'. */
    public static String envName(String n) {
        StringBuilder b = new StringBuilder();
        for (char c : n.toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128) b.append(Character.toUpperCase(c));
            else b.append('_');
        }
        return b.toString();
    }

    /** Expands {@code <Var>} references from the environment; unset ones become empty. */
    public String expandVars(String name) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < name.length()) {
            char c = name.charAt(i);
            int close = c == '<' ? name.indexOf('>', i + 1) : -1;
            if (close > i + 1) {
                String v = env.get(envName(name.substring(i + 1, close)));
                b.append(v == null ? "" : v);
                i = close + 1;
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }

    /**
     * @param raw       the name as written, after $ substitution
     * @param enclosing the files currently being read, innermost first
     */
    public Result resolve(String raw, List<Path> enclosing) {
        String name = Host.nativePath(expandVars(raw));
        List<String> tried = new ArrayList<>();
        // On Windows, an absolute path (C:/x, from an Env variable bash set to /c/x), not Hdr:x.
        if (Host.WINDOWS && name.length() > 2 && Character.isLetter(name.charAt(0)) && name.charAt(1) == ':'
                && (name.charAt(2) == '/' || name.charAt(2) == '\\'))
            return search(name, candidates(name.substring(3).replace('\\', '/')), List.of(Path.of(name.substring(0, 3))), tried);
        // RISC OS path prefix, e.g. Hdr:ListOpts
        int colon = name.indexOf(':');
        if (colon > 0 && !name.startsWith("/") && name.substring(0, colon).indexOf('/') < 0) {
            String prefix = name.substring(0, colon);
            String rest = name.substring(colon + 1);
            List<String> dirs = pathVars.get(envName(prefix));
            if (dirs == null) {
                String v = env.get(envName(prefix) + "_PATH");
                if (v != null) dirs = List.of(v.split(":"));
            }
            if (dirs == null) return new Result(null, name, false, tried);
            List<Path> ds = new ArrayList<>();
            for (String d : dirs) if (!d.isEmpty()) ds.add(cwd.resolve(d));
            return search(name, candidates(rest), ds, tried);
        }
        List<String> cands = candidates(name);
        if (name.startsWith("/")) {
            return search(name, cands, List.of(Path.of("/")), tried);
        }
        Set<Path> dirs = new LinkedHashSet<>();
        for (Path p : enclosing) {
            Path d = p.toAbsolutePath().getParent();
            if (d != null) dirs.add(d);
        }
        dirs.add(cwd);
        for (Path d : includeDirs) dirs.add(cwd.resolve(d));
        return search(name, cands, new ArrayList<>(dirs), tried);
    }

    private Result search(String name, List<String> cands, List<Path> dirs, List<String> tried) {
        for (Path d : dirs) {
            for (String c : cands) {
                Path p = d.resolve(c).normalize();
                tried.add(p.toString());
                if (Files.isRegularFile(p)) return new Result(p, name, false, tried);
            }
        }
        // Fall back to a case-insensitive match, reported separately.
        for (Path d : dirs) {
            for (String c : cands) {
                Path p = caseInsensitive(d, c);
                if (p != null) return new Result(p, name, true, tried);
            }
        }
        return new Result(null, name, false, tried);
    }

    /** Possible Unix relative paths for a name in either syntax. */
    static List<String> candidates(String n) {
        List<String> out = new ArrayList<>();
        String n0 = n.startsWith("/") ? n.substring(1) : n;
        boolean dot = n0.indexOf('.') >= 0, slash = n0.indexOf('/') >= 0;
        String riscos = dot ? fromRiscOS(n0) : null;
        if (dot && !slash) {
            out.add(riscos);
            out.add(n0);
        } else {
            out.add(n0);
            if (riscos != null && !riscos.equals(n0)) out.add(riscos);
        }
        return out;
    }

    /** RISC OS form → Unix: '.' separates, '/' is a dot, suffix directories become extensions. */
    static String fromRiscOS(String n) {
        String[] parts = n.split("\\.", -1);
        List<String> comps = new ArrayList<>();
        for (String p : parts) {
            if (p.equals("^")) comps.add("..");
            else if (p.equals("@")) comps.add(".");
            else comps.add(p.replace('/', '.'));
        }
        int k = comps.size();
        if (k >= 2 && SUFFIX_DIRS.contains(comps.get(k - 2).toLowerCase())) {
            String suffix = comps.get(k - 2);
            String leaf = comps.get(k - 1);
            comps.remove(k - 1);
            comps.set(k - 2, leaf + "." + suffix);
        }
        return String.join("/", comps);
    }

    private Path caseInsensitive(Path dir, String rel) {
        Path cur = dir;
        for (String comp : rel.split("/")) {
            if (comp.isEmpty() || comp.equals(".")) continue;
            if (comp.equals("..")) { cur = cur.getParent(); if (cur == null) return null; continue; }
            String[] ls = dirCache.computeIfAbsent(cur, d -> {
                String[] l = d.toFile().list();
                return l == null ? new String[0] : l;
            });
            String hit = null;
            for (String e : ls) if (e.equalsIgnoreCase(comp)) { hit = e; break; }
            if (hit == null) return null;
            cur = cur.resolve(hit);
        }
        return new File(cur.toString()).isFile() ? cur : null;
    }
}
