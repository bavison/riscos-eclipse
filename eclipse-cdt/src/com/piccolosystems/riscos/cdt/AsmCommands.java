package com.piccolosystems.riscos.cdt;

import java.net.URI;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4j.Location;
import org.eclipse.search.internal.ui.text.FileMatch;
import org.eclipse.search.internal.ui.text.FileSearchQuery;
import org.eclipse.search.internal.ui.text.FileSearchResult;
import org.eclipse.search.internal.ui.text.LineElement;
import org.eclipse.search.ui.NewSearchUI;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

import com.piccolosystems.riscos.lsp.RiscOSLanguageClient;

/** The C editor's commands for the assembler side. */
@SuppressWarnings("restriction")
public final class AsmCommands {
    private AsmCommands() {
    }

    /** The name at the C editor's caret and its file, or null. */
    record At(String name, URI file, Shell shell) {}

    static At at(ExecutionEvent event) {
        if (!(HandlerUtil.getActiveEditor(event) instanceof ITextEditor ed)) return null;
        IDocument doc = ed.getDocumentProvider().getDocument(ed.getEditorInput());
        if (!(ed.getSelectionProvider().getSelection() instanceof ITextSelection sel)) return null;
        IRegion w = AsmLinks.word(doc, sel.getOffset());
        URI file = AsmLinks.uriOf(ed.getEditorInput());
        if (w == null || file == null) return null;
        try {
            return new At(doc.get(w.getOffset(), w.getLength()), file, ed.getSite().getShell());
        } catch (BadLocationException e) {
            return null;
        }
    }

    /** Open Assembler Definition: where this component's or its libraries' assembler defines the name. */
    public static class OpenDefinition extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            At a = at(event);
            if (a == null) return null;
            Job.create("Finding the assembler definition of " + a.name, m -> {
                RiscOSLanguageClient.start(30_000);
                List<Location> locs = RiscOSLanguageClient.asmLocations(RiscOSLanguageClient.ASM_DEFINITIONS, a.name,
                        a.file, 60_000);
                Display.getDefault().asyncExec(() -> show(a, locs));
            }).schedule();
            return null;
        }

        private static void show(At a, List<Location> locs) {
            if (locs.isEmpty()) {
                MessageDialog.openInformation(a.shell, "Open Assembler Definition", "The assembler of this component, "
                        + "and of the libraries it links with, doesn't EXPORT " + a.name + ".");
            } else if (locs.size() == 1) {
                LSPEclipseUtils.openInEditor(locs.get(0));
            } else {
                ElementListSelectionDialog d = new ElementListSelectionDialog(a.shell, new LabelProvider() {
                    @Override
                    public String getText(Object o) {
                        return AsmLinks.shortName((Location) o);
                    }
                });
                d.setTitle("Open Assembler Definition");
                d.setMessage(a.name + " is defined in several places:");
                d.setElements(locs.toArray());
                if (d.open() == Window.OK && d.getFirstResult() instanceof Location l) LSPEclipseUtils.openInEditor(l);
            }
        }
    }

    /** Find References in Assembler: in the Search view, as a file search shows its results. */
    public static class FindReferences extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            At a = at(event);
            if (a != null) NewSearchUI.runQueryInBackground(new ReferencesQuery(a.name, a.file));
            return null;
        }
    }

    /** The assembler's uses and definitions of a name, for a C file's component. */
    public static class ReferencesQuery extends FileSearchQuery {
        private final String name;
        private final URI file;

        public ReferencesQuery(String name, URI file) {
            super(name, false, true, true, true, null);
            this.name = name;
            this.file = file;
        }

        @Override
        public IStatus run(IProgressMonitor monitor) throws OperationCanceledException {
            FileSearchResult result = (FileSearchResult) getSearchResult();
            result.removeAll();
            RiscOSLanguageClient.start(30_000);
            for (Location l : RiscOSLanguageClient.asmLocations(RiscOSLanguageClient.ASM_REFERENCES, name, file, 60_000)) {
                IResource r = LSPEclipseUtils.findResourceFor(l.getUri());
                if (!(r instanceof IFile f)) continue;
                IDocument doc = LSPEclipseUtils.getDocument(f);
                if (doc == null) continue;
                try {
                    int start = LSPEclipseUtils.toOffset(l.getRange().getStart(), doc);
                    int end = LSPEclipseUtils.toOffset(l.getRange().getEnd(), doc);
                    IRegion line = doc.getLineInformationOfOffset(start);
                    LineElement le = new LineElement(f, doc.getLineOfOffset(start) + 1, line.getOffset(),
                            doc.get(line.getOffset(), line.getLength()));
                    result.addMatch(new FileMatch(f, start, end - start, le));
                } catch (BadLocationException e) {
                    // the file has changed
                }
            }
            return Status.OK_STATUS;
        }

        @Override
        public String getLabel() {
            return "References to " + name + " in assembler";
        }

        @Override
        public String getResultLabel(int matches) {
            return "'" + name + "' - " + matches + (matches == 1 ? " reference" : " references") + " in assembler";
        }
    }
}
