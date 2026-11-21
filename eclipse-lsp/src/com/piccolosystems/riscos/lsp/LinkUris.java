package com.piccolosystems.riscos.lsp;

import java.net.URI;
import java.nio.file.Path;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IAdapterFactory;
import org.eclipse.core.runtime.IPath;

/**
 * Turns the language server's {@code riscos-link:/path} URIs into workspace
 * files, without resolving symbolic links.
 *
 * <p>Eclipse's own lookup of a file by its location resolves links, so a
 * {@code file:} URI naming {@code objs/Wimp10.s} finds {@code s/Wimp10}, and
 * nothing at all if that is hidden. With the link farm's names preferred, the
 * server names such files with this scheme instead, and LSP4E, which adapts a
 * URI of any scheme but {@code file:} to an {@link IResource}, comes here: the
 * file is found by its path within the project that holds it.
 */
public class LinkUris implements IAdapterFactory {
    static final String SCHEME = "riscos-link";

    @Override
    public <T> T getAdapter(Object adaptable, Class<T> adapterType) {
        // LSP4E adapts the URI as a string; accept a java.net.URI as well.
        URI uri;
        try {
            uri = adaptable instanceof URI u ? u : adaptable instanceof String str ? new URI(str) : null;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
        if (uri == null || !SCHEME.equals(uri.getScheme())) return null;
        if (!adapterType.isAssignableFrom(IFile.class)) return null;
        IFile f = fileAt(ConvertedFiles.pathOf(uri));
        return f == null ? null : adapterType.cast(f);
    }

    @Override
    public Class<?>[] getAdapterList() {
        return new Class<?>[] {IResource.class, IFile.class};
    }

    /**
     * The workspace file at {@code path}, taken literally (links not
     * resolved), in the most deeply nested open project that contains it.
     */
    static IFile fileAt(Path path) {
        Path p = path.toAbsolutePath().normalize();
        IFile best = null;
        int depth = -1;
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (!project.isOpen()) continue;
            IPath loc = project.getLocation();
            if (loc == null) continue;
            Path root = loc.toFile().toPath().toAbsolutePath().normalize();
            if (!p.startsWith(root) || p.equals(root) || root.getNameCount() <= depth) continue;
            IFile f = project.getFile(root.relativize(p).toString());
            if (f.exists()) {
                best = f;
                depth = root.getNameCount();
            }
        }
        return best;
    }
}
