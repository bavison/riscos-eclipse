package com.piccolosystems.riscos.asmindex.cond;

import com.piccolosystems.riscos.asmindex.sym.Site;

/** An entry on the structure stack: IF, WHILE, GET or macro. */
public final class Frame {
    public enum Type { IF, WHILE, GET, MACRO }

    public final Type type;
    /** The state outside this construct. */
    public final State outer;
    /** For IF: whether an earlier clause was taken (YES, NO or MAYBE as TRUE, FALSE, UNKNOWN). */
    public Tri taken = Tri.FALSE;
    public boolean sawElse;
    /** For WHILE: where to jump back to, and whether to stop after one pass of the body. */
    public Object input;
    public int whileLine;
    public boolean once;
    /** Where the construct starts, for folding and diagnostics. */
    public final Site site;
    /** For IF: where the current clause (IF, ELIF or ELSE) starts, for folding each clause. */
    public Site clause;

    public Frame(Type type, State outer, Site site) {
        this.type = type;
        this.outer = outer;
        this.site = site;
    }

    /**
     * State of a clause whose own condition is {@code c}, given the clauses
     * before it; updates {@link #taken}.
     */
    public State clause(Tri c) {
        if (outer == State.SKIP) return State.SKIP;
        Tri eff;
        switch (taken) {
            case TRUE: eff = Tri.FALSE; break;
            case FALSE: eff = c; break;
            default: eff = c == Tri.FALSE ? Tri.FALSE : Tri.UNKNOWN;
        }
        if (taken == Tri.FALSE) taken = c;
        else if (taken == Tri.UNKNOWN && c == Tri.TRUE) taken = Tri.TRUE;
        if (eff == Tri.FALSE) return State.SKIP;
        if (eff == Tri.UNKNOWN) return State.UNKNOWN;
        return outer;
    }
}
