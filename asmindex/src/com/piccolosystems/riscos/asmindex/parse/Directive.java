package com.piccolosystems.riscos.asmindex.parse;

import java.util.HashMap;
import java.util.Map;

/** objasm's directives. Names are upper case only. */
public enum Directive {
    IF("[", true), ELIF(null, true), ELSE("|", true), ENDIF("]", true),
    WHILE(null, true), WEND(null, true),
    MACRO(null, true), MEND(null, true), MEXIT(null, false),
    GET, INCLUDE, END(null, true), LNK,
    GBLA, GBLL, GBLS, LCLA, LCLL, LCLS, SETA, SETL, SETS,
    EQU("*"), RN, FN, SN, DN, QN, CN, CP, RLIST,
    MAP("^"), FIELD("#"), SPACE("%"), FILL,
    DCB("="), DCD("&"), DCDU, DCW, DCWU, DCQ, DCQU, DCDO, DCI, DCI_N("DCI.N"), DCI_W("DCI.W"),
    DCFS, DCFSU, DCFD, DCFDU, DCFH, DCFHU, DCFB, DCFBU,
    ALIGN, AREA, ROUT, LTORG,
    EXPORT, GLOBAL, EXPORTAS, IMPORT, EXTERN,
    STRONG, KEEP, REQUIRE, ALIAS, LEAF, LEADR,
    ASSERT, INFO("!"), OPT, TTL, SUBT,
    CODE16, THUMB, THUMBX, CODE32, ARM,
    DATA, ENTRY, NOFP, ORG, AOF, AOUT, BIN, INCBIN;

    /** Acted on even in skipped regions, to track nesting. */
    public final boolean structural;
    public final String oneChar;
    public final String spelling;

    Directive() { this(null, false); }
    Directive(String oneChar) { this(oneChar, false); }
    Directive(String oneChar, boolean structural) {
        this.oneChar = oneChar != null && oneChar.length() == 1 ? oneChar : null;
        this.spelling = oneChar != null && oneChar.length() > 1 ? oneChar : name();
        this.structural = structural;
    }

    private static final Map<String, Directive> BY_NAME = new HashMap<>();
    static {
        for (Directive d : values()) {
            BY_NAME.put(d.spelling, d);
            if (d.oneChar != null) BY_NAME.put(d.oneChar, d);
        }
    }

    /** The directive for an operation word, or null. */
    public static Directive lookup(String op) { return BY_NAME.get(op); }

    public boolean isData() {
        switch (this) {
            case DCB: case DCD: case DCDU: case DCW: case DCWU: case DCQ: case DCQU: case DCDO:
            case DCI: case DCI_N: case DCI_W: case DCFS: case DCFSU: case DCFD: case DCFDU:
            case DCFH: case DCFHU: case DCFB: case DCFBU:
                return true;
            default: return false;
        }
    }
}
