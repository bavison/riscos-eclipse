package com.piccolosystems.riscos;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.content.IContentDescriber;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.core.runtime.content.ITextContentDescriber;

/**
 * The character set of RISC OS text files: Latin-1, as RISC OS sources are.
 * The exception is text that RISC OS Eclipse support has converted for
 * editing (a Messages file's NULs shown as ␀, tokenised BASIC as text),
 * which it marks with a UTF-8 byte order mark.
 */
public class TextDescriber implements ITextContentDescriber {
    public static final String LATIN_1 = "ISO-8859-1";

    @Override
    public int describe(InputStream contents, IContentDescription description) throws IOException {
        byte[] b = contents.readNBytes(3);
        if (description != null) {
            boolean bom = b.length == 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF;
            if (bom) {
                description.setProperty(IContentDescription.BYTE_ORDER_MARK, IContentDescription.BOM_UTF_8);
                description.setProperty(IContentDescription.CHARSET, "UTF-8");
            } else {
                description.setProperty(IContentDescription.CHARSET, LATIN_1);
            }
        }
        return VALID;
    }

    @Override
    public int describe(Reader contents, IContentDescription description) throws IOException {
        return VALID;
    }

    @Override
    public QualifiedName[] getSupportedOptions() {
        return new QualifiedName[] {IContentDescription.CHARSET, IContentDescription.BYTE_ORDER_MARK};
    }
}
