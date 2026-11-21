package com.piccolosystems.riscos.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.hyperlink.AbstractHyperlinkDetector;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServers;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Ctrl+hover in the RISC OS Text Editor: <i>Open Declaration</i>, <i>Open
 * Assignment</i> for a variable (assembler or BASIC), and <i>Open
 * Definition</i>,
 * all labelled alike, as {@code name:line - project/folder}.
 *
 * <p>It takes the place of LSP4E's links in that editor (see
 * {@link RiscOSTextEditor}). LSP4E labels a link by the file's project and
 * folder only when the file is in one project; in a build tree each file is
 * in its component's project and the tree's, so it gave the whole path
 * instead. An assignment has no kind of link in LSP, so those come from the
 * language server's {@code riscos.assignments} command.
 */
public class RiscOSLinks extends AbstractHyperlinkDetector {
    /** How long a Ctrl+hover waits for the server. */
    private static final long WAIT = 1000;

    @Override
    public IHyperlink[] detectHyperlinks(ITextViewer viewer, IRegion region, boolean canShowMultiple) {
        IDocument doc = viewer.getDocument();
        if (doc == null) return null;
        URI uri = LSPEclipseUtils.toUri(doc);
        IRegion word = word(doc, region.getOffset());
        if (uri == null || word == null) return null;
        org.eclipse.lsp4j.Position pos;
        try {
            pos = LSPEclipseUtils.toPosition(region.getOffset(), doc);
        } catch (BadLocationException e) {
            return null;
        }
        var id = new org.eclipse.lsp4j.TextDocumentIdentifier(uri.toString());
        CompletableFuture<List<Location>> definitions = LanguageServers.forDocument(doc)
                .computeFirst(ls -> ls.getTextDocumentService().definition(new org.eclipse.lsp4j.DefinitionParams(id, pos)))
                .thenApply(o -> o.map(RiscOSLinks::locations).orElse(List.of()));
        CompletableFuture<List<Location>> declarations = LanguageServers.forDocument(doc)
                .computeFirst(ls -> ls.getTextDocumentService().declaration(new org.eclipse.lsp4j.DeclarationParams(id, pos)))
                .thenApply(o -> o.map(RiscOSLinks::locations).orElse(List.of()));
        Map<String, Location> seen = new LinkedHashMap<>();
        Map<String, String> kinds = new LinkedHashMap<>();
        // Declarations, then assignments, then definitions; a place given twice keeps its first kind
        // (a BASIC variable that is never declared is defined by its assignments).
        add(seen, kinds, "Open Declaration", get(declarations));
        // Asked of the server this document is connected to.
        CompletableFuture<List<Location>> assignments = LanguageServers.forDocument(doc)
                .computeFirst(ls -> ls.getWorkspaceService().executeCommand(new org.eclipse.lsp4j.ExecuteCommandParams(
                        RiscOSLanguageClient.ASSIGNMENTS, List.of(uri.toString(), pos.getLine(), pos.getCharacter()))))
                .thenApply(o -> o.map(RiscOSLinks::fromJson).orElse(List.of()));
        add(seen, kinds, "Open Assignment", get(assignments));
        add(seen, kinds, "Open Definition", get(definitions));
        if (seen.isEmpty()) return null;
        List<IHyperlink> out = new ArrayList<>();
        for (Map.Entry<String, Location> e : seen.entrySet()) {
            Location l = e.getValue();
            String text = kinds.get(e.getKey()) + " - " + label(l);
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
                    return text;
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

    /** A command's result: Locations, as JSON. */
    private static List<Location> fromJson(Object r) {
        if (r == null) return List.of();
        com.google.gson.Gson gson = new com.google.gson.Gson();
        com.google.gson.JsonElement json = r instanceof com.google.gson.JsonElement j ? j : gson.toJsonTree(r);
        try {
            return List.of(gson.fromJson(json, Location[].class));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static List<Location> get(CompletableFuture<List<Location>> f) {
        try {
            return f.get(WAIT, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<Location> locations(Either<List<? extends Location>, List<? extends LocationLink>> e) {
        List<Location> out = new ArrayList<>();
        if (e == null) return out;
        if (e.isLeft()) out.addAll(e.getLeft());
        else for (LocationLink l : e.getRight()) out.add(new Location(l.getTargetUri(), l.getTargetSelectionRange()));
        return out;
    }

    private static void add(Map<String, Location> seen, Map<String, String> kinds, String kind, List<Location> locs) {
        for (Location l : locs) {
            String key = l.getUri() + "#" + l.getRange().getStart().getLine() + ":" + l.getRange().getStart().getCharacter();
            if (seen.putIfAbsent(key, l) == null) kinds.put(key, kind);
        }
    }

    /**
     * {@code name:line - project/folder}: the file as named (a link farm's
     * name, if that's how it's named), in the innermost project holding it.
     */
    static String label(Location l) {
        int line = l.getRange().getStart().getLine() + 1;
        Path path = pathOf(l.getUri());
        if (path == null) return l.getUri() + ":" + line;
        IFile f = LinkUris.fileAt(path);
        String name = path.getFileName() + ":" + line;
        if (f == null) return name + " - " + path.getParent();
        IPath folder = f.getParent().getProjectRelativePath();
        return name + " - " + f.getProject().getName() + (folder.isEmpty() ? "" : "/" + folder);
    }

    private static Path pathOf(String uri) {
        try {
            URI u = new URI(uri);
            if ("file".equals(u.getScheme())) return Path.of(u);
            if (u.getPath() != null) return ConvertedFiles.pathOf(u);
        } catch (Exception e) {
            // not a path
        }
        return null;
    }

    /** The name at an offset (assembler, BASIC with its $ and %), or null. */
    static IRegion word(IDocument doc, int offset) {
        try {
            int s = offset, e = offset, n = doc.getLength();
            while (s > 0 && ident(doc.getChar(s - 1))) s--;
            while (e < n && ident(doc.getChar(e))) e++;
            if (e < n && (doc.getChar(e) == '$' || doc.getChar(e) == '%')) e++;
            if (e == s) return null;
            return new Region(s, e - s);
        } catch (BadLocationException x) {
            return null;
        }
    }

    private static boolean ident(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
