package com.piccolosystems.riscos.lsp;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Each RISC OS component's {@code objs/} link farm gives its sources a second
 * name ({@code objs/Wimp10.s} for {@code s/Wimp10}). The {@link Preferences#MIRRORS}
 * preference says which name to use:
 * <ul>
 * <li>{@code links} (the default): the link farm's, as objasm and CDT use them;
 * <li>{@code real}: the real files';
 * <li>{@code both}: show both, but go to the link farm's.
 * </ul>
 * The language server names files to match (see {@link LinkUris}), so that
 * searches, jumps and markers use those names, and {@link MirroredFilesFilter}
 * hides the other names in the Project Explorer.
 */
final class LinkFarms {
    static final String LINKS = "links", REAL = "real", BOTH = "both";

    /** The language server's uriStyle for the preference. */
    static String uriStyle() {
        return REAL.equals(Preferences.store().getString(Preferences.MIRRORS)) ? "real" : "links";
    }

    private LinkFarms() {}

    /**
     * Marks the names the preference doesn't use as derived resources, and
     * the others as not (and the folders of nested projects, see
     * {@link #markNested}): File Search, Open Resource and the like leave
     * derived resources out by default, so that they find each source once,
     * by the name that gets its editor and language features. In the
     * background.
     */
    static void markDerived() {
        WorkspaceJob job = new WorkspaceJob("Marking the second names of RISC OS link farm files") {
            @Override
            public IStatus runInWorkspace(IProgressMonitor monitor) {
                String mode = Preferences.store().getString(Preferences.MIRRORS);
                try {
                    markNested();
                } catch (CoreException | BackingStoreException e) {
                    return new Status(IStatus.WARNING, Preferences.BUNDLE, "Couldn't mark nested projects' folders", e);
                }
                java.util.Set<java.nio.file.Path> seen = new java.util.HashSet<>();
                for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    if (!p.isOpen() || p.getLocation() == null) continue;
                    java.nio.file.Path objs = p.getLocation().toPath().resolve("objs");
                    if (!java.nio.file.Files.isDirectory(objs) || !seen.add(objs)) continue;
                    MirroredFilesFilter.Farm f = MirroredFilesFilter.farm(objs);
                    typeTargets(f);
                    try {
                        for (java.nio.file.Path t : f.targets()) mark(t, LINKS.equals(mode));
                        for (java.nio.file.Path l : f.links()) mark(l, REAL.equals(mode));
                    } catch (CoreException e) {
                        return new Status(IStatus.WARNING, Preferences.BUNDLE,
                                "Couldn't mark the link farm files of " + p.getName(), e);
                    }
                }
                return Status.OK_STATUS;
            }
        };
        job.setRule(ResourcesPlugin.getWorkspace().getRoot());
        job.setSystem(true);
        job.schedule();
    }

    /**
     * Gives each link's file, if its name has no extension, a content type, by
     * name in the settings of the projects holding it, so that something that
     * opens the file by that name (the Search view does, see
     * {@link LinkRedirect}) opens an editor rather than offering to search the
     * Marketplace for one. An assembler file gets the assembler type (the
     * language server takes either name). Any other gets a text type of its
     * own, not the link's ({@code c/main} isn't made C source, as
     * {@code objs/main.c} is): CDT would index it then, and find everything
     * in it twice.
     */
    private static void typeTargets(MirroredFilesFilter.Farm f) {
        var manager = org.eclipse.core.runtime.Platform.getContentTypeManager();
        java.util.Map<IProject, java.util.Map<org.eclipse.core.runtime.content.IContentType, java.util.Set<String>>> add =
                new java.util.LinkedHashMap<>();
        for (var e : f.linkTo().entrySet()) {
            String target = e.getValue().getFileName().toString();
            if (target.contains(".")) continue;
            var linkType = manager.findContentTypeFor(e.getKey().getFileName().toString());
            if (linkType == null) continue;
            var type = linkType.getId().equals(SourceFileTypes.ARMASM) ? linkType : manager.getContentType(LINK_TARGET);
            if (type == null) continue;
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (!project.isOpen() || project.getLocation() == null) continue;
                java.nio.file.Path root = project.getLocation().toPath();
                if (!e.getValue().startsWith(root)) continue;
                add.computeIfAbsent(project, k -> new java.util.LinkedHashMap<>())
                        .computeIfAbsent(type, k -> new java.util.TreeSet<>()).add(target);
            }
        }
        for (var p : add.entrySet())
            for (var t : p.getValue().entrySet()) {
                try {
                    SourceFileTypes.addProjectNames(p.getKey(), t.getKey(), t.getValue());
                } catch (Exception ex) {
                    org.eclipse.core.runtime.Platform.getLog(LinkFarms.class).warn(
                            "Couldn't give the link farm files of " + p.getKey().getName() + " their types", ex);
                }
            }
    }

    static final String LINK_TARGET = "com.piccolosystems.riscos.linkTarget";

    private static final String NESTED_KEY = "nestedDerived";

    /**
     * Marks the folder of each project nested in another (a component's, in
     * its tree's) as derived in the enclosing project, so that searches find
     * its files once, in the component's project, where they get its C
     * settings; and unmarks the folders this marked whose project has gone.
     * The folders marked are listed in the enclosing project's preferences.
     */
    private static void markNested() throws CoreException, BackingStoreException {
        IProject[] all = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        for (IProject outer : all) {
            if (!outer.isOpen() || outer.getLocation() == null) continue;
            java.nio.file.Path root = outer.getLocation().toPath();
            java.util.Set<String> nested = new java.util.TreeSet<>();
            for (IProject inner : all) {
                if (inner == outer || !inner.isOpen() || inner.getLocation() == null) continue;
                java.nio.file.Path loc = inner.getLocation().toPath();
                if (loc.startsWith(root) && !loc.equals(root)) nested.add(root.relativize(loc).toString());
            }
            org.osgi.service.prefs.Preferences node = new ProjectScope(outer).getNode(Preferences.BUNDLE);
            java.util.Set<String> before = new java.util.TreeSet<>(java.util.Arrays.asList(node.get(NESTED_KEY, "").split("\n")));
            before.remove("");
            for (String rel : before) {
                if (nested.contains(rel)) continue;
                IResource r = outer.findMember(rel);
                if (r != null && r.isDerived()) r.setDerived(false, null);
            }
            for (String rel : nested) {
                IResource r = outer.findMember(rel);
                if (r != null && !r.isDerived()) r.setDerived(true, null);
            }
            if (!before.equals(nested)) {
                if (nested.isEmpty()) node.remove(NESTED_KEY);
                else node.put(NESTED_KEY, String.join("\n", nested));
                node.flush();
            }
        }
    }

    /** Marks every workspace file at exactly this path (in a component's project and its tree's). */
    private static void mark(java.nio.file.Path path, boolean derived) throws CoreException {
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (!project.isOpen() || project.getLocation() == null) continue;
            java.nio.file.Path root = project.getLocation().toPath();
            if (!path.startsWith(root) || path.equals(root)) continue;
            IResource r = project.findMember(root.relativize(path).toString());
            if (r != null && r.exists() && r.isDerived() != derived) r.setDerived(derived, null);
        }
    }

    /** Shows the Project Explorer again by the preference. */
    static void refreshViews() {
        MirroredFilesFilter.forget();
        Display.getDefault().asyncExec(() -> {
            if (!PlatformUI.isWorkbenchRunning()) return;
            for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows())
                for (IWorkbenchPage page : w.getPages())
                    for (var ref : page.getViewReferences()) {
                        IViewPart v = ref.getView(false);
                        if (v instanceof CommonNavigator n) n.getCommonViewer().refresh();
                    }
        });
    }
}
