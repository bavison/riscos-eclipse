package com.piccolosystems.riscos.asmindex.lsp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.piccolosystems.riscos.asmindex.text.BasicTokens;

/** {@code BasicLint [--info] FILE...}: the BASIC diagnostics the editor would show, for each file (tokenised or not). */
public final class BasicLint {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        boolean info = args.length > 0 && args[0].equals("--info");
        Basic b = new Basic();
        for (String a : args) {
            if (a.startsWith("--")) continue;
            byte[] d = Files.readAllBytes(Path.of(a));
            String text = BasicTokens.isTokenised(d) ? BasicTokens.detokenise(d).text() : new String(d, StandardCharsets.ISO_8859_1);
            String uri = Path.of(a).toUri().toString();
            b.update(uri, text);
            String[] lines = text.split("\n", -1);
            for (Object o : b.diagnostics(uri, info)) {
                Map<String, Object> m = (Map<String, Object>) o;
                Map<String, Object> st = (Map<String, Object>) ((Map<String, Object>) m.get("range")).get("start");
                int line = (Integer) st.get("line");
                System.out.println(a + "\t" + (line + 1) + "\t" + m.get("severity") + "\t" + m.get("message") + "\t"
                        + lines[line].strip());
            }
            b.close(uri);
        }
    }
}
