package com.piccolosystems.riscos.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.actions.CompoundContributionItem;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;

/** The RISC OS menu. */
public final class Commands {
    private Commands() {
    }

    /** Import Build Tree…: the import wizard. */
    public static class Import extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            Shell shell = HandlerUtil.getActiveShell(event);
            new WizardDialog(shell, new ImportWizard()).open();
            return null;
        }
    }

    /** Rescan Build Tree: buildscan again, after a makefile, Components file or Env file has changed. */
    public static class Rescan extends AbstractHandler {
        @Override
        public boolean isEnabled() {
            return !BuildTree.all().isEmpty();
        }

        @Override
        public Object execute(ExecutionEvent event) {
            BuildTree t = tree(event, "Rescan which build tree?");
            if (t != null) TreeImport.schedule("Rescanning " + t.name, m -> TreeImport.importTree(t, m));
            return null;
        }
    }

    /** Add External Project…: a directory outside the tree, built against its exports. */
    public static class AddExternal extends AbstractHandler {
        @Override
        public boolean isEnabled() {
            return !BuildTree.all().isEmpty();
        }

        @Override
        public Object execute(ExecutionEvent event) {
            BuildTree t = tree(event, "Add the external project to which build tree?");
            if (t == null) return null;
            Map.Entry<Path, String> x = ImportWizard.askForExternal(HandlerUtil.getActiveShell(event));
            if (x != null)
                TreeImport.schedule("Adding " + x.getValue(), m -> TreeImport.addExternal(t, x.getKey(), x.getValue(), m));
            return null;
        }
    }

    /**
     * The tree a command is for: the only one; or the one holding the file
     * being edited, or what is selected; or else the one the user picks.
     */
    static BuildTree tree(ExecutionEvent event, String question) {
        List<BuildTree> all = BuildTree.all();
        if (all.size() <= 1) return all.isEmpty() ? null : all.get(0);
        IResource r = null;
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        if (part instanceof IEditorPart ed) {
            r = ed.getEditorInput().getAdapter(IResource.class);
        } else if (HandlerUtil.getCurrentSelection(event) instanceof IStructuredSelection sel
                && sel.getFirstElement() instanceof IAdaptable a) {
            r = a.getAdapter(IResource.class);
        }
        BuildTree t = BuildTree.of(r);
        if (t != null) return t;
        ElementListSelectionDialog d = new ElementListSelectionDialog(HandlerUtil.getActiveShell(event),
                new LabelProvider() {
                    @Override
                    public String getText(Object o) {
                        return o instanceof BuildTree b ? b.name + "  (" + b.riscos + ")" : String.valueOf(o);
                    }
                });
        d.setTitle("Build Tree");
        d.setMessage(question);
        d.setElements(all.toArray());
        d.setMultipleSelection(false);
        return d.open() == Window.OK && d.getFirstResult() instanceof BuildTree b ? b : null;
    }

    /** Insert NUL: a ␀, which a converted file saves as a NUL byte. */
    public static class InsertNul extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            if (!(HandlerUtil.getActiveEditor(event) instanceof org.eclipse.ui.texteditor.ITextEditor ed)) return null;
            if (!ConvertedFiles.isConverted(ed.getEditorInput())) {
                ed.getSite().getShell().getDisplay().beep();      // the file's text can't hold a NUL
                return null;
            }
            var doc = ed.getDocumentProvider().getDocument(ed.getEditorInput());
            var sel = (org.eclipse.jface.text.ITextSelection) ed.getSelectionProvider().getSelection();
            try {
                doc.replace(sel.getOffset(), sel.getLength(), String.valueOf(EditableNul.SHOWN));
                ed.selectAndReveal(sel.getOffset() + 1, 0);
            } catch (org.eclipse.jface.text.BadLocationException e) {
                // the selection has gone
            }
            return null;
        }
    }

    /** What a NUL looks like when edited. */
    static final class EditableNul {
        static final char SHOWN = com.piccolosystems.riscos.asmindex.text.EditableText.NUL_SHOWN;
    }

    /**
     * Active Configuration ›: one item per configuration, the active one
     * ticked; with several trees, a submenu for each.
     */
    public static class Configurations extends CompoundContributionItem {
        @Override
        protected IContributionItem[] getContributionItems() {
            List<BuildTree> all = BuildTree.all();
            List<IContributionItem> items = new ArrayList<>();
            if (all.isEmpty()) {
                Action none = new Action("No build tree imported") {
                };
                none.setEnabled(false);
                items.add(new ActionContributionItem(none));
            } else if (all.size() == 1) {
                items.addAll(configurations(all.get(0)));
            } else {
                for (BuildTree t : all) {
                    MenuManager m = new MenuManager(t.name);
                    for (IContributionItem i : configurations(t)) m.add(i);
                    items.add(m);
                }
            }
            return items.toArray(IContributionItem[]::new);
        }

        private static List<IContributionItem> configurations(BuildTree t) {
            List<IContributionItem> items = new ArrayList<>();
            for (String env : t.configurations) {
                Action a = new Action(env, IAction.AS_RADIO_BUTTON) {
                    @Override
                    public void run() {
                        if (isChecked() && !env.equals(t.active)) TreeImport.switchTo(t, env);
                    }
                };
                a.setChecked(env.equals(t.active));
                items.add(new ActionContributionItem(a));
            }
            return items;
        }
    }
}
