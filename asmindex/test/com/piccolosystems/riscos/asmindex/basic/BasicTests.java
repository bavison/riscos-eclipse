package com.piccolosystems.riscos.asmindex.basic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.piccolosystems.riscos.asmindex.basic.BasicFile.Use;
import com.piccolosystems.riscos.asmindex.basic.BasicFile.Var;

/**
 * Tests of the BBC BASIC parser. {@code BasicTests FILE...} instead prints
 * what it found in each file: routines, problems and folds.
 */
public final class BasicTests {
    static int passed, failed;

    static void check(String what, boolean ok, Object detail) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": " + detail);
        }
    }

    static BasicFile parse(String... lines) {
        return new BasicFile(String.join("\n", lines) + "\n");
    }

    static List<String> sites(BasicFile f, String key) {
        List<String> out = new ArrayList<>();
        for (Var v : f.vars)
            if (v.key().equals(key)) out.add((v.site().line() + 1) + ":" + v.site().col() + ":" + v.use() + ":" + v.routine());
        return out;
    }

    static boolean fold(BasicFile f, int from, int to) {
        for (int[] r : f.folds) if (r[0] == from - 1 && r[1] == to - 1) return true;
        return false;
    }

    static String folds(BasicFile f) {
        StringBuilder b = new StringBuilder();
        for (int[] r : f.folds) b.append(r[0] + 1).append('-').append(r[1] + 1).append(' ');
        return b.toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            for (String a : args) dump(Path.of(a));
            return;
        }
        System.out.println("== routines");
        BasicFile f = parse(
                "PROCinit",                                  // 1
                "x=FNdouble(3)",                             // 2
                "END",                                       // 3
                ":",                                         // 4
                "REM Sets up",                               // 5
                "DEF PROCinit",                              // 6
                "LOCAL i%",                                  // 7
                "count%=0",                                  // 8
                "FOR i%=1 TO 10",                            // 9
                "  count%+=i%",                              // 10
                "NEXT",                                      // 11
                "IF count%>3 THEN ENDPROC",                  // 12
                "PRINT count%",                              // 13
                "ENDPROC",                                   // 14
                ":",                                         // 15
                "DEF FNdouble(n)",                           // 16
                "IF n<0 THEN =0",                            // 17
                "=n*2");                                     // 18
        check("two routines", f.routines.size() == 2, f.routines.size());
        check("PROCinit ends at its outer ENDPROC, not the one after THEN",
                f.routines.get(0).name.equals("PROCinit") && f.routines.get(0).endLine == 13, f.routines.get(0).endLine);
        check("FNdouble ends at its = line", f.routines.get(1).endLine == 17, f.routines.get(1).endLine);
        check("signatures", f.routines.get(1).signature.equals("DEF FNdouble(n)"), f.routines.get(1).signature);
        check("folds: each routine and the FOR", fold(f, 6, 14) && fold(f, 16, 18) && fold(f, 9, 11), folds(f));
        check("calls", f.calls.size() == 2 && f.calls.get(0).name().equals("PROCinit")
                && f.calls.get(1).name().equals("FNdouble"), f.calls);
        check("i% is local to PROCinit", f.routines.get(0).locals.contains("i%"), f.routines.get(0).locals);
        check("n is FNdouble's parameter", sites(f, "n").get(0).equals("16:13:PARAMETER:1"), sites(f, "n"));
        check("count% assigned once (+= is a read)",
                sites(f, "count%").stream().filter(s -> s.contains("ASSIGN")).count() == 1, sites(f, "count%"));
        check("x assigned outside any routine", sites(f, "x").equals(List.of("2:0:ASSIGN:-1")), sites(f, "x"));
        check("no problems", f.diags.isEmpty(), f.diags);

        System.out.println("== multi-line IF and CASE");
        f = parse(
                "DEF PROCa(c%)",                             // 1
                "IF c% THEN",                                // 2
                "  PRINT 1",                                 // 3
                "ELSE",                                      // 4
                "  PRINT 2",                                 // 5
                "ENDIF",                                     // 6
                "IF c% THEN PRINT 3 ELSE PRINT 4",           // 7
                "CASE c% OF",                                // 8
                "  WHEN 1: PRINT \"one\"",                   // 9
                "  WHEN 2",                                  // 10
                "    PRINT \"two\"",                         // 11
                "  OTHERWISE",                               // 12
                "    PRINT \"many\"",                        // 13
                "ENDCASE",                                   // 14
                "WHILE c%>0",                                // 15
                "  c%-=1",                                   // 16
                "ENDWHILE",                                  // 17
                "REPEAT:c%+=1:UNTIL c%>9",                   // 18
                "ENDPROC");                                  // 19
        check("IF clause and ELSE clause fold separately", fold(f, 2, 3) && fold(f, 4, 6), folds(f));
        check("CASE folds, and each WHEN", fold(f, 8, 14) && fold(f, 10, 11)
                && fold(f, 12, 13), folds(f));
        check("WHILE folds", fold(f, 15, 17), folds(f));
        check("the routine ends at ENDPROC", f.routines.get(0).endLine == 18, f.routines.get(0).endLine);
        check("no problems", f.diags.isEmpty(), f.diags);

        System.out.println("== tokenising");
        f = parse(
                "TIMEOUT%=TIME+100",                         // TIMEOUT% is a variable; TIME a keyword
                "PRINTtotal",                                // PRINT runs on into the variable
                "a$=\"x:y REM z\":b=1",                      // a colon and REM inside a string
                "REM c=1:d=2",                               // all comment
                "DATA e,f",                                  // all data
                "*FX 4,1",                                   // an OS command
                "DIM blk% 100, arr(10), name$(3,4)",
                "SYS \"OS_Byte\",161,addr% TO ,,val%",
                "READ g, h$",
                "INPUT \"Name\";nm$",
                "LET k=INSTR(a$,\"y\")",
                "foo%=1:foo$=\"\":foo=2.5");
        check("TIMEOUT% is a variable, assigned", sites(f, "TIMEOUT%").equals(List.of("1:0:ASSIGN:-1")), sites(f, "TIMEOUT%"));
        check("PRINTtotal reads total", sites(f, "total").equals(List.of("2:5:READ:-1")), sites(f, "total"));
        check("nothing from inside the string", sites(f, "y").isEmpty() && sites(f, "z").isEmpty(), f.vars);
        check("b after the string", sites(f, "b").equals(List.of("3:15:ASSIGN:-1")), sites(f, "b"));
        check("nothing from REM, DATA or *", sites(f, "c").isEmpty() && sites(f, "e").isEmpty() && sites(f, "FX").isEmpty(), f.vars);
        check("DIM: blk% is a scalar, arr( and name$( arrays, all declared",
                sites(f, "blk%").get(0).contains("DIM") && sites(f, "arr(").get(0).contains("DIM")
                        && sites(f, "name$(").get(0).contains("DIM"), f.vars);
        check("SYS: addr% read, val% assigned", sites(f, "addr%").get(0).contains("READ")
                && sites(f, "val%").get(0).contains("ASSIGN"), sites(f, "val%"));
        check("READ assigns", sites(f, "g").get(0).contains("ASSIGN") && sites(f, "h$").get(0).contains("ASSIGN"), f.vars);
        check("INPUT assigns", sites(f, "nm$").get(0).contains("ASSIGN"), sites(f, "nm$"));
        check("LET assigns, after INSTR(", sites(f, "k").get(0).contains("ASSIGN") && sites(f, "a$").size() == 2, sites(f, "a$"));
        check("foo%, foo$ and foo are three variables", sites(f, "foo%").size() == 1 && sites(f, "foo$").size() == 1
                && sites(f, "foo").size() == 1, f.vars);

        System.out.println("== assembler");
        f = parse(
                "DIM code% 100",
                "FOR pass%=0 TO 2 STEP 2",
                "P%=code%",
                "[OPT pass%",
                ".loop",
                "ORR r0, r0, #mask% ; set the bits: really",
                "SUBS r1, r1, #1",
                "BNE loop",
                "MOV pc, lr",
                "]",
                "NEXT",
                "CALL code%");
        check("the label assigns loop", sites(f, "loop").get(0).contains("ASSIGN"), sites(f, "loop"));
        check("and BNE reads it", sites(f, "loop").size() == 2, sites(f, "loop"));
        check("ORR isn't OR R; mask% is read", sites(f, "R").isEmpty() && sites(f, "mask%").size() == 1, f.vars);
        check("registers aren't variables", sites(f, "r0").isEmpty() && sites(f, "pc").isEmpty(), f.vars);
        check("nothing from the comment", sites(f, "really").isEmpty(), f.vars);
        check("pass% read by OPT", sites(f, "pass%").size() == 2, sites(f, "pass%"));
        check("the assembler block folds, and the FOR", fold(f, 4, 10) && fold(f, 2, 11), folds(f));
        check("no problems", f.diags.isEmpty(), f.diags);

        System.out.println("== line numbers and problems");
        f = parse(
                "10 GOSUB 100",
                "20 IF x THEN 40 ELSE 50",
                "30 ENDIF",
                "40 END",
                "100 RETURN");
        check("line numbers", f.lineNumbers.get(100) == 4 && f.lineNumbers.get(40) == 3, f.lineNumbers);
        check("references to them", f.lineRefs.size() == 3, f.lineRefs);
        check("a stray ENDIF", f.diags.size() == 1 && f.diags.get(0).message().contains("ENDIF without"), f.diags);
        f = parse("DEF PROCx", "IF a THEN", "PRINT", "ENDPROC", "DEF PROCy", "ENDPROC");
        check("an unclosed IF is reported", f.diags.stream().anyMatch(d -> d.message().contains("no ENDIF")), f.diags);
        check("and doesn't swallow the next routine", f.routines.size() == 2 && f.routines.get(1).endLine == 5,
                f.routines.get(1).endLine);
        f = parse("TIME=0:REPEAT UNTIL TIME>=2", "IF d% THEN *ADFS:Close", "PRINT", "REPEAT", "UNTIL TRUE");
        check("REPEAT followed by a statement, and THEN by a * command", f.diags.isEmpty(), f.diags);
        f = parse("FOR I%=1 TO 9", "IF I%=3 THEN NEXT", "PRINT I%", "NEXT", "REPEAT", "IF x THEN REPEAT a+=1", "UNTIL a");
        check("loops opened or closed after a single-line IF are left alone", f.diags.isEmpty(), f.diags);
        f = parse("DEF PROCu(RETURN r%)", "r%=1", "ENDPROC");
        check("a RETURN parameter is local", f.routines.get(0).locals.contains("r%"), f.routines.get(0).locals);
        check("Use enum", Use.values().length == 5, null);
        f = parse("DIM a(3), b$(2)", "a(1)=2", "LET a(2)=a(1)", "READ b$(0), x", "a()=0", "q=a(1)");
        check("an element given a value is an assignment",
                sites(f, "a(").equals(List.of("1:4:DIM:-1", "2:0:ASSIGN:-1", "3:4:ASSIGN:-1", "3:9:READ:-1",
                        "5:0:ASSIGN:-1", "6:2:READ:-1")), sites(f, "a("));
        check("READ an element", sites(f, "b$(").equals(List.of("1:10:DIM:-1", "4:5:ASSIGN:-1")), sites(f, "b$("));

        System.out.println("== editable text");
        {
            byte[] msgs = "Tok:Text\0\nPound:\u00a3\0\n# comment\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            var T = com.piccolosystems.riscos.asmindex.text.EditableText.class;
            var kind = com.piccolosystems.riscos.asmindex.text.EditableText.kindOf(msgs);
            check("NULs found", kind == com.piccolosystems.riscos.asmindex.text.EditableText.Kind.NULS, kind);
            byte[] t = com.piccolosystems.riscos.asmindex.text.EditableText.toText(msgs, kind);
            String shown = new String(t, 3, t.length - 3, java.nio.charset.StandardCharsets.UTF_8);
            check("shown as \u2400, with \u00a3 kept", shown.equals("Tok:Text\u2400\nPound:\u00a3\u2400\n# comment\n"), shown);
            byte[] back = com.piccolosystems.riscos.asmindex.text.EditableText.fromText(t, kind);
            check("and back, byte for byte", java.util.Arrays.equals(back, msgs), new String(back));
            try {
                com.piccolosystems.riscos.asmindex.text.EditableText.fromText("a\nb\u20ac".getBytes(java.nio.charset.StandardCharsets.UTF_8), kind);
                check("a character Latin-1 can't hold is refused", false, null);
            } catch (java.io.IOException e) {
                check("a character Latin-1 can't hold is refused, by line", e.getMessage().startsWith("Line 2"), e.getMessage());
            }
            check("T", T != null, null);
        }

        System.out.println("== tokenising");
        {
            check("GOTO's line number is encoded", hex(tok("10 GOTO 100")).equals("000a0b20e5208d446440"), hex(tok("10 GOTO 100")));
            check("and lists back with numbers shown", detok(tok("10 GOTO 100")).equals("   10 GOTO 100\n"), detok(tok("10 GOTO 100")));
            for (int v : new int[] {0, 1, 100, 255, 256, 4095, 65279}) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                com.piccolosystems.riscos.asmindex.text.BasicTokens.encodeConstant(v, o);
                byte[] b = o.toByteArray();
                int back = com.piccolosystems.riscos.asmindex.text.BasicTokens.decodeConstant(b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF);
                check("line number " + v + " encodes and decodes", back == v, back);
            }
            check("P. is PRINT", hex(tok("P.\"x\"")).equals("000a08f1227822"), hex(tok("P.\"x\"")));
            check("an ELSE starting a line is the multi-line ELSE", hex(tok("ELSE")).equals("000a05cc"), hex(tok("ELSE")));
            check("but not after a statement", hex(tok("a=1 ELSE")).contains("8b"), hex(tok("a=1 ELSE")));
            check("TIME on the left is the statement token", hex(tok("TIME=0")).startsWith("000a07d1"), hex(tok("TIME=0")));
            check("on the right, the function", hex(tok("t=TIME")).equals("000a07743d91"), hex(tok("t=TIME")));
            check("SYS is two bytes", hex(tok("SYS 1")).startsWith("000a08c899"), hex(tok("SYS 1")));
            check("SUM is a two-byte function", hex(tok("x=SUM a()")).contains("c68e"), hex(tok("x=SUM a()")));
            check("a PROC's name isn't tokenised", hex(tok("PROCPRINT")).equals("000a0af25052494e54"), hex(tok("PROCPRINT")));
            check("nor what follows REM", hex(tok("REM PRINT")).equals("000a0bf4205052494e54"), hex(tok("REM PRINT")));
            check("nor a string", hex(tok("PRINT \"PRINT\"")).contains("225052494e5422"), hex(tok("PRINT \"PRINT\"")));
            check("nor lower case", hex(tok("print")).equals("000a097072696e74"), hex(tok("print")));
            check("TIMEOUT% is a variable", hex(tok("TIMEOUT%=1")).startsWith("000a0e54494d454f555425"), hex(tok("TIMEOUT%=1")));
            check("a * command is left alone", hex(tok("*PRINT")).equals("000a0a2a5052494e54"), hex(tok("*PRINT")));
            check("keywords run together list spaced, to read back the same", detok(prog("000a06a29f")).equals("EXT ERR\n"),
                    detok(prog("000a06a29f")));
            byte[] p = com.piccolosystems.riscos.asmindex.text.BasicTokens.tokenise("PROCa\nEND\n", 10, 10).data();
            byte[] q = com.piccolosystems.riscos.asmindex.text.BasicTokens.tokenise("PROCa\nPRINT\nEND\n", p).data();
            check("an inserted line is numbered between its neighbours", hex(q).contains("000f05f10d"), hex(q));
            check("and the others keep theirs", hex(q).startsWith("0d000a06f2610d") && hex(q).contains("001405e00d"), hex(q));
        }

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    /** A one-line program, tokenised, as hex without its first CR and the line's CR and the end. */
    static byte[] tok(String line) {
        byte[] d = com.piccolosystems.riscos.asmindex.text.BasicTokens.tokenise(line + "\n", 10, 10).data();
        return java.util.Arrays.copyOfRange(d, 1, d.length - 2);
    }

    static String hex(byte[] b) {
        return java.util.HexFormat.of().formatHex(b);
    }

    /** A program of one line given as hex (number, length, tokens). */
    static byte[] prog(String line) {
        byte[] l = java.util.HexFormat.of().parseHex(line);
        byte[] d = new byte[l.length + 3];
        d[0] = 13;
        System.arraycopy(l, 0, d, 1, l.length);
        d[l.length + 1] = 13;
        d[l.length + 2] = (byte) 0xFF;
        return d;
    }

    static String detok(byte[] lineWithoutEnds) {
        byte[] d = lineWithoutEnds;
        if (d.length == 0 || d[0] != 13) {
            byte[] p = new byte[d.length + 3];
            p[0] = 13;
            System.arraycopy(d, 0, p, 1, d.length);
            p[d.length + 1] = 13;
            p[d.length + 2] = (byte) 0xFF;
            d = p;
        }
        return com.piccolosystems.riscos.asmindex.text.BasicTokens.detokenise(d).text();
    }

    static void dump(Path p) throws Exception {
        long t = System.nanoTime();
        BasicFile f = new BasicFile(new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1));
        long ms = (System.nanoTime() - t) / 1_000_000;
        System.out.println(p + ": " + f.lines.size() + " lines, " + ms + " ms, " + f.routines.size() + " routines, "
                + f.calls.size() + " calls, " + f.vars.size() + " variable sites, " + f.folds.size() + " folds");
        for (BasicFile.Routine r : f.routines)
            if (r.endLine < 0) System.out.println("  no end: " + r.name);
        for (BasicFile.Diag d : f.diags)
            System.out.println("  " + (d.site().line() + 1) + ": " + d.message() + "    | " + f.lines.get(d.site().line()).strip());
    }
}
