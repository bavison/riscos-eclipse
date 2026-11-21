package com.piccolosystems.riscos.asmindex.text;

import java.util.Arrays;
import java.util.List;

/** Which lines of a new text are lines of an old one, unchanged: a longest common subsequence. */
final class Diff {
    private Diff() {
    }

    /** The largest middle part compared line by line (old × new); beyond that, it counts as all new. */
    private static final long MAX_CELLS = 4_000_000;

    /** For each new line, the old line it is, or -1 if it is new or changed. */
    static int[] match(List<String> old, List<String> now) {
        int[] m = new int[now.size()];
        Arrays.fill(m, -1);
        int p = 0;
        while (p < old.size() && p < now.size() && old.get(p).equals(now.get(p))) {
            m[p] = p;
            p++;
        }
        int so = old.size(), sn = now.size();
        while (so > p && sn > p && old.get(so - 1).equals(now.get(sn - 1))) {
            m[sn - 1] = so - 1;
            so--;
            sn--;
        }
        int a = so - p, b = sn - p;
        if (a == 0 || b == 0 || (long) a * b > MAX_CELLS) return m;
        int[][] len = new int[a + 1][b + 1];
        for (int i = a - 1; i >= 0; i--)
            for (int j = b - 1; j >= 0; j--)
                len[i][j] = old.get(p + i).equals(now.get(p + j)) ? len[i + 1][j + 1] + 1
                        : Math.max(len[i + 1][j], len[i][j + 1]);
        int i = 0, j = 0;
        while (i < a && j < b) {
            if (old.get(p + i).equals(now.get(p + j))) {
                m[p + j] = p + i;
                i++;
                j++;
            } else if (len[i + 1][j] >= len[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return m;
    }
}
