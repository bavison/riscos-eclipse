package com.piccolosystems.riscos.lsp;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IImportWizard;
import org.eclipse.ui.IWorkbench;

import com.piccolosystems.riscos.asmindex.buildscan.BuildScan;

/**
 * File › Import… › RISC OS › Build Tree: the tree, its configurations (Env
 * files), its components and any external projects. Finishing starts a
 * background job that scans the makefiles and creates the projects.
 */
public class ImportWizard extends Wizard implements IImportWizard {
    private final TreePage treePage = new TreePage();
    private final ConfigurationsPage configurationsPage = new ConfigurationsPage();
    private final ComponentsPage componentsPage = new ComponentsPage();
    private final ExternalsPage externalsPage = new ExternalsPage();

    public ImportWizard() {
        setWindowTitle("Import RISC OS Build Tree");
        setNeedsProgressMonitor(true);
    }

    @Override
    public void init(IWorkbench workbench, IStructuredSelection selection) {
        // nothing
    }

    @Override
    public void addPages() {
        addPage(treePage);
        addPage(configurationsPage);
        addPage(componentsPage);
        addPage(externalsPage);
    }

    @Override
    public boolean canFinish() {
        return treePage.isPageComplete() && configurationsPage.isPageComplete() && configurationsPage.loaded;
    }

    @Override
    public boolean performFinish() {
        BuildTree t = new BuildTree();
        t.riscos = treePage.riscos;
        t.name = treePage.name();
        t.configurations.addAll(configurationsPage.ticked());
        t.active = configurationsPage.active();
        if (componentsPage.loadedFor != null && !componentsPage.allTicked())
            t.components.addAll(componentsPage.ticked());
        t.externals.putAll(externalsPage.externals);
        TreeImport.schedule("Importing " + t.name, m -> TreeImport.importTree(t, m));
        return true;
    }

    /** The project name for a tree: the directory holding {@code RiscOS}, as trees are usually named. */
    public static String defaultName(Path riscos) {
        Path parent = riscos.getParent();
        if (riscos.getFileName().toString().equalsIgnoreCase("RiscOS") && parent != null && parent.getFileName() != null)
            return parent.getFileName().toString();
        return riscos.getFileName().toString();
    }

    /**
     * The name to suggest for a tree: the one it was imported as before, or
     * else {@link #defaultName}, numbered if another project has that name.
     */
    public static String suggestedName(Path riscos) {
        BuildTree before = BuildTree.at(riscos);
        if (before != null) return before.name;
        String n = defaultName(riscos);
        var root = ResourcesPlugin.getWorkspace().getRoot();
        String s = n;
        for (int i = 2; root.getProject(s).exists() && !riscos.equals(locationOf(root.getProject(s))); i++)
            s = n + " (" + i + ")";
        return s;
    }

    private static Path locationOf(IProject p) {
        IPath l = p.getLocation();
        return l == null ? null : l.toPath();
    }

    /**
     * Asks for an external project: a directory and the component name to build
     * it as. Returns null if cancelled.
     */
    static Map.Entry<Path, String> askForExternal(Shell shell) {
        DirectoryDialog dd = new DirectoryDialog(shell);
        dd.setText("External Project");
        dd.setMessage("Choose a directory outside the tree whose makefile builds against the tree's exports.");
        String dir = dd.open();
        if (dir == null) return null;
        Path p = Path.of(dir).toAbsolutePath().normalize();
        InputDialog id = new InputDialog(shell, "External Project",
                "The component name to build it as (make's COMPONENT variable):", p.getFileName().toString(),
                s -> s.isBlank() ? "Enter a name." : s.chars().anyMatch(Character::isWhitespace) ? "No spaces." : null);
        if (id.open() != Window.OK) return null;
        return Map.entry(p, id.getValue().strip());
    }

    // ------------------------------------------------------------------ pages

    /** The tree's RiscOS directory, and the tree's project name. */
    private class TreePage extends WizardPage {
        Text dir, name;
        Path riscos;
        private boolean nameEdited, settingName;

        TreePage() {
            super("tree");
            setTitle("Build Tree");
            setDescription("Choose the RiscOS directory of a build tree, such as one from ROOL's GitLab.");
            setPageComplete(false);
        }

        @Override
        public void createControl(Composite parent) {
            Composite c = new Composite(parent, SWT.NONE);
            c.setLayout(new GridLayout(3, false));
            new Label(c, SWT.NONE).setText("RiscOS directory:");
            dir = new Text(c, SWT.BORDER);
            dir.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            Button browse = new Button(c, SWT.PUSH);
            browse.setText("Browse…");
            browse.addListener(SWT.Selection, e -> {
                DirectoryDialog d = new DirectoryDialog(getShell());
                d.setMessage("Choose the tree's RiscOS directory (the one holding Env, BuildSys and Sources).");
                if (!dir.getText().isBlank()) d.setFilterPath(dir.getText());
                String s = d.open();
                if (s != null) dir.setText(s);
            });
            new Label(c, SWT.NONE).setText("Project name:");
            name = new Text(c, SWT.BORDER);
            name.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
            Label note = new Label(c, SWT.WRAP);
            note.setText("The tree becomes a project of this name, and each component's directory a project "
                    + "inside it, named after the directory (Wimp for WindowManager). Eclipse's files for them are "
                    + "kept out of git status.");
            GridData nd = new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1);
            nd.widthHint = 400;
            note.setLayoutData(nd);
            dir.addModifyListener(e -> validate());
            name.addModifyListener(e -> {
                if (settingName) return;      // validate() is filling in the default
                nameEdited = true;
                validate();
            });
            setControl(c);
        }

        String name() {
            return name.getText().strip();
        }

        private void validate() {
            riscos = null;
            setPageComplete(false);
            String s = dir.getText().strip();
            if (s.isEmpty()) {
                setErrorMessage(null);
                return;
            }
            Path p = Path.of(s).toAbsolutePath().normalize();
            if (!Files.isDirectory(p.resolve("Env")) && Files.isDirectory(p.resolve("RiscOS").resolve("Env")))
                p = p.resolve("RiscOS");
            if (!Files.isDirectory(p.resolve("Env")) || !Files.isRegularFile(p.resolve("BuildSys/ModuleDB"))) {
                setErrorMessage("That isn't a tree's RiscOS directory: it has no Env directory or no BuildSys/ModuleDB.");
                return;
            }
            try {
                if (p.toRealPath().toString().chars().anyMatch(Character::isWhitespace)) {
                    setErrorMessage("The tree's path has a space in it, which make can't cope with. Move or unpack "
                            + "the tree somewhere without spaces in its path.");
                    return;
                }
            } catch (IOException e) {
                setErrorMessage(e.toString());
                return;
            }
            riscos = p;
            String def = suggestedName(p);
            if (!nameEdited && !name.getText().equals(def)) {
                settingName = true;
                try {
                    name.setText(def);
                } finally {
                    settingName = false;
                }
            }
            IStatus ok = ResourcesPlugin.getWorkspace().validateName(name(), IResource.PROJECT);
            if (!ok.isOK()) {
                setErrorMessage(ok.getMessage());
                return;
            }
            IProject existing = ResourcesPlugin.getWorkspace().getRoot().getProject(name());
            IPath loc = existing.exists() ? existing.getLocation() : null;
            if (existing.exists() && (loc == null || !loc.toPath().equals(p))) {
                setErrorMessage("There is already a project called " + name() + " somewhere else.");
                return;
            }
            setErrorMessage(null);
            setPageComplete(true);
        }
    }

    /** Which Env files become configurations, and which is active. */
    private class ConfigurationsPage extends WizardPage {
        CheckboxTableViewer list;
        Combo active;
        Path loadedFor;
        boolean loaded;

        ConfigurationsPage() {
            super("configurations");
            setTitle("Configurations");
            setDescription("Tick the Env files to index the tree for. Each is scanned on import, so a few are "
                    + "quicker than all of them.");
            setPageComplete(false);
        }

        @Override
        public void createControl(Composite parent) {
            Composite c = new Composite(parent, SWT.NONE);
            c.setLayout(new GridLayout(2, false));
            list = CheckboxTableViewer.newCheckList(c, SWT.BORDER | SWT.V_SCROLL);
            list.setContentProvider(ArrayContentProvider.getInstance());
            GridData ld = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
            ld.heightHint = 250;
            list.getTable().setLayoutData(ld);
            list.addCheckStateListener(e -> update());
            new Label(c, SWT.NONE).setText("Active configuration:");
            active = new Combo(c, SWT.READ_ONLY);
            active.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            Label note = new Label(c, SWT.WRAP);
            note.setText("The active configuration decides which code is shaded as not assembled. "
                    + "Change it later from the RISC OS menu.");
            GridData nd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
            nd.widthHint = 400;
            note.setLayoutData(nd);
            setControl(c);
        }

        @Override
        public void setVisible(boolean visible) {
            if (visible && treePage.riscos != null && !treePage.riscos.equals(loadedFor)) load();
            super.setVisible(visible);
        }

        private void load() {
            Path riscos = treePage.riscos;
            List<String> envs;
            try {
                envs = BuildScan.envFiles(riscos);
            } catch (IOException e) {
                setErrorMessage("Couldn't read the Env directory: " + e.getMessage());
                return;
            }
            loadedFor = riscos;
            loaded = true;
            list.setInput(envs);
            // A tree is usually named after its main configuration (BCM2835 → ROOL/BCM2835).
            String tree = treePage.name();
            for (String e : envs) {
                String last = e.substring(e.lastIndexOf('/') + 1);
                if (last.equalsIgnoreCase(tree)) {
                    list.setChecked(e, true);
                    break;
                }
            }
            if (envs.isEmpty()) setErrorMessage("There are no Env files that set BUILD in " + riscos.resolve("Env"));
            update();
        }

        List<String> ticked() {
            List<String> out = new ArrayList<>();
            if (list != null) for (Object o : list.getCheckedElements()) out.add((String) o);
            return out;
        }

        String active() {
            return active.getText();
        }

        private void update() {
            List<String> t = ticked();
            String was = active.getText();
            active.setItems(t.toArray(String[]::new));
            if (t.contains(was)) active.setText(was);
            else if (!t.isEmpty()) active.select(0);
            setPageComplete(!t.isEmpty());
            getContainer().updateButtons();
        }
    }

    /** Which components become projects. */
    private class ComponentsPage extends WizardPage {
        CheckboxTableViewer list;
        String loadedFor;
        private Path riscos;

        ComponentsPage() {
            super("components");
            setTitle("Components");
            setDescription("The components with code in the chosen configurations. Each one ticked becomes a project.");
        }

        @Override
        public void createControl(Composite parent) {
            Composite c = new Composite(parent, SWT.NONE);
            c.setLayout(new GridLayout(2, false));
            list = CheckboxTableViewer.newCheckList(c, SWT.BORDER | SWT.V_SCROLL);
            list.setContentProvider(ArrayContentProvider.getInstance());
            list.setLabelProvider(new LabelProvider() {
                @Override
                public String getText(Object o) {
                    Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                    Path dir = (Path) e.getValue();
                    return e.getKey() + "    " + (riscos != null && dir.startsWith(riscos) ? riscos.relativize(dir) : dir);
                }
            });
            GridData ld = new GridData(SWT.FILL, SWT.FILL, true, true, 1, 2);
            ld.heightHint = 300;
            list.getTable().setLayoutData(ld);
            Button all = new Button(c, SWT.PUSH);
            all.setText("Select All");
            all.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
            all.addListener(SWT.Selection, e -> list.setAllChecked(true));
            Button none = new Button(c, SWT.PUSH);
            none.setText("Deselect All");
            none.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
            none.addListener(SWT.Selection, e -> list.setAllChecked(false));
            setControl(c);
        }

        @Override
        public void setVisible(boolean visible) {
            super.setVisible(visible);
            String key = treePage.riscos + "|" + configurationsPage.ticked();
            if (visible && !key.equals(loadedFor)) getShell().getDisplay().asyncExec(() -> load(key));
        }

        private void load(String key) {
            riscos = treePage.riscos;
            List<String> envs = configurationsPage.ticked();
            Map<String, Path> found = new LinkedHashMap<>();
            List<String> problems = new ArrayList<>();
            try {
                getContainer().run(true, true, monitor -> {
                    SubMonitor sub = SubMonitor.convert(monitor, "Reading the Components files", envs.size());
                    for (String env : envs) {
                        if (sub.isCanceled()) throw new InterruptedException();
                        sub.subTask(env);
                        try (BuildScan s = new BuildScan(riscos, env)) {
                            for (BuildScan.Component c : s.components(problems)) found.putIfAbsent(c.name(), c.dir());
                        } catch (IOException e) {
                            throw new InvocationTargetException(e);
                        }
                        sub.worked(1);
                    }
                });
            } catch (InvocationTargetException e) {
                setErrorMessage("Couldn't read the Components files: " + e.getCause().getMessage());
                return;
            } catch (InterruptedException e) {
                return;
            }
            setErrorMessage(null);
            List<Map.Entry<String, Path>> items = new ArrayList<>(found.entrySet());
            list.setInput(items);
            list.setAllChecked(true);
            loadedFor = key;
        }

        boolean allTicked() {
            return list.getCheckedElements().length == list.getTable().getItemCount();
        }

        List<String> ticked() {
            List<String> out = new ArrayList<>();
            for (Object o : list.getCheckedElements()) out.add((String) ((Map.Entry<?, ?>) o).getKey());
            return out;
        }
    }

    /** Directories outside the tree that build against its exports. */
    private class ExternalsPage extends WizardPage {
        final Map<Path, String> externals = new LinkedHashMap<>();
        org.eclipse.swt.widgets.List list;

        ExternalsPage() {
            super("externals");
            setTitle("External Projects");
            setDescription("Optional: directories outside the tree that build against its exports. "
                    + "You can also add them later from the RISC OS menu.");
        }

        @Override
        public void createControl(Composite parent) {
            Composite c = new Composite(parent, SWT.NONE);
            c.setLayout(new GridLayout(2, false));
            list = new org.eclipse.swt.widgets.List(c, SWT.BORDER | SWT.V_SCROLL | SWT.SINGLE);
            GridData ld = new GridData(SWT.FILL, SWT.FILL, true, true, 1, 2);
            ld.heightHint = 200;
            list.setLayoutData(ld);
            Button add = new Button(c, SWT.PUSH);
            add.setText("Add…");
            add.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
            add.addListener(SWT.Selection, e -> {
                Map.Entry<Path, String> x = askForExternal(getShell());
                if (x != null) {
                    externals.put(x.getKey(), x.getValue());
                    refresh();
                }
            });
            Button remove = new Button(c, SWT.PUSH);
            remove.setText("Remove");
            remove.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
            remove.addListener(SWT.Selection, e -> {
                int i = list.getSelectionIndex();
                if (i < 0) return;
                externals.remove(new ArrayList<>(externals.keySet()).get(i));
                refresh();
            });
            setControl(c);
            setButtonLayoutData(add);
            setButtonLayoutData(remove);
        }

        private void refresh() {
            list.removeAll();
            for (Map.Entry<Path, String> e : externals.entrySet()) list.add(e.getValue() + "    " + e.getKey());
        }
    }
}
