package com.piccolosystems.riscos.asmindex.macro;

import java.util.ArrayList;
import java.util.List;

/** Parses macro prototypes and call arguments. */
public final class MacroSyntax {
    private MacroSyntax() {}

    static boolean ws(char c) { return c == ' ' || c == '\t'; }
    static boolean symStart(char c) { return Character.isLetter(c) && c < 128 || c == '_'; }
    static boolean symChar(char c) { return Character.isLetterOrDigit(c) && c < 128 || c == '_'; }

    public static final class Prototype {
        public String label, name, suffix;
        public int nameStart, nameEnd;
        public final List<String> params = new ArrayList<>();
        public final List<String> defaults = new ArrayList<>();
        public String error;
    }

    private static int symbol(String s, int i) {
        if (i < s.length() && s.charAt(i) == '|') {
            int q = s.indexOf('|', i + 1);
            return q > i + 1 ? q + 1 : -1;
        }
        if (i >= s.length() || !symStart(s.charAt(i))) return -1;
        while (i < s.length() && symChar(s.charAt(i))) i++;
        return i;
    }

    private static boolean allComment(String s, int i) {
        while (i < s.length() && ws(s.charAt(i))) i++;
        return i >= s.length() || s.charAt(i) == ';';
    }

    /** Parses the line after MACRO: {@code [$label] name[$suffix] [$p[=default], …]}. */
    public static Prototype prototype(String s) {
        Prototype p = new Prototype();
        int i = 0;
        if (s.startsWith("$")) {
            int e = symbol(s, 1);
            if (e < 0) { p.error = "bad label parameter"; return p; }
            p.label = s.substring(1, e);
            i = e;
        }
        if (i >= s.length() || !ws(s.charAt(i))) { p.error = "bad label parameter"; return p; }
        while (i < s.length() && ws(s.charAt(i))) i++;
        int e = symbol(s, i);
        if (e < 0) { p.error = "bad macro name"; return p; }
        p.name = s.charAt(i) == '|' ? s.substring(i + 1, e - 1) : s.substring(i, e);
        p.nameStart = i;
        p.nameEnd = e;
        i = e;
        if (i < s.length() && s.charAt(i) == '$') {
            int f = symbol(s, i + 1);
            if (f < 0) { p.error = "bad suffix parameter"; return p; }
            p.suffix = s.substring(i + 1, f);
            i = f;
        }
        if (allComment(s, i)) return p;
        while (i < s.length() && ws(s.charAt(i))) i++;
        for (;;) {
            if (i >= s.length() || s.charAt(i) != '$') { p.error = "parameter must start with $"; return p; }
            int f = symbol(s, i + 1);
            if (f < 0) { p.error = "bad parameter name"; return p; }
            p.params.add(s.substring(i + 1, f));
            i = f;
            while (i < s.length() && ws(s.charAt(i))) i++;
            String def = "";
            if (i < s.length() && s.charAt(i) == '=') {
                i++;
                StringBuilder b = new StringBuilder();
                i = defaultValue(s, i, b);
                if (i < 0) { p.error = "bad parameter default"; return p; }
                def = b.toString();
            }
            p.defaults.add(def);
            if (allComment(s, i)) return p;
            if (s.charAt(i) != ',') { p.error = "bad parameter separator"; return p; }
            i++;
            while (i < s.length() && ws(s.charAt(i))) i++;
        }
    }

    /** A default: quoted "…" with "" for ", or unquoted up to ',' or ';'. */
    private static int defaultValue(String s, int i, StringBuilder b) {
        boolean quoted = i < s.length() && s.charAt(i) == '"';
        if (quoted) i++;
        for (;;) {
            if (i >= s.length()) return quoted ? -1 : i;
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    i++;
                    if (i >= s.length() || s.charAt(i) != '"') return i;
                }
            } else {
                if (c == ';' || c == ',') return i;
                if (c == '"') {
                    i++;
                    if (i >= s.length() || s.charAt(i) != '"') return -1;
                }
            }
            b.append(c);
            i++;
        }
    }

    /** One call argument: its text, or null meaning "use the default" (a lone |). */
    public static final class Args {
        public final List<String> values = new ArrayList<>();
        public final List<int[]> ranges = new ArrayList<>();
        public String error;
    }

    /**
     * Parses call arguments starting at {@code i} (just after the macro name).
     * Arguments are separated by ',' and end at ';' or the line end. Empty or
     * omitted arguments are empty strings, not defaults.
     */
    public static Args arguments(String s, int i) {
        Args a = new Args();
        if (allComment(s, i)) return a;
        for (;;) {
            while (i < s.length() && ws(s.charAt(i))) i++;
            boolean quoted = i < s.length() && s.charAt(i) == '"';
            if (quoted) i++;
            int start = i;
            StringBuilder b = new StringBuilder();
            for (;;) {
                if (i >= s.length()) {
                    if (quoted) a.error = "unterminated quoted argument";
                    break;
                }
                char c = s.charAt(i);
                if (!quoted && (c == ';' || c == ',')) break;
                if (quoted && c == '"') {
                    i++;
                    if (i < s.length() && s.charAt(i) == '"') { b.append('"'); i++; continue; }
                    while (i < s.length() && ws(s.charAt(i))) i++;   // objasm skips spaces here
                    if (!allComment(s, i) && s.charAt(i) != ',') {
                        // objasm reports this; skip to the separator to continue
                        a.error = "bad macro arguments";
                        while (i < s.length() && s.charAt(i) != ',' && s.charAt(i) != ';') i++;
                    }
                    break;
                }
                b.append(c);
                i++;
            }
            String v = b.toString();
            int end = start + v.length();
            if (!quoted) {
                int k = v.length();
                while (k > 0 && ws(v.charAt(k - 1))) k--;
                v = v.substring(0, k);
                end = start + k;
            }
            a.ranges.add(new int[] {start, end});
            a.values.add(!quoted && v.equals("|") ? null : v);
            if (i >= s.length() || s.charAt(i) != ',') break;
            i++;
        }
        return a;
    }
}
