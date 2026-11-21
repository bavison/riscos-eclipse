package com.piccolosystems.riscos.asmindex.run;

import java.nio.file.Path;
import java.util.List;

/** One objasm invocation, from buildscan's objasm.json. */
public final class EntryPoint {
    public final Path file;
    public final Path directory;
    public final List<String> includes;
    public final List<String> predefines;
    public final List<String> options;

    public EntryPoint(Path file, Path directory, List<String> includes, List<String> predefines, List<String> options) {
        this.file = file; this.directory = directory; this.includes = includes;
        this.predefines = predefines; this.options = options;
    }
}
