package com.piccolosystems.riscos.cdt;

import org.eclipse.cdt.core.model.ICElement;
import org.eclipse.cdt.ui.CUIPlugin;
import org.eclipse.cdt.ui.CElementSorter;
import org.eclipse.cdt.ui.PreferenceConstants;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;

/**
 * Sorts an {@code objs/} link farm in the Project Explorer as CDT sorts a
 * folder, with sources first, but counting the assembler (and CMHG and BASIC)
 * files among them: CDT only knows C's, so it lists {@code Main.s} with the
 * object and dependency files.
 */
public class ObjsSorter extends CElementSorter {
    private static final String SOURCE = "com.piccolosystems.riscos.source";

    private final boolean separate = CUIPlugin.getDefault().getPreferenceStore()
            .getBoolean(PreferenceConstants.CVIEW_SEPARATE_HEADER_AND_SOURCE);

    @Override
    public int category(Object element) {
        if (!(element instanceof ICElement)) {
            IResource r = element instanceof IResource res ? res
                    : element instanceof IAdaptable a ? a.getAdapter(IResource.class) : null;
            if (r instanceof IFile && isSource(r.getName())) return separate ? TRANSLATIONUNIT_SOURCE : TRANSLATIONUNITS;
        }
        return super.category(element);
    }

    static boolean isSource(String name) {
        IContentType source = Platform.getContentTypeManager().getContentType(SOURCE);
        if (source == null) return false;
        for (IContentType t : Platform.getContentTypeManager().findContentTypesFor(name))
            if (t.isKindOf(source)) return true;
        return false;
    }
}
