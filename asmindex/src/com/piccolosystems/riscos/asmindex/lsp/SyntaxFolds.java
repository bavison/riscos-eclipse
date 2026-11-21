package com.piccolosystems.riscos.asmindex.lsp;

import com.piccolosystems.riscos.asmindex.parse.Directive;
import com.piccolosystems.riscos.asmindex.parse.LineSplitter;
import com.piccolosystems.riscos.asmindex.parse.SplitLine;
import com.piccolosystems.riscos.asmindex.source.LogicalLine;
import com.piccolosystems.riscos.asmindex.source.SourceFile;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Folding ranges found from the text alone, for a file that no entry point
 * of the configuration reads, so that the engine has nothing to say about it.
 * The rules are the engine's: each IF, ELIF and ELSE clause folds up to the
 * line before the next ELIF, ELSE or ENDIF; WHILE to WEND and MACRO to MEND
 * fold whole; and a routine folds from its ROUT to the line before the next
 * ROUT, or to the end of the file.
 */
final class SyntaxFolds {
    private SyntaxFolds() {}

    /** @return {startLine, endLine} pairs, 1-based */
    static List<int[]> of(Path path, String text) {
        SourceFile f = SourceFile.fromText(path, text);
        List<int[]> out = new ArrayList<>();
        Deque<int[]> open = new ArrayDeque<>();     // {kind, start}: 0 IF clause, 1 WHILE, 2 MACRO
        int rout = -1;
        for (int i = 0; i < f.size(); i++) {
            LogicalLine ll = f.line(i);
            SplitLine sl = LineSplitter.split(ll.toText());
            if (sl.comment || !sl.hasOp()) continue;
            Directive d = Directive.lookup(sl.op());
            if (d == null) continue;
            int line = ll.firstLine, last = ll.firstLine + ll.physicalLineCount() - 1;
            switch (d) {
                case IF: open.push(new int[] {0, line}); break;
                case ELIF: case ELSE: {
                    int[] o = open.peek();
                    if (o != null && o[0] == 0) {
                        add(out, o[1], line - 1);
                        o[1] = line;
                    }
                    break;
                }
                case ENDIF: {
                    int[] o = open.peek();
                    if (o != null && o[0] == 0) {
                        open.pop();
                        add(out, o[1], line - 1);
                    }
                    break;
                }
                case WHILE: open.push(new int[] {1, line}); break;
                case MACRO: open.push(new int[] {2, line}); break;
                case WEND: case MEND: {
                    int kind = d == Directive.WEND ? 1 : 2;
                    // Unclosed IFs inside are dropped, as the assembler would complain about them.
                    while (!open.isEmpty() && open.peek()[0] != kind) open.pop();
                    if (!open.isEmpty()) add(out, open.pop()[1], last);
                    break;
                }
                case ROUT:
                    if (rout > 0) add(out, rout, line - 1);
                    rout = line;
                    break;
                default:
                    break;
            }
        }
        if (rout > 0) add(out, rout, f.physicalLineCount);
        return out;
    }

    private static void add(List<int[]> out, int start, int end) {
        if (end > start) out.add(new int[] {start, end});
    }
}
