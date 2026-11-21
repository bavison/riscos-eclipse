package com.piccolosystems.riscos.lsp;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.FileFieldEditor;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.RadioGroupFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

/**
 * Preferences › RISC OS: how files are shown and saved. The configuration indexed is
 * chosen by the import wizard and the RISC OS menu; the directory it uses is
 * on the Advanced page, with the other technical settings.
 */
public class Preferences extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {
    static final String BUNDLE = "com.piccolosystems.riscos.lsp";
    static final String JAVA = "java", HEAP = "heap", JAR = "jar", SETTINGS = "settings", SHOW_INFO = "showInfo";
    /** Which name of a link farm file to show: see {@link LinkFarms}. */
    static final String MIRRORS = "mirrors";
    static final String DEFAULT_HEAP = "1500m";
    /** Removing spaces and tabs from the ends of lines on saving: {@link #STRIP_OFF}, {@link #STRIP_EDITED} or {@link #STRIP_ALL}. */
    static final String STRIP_BASIC = "stripBasic", STRIP_ASM = "stripAssembler";
    static final String STRIP_OFF = "off", STRIP_EDITED = "edited", STRIP_ALL = "all";

    private static ScopedPreferenceStore store;

    /** The settings directories in the preference: one per build tree, separated by the path separator. */
    static java.util.List<String> settingsDirs(String pref) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String d : pref.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
            if (!d.isBlank()) out.add(d.strip());
        return out;
    }

    static synchronized IPreferenceStore store() {
        if (store == null) store = new ScopedPreferenceStore(InstanceScope.INSTANCE, BUNDLE);
        return store;
    }

    public Preferences() {
        super(GRID);
        setPreferenceStore(store());
        setDescription("Import a build tree with File › Import… › RISC OS › Build Tree, and choose its "
                + "active configuration from the RISC OS menu.");
    }

    @Override
    public void init(IWorkbench workbench) {
        // nothing
    }

    @Override
    protected void createFieldEditors() {
        addField(new BooleanFieldEditor(SHOW_INFO, "Show information diagnostics (e.g. macros hidden by instructions)",
                getFieldEditorParent()));
        addField(new RadioGroupFieldEditor(MIRRORS,
                "Files in objs/ link farms have two names. Use:", 1, new String[][] {
                    {"The link farm's names (objs/Wimp10.s, objs/main.c), as objasm and CDT use them", LinkFarms.LINKS},
                    {"The real files' names (s/Wimp10, c/main)", LinkFarms.REAL},
                    {"The link farm's names, but show the real files in the Project Explorer too", LinkFarms.BOTH}},
                getFieldEditorParent(), true));
        String[][] strip = {{"Leave them", STRIP_OFF}, {"In lines I've changed", STRIP_EDITED}, {"In all lines", STRIP_ALL}};
        addField(new RadioGroupFieldEditor(STRIP_BASIC,
                "On saving a BASIC file, remove spaces and tabs at the ends of lines (an unquoted DATA item keeps them):",
                3, strip, getFieldEditorParent(), true));
        addField(new RadioGroupFieldEditor(STRIP_ASM,
                "On saving an assembler file, remove spaces and tabs at the ends of lines:", 3, strip,
                getFieldEditorParent(), true));
    }

    @Override
    public boolean performOk() {
        String before = store().getString(MIRRORS);
        boolean ok = super.performOk();
        if (ok && !before.equals(store().getString(MIRRORS))) {
            LinkFarms.refreshViews();
            LinkFarms.markDerived();
            // The language server names files to match; it learns that when it starts.
            setMessage("Restart Eclipse for searches and markers to use the new names.", INFORMATION);
        }
        return ok;
    }

    /** Preferences › RISC OS › Advanced: how the language server is run. */
    public static class Advanced extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {
        public Advanced() {
            super(GRID);
            setPreferenceStore(store());
            setDescription("How the language server is run. The settings directories, one per build tree, "
                    + "are set by the import wizard and the RISC OS menu. Leave the other three blank to use "
                    + "the server included in the plugin, run by the Java that runs Eclipse.");
        }

        @Override
        public void init(IWorkbench workbench) {
            // nothing
        }

        @Override
        public boolean performOk() {
            String before = store().getString(SETTINGS);
            boolean ok = super.performOk();
            String after = store().getString(SETTINGS);
            if (ok && !before.equals(after) && !after.isBlank()) RiscOSLanguageClient.settingsChanged(after, false);
            return ok;
        }

        @Override
        protected void createFieldEditors() {
            Composite parent = getFieldEditorParent();
            for (StringFieldEditor f : new StringFieldEditor[] {
                    new StringFieldEditor(SETTINGS, "Settings directories (separated by "
                            + java.io.File.pathSeparator + "):", parent),
                    new StringFieldEditor(HEAP, "Maximum heap (e.g. 1500m):", parent),
                    new StringFieldEditor(JAVA, "Java 17+ command:", parent),
                    new FileFieldEditor(JAR, "objasm-index.jar:", true, parent)}) {
                addField(f);
                // Fill the page's width, but don't widen it: a long path would
                // otherwise make the page wider than the dialog, and scroll it.
                Text t = f.getTextControl(parent);
                if (t.getLayoutData() instanceof GridData g) {
                    GC gc = new GC(t);
                    try {
                        g.widthHint = 20 * gc.textExtent("X").x;
                    } finally {
                        gc.dispose();
                    }
                }
            }
        }
    }

    /** Default values. */
    public static class Defaults extends AbstractPreferenceInitializer {
        @Override
        public void initializeDefaultPreferences() {
            IPreferenceStore s = store();
            s.setDefault(JAVA, "");
            s.setDefault(HEAP, "");
            s.setDefault(JAR, "");
            s.setDefault(SETTINGS, "");
            s.setDefault(SHOW_INFO, false);
            s.setDefault(MIRRORS, LinkFarms.LINKS);
            s.setDefault(STRIP_BASIC, STRIP_EDITED);
            s.setDefault(STRIP_ASM, STRIP_EDITED);
        }
    }
}
