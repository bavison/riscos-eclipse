package com.piccolosystems.riscos.lsp;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;

/** What is done once the workbench has started. */
public class Startup implements IStartup {
    @Override
    public void earlyStartup() {
        // Link farms may have changed since Eclipse last ran (a build, a rescan elsewhere).
        LinkFarms.markDerived();
        Display d = PlatformUI.getWorkbench().getDisplay();
        // Search results and the like open by the names the Project Explorer shows.
        d.asyncExec(LinkRedirect::install);
        OutlineWatchdog.start(d);
    }
}
