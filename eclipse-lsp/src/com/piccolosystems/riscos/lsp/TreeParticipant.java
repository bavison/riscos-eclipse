package com.piccolosystems.riscos.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;

/**
 * Something an optional bundle does as a build tree is imported and its
 * configurations are switched: the CDT bundle makes the component projects
 * C projects, for example. Contributed through the
 * {@code com.piccolosystems.riscos.lsp.treeParticipants} extension point.
 */
public interface TreeParticipant {
    /**
     * After the tree's projects have been created or rescanned: each
     * component's project, by component name. The workspace is not locked.
     */
    void imported(BuildTree tree, Map<String, IProject> projects, IProgressMonitor monitor) throws CoreException;

    /** After another configuration has become the active one, or the active one has been scanned again. */
    void activated(BuildTree tree);

    /** The participants contributed. */
    static List<TreeParticipant> all() {
        List<TreeParticipant> out = new ArrayList<>();
        for (IConfigurationElement e : Platform.getExtensionRegistry()
                .getConfigurationElementsFor(Preferences.BUNDLE + ".treeParticipants")) {
            try {
                if (e.createExecutableExtension("class") instanceof TreeParticipant p) out.add(p);
            } catch (CoreException x) {
                Platform.getLog(TreeParticipant.class).log(x.getStatus());
            }
        }
        return out;
    }
}
