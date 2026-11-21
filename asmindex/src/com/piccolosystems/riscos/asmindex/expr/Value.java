package com.piccolosystems.riscos.asmindex.expr;

/** The result of evaluating an expression. */
public final class Value {
    public enum Kind { NUM, BOOL, STR, PROGREL, REGREL, RELOC, UNDEFINED, UNKNOWN }

    public final Kind kind;
    public final int num;        // NUM value, or offset for PROGREL/REGREL
    public final boolean bool;
    public final String str;
    public final int base;       // area id for PROGREL; register bit mask for REGREL
    public final String why;     // for UNKNOWN/UNDEFINED: a short reason

    private Value(Kind k, int num, boolean bool, String str, int base, String why) {
        this.kind = k; this.num = num; this.bool = bool; this.str = str; this.base = base; this.why = why;
    }

    public static final Value TRUE = new Value(Kind.BOOL, 0, true, null, 0, null);
    public static final Value FALSE = new Value(Kind.BOOL, 0, false, null, 0, null);

    public static Value num(long n) { return new Value(Kind.NUM, (int) n, false, null, 0, null); }
    public static Value bool(boolean b) { return b ? TRUE : FALSE; }
    public static Value str(String s) { return new Value(Kind.STR, 0, false, s, 0, null); }
    public static Value progRel(int area, int offset) { return new Value(Kind.PROGREL, offset, false, null, area, null); }
    public static Value regRel(int regMask, int offset) { return new Value(Kind.REGREL, offset, false, null, regMask, null); }
    public static Value reloc(String what) { return new Value(Kind.RELOC, 0, false, null, 0, what); }
    public static Value undefined(String why) { return new Value(Kind.UNDEFINED, 0, false, null, 0, why); }
    public static Value unknown(String why) { return new Value(Kind.UNKNOWN, 0, false, null, 0, why); }

    public boolean isNum() { return kind == Kind.NUM; }
    public boolean isBool() { return kind == Kind.BOOL; }
    public boolean isStr() { return kind == Kind.STR; }
    public boolean isUnknown() { return kind == Kind.UNKNOWN; }
    public boolean isUndefined() { return kind == Kind.UNDEFINED; }
    /** Undefined or Unknown: no usable value. */
    public boolean isVague() { return kind == Kind.UNKNOWN || kind == Kind.UNDEFINED; }

    public long unsigned() { return num & 0xFFFFFFFFL; }

    /** Number, or a one-character string's code; else null. */
    public Integer asNumber() {
        if (kind == Kind.NUM) return num;
        if (kind == Kind.STR && str.length() == 1) return str.charAt(0) & 0xFF;
        return null;
    }

    public static String hex8(int n) { return String.format("%08X", n); }

    /** Display form, in objasm's style. */
    public String display() {
        switch (kind) {
            case NUM: return "&" + hex8(num);
            case BOOL: return bool ? "{TRUE}" : "{FALSE}";
            case STR: return "\"" + str + "\"";
            case PROGREL: return "area#" + base + " + &" + Integer.toHexString(num).toUpperCase();
            case REGREL: {
                StringBuilder b = new StringBuilder();
                for (int r = 0; r < 16; r++) if ((base & (1 << r)) != 0) b.append(b.length() == 0 ? "" : "+").append("R").append(r);
                return b + " + &" + Integer.toHexString(num).toUpperCase();
            }
            case RELOC: return "relocatable" + (why == null ? "" : " (" + why + ")");
            case UNDEFINED: return "undefined" + (why == null ? "" : " (" + why + ")");
            default: return "unknown" + (why == null ? "" : " (" + why + ")");
        }
    }

    public boolean sameAs(Value o) {
        if (o == null || kind != o.kind) return false;
        switch (kind) {
            case NUM: return num == o.num;
            case BOOL: return bool == o.bool;
            case STR: return str.equals(o.str);
            case PROGREL: case REGREL: return num == o.num && base == o.base;
            case RELOC: return why != null && why.equals(o.why);
            default: return false;
        }
    }

    @Override public String toString() { return display(); }
}
