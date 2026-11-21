package com.piccolosystems.riscos.asmindex.source;

/**
 * Text derived from a logical line, with each character mapped back to a
 * column of that line. Substitution builds new Text values; inserted text maps
 * to the column of the reference that produced it.
 */
public final class Text {
    public final LogicalLine origin;
    public final String s;
    private final int[] map;

    public Text(LogicalLine origin, String s, int[] map) {
        this.origin = origin;
        this.s = s;
        this.map = map;
    }

    public static Text of(LogicalLine l) {
        int[] m = new int[l.text.length()];
        for (int i = 0; i < m.length; i++) m[i] = i;
        return new Text(l, l.text, m);
    }

    public int length() { return s.length(); }

    public char charAt(int i) { return s.charAt(i); }

    /** Column in the origin line for character {@code i} (or the end). */
    public int originCol(int i) {
        if (map.length == 0) return 0;
        if (i >= map.length) return map[map.length - 1] + (i - map.length + 1);
        return map[i];
    }

    public Text sub(int from, int to) {
        int[] m = new int[to - from];
        for (int i = from; i < to; i++) m[i - from] = originCol(i);
        return new Text(origin, s.substring(from, to), m);
    }

    /** Incremental builder that records an origin column per character. */
    public static final class Builder {
        private final LogicalLine origin;
        private final StringBuilder b = new StringBuilder();
        private int[] m = new int[64];

        public Builder(LogicalLine origin) { this.origin = origin; }

        public Builder append(char c, int col) {
            if (b.length() == m.length) m = java.util.Arrays.copyOf(m, m.length * 2);
            m[b.length()] = col;
            b.append(c);
            return this;
        }

        public Builder append(CharSequence s, int col) {
            for (int i = 0; i < s.length(); i++) append(s.charAt(i), col);
            return this;
        }

        public Builder appendFrom(Text t, int from, int to) {
            for (int i = from; i < to; i++) append(t.charAt(i), t.originCol(i));
            return this;
        }

        public int length() { return b.length(); }

        public Text build() { return new Text(origin, b.toString(), java.util.Arrays.copyOf(m, b.length())); }
    }

    @Override public String toString() { return s; }
}
