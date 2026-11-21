package com.piccolosystems.riscos.asmindex.expr;

/** What the evaluator needs from the assembler. Positions are offsets in the text being evaluated. */
public interface EvalContext {
    /** Value of a symbol; records a use, creating an entry if absent. */
    Value symbol(String name, int pos, int end);
    /** {@code {NAME}} built-in variable. */
    Value builtin(String name);
    Value pc();
    Value storageCounter();
    /** :DEF: — true if the name has any symbol table entry. */
    boolean isDefined(String name, int pos, int end);
    boolean isMacro(String name);
    /** %[FB][TA]n[routine] reference. */
    Value localLabel(char dir, char level, int number, String routine, int pos, int end);
    /** ?symbol */
    Value sizeOf(String name, int pos, int end);
}
