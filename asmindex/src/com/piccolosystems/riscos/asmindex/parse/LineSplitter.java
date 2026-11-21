package com.piccolosystems.riscos.asmindex.parse;

import com.piccolosystems.riscos.asmindex.parse.SplitLine.LabelKind;
import com.piccolosystems.riscos.asmindex.source.Text;

/** Splits a (substituted) line into label, operation and operands. */
public final class LineSplitter {
    private LineSplitter() {}

    static boolean ws(char c) { return c == ' ' || c == '\t'; }
    public static boolean symStart(char c) { return Character.isLetter(c) && c < 128 || c == '_'; }
    public static boolean symChar(char c) { return Character.isLetterOrDigit(c) && c < 128 || c == '_'; }

    /** End of the code part: the first ';' outside a "string" or a 'c' character constant. */
    public static int commentStart(String s, int from) {
        boolean str = false;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') str = !str;
            else if (!str && c == '\'' && i + 2 < s.length() && s.charAt(i + 2) == '\'') i += 2;
            else if (!str && c == ';') return i;
        }
        return s.length();
    }

    public static SplitLine split(Text t) {
        String s = t.s;
        int n = s.length();
        if (n > 0 && s.charAt(0) == '*') return blank(t);
        int i = 0;
        LabelKind lk = LabelKind.NONE;
        int ls = -1, le = -1;
        String label = null;
        if (n > 0 && !ws(s.charAt(0)) && s.charAt(0) != ';') {
            ls = 0;
            if (s.charAt(0) == '|') {
                int q = s.indexOf('|', 1);
                if (q > 1 && (q + 1 == n || term(s.charAt(q + 1)))) {
                    i = q + 1;
                    lk = LabelKind.SYMBOL;
                    label = s.substring(1, q);
                }
            } else if (symStart(s.charAt(0))) {
                int j = 0;
                while (j < n && symChar(s.charAt(j))) j++;
                if (j == n || term(s.charAt(j))) {
                    i = j;
                    lk = LabelKind.SYMBOL;
                    label = s.substring(0, j);
                }
            } else if (Character.isDigit(s.charAt(0))) {
                int j = 0;
                while (j < n && Character.isDigit(s.charAt(j))) j++;
                while (j < n && symChar(s.charAt(j))) j++;
                if (j == n || term(s.charAt(j))) {
                    i = j;
                    lk = LabelKind.LOCAL;
                    label = s.substring(0, j);
                }
            }
            if (lk == LabelKind.NONE) {
                int j = 0;
                while (j < n && !term(s.charAt(j))) j++;
                i = j;
                lk = LabelKind.OTHER;
                label = s.substring(0, j);
            }
            le = i;
        }
        while (i < n && ws(s.charAt(i))) i++;
        if (i >= n || s.charAt(i) == ';') {
            if (lk == LabelKind.NONE) return blank(t);
            return new SplitLine(t, false, lk, ls, le, label, -1, -1, -1, -1);
        }
        int os = i;
        while (i < n && !ws(s.charAt(i)) && s.charAt(i) != ';') i++;
        int oe = i;
        int cs = commentStart(s, oe);
        int as = oe, ae = cs;
        while (as < ae && ws(s.charAt(as))) as++;
        while (ae > as && ws(s.charAt(ae - 1))) ae--;
        if (as >= ae) { as = -1; ae = -1; }
        return new SplitLine(t, false, lk, ls, le, label, os, oe, as, ae);
    }

    private static boolean term(char c) { return ws(c) || c == ';'; }

    private static SplitLine blank(Text t) {
        return new SplitLine(t, true, LabelKind.NONE, -1, -1, null, -1, -1, -1, -1);
    }

    /** Splits operands at top-level commas (outside strings, brackets and bars). Returns [start,end) pairs, trimmed. */
    public static java.util.List<int[]> splitArgs(String s, int from, int to) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        if (from < 0) return out;
        int depth = 0, st = from;
        boolean str = false;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c == '"') str = !str;
            else if (str) continue;
            else if (c == '\'' && i + 2 < to && s.charAt(i + 2) == '\'') i += 2;
            else if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth <= 0) { out.add(trim(s, st, i)); st = i + 1; }
        }
        out.add(trim(s, st, to));
        return out;
    }

    static int[] trim(String s, int a, int b) {
        while (a < b && ws(s.charAt(a))) a++;
        while (b > a && ws(s.charAt(b - 1))) b--;
        return new int[] {a, b};
    }
}
