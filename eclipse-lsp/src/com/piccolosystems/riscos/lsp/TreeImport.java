package com.piccolosystems.riscos.lsp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

import com.piccolosystems.riscos.asmindex.buildscan.BuildScan;
import com.piccolosystems.riscos.asmindex.buildscan.BuildScan.Component;
import com.piccolosystems.riscos.asmindex.util.Json;

/**
 * What the import wizard and the RISC OS menu do: run buildscan for each
 * configuration, create the projects, and point the language server at the
 * active configuration.
 */
public final class TreeImport {
    /** Problems buildscan had with a component's makefile. */
    public static final String MARKER = Preferences.BUNDLE + ".buildscan";
    /** RISC OS sources are Latin-1. */
    public static final String CHARSET = "ISO-8859-1";
    /** What the wizard adds to each git repository's {@code .git/info/exclude}. */
    static final List<String> EXCLUDES = List.of(".project", ".cproject", ".settings/");

    private TreeImport() {
    }

    /** Something to run in a background job. */
    public interface Operation {
        void run(IProgressMonitor monitor) throws CoreException;
    }

    /**
     * Runs an operation as a user job with a progress dialog that can be sent
     * to the background. Not a workspace job: buildscan can take minutes, and
     * the workspace is only locked while projects are created.
     */
    public static Job schedule(String name, Operation op) {
        Job j = Job.create(name, monitor -> {
            try {
                op.run(monitor);
                return Status.OK_STATUS;
            } catch (OperationCanceledException e) {
                return Status.CANCEL_STATUS;
            } catch (CoreException e) {
                return e.getStatus();      // shown to the user, as the job is a user job
            }
        });
        j.setUser(true);
        j.schedule();
        return j;
    }

    /**
     * Imports or rescans the tree: runs buildscan for every configuration,
     * creates a project for the tree and for each component that hasn't one,
     * and makes the active configuration current.
     */
    public static void importTree(BuildTree t, IProgressMonitor monitor) throws CoreException {
        SubMonitor sub = SubMonitor.convert(monitor, "Importing " + t.name, 100 * t.configurations.size() + 20);
        Map<String, Path> projects = new LinkedHashMap<>();
        for (String env : t.configurations)
            for (BuildScan.Result r : scan(t, env, null, false, sub.split(100)))
                projects.putIfAbsent(r.name(), r.dir());
        t.save();
        Map<String, IProject> made = createProjects(t, projects, projectNames(projects, t.externals), sub.split(10));
        participate(t, made, sub.split(10));
        LinkFarms.markDerived();
        activate(t, true);
    }

    /** Lets the optional bundles (CDT) set up the projects. */
    private static void participate(BuildTree t, Map<String, IProject> projects, IProgressMonitor monitor) {
        List<TreeParticipant> all = TreeParticipant.all();
        SubMonitor sub = SubMonitor.convert(monitor, Math.max(1, all.size()));
        for (TreeParticipant p : all) {
            try {
                p.imported(t, projects, sub.split(1));
            } catch (CoreException | RuntimeException e) {
                Platform.getLog(TreeImport.class).error("Setting up the projects failed in " + p.getClass().getName(), e);
            }
        }
    }

    /** Adds a project outside the tree, built against its exports, to every configuration. */
    public static void addExternal(BuildTree t, Path dir, String name, IProgressMonitor monitor) throws CoreException {
        SubMonitor sub = SubMonitor.convert(monitor, "Adding " + name, 10 * t.configurations.size() + 10);
        t.externals.put(dir, name);
        t.save();
        for (String env : t.configurations)
            scan(t, env, List.of(BuildScan.external(dir, name)), true, sub.split(10));
        participate(t, createProjects(t, Map.of(name, dir), Map.of(dir, name), sub.split(5)), sub.split(5));
        LinkFarms.markDerived();
        activate(t, true);
    }

    /** Makes another configuration the active one. */
    public static void switchTo(BuildTree t, String env) {
        t.active = env;
        t.save();
        try {
            ResourcesPlugin.getWorkspace().run(m -> updateMarkers(t), ResourcesPlugin.getWorkspace().getRoot(),
                    IWorkspace.AVOID_UPDATE, null);
        } catch (CoreException e) {
            Platform.getLog(TreeImport.class).log(e.getStatus());
        }
        activate(t, false);
    }

    /** Runs buildscan for one configuration: {@code only} those components, or all the tree's. */
    static List<BuildScan.Result> scan(BuildTree t, String env, List<Component> only, boolean adding,
            IProgressMonitor monitor) throws CoreException {
        SubMonitor sub = SubMonitor.convert(monitor, "Scanning the makefiles for " + env, 100);
        try (BuildScan scan = new BuildScan(t.riscos, env)) {
            List<Component> comps = new ArrayList<>();
            if (only != null) {
                comps.addAll(only);
            } else {
                for (Component c : scan.components(new ArrayList<>()))
                    if (t.components.isEmpty() || t.components.contains(c.name())) comps.add(c);
                for (Map.Entry<Path, String> e : t.externals.entrySet())
                    comps.add(BuildScan.external(e.getKey(), e.getValue()));
            }
            sub.setWorkRemaining(Math.max(1, comps.size()));
            Object lock = new Object();
            return scan.run(comps, t.settingsDir(env), Runtime.getRuntime().availableProcessors(),
                    (r, done, total) -> {
                        synchronized (lock) {
                            sub.subTask(env + ": " + r.name() + " (" + done + " of " + total + ")");
                            sub.worked(1);
                        }
                    }, sub::isCanceled, adding);
        } catch (IOException | RuntimeException e) {
            if (sub.isCanceled()) throw new OperationCanceledException();
            throw new CoreException(Status.error("Scanning the makefiles for " + env + " failed: " + e.getMessage(), e));
        }
    }

    /**
     * The project name for each component directory: the directory's own
     * name ({@code Wimp} for WindowManager), as the tree's paths go, or the
     * component's name if two directories have the same name (USBDriver's and
     * Internet's {@code build}). External projects keep the names given them.
     */
    static Map<Path, String> projectNames(Map<String, Path> components, Map<Path, String> externals) {
        Map<String, Integer> leaves = new HashMap<>();
        for (Path dir : new LinkedHashSet<>(components.values()))
            if (!externals.containsKey(dir) && dir.getFileName() != null)
                leaves.merge(dir.getFileName().toString(), 1, Integer::sum);
        Map<Path, String> names = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : components.entrySet()) {
            Path dir = e.getValue();
            if (names.containsKey(dir)) continue;
            String leaf = dir.getFileName() == null ? e.getKey() : dir.getFileName().toString();
            names.put(dir, externals.containsKey(dir) ? externals.get(dir)
                    : leaves.getOrDefault(leaf, 0) > 1 ? e.getKey() : leaf);
        }
        return names;
    }

    /**
     * Creates (or reopens) the tree's project and one for each component
     * directory, all Latin-1; returns each component's.
     */
    static Map<String, IProject> createProjects(BuildTree t, Map<String, Path> components, Map<Path, String> names,
            IProgressMonitor monitor) throws CoreException {
        IWorkspace ws = ResourcesPlugin.getWorkspace();
        Map<String, IProject> made = new LinkedHashMap<>();
        ws.run(m -> {
            SubMonitor sub = SubMonitor.convert(m, "Creating projects", components.size() + 2);
            project(t.name, t.riscos, sub.split(1));
            Map<Path, IProject> byDir = new HashMap<>();
            for (Map.Entry<String, Path> e : components.entrySet()) {
                Path dir = e.getValue();
                if (dir.equals(t.riscos) || !Files.isDirectory(dir)) {
                    sub.worked(1);
                    continue;
                }
                IProject p = byDir.get(dir);
                if (p == null) {
                    String name = names.getOrDefault(dir, e.getKey());
                    // Another tree's project may have the name: then the tree's name tells them apart.
                    if (taken(name, dir)) name = name + " (" + t.name + ")";
                    p = project(name, dir, sub.split(1));
                    byDir.put(dir, p);
                } else {
                    sub.worked(1);
                }
                made.put(e.getKey(), p);
            }
            SourceFileTypes.exportedHeaders(t.riscos, sub.split(1));
            updateMarkers(t);
        }, ws.getRoot(), IWorkspace.AVOID_UPDATE, monitor);
        return made;
    }

    /** Whether a project other than the one at {@code dir} has that name. */
    private static boolean taken(String name, Path dir) {
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if (!p.exists()) return false;
        IPath loc = p.getLocation();
        return loc == null || !loc.toPath().equals(dir);
    }

    /** The project at {@code dir}, created if there isn't one; open, and Latin-1. */
    static IProject project(String name, Path dir, IProgressMonitor monitor) throws CoreException {
        SubMonitor sub = SubMonitor.convert(monitor, 4);
        IWorkspace ws = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = ws.getRoot();
        IProject p = projectAt(dir);
        if (p == null) {
            String n = name;
            for (int i = 2; root.getProject(n).exists(); i++) n = name + " (" + i + ")";
            p = root.getProject(n);
            IProjectDescription d = ws.newProjectDescription(n);
            d.setLocation(IPath.fromPath(dir));
            p.create(d, sub.split(1));
            excludeFromGit(dir);
        }
        if (!p.isOpen()) p.open(sub.split(1));
        if (!CHARSET.equals(p.getDefaultCharset(false))) p.setDefaultCharset(CHARSET, sub.split(1));
        return p;
    }

    /** The project whose directory is {@code dir}, if there is one. */
    static IProject projectAt(Path dir) {
        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            IPath loc = p.getLocation();
            if (loc != null && loc.toPath().equals(dir)) return p;
        }
        return null;
    }

    /**
     * Adds Eclipse's files to the {@code .git/info/exclude} of the git
     * repository holding {@code dir}, so they don't show in {@code git status}.
     * That file is local to the clone, never committed.
     */
    static void excludeFromGit(Path dir) {
        try {
            for (Path d = dir; d != null; d = d.getParent()) {
                Path git = d.resolve(".git"), gitDir = null;
                if (Files.isDirectory(git)) {
                    gitDir = git;
                } else if (Files.isRegularFile(git)) {
                    // A submodule or worktree: "gitdir: <path>"
                    String s = Files.readString(git, StandardCharsets.UTF_8).strip();
                    if (s.startsWith("gitdir:")) gitDir = d.resolve(s.substring(7).strip()).normalize();
                }
                if (gitDir == null) continue;
                Path common = gitDir.resolve("commondir");
                if (Files.isRegularFile(common))
                    gitDir = gitDir.resolve(Files.readString(common, StandardCharsets.UTF_8).strip()).normalize();
                Path exclude = gitDir.resolve("info").resolve("exclude");
                List<String> lines = Files.isRegularFile(exclude)
                        ? Files.readAllLines(exclude, StandardCharsets.UTF_8) : new ArrayList<>();
                List<String> add = new ArrayList<>();
                for (String e : EXCLUDES) if (!lines.contains(e)) add.add(e);
                if (add.isEmpty()) return;
                StringBuilder b = new StringBuilder();
                if (Files.isRegularFile(exclude)) {
                    String old = Files.readString(exclude, StandardCharsets.UTF_8);
                    b.append(old);
                    if (!old.isEmpty() && !old.endsWith("\n")) b.append('\n');
                }
                b.append("# Eclipse's project files\n");
                for (String a : add) b.append(a).append('\n');
                Files.createDirectories(exclude.getParent());
                Files.writeString(exclude, b, StandardCharsets.UTF_8);
                return;
            }
        } catch (IOException e) {
            Platform.getLog(TreeImport.class).warn("Couldn't add Eclipse's files to the git excludes for " + dir, e);
        }
    }

    /**
     * Puts buildscan's problems with each component's makefile, in the active
     * configuration, on the component's project as warnings.
     */
    static void updateMarkers(BuildTree t) throws CoreException {
        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            if (p.isAccessible()) p.deleteMarkers(MARKER, false, IResource.DEPTH_ZERO);
        Path summary = t.settingsDir(t.active).resolve("summary.json");
        if (!Files.isRegularFile(summary)) return;
        Object o;
        try {
            o = Json.parse(Files.readString(summary, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return;
        }
        if (!(o instanceof Map<?, ?> all)) return;
        for (Map.Entry<?, ?> e : all.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> c) || !(c.get("errors") instanceof List<?> errors) || errors.isEmpty())
                continue;
            IProject p = c.get("dir") instanceof String dir ? projectAt(Path.of(dir)) : null;
            if (p == null || !p.isAccessible()) p = projectAt(t.riscos);
            if (p == null || !p.isAccessible()) continue;
            for (Object err : errors) {
                IMarker m = p.createMarker(MARKER);
                m.setAttributes(new String[] {IMarker.MESSAGE, IMarker.SEVERITY, IMarker.LOCATION},
                        new Object[] {e.getKey() + ": the makefile couldn't be dry-run in " + t.active + ": " + err,
                            IMarker.SEVERITY_WARNING, "makefile"});
            }
        }
    }

    /**
     * Points the language server and CDT at the active configuration's
     * settings, of this tree and every other.
     */
    static void activate(BuildTree t, boolean reload) {
        String dir = BuildTree.activeSettings();
        var prefs = InstanceScope.INSTANCE.getNode(Preferences.BUNDLE);
        prefs.put(Preferences.SETTINGS, dir);
        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            Platform.getLog(TreeImport.class).log(Status.error("Couldn't save the settings directory", e));
        }
        RiscOSLanguageClient.settingsChanged(dir, reload);
        for (TreeParticipant p : TreeParticipant.all()) {
            try {
                p.activated(t);
            } catch (RuntimeException e) {
                Platform.getLog(TreeImport.class).error("Switching configuration failed in " + p.getClass().getName(), e);
            }
        }
    }
}
