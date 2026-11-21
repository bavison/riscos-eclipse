package com.piccolosystems.riscos.asmindex.target;

import com.piccolosystems.riscos.asmindex.expr.Value;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Command-line target options → built-in variables and APCS
 * register names. The CPU table is our own, written from Arm's public
 * documentation of each processor's architecture; it is deliberately small.
 * Built-ins it doesn't model evaluate as Unknown, so they never cause shading.
 */
public final class Targets {
    /** CPU name (lower case) → architecture name. */
    private static final Map<String, String> CPU_ARCH = Map.ofEntries(
            Map.entry("arm2", "2"), Map.entry("arm250", "2a"), Map.entry("arm3", "2a"),
            Map.entry("arm6", "3"), Map.entry("arm610", "3"), Map.entry("arm7", "3"), Map.entry("arm710", "3"),
            Map.entry("arm7m", "3M"), Map.entry("arm7dm", "3M"), Map.entry("arm7500", "3"),
            Map.entry("arm7tdmi", "4T"), Map.entry("arm810", "4"), Map.entry("strongarm", "4"),
            Map.entry("sa-110", "4"), Map.entry("sa-1100", "4"), Map.entry("arm920t", "4T"), Map.entry("arm9tdmi", "4T"),
            Map.entry("arm926ej-s", "5TEJ"), Map.entry("arm946e-s", "5TE"), Map.entry("xscale", "5TE"),
            Map.entry("arm1136j-s", "6"), Map.entry("arm1136jf-s", "6"), Map.entry("arm1176jz-s", "6Z"),
            Map.entry("arm1176jzf-s", "6Z"), Map.entry("mpcore", "6K"), Map.entry("arm1156t2-s", "6T2"),
            Map.entry("cortex-a5", "7-A"), Map.entry("cortex-a7", "7-A"), Map.entry("cortex-a8", "7-A"),
            Map.entry("cortex-a9", "7-A"), Map.entry("cortex-a12", "7-A"), Map.entry("cortex-a15", "7-A"),
            Map.entry("cortex-a17", "7-A"), Map.entry("cortex-a32", "8-A"), Map.entry("cortex-a35", "8-A"),
            Map.entry("cortex-a53", "8-A"), Map.entry("cortex-a55", "8.2-A"), Map.entry("cortex-a57", "8-A"),
            Map.entry("cortex-a72", "8-A"), Map.entry("cortex-a73", "8-A"), Map.entry("cortex-a76", "8.2-A"),
            Map.entry("cortex-r4", "7-R"), Map.entry("cortex-r5", "7-R"),
            Map.entry("cortex-m0", "6-M"), Map.entry("cortex-m3", "7-M"), Map.entry("cortex-m4", "7E-M"));

    public String cpu = "Generic ARM";
    public String arch = null;        // e.g. "6Z"; null if unknown
    public String fpu = null;
    public boolean pc32 = true;
    public boolean thumbDefault = false;
    public boolean ualDefault = false;

    // APCS
    public enum Apcs { NONE, APCS3, POST3 }
    public Apcs apcs = Apcs.NONE;
    public boolean reentrant = false, swStackCheck = true, noFp = false;

    public static Targets fromOptions(List<String> opts) {
        Targets t = new Targets();
        for (int i = 0; i < opts.size(); i++) {
            String o = opts.get(i);
            String lo = o.toLowerCase(Locale.ROOT);
            String arg = i + 1 < opts.size() ? opts.get(i + 1) : "";
            if (lo.equals("-apcs") || lo.equals("--apcs")) { t.apcs(arg); i++; }
            else if (lo.startsWith("--apcs=")) t.apcs(o.substring(7));
            else if (lo.equals("-cpu") || lo.equals("--cpu")) { t.cpu(arg); i++; }
            else if (lo.startsWith("--cpu=")) t.cpu(o.substring(6));
            else if (lo.equals("-arch") || lo.equals("--arch")) { t.arch = archName(arg); i++; }
            else if (lo.equals("-fpu") || lo.equals("--fpu")) { t.fpu = arg; i++; }
            else if (lo.startsWith("--fpu=")) t.fpu = o.substring(6);
            else if (lo.equals("-16") || lo.equals("--thumb")) { t.thumbDefault = true; t.ualDefault = lo.equals("--thumb"); }
            else if (lo.equals("-32")) { t.thumbDefault = false; t.ualDefault = false; }
            else if (lo.equals("--arm")) { t.thumbDefault = false; t.ualDefault = true; }
        }
        return t;
    }

    private void cpu(String c) {
        cpu = c;
        String base = c.toLowerCase(Locale.ROOT);
        int dot = base.indexOf('.');           // e.g. Cortex-A53.crypto
        if (dot > 0) base = base.substring(0, dot);
        String a = CPU_ARCH.get(base);
        arch = a != null ? a : archName(base);
    }

    /** An architecture written as a CPU name (e.g. "6Z", "7-A"), normalised; null if it isn't one. */
    static String archName(String s) {
        String u = s.toUpperCase(Locale.ROOT);
        if (u.matches("[2-8](\\.[0-9])?[A-Z0-9]*(-[ARM])?")) return u.replace("2A", "2a");
        return null;
    }

    /** Qualifiers accumulate across -APCS options, as objasm does. */
    private void apcs(String a) {
        if (a.isEmpty()) return;
        if (a.startsWith("/")) apcs = Apcs.POST3;
        else if (a.startsWith("3")) apcs = Apcs.APCS3;
        else if (a.toLowerCase(Locale.ROOT).startsWith("none")) apcs = Apcs.NONE;
        for (String q : a.toLowerCase(Locale.ROOT).split("/")) {
            if (q.startsWith("reent") || q.equals("pid") || q.equals("rwpi")) reentrant = true;
            else if (q.startsWith("nonreent") || q.equals("nopid") || q.equals("norwpi")) reentrant = false;
            else if (q.startsWith("32")) pc32 = true;
            else if (q.startsWith("26")) pc32 = false;
            else if (q.startsWith("swst")) swStackCheck = true;
            else if (q.startsWith("nosw")) swStackCheck = false;
            else if (q.startsWith("nofp")) noFp = true;
            else if (q.equals("fp")) noFp = false;
        }
    }

    /**
     * Register names objasm predefines for this calling standard, as name → number
     * (in addition to r0–r15, R0–R15, sp, lr, pc).
     */
    public Map<String, Integer> apcsRegisters() {
        java.util.LinkedHashMap<String, Integer> m = new java.util.LinkedHashMap<>();
        boolean post3 = apcs == Apcs.POST3, a3 = apcs == Apcs.APCS3;
        if (apcs != Apcs.NONE) {
            for (int r = 0; r < 4; r++) m.put("a" + (r + 1), r);
            for (int r = 4; r < 9; r++) m.put("v" + (r - 3), r);
            m.put("fp", 11);
            m.put("ip", 12);
            if (post3) { m.put("FP", 11); m.put("IP", 12); }
        }
        if (post3 || a3 && !reentrant) m.put("v6", 9);
        if (a3 && reentrant && !swStackCheck) m.put("v6", 10);
        if (post3 || a3 && !reentrant && !swStackCheck) m.put("v7", 10);
        if (post3 || a3 && noFp) m.put("v8", 11);
        if (post3 || a3 && reentrant) { m.put("sb", 9); if (post3) m.put("SB", 9); }
        if (post3 || a3 && swStackCheck) { m.put("sl", 10); if (post3) m.put("SL", 10); }
        return m;
    }

    /** Thumb-2 era instructions (MOVW/MOVT) available in ARM state: v6T2 and v7 onwards. */
    public boolean hasT2() {
        if (arch == null) return false;
        return armVersion() >= 7 || arch.toUpperCase(Locale.ROOT).startsWith("6T2");
    }

    private int armVersion() {
        if (arch == null) return -1;
        return arch.charAt(0) - '0';
    }

    /** A built-in variable's value, given the current instruction-set state. */
    public Value builtin(String name, boolean thumb, boolean ual) {
        switch (name) {
            case "CONFIG": return Value.num(thumb ? 16 : pc32 ? 32 : 26);
            case "CODESIZE": return Value.num(thumb ? 16 : 32);
            case "ENDIAN": return Value.str("little");
            case "UAL": return Value.bool(ual);
            case "CPU": return Value.str(cpu);
            case "FPU": return fpu == null ? Value.unknown("{FPU} default not modelled") : Value.str(fpu);
            case "ARCHITECTURE": return arch == null ? Value.unknown("architecture unknown") : Value.str(arch);
            case "INTER": case "ROPI": case "RWPI": return Value.FALSE;
            case "REENTRANT": return Value.bool(reentrant);
            case "TARGET_ARCH_AARCH32": return Value.TRUE;
            case "TARGET_ARCH_AARCH64": return Value.FALSE;
            case "TARGET_ARCH_ARM": {
                int v = armVersion();
                return v < 0 ? Value.unknown("architecture unknown") : Value.num(v);
            }
            default:
        }
        if (name.startsWith("TARGET_ARCH_") && arch != null) {
            String want = name.substring("TARGET_ARCH_".length());
            if (want.equals("THUMB")) return Value.unknown("Thumb version not modelled");
            String have = arch.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');
            if (want.matches("[0-9][0-9A-Z_]*")) return Value.bool(want.equals(have));
        }
        return Value.unknown("{" + name + "} not modelled");
    }
}
