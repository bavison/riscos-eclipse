package com.piccolosystems.riscos.lsp;

import java.lang.reflect.Field;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.progress.PendingUpdateAdapter;
import org.eclipse.ui.views.contentoutline.ContentOutline;

/**
 * Asks again for the symbols of an Outline left showing "Computing symbols…".
 *
 * <p>LSP4E's Outline asks for a document's symbols twice when it opens: once
 * straight away, and again when its reconciler first runs. If the first answer
 * arrives and the Outline then redraws while the second is still on its way,
 * it shows its "Computing symbols…" placeholder; and when the second answer is
 * the same as the first, LSP4E doesn't redraw it again, so the placeholder
 * stays until the document is edited. It happens most to the editor that is
 * active when Eclipse starts, while the language server is busy indexing.
 *
 * <p>Every few seconds, if the Outline of one of our editors has shown the
 * placeholder for a while, it is drawn again; if it still shows it, its
 * input is set again, which makes LSP4E ask again.
 */
public class OutlineWatchdog {
    private static final int PERIOD_MS = 2000;
    /** How long the placeholder may show before the symbols are asked for again. */
    private static final long STUCK_MS = 5000;

    private Object stuckPage;
    private long stuckSince;

    /** Starts watching, from {@link Startup}. */
    static void start(Display d) {
        OutlineWatchdog w = new OutlineWatchdog();
        d.asyncExec(() -> d.timerExec(PERIOD_MS, w::check));
    }

    private void check() {
        if (PlatformUI.getWorkbench().isClosing()) return;
        try {
            look();
        } catch (RuntimeException | LinkageError e) {
            return;      // LSP4E has changed: stop rather than get in the way
        }
        PlatformUI.getWorkbench().getDisplay().timerExec(PERIOD_MS, this::check);
    }

    private void look() {
        IWorkbenchWindow w = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = w == null ? null : w.getActivePage();
        if (page == null) return;
        IEditorPart ed = page.getActiveEditor();
        if (ed == null || !ours(ed)) {
            stuckPage = null;
            return;
        }
        if (!(page.findView("org.eclipse.ui.views.ContentOutline") instanceof ContentOutline outline)) return;
        Object p = outline.getCurrentPage();
        StructuredViewer viewer = viewerOf(p);
        if (viewer == null || !pending(viewer)) {
            stuckPage = null;
            return;
        }
        long now = System.currentTimeMillis();
        if (stuckPage != p) {
            stuckPage = p;
            stuckSince = now;
            asked = false;
        } else if (now - stuckSince >= STUCK_MS) {
            if (!asked) {
                // The answer has usually come: drawing the Outline again shows it.
                viewer.refresh();
                asked = true;
            } else {
                // No answer: LSP4E asks again, and the next look draws what comes.
                viewer.setInput(viewer.getInput());
                asked = false;
            }
            stuckSince = now - STUCK_MS + PERIOD_MS;      // look again next time
        }
    }

    private boolean asked;

    /** Whether the Outline shows only the placeholder. */
    private static boolean pending(StructuredViewer viewer) {
        if (!(viewer.getControl() instanceof Tree t) || t.isDisposed() || t.getItemCount() != 1) return false;
        return t.getItem(0).getData() instanceof PendingUpdateAdapter;
    }

    /** LSP4E's Outline page's viewer, or null if it isn't one. */
    public static StructuredViewer viewerOf(Object page) {
        if (page == null || !page.getClass().getName().equals("org.eclipse.lsp4e.outline.CNFOutlinePage")) return null;
        try {
            Field f = page.getClass().getDeclaredField("outlineViewer");
            f.setAccessible(true);
            return f.get(page) instanceof StructuredViewer v ? v : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Whether the editor's file is one of ours: assembler or BBC BASIC. */
    private static boolean ours(IEditorPart ed) {
        IFile f = ed.getEditorInput().getAdapter(IFile.class);
        if (f == null) return ConvertedFiles.isConverted(ed.getEditorInput());
        try {
            IContentDescription c = f.getContentDescription();
            return c != null && c.getContentType().getId().startsWith("com.piccolosystems.riscos");
        } catch (CoreException e) {
            return false;
        }
    }
}
