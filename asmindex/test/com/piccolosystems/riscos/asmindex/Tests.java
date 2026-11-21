package com.piccolosystems.riscos.asmindex;

import com.piccolosystems.riscos.asmindex.expr.EvalContext;
import com.piccolosystems.riscos.asmindex.expr.Evaluator;
import com.piccolosystems.riscos.asmindex.expr.Value;
import com.piccolosystems.riscos.asmindex.macro.MacroSyntax;
import com.piccolosystems.riscos.asmindex.parse.Mnemonics;
import com.piccolosystems.riscos.asmindex.run.Assembler;
import com.piccolosystems.riscos.asmindex.run.Diagnostic;
import com.piccolosystems.riscos.asmindex.run.EntryPoint;
import com.piccolosystems.riscos.asmindex.source.FileResolver;
import com.piccolosystems.riscos.asmindex.source.SourceFile;
import com.piccolosystems.riscos.asmindex.source.Text;
import com.piccolosystems.riscos.asmindex.subst.Substituter;
import com.piccolosystems.riscos.asmindex.sym.Symbol;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests of the engine, one group per part of its behaviour. Self-contained (no
 * JUnit), so they build with javac alone: run with
 * {@code java -cp build/classes:build/test-classes com.piccolosystems.riscos.asmindex.Tests}.
 */
public final class Tests {
    private static int passed, failed;
    private static String group = "";

    public static void main(String[] args) throws Exception {
        lines();
        substitution();
        expressions();
        precedence();
        macroSyntax();
        mnemonics();
        variablesAndSubstitutionInSource();
        conditionals();
        whileLoops();
        macros();
        localLabels();
        storageMaps();
        defAcrossPasses();
        addSwiGolden();
        literalPools();
        unknownConditions();
        getResolution();
        shadingOfMacroBodies();
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ helpers

    private static void group(String g) { group = g; System.out.println("== " + g); }

    private static void check(String what, boolean ok) {
        if (ok) passed++;
        else { failed++; System.out.println("FAIL [" + group + "] " + what); }
    }

    private static void eq(String what, Object want, Object got) {
        boolean ok = want == null ? got == null
                : want instanceof Number && got instanceof Number ? ((Number) want).longValue() == ((Number) got).longValue()
                : want.equals(got);
        if (ok) passed++;
        else { failed++; System.out.println("FAIL [" + group + "] " + what + ": want <" + want + "> got <" + got + ">"); }
    }

    /** A context with a few fixed symbols, for expression tests. */
    private static final class Ctx implements EvalContext {
        final Map<String, Value> syms = new HashMap<>();
        @Override public Value symbol(String n, int p, int e) { Value v = syms.get(n); return v == null ? Value.undefined(n) : v; }
        @Override public Value builtin(String n) { return n.equals("TRUE") ? Value.TRUE : n.equals("FALSE") ? Value.FALSE : Value.unknown(n); }
        @Override public Value pc() { return Value.progRel(1, 0x100); }
        @Override public Value storageCounter() { return Value.regRel(1 << 12, 8); }
        @Override public boolean isDefined(String n, int p, int e) { return syms.containsKey(n); }
        @Override public boolean isMacro(String n) { return false; }
        @Override public Value localLabel(char d, char l, int n, String r, int p, int e) { return Value.unknown("ll"); }
        @Override public Value sizeOf(String n, int p, int e) { return Value.num(4); }
    }

    private static final Ctx CTX = new Ctx();
    static {
        CTX.syms.put("a", Value.num(2));
        CTX.syms.put("b", Value.num(3));
        CTX.syms.put("c", Value.num(4));
        CTX.syms.put("lab1", Value.progRel(1, 0x10));
        CTX.syms.put("lab2", Value.progRel(1, 0x30));
        CTX.syms.put("field", Value.regRel(1 << 12, 0x1C));
    }

    private static Value ev(String s) { return Evaluator.evaluate(s, CTX).value; }

    private static long num(String s) {
        Value v = ev(s);
        return v.isNum() ? v.unsigned() : -1;
    }

    /** Assembles files written to a temporary directory; the first file is the entry point. */
    private static Assembler asm(Map<String, String> files, String... predefines) throws Exception {
        Path dir = Files.createTempDirectory("asmtest");
        Path first = null;
        for (Map.Entry<String, String> f : files.entrySet()) {
            Path p = dir.resolve(f.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, f.getValue());
            if (first == null) first = p;
        }
        Map<String, List<String>> pathVars = new HashMap<>();
        pathVars.put("HDR", List.of(dir.resolve("hdr").toString()));
        EntryPoint e = new EntryPoint(first, dir, List.of(dir.resolve("inc").toString()), List.of(predefines),
                List.of("-APCS", "3/32bit/fpe3", "-cpu", "6Z"));
        Assembler a = new Assembler(e, new FileResolver(Map.of("MYDIR", dir.toString()), pathVars, List.of(dir.resolve("inc")), dir),
                new HashMap<>());
        a.run();
        return a;
    }

    private static Assembler asm(String src, String... predefines) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("main.s", src);
        return asm(m, predefines);
    }

    private static String val(Assembler a, String name) {
        Symbol s = a.symbols.get(name);
        return s == null || s.value == null ? null : s.value.display();
    }

    private static List<String> errors(Assembler a) {
        List<String> l = new ArrayList<>();
        for (Diagnostic d : a.diagnostics()) if (d.severity == Diagnostic.Severity.ERROR) l.add(d.toString());
        return l;
    }

    /** Classification of a line of main.s: 0 assembled, 1 shaded, 2 unknown, 3 skipped in every expansion. */
    private static int line(Assembler a, int n) {
        for (Map.Entry<SourceFile, int[][]> e : a.lineCounts.entrySet())
            if (e.getKey().name.endsWith("main.s")) return Assembler.classify(e.getValue()[n]);
        return -1;
    }

    // ------------------------------------------------------------------ tests

    static void lines() {
        group("lines and continuation");
        SourceFile f = SourceFile.fromText(Path.of("x"), "a\r\nb\n\rc\\\n  d\re\tf");
        eq("logical lines", 4, f.size());
        eq("continuation joined", "c   d", f.line(2).text);
        eq("physical line of joined part", 4, f.line(2).lineOf(3));
        eq("tab becomes a space", "e f", f.line(3).text);
        eq("line numbers advance", 5, f.line(3).firstLine);
    }

    static void substitution() {
        group("substitution");
        SourceFile f = SourceFile.fromText(Path.of("x"), "$SWIClass._$SWIName $$x |a$b| \"$s\" ; $c");
        Map<String, Value> vars = Map.of("SWIClass", Value.str("OS"), "SWIName", Value.str("WriteC"),
                "s", Value.str("str"), "c", Value.str("C"), "b", Value.str("B"));
        Substituter.Result r = Substituter.variables(f.line(0).toText(), vars::get);
        eq("variables", "OS_WriteC $x |a$b| \"str\" ; $c", r.text.s);
        eq("definition site maps back", 0, r.text.originCol(0));
        Substituter.Result n = Substituter.variables(SourceFile.fromText(Path.of("x"), "$n $l").line(0).toText(),
                Map.of("n", Value.num(10), "l", Value.TRUE)::get);
        eq("arithmetic is 8 hex digits", "0000000A T", n.text.s);
        Substituter.Result u = Substituter.variables(SourceFile.fromText(Path.of("x"), "$nope").line(0).toText(), x -> null);
        eq("non-variable left alone", "$nope", u.text.s);
        eq("non-variable reported", List.of("nope"), u.notVariables);
        Text m = Substituter.macroParams(SourceFile.fromText(Path.of("x"), "$label ADD$cc $p1.x ; $p1 $$v").line(0).toText(),
                Map.of("label", "L", "cc", "EQ", "p1", "r0"));
        eq("macro parameters, including comments; $$ kept as $", "L ADDEQ r0x ; r0 $v", m.s);
    }

    static void expressions() {
        group("expressions");
        eq("hex &", 0x1F, num("&1F"));
        eq("hex 0x", 0x1F, num("0x1f"));
        eq("based", 11, num("2_1011"));
        eq("char", 65, num("'A'"));
        eq("one-char string as number", 66, num("\"A\" + 1"));
        eq("unsigned wrap", 0xFFFFFFFFL, num("0 - 1"));
        eq("unsigned division", 0x7FFFFFFFL, num("-1 / 2"));
        eq("shift by 32", 0, num("1 :SHL: 32"));
        eq("ROR", 0x80000000L, num("1 :ROR: 1"));
        eq("label difference", 0x20, num("lab2 - lab1"));
        eq(":INDEX: of field", 0x1C, num(":INDEX: field"));
        eq(":BASE: of field", 12, num(":BASE: field"));
        eq(":BASE: of a label is the PC", 15, num(":BASE: lab1"));
        eq(":LEN:", 5, num(":LEN: \"hello\""));
        eq(":STR:", "0000002A", ev(":STR: 42").str);
        eq(":CC: / :LEFT: / :RIGHT:", "heo", ev("(\"hello\" :LEFT: 2) :CC: (\"hello\" :RIGHT: 1)").str);
        eq(":UPPERCASE:", "ABC", ev(":UPPERCASE: \"aBc\"").str);
        eq(":REVERSE_CC:", "NE", ev(":REVERSE_CC: \"EQ\"").str);
        eq("unsigned compare", true, ev("-1 > 0").bool);
        eq("string compare", true, ev("\"abc\" < \"abd\"").bool);
        eq("different types: =", false, ev("1 = \"xy\"").bool);
        eq("different types: <>", true, ev("1 <> \"xy\"").bool);
        eq(":DEF: true", true, ev(":DEF: a").bool);
        eq(":DEF: false", false, ev(":DEF: zz").bool);
        eq("undefined symbol", Value.Kind.UNDEFINED, ev("zz + 1").kind);
        eq("unknown spreads", Value.Kind.UNKNOWN, ev("{X} + 1").kind);
        eq("{FALSE} :LAND: unknown", false, ev("{FALSE} :LAND: {X}").bool);
        eq("{TRUE} :LOR: unknown", true, ev("{TRUE} :LOR: {X}").bool);
        eq("word operator closed by a space", 3, num("1 :OR (2)"));
        eq(":FSIZE: is unknown", Value.Kind.UNKNOWN, ev(":FSIZE: \"f\"").kind);
        Evaluator.Result wb = Evaluator.evaluatePrefix("r0!", 0, 3, CTX);
        eq("trailing ! ends the expression", 2, wb.stop);
    }

    static void precedence() {
        group("precedence");
        eq("shift binds tighter than +", 17, num("1 + 2 :SHL: 3"));
        eq(":AND: is at the level of +", (2 + 3) & 4, num("a + b :AND: c"));
        eq("* before +", 14, num("2 + 3 * 4"));
        eq("relational below additive", true, ev("1 + 1 = 2").bool);
        eq("logical lowest", true, ev("1 = 1 :LAND: 2 > 1").bool);
        eq("left associative", 1, num("8 / 4 / 2"));
        eq("unary minus", 0xFFFFFFFEL, num("-a"));
        eq(":CC: between * and shifts", "ab", ev("\"a\" :CC: \"b\"").str);
    }

    static void macroSyntax() {
        group("macro syntax");
        MacroSyntax.Prototype p = MacroSyntax.prototype("$lab  NAME$cc  $p1, $p2=def, $p3=\"q, \"\"x\"\"\"");
        eq("label param", "lab", p.label);
        eq("name", "NAME", p.name);
        eq("suffix", "cc", p.suffix);
        eq("params", List.of("p1", "p2", "p3"), p.params);
        eq("defaults", List.of("", "def", "q, \"x\""), p.defaults);
        eq("barred name", "#", MacroSyntax.prototype("$var |#| $bytes").name);
        MacroSyntax.Args a = MacroSyntax.arguments(" r0 , |, \"a, \"\"b\"\"\"  , ; comment", 0);
        eq("args", 4, a.values.size());
        eq("trimmed", "r0", a.values.get(0));
        eq("| means default", null, a.values.get(1));
        eq("quoted", "a, \"b\"", a.values.get(2));
        eq("empty is empty, not default", "", a.values.get(3));
        eq("spaces after a quoted argument", "r0-r8", MacroSyntax.arguments("\"r0-r8\" , 8*4", 0).values.get(0));
    }

    static void mnemonics() {
        group("instructions vs macros");
        for (String m : new String[] {"MOV", "movs", "ADDEQS", "ADDSEQ", "LDREQB", "LDRBEQ", "STMFD", "LDMNEDB", "BL",
                "SWINE", "ADRL", "ADRNEL", "ADRLNE", "VADD.F32", "FLDD", "LDFD", "MUFEZ", "ITTE", "push", "NOP", "MRS"})
            check(m + " is an instruction", Mnemonics.isInstruction(m));
        for (String m : new String[] {"Push", "Pull", "EXIT", "Entry", "addr", "MOV32x", "BSD", "SETV", "mov.q"})
            check(m + " is not an instruction", !Mnemonics.isInstruction(m));
    }

    static void variablesAndSubstitutionInSource() throws Exception {
        group("variables");
        Assembler a = asm(String.join("\n",
                "        GBLA    n",
                "n       SETA    10",
                "        GBLS    s",
                "s       SETS    \"Foo\"",
                "$s._$n  *       n + 1",
                "        GBLA    n",          // re-declaring resets
                "Reset   *       n",
                "        END", ""), "Pre SETL {TRUE}");
        eq("symbol built by substitution", "&0000000B", val(a, "Foo_0000000A"));
        eq("GBL resets", "&00000000", val(a, "Reset"));
        eq("predefine", "{TRUE}", val(a, "Pre"));
        eq("no errors", List.of(), errors(a));
    }

    static void conditionals() throws Exception {
        group("IF/ELIF/ELSE");
        Assembler a = asm(String.join("\n",
                "        GBLA    x",
                "x       SETA    2",
                "        [ x = 1",         // 3
                "A1      *       1",        // 4 shaded
                "        ELIF x = 2",       // 5
                "A2      *       2",        // 6 assembled
                "        ELIF x = 2",       // 7
                "A3      *       3",        // 8 shaded: an earlier clause was taken
                "        |",                // 9
                "A4      *       4",        // 10 shaded
                "        ]",                // 11
                "        [ {FALSE}",        // 12
                "        [ {TRUE}",         // 13 inside a skipped region: not evaluated
                "B1      *       1",        // 14
                "        ]",                // 15
                "        ]",                // 16
                "        END", ""));
        check("A2 defined", a.symbols.get("A2") != null && a.symbols.get("A2").isDefined());
        check("A1, A3, A4 not defined", !a.symbols.contains("A1") && !a.symbols.contains("A3") && !a.symbols.contains("A4"));
        eq("line 4 shaded", 1, line(a, 4));
        eq("line 6 assembled", 0, line(a, 6));
        eq("line 8 shaded", 1, line(a, 8));
        eq("line 10 shaded", 1, line(a, 10));
        eq("IF line itself not shaded", 0, line(a, 3));
        eq("nested skip shaded", 1, line(a, 14));
        eq("no errors", List.of(), errors(a));
        Assembler b = asm(String.join("\n", "        [ {TRUE}", "X *1", "        |", "Y *2", "        |", "Z *3", "        ]", "        END", ""));
        check("ELSE after ELSE is a warning", b.diagnostics().stream().anyMatch(d -> d.code.equals("else-after-else")));
        eq("after the second ELSE is skipped", 1, line(b, 6));
    }

    static void whileLoops() throws Exception {
        group("WHILE");
        Assembler a = asm(String.join("\n",
                "        GBLA    i",
                "        WHILE   i < 5",
                "Sym$i   *       i * 2",
                "i       SETA    i + 1",
                "        WEND",
                "        WHILE   {FALSE}",
                "Never   *       1",
                "        WEND",
                "        END", ""));
        eq("first iteration", "&00000000", val(a, "Sym00000000"));
        eq("last iteration", "&00000008", val(a, "Sym00000004"));
        check("no sixth", !a.symbols.contains("Sym00000005"));
        eq("body of false WHILE shaded", 1, line(a, 7));
        eq("loop body assembled", 0, line(a, 3));
    }

    static void macros() throws Exception {
        group("macros");
        Assembler a = asm(String.join("\n",
                "        MACRO",
                "$l      Def$cc  $name, $val=7",
                "$l._$name$cc *  $val",
                "        MEND",
                "        MACRO",
                "        DefLong $x",
                "Long_$x *       1",
                "        MEND",
                "        MACRO",
                "$var    |#|     $n",
                "$var    *       $n",
                "        MEND",
                "        MACRO",
                "        Loop",
                "        LCLA    k",
                "        WHILE   k < 2",
                "        LCLA    inner",           // re-declared each iteration: allowed
                "k       SETA    k + 1",
                "        WEND",
                "Looped  *       k",
                "        MEND",
                "P       Def     a, 1",
                "Q       DefEQ   b, |",
                "R       Def     c,",
                "        DefLong y",               // longest prefix: DefLong, not Def
                "W       |#|     5",
                "        Loop",
                "        END", ""));
        eq("label, name and value", "&00000001", val(a, "P_a"));
        eq("suffix and default via |", "&00000007", val(a, "Q_bEQ"));
        check("empty argument is empty, not the default (R_c * <nothing> fails)", !a.symbols.contains("R_c") || !a.symbols.get("R_c").isDefined());
        eq("longest prefix", "&00000001", val(a, "Long_y"));
        eq("barred macro name", "&00000005", val(a, "W"));
        eq("LCLA re-declared in a WHILE", "&00000002", val(a, "Looped"));
        check("definition site is in the macro, instantiated from the call",
                a.symbols.get("P_a").defs.get(0).line == 3 && a.symbols.get("P_a").defs.get(0).via.line == 22);
    }

    static void localLabels() throws Exception {
        group("local labels");
        Assembler a = asm(String.join("\n",
                "        AREA    |C|, CODE",
                "Start   ROUT",
                "01      MOV     r0, #1",           // 0
                "        B       %F02",             // 4
                "        B       %B01",             // 8
                "02      MOV     r0, #2",           // 12
                "02      MOV     r0, #3",           // 16
                "Fwd     *       %F02",             // nothing after in this scope
                "Back    *       %B02",
                "Other   ROUT",
                "02      MOV     r0, #4",           // 20
                "        END", ""));
        eq("local labels defined", 4, a.localLabels.size());
        eq("%F02 from line 4 resolves to the first 02", 1, a.localLabels.get(1).refs.size());
        eq("%B01 resolves", 1, a.localLabels.get(0).refs.size());
        eq("%B02 finds the nearest", "area#1 + &10", val(a, "Back"));
        check("search does not cross ROUT", errors(a).stream().anyMatch(e -> e.contains("local label")));
        check("ROUT label is a routine", a.symbols.get("Other").routine);
    }

    static void storageMaps() throws Exception {
        group("storage maps");
        Assembler a = asm(String.join("\n",
                "        ^       0, R12",
                "WsA     #       4",
                "WsB     #       8",
                "WsC     #       0",
                "        ^       &100",
                "Abs     #       2",
                "Next    *       @",
                "SizeB   *       ?WsB",
                "        END", ""));
        eq("register-relative field", "R12 + &0", val(a, "WsA"));
        eq("advances", "R12 + &4", val(a, "WsB"));
        eq("after 8", "R12 + &C", val(a, "WsC"));
        eq("absolute field", "&00000100", val(a, "Abs"));
        eq("@ is the counter", "&00000102", val(a, "Next"));
        eq("?field", "&00000008", val(a, "SizeB"));
        eq("kind", Symbol.Kind.FIELD, a.symbols.get("WsA").kind);
    }

    static void defAcrossPasses() throws Exception {
        group(":DEF: and the two passes");
        Assembler a = asm(String.join("\n",
                "        AREA    |C|, CODE",
                "        [ :DEF: Later",          // false in pass 1, true in pass 2
                "        MOV     r0, #1",         // assembled in pass 2
                "        ]",
                "Later   MOV     r0, #2",
                "        [ :DEF: Var",            // variables: true only once declared in pass 2
                "Bad     *       1",
                "        ]",
                "        GBLL    Var",
                "        END", ""));
        eq("pass 2 decides: assembled", 0, line(a, 3));
        eq("label offset follows pass 2", "area#1 + &4", val(a, "Later"));
        eq("variable not yet declared in pass 2", 1, line(a, 7));
    }

    static void addSwiGolden() throws Exception {
        group("AddSWI");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("main.s", String.join("\n",
                "        GET     Hdr:Macros",
                "        GET     Hdr:SWIs",
                "        END", ""));
        files.put("hdr/Macros", String.join("\n",
                "        GBLS    SWIClass",
                "        MACRO",
                "        AddSWI  $SWIName,$value",
                "  [     \"$value\" = \"\"",
                "$SWIClass._$SWIName # 1",
                "  |",
                "$SWIClass._$SWIName * $value",
                "  ]",
                "X$SWIClass._$SWIName * $SWIClass._$SWIName + Auto_Error_SWI_bit",
                "        MEND",
                "        END", ""));
        files.put("hdr/SWIs", String.join("\n",
                "Auto_Error_SWI_bit * 1 :SHL: 17",
                "SWIClass SETS   \"OS\"",
                "        ^       0",
                "        AddSWI  WriteC",
                "        AddSWI  WriteS",
                "        AddSWI  Byte, 6",
                "        END", ""));
        Assembler a = asm(files);
        eq("OS_WriteC", "&00000000", val(a, "OS_WriteC"));
        eq("XOS_WriteC", "&00020000", val(a, "XOS_WriteC"));
        eq("OS_WriteS", "&00000001", val(a, "OS_WriteS"));
        eq("OS_Byte with a value", "&00000006", val(a, "OS_Byte"));
        eq("no errors", List.of(), errors(a));
    }

    static void literalPools() throws Exception {
        group("literal pools");
        Assembler a = asm(String.join("\n",
                "        AREA    |C|, CODE",
                "        LDR     r0, =&12345678",    // literal
                "        LDR     r1, =&FF000000",    // rotatable: MOV
                "        LDR     r2, =&FFFFFF00",    // MVN
                "        LDR     r3, =&12345678",    // shared
                "        LTORG",
                "After   MOV     r0, r0",
                "        LDR     r0, =Fwd",          // undefined in pass 1: its own slot
                "        LDR     r0, =Fwd",
                "        LTORG",
                "Fwd     *       1",
                "After2  MOV     r0, r0",
                "        ADRL    r0, After",
                "End     DCB     \"ab\", 0",
                "        ALIGN",
                "End2    DCD     1",
                "        END", ""));
        eq("4 instructions + 1 literal", "area#1 + &14", val(a, "After"));
        eq("two undefined literals, one slot each", "area#1 + &28", val(a, "After2"));
        eq("ADRL is 8 bytes", "area#1 + &34", val(a, "End"));
        eq("ALIGN after 3 bytes", "area#1 + &38", val(a, "End2"));
        eq("area size", 0x3CL, a.finalAreas.get("C")[0]);
    }

    static void unknownConditions() throws Exception {
        group("unknown conditions");
        Assembler a = asm(String.join("\n",
                "        GBLA    v",
                "v       SETA    1",
                "        [ :FSIZE: \"x\" > 0",    // 3: unknown
                "Both    *       1",               // 4
                "v       SETA    2",
                "        |",
                "Both    *       2",               // 7
                "        ]",
                "        [ v = 1",                 // 9: v is now unknown
                "C       *       1",
                "        ]",
                "        [ {TRUE}",
                "D       *       1",
                "        ]",
                "        END", ""));
        eq("unknown branch not shaded", 2, line(a, 4));
        eq("else branch not shaded", 2, line(a, 7));
        check("symbol flagged conditional", a.symbols.get("Both").conditional);
        eq("value unknown", Value.Kind.UNKNOWN, a.symbols.get("Both").value.kind);
        check("condition on a variable set in an undecided branch is unknown", a.unknownConditions.size() == 2);
        eq("decidable conditions still decided", 0, line(a, 13));
        eq("no errors", List.of(), errors(a));
    }

    static void getResolution() throws Exception {
        group("GET resolution");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("main.s", String.join("\n",
                "        GET     Hdr:One",            // path variable
                "        GET     s.Two",              // RISC OS name with a suffix directory
                "        GET     Three",              // -I directory
                "        GET     <MyDir>/sub/Four",   // environment variable
                "        GET     Missing",
                "        END", ""));
        files.put("hdr/One", "One * 1\n END\n");
        files.put("Two.s", "Two * 2\n END\n");
        files.put("inc/Three", "Three * 3\n END\n");
        files.put("sub/Four", "Four * 4\n END\n");
        Assembler a = asm(files);
        for (String s : new String[] {"One", "Two", "Three", "Four"}) check(s + " found", a.symbols.contains(s) && a.symbols.get(s).isDefined());
        check("missing reported", a.diagnostics().stream().anyMatch(d -> d.code.equals("get-unresolved")));
    }

    static void shadingOfMacroBodies() throws Exception {
        group("shading of macro bodies");
        Assembler a = asm(String.join("\n",
                "        MACRO",
                "        M       $x",
                "        [ \"$x\" = \"a\"",
                "A$x     *       1",       // 4: assembled in one call
                "        |",
                "B$x     *       1",       // 6: assembled in the other
                "        ]",
                "        [ {FALSE}",
                "Never$x *       1",       // 9: skipped in every expansion
                "        ]",
                "        MEND",
                "        M       a",
                "        M       b",
                "        END", ""));
        eq("taken in one expansion", 0, line(a, 4));
        eq("taken in the other", 0, line(a, 6));
        eq("skipped in every expansion: kept apart, not shaded", 3, line(a, 9));
    }
}
