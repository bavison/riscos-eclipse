package com.piccolosystems.riscos.asmindex.parse;

import com.piccolosystems.riscos.asmindex.source.Text;

/** The fields of a statement line. Ranges are offsets in {@link #text}; -1 if absent. */
public final class SplitLine {
    public enum LabelKind { NONE, SYMBOL, LOCAL, OTHER }

    public final Text text;
    public final boolean comment;           // whole line is comment or blank
    public final LabelKind labelKind;
    public final int labelStart, labelEnd;  // includes bars for a barred symbol
    public final String label;              // symbol name without bars; local label text; or raw text
    public final int opStart, opEnd;
    public final int argStart, argEnd;      // operands, trimmed, without comment

    SplitLine(Text text, boolean comment, LabelKind lk, int ls, int le, String label,
              int os, int oe, int as, int ae) {
        this.text = text; this.comment = comment; this.labelKind = lk;
        this.labelStart = ls; this.labelEnd = le; this.label = label;
        this.opStart = os; this.opEnd = oe; this.argStart = as; this.argEnd = ae;
    }

    public boolean hasLabel() { return labelKind != LabelKind.NONE; }
    public boolean hasOp() { return opStart >= 0; }
    public String op() { return opStart < 0 ? "" : text.s.substring(opStart, opEnd); }
    public String args() { return argStart < 0 ? "" : text.s.substring(argStart, argEnd); }
    /** The raw label-field text (for macro label parameters). */
    public String labelText() { return labelStart < 0 ? "" : text.s.substring(labelStart, labelEnd); }
}
