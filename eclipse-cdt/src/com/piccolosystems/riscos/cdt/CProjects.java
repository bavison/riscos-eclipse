package com.piccolosystems.riscos.cdt;

import java.util.List;
import java.util.Map;

import org.eclipse.cdt.core.CCProjectNature;
import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.CProjectNature;
import org.eclipse.cdt.core.language.settings.providers.ILanguageSettingsProvider;
import org.eclipse.cdt.core.language.settings.providers.ILanguageSettingsProvidersKeeper;
import org.eclipse.cdt.core.language.settings.providers.LanguageSettingsManager;
import org.eclipse.cdt.core.language.settings.providers.ScannerDiscoveryLegacySupport;
import org.eclipse.cdt.core.model.CoreModel;
import org.eclipse.cdt.core.model.ICProject;
import org.eclipse.cdt.core.settings.model.ICConfigurationDescription;
import org.eclipse.cdt.core.settings.model.ICProjectDescription;
import org.eclipse.cdt.core.settings.model.ICProjectDescriptionManager;
import org.eclipse.cdt.core.settings.model.extension.CConfigurationData;
import org.eclipse.cdt.managedbuilder.core.IBuilder;
import org.eclipse.cdt.managedbuilder.core.ManagedBuildManager;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;

import com.piccolosystems.riscos.lsp.BuildTree;
import com.piccolosystems.riscos.lsp.TreeParticipant;

/**
 * Makes each component with C files a C/C++ project, indexed by CDT with the
 * settings of the tree's active configuration ({@link RiscOSLanguageSettings}),
 * and indexes them again when the configuration changes.
 */
public class CProjects implements TreeParticipant {
    /** The CDT configuration's name: there is one, following the tree's active configuration. */
    public static final String CONFIGURATION = "RISC OS";

    @Override
    public void imported(BuildTree tree, Map<String, IProject> projects, IProgressMonitor monitor) throws CoreException {
        RiscOSLanguageSettings.forget();
        // The active configuration's settings: the language server isn't pointed at them until after this.
        CompileCommands c = CompileCommands.load(tree.settingsDir(tree.active));
        SubMonitor sub = SubMonitor.convert(monitor, "Setting up C projects", projects.size());
        IWorkspace ws = ResourcesPlugin.getWorkspace();
        for (Map.Entry<String, IProject> e : projects.entrySet()) {
            IProject p = e.getValue();
            if (!p.isAccessible() || !c.components.containsKey(e.getKey())) {
                sub.worked(1);
                continue;
            }
            ws.run(m -> makeCProject(p, m), ws.getRoot(), IWorkspace.AVOID_UPDATE, sub.split(1));   // CDT needs the root
        }
    }

    /**
     * Gives a project the C and C++ natures and one configuration whose only
     * settings are ours: a makefile project, as CDT's "Makefile Project with
     * Existing Code" makes one, with no toolchain and managed building off.
     */
    @SuppressWarnings("restriction")
    static void makeCProject(IProject p, IProgressMonitor monitor) throws CoreException {
        SubMonitor sub = SubMonitor.convert(monitor, 4);
        CCorePlugin cdt = CCorePlugin.getDefault();
        if (p.hasNature(CProjectNature.C_NATURE_ID)) {
            ICProjectDescription des = cdt.getProjectDescription(p, false);
            if (des != null && des.getConfigurations().length > 0 && !ours(des)) return;  // someone's own C project
        }
        CProjectNature.addCNature(p, sub.split(1));
        CCProjectNature.addCCNature(p, sub.split(1));
        ICProjectDescriptionManager pdm = CoreModel.getDefault().getProjectDescriptionManager();
        ICProjectDescription des = pdm.createProjectDescription(p, false);
        org.eclipse.cdt.managedbuilder.internal.core.ManagedBuildInfo info = ManagedBuildManager.createBuildInfo(p);
        org.eclipse.cdt.managedbuilder.internal.core.ManagedProject mp =
                new org.eclipse.cdt.managedbuilder.internal.core.ManagedProject(des);
        info.setManagedProject(mp);
        org.eclipse.cdt.managedbuilder.internal.core.Configuration config =
                new org.eclipse.cdt.managedbuilder.internal.core.Configuration(mp, null,
                        ManagedBuildManager.calculateChildId("0", null), CONFIGURATION);
        IBuilder builder = config.getEditableBuilder();
        builder.setManagedBuildOn(false);
        CConfigurationData data = config.getConfigurationData();
        ICConfigurationDescription cfg = des.createConfiguration(ManagedBuildManager.CFG_DATA_PROVIDER_ID, data);
        des.setActiveConfiguration(cfg);
        ILanguageSettingsProvider provider = LanguageSettingsManager.getWorkspaceProvider(RiscOSLanguageSettings.ID);
        ((ILanguageSettingsProvidersKeeper) cfg).setLanguageSettingProviders(List.of(provider));
        // CDT saves the list (in .settings/language.settings.xml) only if it differs from the
        // default, which it doesn't remember: on loading, the default comes from the toolchain,
        // and there is none. So the default differs from the list, but has ours in it too, as
        // CDT also goes back to the default now and then while the project is open.
        ((ILanguageSettingsProvidersKeeper) cfg).setDefaultLanguageSettingsProvidersIds(
                new String[] {RiscOSLanguageSettings.ID, "org.eclipse.cdt.ui.UserLanguageSettingsProvider"});
        ScannerDiscoveryLegacySupport.setLanguageSettingsProvidersFunctionalityEnabled(p, true);
        pdm.setProjectDescription(p, des, true, sub.split(2));
    }

    /** Whether a project's CDT description is one this made. */
    static boolean ours(ICProjectDescription des) {
        return des.getConfigurationByName(CONFIGURATION) != null;
    }

    private static String nameOf(BuildTree t) {
        return t == null ? null : t.name;
    }

    @Override
    public void activated(BuildTree tree) {
        RiscOSLanguageSettings.forget();
        Job.create("Indexing C for " + tree.active, m -> {
            for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                try {
                    if (!p.isAccessible() || !p.hasNature(CProjectNature.C_NATURE_ID)) continue;
                    if (!tree.name.equals(nameOf(BuildTree.of(p)))) continue;     // another tree
                    ICProjectDescription des = CCorePlugin.getDefault().getProjectDescription(p, false);
                    if (des == null || !ours(des)) continue;
                    ICProject cp = CoreModel.getDefault().create(p);
                    CCorePlugin.getIndexManager().reindex(cp);
                } catch (CoreException e) {
                    Platform.getLog(CProjects.class).log(e.getStatus());
                }
            }
        }).schedule();
    }
}
