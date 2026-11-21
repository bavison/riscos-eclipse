package com.piccolosystems.riscos.asmindex.text;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tokenised BBC BASIC V programs (RISC OS filetype &FFB) to text and back,
 * as BASIC and Edit do it: BASIC's keyword matcher, and Edit's handling of
 * line numbers, trailing spaces and multi-line ELSE.
 *
 * <p>A program is a CR, then for each line its number (high byte first), its
 * length (the four bytes of number, length and CR included) and its
 * tokenised text, then a CR; then &FF.
 */
public final class BasicTokens {
    private BasicTokens() {
    }

    // ---------------------------------------------------------------- tokens

    static final int TOTHER = 0x7F, TELSE = 0x8B, TCONST = 0x8D, TFN = 0xA4, TESCFN = 0xC6, TESCCOM = 0xC7,
            TESCSTMT = 0xC8, TELSE2 = 0xCC, TPTR2 = 0xCF, TDATA = 0xDC, TPROC = 0xF2, TREM = 0xF4, TVDU = 0xEF,
            TPRINT = 0xF1, TTRACE = 0xFC, TWIDTH = 0xFE;

    /** Single-byte token names from &7F, in order. */
    private static final String[] SINGLE = ("OTHER AND DIV EOR MOD OR ERROR LINE OFF STEP SPC TAB ELSE THEN CONST OPENU "
            + "PTR PAGE TIME LOMEM HIMEM ABS ACS ADC ASC ASN ATN BGET COS COUNT DEG ERL ERR EVAL EXP EXT FALSE FN GET "
            + "INKEY INSTR INT LEN LN LOG NOT OPENI OPENO PI POINT POS RAD RND SGN SIN SQR TAN TO TRUE USR VAL VPOS "
            + "CHRD GETD INKED LEFTD MIDD RIGHTD STRD STRND EOF ESCFN ESCCOM ESCSTMT WHEN OF ENDCA ELSE2 ENDIF ENDWH "
            + "PTR2 PAGE2 TIME2 LOMM2 HIMM2 BEEP BPUT CALL CHAIN CLEAR CLOSE CLG CLS DATA DEF DIM DRAW END ENDPR "
            + "ENVEL FOR GOSUB GOTO GRAPH IF INPUT LET LOCAL MODE MOVE NEXT ON VDU PLOT PRINT PROC READ REM REPEAT "
            + "REPORT RESTORE RETURN RUN STOP TEXT TRACE UNTIL WIDTH OSCL").split(" ");
    /** Two-byte token names from &8E, after TESCFN, TESCSTMT and TESCCOM. */
    private static final String[] FUNCTIONS = "SUM BEAT".split(" ");
    private static final String[] STATEMENTS = ("CASE CIRCLE FILL ORGIN PSET RECT SWAP WHILE WAIT MOUSE QUIT SYS "
            + "INSTALLBAD LIBRARY TINT ELLIPSE BEATS TEMPO VOICES VOICE STEREO OVERLAY").split(" ");
    private static final String[] COMMANDS = ("APPEND AUTO CRUNCH DELET EDIT HELP LIST LOAD LVAR NEW OLD RENUM SAVE "
            + "TEXTLOAD TEXTSAVE TWIN TWINO INSTALL").split(" ");

    private static final Map<String, Integer> VALUE = new HashMap<>();

    static {
        for (int i = 0; i < SINGLE.length; i++) VALUE.put(SINGLE[i], 0x7F + i);
        for (int i = 0; i < FUNCTIONS.length; i++) VALUE.put("2F:" + FUNCTIONS[i], 0x8E + i);
        for (int i = 0; i < STATEMENTS.length; i++) VALUE.put("2S:" + STATEMENTS[i], 0x8E + i);
        for (int i = 0; i < COMMANDS.length; i++) VALUE.put("2C:" + COMMANDS[i], 0x8E + i);
    }

    /** A keyword table entry: its text, token and job bits. */
    record Entry(String text, int token, int job) {}

    /**
     * BASIC's keyword table, in its order (matching takes the first entry
     * that fits): text, token name, job. Job bits: 7 contains its own
     * bracket; 6 polymorphic, or a two-byte statement; 5 give up
     * tokenising; 4 a line number (list) may follow; 3 two-byte token;
     * 2 to left mode, or a two-byte function; 1 to right mode; 0 not if a
     * letter or digit follows.
     */
    private static final String TABLE = """
            AND AND 2|ABS ABS 0|ACS ACS 0|ADVAL ADC 0|ASC ASC 0|ASN ASN 0|ATN ATN 0|AUTO AUTO 24|APPEND APPEND 10|
            BGET BGET 1|BPUT BPUT 3|BEATS BEATS 74|BEAT BEAT 14|
            COLOUR TEXT 2|CALL CALL 2|CASE CASE 74|CHAIN CHAIN 2|CHR$ CHRD 0|CLEAR CLEAR 1|CLOSE CLOSE 3|CLG CLG 1|
            CLS CLS 1|COS COS 0|COUNT COUNT 1|CIRCLE CIRCLE 74|CRUNCH CRUNCH 10|COLOR TEXT 2|
            DATA DATA 32|DEG DEG 0|DEF DEF 0|DELETE DELET 24|DIV DIV 0|DIM DIM 2|DRAW DRAW 2|
            ENDPROC ENDPR 1|EDIT EDIT 40|ENDWHILE ENDWH 1|ENDCASE ENDCA 1|ENDIF ENDIF 1|END END 1|ENVELOPE ENVEL 2|
            ELSE ELSE 20|EVAL EVAL 0|ERL ERL 1|ERROR ERROR 4|EOF EOF 1|EOR EOR 2|ERR ERR 1|EXP EXP 0|EXT EXT 1|
            ELLIPSE ELLIPSE 74|
            FOR FOR 2|FALSE FALSE 1|FILL FILL 74|FN FN 2|
            GOTO GOTO 18|GET$ GETD 0|GET GET 0|GOSUB GOSUB 18|GCOL GRAPH 2|
            HIMEM HIMEM 67|HELP HELP 9|
            INPUT INPUT 2|IF IF 2|INKEY$ INKED 0|INKEY INKEY 0|INT INT 0|INSTR( INSTR 128|INSTALL INSTALL 10|
            LIST LIST 24|LINE LINE 2|LOAD LOAD 10|LOMEM LOMEM 67|LOCAL LOCAL 2|LEFT$( LEFTD 128|LEN LEN 0|LET LET 4|
            LOG LOG 0|LN LN 0|LIBRARY LIBRARY 74|LVAR LVAR 9|
            MID$( MIDD 128|MODE MODE 2|MOD MOD 0|MOVE MOVE 2|MOUSE MOUSE 74|
            NEXT NEXT 2|NEW NEW 9|NOT NOT 0|
            OLD OLD 9|ON ON 2|OFF OFF 0|OF OF 0|ORIGIN ORGIN 74|OR OR 2|OPENIN OPENU 0|OPENOUT OPENO 0|OPENUP OPENI 0|
            OSCLI OSCL 2|OTHERWISE OTHER 4|OVERLAY OVERLAY 74|
            PRINT PRINT 2|PAGE PAGE 67|PTR PTR 67|PI PI 1|PLOT PLOT 2|POINT( POINT 128|POINT PSET 74|PROC PROC 2|
            POS POS 1|
            QUIT QUIT 74|
            RETURN RETURN 1|REPEAT REPEAT 0|REPORT REPORT 1|READ READ 2|REM REM 32|RUN RUN 1|RAD RAD 0|
            RESTORE RESTORE 18|RIGHT$( RIGHTD 128|RND RND 1|RECTANGLE RECT 74|RENUMBER RENUM 24|
            STEP STEP 0|SAVE SAVE 10|SGN SGN 0|SIN SIN 0|SQR SQR 0|SOUND BEEP 2|SPC SPC 0|STR$ STRD 0|
            STRING$( STRND 128|STOP STOP 1|STEREO STEREO 74|SUM SUM 14|SWAP SWAP 74|SYS SYS 74|
            TAN TAN 0|TAB( TAB 128|TEMPO TEMPO 74|TEXTLOAD TEXTLOAD 10|TEXTSAVE TEXTSAVE 10|THEN THEN 20|
            TIME TIME 67|TINT TINT 74|TO TO 0|TRACE TRACE 18|TRUE TRUE 1|TWINO TWINO 10|TWIN TWIN 9|
            UNTIL UNTIL 2|USR USR 0|
            VDU VDU 2|VAL VAL 0|VPOS VPOS 1|VOICES VOICES 74|VOICE VOICE 74|
            WHILE WHILE 74|WHEN WHEN 2|WAIT WAIT 73|WIDTH WIDTH 2""";

    /** The entries for each first letter. */
    private static final Map<Character, List<Entry>> BY_LETTER = new HashMap<>();
    /** What each token lists as: single-byte tokens by value; two-byte as 0xC6xx etc. */
    private static final Map<Integer, String> TEXT = new HashMap<>();

    static {
        for (String item : TABLE.replace("\n", "").split("\\|")) {
            String[] f = item.trim().split(" ");
            int job = Integer.parseInt(f[2]);
            String kind = (job & 8) == 0 ? "" : (job & 64) != 0 ? "2S:" : (job & 4) != 0 ? "2F:" : "2C:";
            Integer v = VALUE.get(kind + f[1]);
            if (v == null) throw new IllegalStateException("no token " + kind + f[1]);
            Entry e = new Entry(f[0], v, job);
            BY_LETTER.computeIfAbsent(f[0].charAt(0), k -> new ArrayList<>()).add(e);
            int key = kind.isEmpty() ? v : ((kind.equals("2F:") ? TESCFN : kind.equals("2S:") ? TESCSTMT : TESCCOM) << 8) | v;
            TEXT.putIfAbsent(key, f[0]);
            // Polymorphic keywords list the same in their statement form.
            if ((job & 64) != 0 && (job & 8) == 0) TEXT.putIfAbsent(v + TPTR2 - 0x8F, f[0]);
        }
        TEXT.put(TELSE2, "ELSE");
        TEXT.put((TESCSTMT << 8) | VALUE.get("2S:INSTALLBAD"), "INSTALL");
        TEXT.putIfAbsent(TCONST, " unlistable token ");
        for (int t = 0x7F; t <= 0xFF; t++) TEXT.putIfAbsent(t, " unlistable token ");
    }

    /** Whether a file is a tokenised BASIC program (perhaps with something after its end, such as a newline). */
    public static boolean isTokenised(byte[] data) {
        return end(data) > 0;
    }

    /** The index of a program's closing &FF, or -1 if it isn't a tokenised program. */
    private static int end(byte[] data) {
        if (data.length < 2 || data[0] != 13) return -1;
        int i = 1;
        while (i < data.length && (data[i] & 0xFF) != 0xFF) {
            if (i + 3 > data.length) return -1;
            int len = data[i + 2] & 0xFF;
            if (len < 4 || i + len > data.length || data[i + len - 1] != 13) return -1;
            i += len;
        }
        return i < data.length ? i : -1;
    }

    /** What follows a program's end, kept as it is. */
    public static byte[] trailer(byte[] data) {
        int e = end(data);
        return e < 0 ? new byte[0] : java.util.Arrays.copyOfRange(data, e + 1, data.length);
    }

    // ---------------------------------------------------------------- detokenising

    /** A detokenised program. */
    public record Text(String text, boolean lineNumbers, int first, int step) {}

    /**
     * The program as text. Line numbers are left out unless the program
     * refers to them (GOTO, GOSUB, RESTORE…), as Edit does. Whether they were,
     * and the numbering to give the lines back if they weren't (the first
     * number and the step, if the numbers went up evenly, else 10 and 10),
     * are returned with it.
     */
    public static Text detokenise(byte[] data) {
        return detokenise(data, null);
    }

    /** As {@link #detokenise(byte[])}, with line numbers shown if {@code showNumbers} says so (null: as Edit would). */
    public static Text detokenise(byte[] data, Boolean showNumbers) {
        boolean refs = false;
        List<Integer> numbers = new ArrayList<>();
        for (int i = 1; i + 3 <= data.length && (data[i] & 0xFF) != 0xFF; i += Math.max(4, data[i + 2] & 0xFF)) {
            numbers.add(((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF));
            if (lineHasConstant(data, i + 3, i + (data[i + 2] & 0xFF) - 1)) refs = true;
        }
        int first = 10, step = 10;
        if (numbers.size() >= 2) {
            int s = numbers.get(1) - numbers.get(0);
            boolean even = s > 0;
            for (int k = 2; k < numbers.size() && even; k++) even = numbers.get(k) - numbers.get(k - 1) == s;
            if (even) {
                first = numbers.get(0);
                step = s;
            }
        } else if (numbers.size() == 1) {
            first = numbers.get(0);
        }
        if (showNumbers != null) refs = showNumbers;
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (int i = 1; i + 3 <= data.length && (data[i] & 0xFF) != 0xFF; i += Math.max(4, data[i + 2] & 0xFF)) {
            if (refs) b.append(String.format("%5d", numbers.get(n)));
            n++;
            int from = i + 3, to = i + (data[i + 2] & 0xFF) - 1;
            StringBuilder line = new StringBuilder();
            detokeniseLine(data, from, to, line, false);
            if (!sameTokens(line.toString(), data, from, to)) {
                // Keywords run together (crunched, say) would read back as a
                // name: space them apart, which keeps the tokens.
                StringBuilder spaced = new StringBuilder();
                detokeniseLine(data, from, to, spaced, true);
                if (sameTokensButSpaces(spaced.toString(), data, from, to)) line = spaced;
            }
            b.append(line).append('\n');
        }
        return new Text(b.toString(), refs, first, step);
    }

    /** Whether a line's tokens include an encoded line number. */
    private static boolean lineHasConstant(byte[] d, int from, int to) {
        boolean quoted = false;
        for (int i = from; i < to; i++) {
            int c = d[i] & 0xFF;
            if (c == '"') quoted = !quoted;
            if (quoted) continue;
            if (c == TREM || c == TDATA) return false;
            if (c == TESCFN || c == TESCCOM || c == TESCSTMT) i++;
            else if (c == TCONST) return true;
        }
        return false;
    }

    /** Whether a line's text tokenises to its tokens, give or take spaces outside strings. */
    private static boolean sameTokensButSpaces(String text, byte[] d, int from, int to) {
        byte[] again = match(text.getBytes(StandardCharsets.ISO_8859_1)).out;
        return withoutSpaces(again, 0, again.length).equals(withoutSpaces(d, from, to));
    }

    private static String withoutSpaces(byte[] b, int from, int to) {
        StringBuilder s = new StringBuilder();
        boolean quoted = false;
        for (int i = from; i < to; i++) {
            int c = b[i] & 0xFF;
            if (c == '"') quoted = !quoted;
            if (c == ' ' && !quoted) continue;
            s.append((char) (c == TELSE2 ? TELSE : c));
        }
        return s.toString();
    }

    /** Whether a line's text tokenises to its tokens. */
    private static boolean sameTokens(String text, byte[] d, int from, int to) {
        byte[] again = match(text.getBytes(StandardCharsets.ISO_8859_1)).out;
        int k = 0;
        while (k < again.length && again[k] == ' ') k++;
        if (k < again.length && (again[k] & 0xFF) == TELSE && (d[from + k] & 0xFF) == TELSE2) again[k] = (byte) TELSE2;
        return java.util.Arrays.equals(again, java.util.Arrays.copyOfRange(d, from, to));
    }

    /**
     * A line's text, from {@code from} up to (not including) its CR at
     * {@code to}. With {@code spaced}, a keyword is kept apart by a space from
     * letters or digits next to it, so that it reads back as a keyword.
     */
    private static void detokeniseLine(byte[] d, int from, int to, StringBuilder b, boolean spaced) {
        boolean quoted = false, raw = false;
        for (int i = from; i < to; i++) {
            int c = d[i] & 0xFF;
            if (c == '"') quoted = !quoted;
            if (quoted || raw || c < 0x7F) {
                if (spaced && !quoted && !raw && b.length() > 0 && wordc(c) && lastWasKeyword) b.append(' ');
                lastWasKeyword = false;
                b.append((char) c);
                continue;
            }
            if (spaced && b.length() > 0 && wordc(b.charAt(b.length() - 1)) && c != TCONST) b.append(' ');
            lastWasKeyword = c != TCONST;
            if (c == TCONST && i + 3 < to + 1) {
                b.append(decodeConstant(d[i + 1] & 0xFF, d[i + 2] & 0xFF, d[i + 3] & 0xFF));
                i += 3;
                continue;
            }
            int key = c;
            if ((c == TESCFN || c == TESCCOM || c == TESCSTMT) && i + 1 < to) key = (c << 8) | (d[++i] & 0xFF);
            String kw = TEXT.getOrDefault(key, " unlistable token ");
            b.append(kw);
            // PROC and FN run on into their names.
            if (c == TPROC || c == TFN) lastWasKeyword = false;
            // BASIC copied what follows REM and DATA as it was.
            if (c == TREM || c == TDATA) raw = true;
        }
    }

    /** Whether the last thing listed was a keyword (for spacing). Detokenising is single-threaded per call. */
    private static boolean lastWasKeyword;

    public static int decodeConstant(int b1, int b2, int b3) {
        int x = b1 << 2;
        int lo = (x & 0xC0) ^ b2;
        int hi = ((x << 2) ^ b3) & 0xFF;
        return (hi << 8) | lo;
    }

    // ---------------------------------------------------------------- tokenising

    /** A tokenised program, and what went wrong on which lines (1-based). */
    public record Tokenised(byte[] data, List<String> problems) {}

    /** The text's lines, without the newline after the last. */
    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("\n", -1)) out.add(s.endsWith("\r") ? s.substring(0, s.length() - 1) : s);
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    /**
     * Tokenises text. A line may start with its number; a line without one
     * is numbered {@code step} after the one before (the first {@code first}).
     */
    public static Tokenised tokenise(String text, int first, int step) {
        List<String> lines = lines(text);
        int[] numbers = new int[lines.size()];
        for (int k = 0; k < numbers.length; k++) numbers[k] = first + k * step;
        return tokenise(lines, numbers, true);
    }

    /**
     * Tokenises edited text, given the program as it was. If the program was
     * shown without line numbers, each line that is unchanged keeps its
     * number, and new lines are numbered between their neighbours; if there
     * isn't room, the program is renumbered (and that is reported). If it was
     * shown with them, lines without one follow on from the line before.
     */
    public static Tokenised tokenise(String text, byte[] original) {
        if (original == null || !isTokenised(original)) return tokenise(text, 10, 10);
        Text was = detokenise(original);
        if (was.lineNumbers) return tokenise(text, was.first, was.step);
        List<String> lines = lines(text), old = lines(was.text);
        List<Integer> oldNumbers = numbers(original);
        int[] match = Diff.match(old, lines);       // new line → old line, or -1
        int[] numbers = new int[lines.size()];
        boolean room = true;
        int k = 0;
        while (k < lines.size()) {
            if (match[k] >= 0) {
                numbers[k] = oldNumbers.get(match[k]);
                k++;
                continue;
            }
            int from = k;
            while (k < lines.size() && match[k] < 0) k++;
            int before = from == 0 ? -1 : numbers[from - 1];
            int after = k < lines.size() ? oldNumbers.get(match[k]) : 65280;
            int count = k - from, gap = after - before - 1;
            if (gap < count) {
                room = false;
                break;
            }
            int step = Math.max(1, Math.min(10, (after - before) / (count + 1)));
            for (int j = 0; j < count; j++) numbers[from + j] = before + step * (j + 1);
        }
        if (room) return tokenise(lines, numbers, false);
        for (int j = 0; j < numbers.length; j++) numbers[j] = was.first + j * was.step;
        if (numbers.length > 0 && numbers[numbers.length - 1] > 65279)
            for (int j = 0; j < numbers.length; j++) numbers[j] = 10 + j * 10;
        Tokenised t = tokenise(lines, numbers, false);
        List<String> problems = new ArrayList<>(t.problems);
        problems.add(0, "There was no room between line numbers for the new lines, so the program has been renumbered");
        return new Tokenised(t.data, problems);
    }

    /** A program's line numbers. */
    private static List<Integer> numbers(byte[] data) {
        List<Integer> out = new ArrayList<>();
        for (int i = 1; i + 3 <= data.length && (data[i] & 0xFF) != 0xFF; i += Math.max(4, data[i + 2] & 0xFF))
            out.add(((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF));
        return out;
    }

    /**
     * Tokenises lines. A line may start with its own number; otherwise it
     * gets {@code numbers[k]}, or, with {@code followOn}, the number after
     * the previous line's by the same step as the given numbers.
     */
    private static Tokenised tokenise(List<String> lines, int[] numbers, boolean followOn) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> problems = new ArrayList<>();
        out.write(13);
        int step = numbers.length >= 2 ? numbers[1] - numbers[0] : 10;
        int last = numbers.length > 0 ? numbers[0] - step : 0;
        boolean numbered = false, unnumbered = false, mixed = false, order = false;
        for (int ln = 0; ln < lines.size(); ln++) {
            String s = lines.get(ln);
            byte[] src = s.getBytes(StandardCharsets.ISO_8859_1);
            int i = 0;
            while (i < src.length && src[i] == ' ') i++;
            int number, start;
            if (i < src.length && src[i] >= '0' && src[i] <= '9') {
                number = 0;
                while (i < src.length && src[i] >= '0' && src[i] <= '9' && number < 100000)
                    number = number * 10 + (src[i++] - '0');
                start = i;
                numbered = true;
            } else {
                number = followOn ? last + step : numbers[ln];
                start = 0;
                unnumbered = true;
            }
            if (numbered && unnumbered && !mixed && followOn) {
                mixed = true;
                problems.add("Line " + (ln + 1) + ": a mixture of lines with and without line numbers");
            }
            if (number > 65279) {
                problems.add("Line " + (ln + 1) + ": line number " + number + " is more than 65279");
                number = 65279;
            }
            if (ln > 0 && number <= last && !order) {
                order = true;
                problems.add("Line " + (ln + 1) + ": line number " + number + " doesn't follow " + last);
            }
            last = number;
            byte[] body = new byte[src.length - start];
            System.arraycopy(src, start, body, 0, body.length);
            Matched m = match(body);
            // An ELSE that starts a line is the multi-line ELSE, as BASIC and Edit make it.
            // (Edit also drops trailing spaces; BASIC doesn't, and nor does this.)
            int end = m.out.length;
            int k = 0;
            while (k < end && m.out[k] == ' ') k++;
            if (k < end && (m.out[k] & 0xFF) == TELSE) m.out[k] = (byte) TELSE2;
            if (end + 4 > 255) problems.add("Line " + (ln + 1) + ": too long once tokenised");
            out.write(number >> 8);
            out.write(number & 0xFF);
            out.write(Math.min(255, end + 4));
            out.write(m.out, 0, end);
            out.write(13);
            if ((m.smode & 0xFF) == 1) problems.add("Line " + (ln + 1) + ": mismatched quotes");
            if ((m.smode & 256) != 0) problems.add("Line " + (ln + 1) + ": a number too large");
            if ((m.smode & ~0x1FF) != 0) problems.add("Line " + (ln + 1) + ": mismatched brackets");
        }
        out.write(0xFF);
        return new Tokenised(out.toByteArray(), problems);
    }

    private record Matched(byte[] out, int smode) {}

    private static boolean wordc(int c) {
        return c >= 'A' && c <= 'Z' || c >= '_' && c <= 'z' || c >= '0' && c <= '9';
    }

    private static boolean digit(int c) {
        return c >= '0' && c <= '9';
    }

    /** BASIC's MATCH, from left mode with no constants, as Edit calls it. */
    private static Matched match(byte[] line) {
        // The line ends with a CR, as BASIC's input does.
        byte[] src = java.util.Arrays.copyOf(line, line.length + 1);
        src[line.length] = 13;
        Out out = new Out(src.length + 8);
        int smode = 0, mode = 0;        // mode: 0 left (start of a statement), 1 right
        boolean consta = false;
        int i = 0, c = 0;
        boolean examine = false;        // look at c again, rather than read the next character
        while (true) {
            if (!examine) {
                c = src[i++] & 0xFF;
                out.write(c);           // copied, assuming it isn't a token
            }
            examine = false;
            if (c == ' ') continue;
            if (c == 13) break;
            if (c == '"') smode ^= 1;
            if ((smode & 0xFF) != 0) continue;     // in a string, or given up
            if (smode >= 0) {
                if (c == '(') smode += 0x1000;
                else if (c == ')') smode -= 0x1000;
            }
            if (c == '&') {
                boolean recheck = false;
                while (true) {
                    c = src[i++] & 0xFF;
                    out.write(c);
                    if (digit(c) || c >= 'A' && c < 'G' || c >= 'a' && c < 'g') continue;
                    recheck = c < 'A';
                    break;
                }
                if (recheck) {
                    examine = true;
                    continue;
                }
                // Otherwise straight on to the checks for ':', ',' and '*'.
            }
            if (c == ':') {
                mode = 0;
                consta = false;
                continue;
            }
            if (c == ',') continue;
            if (c == '*') {
                if (mode == 0) smode |= 4;     // a * command: the rest is the OS's
                else {
                    consta = false;
                    mode = 1;
                }
                continue;
            }
            if (c == 13) break;
            if (c == '.' || digit(c) && !consta) {
                i = copyWhile(src, i, out, true);
                c = src[i - 1] & 0xFF;
                consta = false;
                mode = 1;
                examine = true;
                continue;
            }
            if (digit(c)) {
                // A line number, unless it's too large.
                int value = c & 15, j = i, next;
                boolean tooLarge = false;
                while (true) {
                    next = src[j++] & 0xFF;
                    if (!digit(next)) break;
                    value = value * 10 + (next & 15);
                    if (value >= 65280) {
                        tooLarge = true;
                        break;
                    }
                }
                if (tooLarge) {
                    smode |= 256;
                    i = copyWhile(src, i, out, true);
                    c = src[i - 1] & 0xFF;
                    consta = false;
                    mode = 1;
                    examine = true;
                    continue;
                }
                out.unwrite(1);
                out.write(TCONST);
                encodeConstant(value, out);
                out.write(next);
                i = j;
                c = next;
                examine = true;
                continue;
            }
            if (c < 'A') {
                consta = false;
                mode = 1;
                continue;
            }
            if (c > 'W' || BY_LETTER.get((char) c) == null) {
                if (wordc(c)) {
                    i = copyWhile(src, i, out, false);
                    c = src[i - 1] & 0xFF;
                    examine = true;
                }
                consta = false;
                mode = 1;
                continue;
            }
            // A keyword? The first entry that fits, as BASIC's table is ordered.
            Entry found = null;
            int after = 0, last = 0;
            for (Entry e : BY_LETTER.get((char) c)) {
                int j = i, k = 1;
                boolean ok = true;
                last = c;
                while (k < e.text.length()) {
                    int sc = src[j] & 0xFF;
                    if (sc == e.text.charAt(k)) {
                        j++;
                        k++;
                        last = sc;
                        continue;
                    }
                    if (sc == '.') {
                        j++;
                        last = sc;
                        break;          // abbreviated
                    }
                    ok = false;
                    break;
                }
                if (!ok) continue;
                if ((e.job & 1) != 0 && wordc(src[j] & 0xFF)) break;   // not a keyword here at all
                found = e;
                after = j;
                break;
            }
            if (found == null) {
                i = copyWhile(src, i, out, false);
                c = src[i - 1] & 0xFF;
                consta = false;
                mode = 1;
                examine = true;
                continue;
            }
            int job = found.job, token = found.token;
            boolean twoByte = (job & 8) != 0;
            if (mode == 1 && token == TTRACE && !twoByte) job &= ~16;
            if (mode == 1 && token == TPRINT && !twoByte && last == '.' && src[after - 2] == 'P'
                    && out.at(-2) == TVDU && after >= 3 && src[after - 3] == 'U') {
                // VDU then P.: the VFP instruction VDUP. in the assembler, not VDU PRINT.
                out.write('.');
                i = after;
                mode = 0;
                consta = false;
                continue;
            }
            out.unwrite(1);        // the keyword's first letter, copied
            if (twoByte) {
                int esc = TESCCOM;
                if ((job & 64) != 0) esc = TESCSTMT;
                if ((job & 4) != 0) {
                    esc = TESCFN;
                    job &= ~4;
                }
                out.write(esc);
                out.write(token);
            } else {
                if ((job & 64) != 0 && mode == 0) token += TPTR2 - 0x8F;
                out.write(token);
            }
            i = after;
            if ((job & 2) != 0) {
                mode = 1;
                consta = false;
            }
            if ((job & 4) != 0) {
                mode = 0;
                consta = false;
            }
            if (token == TFN || token == TPROC) {
                while (wordc(src[i] & 0xFF)) out.write(src[i++]);
            }
            if ((job & 16) != 0) consta = true;
            if ((job & 32) != 0) smode |= 4;
            if ((job & 128) != 0) smode += 0x1000;
        }
        byte[] b = out.toByteArray();
        return new Matched(java.util.Arrays.copyOf(b, b.length - 1), smode);    // without the CR
    }

    /** An output buffer that can take back what it wrote, and look behind. */
    private static final class Out extends ByteArrayOutputStream {
        Out(int size) {
            super(size);
        }

        void unwrite(int n) {
            count -= n;
        }

        int at(int back) {
            int k = count + back;
            return k >= 0 ? buf[k] & 0xFF : -1;
        }
    }

    /**
     * Copies characters from {@code i} while they are digits and '.' (a
     * number) or letters and digits (a word), and the one after them; returns
     * the index after that.
     */
    private static int copyWhile(byte[] src, int i, ByteArrayOutputStream out, boolean number) {
        while (true) {
            int c = src[i++] & 0xFF;
            out.write(c);
            if (c == 13) return i;
            if (number ? digit(c) || c == '.' : wordc(c)) continue;
            return i;
        }
    }

    public static void encodeConstant(int value, ByteArrayOutputStream out) {
        int lo = value & 0xFF, hi = value >> 8;
        int b1 = (((hi & 0xC0) >> 4) | ((lo & 0xC0) >> 2)) ^ 0x54;
        out.write(b1);
        out.write((lo & 0x3F) | 0x40);
        out.write((hi & 0x3F) | 0x40);
    }
}
