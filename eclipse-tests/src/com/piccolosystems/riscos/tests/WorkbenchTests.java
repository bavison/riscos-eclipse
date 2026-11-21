package com.piccolosystems.riscos.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServers;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.piccolosystems.riscos.cdt.AsmCommands;
import com.piccolosystems.riscos.cdt.AsmLinks;
import com.piccolosystems.riscos.lsp.BuildTree;
import com.piccolosystems.riscos.lsp.RiscOSLanguageClient;
import com.piccolosystems.riscos.lsp.ConvertedFiles;
import com.piccolosystems.riscos.lsp.RiscOSTextEditor;
import com.piccolosystems.riscos.lsp.ImportWizard;
import com.piccolosystems.riscos.lsp.MirroredFilesFilter;
import com.piccolosystems.riscos.lsp.TreeImport;

/**
 * Runs the plugins in a real Eclipse workbench, with CDT installed (which
 * also claims {@code *.s}), on an invented RISC OS tree with an {@code objs/}
 * link farm, imported as the import wizard does it:
 *
 * <pre>
 * Tree/.git/                                  the tree's git repository
 * Tree/RiscOS/Env/Test.sh, Other.sh           two configurations
 * Tree/RiscOS/BuildSys/ModuleDB, Components/  one component with code, Comp
 * Tree/RiscOS/Export/Hdr/Defs                 a header, extensionless, defining Value
 * Tree/RiscOS/Sources/Comp/Makefile           assembles Main.s and Helper.s, compiles main.c
 * Tree/RiscOS/Sources/Comp/s/Main, s/Helper   assembler sources, extensionless
 * Tree/RiscOS/Sources/Comp/c/main             a C source, extensionless
 * Tree/RiscOS/Sources/Comp/objs/Main.s etc.   links to them, as the build makes
 * </pre>
 *
 * The checks are what a person would try by hand: the projects the import
 * made, the file types, the editor, colouring, the language server's
 * definition and references, its diagnostics, which names the link farm's
 * files are shown by, and switching configuration. Each test opens what it
 * needs, as they may run in any order.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WorkbenchTests {
    static final String ARMASM = "com.piccolosystems.riscos.armasm";
    static final String LSP_BUNDLE = "com.piccolosystems.riscos.lsp";

    static final String DEFS = String.join("\n",
            "; Definitions",
            "Value           *       42",
            "LongerSymbolNameThatIsQuiteLong * 1", "");
    static final String MAIN = String.join("\n",
            "        GET     Hdr:Defs",
            "        AREA    |Code|, CODE, READONLY",
            "Start   ROUT",
            "        MOV     r0, #Value",
            "  [ Value = 1",
            "        MOV     r1, r1",
            "  |",
            "        MOV     r2, r2",
            "  ]",
            "averyveryverylonglabelnameindeed ROUT",
            "        B       Start",
            "        EXPORT  asm_fn",
            "asm_fn  MOV     pc, lr",
            "        GBLA    Count",
            "Count   SETA    1",
            "Count   SETA    Count+1",
            "        END", "");
    static final String HELPER = String.join("\n",
            "        GET     Hdr:Defs",
            "        AREA    |Code2|, CODE, READONLY",
            "Helper  ROUT",
            "        MOV     r0, #Value",
            "        Bogus   r0",
            "        END", "");
    /** Which function is compiled depends on the configuration (the makefile defines BUILD_$(BUILD)). */
    static final String C_MAIN = "#ifdef BUILD_Test\nint test_only(void) { return 1; }\n#else\n"
            + "int other_only(void) { return 2; }\n#endif\nextern int asm_fn(void);\nint main(void)\n{\n  return asm_fn();\n}\n"
            + "extern int util_fn(void);\nint use_util(void)\n{\n  return util_fn();\n}\n";
    /** Another C file, for going from one to another. */
    static final String C_UTIL = "int util_fn(void)\n{\n  return 3;\n}\n";
    /** A tokenised program: PROCa / END / DEF PROCa / PRINT "hi" / ENDPROC, lines 10 to 50. */
    static final byte[] TOKENISED = bytes(0x0D,
            0x00, 0x0A, 0x06, 0xF2, 'a', 0x0D,
            0x00, 0x14, 0x05, 0xE0, 0x0D,
            0x00, 0x1E, 0x08, 0xDD, ' ', 0xF2, 'a', 0x0D,
            0x00, 0x28, 0x0A, 0xF1, ' ', '"', 'h', 'i', '"', 0x0D,
            0x00, 0x32, 0x05, 0xE1, 0x0D,
            0xFF);

    static byte[] bytes(int... b) {
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) out[i] = (byte) b[i];
        return out;
    }

    /** Messages end with NULs; \u00a3 is Latin-1. */
    static final String MESSAGES = "# Comp's messages\nHello:Hello, world\0\nPrice:\u00a35\0\n";
    static final String BASIC = String.join("\n",
            "PROCgreet(\"world\")",
            "PROCnowhere",
            "END",
            "",
            "REM Says hello",
            "DEF PROCgreet(who$)",
            "IF who$<>\"\" THEN",
            "  PRINT \"Hello, \";who$",
            "ENDIF",
            "ENDPROC", "");

    static Path tmp, riscos;
    static IProject project, comp;
    static BuildTree tree;

    @BeforeAll
    static void makeTree() throws Exception {
        tmp = Files.createTempDirectory("riscos-tests").toRealPath();
        riscos = tmp.resolve("Tree/RiscOS");
        Files.createDirectories(tmp.resolve("Tree/.git/info"));
        for (String env : List.of("Test", "Other"))
            write(riscos.resolve("Env/" + env + ".sh"), "export BUILD=" + env + "\nexport INSTALLDIR=" + bashPath(riscos.resolve("Install"))
                    + "\nexport HDR_PATH=" + bashPath(riscos.resolve("Export/Hdr")) + "/\n");
        write(riscos.resolve("BuildSys/ModuleDB"), "# name type directory\nComp ASM Sources.Comp\nData DATA Sources.Data\n");
        write(riscos.resolve("BuildSys/Components/Test"), "Comp\nData\n");
        write(riscos.resolve("BuildSys/Components/Other"), "Comp\n");
        Path compDir = riscos.resolve("Sources/Comp");
        write(compDir.resolve("Makefile"), String.join("\n",
                "rom:",
                "\tarmasm -apcs 3/32bit/fpe3 -cpu 6Z -o o.Main Main.s",
                "\tarmasm -apcs 3/32bit/fpe3 -cpu 6Z -o o.Helper Helper.s",
                "\tarmcc -c -apcs 3/32bit -I. -DBUILD_$(BUILD) -o o.main main.c",
                "\tarmcc -c -apcs 3/32bit -I. -DBUILD_$(BUILD) -o o.util util.c", ""));
        write(riscos.resolve("Export/Hdr/Defs"), DEFS);
        write(compDir.resolve("s/Main"), MAIN);
        write(compDir.resolve("s/Helper"), HELPER);
        write(compDir.resolve("c/main"), C_MAIN);
        write(compDir.resolve("c/util"), C_UTIL);
        write(compDir.resolve("Prog.bas"), BASIC);
        write(compDir.resolve("Resources/UK/Messages"), MESSAGES);
        write(compDir.resolve("Makefile2"), "# not BASIC\n");
        Files.write(compDir.resolve("Prog2,ffb"), TOKENISED);
        Files.createDirectories(compDir.resolve("objs"));
        Files.createSymbolicLink(compDir.resolve("objs/Main.s"), Path.of("../s/Main"));
        Files.createSymbolicLink(compDir.resolve("objs/Helper.s"), Path.of("../s/Helper"));
        Files.createSymbolicLink(compDir.resolve("objs/main.c"), Path.of("../c/main"));
        Files.createSymbolicLink(compDir.resolve("objs/util.c"), Path.of("../c/util"));


        // LSP4E's own log of every message, as Preferences > Language Servers > Logs sets it.
        var lsp4e = InstanceScope.INSTANCE.getNode("org.eclipse.lsp4e");
        lsp4e.putBoolean("com.piccolosystems.riscos.objasm.file.logging.enabled", true);
        lsp4e.flush();

        // What the wizard's Finish does.
        tree = new BuildTree();
        tree.riscos = riscos;
        tree.name = ImportWizard.defaultName(riscos);
        tree.configurations.addAll(List.of("Test", "Other"));
        tree.active = "Test";
        TreeImport.importTree(tree, new NullProgressMonitor());

        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        project = root.getProject("Tree");
        comp = root.getProject("Comp");
    }

    @AfterAll
    static void cleanUp() throws CoreException {
        ui(() -> PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().closeAllEditors(false));
        if (comp != null && comp.exists()) comp.delete(false, true, null);
        if (project != null && project.exists()) project.delete(false, true, null);
    }

    static void write(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.ISO_8859_1);
    }

    /** A file by its path in the tree, in the innermost project: a component's files are in its project. */
    static IFile file(String path) {
        return path.startsWith("Sources/Comp/") ? comp.getFile(path.substring("Sources/Comp/".length()))
                : project.getFile(path);
    }

    @Test
    @Order(0)
    void importMadeTheProjects() throws Exception {
        assertTrue(project.isOpen(), "the tree's project");
        assertEquals(IPath.fromPath(riscos), project.getLocation(), "the tree's project is at RiscOS/");
        assertTrue(comp.isOpen(), "the component's project");
        assertEquals(IPath.fromPath(riscos.resolve("Sources/Comp")), comp.getLocation(), "Comp's project is at its directory");
        assertEquals(TreeImport.CHARSET, project.getDefaultCharset(false), "the tree's encoding");
        assertEquals(TreeImport.CHARSET, comp.getDefaultCharset(false), "Comp's encoding");
        assertNull(ResourcesPlugin.getWorkspace().getRoot().getProject("Data").getLocation(), "Data has no code, so no project");
        String exclude = Files.readString(tmp.resolve("Tree/.git/info/exclude"));
        for (String e : List.of(".project", ".cproject", ".settings/"))
            assertTrue(exclude.lines().anyMatch(e::equals), e + " in .git/info/exclude: " + exclude);
        String settings = InstanceScope.INSTANCE.getNode(LSP_BUNDLE).get("settings", "");
        assertEquals(tree.settingsDir("Test").toString(), settings, "the language server's settings");
        assertTrue(Files.isRegularFile(Path.of(settings, "Comp/objasm.json")), "buildscan's output for Comp");
        assertTrue(Files.readString(Path.of(settings, "Comp/objasm.json")).contains("Helper.s"), "Helper.s is assembled");
        assertTrue(Files.isRegularFile(tree.settingsDir("Other").resolve("Comp/objasm.json")), "and in the other configuration");
        assertEquals(0, project.findMarkers(TreeImport.MARKER, false, IResource.DEPTH_INFINITE).length
                + comp.findMarkers(TreeImport.MARKER, false, IResource.DEPTH_INFINITE).length, "makefile problems");
        BuildTree saved = BuildTree.named("Tree");
        assertNotNull(saved, "the tree is remembered");
        assertEquals(List.of("Test", "Other"), saved.configurations, "its configurations");
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Order(1)
    void contentTypes() throws CoreException {
        IContentDescription d = file("Sources/Comp/objs/Main.s").getContentDescription();
        assertNotNull(d, "no content description for objs/Main.s");
        assertEquals(ARMASM, d.getContentType().getId(), "content type of objs/Main.s (CDT also claims *.s)");
        IContentDescription c = file("Sources/Comp/objs/main.c").getContentDescription();
        assertNotNull(c, "no content description for objs/main.c");
        assertEquals("org.eclipse.cdt.core.cSource", c.getContentType().getId(), "content type of objs/main.c");
        // An exported header has no extension: it is an assembler file from the import on.
        IContentDescription h = file("Export/Hdr/Defs").getContentDescription();
        assertNotNull(h, "no content description for Export/Hdr/Defs");
        assertEquals(ARMASM, h.getContentType().getId(), "content type of Export/Hdr/Defs");
        // LSP4E goes by the name and contents alone, not the project's settings.
        assertTrue(org.eclipse.lsp4e.LSPEclipseUtils.getFileContentTypes(file("Export/Hdr/Defs")).stream()
                .anyMatch(t -> t.getId().equals(ARMASM)), "LSP4E's content types of Export/Hdr/Defs");
    }

    @Test
    @Order(2)
    void editorAndColouring() {
        ITextEditor ed = open("Sources/Comp/objs/Main.s");
        assertEquals(RiscOSTextEditor.ID, ui(() -> ed.getSite().getId()), "editor for objs/Main.s");
        // Colouring: TM4E styles the text in the background.
        waitFor("colouring of objs/Main.s", 60, () -> ui(() -> styledRanges(ed) >= 6));
        // The line with the long label must be coloured too.
        int line = 9;   // 0-based: "averyveryverylonglabelnameindeed ROUT"
        waitFor("colouring of the line with a long label", 30, () -> ui(() -> styledOnLine(ed, line) >= 1));
    }

    @Test
    @Order(3)
    void definitionGoesToTheHeader() throws Exception {
        ITextEditor ed = open("Sources/Comp/objs/Main.s");
        IDocument doc = document(ed);
        List<Location> locs = definitions(doc, file("Sources/Comp/objs/Main.s"), 3, MAIN.split("\n")[3].indexOf("Value") + 1);
        assertTrue(!locs.isEmpty(), "no definition of Value");
        IResource r = LSPEclipseUtils.findResourceFor(locs.get(0).getUri());
        assertNotNull(r, "definition location " + locs.get(0).getUri() + " has no workspace file");
        assertEquals("/Tree/Export/Hdr/Defs", r.getFullPath().toString(), "where Value is defined");
        // Ctrl+click opens the location as LSP4E does: in the assembler editor, with colouring.
        Location at = locs.get(0);
        IEditorPart opened = ui(() -> {
            LSPEclipseUtils.openInEditor(at);
            return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
        });
        assertNotNull(opened, "no editor for the definition");
        assertEquals(RiscOSTextEditor.ID, ui(() -> opened.getSite().getId()),
                "editor for Export/Hdr/Defs, opened from a definition (" + ui(() -> opened.getEditorInput().getClass().getName()) + ")");
        ITextEditor hed = opened.getAdapter(ITextEditor.class);
        waitFor("colouring of Export/Hdr/Defs", 60, () -> ui(() -> styledRanges(hed) >= 2), () -> ui(() -> {
            String why = "input " + opened.getEditorInput().getClass().getSimpleName();
            try {
                IFile f = opened.getEditorInput().getAdapter(IFile.class);
                why += ", file " + (f == null ? null : f.getFullPath() + " content type "
                        + (f.getContentDescription() == null ? null : f.getContentDescription().getContentType().getId()));
                var buf = org.eclipse.core.filebuffers.FileBuffers.getTextFileBufferManager()
                        .getTextFileBuffer(document(hed));
                why += ", buffer content type " + (buf == null ? null : buf.getContentType() == null ? null : buf.getContentType().getId());
                var tv = LSPEclipseUtils.getTextViewer(hed);
                var rec = tv == null ? null : org.eclipse.tm4e.ui.text.TMPresentationReconciler.getTMPresentationReconciler(tv);
                why += ", TM reconciler " + (rec == null ? null : rec.getGrammar() == null ? "no grammar" : "a grammar");
            } catch (CoreException e) {
                why += ", " + e;
            }
            return why;
        }));
    }

    @Test
    @Order(4)
    void referencesUseTheLinkFarmNames() throws Exception {
        ITextEditor ed = open("Sources/Comp/objs/Main.s");
        IDocument doc = document(ed);
        // The server may be indexing again (after a configuration switch): ask until it answers.
        Optional<List<? extends Location>> refs = Optional.empty();
        for (long end = System.currentTimeMillis() + 120_000; System.currentTimeMillis() < end; Thread.sleep(1000)) {
            refs = LanguageServers.forDocument(doc).computeFirst(ls -> {
                ReferenceParams p = new ReferenceParams(new TextDocumentIdentifier(uri(file("Sources/Comp/objs/Main.s"))),
                        new Position(3, MAIN.split("\n")[3].indexOf("Value") + 1), new ReferenceContext(true));
                return ls.getTextDocumentService().references(p);
            }).get(60, TimeUnit.SECONDS);
            if (refs.isPresent() && !refs.get().isEmpty()) break;
        }
        assertTrue(refs.isPresent() && !refs.get().isEmpty(), "no references to Value");
        List<String> where = new ArrayList<>();
        for (Location l : refs.get()) {
            IResource r = LSPEclipseUtils.findResourceFor(l.getUri());
            where.add(l.getUri() + " -> " + (r == null ? "nothing" : r.getFullPath()));
        }
        for (String w : where) assertTrue(!w.endsWith("nothing"), "a reference has no workspace file: " + where);
        assertTrue(where.stream().anyMatch(w -> w.endsWith("/Comp/objs/Main.s")), "Main.s by its link: " + where);
        assertTrue(where.stream().anyMatch(w -> w.endsWith("/Comp/objs/Helper.s")), "Helper.s by its link: " + where);
        assertTrue(where.stream().noneMatch(w -> w.contains("/Comp/s/")), "no real names: " + where);
    }

    @Test
    @Order(5)
    void diagnosticsAreOnTheLink() {
        // Opening a file starts the language server; the tests may run in any order.
        open("Sources/Comp/objs/Main.s");
        IFile helper = file("Sources/Comp/objs/Helper.s");
        waitFor("an unknown-operation marker on objs/Helper.s", 90, () -> {
            try {
                for (IMarker m : helper.findMarkers(null, true, IResource.DEPTH_ZERO))
                    if (String.valueOf(m.getAttribute(IMarker.MESSAGE, "")).contains("Bogus")) return true;
            } catch (CoreException e) {
                // not yet
            }
            return false;
        }, () -> {
            StringBuilder b = new StringBuilder("markers in the project:");
            try {
                for (IMarker m : project.findMarkers(null, true, IResource.DEPTH_INFINITE))
                    b.append("\n  ").append(m.getResource().getFullPath()).append(" ").append(m.getType()).append(": ")
                            .append(m.getAttribute(IMarker.MESSAGE, ""));
            } catch (CoreException e) {
                b.append(e);
            }
            String link = "riscos-link:" + riscos.resolve("Sources/Comp/objs/Helper.s");
            IResource r = LSPEclipseUtils.findResourceFor(link);
            b.append("\n").append(link).append(" -> ").append(r == null ? "nothing" : r.getFullPath() + (r.isAccessible() ? "" : " (inaccessible)"));
            return b.toString();
        });
    }

    @Test
    @Order(12)
    void editorsRestoredAtStartup() throws Exception {
        // Eclipse restores an editor by saving its input in a memento when it closes, and
        // making the input again from that with the input's factory when it starts.
        for (String path : List.of("Sources/Comp/objs/Main.s", "Sources/Comp/Prog2,ffb")) {
            ITextEditor ed = open(path);
            String before = ui(() -> document(ed).get());
            org.eclipse.ui.IEditorInput restored = ui(() -> {
                var persistable = ed.getEditorInput().getPersistable();
                assertNotNull(persistable, path + ": its editor can be restored");
                var memento = org.eclipse.ui.XMLMemento.createWriteRoot("editor");
                persistable.saveState(memento);
                var factory = PlatformUI.getWorkbench().getElementFactory(persistable.getFactoryId());
                return (org.eclipse.ui.IEditorInput) factory.createElement(memento);
            });
            ui(() -> ed.getSite().getPage().closeEditor(ed, false));
            IEditorPart again = ui(() -> {
                try {
                    return IDE.openEditor(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), restored,
                            RiscOSTextEditor.ID);
                } catch (org.eclipse.ui.PartInitException e) {
                    throw new RuntimeException(e);
                }
            });
            ITextEditor te = again.getAdapter(ITextEditor.class);
            assertEquals(before, ui(() -> document(te).get()), path + ": restored with the same text");
            assertEquals(java.nio.file.Path.of(path).getFileName().toString(), ui(() -> te.getEditorInput().getName()),
                    path + ": by the same name");
            ui(() -> te.getSite().getPage().closeEditor(te, false));
        }
    }

    @Test
    @Order(12)
    void revisionsOfTokenisedBasicShownAsText() throws Exception {
        // As git's history and compare show a revision: contents, not a file.
        var storage = new org.eclipse.core.resources.IStorage() {
            @Override
            public java.io.InputStream getContents() {
                return new java.io.ByteArrayInputStream(TOKENISED);
            }

            @Override
            public IPath getFullPath() {
                return IPath.fromOSString("/Comp/Old,ffb");
            }

            @Override
            public String getName() {
                return "Old,ffb";
            }

            @Override
            public boolean isReadOnly() {
                return true;
            }

            @Override
            public <T> T getAdapter(Class<T> c) {
                return null;
            }
        };
        var input = new org.eclipse.ui.IStorageEditorInput() {
            @Override
            public org.eclipse.core.resources.IStorage getStorage() {
                return storage;
            }

            @Override
            public boolean exists() {
                return true;
            }

            @Override
            public org.eclipse.jface.resource.ImageDescriptor getImageDescriptor() {
                return null;
            }

            @Override
            public String getName() {
                return "Old,ffb";
            }

            @Override
            public org.eclipse.ui.IPersistableElement getPersistable() {
                return null;
            }

            @Override
            public String getToolTipText() {
                return "Old,ffb (a revision)";
            }

            @Override
            public <T> T getAdapter(Class<T> c) {
                return null;
            }
        };
        IEditorPart ed = ui(() -> {
            try {
                return IDE.openEditor(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), input,
                        RiscOSTextEditor.ID);
            } catch (org.eclipse.ui.PartInitException e) {
                throw new RuntimeException(e);
            }
        });
        ITextEditor te = ed.getAdapter(ITextEditor.class);
        String text = ui(() -> document(te).get());
        assertTrue(text.startsWith("PROCa") && text.contains("DEF PROCa") && text.contains("PRINT \"hi\""),
                "the revision shown as text: " + text);
        assertTrue(!ui(() -> te.isEditable()), "and read-only");
        ui(() -> te.getSite().getPage().closeEditor(te, false));
    }

    @Test
    @Order(12)
    void newTokenisedBasic() throws Exception {
        // As File › New › File does: an empty file, then an editor on it.
        IFile f = comp.getFile("NewProg,ffb");
        f.create(new java.io.ByteArrayInputStream(new byte[0]), true, new NullProgressMonitor());
        try {
            ITextEditor ed = open("Sources/Comp/NewProg,ffb");
            assertTrue(ConvertedFiles.isConverted(ui(() -> ed.getEditorInput())), "an empty ,ffb file is opened as a program");
            IDocument doc = document(ed);
            assertEquals("", ui(() -> doc.get()), "with no lines");
            ui(() -> {
                doc.set("PRINT \"hi\"\nEND\n");
                ed.doSave(new NullProgressMonitor());
            });
            byte[] saved = Files.readAllBytes(riscos.resolve("Sources/Comp/NewProg,ffb"));
            String hex = java.util.HexFormat.of().formatHex(saved);
            assertTrue(saved.length > 4 && saved[0] == 0x0D && saved[saved.length - 2] == 0x0D && saved[saved.length - 1] == (byte) 0xFF
                    && hex.contains("f1") && hex.contains("e0") && !new String(saved, java.nio.charset.StandardCharsets.ISO_8859_1).contains("PRINT"),
                    "saved tokenised (PRINT as &F1, END as &E0): " + hex);
            ui(() -> ed.getSite().getPage().closeEditor(ed, false));
        } finally {
            f.delete(true, new NullProgressMonitor());
        }
    }

    @Test
    @Order(12)
    void trailingSpacesRemovedOnSave() throws Exception {
        var prefs = org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode("com.piccolosystems.riscos.lsp");
        Path bas = riscos.resolve("Sources/Comp/Strip.bas"), asm = riscos.resolve("Sources/Comp/Strip.s");
        write(bas, "PRINT 1  \nPRINT 2\t \n");
        write(asm, "        MOV     r0, #1  \n        MOV     r1, #2 \n");
        try {
            comp.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
            // BASIC, by default only the lines changed: the first.
            ITextEditor ed = open("Sources/Comp/Strip.bas");
            IDocument doc = document(ed);

            ui(() -> {
                try {
                    doc.replace(0, 0, "REM\n");
                } catch (org.eclipse.jface.text.BadLocationException e) {
                    throw new RuntimeException(e);
                }
                ed.doSave(new NullProgressMonitor());
            });
            assertEquals("REM\nPRINT 1  \nPRINT 2\t \n", Files.readString(bas),
                    "only an inserted line is changed: the others keep their spaces");
            ui(() -> {
                try {
                    doc.replace(doc.getLineOffset(1) + 7, 0, "0");      // PRINT 10, spaces after
                } catch (org.eclipse.jface.text.BadLocationException e) {
                    throw new RuntimeException(e);
                }
                ed.doSave(new NullProgressMonitor());
            });
            assertEquals("REM\nPRINT 10\nPRINT 2\t \n", Files.readString(bas), "the changed line loses its spaces");
            // All lines.
            prefs.put("stripBasic", "all");
            ui(() -> {
                try {
                    doc.replace(0, 3, "REM hello");
                } catch (org.eclipse.jface.text.BadLocationException e) {
                    throw new RuntimeException(e);
                }
                ed.doSave(new NullProgressMonitor());
            });
            assertEquals("REM hello\nPRINT 10\nPRINT 2\n", Files.readString(bas), "every line loses its spaces");
            ui(() -> ed.getSite().getPage().closeEditor(ed, false));
            // Assembler, by default only the lines changed: the first.
            ITextEditor ae = open("Sources/Comp/Strip.s");
            IDocument ad = document(ae);
            ui(() -> {
                try {
                    ad.replace(0, 0, "; x\n");
                } catch (org.eclipse.jface.text.BadLocationException e) {
                    throw new RuntimeException(e);
                }
                ae.doSave(new NullProgressMonitor());
            });
            assertEquals("; x\n        MOV     r0, #1  \n        MOV     r1, #2 \n", Files.readString(asm),
                    "only the inserted line is changed");
            prefs.put("stripAssembler", "all");
            ui(() -> {
                try {
                    ad.replace(0, 3, "; y");
                } catch (org.eclipse.jface.text.BadLocationException e) {
                    throw new RuntimeException(e);
                }
                ae.doSave(new NullProgressMonitor());
            });
            assertEquals("; y\n        MOV     r0, #1\n        MOV     r1, #2\n", Files.readString(asm), "every line, when asked");
            ui(() -> ae.getSite().getPage().closeEditor(ae, false));
        } finally {
            prefs.remove("stripBasic");
            prefs.remove("stripAssembler");
            Files.deleteIfExists(bas);
            Files.deleteIfExists(asm);
            comp.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        }
    }

    @Test
    @Order(6)
    void linkFarmSourcesComeFirstInTheProjectExplorer() throws Exception {
        // An object file whose name sorts before the sources'.
        Path obj = riscos.resolve("Sources/Comp/objs/Aaa.o");
        Files.write(obj, new byte[4]);
        try {
            comp.getFolder("objs").refreshLocal(IResource.DEPTH_ONE, new NullProgressMonitor());
            List<String> names = new ArrayList<>();
            waitFor("objs/ listed in the Project Explorer", 30, () -> ui(() -> {
                try {
                    var view = (org.eclipse.ui.navigator.CommonNavigator) PlatformUI.getWorkbench()
                            .getActiveWorkbenchWindow().getActivePage().showView("org.eclipse.ui.navigator.ProjectExplorer");
                    var viewer = view.getCommonViewer();
                    viewer.refresh();
                    viewer.expandToLevel(comp, 1);
                    names.clear();
                    for (var top : viewer.getTree().getItems()) {
                        var objs = find(viewer, top, comp.getFolder("objs"));
                        if (objs == null) continue;
                        viewer.expandToLevel(objs.getData(), 1);
                        for (var item : objs.getItems()) names.add(item.getText());
                    }
                } catch (org.eclipse.ui.PartInitException e) {
                    throw new RuntimeException(e);
                }
                return names.contains("Aaa.o") && names.contains("Main.s") && names.contains("main.c");
            }));
            assertTrue(names.indexOf("Main.s") < names.indexOf("Aaa.o") && names.indexOf("main.c") < names.indexOf("Aaa.o"),
                    "the assembler and C sources come before the object file: " + names);
        } finally {
            Files.deleteIfExists(obj);
            comp.getFolder("objs").refreshLocal(IResource.DEPTH_ONE, new NullProgressMonitor());
        }
    }

    @Test
    @Order(6)
    void assemblerAndBasicFilesOpenOutIntoTheirSymbols() throws Exception {
        // CDT's order for a C file: by kind (the include, then the functions), then by name
        // (in the file, test_only and other_only come before main).
        List<String> cl = childLabels(comp.getFile("objs/main.c"), "main");
        assertTrue(at(cl, "main") > 0 && at(cl, "main") < at(cl, "other_only") && at(cl, "other_only") < at(cl, "test_only"),
                "CDT sorts main.c's: " + cl);
        // Ours alike: in Main.s, the GET, the macro, the IMPORT, constants, variables, then labels by name.
        List<String> asm = childLabels(comp.getFile("objs/Main.s"), "Start");
        assertTrue(at(asm, "asm_fn") >= 0 && at(asm, "asm_fn") < at(asm, "averyvery") && at(asm, "averyvery") < at(asm, "Start"),
                "labels by name: " + asm);
        assertEquals(0, at(asm, "Hdr:Defs"), "the file it GETs comes first, as a C file's #include does: " + asm);
        for (String[] c : new String[][] {{"objs/Main.s", "Start"}, {"Prog.bas", "PROCgreet"}}) {
            IFile f = comp.getFile(c[0]);
            List<String> names = new ArrayList<>();
            Object[] node = {null};
            waitFor(c[0] + "'s symbols in the Project Explorer", 60, () -> ui(() -> {
                try {
                    var view = (org.eclipse.ui.navigator.CommonNavigator) PlatformUI.getWorkbench()
                            .getActiveWorkbenchWindow().getActivePage().showView("org.eclipse.ui.navigator.ProjectExplorer");
                    var viewer = view.getCommonViewer();
                    names.clear();
                    for (var top : viewer.getTree().getItems()) {
                        var item = find(viewer, top, f);
                        if (item == null) continue;
                        viewer.expandToLevel(item.getData(), 1);
                        for (var child : item.getItems()) {
                            names.add(child.getText());
                            if (child.getText().startsWith(c[1])) node[0] = child.getData();
                        }
                    }
                } catch (org.eclipse.ui.PartInitException e) {
                    throw new RuntimeException(e);
                }
                return node[0] != null;
            }));
            assertTrue(node[0] instanceof com.piccolosystems.riscos.lsp.FileSymbols.Node, c[0] + ": " + names);
            ui(() -> com.piccolosystems.riscos.lsp.FileSymbols.open((com.piccolosystems.riscos.lsp.FileSymbols.Node) node[0]));
            IEditorPart ed = ui(() -> PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor());
            assertEquals(f.getName(), ed.getEditorInput().getName(), "opening " + c[1] + " opens its file");
            ui(() -> ed.getSite().getPage().closeEditor(ed, false));
        }
    }

    /** A path as an Env file (a bash script) would write it: {@code /c/x} for {@code C:\\x} on Windows. */
    static String bashPath(Path p) {
        String s = p.toAbsolutePath().toString();
        if (s.length() > 1 && s.charAt(1) == ':') s = "/" + Character.toLowerCase(s.charAt(0)) + s.substring(2).replace('\\', '/');
        return s;
    }

    /** Where the first label starting {@code prefix} is, or -1. */
    private static int at(List<String> labels, String prefix) {
        for (int i = 0; i < labels.size(); i++) if (labels.get(i).startsWith(prefix)) return i;
        return -1;
    }

    /** The labels of a file's children in the Project Explorer, once one starts with {@code expected}. */
    private static List<String> childLabels(IFile f, String expected) throws Exception {
        List<String> names = new ArrayList<>();
        waitFor(f.getName() + "'s children in the Project Explorer", 60, () -> ui(() -> {
            try {
                var view = (org.eclipse.ui.navigator.CommonNavigator) PlatformUI.getWorkbench()
                        .getActiveWorkbenchWindow().getActivePage().showView("org.eclipse.ui.navigator.ProjectExplorer");
                var viewer = view.getCommonViewer();
                names.clear();
                for (var top : viewer.getTree().getItems()) {
                    var item = find(viewer, top, f);
                    if (item == null) continue;
                    viewer.expandToLevel(item.getData(), 1);
                    for (var child : item.getItems()) names.add(child.getText());
                }
            } catch (org.eclipse.ui.PartInitException e) {
                throw new RuntimeException(e);
            }
            return names.stream().anyMatch(n -> n.startsWith(expected));
        }));
        return names;
    }

    /** The tree item under {@code item} showing {@code r}, expanding the way to it. */
    private static org.eclipse.swt.widgets.TreeItem find(org.eclipse.jface.viewers.TreeViewer viewer,
            org.eclipse.swt.widgets.TreeItem item, IResource r) {
        Object d = item.getData();
        IResource here = d instanceof IResource res ? res
                : d instanceof org.eclipse.core.runtime.IAdaptable a ? a.getAdapter(IResource.class) : null;
        if (r.equals(here)) return item;
        // By location: with projects shown hierarchically, a component's project is inside its tree's.
        if (here == null || here.getLocation() == null || !here.getLocation().isPrefixOf(r.getLocation())) return null;
        viewer.expandToLevel(d, 1);
        for (var child : item.getItems()) {
            var found = find(viewer, child, r);
            if (found != null) return found;
        }
        return null;
    }

    @Test
    @Order(6)
    void realNamesAreHiddenInTheProjectExplorer() {
        assertTrue(MirroredFilesFilter.hidden(file("Sources/Comp/s/Main")), "s/Main should be hidden");
        assertTrue(MirroredFilesFilter.hidden(file("Sources/Comp/c/main")), "c/main should be hidden");
        assertTrue(!MirroredFilesFilter.hidden(file("Sources/Comp/objs/Main.s")), "objs/Main.s should show");
        assertTrue(!MirroredFilesFilter.hidden(comp.getFolder("s")), "s/ itself should show, for new files");
        assertTrue(!MirroredFilesFilter.hidden(file("Export/Hdr/Defs")), "headers outside link farms should show");
        // Only the view hides them: the files are still there.
        assertNotNull(comp.findMember("s/Main"), "s/Main is still a workspace file");
        // But derived, so that searches leave them out, in the component's project and the tree's.
        waitFor("s/Main to be marked derived", 30, () -> file("Sources/Comp/s/Main").isDerived()
                && project.getFile("Sources/Comp/s/Main").isDerived() && file("Sources/Comp/c/main").isDerived());
        assertTrue(!file("Sources/Comp/objs/Main.s").isDerived(), "objs/Main.s isn't derived");
        assertTrue(!file("Export/Hdr/Defs").isDerived(), "headers outside link farms aren't derived");
        // A real file has a type with an editor, so it opens without a Marketplace offer;
        // but not C source, or CDT would index everything twice.
        waitFor("c/main to have the link farm file type", 30, () -> {
            try {
                var d = file("Sources/Comp/c/main").getContentDescription();
                return d != null && d.getContentType().getId().equals("com.piccolosystems.riscos.linkTarget");
            } catch (CoreException e) {
                return false;
            }
        });
    }

    @Test
    @Order(7)
    void editorsStayOpen() throws Exception {
        ITextEditor ed = open("Sources/Comp/objs/Helper.s");
        Thread.sleep(10_000);
        assertTrue(ui(() -> {
            for (var ref : PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getEditorReferences())
                if (ref.getEditor(false) == ed) return true;
            return false;
        }), "the editor on objs/Helper.s was closed");
    }

    @Test
    @Order(8)
    void switchingConfiguration() {
        open("Sources/Comp/objs/Main.s");
        IFile helper = file("Sources/Comp/objs/Helper.s");
        try {
            // Through the drop-down on the toolbar.
            Combo combo = toolbarCombo();
            assertEquals(List.of("Test", "Other"), ui(() -> List.of(combo.getItems())), "the toolbar lists the configurations");
            assertEquals("Test", ui(() -> combo.getText()), "and shows the active one");
            assertTrue(ui(() -> {
                org.eclipse.swt.graphics.GC gc = new org.eclipse.swt.graphics.GC(combo);
                try {
                    int text = 0;
                    for (String item : combo.getItems()) text = Math.max(text, gc.textExtent(item).x);
                    if (combo.getSize().x < text + com.piccolosystems.riscos.lsp.ConfigurationCombo.ARROW_AND_PADDING) return false;
                    // Nor is it cut off by what holds it, up to the toolbar.
                    for (Control c = combo; c.getParent() != null && !(c.getParent() instanceof org.eclipse.swt.widgets.ToolBar); c = c.getParent())
                        if (c.getBounds().x + c.getSize().x > c.getParent().getSize().x) return false;
                    return true;
                } finally {
                    gc.dispose();
                }
            }), "the drop-down is wide enough for its longest configuration");
            ui(() -> {
                combo.select(combo.indexOf("Other"));
                combo.notifyListeners(SWT.Selection, new Event());
            });
            waitFor("the switch to Other", 60, () -> "Other".equals(BuildTree.named("Tree").active));
            assertEquals(tree.settingsDir("Other").toString(), InstanceScope.INSTANCE.getNode(LSP_BUNDLE).get("settings", ""),
                    "the settings follow the active configuration");
            assertEquals("Other", BuildTree.named("Tree").active, "the active configuration is remembered");
            // The server indexes the other configuration: the diagnostics come back.
            waitFor("the unknown-operation marker on objs/Helper.s after switching", 90, () -> bogusMarker(helper));
        } finally {
            TreeImport.switchTo(tree, "Test");
        }
        // Switched by other means, the toolbar follows.
        waitFor("the toolbar to show Test again", 30, () -> ui(() -> "Test".equals(toolbarCombo().getText())));
    }

    /** The toolbar's drop-down of the active configuration. */
    static Combo toolbarCombo() {
        return ui(() -> {
            for (Combo c : widgets(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), Combo.class))
                if (c.getToolTipText() != null && c.getToolTipText().startsWith(com.piccolosystems.riscos.lsp.ConfigurationCombo.TOOLTIP))
                    return c;
            throw new AssertionError("no configuration drop-down on the toolbar");
        });
    }

    @Test
    @Order(9)
    void wizardPages() {
        ImportWizard wizard = new ImportWizard();
        WizardDialog d = ui(() -> {
            WizardDialog w = new WizardDialog(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), wizard);
            w.setBlockOnOpen(false);
            w.open();
            return w;
        });
        try {
            IWizardPage tree = wizard.getPage("tree"), configs = wizard.getPage("configurations"),
                    comps = wizard.getPage("components");
            List<Text> texts = ui(() -> widgets(tree.getControl(), Text.class));
            // Not a tree.
            ui(() -> texts.get(0).setText(tmp.toString()));
            assertTrue(!ui(() -> tree.isPageComplete()), "a directory that isn't a tree");
            assertNotNull(ui(() -> tree.getErrorMessage()), "says why");
            // The directory above RiscOS will do, and names the project.
            ui(() -> texts.get(0).setText(tmp.resolve("Tree").toString()));
            assertTrue(ui(() -> tree.isPageComplete()), "the tree: " + ui(() -> tree.getErrorMessage()));
            assertEquals("Tree", ui(() -> texts.get(1).getText()), "the project name");
            assertTrue(!ui(() -> wizard.canFinish()), "can't finish before choosing a configuration");

            ui(() -> d.showPage(configs));
            Table envs = ui(() -> widgets(configs.getControl(), Table.class).get(0));
            assertEquals(List.of("Other", "Test"), ui(() -> {
                List<String> l = new ArrayList<>();
                for (TableItem i : envs.getItems()) l.add(i.getText());
                return l;
            }), "the Env files that set BUILD");
            ui(() -> {
                TableItem test = envs.getItem(1);
                test.setChecked(true);
                Event e = new Event();
                e.item = test;
                e.detail = SWT.CHECK;
                envs.notifyListeners(SWT.Selection, e);
            });
            assertTrue(ui(() -> configs.isPageComplete()), "a configuration is ticked");
            assertEquals("Test", ui(() -> widgets(configs.getControl(), Combo.class).get(0).getText()), "and is active");
            assertTrue(ui(() -> wizard.canFinish()), "can finish now");

            ui(() -> d.showPage(comps));
            Table list = ui(() -> widgets(comps.getControl(), Table.class).get(0));
            waitFor("the components to be listed", 60, () -> ui(() -> list.getItemCount() > 0));
            assertEquals("Comp    Sources" + java.io.File.separator + "Comp", ui(() -> list.getItem(0).getText()), "the component with code");
            assertEquals(1, (int) ui(() -> list.getItemCount()), "only components with code");
            assertTrue(ui(() -> list.getItem(0).getChecked()), "ticked");

            ui(() -> d.showPage(wizard.getPage("externals")));
        } finally {
            ui(() -> d.close());
        }
    }

    /** Every widget of a type in a control's tree, in order. */
    static <T> List<T> widgets(Control c, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (type.isInstance(c)) out.add(type.cast(c));
        if (c instanceof Composite comp)
            for (Control k : comp.getChildren()) out.addAll(widgets(k, type));
        return out;
    }

    @Test
    @Order(10)
    void basic() throws Exception {
        ITextEditor ed = open("Sources/Comp/Prog.bas");
        assertEquals(RiscOSTextEditor.ID, ui(() -> ed.getSite().getId()), "editor for Prog.bas");
        IFile prog = file("Sources/Comp/Prog.bas");
        List<Location> locs = definitions(document(ed), prog, 0, 5);
        assertEquals(1, locs.size(), "the definition of PROCgreet");
        assertEquals(5, locs.get(0).getRange().getStart().getLine(), "is its DEF line");
        waitFor("a marker for the undefined PROCnowhere", 60, () -> {
            try {
                for (IMarker m : prog.findMarkers(null, true, IResource.DEPTH_ZERO))
                    if (String.valueOf(m.getAttribute(IMarker.MESSAGE, "")).contains("PROCnowhere")) return true;
            } catch (CoreException e) {
                // not yet
            }
            return false;
        });
    }

    @Test
    @Order(11)
    void messagesWithNuls() throws Exception {
        assertEquals("com.piccolosystems.riscos.messages", file("Sources/Comp/Resources/UK/Messages").getContentDescription()
                .getContentType().getId(), "content type of Messages");
        IContentDescription other = file("Sources/Comp/Makefile2").getContentDescription();
        assertTrue(other == null || !other.getContentType().getId().startsWith("com.piccolosystems"),
                "a file of no RISC OS type isn't one: " + (other == null ? null : other.getContentType().getId()));

        ITextEditor ed = open("Sources/Comp/Resources/UK/Messages");
        assertEquals(RiscOSTextEditor.ID, ui(() -> ed.getSite().getId()), "editor for Messages");
        assertTrue(ConvertedFiles.isConverted(ui(() -> ed.getEditorInput())), "opened converted");
        IDocument doc = document(ed);
        String text = ui(() -> doc.get());
        assertEquals(MESSAGES.replace('\0', '\u2400'), text, "NULs shown as \u2400, \u00a3 read as Latin-1");
        waitFor("colouring of Messages", 60, () -> ui(() -> styledRanges(ed) >= 3));

        // Reopening the file finds the same editor.
        assertTrue(ui(() -> open("Sources/Comp/Resources/UK/Messages") == ed), "the same editor");

        // Insert NUL, then save.
        ui(() -> {
            ed.selectAndReveal(doc.getLength(), 0);
            try {
                doc.replace(doc.getLength(), 0, "Bye:Goodbye");
                ed.selectAndReveal(doc.getLength(), 0);
                PlatformUI.getWorkbench().getService(org.eclipse.ui.handlers.IHandlerService.class)
                        .executeCommand("com.piccolosystems.riscos.lsp.insertNul", null);
                doc.replace(doc.getLength(), 0, "\n");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            ed.doSave(new NullProgressMonitor());
        });
        byte[] saved = Files.readAllBytes(riscos.resolve("Sources/Comp/Resources/UK/Messages"));
        assertEquals(MESSAGES + "Bye:Goodbye\0\n", new String(saved, StandardCharsets.ISO_8859_1), "saved with NULs");
        assertTrue(!ui(() -> ed.isDirty()), "not dirty after saving");
        IFile f = file("Sources/Comp/Resources/UK/Messages");
        waitFor("the workspace to see the change", 30, () -> f.isSynchronized(IResource.DEPTH_ZERO));
    }

    @Test
    @Order(12)
    void tokenisedBasic() throws Exception {
        assertEquals("com.piccolosystems.riscos.bbcbasic", file("Sources/Comp/Prog2,ffb").getContentDescription()
                .getContentType().getId(), "content type of Prog2,ffb");
        ITextEditor ed = open("Sources/Comp/Prog2,ffb");
        assertTrue(ConvertedFiles.isConverted(ui(() -> ed.getEditorInput())), "opened converted");
        IDocument doc = document(ed);
        assertEquals("PROCa\nEND\nDEF PROCa\nPRINT \"hi\"\nENDPROC\n", ui(() -> doc.get()), "shown as text");
        waitFor("colouring of Prog2,ffb", 60, () -> ui(() -> styledRanges(ed) >= 3));
        List<Location> locs = definitions(doc, file("Sources/Comp/Prog2,ffb"), 0, 2);
        assertEquals(1, locs.size(), "the definition of PROCa");
        assertEquals(2, locs.get(0).getRange().getStart().getLine(), "is its DEF line");

        // Add a line after PRINT "hi", and save: the other lines keep their numbers.
        ui(() -> {
            try {
                doc.replace(doc.get().indexOf("ENDPROC"), 0, "PRINT \"there\"\n");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            ed.doSave(new NullProgressMonitor());
        });
        byte[] expected = bytes(0x0D,
                0x00, 0x0A, 0x06, 0xF2, 'a', 0x0D,
                0x00, 0x14, 0x05, 0xE0, 0x0D,
                0x00, 0x1E, 0x08, 0xDD, ' ', 0xF2, 'a', 0x0D,
                0x00, 0x28, 0x0A, 0xF1, ' ', '"', 'h', 'i', '"', 0x0D,
                0x00, 0x2D, 0x0D, 0xF1, ' ', '"', 't', 'h', 'e', 'r', 'e', '"', 0x0D,
                0x00, 0x32, 0x05, 0xE1, 0x0D,
                0xFF);
        byte[] saved = Files.readAllBytes(riscos.resolve("Sources/Comp/Prog2,ffb"));
        assertEquals(java.util.HexFormat.of().formatHex(expected), java.util.HexFormat.of().formatHex(saved),
                "saved tokenised, the new line numbered 45");
        assertTrue(!ui(() -> ed.isDirty()), "not dirty after saving");
    }

    @Test
    @Order(13)
    void cIndexing() throws Exception {
        assertTrue(comp.hasNature(org.eclipse.cdt.core.CProjectNature.C_NATURE_ID), "Comp is a C project");
        IFile main = file("Sources/Comp/objs/main.c");
        var info = org.eclipse.cdt.core.CCorePlugin.getDefault().getScannerInfoProvider(comp).getScannerInformation(main);
        assertEquals("1", info.getDefinedSymbols().get("BUILD_Test"), "the makefile's macro: " + info.getDefinedSymbols());
        assertTrue(info.getDefinedSymbols().containsKey("__APCS_32"), "Norcroft's APCS macro");
        assertTrue(List.of(info.getIncludePaths()).contains(riscos.resolve("Sources/Comp/objs").toString()),
                "-I. is objs/: " + List.of(info.getIncludePaths()));
        assertTrue(info instanceof org.eclipse.cdt.core.parser.IExtendedScannerInfo x
                && List.of(x.getIncludeFiles()).stream().anyMatch(f -> f.endsWith("norcroft-compat.h")),
                "norcroft-compat.h is included first");
        assertTrue(indexed("test_only") && !indexed("other_only"), "indexed as the Test configuration");
        try {
            TreeImport.switchTo(tree, "Other");
            waitFor("indexing as the Other configuration", 120, () -> indexed("other_only") && !indexed("test_only"));
        } finally {
            TreeImport.switchTo(tree, "Test");
        }
        waitFor("indexing as the Test configuration again", 120, () -> indexed("test_only"));
        // Saved with the project, for when Eclipse starts again: CDT saves only a list that isn't the default.
        Path saved = riscos.resolve("Sources/Comp/.settings/language.settings.xml");
        assertTrue(Files.isRegularFile(saved) && Files.readString(saved).contains("com.piccolosystems.riscos.cdt.languageSettings"),
                "the provider is saved in " + saved);
    }

    @Test
    @Order(14)
    void fromCToAssembler() throws Exception {
        open("Sources/Comp/objs/Main.s");     // the language server is running
        IFile main = file("Sources/Comp/objs/main.c");
        java.net.URI mu = main.getLocationURI();
        List<Location> locs = new ArrayList<>();
        waitFor("the assembler definition of asm_fn", 120, () -> {
            locs.clear();
            locs.addAll(RiscOSLanguageClient.asmLocations(RiscOSLanguageClient.ASM_DEFINITIONS, "asm_fn", mu, 10_000));
            return !locs.isEmpty();
        });
        assertEquals(1, locs.size(), "one definition: " + locs);
        assertTrue(locs.get(0).getUri().endsWith("/objs/Main.s"), "in Main.s: " + locs.get(0).getUri());
        assertEquals(List.of(MAIN.split("\n")).indexOf("asm_fn  MOV     pc, lr"), locs.get(0).getRange().getStart().getLine(), "at its label");

        // Ctrl+click in the C editor.
        ITextEditor ed = open("Sources/Comp/objs/main.c");
        var links = ui(() -> {
            ITextViewer v = (ITextViewer) ed.getAdapter(ITextOperationTarget.class);
            AsmLinks d = new AsmLinks();
            d.setContext(ed);
            int at = v.getDocument().get().indexOf("asm_fn()") + 2;
            return d.detectHyperlinks(v, new org.eclipse.jface.text.Region(at, 0), true);
        });
        assertNotNull(links, "a link from asm_fn in main.c");
        assertEquals("Open Assembler Definition", links[0].getHyperlinkText(), "its text");

        // Find References in Assembler.
        AsmCommands.ReferencesQuery q = new AsmCommands.ReferencesQuery("asm_fn", mu);
        q.run(new NullProgressMonitor());
        assertEquals(2, ((org.eclipse.search.ui.text.AbstractTextSearchResult) q.getSearchResult()).getMatchCount(),
                "the EXPORT and the label");
    }

    /** Whether CDT's index of Comp has a function of this name, once the indexer is idle. */
    static boolean indexed(String name) {
        try {
            var im = org.eclipse.cdt.core.CCorePlugin.getIndexManager();
            im.joinIndexer(60_000, new NullProgressMonitor());
            var index = im.getIndex(org.eclipse.cdt.core.model.CoreModel.getDefault().create(comp));
            index.acquireReadLock();
            try {
                return index.findBindings(name.toCharArray(), org.eclipse.cdt.core.index.IndexFilter.ALL,
                        new NullProgressMonitor()).length > 0;
            } finally {
                index.releaseReadLock();
            }
        } catch (CoreException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /** Where CDT's index has a name's definitions, and which files it has indexed. */
    static String definitionsOf(String name) {
        try {
            var index = org.eclipse.cdt.core.CCorePlugin.getIndexManager()
                    .getIndex(org.eclipse.cdt.core.model.CoreModel.getDefault().create(comp));
            index.acquireReadLock();
            try {
                List<String> out = new ArrayList<>();
                for (var b : index.findBindings(name.toCharArray(), org.eclipse.cdt.core.index.IndexFilter.ALL, new NullProgressMonitor()))
                    for (var n : index.findNames(b, org.eclipse.cdt.core.index.IIndex.FIND_ALL_OCCURRENCES))
                        out.add((n.isDefinition() ? "def " : n.isDeclaration() ? "decl " : "ref ") + n.getFile().getLocation());
                List<String> files = new ArrayList<>();
                for (var f : index.getAllFiles()) files.add(String.valueOf(f.getLocation()));
                return out + " files " + files;
            } finally {
                index.releaseReadLock();
            }
        } catch (Exception e) {
            return e.toString();
        }
    }

    static boolean bogusMarker(IFile f) {
        try {
            for (IMarker m : f.findMarkers(null, true, IResource.DEPTH_ZERO))
                if (String.valueOf(m.getAttribute(IMarker.MESSAGE, "")).contains("Bogus")) return true;
        } catch (CoreException e) {
            // not yet
        }
        return false;
    }

    // ---------------------------------------------------------------- helpers

    static ITextEditor open(String path) {
        return ui(() -> {
            IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            try {
                IEditorPart part = IDE.openEditor(page, file(path), true);
                ITextEditor ed = part.getAdapter(ITextEditor.class);
                assertNotNull(ed, path + " didn't open in a text editor: " + part.getClass());
                return ed;
            } catch (CoreException e) {
                throw new RuntimeException(e);
            }
        });
    }

    static IDocument document(ITextEditor ed) {
        return ui(() -> ed.getDocumentProvider().getDocument(ed.getEditorInput()));
    }

    static String uri(IFile f) {
        return f.getLocationURI().toString();
    }

    static List<Location> definitions(IDocument doc, IFile f, int line, int col) throws Exception {
        long end = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < end) {
            Optional<Either<List<? extends Location>, List<? extends LocationLink>>> r = LanguageServers.forDocument(doc)
                    .computeFirst(ls -> ls.getTextDocumentService().definition(
                            new DefinitionParams(new TextDocumentIdentifier(uri(f)), new Position(line, col))))
                    .get(60, TimeUnit.SECONDS);
            List<Location> out = new ArrayList<>();
            if (r.isPresent() && r.get() != null) {
                if (r.get().isLeft()) out.addAll(r.get().getLeft());
                else for (LocationLink l : r.get().getRight())
                    out.add(new Location(l.getTargetUri(), l.getTargetSelectionRange()));
            }
            if (!out.isEmpty()) return out;
            Thread.sleep(1000);   // still indexing
        }
        return List.of();
    }

    static int styledRanges(ITextEditor ed) {
        StyledText t = text(ed);
        int n = 0;
        for (StyleRange r : t.getStyleRanges()) if (r.foreground != null) n++;
        return n;
    }

    static int styledOnLine(ITextEditor ed, int line) {
        StyledText t = text(ed);
        int start = t.getOffsetAtLine(line), len = t.getLine(line).length();
        int n = 0;
        for (StyleRange r : t.getStyleRanges(start, len)) if (r.foreground != null) n++;
        return n;
    }

    static StyledText text(ITextEditor ed) {
        ITextViewer v = (ITextViewer) ed.getAdapter(ITextOperationTarget.class);
        return v.getTextWidget();
    }

    @Test
    @Order(15)
    void outline() throws Exception {
        ui(() -> {
            try {
                PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().showView("org.eclipse.ui.views.ContentOutline");
            } catch (org.eclipse.ui.PartInitException e) {
                throw new RuntimeException(e);
            }
        });
        open("Sources/Comp/objs/Main.s");
        java.util.function.Supplier<org.eclipse.jface.viewers.StructuredViewer> viewer = () -> ui(() -> {
            var o = (org.eclipse.ui.views.contentoutline.ContentOutline) PlatformUI.getWorkbench().getActiveWorkbenchWindow()
                    .getActivePage().findView("org.eclipse.ui.views.ContentOutline");
            return o == null ? null : com.piccolosystems.riscos.lsp.OutlineWatchdog.viewerOf(o.getCurrentPage());
        });
        BooleanSupplier symbols = () -> ui(() -> {
            var v = viewer.get();
            if (v == null || !(v.getControl() instanceof org.eclipse.swt.widgets.Tree t)) return false;
            for (var i : t.getItems()) if (i.getText().contains("Start")) return true;
            return false;
        });
        waitFor("the Outline of objs/Main.s", 60, symbols);
        // Asking again gets the same symbols, so LSP4E leaves "Computing symbols…" showing:
        // the watchdog draws the Outline again.
        ui(() -> {
            var v = viewer.get();
            v.setInput(v.getInput());
        });
        waitFor("the Outline of objs/Main.s, unstuck", 60, symbols);
    }

    @Test
    @Order(16)
    @SuppressWarnings("restriction")
    void fileSearch() throws Exception {
        waitFor("s/Main to be marked derived", 30, () -> file("Sources/Comp/s/Main").isDerived());
        // The whole workspace, as Ctrl+H searches by default: the tree's project holds Comp's files too.
        var scope = org.eclipse.search.ui.text.FileTextSearchScope.newWorkspaceScope(new String[] {"*"}, false);
        var q = new org.eclipse.search.internal.ui.text.FileSearchQuery("asm_fn", false, true, scope);
        q.run(new NullProgressMonitor());
        var result = (org.eclipse.search.ui.text.AbstractTextSearchResult) q.getSearchResult();
        List<String> found = new ArrayList<>();
        for (Object e : result.getElements()) found.add(((IFile) e).getFullPath().toString());
        java.util.Collections.sort(found);
        assertEquals(List.of("/Comp/objs/Main.s", "/Comp/objs/main.c"), found, "files found by File Search");
        for (Object e : result.getElements()) {
            IFile f = (IFile) e;
            var m = result.getMatches(e)[0];
            // What the Search view does with a match: open the least nested file at its location,
            // which Eclipse's lookup finds by following the link, so the real file.
            IFile at = java.util.Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(f.getLocationURI()))
                    .min(java.util.Comparator.comparingInt(x -> x.getFullPath().segmentCount())).orElse(f);
            // (Not on Windows, where Eclipse's lookup doesn't follow links, and so finds the name shown.)
            if (!System.getProperty("os.name").startsWith("Windows"))
                assertTrue(!at.equals(f), "Eclipse's lookup of " + f.getFullPath() + " follows the link: " + at.getFullPath());
            ui(() -> {
                try {
                    new org.eclipse.search.internal.ui.text.EditorOpener().openAndSelect(
                            PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), at, m.getOffset(),
                            m.getLength(), true);
                } catch (org.eclipse.ui.PartInitException x) {
                    throw new RuntimeException(x);
                }
            });
            // Reopened by the name shown, at the match.
            waitFor("the editor for the match in " + f.getName() + " (opened as " + at.getFullPath() + ")", 30, () -> ui(() -> {
                IEditorPart ed = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
                IFile opened = ed == null ? null : ed.getEditorInput().getAdapter(IFile.class);
                return opened != null && opened.equals(f) && ed instanceof ITextEditor te
                        && te.getSelectionProvider().getSelection() instanceof org.eclipse.jface.text.ITextSelection s
                        && s.getOffset() == m.getOffset();
            }));
            assertTrue(ui(() -> java.util.Arrays.stream(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
                    .getEditorReferences()).noneMatch(r -> r.getName().equals(at.getName()) && !at.equals(f))),
                    "the editor on " + at.getFullPath() + " was closed");
        }
    }

    /** The file of the active editor, waiting for it to be {@code want} (another editor may be opened first). */
    static void waitForActive(String what, IFile want, int offset) {
        waitFor(what, 60, () -> ui(() -> {
            IEditorPart ed = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
            IFile f = ed == null ? null : ed.getEditorInput().getAdapter(IFile.class);
            // At the line of offset.
            return want.equals(f) && (offset < 0 || ed instanceof ITextEditor te
                    && te.getSelectionProvider().getSelection() instanceof org.eclipse.jface.text.ITextSelection s
                    && lineOf(te, s.getOffset()) == lineOf(te, offset));
        }), () -> ui(() -> {
            IEditorPart ed = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
            return "active: " + (ed == null ? null : ed.getEditorInput().getAdapter(IFile.class) + " in " + ed.getSite().getId());
        }));
    }

    static int lineOf(ITextEditor ed, int offset) {
        try {
            return document(ed).getLineOfOffset(offset);
        } catch (org.eclipse.jface.text.BadLocationException e) {
            return -1;
        }
    }

    @Test
    @Order(17)
    @SuppressWarnings("restriction")
    void openDeclarationInC() throws Exception {
        waitFor("util_fn to be indexed", 120, () -> indexed("util_fn"));
        assertTrue(!definitionsOf("util_fn").contains("/Comp/c/"), "CDT indexes the real names too: " + definitionsOf("util_fn"));
        open("Sources/Comp/objs/main.c");
        // How CDT's navigation (F3, Call Hierarchy, Open Element...) ends: it opens the location
        // the index gives, looking the file up by location, as the Search view does.
        IPath location = IPath.fromPath(riscos.resolve("Sources/Comp/objs/util.c"));
        ui(() -> {
            try {
                org.eclipse.cdt.internal.ui.util.EditorUtility.openInEditor(location, null);
            } catch (org.eclipse.ui.PartInitException e) {
                throw new RuntimeException(e);
            }
        });
        waitForActive("CDT's navigation to open objs/util.c", file("Sources/Comp/objs/util.c"), -1);
        assertEquals("org.eclipse.cdt.ui.editor.CEditor", ui(() -> PlatformUI.getWorkbench().getActiveWorkbenchWindow()
                .getActivePage().getActiveEditor().getSite().getId()), "the editor for objs/util.c");
    }

    @Test
    @Order(18)
    void openFromAMarker() throws Exception {
        IFile helper = file("Sources/Comp/objs/Helper.s");
        open("Sources/Comp/objs/Main.s");
        waitFor("the unknown-operation marker on objs/Helper.s", 90, () -> bogusMarker(helper));
        IMarker marker = null;
        for (IMarker m : helper.findMarkers(null, true, IResource.DEPTH_ZERO))
            if (String.valueOf(m.getAttribute(IMarker.MESSAGE, "")).contains("Bogus")) marker = m;
        IMarker found = marker;
        ui(() -> {
            try {
                // What the Problems view does on a double-click.
                IDE.openEditor(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), found);
            } catch (org.eclipse.ui.PartInitException e) {
                throw new RuntimeException(e);
            }
        });
        waitForActive("the editor for the marker on objs/Helper.s", helper, HELPER.indexOf("Bogus"));
    }

    @Test
    @Order(19)
    void openAWorkspaceSymbol() throws Exception {
        ITextEditor ed = open("Sources/Comp/objs/Main.s");
        IDocument doc = document(ed);
        List<org.eclipse.lsp4j.WorkspaceSymbol> found = new ArrayList<>();
        waitFor("the workspace symbol Helper", 60, () -> {
            try {
                var r = LanguageServers.forDocument(doc).computeFirst(ls -> ls.getWorkspaceService().symbol(
                        new org.eclipse.lsp4j.WorkspaceSymbolParams("Helper"))).get(30, TimeUnit.SECONDS);
                found.clear();
                if (r.isPresent() && r.get() != null) {
                    if (r.get().isRight()) found.addAll(r.get().getRight());
                    else for (var si : r.get().getLeft())
                        found.add(new org.eclipse.lsp4j.WorkspaceSymbol(si.getName(), si.getKind(), Either.forLeft(si.getLocation())));
                }
                found.removeIf(w -> !w.getName().equals("Helper"));
                return !found.isEmpty();
            } catch (Exception e) {
                return false;
            }
        });
        Location loc = found.get(0).getLocation().getLeft();
        // What Go to Symbol in Workspace (Ctrl+Shift+T) does with the one picked.
        ui(() -> LSPEclipseUtils.openInEditor(loc));
        waitForActive("the editor for the symbol Helper", file("Sources/Comp/objs/Helper.s"), HELPER.indexOf("Helper  ROUT"));
    }

    @Test
    @Order(20)
    void variableAssignments() throws Exception {
        ITextEditor ed = open("Sources/Comp/objs/Main.s");
        var viewer = ui(() -> LSPEclipseUtils.getTextViewer(ed));
        // The editor's Ctrl+hover links are the RISC OS ones (and URLs'), not LSP4E's.
        List<String> installed = ui(() -> {
            try {
                var f = org.eclipse.jface.text.TextViewer.class.getDeclaredField("fHyperlinkDetectors");
                f.setAccessible(true);
                List<String> names = new ArrayList<>();
                for (Object d : (Object[]) f.get(viewer)) names.add(d.getClass().getSimpleName());
                return names;
            } catch (ReflectiveOperationException e) {
                return List.of(e.toString());
            }
        });
        assertEquals(List.of("RiscOSLinks", "URLHyperlinkDetector"), installed, "the editor's hyperlink detectors");
        int at = MAIN.indexOf("GBLA    Count") + "GBLA    ".length() + 1;
        List<String> texts = new ArrayList<>();
        waitFor("links on Count", 60, () -> ui(() -> {
            var links = new com.piccolosystems.riscos.lsp.RiscOSLinks().detectHyperlinks(viewer,
                    new org.eclipse.jface.text.Region(at, 0), true);
            texts.clear();
            if (links != null) for (var l : links) texts.add(l.getHyperlinkText());
            return texts.size() == 3;
        }), () -> String.valueOf(texts));
        int decl = List.of(MAIN.split("\n")).indexOf("        GBLA    Count") + 1;
        assertEquals(List.of("Open Declaration - Main.s:" + decl + " - Comp/objs",
                "Open Assignment - Main.s:" + (decl + 1) + " - Comp/objs",
                "Open Assignment - Main.s:" + (decl + 2) + " - Comp/objs"), texts, "the links, labelled alike");
        // A label's definition, labelled the same way, in a header outside the components.
        int vat = MAIN.indexOf("#Value") + 2;
        waitFor("a link on Value", 60, () -> ui(() -> {
            var links = new com.piccolosystems.riscos.lsp.RiscOSLinks().detectHyperlinks(viewer,
                    new org.eclipse.jface.text.Region(vat, 0), true);
            texts.clear();
            if (links != null) for (var l : links) texts.add(l.getHyperlinkText());
            return !texts.isEmpty();
        }), () -> String.valueOf(texts));
        assertEquals(List.of("Open Definition - Defs:2 - Tree/Export/Hdr"), texts, "the link on Value");
    }

    // ------------------------------------------------------------------ a second tree

    @Test
    @Order(20)
    void aSecondTree() throws Exception {
        // Another tree: a component with the same name as the first tree's, one whose
        // directory is named differently, and a library of headers only.
        Path riscos2 = tmp.resolve("Tree2/RiscOS");
        write(riscos2.resolve("Env/Test.sh"), "export BUILD=Test\nexport INSTALLDIR=" + bashPath(riscos2.resolve("Install"))
                + "\nexport HDR_PATH=" + bashPath(riscos2.resolve("Export/Hdr")) + "/\n");
        write(riscos2.resolve("BuildSys/ModuleDB"), "# name type directory\nComp ASM Sources.Comp\n"
                + "Named ASM Sources.Leaf\nHdrs C Sources.Lib.headers\n");
        write(riscos2.resolve("BuildSys/Components/Test"), "Comp\nNamed\nHdrs -type EXP\n");
        String asm = "\trom:\n\tarmasm -apcs 3/32bit/fpe3 -cpu 6Z -o o.Main Main.s\n";
        write(riscos2.resolve("Sources/Comp/Makefile"), asm.substring(1));
        write(riscos2.resolve("Sources/Leaf/Makefile"), asm.substring(1));
        write(riscos2.resolve("Sources/Lib/headers/Makefile"), "export:\n\t@true\n");
        write(riscos2.resolve("Sources/Lib/headers/h/thing"), "#define THING 1\n");
        write(riscos2.resolve("Export/Hdr/Defs"), "; The second tree's\nValue           *       43\n");
        for (String c : List.of("Comp", "Leaf")) {
            write(riscos2.resolve("Sources/" + c + "/s/Main"), MAIN);
            Files.createDirectories(riscos2.resolve("Sources/" + c + "/objs"));
            Files.createSymbolicLink(riscos2.resolve("Sources/" + c + "/objs/Main.s"), Path.of("../s/Main"));
        }
        BuildTree t2 = new BuildTree();
        t2.riscos = riscos2;
        t2.name = ImportWizard.suggestedName(riscos2);
        assertEquals("Tree2", t2.name, "the suggested name");
        t2.configurations.add("Test");
        t2.active = "Test";
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        try {
            TreeImport.importTree(t2, new NullProgressMonitor());
            assertEquals(List.of("Tree", "Tree2"), BuildTree.all().stream().map(t -> t.name).toList(), "both trees are remembered");
            assertEquals(riscos.resolve("Sources/Comp"), root.getProject("Comp").getLocation().toPath(), "the first tree's Comp keeps its name");
            IProject comp2 = root.getProject("Comp (Tree2)");
            assertTrue(comp2.exists(), "the second tree's Comp is told apart by the tree's name");
            assertEquals(riscos2.resolve("Sources/Comp"), comp2.getLocation().toPath(), "where it is");
            IProject leaf = root.getProject("Leaf");
            assertTrue(leaf.exists(), "a project is named after its directory, not the component (Named)");
            IProject headers = root.getProject("headers");
            assertTrue(headers.exists(), "a library of headers only has a project too");
            assertEquals(riscos2.resolve("Sources/Lib/headers"), headers.getLocation().toPath(), "where it is");
            assertEquals("Tree2", BuildTree.of(headers).name, "the project belongs to the second tree");
            assertEquals("Tree", BuildTree.of(comp).name, "and Comp to the first");

            String settings = InstanceScope.INSTANCE.getNode(LSP_BUNDLE).get("settings", "");
            assertEquals(tree.settingsDir("Test") + File.pathSeparator + t2.settingsDir("Test"), settings,
                    "the language server is given both trees' settings");

            // Each tree's files go to their own tree's definitions.
            IFile main2 = leaf.getFile("objs/Main.s");
            ITextEditor ed2 = ui(() -> {
                try {
                    return IDE.openEditor(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), main2, true)
                            .getAdapter(ITextEditor.class);
                } catch (CoreException e) {
                    throw new RuntimeException(e);
                }
            });
            List<Location> d2 = definitions(document(ed2), main2, 3, MAIN.split("\n")[3].indexOf("Value") + 1);
            assertTrue(!d2.isEmpty(), "no definition of Value in the second tree");
            assertEquals("/Tree2/Export/Hdr/Defs", LSPEclipseUtils.findResourceFor(d2.get(0).getUri()).getFullPath().toString(),
                    "the second tree's Value");
            ITextEditor ed1 = open("Sources/Comp/objs/Main.s");
            List<Location> d1 = definitions(document(ed1), file("Sources/Comp/objs/Main.s"), 3, MAIN.split("\n")[3].indexOf("Value") + 1);
            assertTrue(!d1.isEmpty(), "no definition of Value in the first tree");
            assertEquals("/Tree/Export/Hdr/Defs", LSPEclipseUtils.findResourceFor(d1.get(0).getUri()).getFullPath().toString(),
                    "the first tree's Value, still");
        } finally {
            ui(() -> PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().closeAllEditors(false));
            for (IProject p : root.getProjects()) {
                var loc = p.getLocation();
                if (loc != null && loc.toPath().startsWith(riscos2)) p.delete(false, true, null);
            }
            // As it was for the other tests, which may run in any order.
            t2.forget();
            TreeImport.switchTo(tree, tree.active);
        }
    }

    static void waitFor(String what, int seconds, BooleanSupplier ok) {
        waitFor(what, seconds, ok, () -> "");
    }

    /** As above, adding {@code explain} to the failure message. */
    static void waitFor(String what, int seconds, BooleanSupplier ok, Supplier<String> explain) {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            if (ok.getAsBoolean()) return;
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("timed out after " + seconds + " s waiting for " + what + ". " + explain.get());
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
