package com.piccolosystems.riscos.lsp;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.menus.WorkbenchWindowControlContribution;

/**
 * The active configurations, on the main toolbar: for each imported tree, a
 * drop-down list of its configurations with the active one shown ("Tree: Env"
 * when there's more than one tree, since each has its own). Choosing one
 * switches to it, as RISC OS › Active Configuration does.
 *
 * <p>The lists follow the trees' settings, and the workspace's projects too:
 * a tree is listed only while its project exists, and an import saves the
 * tree before it makes the projects.
 */
public class ConfigurationCombo extends WorkbenchWindowControlContribution {
    public static final String TOOLTIP = "RISC OS: the active configuration";

    /** What a tree's list offers, in order. */
    public static List<String> labels(BuildTree t, boolean several) {
        List<String> out = new ArrayList<>();
        for (String env : t.configurations) out.add(several ? t.name + ": " + env : env);
        return out;
    }

    /** Switches a tree to a configuration, unless it is the active one already. */
    public static void choose(BuildTree t, String env) {
        if (!env.equals(t.active)) TreeImport.switchTo(t, env);
    }

    private Composite box;
    private final Runnable refresh = () -> {
        Composite b = box;
        if (b != null && !b.isDisposed()) b.getDisplay().asyncExec(this::fill);
    };
    private final IResourceChangeListener projects = e -> {
        IResourceDelta d = e.getDelta();
        if (d == null) return;
        for (IResourceDelta k : d.getAffectedChildren(IResourceDelta.ADDED | IResourceDelta.REMOVED))
            if (k.getResource() instanceof IProject) {
                refresh.run();
                return;
            }
    };

    @Override
    protected Control createControl(Composite parent) {
        box = new Composite(parent, SWT.NONE);
        RowLayout layout = new RowLayout(SWT.HORIZONTAL);
        layout.marginTop = layout.marginBottom = layout.marginLeft = layout.marginRight = 0;
        layout.center = true;
        box.setLayout(layout);
        box.addListener(SWT.Dispose, e -> {
            BuildTree.removeListener(refresh);
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(projects);
        });
        BuildTree.addListener(refresh);
        ResourcesPlugin.getWorkspace().addResourceChangeListener(projects, IResourceChangeEvent.POST_CHANGE);
        fill();
        // The toolbar wraps the control only once this returns.
        box.getDisplay().asyncExec(() -> {
            if (!box.isDisposed()) fitSlot();
        });
        return box;
    }

    private void fill() {
        if (box == null || box.isDisposed()) return;
        for (Control c : box.getChildren()) c.dispose();
        List<BuildTree> all = BuildTree.all();
        if (all.isEmpty()) {
            Combo none = new Combo(box, SWT.READ_ONLY | SWT.DROP_DOWN);
            none.setItems("No build tree");
            none.select(0);
            none.setEnabled(false);
            none.setToolTipText(TOOLTIP);
        }
        for (BuildTree t : all) {
            Combo c = new Combo(box, SWT.READ_ONLY | SWT.DROP_DOWN);
            c.setToolTipText(all.size() == 1 ? TOOLTIP : TOOLTIP + " of " + t.name);
            c.setItems(labels(t, all.size() > 1).toArray(String[]::new));
            c.select(t.configurations.indexOf(t.active));
            fitWidth(c);
            c.addListener(SWT.Selection, e -> {
                int i = c.getSelectionIndex();
                if (i >= 0 && i < t.configurations.size()) choose(t, t.configurations.get(i));
            });
        }
        box.layout(true);
        fitSlot();
    }

    /**
     * The toolbar sizes a control's slot once, when it makes it, around a
     * wrapper of its own: fit both to the lists as they are now.
     */
    private void fitSlot() {
        Control slot = box;
        while (slot.getParent() != null && !(slot.getParent() instanceof ToolBar)) slot = slot.getParent();
        if (!(slot.getParent() instanceof ToolBar bar)) {
            box.getParent().layout(true, true);
            return;
        }
        int width = slot.computeSize(SWT.DEFAULT, SWT.DEFAULT, true).x;
        for (ToolItem item : bar.getItems()) if (item.getControl() == slot) item.setWidth(width);
        slot.setSize(width, slot.getSize().y);
        for (Control c = box; c != slot; c = c.getParent()) c.setSize(width, c.getSize().y);
        if (slot instanceof Composite s) s.layout(true, true);
        bar.pack(true);
        bar.getParent().layout(true, true);
    }

    /** Room for an item's text beside it, for the arrow and the padding. */
    public static final int ARROW_AND_PADDING = 40;

    /**
     * Makes a list wide enough for its longest item. GTK asks for a read-only
     * one a character or so too narrow, cutting off the end of its text.
     */
    static void fitWidth(Combo c) {
        int text = 0;
        GC gc = new GC(c);
        try {
            for (String item : c.getItems()) text = Math.max(text, gc.textExtent(item).x);
        } finally {
            gc.dispose();
        }
        int width = Math.max(c.computeSize(SWT.DEFAULT, SWT.DEFAULT).x, text + ARROW_AND_PADDING);
        c.setLayoutData(new RowData(width, SWT.DEFAULT));
    }

    @Override
    public boolean isDynamic() {
        return true;
    }
}
