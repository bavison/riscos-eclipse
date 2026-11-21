package com.piccolosystems.riscos.asmindex.cond;

/** A three-valued condition outcome. */
public enum Tri {
    TRUE, FALSE, UNKNOWN;

    public static Tri of(boolean b) { return b ? TRUE : FALSE; }
}
