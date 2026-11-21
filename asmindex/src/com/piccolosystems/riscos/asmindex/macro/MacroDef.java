package com.piccolosystems.riscos.asmindex.macro;

import com.piccolosystems.riscos.asmindex.source.LogicalLine;
import com.piccolosystems.riscos.asmindex.sym.Site;

import java.util.ArrayList;
import java.util.List;

/** A macro definition. Parameter 0 is the label, 1 the suffix, then the others. */
public final class MacroDef {
    public final String name;
    public final List<String> paramNames = new ArrayList<>();   // null entries for absent label/suffix
    public final List<String> defaults = new ArrayList<>();     // "" if none
    public final List<LogicalLine> body = new ArrayList<>();
    public final Site defSite;
    public boolean definedInPass2;
    public boolean conditional;
    public final List<Site> uses = new ArrayList<>();

    public MacroDef(String name, Site defSite) {
        this.name = name;
        this.defSite = defSite;
    }

    public String labelParam() { return paramNames.get(0); }
    public String suffixParam() { return paramNames.get(1); }

    /** The prototype as a signature, for hover and completion. */
    public String signature() {
        StringBuilder b = new StringBuilder();
        if (labelParam() != null) b.append('$').append(labelParam()).append(' ');
        b.append(name);
        if (suffixParam() != null) b.append('$').append(suffixParam());
        for (int i = 2; i < paramNames.size(); i++) {
            b.append(i == 2 ? " " : ", ").append('$').append(paramNames.get(i));
            if (!defaults.get(i).isEmpty()) b.append('=').append(defaults.get(i));
        }
        return b.toString();
    }
}
