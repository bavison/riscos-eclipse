package com.piccolosystems.riscos.lsp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileInfo;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.filesystem.provider.FileStore;
import org.eclipse.core.filesystem.provider.FileSystem;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorMatchingStrategy;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.ide.FileStoreEditorInput;

import com.piccolosystems.riscos.asmindex.text.EditableText;

/**
 * Files edited as converted text: a Messages file with NULs, tokenised BASIC
 * (shown as text; saved tokenised, its unchanged lines keeping their numbers).
 * The editor opens them through the {@code riscos-text:} file system, whose
 * files are the real files converted by {@link EditableText} as they are read
 * and converted back as they are written. {@code riscos-text:/path} is the
 * file at {@code /path}, named as given (a link farm's name stays a link).
 */
public final class ConvertedFiles {
    public static final String SCHEME = "riscos-text";

    private ConvertedFiles() {
    }

    public static URI uri(Path p) {
        try {
            // The path as a file: URI has it, /C:/x on Windows.
            return new URI(SCHEME, null, p.toAbsolutePath().normalize().toUri().getPath(), null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * The file a URI of one of our schemes ({@code riscos-text:}, {@code riscos-link:})
     * names: its path read as a {@code file:} URI's, so {@code /C:/x} is {@code C:\x} on Windows.
     */
    public static Path pathOf(URI u) {
        try {
            return Path.of(new URI("file", null, u.getPath(), null));
        } catch (URISyntaxException | RuntimeException e) {
            return Path.of(u.getPath());
        }
    }

    /** The real file of an editor input, if it is a local file. */
    static Path pathOf(IEditorInput input) {
        if (input instanceof IFileEditorInput fi) {
            IPath loc = fi.getFile().getLocation();
            return loc == null ? null : loc.toPath();
        }
        if (input instanceof IURIEditorInput ui) {
            URI u = ui.getURI();
            if (u != null && "file".equals(u.getScheme())) return Path.of(u);
            if (u != null && SCHEME.equals(u.getScheme())) return pathOf(u);
        }
        return null;
    }

    /** Whether an editor input is a converted file. */
    public static boolean isConverted(IEditorInput input) {
        return input instanceof IURIEditorInput ui && ui.getURI() != null && SCHEME.equals(ui.getURI().getScheme());
    }

    /** An empty tokenised BASIC program: just the end marker. */
    private static final byte[] EMPTY_PROGRAM = {0x0D, (byte) 0xFF};

    /**
     * A file's contents, except that an empty file named as tokenised BASIC
     * ({@code ,ffb}) is taken to be an empty program: so a new one, made with
     * File › New › File, is edited as text and saved tokenised.
     */
    static byte[] contents(Path p) throws IOException {
        byte[] data = Files.readAllBytes(p);
        if (data.length == 0 && p.getFileName() != null
                && p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(",ffb"))
            return EMPTY_PROGRAM;
        return data;
    }

    /** The input to edit: a file that needs converting through {@code riscos-text:}, else the input as it is. */
    static IEditorInput editable(IEditorInput input) {
        if (isConverted(input)) return input;
        if (input instanceof org.eclipse.ui.IStorageEditorInput si && !(input instanceof org.eclipse.ui.IFileEditorInput)
                && !(input instanceof IURIEditorInput)) {
            IEditorInput converted = convertedStorage(si);
            return converted != null ? converted : input;
        }
        Path p = pathOf(input);
        if (p == null || !Files.isRegularFile(p)) return input;
        try {
            if (EditableText.kindOf(contents(p)) == EditableText.Kind.PLAIN) return input;
            return new FileStoreEditorInput(EFS.getStore(uri(p)));
        } catch (IOException | CoreException e) {
            Platform.getLog(ConvertedFiles.class).warn("Couldn't open " + p + " converted", e);
            return input;
        }
    }

    /**
     * Contents that aren't a file, such as a revision in git's history or a
     * compare: if they need converting (a tokenised program, NULs), the same
     * contents converted, read-only; else null.
     */
    private static IEditorInput convertedStorage(org.eclipse.ui.IStorageEditorInput si) {
        try {
            org.eclipse.core.resources.IStorage st = si.getStorage();
            byte[] data;
            try (InputStream in = st.getContents()) {
                data = in.readAllBytes();
            }
            if (data.length == 0 && st.getName() != null && st.getName().toLowerCase(java.util.Locale.ROOT).endsWith(",ffb"))
                data = EMPTY_PROGRAM;
            EditableText.Kind kind = EditableText.kindOf(data);
            if (kind == EditableText.Kind.PLAIN) return null;
            byte[] text = EditableText.toText(data, kind);
            if (text.length >= 3 && text[0] == (byte) 0xEF && text[1] == (byte) 0xBB && text[2] == (byte) 0xBF)
                text = java.util.Arrays.copyOfRange(text, 3, text.length);     // the charset says UTF-8 already
            return new ConvertedStorageInput(si, text);
        } catch (CoreException | IOException | RuntimeException e) {
            return null;
        }
    }

    /** Contents shown converted, read-only, under their own name. */
    static final class ConvertedStorageInput extends org.eclipse.core.runtime.PlatformObject
            implements org.eclipse.ui.IStorageEditorInput {
        private final org.eclipse.ui.IStorageEditorInput original;
        private final byte[] text;

        ConvertedStorageInput(org.eclipse.ui.IStorageEditorInput original, byte[] text) {
            this.original = original;
            this.text = text;
        }

        @Override
        public org.eclipse.core.resources.IStorage getStorage() {
            return new Storage();
        }

        private final class Storage extends org.eclipse.core.runtime.PlatformObject
                implements org.eclipse.core.resources.IEncodedStorage {
            @Override
            public InputStream getContents() {
                return new ByteArrayInputStream(text);
            }

            @Override
            public org.eclipse.core.runtime.IPath getFullPath() {
                try {
                    return original.getStorage().getFullPath();
                } catch (CoreException e) {
                    return null;
                }
            }

            @Override
            public String getName() {
                return original.getName();
            }

            @Override
            public boolean isReadOnly() {
                return true;
            }

            @Override
            public String getCharset() {
                return "UTF-8";
            }
        }

        @Override
        public boolean exists() {
            return original.exists();
        }

        @Override
        public org.eclipse.jface.resource.ImageDescriptor getImageDescriptor() {
            return original.getImageDescriptor();
        }

        @Override
        public String getName() {
            return original.getName();
        }

        @Override
        public org.eclipse.ui.IPersistableElement getPersistable() {
            return null;
        }

        @Override
        public String getToolTipText() {
            return original.getToolTipText();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ConvertedStorageInput c && c.original.equals(original);
        }

        @Override
        public int hashCode() {
            return original.hashCode();
        }
    }

    /** Opening a file finds the editor already open on it converted. */
    public static class Matching implements IEditorMatchingStrategy {
        @Override
        public boolean matches(IEditorReference ref, IEditorInput input) {
            try {
                IEditorInput open = ref.getEditorInput();
                if (open.equals(input)) return true;
                Path a = pathOf(open), b = pathOf(input);
                return a != null && a.toAbsolutePath().normalize().equals(b == null ? null : b.toAbsolutePath().normalize());
            } catch (PartInitException e) {
                return false;
            }
        }
    }

    /** The {@code riscos-text:} file system. */
    public static class Scheme extends FileSystem {
        @Override
        public IFileStore getStore(URI uri) {
            return new Store(pathOf(uri));
        }

        @Override
        public boolean canWrite() {
            return true;
        }
    }

    /** A real file, seen converted. */
    static final class Store extends FileStore {
        final Path path;

        Store(Path path) {
            this.path = path;
        }

        private IFileStore real() {
            return EFS.getLocalFileSystem().getStore(IPath.fromPath(path));
        }

        @Override
        public String[] childNames(int options, IProgressMonitor monitor) throws CoreException {
            return real().childNames(options, monitor);
        }

        @Override
        public IFileInfo fetchInfo(int options, IProgressMonitor monitor) throws CoreException {
            return real().fetchInfo(options, monitor);
        }

        @Override
        public void putInfo(IFileInfo info, int options, IProgressMonitor monitor) throws CoreException {
            real().putInfo(info, options, monitor);
        }

        @Override
        public IFileStore getChild(String name) {
            return new Store(path.resolve(name));
        }

        @Override
        public String getName() {
            Path n = path.getFileName();
            return n == null ? "" : n.toString();
        }

        @Override
        public IFileStore getParent() {
            return path.getParent() == null ? null : new Store(path.getParent());
        }

        @Override
        public InputStream openInputStream(int options, IProgressMonitor monitor) throws CoreException {
            try {
                byte[] data = contents(path);
                return new ByteArrayInputStream(EditableText.toText(data, EditableText.kindOf(data)));
            } catch (IOException e) {
                throw new CoreException(Status.error("Couldn't read " + path + ": " + e.getMessage(), e));
            }
        }

        @Override
        public OutputStream openOutputStream(int options, IProgressMonitor monitor) throws CoreException {
            return new ByteArrayOutputStream() {
                private boolean closed;

                @Override
                public void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    byte[] old = Files.isRegularFile(path) ? contents(path) : null;
                    EditableText.Saved saved = EditableText.save(toByteArray(), old);
                    Files.write(path, saved.data());
                    refreshWorkspace(path);
                    if (!saved.problems().isEmpty()) tell(path, saved.problems());
                }
            };
        }

        @Override
        public URI toURI() {
            return uri(path);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Store s && s.path.equals(path);
        }

        @Override
        public int hashCode() {
            return path.hashCode();
        }
    }

    /** Tells the user what was wrong with a saved program, as Edit does, without holding up the save. */
    static void tell(Path path, java.util.List<String> problems) {
        Platform.getLog(ConvertedFiles.class).warn(path + ": " + String.join("; ", problems));
        if (!org.eclipse.ui.PlatformUI.isWorkbenchRunning()) return;
        org.eclipse.swt.widgets.Display d = org.eclipse.ui.PlatformUI.getWorkbench().getDisplay();
        d.asyncExec(() -> org.eclipse.jface.dialogs.MessageDialog.openWarning(d.getActiveShell(),
                "Saved " + path.getFileName(), "The program was saved, but:\n\n" + String.join("\n", problems)));
    }

    /** Tells the workspace a file has changed, so its resource is in step. */
    static void refreshWorkspace(Path path) {
        Job.create("Refreshing " + path.getFileName(), m -> {
            Set<IFile> files = new LinkedHashSet<>();
            for (IFile f : ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(path.toUri())) files.add(f);
            IFile literal = LinkUris.fileAt(path);
            if (literal != null) files.add(literal);
            for (IFile f : files) {
                try {
                    f.refreshLocal(IResource.DEPTH_ZERO, m);
                } catch (CoreException e) {
                    // it will be noticed later
                }
            }
        }).schedule();
    }
}
