package com.piccolosystems.riscos.asmindex.sym;

import com.piccolosystems.riscos.asmindex.expr.Value;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A symbol table entry. */
public final class Symbol {
    public enum Kind {
        /** Entry created by a use, never defined. */
        UNDEFINED("undefined"),
        LABEL("label"),
        CONSTANT("constant"),
        ADDRESS("address constant"),
        REG_RELATIVE("register-relative constant"),
        FIELD("storage-map field"),
        REGISTER("register"),
        FP_REGISTER("FPA register"),
        EXT_REGISTER("VFP/NEON register"),
        COPROC_REGISTER("coprocessor register"),
        COPROCESSOR("coprocessor"),
        REGISTER_LIST("register list"),
        VAR_A("arithmetic variable"),
        VAR_L("logical variable"),
        VAR_S("string variable"),
        EXTERNAL("external");

        public final String description;
        Kind(String d) { description = d; }

        public boolean isVariable() { return this == VAR_A || this == VAR_L || this == VAR_S; }
    }

    public enum Status { UNDEFINED, PARTIAL, DEFINED }

    public final String name;
    public Kind kind = Kind.UNDEFINED;
    public Status status = Status.UNDEFINED;
    public Value value;
    public int size;                  // ?symbol
    public boolean predefined;
    public boolean exported;
    public boolean routine;           // label of a ROUT
    /** Defined in a region whose assembly the engine couldn't decide, or repeatedly with different values. */
    public boolean conditional;
    public boolean approximate;       // value depends on approximated sizes (Thumb, literal pools)
    public int definedInPass;         // 0 if not defined in the current pass yet
    public boolean declaredInPass2;   // variables: GBLx seen in pass 2
    public final List<Site> defs = new ArrayList<>();
    public final Set<Site> uses = new LinkedHashSet<>();
    /** For a variable: where SETA, SETL or SETS assigns it (these are among the uses too). */
    public final Set<Site> sets = new LinkedHashSet<>();
    public Site firstDef;             // first definition in any pass (for diagnostics)
    public String areaName;           // for labels

    public Symbol(String name) { this.name = name; }

    public boolean isDefined() { return status == Status.DEFINED; }

    @Override public String toString() { return name + " (" + kind.description + ") = " + value; }
}
