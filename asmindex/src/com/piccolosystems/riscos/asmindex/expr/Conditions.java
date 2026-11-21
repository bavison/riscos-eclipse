package com.piccolosystems.riscos.asmindex.expr;

/** ARM condition codes, from the Arm Architecture Reference Manual. */
public final class Conditions {
    private static final String[] NAMES = {
        "EQ", "NE", "CS", "CC", "MI", "PL", "VS", "VC", "HI", "LS", "GE", "LT", "GT", "LE", "AL", "NV"};

    private Conditions() {}

    /** Encoding 0–15, or −1. HS and LO are synonyms for CS and CC; case-insensitive. */
    public static int encoding(String cc) {
        String u = cc.toUpperCase();
        if (u.equals("HS")) return 2;
        if (u.equals("LO")) return 3;
        if (u.isEmpty()) return 14;
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(u)) return i;
        return -1;
    }

    /** The inverse condition, keeping the case of the input; null if not a condition. */
    public static String reverse(String cc) {
        int e = encoding(cc);
        if (e < 0 || e >= 14 || cc.isEmpty()) return null;
        String r = NAMES[e ^ 1];
        if (cc.toUpperCase().equals("HS")) r = "LO";
        else if (cc.toUpperCase().equals("LO")) r = "HS";
        return Character.isLowerCase(cc.charAt(0)) ? r.toLowerCase() : r;
    }

    public static boolean isCondition(String cc) {
        int e = encoding(cc);
        return e >= 0 && !cc.isEmpty() && (cc.equals(cc.toUpperCase()) || cc.equals(cc.toLowerCase()));
    }
}
