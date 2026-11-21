package com.piccolosystems.riscos.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.content.IContentTypeSettings;
import org.eclipse.core.runtime.jobs.Job;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/**
 * Makes every file the assembler reads an assembler file in Eclipse, in the
 * project that holds it, so that it gets colouring and reaches the language
 * server.
 *
 * <p>Many RISC OS assembler files have no extension: the exported headers
 * ({@code Hdr:Macros}), and the real files behind the {@code objs/} link
 * farm's {@code .s} and {@code .hdr} names ({@code s/Wimp10}). Eclipse
 * resolves the links, so an editor opened on {@code objs/Wimp10.s} is
 * really on {@code s/Wimp10}. Content types can't match by directory, so
 * this adds the names themselves to the assembler content type, as
 * project-specific settings (the project's
 * {@code .settings/org.eclipse.core.runtime.prefs}), and as the workspace's
 * own settings too (Preferences › General › Content Types). LSP4E needs the
 * latter: it decides whether a file belongs to a language server from its
 * name and contents alone, not its project's settings, so without them an
 * exported header such as {@code Hdr:Font} would be coloured but get no
 * hover, outline or definitions. Files of those names in other projects of
 * the workspace are assembler files too, then.
 */
final class SourceFileTypes {
    static final String ARMASM = "com.piccolosystems.riscos.armasm";

    /** Names already added, per project, so that repeated lists cost nothing. */
    private static final Map<String, Set<String>> done = new LinkedHashMap<>();

    private SourceFileTypes() {}

    /**
     * The exported assembler headers ({@code Export/<APCS>/Hdr/...}, or
     * {@code Export/Hdr/...}), made
     * assembler files straight away on import, without waiting for the
     * language server to list them once it has indexed.
     */
    static void exportedHeaders(Path riscos, IProgressMonitor monitor) {
        List<String> files = new ArrayList<>();
        Path export = riscos.resolve("Export");
        if (java.nio.file.Files.isDirectory(export)) {
            try (var apcs = java.nio.file.Files.list(export)) {
                List<Path> hdrs = new ArrayList<>(List.of(export.resolve("Hdr")));
                for (Path a : (Iterable<Path>) apcs::iterator) hdrs.add(a.resolve("Hdr"));
                for (Path hdr : hdrs) {
                    if (!java.nio.file.Files.isDirectory(hdr)) continue;
                    try (var s = java.nio.file.Files.walk(hdr)) {
                        s.filter(java.nio.file.Files::isRegularFile).forEach(f -> files.add(f.toString()));
                    }
                }
            } catch (java.io.IOException | RuntimeException e) {
                Platform.getLog(SourceFileTypes.class).warn("Couldn't list the exported headers in " + export, e);
            }
        }
        apply(files, monitor);
    }

    static void update(List<String> files) {
        if (files == null || files.isEmpty()) return;
        Job job = Job.create("Associating RISC OS assembler files", (IProgressMonitor monitor) -> {
            apply(files, monitor);
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    private static void apply(List<String> files, IProgressMonitor monitor) {
        IContentType armasm = Platform.getContentTypeManager().getContentType(ARMASM);
        if (armasm == null) return;
        Set<String> extensions = new HashSet<>(Arrays.asList(armasm.getFileSpecs(IContentType.FILE_EXTENSION_SPEC)));
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        Map<IProject, Set<String>> names = new LinkedHashMap<>();
        for (String f : files) {
            if (monitor.isCanceled()) return;
            URI uri;
            try {
                uri = Path.of(f).toUri();
            } catch (RuntimeException e) {
                continue;
            }
            // Eclipse's lookup resolves links, and finds nothing for a hidden
            // real file, so look the path up literally as well.
            List<IFile> found = new ArrayList<>(Arrays.asList(root.findFilesForLocationURI(uri)));
            IFile literal = LinkUris.fileAt(Path.of(f));
            if (literal != null && !found.contains(literal)) found.add(literal);
            for (IFile file : found) {
                String name = file.getName();
                String ext = file.getFileExtension();
                if (ext != null && extensions.contains(ext)) continue;
                names.computeIfAbsent(file.getProject(), p -> new TreeSet<>()).add(name);
            }
        }
        // The workspace's own settings, for LSP4E.
        Set<String> all = new TreeSet<>();
        for (Set<String> s : names.values()) all.addAll(s);
        Set<String> global = new HashSet<>(Arrays.asList(armasm.getFileSpecs(IContentType.FILE_NAME_SPEC)));
        for (String n : all) {
            if (global.contains(n)) continue;
            try {
                armasm.addFileSpec(n, IContentType.FILE_NAME_SPEC);
            } catch (CoreException | RuntimeException ex) {
                Platform.getLog(SourceFileTypes.class).log(new Status(IStatus.WARNING, BUNDLE,
                        "Couldn't make files called " + n + " assembler files", ex));
                break;
            }
        }
        for (Map.Entry<IProject, Set<String>> e : names.entrySet()) {
            IProject project = e.getKey();
            Set<String> already = done.computeIfAbsent(project.getName(), k -> new HashSet<>());
            List<String> add = new ArrayList<>();
            for (String n : e.getValue()) if (already.add(n)) add.add(n);
            if (add.isEmpty()) continue;
            try {
                addProjectNames(project, armasm, add);
            } catch (Exception ex) {
                Platform.getLog(SourceFileTypes.class).log(new Status(IStatus.WARNING, BUNDLE,
                        "Couldn't make RISC OS assembler files in " + project.getName() + " assembler files", ex));
            }
        }
    }

    /**
     * Adds file names to a content type in one project's own settings
     * (turning those on), as the names of files of that type.
     */
    static void addProjectNames(IProject project, IContentType type, java.util.Collection<String> add) throws Exception {
        if (add.isEmpty()) return;
        ProjectScope scope = new ProjectScope(project);
        Preferences node = scope.getNode(Platform.PI_RUNTIME).node("content-types");
        if (!node.getBoolean("enabled", false)) {
            node.putBoolean("enabled", true);
            node.flush();
        }
        IContentTypeSettings settings = type.getSettings(scope);
        Set<String> present = new HashSet<>(Arrays.asList(settings.getFileSpecs(IContentType.FILE_NAME_SPEC)));
        for (String n : add) if (!present.contains(n)) settings.addFileSpec(n, IContentType.FILE_NAME_SPEC);
    }

    private static final String BUNDLE = "com.piccolosystems.riscos.lsp";
}
