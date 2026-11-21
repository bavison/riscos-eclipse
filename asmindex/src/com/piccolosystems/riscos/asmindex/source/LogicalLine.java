package com.piccolosystems.riscos.asmindex.source;

/**
 * One logical line: physical lines joined by backslash continuation. Keeps
 * enough to map a column in the joined text back to a physical position.
 */
public final class LogicalLine {
    public final SourceFile file;
    public final int index;        // index of this logical line within the file
    public final String text;
    public final int firstLine;    // 1-based physical line number
    private final int[] segStart;  // offset in text where each physical line starts

    LogicalLine(SourceFile file, int index, String text, int firstLine, int[] segStart) {
        this.file = file;
        this.index = index;
        this.text = text;
        this.firstLine = firstLine;
        this.segStart = segStart;
    }

    public int physicalLineCount() { return segStart.length; }

    /** 1-based physical line for a column of the joined text. */
    public int lineOf(int col) {
        int k = segStart.length - 1;
        while (k > 0 && segStart[k] > col) k--;
        return firstLine + k;
    }

    /** 0-based column within its physical line. */
    public int columnOf(int col) {
        int k = segStart.length - 1;
        while (k > 0 && segStart[k] > col) k--;
        return Math.max(0, col - segStart[k]);
    }

    /**
     * Text of the k-th physical line of this logical line, as joined: tabs are
     * single spaces, and a continuation backslash has become a space.
     */
    public String physicalText(int k) {
        int end = k + 1 < segStart.length ? segStart[k + 1] : text.length();
        return text.substring(segStart[k], end);
    }

    public Text toText() { return Text.of(this); }

    @Override public String toString() { return file.name + ":" + firstLine + ": " + text; }
}
