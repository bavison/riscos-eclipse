package com.piccolosystems.riscos.asmindex.sym;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** The global, case-sensitive symbol table. */
public final class SymbolTable {
    private final Map<String, Symbol> map = new HashMap<>();

    public Symbol get(String name) { return map.get(name); }

    public Symbol getOrCreate(String name) { return map.computeIfAbsent(name, Symbol::new); }

    public boolean contains(String name) { return map.containsKey(name); }

    public void remove(String name) { map.remove(name); }

    public Collection<Symbol> all() { return map.values(); }
}
