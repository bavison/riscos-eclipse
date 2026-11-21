package com.piccolosystems.riscos.cdt;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.hyperlink.AbstractHyperlinkDetector;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4j.Location;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;

import com.piccolosystems.riscos.lsp.RiscOSLanguageClient;

/**
 * Ctrl+click in a C editor: <i>Open Assembler Definition</i>, for a name the
 * assembler of the file's component, or of a library it links with, defines
 * and EXPORTs. CDT's own links (to the C declaration) stay as they are.
 */
public class AsmLinks extends AbstractHyperlinkDetector {
    /** How long a Ctrl+hover waits for the server: it only asks a server already running. */
    private static final long WAIT = 800;

    @Override
    public IHyperlink[] detectHyperlinks(ITextViewer viewer, IRegion region, boolean canShowMultiple) {
        ITextEditor ed = getAdapter(ITextEditor.class);
        if (ed == null || !RiscOSLanguageClient.isRunning()) return null;
        URI file = uriOf(ed.getEditorInput());
        IRegion word = word(viewer.getDocument(), region.getOffset());
        if (file == null || word == null) return null;
        String name;
        try {
            name = viewer.getDocument().get(word.getOffset(), word.getLength());
        } catch (BadLocationException e) {
            return null;
        }
        List<Location> locs = RiscOSLanguageClient.asmLocations(RiscOSLanguageClient.ASM_DEFINITIONS, name, file, WAIT);
        if (locs.isEmpty()) return null;
        List<IHyperlink> out = new ArrayList<>();
        for (Location l : locs) {
            String where = locs.size() == 1 ? "" : " (" + shortName(l) + ")";
            out.add(new IHyperlink() {
                @Override
                public IRegion getHyperlinkRegion() {
                    return word;
                }

                @Override
                public String getTypeLabel() {
                    return null;
                }

                @Override
                public String getHyperlinkText() {
                    return "Open Assembler Definition" + where;
                }

                @Override
                public void open() {
                    LSPEclipseUtils.openInEditor(l);
                }
            });
            if (!canShowMultiple) break;
        }
        return out.toArray(IHyperlink[]::new);
    }

    /** A location as {@code file:line}. */
    static String shortName(Location l) {
        String u = l.getUri();
        return u.substring(u.lastIndexOf('/') + 1) + ":" + (l.getRange().getStart().getLine() + 1);
    }

    /** The URI of the file an editor shows. */
    static URI uriOf(IEditorInput input) {
        if (input instanceof IFileEditorInput fi) {
            IFile f = fi.getFile();
            return f.getLocationURI();
        }
        if (input instanceof IURIEditorInput ui) return ui.getURI();
        return null;
    }

    /** The C identifier at an offset, or null. */
    static IRegion word(IDocument doc, int offset) {
        try {
            int s = offset, e = offset, n = doc.getLength();
            while (s > 0 && ident(doc.getChar(s - 1))) s--;
            while (e < n && ident(doc.getChar(e))) e++;
            if (e == s || Character.isDigit(doc.getChar(s))) return null;
            return new Region(s, e - s);
        } catch (BadLocationException x) {
            return null;
        }
    }

    private static boolean ident(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
