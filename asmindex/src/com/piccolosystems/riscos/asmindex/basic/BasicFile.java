package com.piccolosystems.riscos.asmindex.basic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One BBC BASIC V program file, as text (not tokenised), parsed for the
 * language server: its procedures and functions, the calls to them, every
 * variable site, line numbers and references to them, and the folds of its
 * block structure.
 *
 * <p>Statements are separated by newlines and colons. Keywords are upper case
 * and, as BASIC's tokeniser reads them, may run on into what follows
 * ({@code PRINTx}), except for those that are only keywords when no letter or
 * digit follows ({@code TIMEOUT} is a variable). Integer, string and real
 * variables ({@code foo%}, {@code foo$}, {@code foo}) are different variables,
 * and so are arrays ({@code foo%(}).
 */
public final class BasicFile {

    // ---------------------------------------------------------------- results

    /** A position: 0-based line and column, and length. */
    public record Site(int line, int col, int len) {
        public boolean contains(int l, int c) {
            return l == line && c >= col && c <= col + len;
        }
    }

    /** A {@code DEF PROC} or {@code DEF FN}: from its DEF line to its end. */
    public static final class Routine {
        /** {@code PROCname} or {@code FNname}. */
        public final String name;
        public final Site site;
        /** The DEF statement's text, from DEF to the end of its parameter list. */
        public final String signature;
        public int endLine = -1;
        public final Set<String> locals = new LinkedHashSet<>();

        Routine(String name, Site site, String signature) {
            this.name = name;
            this.site = site;
            this.signature = signature;
        }

        public boolean isFunction() {
            return name.startsWith("FN");
        }
    }

    /** A call: {@code PROCname} or {@code FNname} without DEF. */
    public record Call(String name, Site site) {}

    /** How a variable site uses the variable. */
    public enum Use {
        /** Read, or changed in place ({@code +=}). */
        READ,
        /** Given a value: {@code =}, FOR, READ, INPUT, SWAP, SYS … TO, an assembler label; an array element too. */
        ASSIGN,
        /** A {@code LOCAL} or a parameter: a declaration local to the routine. */
        LOCAL,
        PARAMETER,
        /** In a {@code DIM}: an array's declaration, or a block's. */
        DIM
    }

    /**
     * A variable site. {@code key} is the name with its suffix, and {@code (}
     * for an array: {@code foo}, {@code foo%}, {@code foo$(}.
     */
    public record Var(String key, Site site, Use use, int routine) {}

    /** A reference to a line number: GOTO, GOSUB, RESTORE, THEN, ELSE. */
    public record LineRef(int number, Site site) {}

    public record Diag(Site site, int severity, String message) {}

    public final String text;
    public final List<String> lines;
    public final List<Routine> routines = new ArrayList<>();
    public final List<Call> calls = new ArrayList<>();
    public final List<Var> vars = new ArrayList<>();
    public final List<LineRef> lineRefs = new ArrayList<>();
    /** BASIC line number → line, for files with line numbers. */
    public final Map<Integer, Integer> lineNumbers = new HashMap<>();
    /** {startLine, endLine}, 0-based, inclusive. */
    public final List<int[]> folds = new ArrayList<>();
    public final List<Diag> diags = new ArrayList<>();
    /** Whether it loads libraries at run time, which may define procedures it calls. */
    public boolean loadsLibraries;

    public static final int ERROR = 1, WARNING = 2, INFO = 3;

    public BasicFile(String text) {
        this.text = text;
        this.lines = List.of(text.split("\r?\n", -1));
        parse();
    }

    // ---------------------------------------------------------------- lexing

    enum T { IDENT, KEYWORD, PROC, FN, NUMBER, STRING, OP, COLON, LABEL, MNEMONIC, ASM_OPEN, ASM_CLOSE }

    record Tok(T type, String text, int col, int len, boolean array) {
        boolean is(String s) {
            return text.equals(s) && (type == T.KEYWORD || type == T.OP);
        }
    }

    /**
     * BBC BASIC V's keywords. Those marked * are only keywords if what follows
     * isn't a letter, digit, {@code _} or {@code `}.
     */
    private static final String KEYWORDS = "ABS ACS ADVAL AND APPEND ASC ASN ATN AUTO BEAT* BEATS BGET BPUT BY CALL CASE "
            + "CHAIN CHR$ CIRCLE CLEAR* CLG* CLOSE CLS* COLOUR COLOR COS COUNT* CRUNCH DATA DEF DEG DELETE DIM DIV DRAW "
            + "EDIT ELLIPSE ELSE END* ENDCASE* ENDIF* ENDPROC* ENDWHILE* ENVELOPE EOF EOR ERL* ERR* ERROR EVAL EXP EXT "
            + "FALSE* FILL FOR GCOL GET GET$ GOSUB GOTO HELP HIMEM* IF INKEY INKEY$ INPUT INSTALL INSTR( INT LEFT$( LEN LET "
            + "LIBRARY LINE LIST LISTO LN LOAD LOCAL LOG LOMEM* LVAR MID$( MOD MODE MOUSE MOVE NEW* NEXT NOT OF OFF OLD* "
            + "ON OPENIN OPENOUT OPENUP OR ORIGIN OSCLI OTHERWISE* OVERLAY PAGE* PI* PLOT POINT( POS* PRINT PTR QUIT* RAD "
            + "READ RECTANGLE REM RENUMBER REPEAT REPORT* REPORT$ RESTORE RETURN* RIGHT$( RND* RUN* SAVE SGN SIN SOUND SPC "
            + "SQR STEP STEREO STOP* STR$ STRING$( SUM SUMLEN SWAP SYS TAB( TAN TEMPO THEN TIME* TIME$ TINT TO TOP* TRACE "
            + "TRUE* TWIN TWINO UNTIL USR VAL VDU VOICE VOICES VPOS* WAIT WHEN WHILE WIDTH*";
    private static final Map<String, Boolean> KEYWORD = new HashMap<>();
    private static final int LONGEST;

    static {
        int longest = 0;
        for (String k : KEYWORDS.split(" ")) {
            boolean conditional = k.endsWith("*");
            String w = conditional ? k.substring(0, k.length() - 1) : k;
            KEYWORD.put(w, conditional);
            longest = Math.max(longest, w.length());
        }
        LONGEST = longest;
    }

    private static boolean identChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '`';
    }

    /** The keyword at {@code i}, if any, as BASIC's tokeniser would read it. */
    private static String keywordAt(String s, int i) {
        if (i >= s.length() || !Character.isUpperCase(s.charAt(i))) return null;
        for (int n = Math.min(LONGEST, s.length() - i); n >= 2; n--) {
            String w = s.substring(i, i + n);
            Boolean conditional = KEYWORD.get(w);
            if (conditional == null) continue;
            if (conditional && i + n < s.length() && identChar(s.charAt(i + n))) continue;
            return w;
        }
        return null;
    }

    private boolean inAsm;

    /** One line's tokens. REM, DATA and * commands end the useful part of a line. */
    private List<Tok> lex(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n && s.charAt(i) == ' ') i++;
        // A line number.
        int start = i;
        while (i < n && Character.isDigit(s.charAt(i))) i++;
        if (i > start) out.add(new Tok(T.NUMBER, s.substring(start, i), start, i - start, false));
        boolean stmtStart = true, mnemonicNext = inAsm;
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') {
                i++;
                continue;
            }
            int from = i;
            if (inAsm) {
                if (c == ';' || c == '\\') {          // an assembler comment, to the end of the statement
                    while (i < n && s.charAt(i) != ':') i++;
                    continue;
                }
                if (stmtStart && c == ']') {
                    out.add(new Tok(T.ASM_CLOSE, "]", i, 1, false));
                    inAsm = false;
                    i++;
                    stmtStart = false;
                    continue;
                }
                if (stmtStart && c == '.' && i + 1 < n && (Character.isLetter(s.charAt(i + 1)) || s.charAt(i + 1) == '_')) {
                    int j = i + 1;
                    while (j < n && identChar(s.charAt(j))) j++;
                    if (j < n && (s.charAt(j) == '%' || s.charAt(j) == '$')) j++;
                    out.add(new Tok(T.LABEL, s.substring(i + 1, j), i + 1, j - i - 1, false));
                    i = j;
                    stmtStart = false;
                    mnemonicNext = true;
                    continue;
                }
                if (mnemonicNext && Character.isLetter(c)) {
                    // The instruction or directive, read as a word: ORR isn't OR R.
                    int j = i;
                    while (j < n && identChar(s.charAt(j))) j++;
                    out.add(new Tok(T.MNEMONIC, s.substring(i, j), i, j - i, false));
                    i = j;
                    stmtStart = mnemonicNext = false;
                    continue;
                }
            } else if (stmtStart && c == '*') {
                out.add(new Tok(T.OP, "*", i, 1, false));   // an OS command: the rest of the line is the OS's
                return out;
            } else if (stmtStart && c == '[') {
                out.add(new Tok(T.ASM_OPEN, "[", i, 1, false));
                inAsm = true;
                mnemonicNext = true;
                i++;
                continue;
            }
            if (c == ':') {
                out.add(new Tok(T.COLON, ":", i, 1, false));
                i++;
                stmtStart = true;
                mnemonicNext = inAsm;
                continue;
            }
            stmtStart = mnemonicNext = false;
            if (c == '"') {
                i++;
                while (i < n) {
                    if (s.charAt(i) == '"') {
                        if (i + 1 < n && s.charAt(i + 1) == '"') i += 2;
                        else break;
                    } else {
                        i++;
                    }
                }
                i = Math.min(n, i + 1);
                out.add(new Tok(T.STRING, s.substring(from, i), from, i - from, false));
                continue;
            }
            String kw = inAsm && !Character.isUpperCase(c) ? null : keywordAt(s, i);
            if (kw == null && (s.startsWith("PROC", i) || s.startsWith("FN", i))) {
                int k = i + (s.startsWith("PROC", i) ? 4 : 2), j = k;
                while (j < n && identChar(s.charAt(j))) j++;
                if (j > k) {
                    out.add(new Tok(s.startsWith("PROC", i) ? T.PROC : T.FN, s.substring(i, j), i, j - i, false));
                    i = j;
                    continue;
                }
            }
            if (kw != null) {
                i += kw.length();
                out.add(new Tok(T.KEYWORD, kw, from, kw.length(), false));
                if (kw.equals("REM")) return out;
                if (kw.equals("DATA") && !inAsm) return out;
                // A statement may follow these directly, without a colon.
                if (kw.equals("THEN") || kw.equals("ELSE") || kw.equals("REPEAT") || kw.equals("OTHERWISE"))
                    stmtStart = true;
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '`' || c == '@' && i + 1 < n && s.charAt(i + 1) == '%') {
                int j = i + 1;
                while (j < n && identChar(s.charAt(j))) j++;
                if (j < n && (s.charAt(j) == '%' || s.charAt(j) == '$')) j++;
                boolean array = j < n && s.charAt(j) == '(';
                out.add(new Tok(T.IDENT, s.substring(i, j), i, j - i, array));
                i = j;
                continue;
            }
            if (Character.isDigit(c) || c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1))) {
                int j = i;
                while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
                if (j < n && s.charAt(j) == 'E' && j + 1 < n && (Character.isDigit(s.charAt(j + 1)) || s.charAt(j + 1) == '-')) {
                    j += 2;
                    while (j < n && Character.isDigit(s.charAt(j))) j++;
                }
                out.add(new Tok(T.NUMBER, s.substring(i, j), i, j - i, false));
                i = j;
                continue;
            }
            if (c == '&' && i + 1 < n && Character.digit(s.charAt(i + 1), 16) >= 0) {
                int j = i + 1;
                while (j < n && Character.digit(s.charAt(j), 16) >= 0) j++;
                out.add(new Tok(T.NUMBER, s.substring(i, j), i, j - i, false));
                i = j;
                continue;
            }
            if (c == '%' && i + 1 < n && (s.charAt(i + 1) == '0' || s.charAt(i + 1) == '1')) {
                int j = i + 1;
                while (j < n && (s.charAt(j) == '0' || s.charAt(j) == '1')) j++;
                out.add(new Tok(T.NUMBER, s.substring(i, j), i, j - i, false));
                i = j;
                continue;
            }
            String op = String.valueOf(c);
            if (i + 1 < n) {
                String two = s.substring(i, i + 2);
                if (List.of("+=", "-=", "<=", ">=", "<>", "<<", ">>").contains(two)) op = two;
            }
            out.add(new Tok(T.OP, op, i, op.length(), false));
            i += op.length();
        }
        return out;
    }

    // ---------------------------------------------------------------- parsing

    private enum B { IF, CASE, WHILE, REPEAT, FOR, ASM }

    private static final class Block {
        final B kind;
        final int line;
        final Site site;
        int clause = -1;                  // the line of the current ELSE, WHEN or OTHERWISE
        boolean hadElse;

        Block(B kind, int line, Site site) {
            this.kind = kind;
            this.line = line;
            this.site = site;
        }
    }

    private final Deque<Block> blocks = new ArrayDeque<>();
    private Routine routine;              // the routine being read, until its end
    private int routineIndex = -1;        // the routine the current line belongs to, for scoping

    private static final Set<String> REGISTERS = new java.util.HashSet<>();

    static {
        for (int r = 0; r < 16; r++) {
            REGISTERS.add("r" + r);
            REGISTERS.add("R" + r);
            REGISTERS.add("c" + r);
            REGISTERS.add("C" + r);
            REGISTERS.add("p" + r);
            REGISTERS.add("P" + r);
        }
        for (int r = 0; r < 8; r++) {
            REGISTERS.add("f" + r);
            REGISTERS.add("F" + r);
        }
        for (int r = 0; r < 32; r++) {
            REGISTERS.add("s" + r);
            REGISTERS.add("d" + r);
        }
        for (String r : List.of("a1", "a2", "a3", "a4", "v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8", "sb", "sl", "fp",
                "ip", "sp", "lr", "pc", "wp", "cpsr", "spsr", "lsl", "lsr", "asr", "ror", "rrx", "LSL", "LSR", "ASR",
                "ROR", "RRX", "SP", "LR", "PC", "FP", "IP", "SL", "SB", "WP", "CPSR", "SPSR", "CPSR_c", "CPSR_f",
                "CPSR_cxsf", "SPSR_cxsf", "CPSR_all", "SPSR_all"))
            REGISTERS.add(r);
    }

    private void parse() {
        for (int ln = 0; ln < lines.size(); ln++) {
            List<Tok> toks = lex(lines.get(ln));
            int k = 0;
            if (!toks.isEmpty() && toks.get(0).type == T.NUMBER && toks.get(0).col == leadingSpaces(lines.get(ln))) {
                try {
                    lineNumbers.putIfAbsent(Integer.parseInt(toks.get(0).text), ln);
                } catch (NumberFormatException e) {
                    // not a line number after all
                }
                k = 1;
            }
            parseLine(ln, toks.subList(k, toks.size()));
        }
        int last = lines.size() - 1;
        if (routine != null) endRoutine(lastCodeLine(last));
        unclosed();
    }

    private static int leadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }

    /** The last line at or before {@code line} that isn't blank. */
    private int lastCodeLine(int line) {
        while (line > 0 && lines.get(line).isBlank()) line--;
        return line;
    }

    /** Splits a line into statements: at colons, after THEN, and around ELSE. */
    private static List<List<Tok>> statements(List<Tok> toks) {
        List<List<Tok>> out = new ArrayList<>();
        List<Tok> cur = new ArrayList<>();
        for (Tok t : toks) {
            if (t.type == T.COLON) {
                out.add(cur);
                cur = new ArrayList<>();
            } else if (t.is("ELSE")) {
                out.add(cur);
                out.add(new ArrayList<>(List.of(t)));
                cur = new ArrayList<>();
            } else {
                cur.add(t);
                if (t.is("THEN") || t.is("REPEAT") && cur.size() == 1 || t.is("OTHERWISE") && cur.size() == 1) {
                    out.add(cur);
                    cur = new ArrayList<>();
                }
            }
        }
        out.add(cur);
        out.removeIf(List::isEmpty);
        return out;
    }

    private void parseLine(int ln, List<Tok> toks) {
        lineReferences(ln, toks);
        List<List<Tok>> stmts = statements(toks);
        Tok lastTok = toks.isEmpty() ? null : toks.get(toks.size() - 1);
        boolean conditional = false;         // after a single-line IF: the rest of the line is conditional
        boolean asm = asmOpen();
        for (int si = 0; si < stmts.size(); si++) {
            List<Tok> s = stmts.get(si);
            Tok first = s.get(0);
            if (asm) {
                if (first.type == T.ASM_CLOSE) {
                    closeBlock(B.ASM, ln, first, "]");
                    asm = false;
                    if (s.size() > 1) basicStatement(ln, s.subList(1, s.size()), si == 0, conditional, lastTok);
                } else {
                    asmStatement(ln, s);
                }
                continue;
            }
            if (first.type == T.ASM_OPEN) {
                blocks.push(new Block(B.ASM, ln, site(ln, first)));
                asm = true;
                if (s.size() > 1) asmStatement(ln, s.subList(1, s.size()));
                continue;
            }
            if (first.is("IF") && !(lastTok != null && lastTok.is("THEN") && s.get(s.size() - 1) == lastTok)) {
                // Not the IF of a multi-line IF; its THEN or ELSE parts follow on this line.
                basicStatement(ln, s, si == 0, conditional, lastTok);
                conditional = true;
                continue;
            }
            basicStatement(ln, s, si == 0, conditional, lastTok);
        }
    }

    /** Line numbers after GOTO, GOSUB, RESTORE, THEN, ELSE and TRACE (and ON … GOTO's lists). */
    private void lineReferences(int ln, List<Tok> toks) {
        for (int i = 0; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (t.type != T.KEYWORD || !Set.of("GOTO", "GOSUB", "RESTORE", "THEN", "ELSE", "TRACE").contains(t.text))
                continue;
            for (int j = i + 1; j < toks.size() && toks.get(j).type == T.NUMBER; j += 2) {
                Tok n = toks.get(j);
                try {
                    lineRefs.add(new LineRef(Integer.parseInt(n.text), site(ln, n)));
                } catch (NumberFormatException e) {
                    break;
                }
                if (j + 1 >= toks.size() || !toks.get(j + 1).is(",")) break;
            }
        }
    }

    private boolean asmOpen() {
        return !blocks.isEmpty() && blocks.peek().kind == B.ASM;
    }

    private Site site(int ln, Tok t) {
        return new Site(ln, t.col, t.len);
    }

    private void asmStatement(int ln, List<Tok> s) {
        int i = 0;
        if (s.get(0).type == T.LABEL) {
            Tok l = s.get(0);
            vars.add(new Var(l.text, site(ln, l), Use.ASSIGN, routineIndex));
            i = 1;
        }
        for (; i < s.size(); i++) {
            Tok t = s.get(i);
            if (t.type == T.IDENT && !REGISTERS.contains(t.text))
                vars.add(new Var(key(t), site(ln, t), Use.READ, routineIndex));
            else if (t.type == T.PROC || t.type == T.FN)
                calls.add(new Call(t.text, site(ln, t)));
        }
    }

    /** An opening bracket, or a keyword that includes one (INSTR(, MID$(). */
    /**
     * Whether the statement assigns the name at {@code i}: {@code name =}, or
     * for an array {@code name(…) =} (an element) or {@code name() =}.
     */
    private static boolean assignedAt(List<Tok> s, int i) {
        if (i >= s.size() || s.get(i).type != T.IDENT) return false;
        int j = i + 1;
        if (s.get(i).array && j < s.size() && s.get(j).is("(")) {
            int depth = 1;
            for (j++; j < s.size() && depth > 0; j++) {
                if (opens(s.get(j))) depth++;
                else if (s.get(j).is(")")) depth--;
            }
        }
        return j < s.size() && s.get(j).is("=");
    }

    /**
     * Whether the name at {@code i} (with its subscripts, for an array) is a
     * whole item of a list: what follows is a comma, a semicolon or the end.
     */
    private static boolean wholeItem(List<Tok> s, int i) {
        int j = i + 1;
        if (s.get(i).array && j < s.size() && s.get(j).is("(")) {
            int depth = 1;
            for (j++; j < s.size() && depth > 0; j++) {
                if (opens(s.get(j))) depth++;
                else if (s.get(j).is(")")) depth--;
            }
        }
        return j >= s.size() || s.get(j).is(",") || s.get(j).is(";");
    }

    private static boolean opens(Tok t) {
        return t.is("(") || t.type == T.KEYWORD && t.text.endsWith("(");
    }

    private static String key(Tok t) {
        return t.array ? t.text + "(" : t.text;
    }

    /**
     * A BASIC statement. {@code lineStart}: the first on its line;
     * {@code conditional}: after a single-line IF on the same line.
     */
    private void basicStatement(int ln, List<Tok> s, boolean lineStart, boolean conditional, Tok lastTok) {
        Tok first = s.get(0);
        String w = first.type == T.KEYWORD ? first.text : "";
        boolean onlyStmt = !conditional;
        // After a single-line IF, loops are opened and closed only at run time
        // (IF a THEN NEXT exits a FOR early): leave the block structure alone.
        if (conditional && Set.of("ENDIF", "CASE", "WHEN", "OTHERWISE", "ENDCASE", "WHILE", "ENDWHILE", "REPEAT",
                "UNTIL", "FOR", "NEXT").contains(w)) {
            variables(ln, s);
            return;
        }
        switch (w) {
            case "DEF" -> {
                if (s.size() > 1 && (s.get(1).type == T.PROC || s.get(1).type == T.FN)) {
                    startRoutine(ln, s);
                    return;
                }
            }
            case "IF" -> {
                if (lastTok != null && lastTok.is("THEN") && s.get(s.size() - 1) == lastTok)
                    blocks.push(new Block(B.IF, ln, site(ln, first)));
            }
            case "ELSE" -> {
                if (lineStart) {
                    Block b = blocks.peek();
                    if (b != null && b.kind == B.IF && !b.hadElse) {
                        folds.add(new int[] {b.line, ln - 1});
                        b.hadElse = true;
                        b.clause = ln;
                    } else {
                        diags.add(new Diag(site(ln, first), WARNING,
                                b != null && b.kind == B.IF ? "A second ELSE in this IF" : "ELSE without a multi-line IF"));
                    }
                    return;
                }
            }
            case "ENDIF" -> {
                if (onlyStmt) {
                    closeBlock(B.IF, ln, first, "ENDIF");
                    return;
                }
            }
            case "CASE" -> {
                if (lastTok != null && lastTok.is("OF")) blocks.push(new Block(B.CASE, ln, site(ln, first)));
            }
            case "WHEN", "OTHERWISE" -> {
                Block b = blocks.peek();
                if (b != null && b.kind == B.CASE) {
                    if (b.clause >= 0) folds.add(new int[] {b.clause, ln - 1});
                    b.clause = ln;
                } else {
                    diags.add(new Diag(site(ln, first), WARNING, w + " without a CASE"));
                }
            }
            case "ENDCASE" -> {
                closeBlock(B.CASE, ln, first, "ENDCASE");
                return;
            }
            case "WHILE" -> blocks.push(new Block(B.WHILE, ln, site(ln, first)));
            case "ENDWHILE" -> {
                closeBlock(B.WHILE, ln, first, "ENDWHILE");
                return;
            }
            case "REPEAT" -> blocks.push(new Block(B.REPEAT, ln, site(ln, first)));
            case "UNTIL" -> closeBlock(B.REPEAT, ln, first, "UNTIL");
            case "FOR" -> blocks.push(new Block(B.FOR, ln, site(ln, first)));
            case "NEXT" -> {
                int n = 1;
                for (Tok t : s) if (t.is(",")) n++;
                for (int k = 0; k < n; k++) closeBlock(B.FOR, ln, first, "NEXT");
            }
            case "ENDPROC" -> {
                if (onlyStmt && routine != null && !routine.isFunction() && routineDepth() == 0) {
                    endRoutine(ln);
                    return;
                }
            }
            case "LIBRARY", "INSTALL", "OVERLAY" -> loadsLibraries = true;
            default -> {
                if (first.is("=") && onlyStmt && routine != null && routine.isFunction() && routineDepth() == 0) {
                    variables(ln, s.subList(1, s.size()));
                    endRoutine(ln);
                    return;
                }
            }
        }
        if (first.is("=") || w.equals("ELSE") || w.equals("ENDIF")) {
            variables(ln, s.subList(1, s.size()));
            return;
        }
        variables(ln, s);
    }

    /** The depth of blocks opened inside the current routine. */
    private int routineDepth() {
        return blocks.size();
    }

    private void startRoutine(int ln, List<Tok> s) {
        if (routine != null) endRoutine(lastCodeLine(ln - 1));
        unclosed();
        Tok name = s.get(1);
        int end = 2;
        if (s.size() > 2 && s.get(2).is("(")) {
            int depth = 0;
            for (end = 2; end < s.size(); end++) {
                if (opens(s.get(end))) depth++;
                else if (s.get(end).is(")") && --depth == 0) {
                    end++;
                    break;
                }
            }
        }
        Tok lastSig = s.get(Math.min(end, s.size()) - 1);
        String sig = lines.get(ln).substring(s.get(0).col, lastSig.col + lastSig.len);
        routine = new Routine(name.text, site(ln, name), sig);
        routines.add(routine);
        routineIndex = routines.size() - 1;
        for (int i = 2; i < Math.min(end, s.size()); i++) {
            Tok t = s.get(i);
            if (t.type == T.IDENT) {
                routine.locals.add(key(t));
                vars.add(new Var(key(t), site(ln, t), Use.PARAMETER, routineIndex));
            }
        }
        if (end < s.size()) variables(ln, s.subList(end, s.size()));
    }

    private void endRoutine(int ln) {
        routine.endLine = Math.max(ln, routine.site.line);
        if (routine.endLine > routine.site.line) folds.add(new int[] {routine.site.line, routine.endLine});
        unclosed();
        routine = null;
        routineIndex = -1;       // what follows belongs to no routine until the next DEF
    }

    /** Reports the blocks still open, and forgets them. */
    private void unclosed() {
        while (!blocks.isEmpty()) {
            Block b = blocks.pop();
            String what = switch (b.kind) {
                case IF -> "This multi-line IF has no ENDIF";
                case CASE -> "This CASE has no ENDCASE";
                case WHILE -> "This WHILE has no ENDWHILE";
                case REPEAT -> "This REPEAT has no UNTIL";
                case FOR -> "This FOR has no NEXT";
                case ASM -> "This assembler block has no ]";
            };
            diags.add(new Diag(b.site, WARNING, what));
        }
    }

    private void closeBlock(B kind, int ln, Tok t, String word) {
        // A FOR … NEXT on one line, or a WHILE … ENDWHILE, may close a block
        // opened earlier on the same line: fine, no fold.
        Block b = blocks.peek();
        if (b == null || b.kind != kind) {
            // Tolerate a missing NEXT or UNTIL inside, as BASIC does at run time.
            boolean found = false;
            for (Block x : blocks) if (x.kind == kind) found = true;
            if (!found) {
                diags.add(new Diag(site(ln, t), WARNING, word + " without " + switch (kind) {
                    case IF -> "a multi-line IF";
                    case CASE -> "a CASE";
                    case WHILE -> "a WHILE";
                    case REPEAT -> "a REPEAT";
                    case FOR -> "a FOR";
                    case ASM -> "an assembler block";
                }));
                return;
            }
            while (blocks.peek().kind != kind) {
                Block skipped = blocks.pop();
                if (skipped.kind != B.FOR && skipped.kind != B.REPEAT)
                    diags.add(new Diag(skipped.site, WARNING, "This " + skipped.kind + " isn't closed before " + word));
            }
            b = blocks.peek();
        }
        blocks.pop();
        if (b.clause >= 0) {
            // The last clause folds to the closing line; the whole block too, for CASE.
            folds.add(new int[] {b.clause, kind == B.CASE ? ln - 1 : ln});
            if (kind == B.CASE) folds.add(new int[] {b.line, ln});
        } else if (ln > b.line) {
            folds.add(new int[] {b.line, ln});
        }
    }

    /** The variables, calls and line references in the rest of a statement. */
    private void variables(int ln, List<Tok> s) {
        if (s.isEmpty()) return;
        Tok first = s.get(0);
        String w = first.type == T.KEYWORD ? first.text : "";
        Set<Integer> assigned = new java.util.HashSet<>();
        Use assignUse = Use.ASSIGN;
        switch (w) {
            case "LET" -> {
                if (assignedAt(s, 1)) assigned.add(1);
            }
            case "LOCAL" -> {
                assignUse = Use.LOCAL;
                for (int i = 1; i < s.size(); i++)
                    if (s.get(i).type == T.IDENT && (i == 1 || s.get(i - 1).is(","))) {
                        assigned.add(i);
                        if (routine != null) routine.locals.add(key(s.get(i)));
                    }
            }
            case "DIM" -> {
                assignUse = Use.DIM;
                int depth = 0;
                for (int i = 1; i < s.size(); i++) {
                    Tok t = s.get(i);
                    if (opens(t)) depth++;
                    else if (t.is(")")) depth--;
                    else if (depth == 0 && t.type == T.IDENT && (i == 1 || s.get(i - 1).is(","))) assigned.add(i);
                }
            }
            case "FOR" -> {
                if (s.size() > 1 && s.get(1).type == T.IDENT) assigned.add(1);
            }
            case "READ", "INPUT", "SWAP" -> {
                int depth = 0;
                boolean channel = s.size() > 1 && s.get(1).is("#");
                for (int i = 1; i < s.size(); i++) {
                    Tok t = s.get(i);
                    if (opens(t)) depth++;
                    else if (t.is(")")) depth--;
                    else if (t.is(",") && depth == 0) channel = false;
                    else if (depth == 0 && !channel && t.type == T.IDENT && wholeItem(s, i))
                        assigned.add(i);
                }
            }
            case "SYS" -> {
                boolean after = false;
                int depth = 0;
                for (int i = 1; i < s.size(); i++) {
                    Tok t = s.get(i);
                    if (opens(t)) depth++;
                    else if (t.is(")")) depth--;
                    else if (depth == 0 && t.is("TO")) after = true;
                    else if (after && depth == 0 && t.type == T.IDENT && !t.array) assigned.add(i);
                }
            }
            default -> {
                if (assignedAt(s, 0)) assigned.add(0);
            }
        }
        for (int i = 0; i < s.size(); i++) {
            Tok t = s.get(i);
            switch (t.type) {
                case IDENT -> {
                    // BASIC's resident integer variables and @% are always there.
                    Use u = assigned.contains(i) ? assignUse : Use.READ;
                    vars.add(new Var(key(t), site(ln, t), u, routineIndex));
                }
                case PROC, FN -> calls.add(new Call(t.text, site(ln, t)));
                default -> {
                }
            }
        }
    }
}
