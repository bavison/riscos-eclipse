package com.piccolosystems.riscos.asmindex.parse;

import java.util.HashMap;
import java.util.Map;

/**
 * Recognises instruction mnemonics, so that instructions take precedence over
 * macros as they do in objasm. Written from Arm's public
 * instruction set documentation (A32/T32, VFP/Advanced SIMD, FPA, and the
 * pre-UAL forms). Recognition only: no encoding.
 *
 * A mnemonic is accepted in all-upper or all-lower case.
 */
public final class Mnemonics {
    private Mnemonics() {}

    /** Suffix grammar classes. */
    private enum Form {
        DP,        // {S}{cond} in either order (data processing, multiplies, shifts)
        CMP,       // {cond}{P} (TEQP etc. on 26-bit) 
        COND,      // {cond}
        NONE,      // nothing (unconditional)
        LDR,       // {cond} and size/translation suffix in either order
        STR,
        LDM,       // {cond} and addressing mode in either order
        CP,        // {cond}{L}
        FPA_DP,    // {cond}{S|D|E}{P|M|Z}
        FPA_LS,    // {cond}{S|D|E|P}
        FPA_LFM,   // {cond}{FD|EA}
        FPA_CMP,   // {cond} (CMF, CNF, CMFE, CNFE)
        VFP,       // {cond}, then optional .dt qualifiers (handled before)
        VLDM,      // {mode}{cond}
        IT         // IT, ITT, ITE… : nothing after
    }

    private static final Map<String, Form> BASE = new HashMap<>();

    private static void add(Form f, String... names) { for (String n : names) BASE.put(n, f); }

    static {
        add(Form.DP, "AND", "EOR", "SUB", "RSB", "ADD", "ADC", "SBC", "RSC", "ORR", "MOV", "BIC", "MVN", "ORN",
                "MUL", "MLA", "UMULL", "UMLAL", "SMULL", "SMLAL", "LSL", "LSR", "ASR", "ROR", "RRX", "NEG",
                "ADRL");
        add(Form.CP, "ADR");   // ADR{cond}{L}: pre-UAL ADRL is written ADR{cond}L
        add(Form.CMP, "TST", "TEQ", "CMP", "CMN");
        add(Form.COND, "B", "BL", "BX", "BLX", "BXJ", "SWI", "SVC", "MRS", "MSR", "CLZ", "SWP", "SWPB",
                "MLS", "UMAAL", "SMLALBB", "SMLALBT", "SMLALTB", "SMLALTT",
                "SMLABB", "SMLABT", "SMLATB", "SMLATT", "SMULBB", "SMULBT", "SMULTB", "SMULTT",
                "SMLAWB", "SMLAWT", "SMULWB", "SMULWT", "SMUAD", "SMUADX", "SMUSD", "SMUSDX",
                "SMLAD", "SMLADX", "SMLSD", "SMLSDX", "SMLALD", "SMLALDX", "SMLSLD", "SMLSLDX",
                "SMMUL", "SMMULR", "SMMLA", "SMMLAR", "SMMLS", "SMMLSR", "SDIV", "UDIV",
                "QADD", "QSUB", "QDADD", "QDSUB", "SSAT", "USAT", "SSAT16", "USAT16",
                "SXTB", "SXTH", "UXTB", "UXTH", "SXTB16", "UXTB16", "SXTAB", "SXTAH", "UXTAB", "UXTAH",
                "SXTAB16", "UXTAB16", "REV", "REV16", "REVSH", "RBIT", "BFC", "BFI", "SBFX", "UBFX",
                "PKHBT", "PKHTB", "SEL", "USAD8", "USADA8", "MOVW", "MOVT", "MOV32",
                "NOP", "YIELD", "WFE", "WFI", "SEV", "SEVL", "DBG", "SMC", "SMI", "HVC", "ERET",
                "LDREX", "LDREXB", "LDREXH", "LDREXD", "STREX", "STREXB", "STREXH", "STREXD",
                "LDA", "LDAB", "LDAH", "STL", "STLB", "STLH", "LDAEX", "LDAEXB", "LDAEXH", "LDAEXD",
                "STLEX", "STLEXB", "STLEXH", "STLEXD",
                "PUSH", "POP", "CPY", "CBZ", "CBNZ", "TBB", "TBH", "UND", "UDF",
                "MCR", "MRC", "MCR2", "MRC2", "MCRR", "MRRC", "MCRR2", "MRRC2", "CDP", "CDP2",
                "MIA", "MIAPH", "MIABB", "MIABT", "MIATB", "MIATT", "MAR", "MRA",
                "CRC32B", "CRC32H", "CRC32W", "CRC32CB", "CRC32CH", "CRC32CW");
        add(Form.NONE, "BKPT", "CPS", "CPSIE", "CPSID", "SETEND", "CLREX", "DMB", "DSB", "ISB", "SSBB", "PSSBB",
                "PLD", "PLDW", "PLI", "RFE", "RFEIA", "RFEIB", "RFEDA", "RFEDB", "RFEFA", "RFEFD", "RFEEA", "RFEED",
                "SRS", "SRSIA", "SRSIB", "SRSDA", "SRSDB", "SRSFA", "SRSFD", "SRSEA", "SRSED", "BLX2", "HLT",
                "SETPAN", "SB", "CSDB",
                "AESD", "AESE", "AESIMC", "AESMC", "SHA1C", "SHA1H", "SHA1M", "SHA1P", "SHA1SU0", "SHA1SU1",
                "SHA256H", "SHA256H2", "SHA256SU0", "SHA256SU1");
        for (String p : new String[] {"S", "Q", "SH", "U", "UQ", "UH"})
            for (String o : new String[] {"ADD16", "SUB16", "ADD8", "SUB8", "ASX", "SAX", "ADDSUBX", "SUBADDX"})
                add(Form.COND, p + o);
        add(Form.LDR, "LDR");
        add(Form.STR, "STR");
        add(Form.LDM, "LDM", "STM");
        add(Form.CP, "LDC", "STC", "LDC2", "STC2");
        add(Form.FPA_DP, "ADF", "MUF", "SUF", "RSF", "DVF", "RDF", "POW", "RPW", "RMF", "FML", "FDV", "FRD", "POL",
                "MVF", "MNF", "ABS", "RND", "SQT", "LOG", "LGN", "EXP", "SIN", "COS", "TAN", "ASN", "ACS", "ATN",
                "URD", "NRM", "FLT", "FIX");
        add(Form.FPA_LS, "LDF", "STF");
        add(Form.FPA_LFM, "LFM", "SFM");
        add(Form.FPA_CMP, "CMF", "CNF", "CMFE", "CNFE", "WFS", "RFS", "WFC", "RFC");
        // VFP and Advanced SIMD (UAL). Data-type qualifiers (.F32, .I16 …) are stripped first.
        add(Form.VFP, "VABA", "VABAL", "VABD", "VABDL", "VABS", "VACGE", "VACGT", "VACLE", "VACLT", "VADD", "VADDHN",
                "VADDL", "VADDW", "VAND", "VBIC", "VBIF", "VBIT", "VBSL", "VCEQ", "VCGE", "VCGT", "VCLE", "VCLS",
                "VCLT", "VCLZ", "VCMP", "VCMPE", "VCNT", "VCVT", "VCVTR", "VCVTB", "VCVTT", "VCVTA", "VCVTN", "VCVTP",
                "VCVTM", "VDIV", "VDUP", "VEOR", "VEXT", "VFMA", "VFMS", "VFNMA", "VFNMS", "VHADD", "VHSUB", "VLD1",
                "VLD2", "VLD3", "VLD4", "VLDR", "VMAX", "VMIN", "VMAXNM", "VMINNM", "VMLA", "VMLAL", "VMLS", "VMLSL",
                "VMOV", "VMOVL", "VMOVN", "VMRS", "VMSR", "VMUL", "VMULL", "VMVN", "VNEG", "VNMLA", "VNMLS", "VNMUL",
                "VORN", "VORR", "VPADAL", "VPADD", "VPADDL", "VPMAX", "VPMIN", "VPOP", "VPUSH", "VQABS", "VQADD",
                "VQDMLAL", "VQDMLSL", "VQDMULH", "VQDMULL", "VQMOVN", "VQMOVUN", "VQNEG", "VQRDMULH", "VQRDMLAH",
                "VQRDMLSH", "VQRSHL", "VQRSHRN", "VQRSHRUN", "VQSHL", "VQSHLU", "VQSHRN", "VQSHRUN", "VQSUB",
                "VRADDHN", "VRECPE", "VRECPS", "VREV16", "VREV32", "VREV64", "VRHADD", "VRINTA", "VRINTN", "VRINTP",
                "VRINTM", "VRINTR", "VRINTX", "VRINTZ", "VRSHL", "VRSHR", "VRSHRN", "VRSQRTE", "VRSQRTS", "VRSRA",
                "VRSUBHN", "VSEL", "VSELEQ", "VSELGE", "VSELGT", "VSELVS", "VSHL", "VSHLL", "VSHR", "VSHRN", "VSLI",
                "VSQRT", "VSRA", "VSRI", "VST1", "VST2", "VST3", "VST4", "VSTR", "VSUB", "VSUBHN", "VSUBL", "VSUBW",
                "VSWP", "VTBL", "VTBX", "VTRN", "VTST", "VUZP", "VZIP", "VJCVT", "VSDOT", "VUDOT");
        add(Form.VLDM, "VLDM", "VSTM");
        // Pre-UAL VFP: precision is part of the name, condition follows.
        for (String p : new String[] {"S", "D"}) {
            for (String o : new String[] {"FABS", "FADD", "FCMP", "FCMPE", "FCMPZ", "FCMPEZ", "FCPY", "FDIV",
                    "FLD", "FST", "FMAC", "FMSC", "FMUL", "FNEG", "FNMAC", "FNMSC", "FNMUL", "FSQRT", "FSUB",
                    "FSITO", "FUITO", "FTOSI", "FTOSIZ", "FTOUI", "FTOUIZ"})
                add(Form.COND, o + p);
            for (String m : new String[] {"IA", "DB", "EA", "FD"}) {
                add(Form.COND, "FLDM" + m + p);
                add(Form.COND, "FSTM" + m + p);
            }
        }
        add(Form.COND, "FCVTDS", "FCVTSD", "FMSR", "FMRS", "FMDLR", "FMDHR", "FMRDL", "FMRDH", "FMDRR", "FMRRD",
                "FMSRR", "FMRRS", "FMXR", "FMRX", "FMSTAT", "FLDMIAX", "FSTMIAX", "FLDMDBX", "FSTMDBX");
    }

    private static final String[] CONDS = {
        "EQ", "NE", "CS", "HS", "CC", "LO", "MI", "PL", "VS", "VC", "HI", "LS", "GE", "LT", "GT", "LE", "AL", "NV"};
    private static final String[] LDR_SUFFIX = {"B", "H", "SB", "SH", "D", "T", "BT", "SBT", "SHT", "HT", "EX"};
    private static final String[] STR_SUFFIX = {"B", "H", "D", "T", "BT", "HT"};
    private static final String[] LDM_MODES = {"IA", "IB", "DA", "DB", "FD", "ED", "FA", "EA"};

    /** True if {@code op} is an instruction mnemonic. */
    public static boolean isInstruction(String op) {
        if (op.isEmpty()) return false;
        String up = op.toUpperCase();
        if (!op.equals(up) && !op.equals(op.toLowerCase())) return false;
        // Width (.W/.N) and data-type qualifiers: keep only the part before the first '.'.
        int dot = up.indexOf('.');
        String w = dot >= 0 ? up.substring(0, dot) : up;
        if (dot >= 0 && !validQualifiers(up.substring(dot))) return false;
        if (w.isEmpty()) return false;
        if (isIT(w)) return true;
        // Try every base that is a prefix of w.
        for (int k = Math.min(w.length(), 10); k >= 1; k--) {
            Form f = BASE.get(w.substring(0, k));
            if (f != null && suffixOk(f, w.substring(0, k), w.substring(k))) return true;
        }
        return false;
    }

    private static boolean validQualifiers(String q) {
        for (String part : q.substring(1).split("\\.", -1)) {
            if (!part.matches("W|N|[FISUP]?(8|16|32|64)|F|I|S|U|P|BF16")) return false;
        }
        return true;
    }

    private static boolean isIT(String w) {
        if (!w.startsWith("IT")) return false;
        String r = w.substring(2);
        return r.length() <= 3 && r.chars().allMatch(c -> c == 'T' || c == 'E');
    }

    private static boolean isCond(String s) { for (String c : CONDS) if (c.equals(s)) return true; return false; }

    private static boolean in(String s, String[] set) { for (String c : set) if (c.equals(s)) return true; return false; }

    /** Is {@code r} a condition code optionally followed or preceded by one of {@code extra}? */
    private static boolean condWith(String r, String[] extra) {
        if (r.isEmpty() || isCond(r) || in(r, extra)) return true;
        for (int i = 1; i < r.length(); i++) {
            String a = r.substring(0, i), b = r.substring(i);
            if (isCond(a) && in(b, extra)) return true;
            if (in(a, extra) && isCond(b)) return true;
        }
        return false;
    }

    private static boolean suffixOk(Form f, String base, String r) {
        switch (f) {
            case DP: return condWith(r, new String[] {"S"});
            case CMP: return condWith(r, new String[] {"P", "S"});
            case COND: case VFP: case FPA_CMP: return r.isEmpty() || isCond(r);
            case NONE: return r.isEmpty();
            case LDR: return condWith(r, LDR_SUFFIX);
            case STR: return condWith(r, STR_SUFFIX);
            case LDM: return condWith(r, LDM_MODES);
            case CP: return condWith(r, new String[] {"L"});
            case VLDM: return condWith(r, new String[] {"IA", "DB"});
            case FPA_LS: return condWith(r, new String[] {"S", "D", "E", "P"});
            case FPA_LFM: return condWith(r, new String[] {"FD", "EA"});
            case FPA_DP: {
                // {cond}{prec}{round}
                String rest = r;
                if (rest.length() >= 2 && isCond(rest.substring(0, 2))) rest = rest.substring(2);
                if (!rest.isEmpty() && "SDE".indexOf(rest.charAt(0)) >= 0) rest = rest.substring(1);
                else if (!base.equals("FIX")) return false;   // precision is required, except for FIX
                if (!rest.isEmpty() && "PMZ".indexOf(rest.charAt(0)) >= 0) rest = rest.substring(1);
                return rest.isEmpty();
            }
            default: return false;
        }
    }
}
