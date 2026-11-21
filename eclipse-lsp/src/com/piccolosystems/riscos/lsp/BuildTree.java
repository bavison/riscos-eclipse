package com.piccolosystems.riscos.lsp;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.framework.FrameworkUtil;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/**
 * An imported build tree, as the import wizard set it up: where it is, its
 * configurations (Env files), which one is active, which components were
 * chosen and the external projects. Kept in the workspace's preferences, one
 * node per tree under {@code trees}, named after the tree's project.
 */
public final class BuildTree {
    /** The tree's {@code RiscOS} directory. */
    public Path riscos;
    /** The tree's project name. */
    public String name;
    /** Env file names, such as {@code ROOL/BCM2835}. */
    public final List<String> configurations = new ArrayList<>();
    public String active;
    /** The components imported, by name; empty for every component with code. */
    public final Set<String> components = new LinkedHashSet<>();
    /** Projects outside the tree built against its exports: directory → component name. */
    public final Map<Path, String> externals = new LinkedHashMap<>();

    private static IEclipsePreferences root() {
        return InstanceScope.INSTANCE.getNode(Preferences_.BUNDLE);
    }

    private static Preferences trees() {
        return root().node("trees");
    }

    /** Every imported tree whose project is still in the workspace, in the order imported. */
    public static synchronized List<BuildTree> all() {
        List<BuildTree> out = new ArrayList<>();
        try {
            Preferences n = trees();
            List<String> order = lines(n.get("order", ""));
            for (String child : n.childrenNames()) if (!order.contains(child)) order.add(child);
            for (String child : order) {
                if (!n.nodeExists(child)) continue;
                BuildTree t = read(n.node(child));
                // A tree whose project has been deleted is left alone, in case it comes back.
                if (t != null && ResourcesPlugin.getWorkspace().getRoot().getProject(t.name).exists()) out.add(t);
            }
        } catch (BackingStoreException e) {
            Platform.getLog(BuildTree.class).error("Couldn't read the build trees' settings", e);
        }
        return out;
    }

    /** The tree of that name, or null. */
    public static BuildTree named(String name) {
        for (BuildTree t : all()) if (t.name.equals(name)) return t;
        return null;
    }

    /** The tree whose RiscOS directory is {@code riscos}, or null. */
    public static BuildTree at(Path riscos) {
        for (BuildTree t : all()) if (t.riscos.equals(riscos)) return t;
        return null;
    }

    /** The tree a resource belongs to, inside it or one of its external projects; or null. */
    public static BuildTree of(IResource r) {
        IPath loc = r == null ? null : r.getLocation();
        if (loc == null) return null;
        Path p = loc.toPath().toAbsolutePath().normalize();
        for (BuildTree t : all()) {
            if (p.startsWith(t.riscos)) return t;
            for (Path x : t.externals.keySet()) if (p.startsWith(x)) return t;
        }
        return null;
    }

    /** The only tree, or null if there are none or several. */
    public static BuildTree only() {
        List<BuildTree> a = all();
        return a.size() == 1 ? a.get(0) : null;
    }

    /**
     * The active configuration's settings directory of every tree, separated
     * by the path separator: what the language server and CDT read.
     */
    public static String activeSettings() {
        List<String> dirs = new ArrayList<>();
        for (BuildTree t : all()) if (t.active != null) dirs.add(t.settingsDir(t.active).toString());
        return String.join(File.pathSeparator, dirs);
    }

    private static BuildTree read(Preferences n) {
        String riscos = n.get("riscos", "");
        if (riscos.isEmpty()) return null;
        BuildTree t = new BuildTree();
        t.riscos = Path.of(riscos);
        t.name = n.get("name", t.riscos.getFileName().toString());
        t.configurations.addAll(lines(n.get("configurations", "")));
        t.active = n.get("active", t.configurations.isEmpty() ? null : t.configurations.get(0));
        t.components.addAll(lines(n.get("components", "")));
        for (String l : lines(n.get("externals", ""))) {
            int tab = l.indexOf('\t');
            if (tab > 0) t.externals.put(Path.of(l.substring(tab + 1)), l.substring(0, tab));
        }
        return t;
    }

    private void write(Preferences n) {
        n.put("riscos", riscos.toString());
        n.put("name", name);
        n.put("configurations", String.join("\n", configurations));
        if (active != null) n.put("active", active);
        n.put("components", String.join("\n", components));
        List<String> ext = new ArrayList<>();
        for (Map.Entry<Path, String> e : externals.entrySet()) ext.add(e.getValue() + "\t" + e.getKey());
        n.put("externals", String.join("\n", ext));
    }

    private static final List<Runnable> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Calls {@code r} whenever a tree is saved or forgotten (an import, a switch of configuration). */
    public static void addListener(Runnable r) {
        LISTENERS.add(r);
    }

    public static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    private static void changed() {
        for (Runnable r : LISTENERS) {
            try {
                r.run();
            } catch (RuntimeException e) {
                Platform.getLog(BuildTree.class).warn("A listener to build trees failed: " + e);
            }
        }
    }

    /** Saves the tree, replacing any other record of the same RiscOS directory. */
    public synchronized void save() {
        try {
            Preferences n = trees();
            List<String> order = lines(n.get("order", ""));
            for (String child : n.childrenNames()) {
                Preferences c = n.node(child);
                if (!child.equals(key(name)) && riscos.toString().equals(c.get("riscos", ""))) {
                    c.removeNode();
                    order.remove(child);
                }
            }
            write(n.node(key(name)));
            if (!order.contains(key(name))) order.add(key(name));
            n.put("order", String.join("\n", order));
            root().flush();
        } catch (BackingStoreException e) {
            Platform.getLog(BuildTree.class).error("Couldn't save the build tree's settings", e);
        }
        changed();
    }

    /** Forgets the tree. */
    public synchronized void forget() {
        try {
            Preferences n = trees();
            if (n.nodeExists(key(name))) n.node(key(name)).removeNode();
            List<String> order = lines(n.get("order", ""));
            order.remove(key(name));
            n.put("order", String.join("\n", order));
            root().flush();
        } catch (BackingStoreException e) {
            Platform.getLog(BuildTree.class).error("Couldn't forget the build tree", e);
        }
        changed();
    }

    /** A preference node name for a tree name: no slashes. */
    private static String key(String name) {
        return name.replace('/', '_');
    }

    /** Where buildscan's output for a configuration goes: the plugin's state area, not the tree. */
    public Path settingsDir(String env) {
        Path state = Platform.getStateLocation(FrameworkUtil.getBundle(BuildTree.class)).toPath();
        return state.resolve("trees").resolve(name).resolve(env.replace('/', '_'));
    }

    private static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        for (String l : s.split("\n")) if (!l.isBlank()) out.add(l);
        return out;
    }

    /** The bundle's preference node name (Preferences, the class, is shadowed by OSGi's here). */
    private static final class Preferences_ {
        static final String BUNDLE = com.piccolosystems.riscos.lsp.Preferences.BUNDLE;
    }
}
