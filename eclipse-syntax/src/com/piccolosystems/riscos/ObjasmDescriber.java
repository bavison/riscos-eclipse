package com.piccolosystems.riscos;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.core.runtime.content.ITextContentDescriber;

/**
 * Tells objasm source from GNU assembler, for {@code *.s} files that CDT also
 * claims. Eclipse prefers a content type whose describer accepts a file's
 * contents over one with no describer, so this settles the choice for each
 * file, rather than leaving it to how Eclipse breaks a tie between two types
 * of the same priority.
 *
 * <p>It looks at the first lines for signs of either: for objasm, comments
 * starting {@code ;} and directives such as {@code GET}, {@code AREA},
 * {@code [}, {@code ]} or {@code SETL}; for GNU, {@code @} and C comments,
 * C preprocessor lines, directives starting with {@code .} and labels ending
 * with {@code :}. A file with no signs either way is taken to be objasm,
 * since only files named as objasm sources are asked.
 */
public class ObjasmDescriber implements ITextContentDescriber {
    private static final int MAX_LINES = 400;

    private static final Set<String> OBJASM_OPS = Set.of(
            "GET", "INCLUDE", "AREA", "ROUT", "MACRO", "MEND", "MEXIT", "[", "|", "]", "IF", "ELIF", "ELSE",
            "ENDIF", "WHILE", "WEND", "GBLA", "GBLL", "GBLS", "LCLA", "LCLL", "LCLS", "SETA", "SETL", "SETS",
            "END", "EXPORT", "IMPORT", "EXTERN", "GLOBAL", "ENTRY", "LTORG", "DCB", "DCW", "DCD", "DCQ",
            "ALIGN", "ASSERT", "INFO", "LNK", "BIN", "INCBIN", "TTL", "SUBT", "OPT", "RN", "FN", "CN", "CP",
            "EQU", "^", "#", "*", "=", "%", "!", "KEEP", "NOFP", "REQUIRE8", "PRESERVE8", "THUMB", "ARM", "CODE16",
            "CODE32", "MAP", "FIELD", "SPACE", "FILL");
    private static final Pattern OBJASM_OPERATOR = Pattern.compile(":(?:LAND|LOR|LNOT|LEOR|SHL|SHR|ROL|ROR|AND|OR|EOR|"
            + "MOD|DEF|BASE|INDEX|LEN|CHR|STR|LEFT|RIGHT|CC|UPPERCASE|LOWERCASE|REVERSE_CC|FATTR|FSIZE|"
            + "RCONST|CC_ENCODING):");
    private static final Pattern GNU_PREPROCESSOR = Pattern.compile("^\\s*#\\s*(?:include|define|undef|if|ifdef|"
            + "ifndef|elif|else|endif|error|warning|pragma)\\b");
    private static final Pattern GNU_LABEL = Pattern.compile("^[A-Za-z_.$][\\w.$]*:");

    @Override
    public int describe(Reader contents, IContentDescription description) throws IOException {
        BufferedReader in = new BufferedReader(contents);
        int objasm = 0, gnu = 0;
        String line;
        for (int n = 0; n < MAX_LINES && (line = in.readLine()) != null; n++) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            if (t.startsWith(";")) { objasm++; continue; }
            if (t.startsWith("@") || t.startsWith("/*") || t.startsWith("//")) { gnu++; continue; }
            if (GNU_PREPROCESSOR.matcher(line).find()) { gnu++; continue; }
            if (GNU_LABEL.matcher(line).find()) { gnu++; continue; }
            if (OBJASM_OPERATOR.matcher(line).find()) objasm++;
            // The operation: the first field if the line starts with white space, else the second.
            String[] f = line.split("[ \\t]+", 3);
            String op = Character.isWhitespace(line.charAt(0)) ? (f.length > 1 ? f[1] : "") : (f.length > 1 ? f[1] : "");
            if (op.startsWith(".")) gnu++;
            else if (OBJASM_OPS.contains(op)) objasm++;
        }
        return gnu > objasm ? INVALID : VALID;
    }

    @Override
    public int describe(InputStream contents, IContentDescription description) throws IOException {
        return describe(new InputStreamReader(contents, StandardCharsets.ISO_8859_1), description);
    }

    @Override
    public QualifiedName[] getSupportedOptions() {
        return new QualifiedName[0];
    }
}
