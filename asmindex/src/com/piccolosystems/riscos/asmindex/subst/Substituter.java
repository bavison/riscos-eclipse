package com.piccolosystems.riscos.asmindex.subst;

import com.piccolosystems.riscos.asmindex.expr.Value;
import com.piccolosystems.riscos.asmindex.source.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** The two '$' substitution passes. */
public final class Substituter {
    private Substituter() {}

    static boolean symStart(char c) { return Character.isLetter(c) && c < 128 || c == '_'; }
    static boolean symChar(char c) { return Character.isLetterOrDigit(c) && c < 128 || c == '_'; }

    /**
     * Macro parameter substitution: $param → argument text; $$ → $; |barred| text
     * copied unchanged. Does not stop at comments or strings.
     */
    public static Text macroParams(Text t, Map<String, String> args) {
        if (t.s.indexOf('$') < 0) return t;
        Text.Builder b = new Text.Builder(t.origin);
        int i = 0, n = t.length();
        while (i < n) {
            char c = t.charAt(i);
            if (c == '|') {
                int q = t.s.indexOf('|', i + 1);
                int stop = q < 0 ? n : q + 1;
                b.appendFrom(t, i, stop);
                i = stop;
                continue;
            }
            if (c != '$') { b.append(c, t.originCol(i)); i++; continue; }
            if (i + 1 < n && t.charAt(i + 1) == '$') { b.append('$', t.originCol(i)); i += 2; continue; }
            int j = i + 1;
            while (j < n && symChar(t.charAt(j))) j++;
            String name = t.s.substring(i + 1, j);
            String v = j > i + 1 ? args.get(name) : null;
            if (v == null) { b.appendFrom(t, i, j); i = j; continue; }
            b.append(v, t.originCol(i));
            if (j < n && t.charAt(j) == '.') j++;
            i = j;
        }
        return b.build();
    }

    /** Result of variable substitution. */
    public static final class Result {
        public final Text text;
        /** Names written as $name that aren't variables (objasm warns about them). */
        public final List<String> notVariables;
        /** A variable whose value the engine doesn't know was referenced. */
        public final boolean unknown;
        /** Variables substituted, with the position of the '$' in the input text. */
        public final List<int[]> uses;
        public final List<String> useNames;
        Result(Text t, List<String> nv, boolean u, List<int[]> uses, List<String> names) {
            text = t; notVariables = nv; unknown = u; this.uses = uses; useNames = names;
        }
    }

    /**
     * Variable substitution: stops at the first ';' outside '…' or "…" quotes;
     * |barred| text is copied unchanged. {@code lookup} gives a variable's value, or null
     * if the name isn't a variable.
     */
    public static Result variables(Text t, Function<String, Value> lookup) {
        List<String> nv = new ArrayList<>();
        List<int[]> uses = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (t.s.indexOf('$') < 0) return new Result(t, nv, false, uses, names);
        Text.Builder b = new Text.Builder(t.origin);
        boolean unknown = false;
        char quote = 0;                 // ' or " while inside quotes
        int i = 0, n = t.length();
        while (i < n) {
            char c = t.charAt(i);
            if (c != '$') {
                if (c == quote) quote = 0;
                else if (quote == 0 && (c == '\'' || c == '"')) quote = c;
                else if (quote == 0 && c == ';') { b.appendFrom(t, i, n); break; }
            }
            // Barred text is copied unchanged, even inside quotes (as objasm does).
            if (c == '|') {
                int q = t.s.indexOf('|', i + 1);
                int stop = q < 0 ? n : q + 1;
                b.appendFrom(t, i, stop);
                i = stop;
                continue;
            }
            if (c != '$') { b.append(c, t.originCol(i)); i++; continue; }
            if (i + 1 < n && t.charAt(i + 1) == '$') { b.append('$', t.originCol(i)); i += 2; continue; }
            int j = i + 1;
            if (j < n && symStart(t.charAt(j))) while (j < n && symChar(t.charAt(j))) j++;
            if (j == i + 1) { b.append('$', t.originCol(i)); i++; continue; }
            String name = t.s.substring(i + 1, j);
            Value v = lookup.apply(name);
            if (v == null) {
                nv.add(name);
                b.appendFrom(t, i, j);
                i = j;
                continue;
            }
            uses.add(new int[] {t.originCol(i), t.originCol(j - 1) + 1});
            names.add(name);
            String rep;
            switch (v.kind) {
                case NUM: rep = Value.hex8(v.num); break;
                case BOOL: rep = v.bool ? "T" : "F"; break;
                case STR: rep = v.str; break;
                default:
                    unknown = true;
                    rep = null;
            }
            if (rep == null) { b.appendFrom(t, i, j); i = j; continue; }
            b.append(rep, t.originCol(i));
            if (j < n && t.charAt(j) == '.') j++;
            i = j;
        }
        return new Result(b.build(), nv, unknown, uses, names);
    }
}
