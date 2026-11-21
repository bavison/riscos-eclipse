package com.piccolosystems.riscos.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.lsp4j.Range;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

/** Shows {@code riscos/inactiveRegions} as annotations in every open editor on the file. */
final class InactiveRegions {
    static final String INACTIVE = "com.piccolosystems.riscos.inactive";
    static final String SKIPPED = "com.piccolosystems.riscos.skippedInEveryExpansion";

    /** Our current annotations, per annotation model. */
    private static final Map<IAnnotationModel, Annotation[]> current = new WeakHashMap<>();

    private InactiveRegions() {}

    static void show(RiscOSLanguageClient.InactiveRegionsParams p) {
        if (p == null || p.textDocument == null) return;
        Path file = pathOf(URI.create(p.textDocument.getUri()));
        if (file == null) return;
        Display.getDefault().asyncExec(() -> {
            if (!PlatformUI.isWorkbenchRunning()) return;
            for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows())
                for (IWorkbenchPage page : w.getPages())
                    for (IEditorReference r : page.getEditorReferences()) {
                        IEditorPart part = r.getEditor(false);
                        ITextEditor ed = part == null ? null : part.getAdapter(ITextEditor.class);
                        if (ed == null) continue;
                        IEditorInput in = ed.getEditorInput();
                        if (!(in instanceof IURIEditorInput) || !file.equals(pathOf(((IURIEditorInput) in).getURI()))) continue;
                        IDocumentProvider dp = ed.getDocumentProvider();
                        if (dp != null) apply(dp.getDocument(in), dp.getAnnotationModel(in), p);
                    }
        });
    }

    private static Path pathOf(URI u) {
        try {
            return "file".equals(u.getScheme()) ? Path.of(u).toAbsolutePath().normalize() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void apply(IDocument doc, IAnnotationModel model, RiscOSLanguageClient.InactiveRegionsParams p) {
        if (doc == null || model == null) return;
        Map<Annotation, Position> add = new HashMap<>();
        addAll(add, doc, p.regions, INACTIVE, "Not assembled in this configuration");
        addAll(add, doc, p.skippedInEveryExpansion, SKIPPED, "Skipped in every expansion of this macro");
        Annotation[] old = current.getOrDefault(model, new Annotation[0]);
        if (model instanceof IAnnotationModelExtension) {
            ((IAnnotationModelExtension) model).replaceAnnotations(old, add);
        } else {
            for (Annotation a : old) model.removeAnnotation(a);
            for (Map.Entry<Annotation, Position> e : add.entrySet()) model.addAnnotation(e.getKey(), e.getValue());
        }
        current.put(model, add.keySet().toArray(new Annotation[0]));
    }

    private static void addAll(Map<Annotation, Position> out, IDocument doc, List<Range> ranges, String type, String text) {
        if (ranges == null) return;
        for (Range r : ranges) {
            try {
                int first = r.getStart().getLine(), last = Math.min(r.getEnd().getLine(), doc.getNumberOfLines() - 1);
                if (first > last) continue;
                int start = doc.getLineOffset(first);
                int end = doc.getLineOffset(last) + doc.getLineLength(last);
                out.put(new Annotation(type, false, text), new Position(start, end - start));
            } catch (BadLocationException e) {
                // the document has changed since; the next notification will be right
            }
        }
    }
}
