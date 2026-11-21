package com.piccolosystems.riscos.asmindex.loc;

import com.piccolosystems.riscos.asmindex.expr.Value;
import com.piccolosystems.riscos.asmindex.sym.Site;

import java.util.ArrayList;
import java.util.List;

/** A local label definition. */
public final class LocalLabel {
    public final int number;
    public final int scope;        // ROUT/AREA scope id
    public final String routine;   // routine name of the scope, or null
    public final int macroLevel;
    public final int seq;          // order among all local label definitions in the pass
    public final Value value;
    public final Site site;
    public final List<Site> refs = new ArrayList<>();

    public LocalLabel(int number, int scope, String routine, int macroLevel, int seq, Value value, Site site) {
        this.number = number; this.scope = scope; this.routine = routine;
        this.macroLevel = macroLevel; this.seq = seq; this.value = value; this.site = site;
    }
}
