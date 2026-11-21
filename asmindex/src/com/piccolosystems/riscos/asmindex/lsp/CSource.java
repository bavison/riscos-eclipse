package com.piccolosystems.riscos.asmindex.lsp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the assembler side needs to know about a C file, by name, as the
 * linker joins C and assembler: where each identifier is used, which
 * functions and variables it defines with external linkage, which it
 * declares (prototypes, {@code extern}), and what it includes. It is a
 * lexical reading, not a parse: comments, strings and character constants
 * are skipped, and declarations are recognised at the outermost level.
 */
final class CSource {
    /** A place in the file: 0-based line and column, and length. */
    record Occ(int line, int col, int len) {}

    /** An {@code #include}: the name, and whether it was {@code "quoted"} (else {@code <angled>}). */
    record Include(String name, boolean quoted) {}

    final Map<String, List<Occ>> uses = new HashMap<>();
    final Map<String, List<Occ>> definitions = new HashMap<>();
    final Map<String, List<Occ>> declarations = new HashMap<>();
    final List<Include> includes = new ArrayList<>();

    private static final Set<String> KEYWORDS = Set.of("auto", "break", "case", "char", "const", "continue",
            "default", "do", "double", "else", "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long",
            "register", "restrict", "return", "short", "signed", "sizeof", "static", "struct", "switch", "typedef",
            "union", "unsigned", "void", "volatile", "while", "_Bool", "__inline", "__asm", "__value_in_regs",
            "__pure", "__swi", "__irq", "__packed", "__global_reg", "__weak", "__caller_narrow");

    private record Tok(String text, int line, int col, boolean ident) {}

    static CSource scan(String text) {
        CSource c = new CSource();
        List<Tok> toks = new ArrayList<>();
        int n = text.length(), line = 0, lineStart = 0, i = 0;
        boolean atLineStart = true;
        while (i < n) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                line++;
                lineStart = ++i;
                atLineStart = true;
                continue;
            }
            if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\f') {
                i++;
                continue;
            }
            if (ch == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                while (i < n && text.charAt(i) != '\n') i++;
                continue;
            }
            if (ch == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                i += 2;
                while (i < n && !(text.charAt(i) == '*' && i + 1 < n && text.charAt(i + 1) == '/')) {
                    if (text.charAt(i) == '\n') {
                        line++;
                        lineStart = i + 1;
                    }
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            if (atLineStart && ch == '#') {
                // A preprocessor line (with continuations): #include is noted; the
                // rest contributes uses only (a macro body may call a function).
                int end = i;
                while (end < n && !(text.charAt(end) == '\n' && text.charAt(end - 1) != '\\')) end++;
                String dir = text.substring(i, end);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^#\\s*include\\s*([\"<])([^\">]+)[\">]").matcher(dir);
                if (m.find()) {
                    c.includes.add(new Include(m.group(2).trim(), m.group(1).equals("\"")));
                } else {
                    int dl = line, ds = lineStart;
                    for (int k = i + 1; k < end; ) {
                        char x = text.charAt(k);
                        if (x == '\n') {
                            dl++;
                            ds = k + 1;
                            k++;
                        } else if (Character.isJavaIdentifierStart(x) && x != '$') {
                            int s = k;
                            while (k < end && (Character.isLetterOrDigit(text.charAt(k)) || text.charAt(k) == '_')) k++;
                            c.use(text.substring(s, k), dl, s - ds);
                        } else if (x == '"' || x == '\'') {
                            k = skipQuoted(text, k, end);
                        } else {
                            k++;
                        }
                    }
                }
                for (int k = i; k < end; k++) if (text.charAt(k) == '\n') { line++; lineStart = k + 1; }
                i = end;
                continue;
            }
            atLineStart = false;
            if (ch == '"' || ch == '\'') {
                i = skipQuoted(text, i, n);
                toks.add(new Tok("\"", line, i - lineStart, false));
                continue;
            }
            if (Character.isLetter(ch) || ch == '_') {
                int s = i;
                while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) i++;
                String w = text.substring(s, i);
                toks.add(new Tok(w, line, s - lineStart, !KEYWORDS.contains(w)));
                continue;
            }
            if (Character.isDigit(ch)) {
                while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '.')) i++;
                toks.add(new Tok("0", line, i - lineStart, false));
                continue;
            }
            toks.add(new Tok(String.valueOf(ch), line, i - lineStart, false));
            i++;
        }
        for (Tok t : toks) if (t.ident) c.use(t.text, t.line, t.col);
        c.declarations(toks);
        return c;
    }

    private static int skipQuoted(String text, int i, int end) {
        char q = text.charAt(i++);
        while (i < end && text.charAt(i) != q && text.charAt(i) != '\n') {
            if (text.charAt(i) == '\\') i++;
            i++;
        }
        return Math.min(end, i + 1);
    }

    private void use(String name, int line, int col) {
        if (KEYWORDS.contains(name)) return;
        uses.computeIfAbsent(name, k -> new ArrayList<>()).add(new Occ(line, col, name.length()));
    }

    private static void add(Map<String, List<Occ>> m, Tok t) {
        m.computeIfAbsent(t.text, k -> new ArrayList<>()).add(new Occ(t.line, t.col, t.text.length()));
    }

    /** The outermost declarations: function definitions, prototypes, variables. */
    private void declarations(List<Tok> toks) {
        int depth = 0;
        boolean functionBody = false;
        List<Tok> stmt = new ArrayList<>();
        for (Tok t : toks) {
            if (t.text.equals("{")) {
                if (depth == 0) {
                    Tok f = function(stmt);
                    functionBody = f != null;
                    if (f != null && !has(stmt, "static") && !has(stmt, "typedef")) add(definitions, f);
                }
                depth++;
                continue;
            }
            if (t.text.equals("}")) {
                if (depth > 0) depth--;
                if (depth == 0 && functionBody) {
                    stmt.clear();
                    functionBody = false;
                }
                continue;
            }
            if (depth > 0) continue;
            if (t.text.equals(";")) {
                statement(stmt);
                stmt.clear();
                continue;
            }
            stmt.add(t);
        }
    }

    private static boolean has(List<Tok> stmt, String word) {
        for (Tok t : stmt) if (t.text.equals(word)) return true;
        return false;
    }

    /** The name a statement declares as a function (the identifier before its first outermost '('), or null. */
    private static Tok function(List<Tok> stmt) {
        int paren = 0;
        for (int k = 0; k < stmt.size(); k++) {
            String s = stmt.get(k).text;
            if (s.equals("=") && paren == 0) return null;          // an initialiser
            if (s.equals("(")) {
                if (paren == 0) {
                    Tok before = k > 0 ? stmt.get(k - 1) : null;
                    return before != null && before.ident ? before : null;
                }
                paren++;
            } else if (s.equals(")")) {
                paren--;
            }
        }
        return null;
    }

    /** A statement ending ';' at the outermost level: prototypes, extern and defined variables. */
    private void statement(List<Tok> stmt) {
        if (stmt.isEmpty() || has(stmt, "typedef")) return;
        boolean isStatic = has(stmt, "static"), isExtern = has(stmt, "extern");
        Tok f = function(stmt);
        if (f != null) {
            if (!isStatic) add(declarations, f);                       // a prototype
            return;
        }
        // Variables: each declarator's name is its last identifier before '=' or '['.
        List<Tok> part = new ArrayList<>();
        int paren = 0;
        boolean first = true;
        for (int k = 0; k <= stmt.size(); k++) {
            Tok t = k < stmt.size() ? stmt.get(k) : null;
            if (t != null && (t.text.equals("(") || t.text.equals("["))) paren++;
            if (t != null && (t.text.equals(")") || t.text.equals("]"))) paren--;
            if (t == null || t.text.equals(",") && paren == 0) {
                Tok name = null;
                int bracket = 0;
                for (Tok p : part) {
                    if (p.text.equals("=") && bracket == 0) break;
                    if (p.text.equals("[")) bracket++;
                    else if (p.text.equals("]")) bracket--;
                    else if (p.ident && bracket == 0) name = p;
                }
                // A first declarator needs a type before its name (not "struct foo;").
                boolean typed = !first || part.size() >= 2 && part.indexOf(name) > 0
                        && !Set.of("struct", "union", "enum").contains(part.get(part.indexOf(name) - 1).text);
                if (name != null && typed && !isStatic) add(isExtern ? declarations : definitions, name);
                part.clear();
                first = false;
                continue;
            }
            part.add(t);
        }
    }
}
