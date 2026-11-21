package com.piccolosystems.riscos.asmindex.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The bash and make that run the tree's Env files and makefiles, and the
 * translation of the paths they print.
 *
 * <p>Elsewhere these are just {@code bash} and {@code make} on the PATH, except
 * that on macOS make is GNU make as Homebrew installs it ({@code gmake}). On
 * Windows they come from MSYS2 (or Cygwin), whose programs print POSIX paths
 * ({@code /c/riscos/RiscOS/Export}), which {@link #nativePath} turns into
 * Windows ones ({@code C:/riscos/RiscOS/Export}). They are looked for
 * explicitly: Windows starts a program named without a directory from
 * System32 before the PATH, and System32's {@code bash.exe} is WSL's. The
 * {@code RISCOS_BASH} environment variable can name bash.exe directly; make is
 * then the one beside it, if there is one.
 */
public final class Host {
    private Host() {
    }

    public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    public static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");

    private static String[] tools;

    /**
     * On macOS, GNU make 4: Apple's make is 3.81, too old (the dry runs need
     * {@code --eval}). Homebrew's is {@code gmake}, and not on the PATH of an
     * Eclipse started from the Dock or Finder, so it is looked for in
     * Homebrew's and MacPorts' directories too. {@code RISCOS_MAKE} can name it.
     */
    private static Path gnuMakeDir;

    /** A directory holding just {@code make}, a link to GNU make; null if there's no GNU make apart from {@code make}. */
    private static synchronized Path gnuMakeDir() {
        if (gnuMakeDir != null || make().equals("make")) return gnuMakeDir;
        try {
            Path d = Files.createTempDirectory("riscos-gnumake");
            Files.createSymbolicLink(d.resolve("make"), Path.of(make()));
            d.toFile().deleteOnExit();
            d.resolve("make").toFile().deleteOnExit();
            return gnuMakeDir = d;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    private static String macMake() {
        String given = System.getenv("RISCOS_MAKE");
        if (given != null && !given.isBlank()) return given;
        List<Path> dirs = new ArrayList<>();
        for (String d : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator))
            if (!d.isBlank()) dirs.add(Path.of(d));
        for (String d : List.of("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin")) dirs.add(Path.of(d));
        for (Path d : dirs) if (Files.isExecutable(d.resolve("gmake"))) return d.resolve("gmake").toString();
        return "make";
    }

    /** The bash to run. */
    public static String bash() {
        return tools()[0];
    }

    /** The make to run. */
    public static String make() {
        return tools()[1];
    }

    /**
     * Options for bash before its {@code -c}: on Windows, {@code -o igncr}, so
     * that an Env file checked out by git with CR LF line ends (core.autocrlf)
     * is read as written, its variables not ending in CR. MSYS2's and
     * Cygwin's bash have the option; others don't need it.
     */
    public static List<String> bashOptions() {
        return WINDOWS ? List.of("-o", "igncr") : List.of();
    }

    /** The null device, for a process's standard input. */
    public static File nullDevice() {
        return new File(WINDOWS ? "NUL" : "/dev/null");
    }

    private static synchronized String[] tools() {
        if (tools != null) return tools;
        if (MAC) return tools = new String[] {"bash", macMake()};
        if (!WINDOWS) return tools = new String[] {"bash", "make"};
        List<Path> dirs = new ArrayList<>();
        String given = System.getenv("RISCOS_BASH");
        if (given != null && !given.isBlank()) dirs.add(Path.of(given).getParent());
        String sysRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows").toLowerCase(Locale.ROOT);
        for (String d : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (d.isBlank()) continue;
            String l = d.toLowerCase(Locale.ROOT);
            if (l.startsWith(sysRoot) || l.contains("windowsapps")) continue;   // WSL's bash
            try {
                dirs.add(Path.of(d));
            } catch (RuntimeException e) {
                // not a usable directory name
            }
        }
        for (String d : List.of("C:\\msys64\\usr\\bin", "C:\\cygwin64\\bin")) dirs.add(Path.of(d));
        Path bash = null, make = null;
        for (Path d : dirs) {
            Path b = d.resolve("bash.exe");
            if (!Files.isRegularFile(b)) continue;
            if (bash == null) bash = b;
            if (Files.isRegularFile(d.resolve("make.exe"))) {
                bash = b;
                make = d.resolve("make.exe");
                break;
            }
        }
        if (make == null)
            for (Path d : dirs) if (Files.isRegularFile(d.resolve("make.exe"))) { make = d.resolve("make.exe"); break; }
        return tools = new String[] {bash == null ? "bash" : bash.toString(), make == null ? "make" : make.toString()};
    }

    /**
     * Sets the PATH an Env file is sourced with, and so the dry runs' too. On
     * Windows, bash's directory goes first, so that the commands an Env file
     * runs ({@code env}, {@code dirname}...) are MSYS2's, whatever the PATH
     * Eclipse was started with. On macOS, a directory in which {@code make} is
     * GNU make goes first: makefiles start other makes by that name.
     */
    public static void prepare(java.util.Map<String, String> environment) {
        Path dir = WINDOWS ? Path.of(bash()).getParent() : MAC ? gnuMakeDir() : null;
        if (dir == null) return;
        String key = "PATH";
        for (String k : environment.keySet()) if (k.equalsIgnoreCase("PATH")) key = k;
        String old = environment.get(key);
        environment.put(key, dir + (old == null || old.isEmpty() ? "" : File.pathSeparator + old));
    }

    /** Where MSYS2's or Cygwin's {@code /} is: two levels above its bash (for MSYS2, {@code C:/msys64/usr/bin/bash.exe}). */
    private static String posixRoot() {
        Path b = Path.of(bash()).toAbsolutePath();
        Path bin = b.getParent();
        Path root = bin == null ? null : bin.getParent();
        if (root != null && root.getFileName() != null && root.getFileName().toString().equalsIgnoreCase("usr"))
            root = root.getParent();
        return root == null ? "C:" : root.toString().replace('\\', '/');
    }

    /**
     * A path as bash or make printed it, as this system names it: unchanged
     * except on Windows, where {@code /c/x} becomes {@code C:/x} and another
     * absolute path is taken to be under MSYS2's root. Relative paths, and
     * RISC OS ones ({@code Hdr:ListOpts}), are unchanged.
     */
    public static String nativePath(String p) {
        if (!WINDOWS || p == null || !p.startsWith("/")) return p;
        if (p.startsWith("//")) return p;                                        // a UNC path
        if (p.length() >= 2 && Character.isLetter(p.charAt(1)) && (p.length() == 2 || p.charAt(2) == '/'))
            return Character.toUpperCase(p.charAt(1)) + ":" + (p.length() == 2 ? "/" : p.substring(2));
        if (p.startsWith("/cygdrive/") && p.length() >= 11 && (p.length() == 11 || p.charAt(11) == '/'))
            return Character.toUpperCase(p.charAt(10)) + ":" + (p.length() == 11 ? "/" : p.substring(11));
        return posixRoot() + p;
    }

    /** A Windows path as bash takes it ({@code C:/x} as {@code /c/x}); unchanged elsewhere. */
    public static String posixPath(String p) {
        if (!WINDOWS || p == null || p.length() < 2 || p.charAt(1) != ':' || !Character.isLetter(p.charAt(0))) return p;
        return "/" + Character.toLowerCase(p.charAt(0)) + p.substring(2).replace('\\', '/');
    }
}
