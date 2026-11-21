package com.piccolosystems.riscos.asmindex.sym;

/**
 * A position in source: file, 1-based line, 0-based column and length. For
 * text produced inside a macro expansion, {@link #via} is the macro call that
 * instantiated it (which may itself have a via).
 */
public final class Site {
    public final String file;
    public final int line, col, len;
    public final Site via;

    public Site(String file, int line, int col, int len, Site via) {
        this.file = file; this.line = line; this.col = col; this.len = len; this.via = via;
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Site)) return false;
        Site s = (Site) o;
        return file.equals(s.file) && line == s.line && col == s.col && len == s.len
                && (via == null ? s.via == null : via.equals(s.via));
    }

    @Override public int hashCode() { return file.hashCode() * 31 + line * 7 + col; }

    @Override public String toString() { return file + ":" + line + ":" + col; }
}
