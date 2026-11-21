package com.piccolosystems.riscos.asmindex.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A source file split into logical lines. Files are read as
 * Latin-1, as RISC OS text is. Tabs become single spaces, so a column is a
 * character offset in the physical line.
 */
public final class SourceFile {
    public final Path path;
    public final String name;          // path as a string, for output
    private final List<LogicalLine> lines = new ArrayList<>();
    public final int physicalLineCount;

    private SourceFile(Path path, String text) {
        this.path = path;
        this.name = path.toString();
        List<String> phys = splitPhysical(text);
        physicalLineCount = phys.size();
        int n = 0;
        while (n < phys.size()) {
            int first = n + 1;
            StringBuilder b = new StringBuilder();
            List<Integer> starts = new ArrayList<>();
            for (;;) {
                String p = phys.get(n++).replace('\t', ' ');
                starts.add(b.length());
                boolean cont = p.endsWith("\\") && n < phys.size() && !p.startsWith("*");
                if (cont) {
                    b.append(p, 0, p.length() - 1).append(' ');
                } else {
                    b.append(p);
                    break;
                }
            }
            int[] st = new int[starts.size()];
            for (int k = 0; k < st.length; k++) st[k] = starts.get(k);
            lines.add(new LogicalLine(this, lines.size(), b.toString(), first, st));
        }
    }

    /** Builds a file from text, for tests. */
    public static SourceFile fromText(Path path, String text) {
        return new SourceFile(path, text);
    }

    public static SourceFile read(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return new SourceFile(path, new String(bytes, StandardCharsets.ISO_8859_1));
    }

    /** CR, LF, CR LF and LF CR each end a line. */
    static List<String> splitPhysical(String t) {
        List<String> out = new ArrayList<>();
        int st = 0, i = 0, n = t.length();
        while (i < n) {
            char c = t.charAt(i);
            if (c == '\r' || c == '\n') {
                out.add(t.substring(st, i));
                i++;
                if (i < n) {
                    char d = t.charAt(i);
                    if ((d == '\r' || d == '\n') && d != c) i++;
                }
                st = i;
            } else i++;
        }
        if (st < n) out.add(t.substring(st));
        return out;
    }

    public int size() { return lines.size(); }

    public LogicalLine line(int index) { return lines.get(index); }

    /** The logical line containing 1-based physical line {@code physical}, or null. */
    public LogicalLine lineAt(int physical) {
        int lo = 0, hi = lines.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            LogicalLine l = lines.get(mid);
            if (physical < l.firstLine) hi = mid - 1;
            else if (physical >= l.firstLine + l.physicalLineCount()) lo = mid + 1;
            else return l;
        }
        return null;
    }

    /** Text of 1-based physical line {@code physical} (see {@link LogicalLine#physicalText}), or "". */
    public String lineText(int physical) {
        LogicalLine l = lineAt(physical);
        return l == null ? "" : l.physicalText(physical - l.firstLine);
    }

    @Override public String toString() { return name; }
}
