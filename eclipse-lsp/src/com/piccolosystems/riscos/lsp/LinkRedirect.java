package com.piccolosystems.riscos.lsp;

import java.nio.file.Path;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Reopens an editor opened on the name of a link farm file that the
 * preference hides, by the name it shows, at the same place.
 *
 * <p>Eclipse's lookup of a file by its location follows symbolic links, and
 * some of Eclipse opens files through it: the Search view, for one, opens a
 * match in the file it finds at the match's location, so a match in
 * {@code objs/main.c} opened {@code c/main}, which has no extension, so no C
 * or assembler features. An editor opened on such a name is closed, and one
 * opened on the other name, with the same selection.
 */
final class LinkRedirect implements IPartListener2, IWindowListener {
    static void install() {
        IWorkbench wb = PlatformUI.getWorkbench();
        LinkRedirect r = new LinkRedirect();
        wb.addWindowListener(r);
        for (IWorkbenchWindow w : wb.getWorkbenchWindows()) r.windowOpened(w);
    }

    @Override
    public void windowOpened(IWorkbenchWindow window) {
        window.getPartService().addPartListener(this);
    }

    @Override
    public void windowClosed(IWorkbenchWindow window) {
        window.getPartService().removePartListener(this);
    }

    @Override
    public void windowActivated(IWorkbenchWindow window) {
        // nothing
    }

    @Override
    public void windowDeactivated(IWorkbenchWindow window) {
        // nothing
    }

    @Override
    public void partOpened(IWorkbenchPartReference ref) {
        if (!(ref.getPart(false) instanceof IEditorPart ed)) return;
        IFile file = ed.getEditorInput().getAdapter(IFile.class);
        if (file == null || file.getLocation() == null || !MirroredFilesFilter.hidden(file)) return;
        Path other = MirroredFilesFilter.shownName(file.getLocation().toPath());
        IFile shown = other == null ? null : LinkUris.fileAt(other);
        if (shown == null) return;
        IWorkbenchPage page = ref.getPage();
        // After whoever opened it has finished with it (the Search view selects the match).
        page.getWorkbenchWindow().getShell().getDisplay().asyncExec(() -> {
            if (page.getWorkbenchWindow().getShell().isDisposed()) return;
            ITextSelection sel = ed instanceof ITextEditor te
                    && te.getSelectionProvider().getSelection() instanceof ITextSelection s ? s : null;
            boolean active = page.getActiveEditor() == ed;
            try {
                IEditorPart opened = IDE.openEditor(page, shown, active);
                if (sel != null && opened instanceof ITextEditor te) te.selectAndReveal(sel.getOffset(), sel.getLength());
                if (!ed.isDirty()) page.closeEditor(ed, false);
            } catch (PartInitException e) {
                // leave it open by the name it has
            }
        });
    }
}
