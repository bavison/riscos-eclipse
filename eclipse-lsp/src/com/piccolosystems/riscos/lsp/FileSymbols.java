package com.piccolosystems.riscos.lsp;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Location;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.navigator.CommonActionProvider;
import org.eclipse.ui.navigator.ICommonActionConstants;
import org.eclipse.ui.navigator.ICommonActionExtensionSite;

/**
 * In the Project Explorer, an assembler or BASIC file opens out into what its
 * outline shows, as CDT does for a C file. The symbols come from the language
 * server, which knows an assembler file from the index and reads a BASIC
 * file from disk if it isn't open; so expanding a file needn't open it.
 * They're sorted as CDT sorts a C file's, and fetched in the background, a
 * file showing "Pending..." meanwhile,
 * and fetched again when the file changes.
 */
public final class FileSymbols {
    private FileSymbols() {
    }

    /** A symbol of a file, with the symbol it is inside, if any. */
    public record Node(IFile file, DocumentSymbol symbol, Node parent) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Node n && n.file.equals(file) && n.symbol.equals(symbol);
        }

        @Override
        public int hashCode() {
            return file.hashCode() * 31 + symbol.getName().hashCode();
        }
    }

    /** What a file shows while its symbols are fetched. */
    public static final class Pending {
        @Override
        public String toString() {
            return "Pending...";
        }
    }

    public static final Pending PENDING = new Pending();

    private static final String ARMASM = "com.piccolosystems.riscos.armasm", BASIC = "com.piccolosystems.riscos.bbcbasic";

    /** The kind of file it is ({@link #ARMASM} or {@link #BASIC}), from its name; null for anything else. */
    static String kind(IFile f) {
        IContentType asm = Platform.getContentTypeManager().getContentType(ARMASM);
        IContentType basic = Platform.getContentTypeManager().getContentType(BASIC);
        for (IContentType t : Platform.getContentTypeManager().findContentTypesFor(f.getName())) {
            if (basic != null && t.isKindOf(basic)) return BASIC;
            if (asm != null && t.isKindOf(asm)) return ARMASM;
        }
        return null;
    }

    static IFile file(Object o) {
        return o instanceof IFile f ? f : o instanceof IAdaptable a && !(o instanceof IResource) ? a.getAdapter(IFile.class) : null;
    }

    public static class ContentProvider implements ITreeContentProvider, IResourceChangeListener {
        private record Fetched(long stamp, Node[] nodes) {}

        private final Map<IFile, Fetched> fetched = new ConcurrentHashMap<>();
        private final Set<IFile> fetching = ConcurrentHashMap.newKeySet();
        private volatile StructuredViewer viewer;

        public ContentProvider() {
            ResourcesPlugin.getWorkspace().addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE);
        }

        @Override
        public void inputChanged(Viewer v, Object oldInput, Object newInput) {
            viewer = v instanceof StructuredViewer s ? s : null;
        }

        @Override
        public Object[] getElements(Object input) {
            return getChildren(input);
        }

        @Override
        public Object[] getChildren(Object parent) {
            if (parent instanceof Node n) return nodes(n.file, n.symbol.getChildren(), n);
            IFile f = file(parent);
            if (f == null || kind(f) == null) return new Object[0];
            Fetched got = fetched.get(f);
            if (got != null && got.stamp == f.getModificationStamp()) return got.nodes;
            fetch(f, parent);
            return got != null ? got.nodes : new Object[] {PENDING};
        }

        private void fetch(IFile f, Object element) {
            if (!fetching.add(f) || f.getLocationURI() == null) return;
            long stamp = f.getModificationStamp();
            RiscOSLanguageClient.symbols(f.getLocationURI(), kind(f) == BASIC).whenComplete((symbols, e) -> {
                fetching.remove(f);
                Node[] nodes = nodes(f, symbols == null ? List.of() : symbols, null);
                // An empty answer may only mean indexing hasn't got to the file: ask again next time.
                if (nodes.length > 0) fetched.put(f, new Fetched(stamp, nodes));
                else fetched.remove(f);
                refresh(element);
            });
        }

        private void refresh(Object element) {
            StructuredViewer v = viewer;
            if (v == null) return;
            Display d = v.getControl().getDisplay();
            if (!d.isDisposed()) d.asyncExec(() -> {
                if (!v.getControl().isDisposed()) v.refresh(element, true);
            });
        }

        private static Node[] nodes(IFile f, List<DocumentSymbol> symbols, Node parent) {
            if (symbols == null) return new Node[0];
            return symbols.stream().map(s -> new Node(f, s, parent)).toArray(Node[]::new);
        }

        @Override
        public Object getParent(Object element) {
            if (element instanceof Node n) return n.parent != null ? n.parent : n.file;
            return null;
        }

        @Override
        public boolean hasChildren(Object element) {
            if (element instanceof Node n) return n.symbol.getChildren() != null && !n.symbol.getChildren().isEmpty();
            IFile f = file(element);
            if (f == null || kind(f) == null) return false;
            Fetched got = fetched.get(f);
            return got == null || got.nodes.length > 0;
        }

        @Override
        public void resourceChanged(IResourceChangeEvent event) {
            if (event.getDelta() == null || fetched.isEmpty()) return;
            try {
                event.getDelta().accept(d -> {
                    if (d.getResource() instanceof IFile f && fetched.containsKey(f)
                            && (d.getKind() == IResourceDelta.REMOVED || (d.getFlags() & IResourceDelta.CONTENT) != 0)) {
                        fetched.remove(f);
                        if (d.getKind() != IResourceDelta.REMOVED) refresh(f);
                    }
                    return true;
                });
            } catch (CoreException e) {
                // nothing to update then
            }
        }

        @Override
        public void dispose() {
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
        }
    }

    /** Names and icons as the Outline view has them: the name, then its kind and value in the muted colour. */
    public static class Labels extends LabelProvider
            implements org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider {
        private final org.eclipse.lsp4e.outline.SymbolsLabelProvider outline = new org.eclipse.lsp4e.outline.SymbolsLabelProvider(false, false);

        @Override
        public String getText(Object element) {
            if (element instanceof Node n) return outline.getText(n.symbol);
            if (element == PENDING) return PENDING.toString();
            return null;
        }

        @Override
        public org.eclipse.jface.viewers.StyledString getStyledText(Object element) {
            if (element instanceof Node n) return outline.getStyledText(n.symbol);
            return new org.eclipse.jface.viewers.StyledString(element == PENDING ? PENDING.toString() : "");
        }

        @Override
        public Image getImage(Object element) {
            return element instanceof Node n ? outline.getImage(n.symbol) : null;
        }

        @Override
        public void dispose() {
            outline.dispose();
            super.dispose();
        }
    }

    /**
     * A file's symbols sorted as CDT sorts a C file's: by kind, in CDT's order
     * (files included; macros; declarations of things defined elsewhere; constants, fields and
     * register names, as C's types; variables; labels and routines, as C's
     * functions), then by name, with names starting {@code _} or {@code __}
     * after the others of their kind.
     */
    public static class LikeCdt extends ViewerComparator {
        @Override
        public int category(Object element) {
            if (!(element instanceof Node n)) return 0;
            int k = n.symbol.getKind() == null ? 0 : n.symbol.getKind().getValue();
            int group = switch (k) {
                case 1 -> 0;                    // File: a GET, INCLUDE or LNK, as C's #include
                case 25 -> 1;                   // Operator: a macro
                case 11 -> 2;                   // Interface: IMPORTed, defined elsewhere
                case 14, 8, 22 -> 3;            // Constant, Field, EnumMember (register names)
                case 13 -> 4;                   // Variable
                case 12, 6 -> 5;                // Function, Method: labels, PROCs and FNs
                default -> 6;
            };
            String name = n.symbol.getName();
            int reserved = name.startsWith("__") ? 2 : name.startsWith("_") ? 1 : 0;
            return group * 3 + reserved;
        }

        @Override
        public int compare(Viewer viewer, Object a, Object b) {
            int c = Integer.compare(category(a), category(b));
            if (c != 0 || !(a instanceof Node x) || !(b instanceof Node y)) return c;
            return getComparator().compare(x.symbol.getName(), y.symbol.getName());
        }
    }

    /** Double-clicking (or Enter on) a symbol opens its file there. */
    public static class Open extends CommonActionProvider {
        private IAction open;

        @Override
        public void init(ICommonActionExtensionSite site) {
            open = new Action("Open") {
                @Override
                public void run() {
                    if (site.getStructuredViewer().getSelection() instanceof IStructuredSelection s
                            && s.getFirstElement() instanceof Node n)
                        open(n);
                }
            };
        }

        @Override
        public void fillActionBars(IActionBars bars) {
            if (getContext() != null && getContext().getSelection() instanceof IStructuredSelection s
                    && s.size() == 1 && s.getFirstElement() instanceof Node)
                bars.setGlobalActionHandler(ICommonActionConstants.OPEN, open);
        }
    }

    public static void open(Node n) {
        LSPEclipseUtils.openInEditor(new Location(n.file.getLocationURI().toString(), n.symbol.getSelectionRange()));
    }
}
