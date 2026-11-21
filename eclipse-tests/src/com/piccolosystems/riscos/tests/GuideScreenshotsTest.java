package com.piccolosystems.riscos.tests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.piccolosystems.riscos.lsp.BuildTree;
import com.piccolosystems.riscos.lsp.ImportWizard;
import com.piccolosystems.riscos.lsp.RiscOSLinks;
import com.piccolosystems.riscos.lsp.TreeImport;

/**
 * The user guide's screenshots, of a small invented tree, taken in the test
 * workbench and written to {@code target/screenshots}. Not a test of
 * anything: a scene that can't be set up is left out, and the others taken.
 */
public class GuideScreenshotsTest {
    static final String MACROS = String.join("\n",
            "; Register saving",
            "        MACRO",
            "$label  Push    $regs",
            "$label  STMFD   sp!, {$regs}",
            "        MEND",
            "",
            "        MACRO",
            "$label  Pull    $regs",
            "        [ \"$regs\" :RIGHT: 2 = \"pc\"",
            "$label  LDMFD   sp!, {$regs}^",
            "        |",
            "$label  LDMFD   sp!, {$regs}",
            "        ]",
            "        MEND",
            "",
            "        MACRO",
            "$label  Entry   $regs",
            "$label  Push    \"$regs, lr\"",
            "        MEND",
            "        END", "");
    static final String DEFS = String.join("\n",
            "; Constants shared by the demo",
            "                GBLL    Debug",
            "Debug           SETL    {FALSE}",
            "BufferSize      *       256",
            "Flag_Ready      *       1 :SHL: 0",
            "Flag_Busy       *       1 :SHL: 1",
            "        END", "");
    static final String MAIN = String.join("\n",
            "; Demo module: a buffer and its flags",
            "        GET     Hdr:Macros",
            "        GET     Hdr:Defs",
            "",
            "        AREA    |Demo$$Code|, CODE, READONLY",
            "",
            "        GBLA    Slots",
            "Slots   SETA    4",
            "  [ :DEF: BIG_BUFFERS",
            "Slots   SETA    Slots * 4",
            "  ]",
            "",
            "        EXPORT  buffer_init",
            "; Clears the buffer and marks it ready",
            "buffer_init ROUT",
            "        Entry   \"r4-r6\"",
            "        MOV     r4, #BufferSize",
            "        MOV     r5, #0",
            "01      SUBS    r4, r4, #4",
            "        STRGE   r5, [r0, r4]",
            "        BGT     %BT01",
            "        MOV     r6, #Flag_Ready",
            "  [ Debug",
            "        SWI     XOS_WriteS",
            "        DCB     \"buffer cleared\", 0",
            "        ALIGN",
            "  ]",
            "        STR     r6, [r0, #BufferSize]",
            "        MOV     r1, #Flag_Done",
            "        Pull    \"r4-r6, pc\"",
            "",
            "        DCD     Slots",
            "        END", "");
    static final String C_MAIN = String.join("\n",
            "/* Demo: the C side of the module */",
            "#include <stdint.h>",
            "",
            "extern void buffer_init(uint32_t *buffer);",
            "",
            "static uint32_t buffer[64 + 1];",
            "",
            "#ifdef BIG_BUFFERS",
            "#define SLOTS 16",
            "#else",
            "#define SLOTS 4",
            "#endif",
            "",
            "int demo_start(void)",
            "{",
            "  buffer_init(buffer);",
            "  return SLOTS;",
            "}", "");
    static final String BASIC = String.join("\n",
            "REM >MakeTable",
            "REM Builds the lookup table for the demo",
            "DIM table%(255)",
            "PROCbuild(4)",
            "PRINT FNsum(255)",
            "PROCreport",
            "END",
            "",
            "REM Fills table%() with multiples of step%",
            "DEF PROCbuild(step%)",
            "LOCAL i%",
            "FOR i% = 0 TO 255",
            "  table%(i%) = i% * step%",
            "NEXT",
            "IF step% > 8 THEN",
            "  PRINT \"large step\"",
            "ELSE",
            "  PRINT \"small step\"",
            "ENDIF",
            "ENDPROC",
            "",
            "REM The sum of the first n% entries",
            "DEF FNsum(n%)",
            "LOCAL i%, total%",
            "FOR i% = 0 TO n%",
            "  total% += table%(i%)",
            "NEXT",
            "= total%", "");
    static final String MESSAGES = "# Demo's messages\nTitle:Buffer demo\0\nReady:Buffer ready\0\n"
            + "Err1:Buffer full (%0 entries)\0\n";
    static final String CMHG = String.join("\n",
            "; Module header for the demo",
            "title-string:           Demo",
            "help-string:            Demo 0.01",
            "initialisation-code:    demo_init",
            "finalisation-code:      demo_final",
            "swi-chunk-base-number:  0x5AB00",
            "swi-handler-code:       demo_swi",
            "swi-decoding-table:     Demo, Init, Status", "");

    static Path tmp, riscos, out;
    static BuildTree tree;

    @BeforeAll
    static void makeTree() throws Exception {
        out = Path.of("target", "screenshots").toAbsolutePath();
        Files.createDirectories(out);
        // A short path, as the wizard shows it: <tmp>/riscos, if that is free.
        Path plain = Path.of(System.getProperty("java.io.tmpdir"), "riscos");
        tmp = (Files.exists(plain) ? Files.createTempDirectory("riscos-shots") : Files.createDirectories(plain))
                .toRealPath();
        // CDT's Outline lists a C file's includes in one group, folded: the forced
        // include is named by its path in this test's workspace, which means nothing.
        ui(() -> org.eclipse.cdt.ui.PreferenceConstants.getPreferenceStore()
                .setValue(org.eclipse.cdt.ui.PreferenceConstants.OUTLINE_GROUP_INCLUDES, true));
        riscos = tmp.resolve("BCM2835/RiscOS");
        Files.createDirectories(tmp.resolve("BCM2835/.git/info"));
        for (String env : List.of("ROOL/BCM2835", "ROOL/Titanium"))
            write(riscos.resolve("Env/" + env + ".sh"), "export BUILD=" + env.substring(5) + "\nexport INSTALLDIR="
                    + WorkbenchTests.bashPath(riscos.resolve("Install")) + "\nexport HDR_PATH="
                    + WorkbenchTests.bashPath(riscos.resolve("Export/APCS-32/Hdr")) + "/\n");
        write(riscos.resolve("BuildSys/ModuleDB"), "# name type directory\nDemo C Sources.Demo\n");
        for (String c : List.of("BCM2835", "Titanium", "ROOL/BCM2835", "ROOL/Titanium"))
            write(riscos.resolve("BuildSys/Components/" + c), "Demo\n");
        Path demo = riscos.resolve("Sources/Demo");
        write(demo.resolve("Makefile"), String.join("\n",
                "rom:",
                "\tarmasm -apcs 3/32bit/fpe3 -cpu 6Z -o o.Main Main.s",
                "\tarmcc -c -apcs 3/32bit -I. -DBUILD_$(BUILD) -o o.main main.c", ""));
        write(demo.resolve("s/Main"), MAIN);
        write(demo.resolve("c/main"), C_MAIN);
        write(demo.resolve("cmhg/ModHdr"), CMHG);
        write(demo.resolve("Resources/UK/Messages"), MESSAGES);
        write(demo.resolve("Tools/MakeTable.bas"), BASIC);
        Files.createDirectories(demo.resolve("objs"));
        Files.createSymbolicLink(demo.resolve("objs/Main.s"), Path.of("../s/Main"));
        Files.createSymbolicLink(demo.resolve("objs/main.c"), Path.of("../c/main"));
        Files.createSymbolicLink(demo.resolve("objs/ModHdr.cmhg"), Path.of("../cmhg/ModHdr"));
        // Hdr:Macros and Hdr:Defs, as the Env file's path variable finds them.
        write(riscos.resolve("Export/APCS-32/Hdr/Macros"), MACROS);
        write(riscos.resolve("Export/APCS-32/Hdr/Defs"), DEFS);

        ui(() -> {
            Shell s = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
            s.setMaximized(false);
            s.setBounds(0, 0, 1280, 800);
            IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            for (var ref : page.getViewReferences())
                if (ref.getId().contains("TaskList") || ref.getId().contains("tasks")) page.hideView(ref);
        });
    }

    @AfterAll
    static void cleanUp() throws Exception {
        ui(() -> PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().closeAllEditors(false));
        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            var loc = p.getLocation();
            if (loc != null && loc.toPath().startsWith(riscos)) p.delete(false, true, null);
        }
        if (tree != null) tree.forget();
        // Point the language server back at whatever other trees there are.
        for (BuildTree t : BuildTree.all()) TreeImport.switchTo(t, t.active);
    }

    @Test
    void screenshots() throws Exception {
        // ---- the import wizard
        ImportWizard wizard = new ImportWizard();
        WizardDialog d = ui(() -> {
            WizardDialog w = new WizardDialog(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), wizard);
            w.setBlockOnOpen(false);
            w.open();
            w.getShell().setSize(760, 620);
            return w;
        });
        try {
            IWizardPage treePage = wizard.getPage("tree"), configs = wizard.getPage("configurations"),
                    comps = wizard.getPage("components");
            ui(() -> widgets(treePage.getControl(), Text.class).get(0).setText(riscos.toString()));
            settle(500);
            shot("import-tree", ui(() -> d.getShell()));
            ui(() -> d.showPage(configs));
            waitQuietly(60, () -> ui(() -> widgets(configs.getControl(), Table.class).get(0).getItemCount() > 0));
            ui(() -> {
                Table envs = widgets(configs.getControl(), Table.class).get(0);
                for (TableItem i : envs.getItems()) {
                    i.setChecked(true);
                    Event e = new Event();
                    e.item = i;
                    e.detail = SWT.CHECK;
                    envs.notifyListeners(SWT.Selection, e);
                }
            });
            settle(300);
            shot("import-configurations", ui(() -> d.getShell()));
            ui(() -> d.showPage(comps));
            waitQuietly(120, () -> ui(() -> widgets(comps.getControl(), Table.class).stream().anyMatch(t -> t.getItemCount() > 0)));
            settle(500);
            shot("import-components", ui(() -> d.getShell()));
        } finally {
            ui(() -> d.close());
        }

        // ---- import it, as Finish does
        tree = new BuildTree();
        tree.riscos = riscos;
        tree.name = "BCM2835";
        tree.configurations.addAll(List.of("ROOL/BCM2835", "ROOL/Titanium"));
        tree.active = "ROOL/BCM2835";
        TreeImport.importTree(tree, new NullProgressMonitor());
        IProject demo = ResourcesPlugin.getWorkspace().getRoot().getProject("Demo");

        // ---- assembler: Project Explorer, editor with shading, Outline
        ui(() -> {
            try {
                IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                page.showView("org.eclipse.ui.views.ContentOutline");
                page.showView(IPageLayout_PROJECT_EXPLORER);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ITextEditor asm = open(demo.getFile("objs/Main.s"));
        waitQuietly(60, () -> ui(() -> outlineItems() > 3));
        expand(demo.getFile("objs/Main.s"));
        settle(3000);
        shot("assembler", workbench());

        // ---- hover on a macro call
        int entry = MAIN.indexOf("Entry   \"r4-r6\"") + 2;
        if (showHover(asm, entry)) shot("hover-macro", workbench());
        closePopups();
        int value = MAIN.indexOf("#BufferSize") + 3;
        if (showHover(asm, value)) shot("hover-symbol", workbench());
        closePopups();

        // ---- Ctrl+hover on a variable: its declaration and assignments
        int slots = MAIN.indexOf("DCD     Slots") + 10;
        if (showLinks(asm, slots)) shot("ctrl-hover-variable", workbench());
        closePopups();

        // ---- BASIC
        ITextEditor bas = open(demo.getFile("Tools/MakeTable.bas"));
        waitQuietly(30, () -> ui(() -> outlineItems() > 1));
        expand(demo.getFile("Tools/MakeTable.bas"));
        settle(2000);
        shot("basic", workbench());
        int iv = BASIC.indexOf("table%(i%) = i%") + 1;
        if (showLinks(bas, iv)) shot("ctrl-hover-basic", workbench());
        closePopups();

        // ---- Messages, with its NULs
        open(demo.getFile("Resources/UK/Messages"));
        settle(2000);
        shot("messages", workbench());

        // ---- CMHG
        open(demo.getFile("objs/ModHdr.cmhg"));
        settle(1500);
        shot("cmhg", workbench());

        // ---- C, shaded by the active configuration
        waitQuietly(120, () -> {
            try {
                var im = org.eclipse.cdt.core.CCorePlugin.getIndexManager();
                return im.joinIndexer(1000, new NullProgressMonitor());
            } catch (Exception e) {
                return false;
            }
        });
        open(demo.getFile("objs/main.c"));
        settle(4000);
        shot("c", workbench());

        // ---- File Search
        ui(() -> {
            var scope = org.eclipse.search.ui.text.FileTextSearchScope.newWorkspaceScope(new String[] {"*"}, false);
            @SuppressWarnings("restriction")
            var q = new org.eclipse.search.internal.ui.text.FileSearchQuery("buffer_init", false, true, scope);
            org.eclipse.search.ui.NewSearchUI.runQueryInForeground(null, q);
        });
        settle(3000);
        shot("search", workbench());

        // ---- the Problems view, with Main.s and its problem
        open(demo.getFile("objs/Main.s"));
        ui(() -> {
            try {
                PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
                        .showView("org.eclipse.ui.views.ProblemView");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        settle(1500);
        ui(() -> {
            var v = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
                    .findView("org.eclipse.ui.views.ProblemView");
            if (v instanceof org.eclipse.ui.views.markers.MarkerSupportView m) {
                for (org.eclipse.swt.widgets.Tree t : widgets(PlatformUI.getWorkbench().getActiveWorkbenchWindow()
                        .getShell(), org.eclipse.swt.widgets.Tree.class))
                    if (t.getParent() != null && t.isVisible() && t.getColumnCount() > 1)
                        for (var item : t.getItems()) item.setExpanded(true);
            }
        });
        settle(1000);
        shot("problems", workbench());

        // ---- preferences
        for (String[] p : new String[][] {{"com.piccolosystems.riscos.lsp.preferences", "preferences"},
                {"com.piccolosystems.riscos.lsp.preferences.advanced", "preferences-advanced"}}) {
            PreferenceDialog pd = ui(() -> {
                PreferenceDialog x = PreferencesUtil.createPreferenceDialogOn(
                        PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), p[0], null, null);
                x.setBlockOnOpen(false);
                x.open();
                x.getShell().setSize(900, 560);
                return x;
            });
            settle(1000);
            // The settings directory is this test's workspace: show where a user's would be instead.
            // Only the field is changed; the dialog is closed without applying it.
            ui(() -> showAsUsers(pd.getShell()));
            settle(300);
            shot(p[1], ui(() -> pd.getShell()));
            ui(() -> pd.close());
        }
    }

    /** Shows a typical user's settings directory in any field holding this workspace's. */
    static void showAsUsers(Control c) {
        if (c instanceof Text t && t.getText().contains(".metadata")) {
            t.setText("/home/me/eclipse-workspace/.metadata/.plugins/com.piccolosystems.riscos.lsp/trees/BCM2835/ROOL_BCM2835");
            t.setSelection(0);
        } else if (c instanceof org.eclipse.swt.widgets.Composite comp) {
            for (Control k : comp.getChildren()) showAsUsers(k);
        }
    }

    static final String IPageLayout_PROJECT_EXPLORER = "org.eclipse.ui.navigator.ProjectExplorer";

    // ------------------------------------------------------------------ scenes

    static ITextEditor open(IFile f) {
        return ui(() -> {
            try {
                var ed = IDE.openEditor(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), f, true);
                return ed.getAdapter(ITextEditor.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Shows the file in the Project Explorer, hierarchically. */
    static void reveal(IResource r) {
        ui(() -> {
            try {
                var view = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
                        .showView(IPageLayout_PROJECT_EXPLORER);
                if (view instanceof org.eclipse.ui.navigator.CommonNavigator n) {
                    n.selectReveal(new org.eclipse.jface.viewers.StructuredSelection(r));
                }
            } catch (Exception e) {
                // as it is
            }
        });
    }

    /** Shows the file in the Project Explorer, alone opened out into its symbols, once they have come. */
    static void expand(IFile f) {
        ui(() -> {
            var view = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().findView(IPageLayout_PROJECT_EXPLORER);
            if (view instanceof org.eclipse.ui.navigator.CommonNavigator n) n.getCommonViewer().collapseAll();
        });
        reveal(f);
        waitQuietly(60, () -> ui(() -> {
            var view = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().findView(IPageLayout_PROJECT_EXPLORER);
            if (!(view instanceof org.eclipse.ui.navigator.CommonNavigator n)) return true;
            var viewer = n.getCommonViewer();
            var sel = viewer.getTree().getSelection();
            if (sel.length == 0) return false;
            viewer.expandToLevel(sel[0].getData(), 1);
            var items = sel[0].getItems();
            if (items.length == 0 || items[0].getText().startsWith("Pending")) return false;
            viewer.getTree().showItem(items[items.length - 1]);
            return true;
        }));
    }

    /** The number of items the Outline shows. */
    static int outlineItems() {
        var o = (org.eclipse.ui.views.contentoutline.ContentOutline) PlatformUI.getWorkbench()
                .getActiveWorkbenchWindow().getActivePage().findView("org.eclipse.ui.views.ContentOutline");
        if (o == null || o.getCurrentPage() == null || o.getCurrentPage().getControl() == null) return 0;
        Control c = o.getCurrentPage().getControl();
        return c instanceof org.eclipse.swt.widgets.Tree t ? t.getItemCount() : 0;
    }

    /** Rests the mouse on the text at an offset, so that its hover shows. */
    static boolean showHover(ITextEditor ed, int offset) {
        ui(() -> ed.selectAndReveal(offset, 0));
        settle(500);
        for (int i = 0; i < 10; i++) {
            mouseAway();
            ui(() -> {
                var t = LSPEclipseUtils.getTextViewer(ed).getTextWidget();
                var at = t.toDisplay(t.getLocationAtOffset(offset));
                post(SWT.MouseMove, at.x + 3, at.y + t.getLineHeight() / 2);
            });
            settle(400);
            ui(() -> {
                var t = LSPEclipseUtils.getTextViewer(ed).getTextWidget();
                var at = t.toDisplay(t.getLocationAtOffset(offset));
                post(SWT.MouseMove, at.x + 5, at.y + t.getLineHeight() / 2);
            });
            settle(3000);
            if (ui(() -> popups() > 0)) return true;
        }
        return false;
    }

    static void post(int type, int x, int y) {
        Event e = new Event();
        e.type = type;
        e.x = x;
        e.y = y;
        Display.getDefault().post(e);
    }

    /** Moves the mouse off the editors, which closes their hovers. */
    static void mouseAway() {
        ui(() -> post(SWT.MouseMove, 1270, 790));
        settle(800);
    }

    /** Shows the Ctrl+hover links at an offset, as holding Ctrl over the name does. */
    static boolean showLinks(ITextEditor ed, int offset) {
        var viewer = ui(() -> LSPEclipseUtils.getTextViewer(ed));
        AtomicReference<org.eclipse.jface.text.hyperlink.IHyperlink[]> links = new AtomicReference<>();
        waitQuietly(60, () -> {
            links.set(ui(() -> new RiscOSLinks().detectHyperlinks(viewer, new org.eclipse.jface.text.Region(offset, 0), true)));
            return links.get() != null && links.get().length > 1;
        });
        if (links.get() == null || links.get().length < 2) {
            System.out.println("screenshots: links at " + offset + ": "
                    + (links.get() == null ? "none" : java.util.Arrays.toString(links.get())));
            return false;
        }
        ui(() -> {
            try {
                var f = org.eclipse.jface.text.TextViewer.class.getDeclaredField("fHyperlinkPresenter");
                f.setAccessible(true);
                var presenter = (org.eclipse.jface.text.hyperlink.IHyperlinkPresenter) f.get(viewer);
                if (presenter instanceof org.eclipse.jface.text.hyperlink.IHyperlinkPresenterExtension2 p2)
                    p2.showHyperlinks(links.get(), true);
                else
                    presenter.showHyperlinks(links.get());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        settle(1500);
        return ui(() -> popups() > 0);
    }

    /** The pop-ups showing over the workbench. */
    static int popups() {
        int n = 0;
        Shell main = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
        for (Shell s : Display.getDefault().getShells())
            if (s != main && s.isVisible() && !s.isDisposed()) n++;
        return n;
    }

    static void closePopups() {
        mouseAway();
        ui(() -> {
            Shell main = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
            for (Shell s : Display.getDefault().getShells())
                if (s != main && s.isVisible() && s.getParent() != null) s.setVisible(false);
        });
        settle(500);
    }

    static Shell workbench() {
        return ui(() -> PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell());
    }

    // ------------------------------------------------------------------ taking them

    /** Saves what the screen shows of a shell (and anything over it) as {@code name.png}. */
    static void shot(String name, Shell shell) {
        // A small resize makes everything lay out and paint again (tables shown after their
        // page was hidden aren't, under a virtual display).
        if (ui(() -> popups()) == 0 || shell != workbench()) {
            ui(() -> {
                var size = shell.getSize();
                shell.setSize(size.x + 1, size.y);
            });
            settle(400);
            ui(() -> {
                var size = shell.getSize();
                shell.setSize(size.x - 1, size.y);
            });
            settle(400);
        }
        ui(() -> {
            shell.layout(true, true);
            shell.redraw();
            shell.update();
            for (Table t : widgets(shell, Table.class))
                System.out.println("screenshots: " + name + ": a table of " + t.getItemCount() + " items");
        });
        settle(600);
        ui(() -> {
            Display d = Display.getDefault();
            Rectangle b = shell.getBounds();
            // Only as far as the shell's controls reach: with no window manager to draw a frame,
            // the bounds are larger than what is drawn, and the rest shows what's behind.
            var origin = shell.toDisplay(0, 0);
            int right = 0, bottom = 0;
            for (Control c : shell.getChildren()) {
                if (!c.isVisible()) continue;
                Rectangle cb = c.getBounds();
                right = Math.max(right, origin.x + cb.x + cb.width);
                bottom = Math.max(bottom, origin.y + cb.y + cb.height);
            }
            if (right > b.x && right < b.x + b.width) b.width = right - b.x;
            if (bottom > b.y && bottom < b.y + b.height) b.height = bottom - b.y;
            Image img = new Image(d, b.width, b.height);
            GC gc = new GC(d);
            try {
                gc.copyArea(img, b.x, b.y);
                ImageData data = img.getImageData();
                // Under the virtual display the right and bottom edges of a shell can be
                // left unpainted, black: leave them out.
                int w = data.width, h = data.height;
                while (w > 1 && blackColumn(data, w - 1, h)) w--;
                while (h > 1 && blackRow(data, h - 1, w)) h--;
                if (w < data.width || h < data.height) {
                    Image cropped = new Image(d, w, h);
                    gc.copyArea(cropped, b.x, b.y);
                    data = cropped.getImageData();
                    cropped.dispose();
                }
                ImageLoader l = new ImageLoader();
                l.data = new ImageData[] {data};
                l.save(out.resolve(name + ".png").toString(), SWT.IMAGE_PNG);
            } finally {
                gc.dispose();
                img.dispose();
            }
        });
    }

    private static boolean blackColumn(ImageData data, int x, int h) {
        for (int y = 0; y < h; y++) if (data.getPixel(x, y) != 0) return false;
        return true;
    }

    private static boolean blackRow(ImageData data, int y, int w) {
        for (int x = 0; x < w; x++) if (data.getPixel(x, y) != 0) return false;
        return true;
    }

    /** Lets the UI run for a while (the tests run outside the UI thread): painting, background jobs, pop-ups. */
    static void settle(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ui(() -> {
            // and wait for what was queued meanwhile
        });
    }

    static void waitQuietly(int seconds, BooleanSupplier ok) {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            if (ok.getAsBoolean()) return;
            settle(250);
        }
    }

    static <T extends Control> List<T> widgets(Control c, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (type.isInstance(c)) out.add(type.cast(c));
        if (c instanceof org.eclipse.swt.widgets.Composite comp)
            for (Control child : comp.getChildren()) out.addAll(widgets(child, type));
        return out;
    }

    static void write(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.ISO_8859_1);
    }

    static <T> T ui(Supplier<T> s) {
        AtomicReference<T> r = new AtomicReference<>();
        AtomicReference<RuntimeException> ex = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            try {
                r.set(s.get());
            } catch (RuntimeException e) {
                ex.set(e);
            }
        });
        if (ex.get() != null) throw ex.get();
        return r.get();
    }

    static void ui(Runnable r) {
        ui(() -> {
            r.run();
            return null;
        });
    }
}
