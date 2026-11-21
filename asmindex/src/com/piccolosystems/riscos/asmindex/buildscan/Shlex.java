package com.piccolosystems.riscos.asmindex.buildscan;

import java.util.ArrayList;
import java.util.List;

/** Splits a line into words as a POSIX shell does: quotes and backslashes, no expansion. */
final class Shlex {
    private Shlex() {}

    /** @throws IllegalArgumentException for an unterminated quote or a trailing backslash */
    static List<String> split(String s) {
        List<String> words = new ArrayList<>();
        StringBuilder w = new StringBuilder();
        boolean inWord = false;
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i++);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (inWord) {
                    words.add(w.toString());
                    w.setLength(0);
                    inWord = false;
                }
            } else if (c == '\'') {
                inWord = true;
                int end = s.indexOf('\'', i);
                if (end < 0) throw new IllegalArgumentException("no closing quotation");
                w.append(s, i, end);
                i = end + 1;
            } else if (c == '"') {
                inWord = true;
                for (;;) {
                    if (i >= n) throw new IllegalArgumentException("no closing quotation");
                    char d = s.charAt(i++);
                    if (d == '"') break;
                    if (d == '\\' && i < n && "\\\"".indexOf(s.charAt(i)) >= 0) d = s.charAt(i++);
                    w.append(d);
                }
            } else if (c == '\\') {
                if (i >= n) throw new IllegalArgumentException("no escaped character");
                inWord = true;
                char d = s.charAt(i++);
                if (d != '\n') w.append(d);
            } else {
                inWord = true;
                w.append(c);
            }
        }
        if (inWord) words.add(w.toString());
        return words;
    }
}
