package com.piccolosystems.riscos.lsp;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.internal.genericeditor.ExtensionBasedTextEditor;
import org.eclipse.ui.internal.genericeditor.ExtensionBasedTextViewerConfiguration;

/**
 * The Generic Code Editor, except for two things.
 *
 * <p>A file that can't be edited as it is (a Messages file with NULs,
 * tokenised BASIC) is opened converted, through {@link ConvertedFiles}.
 * Everything else (colouring, the language server, folding) works on the
 * converted text.
 *
 * <p>Its colouring follows the file's content type even when that comes from
 * the project's settings rather than its name, as it does for assembler files
 * without an extension ({@code Hdr:Macros}, {@code s/Wimp10}). The Generic
 * Code Editor chooses its colouring before it has the file's document, from
 * the file's name alone, so it would colour those as plain text; here the
 * content types are worked out once the document is there, before the
 * colouring is chosen.
 */
@SuppressWarnings("restriction")
public class RiscOSTextEditor extends ExtensionBasedTextEditor {
    public static final String ID = "com.piccolosystems.riscos.lsp.textEditor";

    /**
     * Its Ctrl+hover links: {@link RiscOSLinks}, labelled alike, rather than
     * LSP4E's; and links to URLs. Set again when the hyperlink preferences
     * change, as the editor would set the configuration's.
     */
    private void setLinks() {
        if (!(getSourceViewer() instanceof org.eclipse.jface.text.source.SourceViewer v)) return;
        var prefs = getPreferenceStore();
        if (prefs != null && !prefs.getBoolean(org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants.EDITOR_HYPERLINKS_ENABLED)) {
            v.setHyperlinkDetectors(null, 0);
            return;
        }
        RiscOSLinks links = new RiscOSLinks();
        links.setContext(this);
        v.setHyperlinkDetectors(new org.eclipse.jface.text.hyperlink.IHyperlinkDetector[] {links,
                new org.eclipse.jface.text.hyperlink.URLHyperlinkDetector()},
                getSourceViewerConfiguration().getHyperlinkStateMask(v));
    }

    @Override
    public void createPartControl(org.eclipse.swt.widgets.Composite parent) {
        super.createPartControl(parent);
        setLinks();
    }

    @Override
    protected void handlePreferenceStoreChanged(org.eclipse.jface.util.PropertyChangeEvent event) {
        super.handlePreferenceStoreChanged(event);
        String p = event.getProperty();
        if (p != null && p.startsWith("hyperlink")) setLinks();
    }

    /**
     * Before saving, removes spaces and tabs at the ends of lines, if the
     * preference for the file's language says to: in every line, or only in
     * those edited since the file was last saved (as CDT's save action can).
     * One step to undo.
     */
    @Override
    protected void performSave(boolean overwrite, org.eclipse.core.runtime.IProgressMonitor monitor) {
        try {
            stripTrailingSpace();
        } catch (RuntimeException e) {
            org.eclipse.core.runtime.Platform.getLog(RiscOSTextEditor.class).warn("Couldn't remove trailing spaces: " + e);
        }
        super.performSave(overwrite, monitor);
        if (!isDirty()) forgetEdits();
    }

    // ---- the places edited since the file was last saved, kept up to date as text moves

    private static final String EDITED = "com.piccolosystems.riscos.edited";
    private org.eclipse.jface.text.IDocument tracked;
    private final org.eclipse.jface.text.IDocumentListener editListener = new org.eclipse.jface.text.IDocumentListener() {
        @Override
        public void documentAboutToBeChanged(org.eclipse.jface.text.DocumentEvent e) {
            // nothing
        }

        @Override
        public void documentChanged(org.eclipse.jface.text.DocumentEvent e) {
            try {
                e.getDocument().addPosition(EDITED,
                        new org.eclipse.jface.text.Position(e.getOffset(), e.getText() == null ? 0 : e.getText().length()));
            } catch (org.eclipse.jface.text.BadLocationException | org.eclipse.jface.text.BadPositionCategoryException x) {
                // not tracked
            }
        }
    };

    private void track(org.eclipse.jface.text.IDocument doc) {
        if (doc == tracked) return;
        if (tracked != null) {
            tracked.removeDocumentListener(editListener);
            try {
                tracked.removePositionCategory(EDITED);
            } catch (org.eclipse.jface.text.BadPositionCategoryException e) {
                // gone already
            }
        }
        tracked = doc;
        if (doc == null) return;
        doc.addPositionCategory(EDITED);
        doc.addPositionUpdater(new org.eclipse.jface.text.DefaultPositionUpdater(EDITED));
        doc.addDocumentListener(editListener);
    }

    private void forgetEdits() {
        if (tracked == null) return;
        try {
            for (var p : tracked.getPositions(EDITED)) tracked.removePosition(EDITED, p);
        } catch (org.eclipse.jface.text.BadPositionCategoryException e) {
            // nothing tracked
        }
    }

    /** The lines edited since the file was last saved. */
    private java.util.BitSet changedLines(org.eclipse.jface.text.IDocument doc) {
        java.util.BitSet out = new java.util.BitSet();
        if (doc != tracked) return out;
        try {
            for (var p : doc.getPositions(EDITED)) {
                int from = Math.min(p.getOffset(), doc.getLength()), to = Math.min(p.getOffset() + p.getLength(), doc.getLength());
                // Text inserted up to a newline doesn't change the line after it.
                out.set(doc.getLineOfOffset(from), doc.getLineOfOffset(to > from ? to - 1 : to) + 1);
            }
        } catch (org.eclipse.jface.text.BadLocationException | org.eclipse.jface.text.BadPositionCategoryException e) {
            // as far as it got
        }
        return out;
    }

    private void stripTrailingSpace() {
        String mode = Preferences.store().getString(switch (language()) {
            case "basic" -> Preferences.STRIP_BASIC;
            case "asm" -> Preferences.STRIP_ASM;
            default -> "";
        });
        if (mode.isEmpty() || Preferences.STRIP_OFF.equals(mode)) return;
        var doc = getDocumentProvider().getDocument(getEditorInput());
        if (doc == null) return;
        java.util.BitSet changed = Preferences.STRIP_EDITED.equals(mode) ? changedLines(doc) : null;
        var target = getAdapter(org.eclipse.jface.text.IRewriteTarget.class);
        boolean compound = false;
        try {
            for (int line = doc.getNumberOfLines() - 1; line >= 0; line--) {
                if (changed != null && !changed.get(line)) continue;
                var r = doc.getLineInformation(line);
                int end = r.getOffset() + r.getLength(), start = end;
                while (start > r.getOffset() && (doc.getChar(start - 1) == ' ' || doc.getChar(start - 1) == '\t')) start--;
                if (start == end) continue;
                if (!compound && target != null) {
                    target.beginCompoundChange();
                    compound = true;
                }
                doc.replace(start, end - start, "");
            }
        } catch (org.eclipse.jface.text.BadLocationException e) {
            // the document changed under us: leave the rest
        } finally {
            if (compound) target.endCompoundChange();
        }
    }

    /** "asm", "basic", or "" for the file being edited, by its content type. */
    String language() {
        IEditorInput in = getEditorInput();
        org.eclipse.core.runtime.content.IContentType type = null;
        try {
            if (in instanceof org.eclipse.ui.IFileEditorInput fi) {
                var d = fi.getFile().getContentDescription();
                if (d != null) type = d.getContentType();
            }
            if (type == null) {
                java.nio.file.Path p = ConvertedFiles.pathOf(in);
                if (p != null) type = org.eclipse.core.runtime.Platform.getContentTypeManager()
                        .findContentTypeFor(p.getFileName().toString());
            }
        } catch (CoreException | RuntimeException e) {
            return "";
        }
        var m = org.eclipse.core.runtime.Platform.getContentTypeManager();
        if (type == null) return "";
        var basic = m.getContentType("com.piccolosystems.riscos.bbcbasic");
        var asm = m.getContentType("com.piccolosystems.riscos.armasm");
        if (basic != null && type.isKindOf(basic)) return "basic";
        if (asm != null && type.isKindOf(asm)) return "asm";
        return "";
    }

    @Override
    public void dispose() {
        track(null);
        super.dispose();
    }

    @Override
    protected void doSetInput(IEditorInput input) throws CoreException {
        IEditorInput editable = ConvertedFiles.editable(input);
        super.doSetInput(editable);
        track(getDocumentProvider() == null ? null : getDocumentProvider().getDocument(editable));
        if (getSourceViewerConfiguration() instanceof ExtensionBasedTextViewerConfiguration c && getSourceViewer() == null)
            c.getContentTypes(getDocumentProvider().getDocument(editable));      // remembered from now on
    }
}
