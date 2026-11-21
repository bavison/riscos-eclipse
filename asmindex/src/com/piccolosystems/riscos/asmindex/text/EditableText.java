package com.piccolosystems.riscos.asmindex.text;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Converts RISC OS files that a text editor can't show as they are into text
 * that it can, and back. The text is UTF-8 with a byte order mark, which marks
 * it as converted.
 * <ul>
 * <li>{@link Kind#NULS}: Latin-1 text with NUL bytes, such as a Messages file,
 *     where a NUL ends each message. Each NUL is shown as ␀ (U+2400).
 * <li>{@link Kind#TOKENISED_BASIC}: a tokenised BASIC program, shown as its
 *     text (see {@link BasicTokens}).
 * </ul>
 */
public final class EditableText {
    private EditableText() {
    }

    public enum Kind {
        /** Nothing to convert: edit the file as it is. */
        PLAIN,
        NULS,
        TOKENISED_BASIC
    }

    /** How a file needs converting, from its contents. */
    public static Kind kindOf(byte[] data) {
        if (BasicTokens.isTokenised(data)) return Kind.TOKENISED_BASIC;
        for (byte b : data) if (b == 0) return Kind.NULS;
        return Kind.PLAIN;
    }

    /** What a NUL is shown as. */
    public static final char NUL_SHOWN = '␀';

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** The file's contents as text to edit. */
    public static byte[] toText(byte[] data, Kind kind) {
        String s = kind == Kind.TOKENISED_BASIC ? BasicTokens.detokenise(data).text()
                : new String(data, StandardCharsets.ISO_8859_1);
        if (kind != Kind.PLAIN) s = s.replace('\0', NUL_SHOWN);
        return withBom(s);
    }

    private static byte[] withBom(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() + 3);
        out.writeBytes(BOM);
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** Edited text (UTF-8, with or without its byte order mark) back as the file's contents. */
    public static byte[] fromText(byte[] text, Kind kind) throws IOException {
        int from = text.length >= 3 && text[0] == BOM[0] && text[1] == BOM[1] && text[2] == BOM[2] ? 3 : 0;
        String s = new String(text, from, text.length - from, StandardCharsets.UTF_8);
        s = s.replace(NUL_SHOWN, '\0');
        return latin1(s);
    }

    /** A file's new contents, and anything worth telling the user about them. */
    public record Saved(byte[] data, java.util.List<String> problems) {}

    /**
     * Edited text back as a file's contents, converted as the file it
     * replaces ({@code original}) was: tokenised if that was a tokenised
     * program, keeping its unchanged lines' numbers; otherwise Latin-1 with
     * ␀ as NUL.
     */
    public static Saved save(byte[] text, byte[] original) throws IOException {
        if (original == null || kindOf(original) != Kind.TOKENISED_BASIC)
            return new Saved(fromText(text, Kind.NULS), java.util.List.of());
        int from = text.length >= 3 && text[0] == BOM[0] && text[1] == BOM[1] && text[2] == BOM[2] ? 3 : 0;
        String s = new String(text, from, text.length - from, StandardCharsets.UTF_8).replace(NUL_SHOWN, '\0');
        latin1(s);
        BasicTokens.Tokenised t = BasicTokens.tokenise(s, original);
        byte[] trailer = BasicTokens.trailer(original);
        byte[] data = java.util.Arrays.copyOf(t.data(), t.data().length + trailer.length);
        System.arraycopy(trailer, 0, data, t.data().length, trailer.length);
        return new Saved(data, t.problems());
    }

    /** Latin-1 bytes, or an error naming the first character Latin-1 can't hold. */
    static byte[] latin1(String s) throws IOException {
        int line = 1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') line++;
            if (c > 0xFF)
                throw new IOException("Line " + line + " has a character RISC OS's Latin-1 can't hold: " + c
                        + " (U+" + String.format("%04X", (int) c) + ")");
        }
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
