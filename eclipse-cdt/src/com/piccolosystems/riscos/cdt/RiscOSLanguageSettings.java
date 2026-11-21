package com.piccolosystems.riscos.cdt;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.cdt.core.language.settings.providers.ILanguageSettingsProvider;
import org.eclipse.cdt.core.settings.model.ICConfigurationDescription;
import org.eclipse.cdt.core.settings.model.ICLanguageSettingEntry;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.preferences.InstanceScope;

/**
 * The language settings of the files of a RISC OS build tree: what buildscan
 * found the makefiles pass the compiler, for each tree's active configuration. A
 * file that isn't compiled itself (a header) gets the settings of the first
 * compiled file in the component it is in. Nothing comes from the host's
 * compiler: what Norcroft predefines is in {@code norcroft-compat.h}, which
 * every file includes first.
 */
public class RiscOSLanguageSettings implements ILanguageSettingsProvider {
    public static final String ID = "com.piccolosystems.riscos.cdt.languageSettings";

    private static String loadedFor;
    private static CompileCommands commands = new CompileCommands();

    /** Each tree's active configuration's settings directory, as the language server is told them. */
    static String settingsDirs() {
        return InstanceScope.INSTANCE.getNode("com.piccolosystems.riscos.lsp").get("settings", "");
    }

    /** The active configurations' compile commands, read again if a configuration has changed. */
    static synchronized CompileCommands commands() {
        String dirs = settingsDirs();
        if (!dirs.equals(loadedFor)) {
            List<Path> paths = new ArrayList<>();
            for (String d : dirs.split(Pattern.quote(File.pathSeparator)))
                if (!d.isBlank()) paths.add(Path.of(d.strip()));
            commands = CompileCommands.load(paths);
            loadedFor = dirs;
        }
        return commands;
    }

    /** Forgets what was read, so the next request reads the settings again (after a rescan). */
    static synchronized void forget() {
        loadedFor = null;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "RISC OS build tree";
    }

    @Override
    public List<ICLanguageSettingEntry> getSettingEntries(ICConfigurationDescription cfg, IResource rc,
            String languageId) {
        CompileCommands c = commands();
        if (rc == null) return null;
        IPath loc = rc.getLocation();
        if (loc == null) return null;
        Path p = loc.toPath().toAbsolutePath().normalize();
        List<ICLanguageSettingEntry> e = c.byFile.get(p);
        if (e != null) return e;
        // Not compiled itself: the settings of the component it is in.
        for (Path d = p; d != null; d = d.getParent()) {
            e = c.byDirectory.get(d);
            if (e != null) return e;
        }
        return null;
    }
}
