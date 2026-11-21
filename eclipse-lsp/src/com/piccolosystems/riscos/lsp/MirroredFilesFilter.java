package com.piccolosystems.riscos.lsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;

/**
 * A Project Explorer filter that shows each file of an {@code objs/} link
 * farm by one name only, as Preferences › RISC OS says: by default the real
 * files the links point to are hidden ({@code s/Wimp10}), so that sources are
 * opened by the names objasm and CDT use ({@code objs/Wimp10.s}).
 *
 * <p>It only affects what the view shows. Eclipse's resources are untouched:
 * hiding them with resource filters made Eclipse treat the links, which it
 * sees as aliases of the hidden files, as deleted, closing their editors and
 * dropping their markers. Directories stay visible unless a link names one
 * whole ({@code objs/Resources}), so that new files can be made in {@code c/}
 * or {@code s/}. It isn't listed in the view's Filters and Customization
 * dialog: a filter listed there is off in any Project Explorer whose filters
 * were saved before the filter was installed. The preference's third choice
 * shows both names.
 */
public class MirroredFilesFilter extends ViewerFilter {
    /** For each objs/ directory: when it was last examined, and what its links point to. */
    /** A link farm: the files its links name, the links, and each link's file. */
    record Farm(long modified, Set<Path> targets, Set<Path> links, Map<Path, Path> linkTo) {}

    private static final Map<Path, Farm> farms = new ConcurrentHashMap<>();

    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element) {
        IResource r = element instanceof IResource res ? res
                : element instanceof IAdaptable a ? a.getAdapter(IResource.class) : null;
        return r == null || !hidden(r);
    }

    /** Whether the preference hides {@code r}. */
    public static boolean hidden(IResource r) {
        String mode = Preferences.store().getString(Preferences.MIRRORS);
        if (LinkFarms.BOTH.equals(mode) || r.getType() == IResource.PROJECT) return false;
        IPath loc = r.getLocation();
        if (loc == null) return false;
        Path p = loc.toFile().toPath().toAbsolutePath().normalize();
        boolean hideLinks = LinkFarms.REAL.equals(mode);
        // The component is the nearest directory above with an objs/ in it.
        for (Path c = p.getParent(); c != null; c = c.getParent()) {
            Path objs = c.resolve("objs");
            if (!Files.isDirectory(objs)) continue;
            Farm f = farm(objs);
            return hideLinks ? f.links.contains(p) : f.targets.contains(p);
        }
        return false;
    }

    static Farm farm(Path objs) {
        long modified;
        try {
            modified = Files.getLastModifiedTime(objs).toMillis();
        } catch (IOException e) {
            return new Farm(0, Set.of(), Set.of(), Map.of());
        }
        Farm f = farms.get(objs);
        if (f != null && f.modified == modified) return f;
        Set<Path> targets = new HashSet<>(), links = new HashSet<>();
        Map<Path, Path> linkTo = new java.util.HashMap<>();
        Path component = objs.getParent();
        try (Stream<Path> s = Files.walk(objs)) {
            for (Path l : (Iterable<Path>) s::iterator) {
                if (!Files.isSymbolicLink(l)) continue;
                Path t = l.getParent().resolve(Files.readSymbolicLink(l)).normalize();
                if (!t.startsWith(component) || t.startsWith(objs) || !Files.exists(t)) continue;
                // A directory the link doesn't name whole stays, for new files.
                targets.add(t);
                links.add(l.toAbsolutePath().normalize());
                linkTo.put(l.toAbsolutePath().normalize(), t);
            }
        } catch (IOException e) {
            // what was found so far
        }
        f = new Farm(modified, targets, links, linkTo);
        farms.put(objs, f);
        return f;
    }

    /**
     * The other name of a link farm file that the preference hides: its link
     * if it is a link's file, its file if it is a link; or null.
     */
    static Path shownName(Path hidden) {
        Path p = hidden.toAbsolutePath().normalize();
        for (Path c = p.getParent(); c != null; c = c.getParent()) {
            Path objs = c.resolve("objs");
            if (!Files.isDirectory(objs)) continue;
            Farm f = farm(objs);
            for (Map.Entry<Path, Path> e : f.linkTo.entrySet()) {
                if (e.getValue().equals(p)) return e.getKey();
                if (e.getKey().equals(p)) return e.getValue();
            }
            return null;
        }
        return null;
    }

    /** Forgets what was found, so that the next look examines the link farms again. */
    static void forget() {
        farms.clear();
    }
}
