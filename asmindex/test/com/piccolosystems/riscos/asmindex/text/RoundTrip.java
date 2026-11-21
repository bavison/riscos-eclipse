package com.piccolosystems.riscos.asmindex.text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** {@code RoundTrip FILE...}: detokenises and tokenises each file, and reports any that don't come back the same. */
public final class RoundTrip {
    public static void main(String[] args) throws Exception {
        int sameButSpaces = 0;
        int same = 0, sameEdit = 0, total = 0, numbered = 0, even = 0, uneven = 0, butSpaces = 0;
        for (String a : args) {
            byte[] d = Files.readAllBytes(Path.of(a));
            if (!BasicTokens.isTokenised(d)) {
                System.out.println("not tokenised: " + a);
                continue;
            }
            total++;
            BasicTokens.Text t = BasicTokens.detokenise(d, true);
            BasicTokens.Tokenised back = BasicTokens.tokenise(t.text(), 10, 10);
            if (Arrays.equals(concat(back.data(), BasicTokens.trailer(d)), d)) same++;
            else if (lines(back.data()).equals(lines(d))) butSpaces++;
            else report(a, d, back.data(), t.text());
            BasicTokens.Text e = BasicTokens.detokenise(d);
            if (e.lineNumbers()) numbered++;
            else if (BasicTokens.detokenise(d, false).step() != 10 || BasicTokens.detokenise(d, false).first() != 10
                    || evenly(d)) even++;
            else uneven++;
            BasicTokens.Tokenised eb = BasicTokens.tokenise(e.text(), d);
            byte[] withTrailer = concat(eb.data(), BasicTokens.trailer(d));
            if (Arrays.equals(withTrailer, d)) sameEdit++;
            else if (lines(withTrailer).equals(lines(d))) sameButSpaces++;
            else System.out.println("EDIT DIFF " + a + " " + eb.problems());
        }
        System.out.println(total + " files: " + same + " the same with line numbers shown, " + butSpaces
                + " the same but for spaces; " + sameEdit + " the same as shown for editing, " + sameButSpaces + " but for spaces (" + numbered
                + " with line numbers shown; of the others, " + even + " numbered evenly, " + uneven + " not)");
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] c = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    /** Each line's number and tokens, without spaces outside strings. */
    static java.util.List<String> lines(byte[] d) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 1; i + 3 <= d.length && (d[i] & 0xFF) != 0xFF; i += Math.max(4, d[i + 2] & 0xFF)) {
            StringBuilder s = new StringBuilder().append((d[i] & 0xFF) * 256 + (d[i + 1] & 0xFF)).append(':');
            boolean q = false;
            for (int k = i + 3; k < i + (d[i + 2] & 0xFF) - 1; k++) {
                int c = d[k] & 0xFF;
                if (c == '"') q = !q;
                if (c != ' ' || q) s.append((char) c);
            }
            out.add(s.toString());
        }
        return out;
    }

    static String strip(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) if (x != ' ') s.append((char) (x & 0xFF));
        return s.toString();
    }

    /** Whether the line numbers go 10, 20, 30… */
    static boolean evenly(byte[] d) {
        int expect = 10;
        for (int i = 1; i + 3 <= d.length && (d[i] & 0xFF) != 0xFF; i += d[i + 2] & 0xFF, expect += 10)
            if ((((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF)) != expect) return false;
        return true;
    }

    static void report(String file, byte[] a, byte[] b, String text) {
        int k = 0;
        while (k < Math.min(a.length, b.length) && a[k] == b[k]) k++;
        // Find the line.
        int i = 1, line = 0;
        while (i < a.length && (a[i] & 0xFF) != 0xFF && i + (a[i + 2] & 0xFF) <= k) {
            i += a[i + 2] & 0xFF;
            line++;
        }
        String[] lines = text.split("\n");
        System.out.println("DIFF " + file + " at byte " + k + ", line " + (line + 1) + ": "
                + (line < lines.length ? lines[line].strip() : "?"));
        System.out.println("   was " + hex(a, i, Math.min(a.length, i + 48)));
        System.out.println("   now " + hex(b, i, Math.min(b.length, i + 48)));
    }

    static String hex(byte[] d, int from, int to) {
        StringBuilder s = new StringBuilder();
        for (int k = from; k < to; k++) {
            int c = d[k] & 0xFF;
            s.append(c >= 32 && c < 127 ? " " + (char) c : String.format("%02X", c)).append(' ');
        }
        return s.toString();
    }
}
