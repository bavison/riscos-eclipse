package com.piccolosystems.riscos.cdt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.cdt.core.settings.model.CIncludeFileEntry;
import org.eclipse.cdt.core.settings.model.CIncludePathEntry;
import org.eclipse.cdt.core.settings.model.CMacroEntry;
import org.eclipse.cdt.core.settings.model.ICLanguageSettingEntry;
import org.eclipse.cdt.core.settings.model.ICSettingEntry;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;

import com.piccolosystems.riscos.asmindex.util.Json;

/**
 * The C settings of one configuration: each file's include paths, macros and
 * forced includes, from buildscan's {@code <component>/compile_commands.json}
 * files, as CDT language setting entries.
 */
final class CompileCommands {
    /** Each compiled file's entries, by its path as named and as resolved. */
    final Map<Path, List<ICLanguageSettingEntry>> byFile = new HashMap<>();
    /** For files that aren't compiled themselves (headers): the first compiled file's, by component directory. */
    final Map<Path, List<ICLanguageSettingEntry>> byDirectory = new LinkedHashMap<>();
    /** The components with C files, by name: their directories. */
    final Map<String, Path> components = new LinkedHashMap<>();

    static CompileCommands load(Path settings) {
        return load(settings == null ? List.of() : List.of(settings));
    }

    /** Several configurations' settings, one per build tree: their files don't overlap. */
    static CompileCommands load(List<Path> all) {
        CompileCommands c = new CompileCommands();
        for (Path settings : all) {
            if (!Files.isDirectory(settings)) continue;
            try (var dirs = Files.list(settings)) {
                for (Path d : (Iterable<Path>) dirs.sorted()::iterator) {
                    Path f = d.resolve("compile_commands.json");
                    if (Files.isRegularFile(f)) c.read(d.getFileName().toString(), f);
                }
            } catch (IOException e) {
                Platform.getLog(CompileCommands.class).warn("Couldn't read the C settings in " + settings, e);
            }
        }
        return c;
    }

    private void read(String component, Path f) throws IOException {
        Object o = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
        if (!(o instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m) || !(m.get("file") instanceof String file)
                    || !(m.get("directory") instanceof String dir) || !(m.get("arguments") instanceof List<?> args))
                continue;
            List<String> words = new ArrayList<>();
            for (Object a : args) words.add(String.valueOf(a));
            List<ICLanguageSettingEntry> entries = entries(Path.of(dir), words);
            Path p = Path.of(file).toAbsolutePath().normalize();
            byFile.put(p, entries);
            try {
                byFile.putIfAbsent(p.toRealPath(), entries);
            } catch (IOException e) {
                // gone
            }
            // The component directory: where objs/ (the usual working directory) is.
            Path cwd = Path.of(dir).toAbsolutePath().normalize();
            Path compDir = cwd.getFileName() != null && cwd.getFileName().toString().equals("objs") ? cwd.getParent() : cwd;
            byDirectory.putIfAbsent(compDir, entries);
            components.putIfAbsent(component, compDir);
        }
    }

    /** A clang-style command's include paths, macros and forced includes, in order. */
    static List<ICLanguageSettingEntry> entries(Path dir, List<String> words) {
        List<ICLanguageSettingEntry> out = new ArrayList<>();
        for (int i = 1; i < words.size(); i++) {
            String w = words.get(i);
            String value;
            if ((w.equals("-I") || w.equals("-isystem") || w.equals("-iquote") || w.equals("-include")
                    || w.equals("-D")) && i + 1 < words.size()) {
                value = words.get(++i);
            } else if (w.startsWith("-I") || w.startsWith("-D")) {
                value = w.substring(2);
                w = w.substring(0, 2);
            } else {
                continue;
            }
            switch (w) {
                case "-I", "-isystem" -> out.add(new CIncludePathEntry(path(dir, value), 0));
                case "-iquote" -> out.add(new CIncludePathEntry(path(dir, value), ICSettingEntry.LOCAL));
                case "-include" -> out.add(new CIncludeFileEntry(path(dir, value), 0));
                case "-D" -> {
                    int eq = value.indexOf('=');
                    out.add(eq < 0 ? new CMacroEntry(value, "1", 0)
                            : new CMacroEntry(value.substring(0, eq), value.substring(eq + 1), 0));
                }
                default -> {
                }
            }
        }
        return out;
    }

    private static IPath path(Path dir, String p) {
        return IPath.fromPath(dir.resolve(p).toAbsolutePath().normalize());
    }
}
