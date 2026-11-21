package com.piccolosystems.riscos.asmindex.run;

import com.piccolosystems.riscos.asmindex.sym.Site;

/** A problem found while assembling an entry point. */
public final class Diagnostic {
    public enum Severity { ERROR, WARNING, INFO }

    public final Severity severity;
    /** Short stable identifier, for counting: e.g. "get-unresolved", "unknown-op". */
    public final String code;
    public final String message;
    public final Site site;
    public final int pass;

    public Diagnostic(Severity severity, String code, String message, Site site, int pass) {
        this.severity = severity; this.code = code; this.message = message; this.site = site; this.pass = pass;
    }

    String key() { return code + "|" + message + "|" + site; }

    @Override public String toString() {
        return (site == null ? "" : site + ": ") + severity.name().toLowerCase() + " [" + code + "] " + message;
    }
}
