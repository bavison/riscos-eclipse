package com.piccolosystems.riscos.asmindex.loc;


/** An AREA and its location counter. */
public final class Area {
    public final int id;
    public final String name;
    public long pc;                 // offset within the area
    public boolean exact = true;    // false once a size had to be approximated
    public boolean absolute;        // ABS attribute: labels are plain numbers
    public long org;                // ORG base for absolute areas
    public boolean code;
    /** Literal pool entries waiting for LTORG or the end of the area: key → {size, alignment}. */
    public final java.util.LinkedHashMap<String, int[]> pendingLiterals = new java.util.LinkedHashMap<>();

    public Area(int id, String name) { this.id = id; this.name = name; }

    public void align(int n) {
        if (n > 1) pc = (pc + n - 1) / n * n;
    }

    public void alignWithOffset(long n, long offset) {
        if (n <= 1) return;
        long rel = Math.floorMod(pc - offset, n);
        if (rel != 0) pc += n - rel;
    }

    /** Literals already placed in a dumped pool: key → address. */
    public final java.util.Map<String, java.util.List<Long>> placed = new java.util.HashMap<>();

    /** Pass 1: dumps the pool and returns its size; literals are placed 4 bytes apart. */
    public long dumpLiterals() {
        if (pendingLiterals.isEmpty()) return 0;
        align(4);
        long start = pc;
        for (java.util.Map.Entry<String, int[]> e : pendingLiterals.entrySet()) {
            align(e.getValue()[1]);
            placed.computeIfAbsent(e.getKey(), x -> new java.util.ArrayList<>()).add(pc);
            pc += e.getValue()[0];
        }
        pendingLiterals.clear();
        return pc - start;
    }

    /** Pass 1: is a defined literal already placed within reach of an LDR at the current location? */
    public boolean reachable(String key, int range) {
        java.util.List<Long> l = placed.get(key);
        if (l == null) return false;
        for (long a : l) if (Math.abs(a - pc - 8) < range) return true;
        return false;
    }
}
