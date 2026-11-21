package com.piccolosystems.riscos.lsp;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.lsp4e.server.ProcessStreamConnectionProvider;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Starts the objasm indexer's language server: {@code java -jar objasm-index.jar lsp}.
 * By default the jar is the one inside this bundle, and Java is the one running Eclipse.
 * Whatever the server writes to stderr, including Java's own start-up errors,
 * goes to {@code objasm-index.log} in this plugin's workspace state area.
 */
public class ObjasmServer extends ProcessStreamConnectionProvider {
    static final String BUNDLED_JAR = "server/objasm-index.jar";

    public ObjasmServer() {
        IPreferenceStore s = Preferences.store();
        String heap = s.getString(Preferences.HEAP).strip();
        setCommands(List.of(java(s.getString(Preferences.JAVA).strip()),
                "-Xmx" + (heap.isEmpty() ? Preferences.DEFAULT_HEAP : heap),
                "-jar", jar(s.getString(Preferences.JAR).strip()), "lsp"));
        setWorkingDirectory(System.getProperty("user.dir"));
    }

    /** The server's log: stderr, appended to, so that start-up failures can be seen. */
    static File logFile() {
        Bundle b = FrameworkUtil.getBundle(ObjasmServer.class);
        File dir = b == null ? new File(System.getProperty("java.io.tmpdir")) : Platform.getStateLocation(b).toFile();
        dir.mkdirs();
        return new File(dir, "objasm-index.log");
    }

    @Override
    protected ProcessBuilder createProcessBuilder() {
        ProcessBuilder pb = super.createProcessBuilder();
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile()));
        return pb;
    }

    private static String java(String configured) {
        if (!configured.isBlank()) return configured;
        String exe = File.separatorChar == '\\' ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    private static String jar(String configured) {
        if (!configured.isBlank()) return configured;
        Bundle b = FrameworkUtil.getBundle(ObjasmServer.class);
        URL entry = b == null ? null : b.getEntry(BUNDLED_JAR);
        if (entry == null) return BUNDLED_JAR;
        try {
            return new File(FileLocator.toFileURL(entry).getPath()).getAbsolutePath();
        } catch (IOException e) {
            return BUNDLED_JAR;
        }
    }

    /** The configuration's settings: buildscan's output directory for one Env file. */
    @Override
    public Object getInitializationOptions(URI rootUri) {
        IPreferenceStore s = Preferences.store();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("settings", Preferences.settingsDirs(s.getString(Preferences.SETTINGS)));
        o.put("showInfoDiagnostics", s.getBoolean(Preferences.SHOW_INFO));
        // Name files the way the preference says (see LinkFarms and LinkUris).
        o.put("uriStyle", LinkFarms.uriStyle());
        return o;
    }

    @Override
    public String toString() {
        return "objasm indexer: " + super.toString();
    }
}
