package com.piccolosystems.riscos.asmindex.expr;

import com.piccolosystems.riscos.asmindex.expr.Value.Kind;

/**
 * Parses and evaluates an objasm expression directly.
 * Unknown spreads through operators except where the result is decided anyway
 * ({FALSE} :LAND: x, {TRUE} :LOR: x).
 */
public final class Evaluator {
    private final String s;
    private int p;
    private final int end;
    private final EvalContext ctx;
    /** First syntax error, or null. */
    public String error;

    private Evaluator(String s, int from, int to, EvalContext ctx) {
        this.s = s; this.p = from; this.end = to; this.ctx = ctx;
    }

    /** Result of evaluating a whole range. */
    public static final class Result {
        public final Value value;
        public final int stop;       // where parsing stopped
        public final String error;
        Result(Value v, int stop, String error) { this.value = v; this.stop = stop; this.error = error; }
        public boolean ok() { return error == null; }
    }

    /** Evaluates s[from,to); it is an error if the expression doesn't use all of it. */
    public static Result evaluate(String s, int from, int to, EvalContext ctx) {
        Evaluator e = new Evaluator(s, from, to, ctx);
        Value v;
        try {
            v = e.logical();
            e.ws();
            if (e.error == null && e.p < e.end) e.fail("unexpected '" + s.substring(e.p, Math.min(e.end, e.p + 10)) + "'");
        } catch (Syntax x) {
            v = Value.unknown("syntax error");
        }
        if (e.error != null) v = Value.unknown("syntax error: " + e.error);
        return new Result(v, e.p, e.error);
    }

    /** Evaluates the longest expression starting at from; doesn't require it to reach to. */
    public static Result evaluatePrefix(String s, int from, int to, EvalContext ctx) {
        Evaluator e = new Evaluator(s, from, to, ctx);
        Value v;
        try {
            v = e.logical();
        } catch (Syntax x) {
            v = Value.unknown("syntax error");
        }
        if (e.error != null) v = Value.unknown("syntax error: " + e.error);
        return new Result(v, e.p, e.error);
    }

    public static Result evaluate(String s, EvalContext ctx) { return evaluate(s, 0, s.length(), ctx); }

    private static final class Syntax extends RuntimeException {
        Syntax() { super(null, null, false, false); }
    }

    private Syntax fail(String m) {
        if (error == null) error = m;
        return new Syntax();
    }

    private void ws() { while (p < end && s.charAt(p) == ' ') p++; }

    private boolean at(String t) { return s.startsWith(t, p) && p + t.length() <= end; }

    // ---------------- operators ----------------

    private enum Op {
        MUL(1), DIV(1), MOD(1),
        LEFT(2), RIGHT(2), CC(2),
        SHL(3), SHR(3), ROL(3), ROR(3),
        ADD(4), SUB(4), AND(4), OR(4), EOR(4),
        EQ(5), NE(5), LT(5), LE(5), GT(5), GE(5),
        LAND(6), LOR(6), LEOR(6);
        final int level;
        Op(int l) { level = l; }
    }

    private int opLen;

    /** Recognises a binary operator at p (after spaces), without consuming it. */
    private Op peekOp() {
        ws();
        if (p >= end) return null;
        char c = s.charAt(p);
        char d = p + 1 < end ? s.charAt(p + 1) : 0;
        opLen = 1;
        switch (c) {
            case ':': {
                int q = wordOperatorEnd(p);
                if (q < 0) return null;
                String w = s.substring(p + 1, q);
                opLen = q - p + 1;
                switch (w) {
                    case "MOD": return Op.MOD;
                    case "LEFT": return Op.LEFT;
                    case "RIGHT": return Op.RIGHT;
                    case "CC": return Op.CC;
                    case "SHL": return Op.SHL;
                    case "SHR": return Op.SHR;
                    case "ROL": return Op.ROL;
                    case "ROR": return Op.ROR;
                    case "AND": return Op.AND;
                    case "OR": return Op.OR;
                    case "EOR": return Op.EOR;
                    case "LAND": return Op.LAND;
                    case "LOR": return Op.LOR;
                    case "LEOR": return Op.LEOR;
                    default: return null;
                }
            }
            case '*': return Op.MUL;
            case '/': if (d == '=') { opLen = 2; return Op.NE; } return Op.DIV;
            case '%': return Op.MOD;
            case '+': return Op.ADD;
            case '-': return Op.SUB;
            case '^': return Op.EOR;
            case '&': if (d == '&') { opLen = 2; return Op.LAND; } return Op.AND;
            case '|': if (d == '|') { opLen = 2; return Op.LOR; } return Op.OR;
            case '=': if (d == '=') opLen = 2; return Op.EQ;
            case '!': if (d == '=') { opLen = 2; return Op.NE; } return null; // writeback '!' ends it
            case '<':
                if (d == '<') { opLen = 2; return Op.SHL; }
                if (d == '>') { opLen = 2; return Op.NE; }
                if (d == '=') { opLen = 2; return Op.LE; }
                return Op.LT;
            case '>':
                if (d == '>') { opLen = 2; return Op.SHR; }
                if (d == '<') { opLen = 2; return Op.NE; }
                if (d == '=') { opLen = 2; return Op.GE; }
                return Op.GT;
            default: return null;
        }
    }

    private Value logical() { return binary(6); }

    private Value binary(int level) {
        if (level == 0) return unary();
        Value l = binary(level - 1);
        for (;;) {
            int save = p;
            Op op = peekOp();
            if (op == null || op.level != level) { p = save; return l; }
            p += opLen;
            Value r = binary(level - 1);
            l = apply(op, l, r);
        }
    }

    // ---------------- operands ----------------

    private Value unary() {
        ws();
        if (p >= end) throw fail("missing operand");
        char c = s.charAt(p);
        switch (c) {
            case '(': {
                p++;
                Value v = logical();
                ws();
                if (p >= end || s.charAt(p) != ')') throw fail("missing ')'");
                p++;
                return v;
            }
            case '+': p++; return arith1(unary(), "+");
            case '-': p++; return arith1(unary(), "-");
            case '~': p++; return arith1(unary(), "~");
            case '!': p++; return lnot(unary());
            case ':': return colonUnary();
            case '"': return string();
            case '\'': return charConst();
            case '&': p++; return hex();
            case '{': return builtin();
            case '.':
                if (p + 1 < end && Character.isDigit(s.charAt(p + 1))) { skipFloat(); return Value.unknown("floating point"); }
                p++;
                return ctx.pc();
            case '@': p++; return ctx.storageCounter();
            case '?': {
                p++;
                int st = p;
                String n = symbolName();
                if (n == null) throw fail("symbol expected after '?'");
                return ctx.sizeOf(n, st, p);
            }
            case '%': return localLabel();
            case '|': {
                int st = p;
                String n = symbolName();
                return ctx.symbol(n, st, p);
            }
            default:
                if (Character.isDigit(c)) return number();
                if (isSymStart(c)) {
                    int st = p;
                    String n = symbolName();
                    return ctx.symbol(n, st, p);
                }
                throw fail("unexpected '" + c + "'");
        }
    }

    static boolean isSymStart(char c) { return Character.isLetter(c) && c < 128 || c == '_'; }
    static boolean isSymChar(char c) { return (Character.isLetterOrDigit(c) && c < 128) || c == '_'; }

    /** Reads a plain or |barred| symbol name at p; null if none. */
    private String symbolName() {
        if (p < end && s.charAt(p) == '|') {
            int q = s.indexOf('|', p + 1);
            if (q < 0 || q >= end || q == p + 1) throw fail("bad barred symbol");
            String n = s.substring(p + 1, q);
            p = q + 1;
            return n;
        }
        if (p >= end || !isSymStart(s.charAt(p))) return null;
        int st = p;
        while (p < end && isSymChar(s.charAt(p))) p++;
        return s.substring(st, p);
    }

    /**
     * End of a :WORD: operator starting at {@code at}: the index of the closing
     * ':' — or of a space, which objasm also accepts in its place — or -1.
     */
    private int wordOperatorEnd(int at) {
        int q = at + 1;
        while (q < end && isSymChar(s.charAt(q))) q++;
        if (q == at + 1 || q >= end) return -1;
        char c = s.charAt(q);
        return c == ':' || c == ' ' ? q : -1;
    }

    private Value colonUnary() {
        int q = wordOperatorEnd(p);
        if (q < 0) throw fail("bad ':' operator");
        String w = s.substring(p + 1, q);
        p = q + 1;
        switch (w) {
            case "DEF": {
                ws();
                int st = p;
                if (p < end && s.charAt(p) == '(') {   // :DEF:(name)
                    p++; ws(); st = p;
                    String n = symbolName();
                    if (n == null) throw fail("symbol expected after :DEF:");
                    int en = p;
                    ws();
                    if (p < end && s.charAt(p) == ')') p++;
                    return Value.bool(ctx.isDefined(n, st, en));
                }
                String n = symbolName();
                if (n == null) throw fail("symbol expected after :DEF:");
                return Value.bool(ctx.isDefined(n, st, p));
            }
            case "MDEF": {
                ws();
                String n = symbolName();
                if (n == null) throw fail("symbol expected after :MDEF:");
                return Value.bool(ctx.isMacro(n));
            }
            case "NOT": return arith1(unary(), "~");
            case "LNOT": return lnot(unary());
            case "LEN": {
                Value v = unary();
                if (v.isVague()) return v;
                if (!v.isStr()) return bad("string expected for :LEN:");
                return Value.num(v.str.length());
            }
            case "CHR": {
                Value v = unary();
                if (v.isVague()) return v;
                Integer n = v.asNumber();
                if (n == null) return bad("number expected for :CHR:");
                return Value.str(String.valueOf((char) (n & 0xFF)));
            }
            case "STR": {
                Value v = unary();
                if (v.isVague()) return v;
                if (v.isBool()) return Value.str(v.bool ? "T" : "F");
                if (v.isNum()) return Value.str(Value.hex8(v.num));
                return Value.unknown(":STR: of " + v.kind);
            }
            case "LOWERCASE": case "UPPERCASE": {
                Value v = unary();
                if (v.isVague()) return v;
                if (!v.isStr()) return bad("string expected");
                return Value.str(w.equals("LOWERCASE") ? lower(v.str) : upper(v.str));
            }
            case "BASE": {
                Value v = unary();
                if (v.isVague()) return v;
                if (v.kind == Kind.REGREL) return Value.num(Integer.numberOfTrailingZeros(v.base));
                if (v.kind == Kind.PROGREL) return Value.num(15);   // program-relative: based on the PC
                return Value.unknown(":BASE: of " + v.kind);
            }
            case "INDEX": {
                Value v = unary();
                if (v.isVague()) return v;
                if (v.kind == Kind.REGREL || v.kind == Kind.PROGREL || v.kind == Kind.NUM) return Value.num(v.num);
                Integer n = v.asNumber();
                if (n != null) return Value.num(n);
                return Value.unknown(":INDEX: of " + v.kind);
            }
            case "RCONST": {
                Value v = unary();
                if (v.isVague()) return v;
                if (v.isNum()) return v;
                return Value.unknown(":RCONST: of " + v.kind);
            }
            case "REVERSE_CC": {
                Value v = unary();
                if (v.isVague()) return v;
                if (!v.isStr()) return bad("string expected");
                String r = Conditions.reverse(v.str);
                return r == null ? bad("not a condition code: " + v.str) : Value.str(r);
            }
            case "CC_ENCODING": {
                Value v = unary();
                if (v.isVague()) return v;
                if (!v.isStr()) return bad("string expected");
                int n = Conditions.encoding(v.str);
                return n < 0 ? bad("not a condition code: " + v.str) : Value.num(n);
            }
            case "FLOAD": case "FEXEC": case "FSIZE": case "FATTR":
                unary();
                return Value.unknown(":" + w + ": not modelled");
            default:
                throw fail("unknown operator :" + w + ":");
        }
    }

    private static String lower(String x) {
        StringBuilder b = new StringBuilder();
        for (char c : x.toCharArray()) b.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        return b.toString();
    }

    private static String upper(String x) {
        StringBuilder b = new StringBuilder();
        for (char c : x.toCharArray()) b.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
        return b.toString();
    }

    /** An evaluation error that objasm would report: recorded, gives Unknown. */
    private Value bad(String m) {
        if (error == null) error = m;
        return Value.unknown(m);
    }

    private Value string() {
        p++;
        StringBuilder b = new StringBuilder();
        for (;;) {
            if (p >= end) throw fail("unterminated string");
            char c = s.charAt(p++);
            if (c == '"') {
                if (p < end && s.charAt(p) == '"') { b.append('"'); p++; continue; }
                break;
            }
            if (c == '\\' && p < end) { b.append(escape()); continue; }
            b.append(c);
        }
        return Value.str(b.toString());
    }

    /** C-style escape after a backslash (objasm's default). */
    private char escape() {
        char e = s.charAt(p++);
        switch (e) {
            case 'n': return '\n';
            case 't': return '\t';
            case 'r': return '\r';
            case 'a': return 7;
            case 'b': return 8;
            case 'f': return 12;
            case 'v': return 11;
            case 'x': {
                int v = 0, k = 0;
                while (k < 2 && p < end && Character.digit(s.charAt(p), 16) >= 0) { v = v * 16 + Character.digit(s.charAt(p++), 16); k++; }
                return (char) v;
            }
            default:
                if (e >= '0' && e <= '7') {
                    int v = e - '0', k = 1;
                    while (k < 3 && p < end && s.charAt(p) >= '0' && s.charAt(p) <= '7') { v = v * 8 + (s.charAt(p++) - '0'); k++; }
                    return (char) (v & 0xFF);
                }
                return e;
        }
    }

    private Value charConst() {
        // 'c' : exactly one character between the quotes
        if (p + 2 < end + 1 && p + 2 < s.length() && s.charAt(p + 2) == '\'') {
            char c = s.charAt(p + 1);
            p += 3;
            return Value.num(c & 0xFF);
        }
        if (p + 3 < s.length() && s.charAt(p + 1) == '\\' && s.charAt(p + 3) == '\'') {
            p += 2;
            char c = escape();
            p++;
            return Value.num(c & 0xFF);
        }
        throw fail("bad character constant");
    }

    private Value hex() {
        int st = p;
        long v = 0;
        while (p < end && Character.digit(s.charAt(p), 16) >= 0) { v = v * 16 + Character.digit(s.charAt(p), 16); p++; }
        if (p == st) throw fail("hex digits expected");
        return Value.num(v);
    }

    private void skipFloat() {
        while (p < end && (Character.isLetterOrDigit(s.charAt(p)) || s.charAt(p) == '.' || s.charAt(p) == '_'
                || ((s.charAt(p) == '+' || s.charAt(p) == '-') && (s.charAt(p - 1) == 'e' || s.charAt(p - 1) == 'E')))) p++;
    }

    private Value number() {
        int st = p;
        if (s.charAt(p) == '0' && p + 1 < end && (s.charAt(p + 1) == 'x' || s.charAt(p + 1) == 'X')) {
            p += 2;
            return hex();
        }
        if (s.charAt(p) == '0' && p + 2 < end && s.charAt(p + 2) == '_'
                && "fdFDhHbB".indexOf(s.charAt(p + 1)) >= 0) {
            skipFloat();
            return Value.unknown("floating point");
        }
        if (p + 1 < end && s.charAt(p + 1) == '_' && s.charAt(p) >= '2' && s.charAt(p) <= '9') {
            int base = s.charAt(p) - '0';
            p += 2;
            int ds = p;
            long v = 0;
            while (p < end && Character.isDigit(s.charAt(p))) {
                int d = s.charAt(p) - '0';
                if (d >= base) throw fail("bad digit for base " + base);
                v = v * base + d;
                p++;
            }
            if (p == ds) throw fail("digits expected");
            return Value.num(v);
        }
        long v = 0;
        while (p < end && Character.isDigit(s.charAt(p))) { v = v * 10 + (s.charAt(p) - '0'); p++; }
        if (p < end && (s.charAt(p) == '.' || s.charAt(p) == 'e' || s.charAt(p) == 'E')
                && p + 1 < end && Character.isDigit(s.charAt(p + 1))) {
            p = st;
            skipFloat();
            return Value.unknown("floating point");
        }
        return Value.num(v);
    }

    private Value builtin() {
        int q = s.indexOf('}', p);
        if (q < 0 || q >= end) throw fail("missing '}'");
        String n = s.substring(p + 1, q);
        p = q + 1;
        return ctx.builtin(n);
    }

    private Value localLabel() {
        int st = p;
        p++;
        char dir = 0, level = 0;
        if (p < end && "FfBb".indexOf(s.charAt(p)) >= 0) dir = Character.toUpperCase(s.charAt(p++));
        if (p < end && "TtAa".indexOf(s.charAt(p)) >= 0) level = Character.toUpperCase(s.charAt(p++));
        int ds = p;
        int n = 0;
        while (p < end && Character.isDigit(s.charAt(p))) n = n * 10 + (s.charAt(p++) - '0');
        if (p == ds) throw fail("bad local label reference");
        int rs = p;
        while (p < end && isSymChar(s.charAt(p))) p++;
        String routine = p > rs ? s.substring(rs, p) : null;
        return ctx.localLabel(dir, level, n, routine, st, p);
    }

    // ---------------- semantics ----------------

    private static Value spread(Value a, Value b) {
        if (a.isUndefined()) return a;
        if (b.isUndefined()) return b;
        if (a.isUnknown()) return a;
        return b;
    }

    private Value arith1(Value v, String op) {
        if (v.isVague()) return v;
        if (op.equals("+")) return v;
        Integer n = v.asNumber();
        if (n == null) {
            if (op.equals("-") && (v.kind == Kind.PROGREL || v.kind == Kind.REGREL)) return Value.unknown("negated address");
            return bad("number expected for unary " + op);
        }
        return Value.num(op.equals("-") ? -n : ~n);
    }

    private Value lnot(Value v) {
        if (v.isVague()) return v;
        if (!v.isBool()) return bad("logical expected for :LNOT:");
        return Value.bool(!v.bool);
    }

    private Value apply(Op op, Value a, Value b) {
        switch (op) {
            case LAND: case LOR: case LEOR: {
                if (a.isBool() && !b.isVague() && !b.isBool() || b.isBool() && !a.isVague() && !a.isBool()
                        || !a.isVague() && !b.isVague() && (!a.isBool() || !b.isBool()))
                    return bad("logical operands expected");
                if (op == Op.LAND) {
                    if (a.isBool() && !a.bool || b.isBool() && !b.bool) {
                        if (a.isUndefined() || b.isUndefined()) return spread(a, b);
                        return Value.FALSE;
                    }
                } else if (op == Op.LOR) {
                    if (a.isBool() && a.bool || b.isBool() && b.bool) {
                        if (a.isUndefined() || b.isUndefined()) return spread(a, b);
                        return Value.TRUE;
                    }
                }
                if (a.isVague() || b.isVague()) return spread(a, b);
                switch (op) {
                    case LAND: return Value.bool(a.bool && b.bool);
                    case LOR: return Value.bool(a.bool || b.bool);
                    default: return Value.bool(a.bool != b.bool);
                }
            }
            default:
        }
        if (a.isVague() || b.isVague()) return spread(a, b);
        switch (op) {
            case EQ: case NE: case LT: case LE: case GT: case GE: return compare(op, a, b);
            case CC:
                if (!a.isStr() || !b.isStr()) return bad("strings expected for :CC:");
                return Value.str(a.str + b.str);
            case LEFT: case RIGHT: {
                Integer n = b.asNumber();
                if (!a.isStr() || n == null) return bad("bad operands for :" + op + ":");
                int k = n;
                if (k < 0 || k > a.str.length()) return bad("string index out of range");
                return Value.str(op == Op.LEFT ? a.str.substring(0, k) : a.str.substring(a.str.length() - k));
            }
            case ADD: {
                Integer x = a.asNumber(), y = b.asNumber();
                if (x != null && y != null) return Value.num(x + y);
                if (y != null && a.kind == Kind.PROGREL) return Value.progRel(a.base, a.num + y);
                if (x != null && b.kind == Kind.PROGREL) return Value.progRel(b.base, b.num + x);
                if (y != null && a.kind == Kind.REGREL) return Value.regRel(a.base, a.num + y);
                if (x != null && b.kind == Kind.REGREL) return Value.regRel(b.base, b.num + x);
                if (a.kind == Kind.REGREL && b.kind == Kind.REGREL) return Value.regRel(a.base | b.base, a.num + b.num);
                if (a.kind == Kind.RELOC || b.kind == Kind.RELOC || a.kind == Kind.PROGREL || b.kind == Kind.PROGREL)
                    return Value.reloc("sum of addresses");
                return bad("bad operands for +");
            }
            case SUB: {
                Integer x = a.asNumber(), y = b.asNumber();
                if (x != null && y != null) return Value.num(x - y);
                if (y != null && a.kind == Kind.PROGREL) return Value.progRel(a.base, a.num - y);
                if (y != null && a.kind == Kind.REGREL) return Value.regRel(a.base, a.num - y);
                if (a.kind == Kind.PROGREL && b.kind == Kind.PROGREL) {
                    if (a.base == b.base) return Value.num(a.num - b.num);
                    return Value.reloc("difference of areas");
                }
                if (a.kind == Kind.REGREL && b.kind == Kind.REGREL && a.base == b.base) return Value.num(a.num - b.num);
                if (a.kind == Kind.RELOC || b.kind == Kind.RELOC || a.kind == Kind.PROGREL || b.kind == Kind.PROGREL)
                    return Value.reloc("address difference");
                return bad("bad operands for -");
            }
            default: {
                Integer x = a.asNumber(), y = b.asNumber();
                if (x == null || y == null) {
                    if (a.kind == Kind.PROGREL || a.kind == Kind.RELOC || b.kind == Kind.PROGREL || b.kind == Kind.RELOC
                            || a.kind == Kind.REGREL || b.kind == Kind.REGREL)
                        return Value.unknown(op + " on an address");
                    return bad("numbers expected for " + op);
                }
                long ux = x & 0xFFFFFFFFL, uy = y & 0xFFFFFFFFL;
                switch (op) {
                    case MUL: return Value.num(ux * uy);
                    case DIV: if (uy == 0) return bad("division by zero"); return Value.num(ux / uy);
                    case MOD: if (uy == 0) return bad("division by zero"); return Value.num(ux % uy);
                    case SHL: return Value.num(uy >= 32 ? 0 : ux << uy);
                    case SHR: return Value.num(uy >= 32 ? 0 : ux >>> uy);
                    case ROL: return Value.num(Integer.rotateLeft(x, (int) (uy & 31)));
                    case ROR: return Value.num(Integer.rotateRight(x, (int) (uy & 31)));
                    case AND: return Value.num(x & y);
                    case OR: return Value.num(x | y);
                    case EOR: return Value.num(x ^ y);
                    default: return bad("bad operator");
                }
            }
        }
    }

    private Value compare(Op op, Value a, Value b) {
        int c;
        if (a.isStr() && b.isStr()) {
            c = compareBytes(a.str, b.str);
        } else if ((a.isNum() || b.isNum()) && a.asNumber() != null && b.asNumber() != null) {
            c = Long.compare(a.asNumber() & 0xFFFFFFFFL, b.asNumber() & 0xFFFFFFFFL);
        } else if (a.isBool() && b.isBool()) {
            if (op == Op.EQ) return Value.bool(a.bool == b.bool);
            if (op == Op.NE) return Value.bool(a.bool != b.bool);
            return bad("can't order logical values");
        } else if (a.kind == b.kind && (a.kind == Kind.PROGREL || a.kind == Kind.REGREL)) {
            if (a.base != b.base) return op == Op.NE ? Value.TRUE : op == Op.EQ ? Value.FALSE : Value.unknown("compare different bases");
            c = Long.compare(a.unsigned(), b.unsigned());
        } else if (a.kind == Kind.RELOC || b.kind == Kind.RELOC) {
            return Value.unknown("comparison of relocatable values");
        } else {
            // Different types: = is false and <> is true.
            if (op == Op.EQ) return Value.FALSE;
            if (op == Op.NE) return Value.TRUE;
            return bad("can't compare " + a.kind + " with " + b.kind);
        }
        switch (op) {
            case EQ: return Value.bool(c == 0);
            case NE: return Value.bool(c != 0);
            case LT: return Value.bool(c < 0);
            case LE: return Value.bool(c <= 0);
            case GT: return Value.bool(c > 0);
            default: return Value.bool(c >= 0);
        }
    }

    private static int compareBytes(String x, String y) {
        int n = Math.min(x.length(), y.length());
        for (int i = 0; i < n; i++) {
            int d = (x.charAt(i) & 0xFF) - (y.charAt(i) & 0xFF);
            if (d != 0) return d;
        }
        return x.length() - y.length();
    }
}
