package com.piccolosystems.riscos.asmindex.run;

import com.piccolosystems.riscos.asmindex.cond.Frame;
import com.piccolosystems.riscos.asmindex.cond.State;
import com.piccolosystems.riscos.asmindex.cond.Tri;
import com.piccolosystems.riscos.asmindex.expr.EvalContext;
import com.piccolosystems.riscos.asmindex.expr.Evaluator;
import com.piccolosystems.riscos.asmindex.expr.Value;
import com.piccolosystems.riscos.asmindex.loc.Area;
import com.piccolosystems.riscos.asmindex.loc.LocalLabel;
import com.piccolosystems.riscos.asmindex.macro.MacroDef;
import com.piccolosystems.riscos.asmindex.macro.MacroSyntax;
import com.piccolosystems.riscos.asmindex.parse.Directive;
import com.piccolosystems.riscos.asmindex.parse.LineSplitter;
import com.piccolosystems.riscos.asmindex.parse.Mnemonics;
import com.piccolosystems.riscos.asmindex.parse.SplitLine;
import com.piccolosystems.riscos.asmindex.source.FileResolver;
import com.piccolosystems.riscos.asmindex.source.LogicalLine;
import com.piccolosystems.riscos.asmindex.source.SourceFile;
import com.piccolosystems.riscos.asmindex.source.Text;
import com.piccolosystems.riscos.asmindex.subst.Substituter;
import com.piccolosystems.riscos.asmindex.sym.Site;
import com.piccolosystems.riscos.asmindex.sym.Symbol;
import com.piccolosystems.riscos.asmindex.sym.Symbol.Kind;
import com.piccolosystems.riscos.asmindex.sym.Symbol.Status;
import com.piccolosystems.riscos.asmindex.sym.SymbolTable;
import com.piccolosystems.riscos.asmindex.target.Targets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs objasm's two passes over one entry point and
 * collects symbols, conditional outcomes, folding ranges and diagnostics.
 */
public final class Assembler {
    /** The name of the pseudo file holding a predefine ({@code -pd}): "[predefine 1]", a legal file name everywhere. */
    static final String PREDEFINE = "[predefine ";

    /** WHILE iteration cap: beyond this the loop is treated as unknown. */
    public static final int WHILE_CAP = 10000;
    /** Nesting cap for GET and macro calls, to stop runaway recursion. */
    public static final int NEST_CAP = 256;

    // ---------------- configuration ----------------
    private final EntryPoint entry;
    private final FileResolver resolver;
    private final Targets targets;
    private final Map<Path, SourceFile> cache;

    // ---------------- results ----------------
    public final SymbolTable symbols = new SymbolTable();
    public final Map<String, MacroDef> macros = new LinkedHashMap<>();
    public final List<LocalLabel> localLabels = new ArrayList<>();          // pass 2
    private final List<LocalLabel> pass1LocalLabels = new ArrayList<>();
    private final Map<String, Diagnostic> diagnostics = new LinkedHashMap<>();
    /**
     * Per file: per physical line, pass-2 counts of [assembled, skipped, unknown,
     * skipped inside a macro expansion]. Macro bodies are templates assembled once
     * per call, so a skip there is kept apart and not shaded by default.
     */
    public final Map<SourceFile, int[][]> lineCounts = new LinkedHashMap<>();
    /** Folding ranges: file → set of {startLine, endLine, kind}. */
    public final Map<String, Set<List<Object>>> folds = new LinkedHashMap<>();
    /** GET edges: includer site → included file. */
    public final List<Object[]> includes = new ArrayList<>();
    public final Set<String> filesRead = new LinkedHashSet<>();
    public final Map<String, Integer> metrics = new LinkedHashMap<>();
    /** Distinct sites of IF/ELIF/WHILE conditions that were unknown in pass 2, with a reason. */
    public final Map<Site, String> unknownConditions = new LinkedHashMap<>();

    // ---------------- per-pass state ----------------
    private int pass;
    private State state;
    private final Deque<Frame> frames = new ArrayDeque<>();
    private final Deque<Input> inputs = new ArrayDeque<>();
    private boolean passEnded;
    private final Map<String, Area> areas = new LinkedHashMap<>();
    private Area area;
    private int nextAreaId;
    private Value storage;          // storage-map counter
    private boolean thumb, ual;
    private int scopeId;            // local-label scope
    private String routine;         // current ROUT name
    private int localSeq;
    private final Map<String, Integer> whileCounts = new HashMap<>();
    private int linesProcessed;
    private int currentRoutStart = -1;
    private String currentRoutFile;
    /** The file the current routine is in, to fold the last routine to its end. */
    private SourceFile currentRoutSource;

    /** A source of lines: a file being read, or a macro body being expanded. */
    /**
     * Set before {@link #run()} to record the first expansion, in pass 2, of
     * the macro call written at {@code file} line {@code line} (not one inside
     * another macro): each line after substitution, and whether it was
     * assembled. The run stops once that expansion ends.
     */
    public static final class Capture {
        public final String file;
        public final int line;
        /** {text, state}: state is "" if assembled, "skip" if not, "?" if undecided. */
        public final List<String[]> lines = new ArrayList<>();
        /**
         * The lines that produce code or data, after '$' substitution, with
         * the macros called inside expanded too: instructions, DCD and the
         * like, SPACE, FILL, ALIGN, LTORG and INCBIN.
         */
        public final List<String> output = new ArrayList<>();
        public boolean started, done;

        public Capture(String file, int line) {
            this.file = file;
            this.line = line;
        }
    }

    public Capture capture;

    /**
     * Set before {@link #run()} to record how the line at {@code file} line
     * {@code line} reads after '$' substitution, each time it is assembled in
     * pass 2 (a macro body line once per expansion, a WHILE body once per
     * time round). The run stops once {@link #LIMIT} different results are seen.
     */
    public static final class LineCapture {
        public static final int LIMIT = 10;
        public final String file;
        public final int line;
        /** Each different result, with how many times it was seen. */
        public final Map<String, Integer> results = new LinkedHashMap<>();

        public LineCapture(String file, int line) {
            this.file = file;
            this.line = line;
        }
    }

    public LineCapture lineCapture;

    private static final class Input {
        boolean capturing;               // this expansion is being captured
        final SourceFile file;           // for files
        final MacroDef macro;            // for expansions
        final Map<String, String> args;
        final Map<String, Symbol> locals;
        final Site callSite;             // the macro call, for via chains
        final int level;                 // macro nesting level (0 = not in a macro)
        int next;
        Frame frame;

        Input(SourceFile f) { file = f; macro = null; args = null; locals = null; callSite = null; level = 0; }

        Input(MacroDef m, Map<String, String> args, Site callSite, int level) {
            file = null; macro = m; this.args = args; locals = new HashMap<>(); this.callSite = callSite; this.level = level;
        }

        int size() { return macro != null ? macro.body.size() : file.size(); }
        LogicalLine line(int i) { return macro != null ? macro.body.get(i) : file.line(i); }
    }

    public Assembler(EntryPoint entry, FileResolver resolver, Map<Path, SourceFile> cache) {
        this.entry = entry;
        this.resolver = resolver;
        this.targets = Targets.fromOptions(entry.options);
        this.cache = cache;
    }

    public Targets targets() { return targets; }

    /** Debug: {file suffix, first line, last line} whose pass-2 locations are printed to stderr. */
    public String[] trace;

    public List<Diagnostic> diagnostics() { return new ArrayList<>(diagnostics.values()); }

    // =====================================================================
    // Driver
    // =====================================================================

    public void run() throws IOException {
        SourceFile top = load(entry.file);
        if (top == null) {
            diag(Diagnostic.Severity.ERROR, "entry-missing", "entry point not found: " + entry.file, null);
            return;
        }
        for (pass = 1; pass <= 2; pass++) runPass(top);
        finish();
    }

    private void runPass(SourceFile top) {
        // Variables are reset between passes; labels and macros persist.
        if (pass == 2) {
            List<String> vars = new ArrayList<>();
            for (Symbol s : symbols.all()) {
                if (s.kind.isVariable()) vars.add(s.name);
                s.definedInPass = 0;
            }
            for (String v : vars) symbols.remove(v);
        }
        if (pass == 1) predefineRegisters();
        state = State.ASSEMBLE;
        frames.clear();
        inputs.clear();
        passEnded = false;
        areas.clear();
        area = null;
        nextAreaId = 1;
        poolIndex = 0;
        undefinedLiterals = 0;
        storage = Value.num(0);
        thumb = targets.thumbDefault;
        ual = targets.ualDefault;
        scopeId = 0;
        routine = null;
        localSeq = 0;
        whileCounts.clear();
        currentRoutStart = -1;
        predefines();
        Input in = new Input(top);
        inputs.push(in);
        filesRead.add(top.name);
        while (!inputs.isEmpty() && !passEnded) {
            Input cur = inputs.peek();
            if (cur.next >= cur.size()) {
                endOfInput(cur);
                continue;
            }
            LogicalLine ll = cur.line(cur.next++);
            linesProcessed++;
            currentSite = site(ll, 0, 0, cur);
            if (trace != null && pass == 2 && ll.file.name.endsWith(trace[0])) {
                int ln = ll.firstLine;
                if (ln >= Integer.parseInt(trace[1]) && ln <= Integer.parseInt(trace[2]))
                    System.err.printf("%-8s %s %-4s %5d %s%n", area == null ? "-" : Long.toHexString(area.pc), state.name().charAt(0),
                            cur.macro != null ? "m" + cur.level : "", ln, ll.text);
            }
            try {
                processLine(ll, cur);
            } catch (RuntimeException e) {
                diag(Diagnostic.Severity.ERROR, "engine-exception", e.toString(), site(ll, 0, 0, cur));
            }
        }
        if (area != null) dumpPool(area);
        closeRout(null, 0);
        if (pass == 2) {
            finalAreas.clear();
            // objasm rounds each area's size up to a word.
            for (Area a : areas.values()) finalAreas.put(a.name, new long[] {(a.pc + 3) & ~3L, a.exact ? 1 : 0});
        }
    }

    private void predefineRegisters() {
        for (int r = 0; r < 16; r++) {
            predef("r" + r, Kind.REGISTER, r);
            predef("R" + r, Kind.REGISTER, r);
        }
        predef("sp", Kind.REGISTER, 13); predef("SP", Kind.REGISTER, 13);
        predef("lr", Kind.REGISTER, 14); predef("LR", Kind.REGISTER, 14);
        predef("pc", Kind.REGISTER, 15); predef("PC", Kind.REGISTER, 15);
        for (Map.Entry<String, Integer> e : targets.apcsRegisters().entrySet()) predef(e.getKey(), Kind.REGISTER, e.getValue());
        for (int r = 0; r < 32; r++) {
            predef("s" + r, Kind.EXT_REGISTER, r); predef("S" + r, Kind.EXT_REGISTER, r);
            predef("d" + r, Kind.EXT_REGISTER, r); predef("D" + r, Kind.EXT_REGISTER, r);
            if (r < 16) { predef("q" + r, Kind.EXT_REGISTER, r); predef("Q" + r, Kind.EXT_REGISTER, r); }
        }
        for (int r = 0; r < 8; r++) { predef("f" + r, Kind.FP_REGISTER, r); predef("F" + r, Kind.FP_REGISTER, r); }
        for (int r = 0; r < 16; r++) { predef("p" + r, Kind.COPROCESSOR, r); predef("c" + r, Kind.COPROC_REGISTER, r); }
        for (int r = 0; r < 8; r++) predef("acc" + r, Kind.REGISTER, r);
    }

    private void predef(String name, Kind k, int v) {
        Symbol s = symbols.getOrCreate(name);
        s.kind = k;
        s.status = Status.DEFINED;
        s.value = Value.num(v);
        s.predefined = true;
    }

    /** -PD "name SETx expr": an implicit GBLx and an assignment, before the source. */
    private void predefines() {
        int n = 0;
        for (String pd : entry.predefines) {
            n++;
            SourceFile sf = SourceFile.fromText(Path.of(PREDEFINE + n + "]"), pd);
            if (sf.size() == 0) continue;
            LogicalLine ll = sf.line(0);
            SplitLine sl = LineSplitter.split(ll.toText());
            Directive d = Directive.lookup(sl.op());
            if (sl.labelKind != SplitLine.LabelKind.SYMBOL || d == null
                    || (d != Directive.SETA && d != Directive.SETL && d != Directive.SETS)) {
                diag(Diagnostic.Severity.ERROR, "bad-predefine", "bad -PD: " + pd, null);
                continue;
            }
            Kind k = d == Directive.SETA ? Kind.VAR_A : d == Directive.SETL ? Kind.VAR_L : Kind.VAR_S;
            Input fake = new Input(sf);
            declareVariable(sl.label, k, false, siteOf(sl.text, sl.labelStart, sl.labelEnd, fake), fake, true);
            Symbol s = symbols.get(sl.label);
            if (s != null) s.predefined = true;
            setVariable(sl, d, fake);
        }
    }

    private void endOfInput(Input in) {
        if (in.macro != null) {
            exitMacro(in, false);
            return;
        }
        if (inputs.size() == 1) {
            if (pass == 1) diag(Diagnostic.Severity.WARNING, "missing-end", "file ends without END", lastSite(in));
            closeFile(in);
            passEnded = true;
            return;
        }
        closeFile(in);
    }

    private Site lastSite(Input in) {
        if (in.size() == 0) return new Site(in.file.name, 1, 0, 0, null);
        LogicalLine l = in.line(in.size() - 1);
        return new Site(l.file.name, l.firstLine, 0, 0, null);
    }

    /** Ends a file: unwinds to its GET frame, complaining about open IF/WHILE. */
    private void closeFile(Input in) {
        while (!frames.isEmpty() && frames.peek() != in.frame) {
            Frame f = frames.pop();
            if (f.type == Frame.Type.IF || f.type == Frame.Type.WHILE) {
                diag(Diagnostic.Severity.ERROR, "unclosed", f.type + " not closed before end of file", f.site);
                state = f.outer;
            } else break;
        }
        if (!frames.isEmpty() && frames.peek() == in.frame) frames.pop();
        inputs.remove(in);
    }

    // =====================================================================
    // Per-line processing
    // =====================================================================

    private void processLine(LogicalLine ll, Input in) {
        Text t = ll.toText();
        if (in.macro != null) t = Substituter.macroParams(t, in.args);
        if (t.length() > 0 && t.charAt(0) == '*') {
            if (in.capturing) captured(t.s);
            record(ll, state);
            return;
        }
        boolean lineUnknown = false;
        if (t.s.indexOf('$') >= 0) {
            Substituter.Result r = Substituter.variables(t, name -> variableValue(name, in));
            // Information only: almost always a '$' in a string that should
            // have been written '$$', which objasm leaves as it is.
            if (pass == 1 && state != State.SKIP)
                for (String nv : r.notVariables)
                    diag(Diagnostic.Severity.INFO, "subst-not-variable", "$" + nv + " is not a variable", site(ll, 0, 0, in));
            if (pass == 2 && state != State.SKIP) {
                for (int k = 0; k < r.uses.size(); k++) {
                    Symbol s = lookupVariableSymbol(r.useNames.get(k), in);
                    if (s != null) {
                        int[] u = r.uses.get(k);
                        s.uses.add(site(ll, u[0], u[1] - u[0], in));
                    }
                }
            }
            if (r.unknown && state != State.SKIP) lineUnknown = true;
            t = r.text;
        }
        if (in.capturing) captured(t.s);
        if (lineCapture != null && pass == 2 && state != State.SKIP && ll.file.name.equals(lineCapture.file)
                && lineCapture.line >= ll.firstLine && lineCapture.line <= ll.lineOf(Math.max(0, ll.toText().length() - 1))) {
            lineCapture.results.merge(t.s.stripTrailing(), 1, Integer::sum);
            if (lineCapture.results.size() >= LineCapture.LIMIT) passEnded = true;
        }
        SplitLine sl = LineSplitter.split(t);
        // The lines of a conditional are structure, assembled or not: don't mark them.
        if (in.capturing && CONDITIONAL_OPS.contains(sl.op())) capture.lines.get(capture.lines.size() - 1)[1] = "";
        if (sl.comment) { record(ll, state); return; }
        String op = sl.op();
        Directive d = sl.hasOp() ? Directive.lookup(op) : null;

        if (state == State.SKIP) {
            if (d != null && d.structural) directive(d, sl, ll, in, false);
            else record(ll, State.SKIP);
            return;
        }
        if (lineUnknown && (d == null || !d.structural)) {
            record(ll, State.UNKNOWN);
            return;
        }
        if (d != null) {
            if (d.isData() || OUTPUT_DIRECTIVES.contains(d)) output(t.s);
            directive(d, sl, ll, in, lineUnknown);
            return;
        }
        record(ll, state);
        if (!sl.hasOp()) {
            // A label on its own line.
            defineLineLabel(sl, in, 0);
            return;
        }
        if (Mnemonics.isInstruction(op)) {
            output(t.s);
            instruction(sl, in, op);
            return;
        }
        MacroDef m = findMacro(op);
        if (m != null) {
            callMacro(m, sl, ll, in, op);
            return;
        }
        diag(Diagnostic.Severity.ERROR, "unknown-op", "unknown operation '" + op + "'", siteOf(sl.text, sl.opStart, sl.opEnd, in));
        output(t.s);
        instruction(sl, in, op);
    }

    // =====================================================================
    // Line outcomes, sites, diagnostics
    // =====================================================================

    private void record(LogicalLine ll, State s) {
        if (pass != 2) return;
        int[][] c = lineCounts.computeIfAbsent(ll.file, f -> new int[f.physicalLineCount + 1][4]);
        int idx = s == State.ASSEMBLE ? 0 : s == State.SKIP ? (inMacroExpansion() ? 3 : 1) : 2;
        for (int k = 0; k < ll.physicalLineCount(); k++) {
            int line = ll.firstLine + k;
            if (line < c.length) c[line][idx]++;
        }
    }

    private static final Set<String> CONDITIONAL_OPS = Set.of("[", "|", "]", "IF", "ELIF", "ELSE", "ENDIF");

    private static final Set<Directive> OUTPUT_DIRECTIVES =
            Set.of(Directive.SPACE, Directive.FILL, Directive.ALIGN, Directive.LTORG, Directive.INCBIN);

    /** A line that produces code or data, inside the expansion being captured (at any depth). */
    private void output(String text) {
        if (capture != null && capture.started && !capture.done && pass == 2) capture.output.add(text.stripTrailing());
    }

    private void captured(String text) {
        capture.lines.add(new String[] {text, state == State.SKIP ? "skip" : state == State.UNKNOWN ? "?" : ""});
    }

    private boolean inMacroExpansion() {
        for (Input i : inputs) if (i.macro != null) return true;
        return false;
    }

    private Site site(LogicalLine ll, int col, int len, Input in) {
        return new Site(ll.file.name, ll.lineOf(col), ll.columnOf(col), len, in == null ? null : in.callSite);
    }

    /** Site of text[from,to) in a (possibly substituted) line. */
    private Site siteOf(Text t, int from, int to, Input in) {
        int c0 = t.originCol(Math.max(0, from));
        int c1 = to > from ? t.originCol(to - 1) + 1 : c0;
        return site(t.origin, c0, Math.max(0, c1 - c0), in);
    }

    private void diag(Diagnostic.Severity sev, String code, String msg, Site site) {
        Diagnostic d = new Diagnostic(sev, code, msg, site, pass);
        diagnostics.putIfAbsent(d.key(), d);
    }

    /** A fold from {@code start} to the line before {@code next} (an ELIF, ELSE or ENDIF). */
    private void foldBefore(Site start, LogicalLine next, String kind) {
        if (pass != 2 || start == null || !start.file.equals(next.file.name)) return;
        int end = next.firstLine - 1;
        if (end <= start.line) return;
        folds.computeIfAbsent(start.file, f -> new LinkedHashSet<>()).add(List.of(start.line, end, kind));
    }

    private void fold(Site start, LogicalLine endLine, String kind) {
        if (pass != 2 || start == null || !start.file.equals(endLine.file.name)) return;
        int end = endLine.firstLine + endLine.physicalLineCount() - 1;
        if (end <= start.line) return;
        folds.computeIfAbsent(start.file, f -> new LinkedHashSet<>()).add(List.of(start.line, end, kind));
    }

    private boolean conditional() { return state == State.UNKNOWN; }

    // =====================================================================
    // Expression evaluation
    // =====================================================================

    /** Evaluation context bound to one line. */
    private final class Ctx implements EvalContext {
        final Text text;
        final Input in;
        final boolean forCondition;
        final boolean recordUses;
        boolean usedApproximate;

        Ctx(Text text, Input in, boolean forCondition) {
            this.text = text; this.in = in; this.forCondition = forCondition;
            this.recordUses = pass == 2 && state != State.SKIP;
        }

        Site at(int pos, int end) { return siteOf(text, pos, end, in); }

        @Override public Value symbol(String name, int pos, int end) {
            if (in != null && in.locals != null) {
                Symbol l = in.locals.get(name);
                if (l != null) {
                    if (recordUses) l.uses.add(at(pos, end));
                    return l.value;
                }
            }
            Symbol s = symbols.get(name);
            if (s == null) {
                s = symbols.getOrCreate(name);   // so that a later definition is found by its uses
            }
            if (recordUses && !s.predefined) s.uses.add(at(pos, end));
            if (s.kind.isVariable() && pass == 2 && !s.declaredInPass2) return Value.undefined(name + " not declared in pass 2");
            if (s.status == Status.UNDEFINED) return Value.undefined(name + " undefined");
            if (s.status == Status.PARTIAL) return Value.undefined(name + " not yet defined");
            if (s.approximate) {
                usedApproximate = true;
                if (forCondition) return Value.unknown(name + " has an approximate value");
            }
            return s.value;
        }

        @Override public Value builtin(String name) {
            switch (name) {
                case "TRUE": return Value.TRUE;
                case "FALSE": return Value.FALSE;
                case "PC": return pc();
                case "VAR": return storageCounter();
                case "OPT": return Value.num(0);       // headers only save and restore it
                case "AREANAME": return area == null ? Value.unknown("no area") : Value.str(area.name);
                case "INPUTFILE": return Value.str(text.origin.file.name);
                case "LINENUM": return Value.num(text.origin.firstLine);
                case "LINENUMUP": case "LINENUMUPPER": {
                    Site s = in != null ? in.callSite : null;
                    if (s == null) return Value.num(text.origin.firstLine);
                    if (name.equals("LINENUMUPPER")) while (s.via != null) s = s.via;
                    return Value.num(s.line);
                }
                case "OBJASM_VERSION": return Value.unknown("objasm version");
                case "PCSTOREOFFSET": return Value.unknown("{PCSTOREOFFSET} not modelled");
                default: return targets.builtin(name, thumb, ual);
            }
        }

        @Override public Value pc() {
            if (area == null) return Value.unknown("no AREA yet");
            if (!area.exact) {
                usedApproximate = true;
                if (forCondition) return Value.unknown("location is approximate");
            }
            return area.absolute ? Value.num(area.org + area.pc) : Value.progRel(area.id, (int) area.pc);
        }

        @Override public Value storageCounter() { return storage; }

        @Override public boolean isDefined(String name, int pos, int end) {
            Symbol s = symbols.get(name);
            if (s == null) return false;
            if (recordUses && !s.predefined) s.uses.add(at(pos, end));
            if (s.kind.isVariable()) return pass == 1 || s.declaredInPass2;
            return true;
        }

        @Override public boolean isMacro(String name) {
            MacroDef m = macros.get(name);
            return m != null && (pass == 1 || m.definedInPass2);
        }

        @Override public Value localLabel(char dir, char level, int number, String rname, int pos, int end) {
            LocalLabel l = findLocal(dir, level, number, in == null ? 0 : in.level);
            if (rname != null && routine != null && !rname.equals(routine))
                diag(Diagnostic.Severity.ERROR, "local-label-routine", "routine name " + rname + " doesn't match " + routine, at(pos, end));
            if (l == null) {
                if (pass == 2) diag(Diagnostic.Severity.ERROR, "local-label-missing", "local label %" + dir + level + number + " not found", at(pos, end));
                return Value.undefined("local label");
            }
            if (recordUses) {
                if (pass == 2 && !localLabels.contains(l)) forwardRefs.computeIfAbsent(l.seq, k -> new ArrayList<>()).add(at(pos, end));
                else l.refs.add(at(pos, end));
            }
            return l.value;
        }

        @Override public Value sizeOf(String name, int pos, int end) {
            Symbol s = symbols.get(name);
            if (s == null || s.status != Status.DEFINED) return Value.undefined("?" + name);
            if (recordUses) s.uses.add(at(pos, end));
            return Value.num(s.size);
        }
    }

    private Value eval(Text t, int from, int to, Input in, boolean cond) {
        if (from < 0 || from >= to) return Value.unknown("missing expression");
        Ctx c = new Ctx(t, in, cond);
        Evaluator.Result r = Evaluator.evaluate(t.s, from, to, c);
        if (r.error != null && !r.value.isVague()) return r.value;
        if (r.error != null) {
            diag(Diagnostic.Severity.ERROR, "expr", r.error, siteOf(t, from, to, in));
        }
        lastApproximate = c.usedApproximate;
        return r.value;
    }

    private boolean lastApproximate;

    private Value variableValue(String name, Input in) {
        Symbol s = lookupVariableSymbol(name, in);
        if (s == null) return null;
        if (pass == 2 && !s.declaredInPass2 && (in == null || in.locals == null || in.locals.get(name) != s)) return null;
        return s.value;
    }

    private Symbol lookupVariableSymbol(String name, Input in) {
        if (in != null && in.locals != null) {
            Symbol l = in.locals.get(name);
            if (l != null) return l;
        }
        Symbol s = symbols.get(name);
        return s != null && s.kind.isVariable() ? s : null;
    }

    /** Evaluates an IF/ELIF/WHILE condition to a three-valued result. */
    private Tri condition(SplitLine sl, Input in, Site site, boolean lineUnknown) {
        if (lineUnknown) {
            noteUnknown(site, "substituted variable has an unknown value");
            return Tri.UNKNOWN;
        }
        if (sl.argStart < 0) {
            diag(Diagnostic.Severity.ERROR, "expr", "missing condition", site);
            return Tri.UNKNOWN;
        }
        Value v = eval(sl.text, sl.argStart, sl.argEnd, in, true);
        if (v.isBool()) return Tri.of(v.bool);
        if (v.isUndefined()) {
            diag(Diagnostic.Severity.ERROR, "cond-undefined", "condition uses an undefined symbol: " + v.why, site);
            noteUnknown(site, "undefined: " + v.why);
            return Tri.UNKNOWN;
        }
        if (v.isUnknown()) {
            noteUnknown(site, v.why);
            return Tri.UNKNOWN;
        }
        diag(Diagnostic.Severity.ERROR, "cond-type", "condition is not logical: " + v.display(), site);
        return Tri.UNKNOWN;
    }

    private void noteUnknown(Site site, String why) {
        if (pass == 2) unknownConditions.putIfAbsent(site, why == null ? "unknown" : why);
    }

    // =====================================================================
    // Symbols
    // =====================================================================

    /**
     * Defines a symbol. Handles redefinition, pass-2 re-definition, and
     * definitions in regions the engine can't decide.
     */
    private Symbol define(String name, Kind kind, Value value, int size, Site site) {
        Symbol s = symbols.getOrCreate(name);
        if (s.predefined && s.kind == kind && (kind == Kind.REGISTER || kind == Kind.EXT_REGISTER || kind == Kind.FP_REGISTER
                || kind == Kind.COPROCESSOR || kind == Kind.COPROC_REGISTER) && value.sameAs(s.value)) {
            return s;   // e.g. "sp RN 13"
        }
        boolean isNew = s.kind == Kind.UNDEFINED || s.status == Status.UNDEFINED && !s.kind.isVariable()
                || (s.status == Status.PARTIAL || s.definedInPass != pass) && equFamily(s.kind) && equFamily(kind);
        if (!isNew && s.kind != kind && !(compatible(s.kind, kind))) {
            diag(Diagnostic.Severity.ERROR, "redefined-kind",
                    name + " already defined as " + s.kind.description, site);
            return s;
        }
        if (s.definedInPass == pass && s.status == Status.DEFINED) {
            boolean regLike = kind == Kind.REGISTER || kind == Kind.EXT_REGISTER || kind == Kind.FP_REGISTER
                    || kind == Kind.COPROCESSOR || kind == Kind.COPROC_REGISTER;
            if (regLike && value.sameAs(s.value)) {
                addDef(s, site);
                return s;
            }
            if (conditional() || s.conditional) {
                if (!value.sameAs(s.value)) s.value = Value.unknown("defined differently in undecided branches");
                s.conditional = true;
                addDef(s, site);
                return s;
            }
            diag(Diagnostic.Severity.ERROR, "redefined", name + " is already defined", site);
            return s;
        }
        if (pass == 2 && s.definedInPass == 0 && s.status == Status.DEFINED && s.value != null
                && !s.approximate && !s.conditional && !value.isVague() && !value.sameAs(s.value)
                && (kind != Kind.LABEL || !phaseReported.contains(area == null ? "" : area.name))) {
            if (kind == Kind.LABEL && area != null) phaseReported.add(area.name);
            diag(Diagnostic.Severity.WARNING, "phase", name + " has a different value in pass 2 ("
                    + value.display() + " vs " + s.value.display() + ")", site);
        }
        s.kind = kind;
        s.value = value;
        s.size = size;
        s.status = value.isUndefined() ? Status.PARTIAL : Status.DEFINED;
        s.definedInPass = pass;
        if (conditional()) s.conditional = true;
        addDef(s, site);
        return s;
    }

    /** Kinds an EQU can produce, which can change once a forward reference is resolved. */
    private static boolean equFamily(Kind k) {
        return k == Kind.CONSTANT || k == Kind.ADDRESS || k == Kind.REG_RELATIVE || k == Kind.LABEL || k == Kind.FIELD;
    }

    private static boolean compatible(Kind a, Kind b) {
        // Address constants and labels are the same kind of thing for redefinition purposes.
        return (a == Kind.LABEL || a == Kind.ADDRESS) && (b == Kind.LABEL || b == Kind.ADDRESS);
    }

    private void addDef(Symbol s, Site site) {
        if (s.firstDef == null) s.firstDef = site;
        if (pass == 2 && site != null && !s.defs.contains(site)) s.defs.add(site);
    }

    /** Area name → {size, exact(1/0)} at the end of pass 2. */
    public final Map<String, long[]> finalAreas = new LinkedHashMap<>();

    private Value pcValue() {
        if (area == null) return Value.unknown("no AREA");
        return area.absolute ? Value.num(area.org + area.pc) : Value.progRel(area.id, (int) area.pc);
    }

    /** Defines the label field of a line as a program label (or local label) at the current location. */
    private void defineLineLabel(SplitLine sl, Input in, int size) {
        if (!sl.hasLabel()) return;
        Site site = siteOf(sl.text, sl.labelStart, sl.labelEnd, in);
        if (sl.labelKind == SplitLine.LabelKind.LOCAL) {
            defineLocal(sl.label, site, in);
            return;
        }
        if (sl.labelKind != SplitLine.LabelKind.SYMBOL) {
            diag(Diagnostic.Severity.ERROR, "bad-label", "invalid line start '" + sl.label + "'", site);
            return;
        }
        if (area == null) {
            diag(Diagnostic.Severity.ERROR, "label-no-area", "label " + sl.label + " before any AREA", site);
        }
        Symbol s = define(sl.label, Kind.LABEL, pcValue(), size, site);
        if (area != null) {
            s.areaName = area.name;
            s.approximate = !area.exact;
        }
    }

    // =====================================================================
    // Local labels
    // =====================================================================

    private void defineLocal(String text, Site site, Input in) {
        int k = 0;
        while (k < text.length() && Character.isDigit(text.charAt(k))) k++;
        int n;
        try { n = Integer.parseInt(text.substring(0, k)); } catch (NumberFormatException e) { n = -1; }
        String rn = text.substring(k);
        if (!rn.isEmpty() && (routine == null || !rn.equals(routine)))
            diag(Diagnostic.Severity.ERROR, "local-label-routine", "routine name " + rn + " doesn't match "
                    + (routine == null ? "(none)" : routine), site);
        LocalLabel l = new LocalLabel(n, scopeId, routine, in.level, localSeq++, pcValue(), site);
        if (pass == 1) pass1LocalLabels.add(l);
        else {
            localLabels.add(l);
            List<Site> fwd = forwardRefs.remove(l.seq);
            if (fwd != null) l.refs.addAll(fwd);
        }
    }

    /** Pass 2: forward references, by the sequence number of the definition they resolve to. */
    private final Map<Integer, List<Site>> forwardRefs = new HashMap<>();

    private LocalLabel findLocal(char dir, char level, int number, int curLevel) {
        List<LocalLabel> sofar = pass == 1 ? pass1LocalLabels : localLabels;
        if (dir != 'F') {
            for (int i = sofar.size() - 1; i >= 0; i--) {
                LocalLabel l = sofar.get(i);
                if (l.scope != scopeId) break;
                if (l.number == number && levelOk(level, l.macroLevel, curLevel)) return l;
            }
        }
        if (dir != 'B' && pass == 2) {
            // Forward: definitions from pass 1 at or after the current position.
            for (LocalLabel l : pass1LocalLabels) {
                if (l.seq < localSeq) continue;
                if (l.scope != scopeId) { if (l.scope > scopeId) break; else continue; }
                if (l.number == number && levelOk(level, l.macroLevel, curLevel)) return l;
            }
        }
        return null;
    }

    private static boolean levelOk(char q, int defLevel, int cur) {
        if (q == 'A') return true;
        if (q == 'T') return defLevel == cur;
        return defLevel <= cur;
    }

    private void newScope() { scopeId++; }

    /**
     * Records the fold of the routine that is ending: up to the line before
     * the next ROUT in the same file, or else to the end of its file (at the
     * end of assembly, or when the next ROUT is in another file).
     */
    private void closeRout(LogicalLine at, int line) {
        if (currentRoutStart > 0 && pass == 2) {
            int end = at != null && at.file.name.equals(currentRoutFile) ? line - 1
                    : currentRoutSource != null ? currentRoutSource.physicalLineCount : -1;
            if (end > currentRoutStart)
                folds.computeIfAbsent(currentRoutFile, f -> new LinkedHashSet<>()).add(List.of(currentRoutStart, end, "rout"));
        }
        currentRoutStart = -1;
    }

    // =====================================================================
    // Instructions
    // =====================================================================

    private static final Set<String> OPERAND_KEYWORDS = Set.of(
            "LSL", "LSR", "ASR", "ROR", "RRX", "lsl", "lsr", "asr", "ror", "rrx",
            "SY", "ST", "LD", "ISH", "ISHST", "ISHLD", "NSH", "NSHST", "NSHLD", "OSH", "OSHST", "OSHLD",
            "sy", "st", "ld", "ish", "ishst", "ishld", "nsh", "nshst", "nshld", "osh", "oshst", "oshld",
            "FPSCR", "FPEXC", "FPSID", "FPINST", "FPINST2", "MVFR0", "MVFR1", "MVFR2",
            "fpscr", "fpexc", "fpsid", "fpinst", "fpinst2", "mvfr0", "mvfr1", "mvfr2",
            "BE", "LE", "be", "le", "a", "i", "f", "ai", "af", "if", "aif", "A", "I", "F", "AI", "AF", "IF", "AIF",
            "APSR_nzcv", "APSR_nzcvq", "APSR_g", "APSR_nzcvqg");

    private void instruction(SplitLine sl, Input in, String op) {
        int size = 4;
        String uop = op.toUpperCase();
        if (thumb) {
            size = uop.endsWith(".W") || uop.startsWith("BL") && !uop.startsWith("BLX") ? 4 : 2;
            inexact("Thumb instruction sizes");
        }
        // ADRL is two instructions: ADRL{cond} (UAL) or ADR{cond}L (pre-UAL).
        if (uop.matches("ADRL([A-Z]{2})?|ADR([A-Z]{2})L")) size = 8;
        if (area != null) area.align(thumb ? 2 : 4);
        if (conditional()) inexact("instruction in an undecided region");
        defineLineLabel(sl, in, thumb ? 2 : 4);
        scanOperands(sl, in, uop);
        if (area != null) area.pc += size;
    }

    /** Records uses of symbols and local labels in instruction operands; collects literals. */
    private void scanOperands(SplitLine sl, Input in, String uop) {
        if (sl.argStart < 0) return;
        String s = sl.text.s;
        int i = sl.argStart, end = sl.argEnd;
        Ctx ctx = new Ctx(sl.text, in, false);
        while (i < end) {
            char c = s.charAt(i);
            if (c == '"') {
                int q = s.indexOf('"', i + 1);
                i = q < 0 || q >= end ? end : q + 1;
            } else if (c == '\'' && i + 2 < end && s.charAt(i + 2) == '\'') {
                i += 3;
            } else if (c == '=' && literalKind(uop, s.substring(sl.argStart, i)) != null) {
                // Load from the literal pool: LDR rd, =expr and its relatives
                int[] kind = literalKind(uop, s.substring(sl.argStart, i));
                ldrToPc = s.substring(sl.argStart, i).replace(" ", "").toLowerCase().matches("(pc|r15),");
                if (kind[0] == 4 && kind[2] == 0) {
                    Value v = eval(sl.text, i + 1, end, in, false);
                    literal(v, s.substring(i + 1, end).trim());
                } else {
                    // Floating-point or 64-bit constant: shared only with an identical one.
                    eval(sl.text, i + 1, end, in, false);
                    if (area != null && pass == 1) {
                        String key = "f" + kind[0] + ":" + s.substring(i + 1, end).trim();
                        if (!area.pendingLiterals.containsKey(key) && !area.reachable(key, kind[3]))
                            area.pendingLiterals.put(key, new int[] {kind[0], kind[1]});
                    }
                }
                i = end;
            } else if (c == '%' && i + 1 < end && "FfBbTtAa0123456789".indexOf(s.charAt(i + 1)) >= 0
                    && (i == sl.argStart || " ,#[(".indexOf(s.charAt(i - 1)) >= 0)) {
                Evaluator.Result r = Evaluator.evaluatePrefix(s, i, end, ctx);
                i = Math.max(r.stop, i + 1);
            } else if (c == '{') {
                int q = s.indexOf('}', i);
                if (q > i && q < end && s.substring(i + 1, q).matches("[A-Z_0-9]+")) i = q + 1;
                else i++;
            } else if (c == ':') {
                int q = s.indexOf(':', i + 1);
                if (q > i && q < end && s.substring(i + 1, q).matches("[A-Z_]+")) i = q + 1;
                else i++;
            } else if (c == '&' || c == '0' && i + 1 < end && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
                i += c == '&' ? 1 : 2;
                while (i < end && Character.digit(s.charAt(i), 16) >= 0) i++;
            } else if (Character.isDigit(c)) {
                while (i < end && (LineSplitter.symChar(s.charAt(i)))) i++;
            } else if (c == '|') {
                int q = s.indexOf('|', i + 1);
                if (q > i + 1 && q < end) {
                    ctx.symbol(s.substring(i + 1, q), i, q + 1);
                    i = q + 1;
                } else i++;
            } else if (LineSplitter.symStart(c)) {
                int st = i;
                while (i < end && LineSplitter.symChar(s.charAt(i))) i++;
                String name = s.substring(st, i);
                boolean qualifier = st > 0 && s.charAt(st - 1) == '.';
                if (!qualifier && !OPERAND_KEYWORDS.contains(name) && !isPsrName(name)) ctx.symbol(name, st, i);
            } else i++;
        }
    }

    private static boolean isPsrName(String n) {
        String u = n.toUpperCase();
        return u.startsWith("CPSR") || u.startsWith("SPSR") || u.startsWith("APSR");
    }

    /**
     * LDR rd, =expr. The pool layout is decided in pass 1 and reused in pass
     * 2: a value known in pass 1 that fits MOV or MVN needs no literal; a
     * defined literal is shared with a pending one or one already placed
     * within reach; a literal undefined in pass 1 always gets its own slot.
     */
    private void literal(Value v, String text) {
        if (area == null || pass == 2) return;
        String key;
        switch (v.kind) {
            case NUM:
                if (rotatable(v.num) || rotatable(~v.num)) return;   // becomes MOV or MVN
                if (targets.hasT2() && (v.num & 0xFFFFFFFFL) < 0x10000 && !ldrToPc) return;   // becomes MOVW
                key = "#" + v.num;
                break;
            case PROGREL: key = "@" + v.base + ":" + v.num; break;
            case REGREL: key = "r" + v.base + ":" + v.num; break;
            case RELOC: key = "x" + v.why; break;
            case UNKNOWN:
                inexact("literal of unknown value: " + text);
                key = "?" + text;
                break;
            default:
                area.pendingLiterals.put("undefined#" + (undefinedLiterals++), new int[] {4, 4});
                return;
        }
        if (area.pendingLiterals.containsKey(key) || area.reachable(key, 0x1000)) return;
        area.pendingLiterals.put(key, new int[] {4, 4});
    }

    private int undefinedLiterals;
    private boolean ldrToPc;
    private int poolIndex;
    private final List<Long> poolSizes = new ArrayList<>();

    /** Dumps the current literal pool: computed in pass 1, replayed in pass 2. */
    private void dumpPool(Area a) {
        if (pass == 1) {
            boolean any = !a.pendingLiterals.isEmpty();
            long size = a.dumpLiterals();
            poolSizes.add(any ? size : -1L);
        } else {
            long size = poolIndex < poolSizes.size() ? poolSizes.get(poolIndex) : 0;
            if (size > 0) {
                a.align(4);
                a.pc += size;
            }
        }
        poolIndex++;
    }

    /**
     * For an instruction that can load from the literal pool with "=", returns
     * {size, alignment, isFloatOr64 (0/1), reach}; null otherwise.
     */
    static int[] literalKind(String uop, String before) {
        String b = before.replace(" ", "").toUpperCase();
        String base = uop.contains(".") ? uop.substring(0, uop.indexOf('.')) : uop;
        if (base.startsWith("LDRD") || base.matches("LDR[A-Z]{2}D")) return new int[] {8, 8, 1, 0x100};
        if (base.startsWith("LDR")) return new int[] {4, 4, 0, 0x1000};
        if (base.startsWith("FLDD")) return new int[] {8, 4, 1, 0x400};
        if (base.startsWith("FLDS")) return new int[] {4, 4, 1, 0x400};
        if (base.startsWith("VLDR")) {
            boolean dbl = b.startsWith("D") || uop.contains(".F64") || uop.contains(".64");
            return dbl ? new int[] {8, 4, 1, 0x400} : new int[] {4, 4, 1, 0x400};
        }
        if (base.startsWith("LDF")) {
            // LDF{cond}{S|D|E|P}: precision is the last letter
            char p = base.charAt(base.length() - 1);
            int size = p == 'D' ? 8 : p == 'E' || p == 'P' ? 12 : 4;
            return new int[] {size, 4, 1, 0x400};
        }
        return null;
    }

    /** True if n is an 8-bit value rotated right by an even amount (an A32 modified immediate). */
    static boolean rotatable(int n) {
        for (int r = 0; r < 32; r += 2) if ((Integer.rotateLeft(n, r) & ~0xFF) == 0) return true;
        return false;
    }

    // =====================================================================
    // Macros
    // =====================================================================

    private MacroDef findMacro(String op) {
        if (op.length() > 2 && op.startsWith("|") && op.endsWith("|")) {
            MacroDef m = macros.get(op.substring(1, op.length() - 1));
            return m != null && (pass == 1 || m.definedInPass2) ? m : null;
        }
        for (int i = 0; i < op.length(); i++) if (!LineSplitter.symChar(op.charAt(i))) return null;
        for (int k = op.length(); k >= 1; k--) {
            MacroDef m = macros.get(op.substring(0, k));
            if (m != null && (pass == 1 || m.definedInPass2)) return m;
        }
        return null;
    }

    private void callMacro(MacroDef m, SplitLine sl, LogicalLine ll, Input in, String op) {
        Site callSite = siteOf(sl.text, sl.opStart, sl.opEnd, in);
        if (inputs.size() > NEST_CAP) {
            diag(Diagnostic.Severity.ERROR, "nesting", "macro nesting too deep", callSite);
            return;
        }
        if (pass == 2) m.uses.add(callSite);
        Map<String, String> args = new HashMap<>();
        String label = sl.hasLabel() ? sl.labelText() : "";
        String suffix = op.startsWith("|") ? "" : op.substring(m.name.length());
        if (m.labelParam() != null) args.put(m.labelParam(), label);
        else if (!label.isEmpty() && pass == 1)
            diag(Diagnostic.Severity.WARNING, "macro-ignores-label", "macro " + m.name + " has no label parameter; label ignored", callSite);
        if (m.suffixParam() != null) args.put(m.suffixParam(), suffix);
        else if (!suffix.isEmpty() && pass == 1)
            diag(Diagnostic.Severity.WARNING, "macro-ignores-suffix", "macro " + m.name + " has no suffix parameter", callSite);
        MacroSyntax.Args a = MacroSyntax.arguments(sl.text.s, sl.opEnd);
        if (a.error != null) diag(Diagnostic.Severity.ERROR, "macro-args", a.error, callSite);
        int nParams = m.paramNames.size() - 2;
        if (a.values.size() > nParams) {
            diag(Diagnostic.Severity.ERROR, "macro-args", "too many arguments for " + m.name, callSite);
            return;
        }
        for (int i = 0; i < nParams; i++) {
            String v = i < a.values.size() ? a.values.get(i) : "";
            if (v == null) v = m.defaults.get(i + 2);
            args.put(m.paramNames.get(i + 2), v);
        }
        // The call's position for anything defined inside, chained through outer calls.
        Site via = new Site(callSite.file, callSite.line, callSite.col, callSite.len, in.callSite);
        Input mi = new Input(m, args, via, in.level + 1);
        if (capture != null && pass == 2 && !capture.started && in.callSite == null
                && callSite.file.equals(capture.file) && callSite.line == capture.line) {
            mi.capturing = true;
            capture.started = true;
        }
        Frame f = new Frame(Frame.Type.MACRO, state, via);
        mi.frame = f;
        frames.push(f);
        inputs.push(mi);
    }

    private void exitMacro(Input mi, boolean viaMexit) {
        while (!frames.isEmpty()) {
            Frame f = frames.pop();
            if (f == mi.frame) { state = f.outer; break; }
            if (!viaMexit && (f.type == Frame.Type.IF || f.type == Frame.Type.WHILE))
                diag(Diagnostic.Severity.ERROR, "unclosed", f.type + " not closed in macro " + mi.macro.name, f.site);
            state = f.outer;
        }
        inputs.remove(mi);
        if (mi.capturing) {
            capture.done = true;
            passEnded = true;     // nothing more is wanted from this run
        }
    }

    /** Reads a MACRO definition from the current input: prototype line, then body to MEND. */
    private void defineMacro(LogicalLine macroLine, Input in, Site macroSite) {
        boolean skipping = state == State.SKIP;
        if (in.next >= in.size()) {
            diag(Diagnostic.Severity.ERROR, "macro-eof", "MACRO at end of file", macroSite);
            return;
        }
        LogicalLine proto = in.line(in.next++);
        Text pt = proto.toText();
        if (in.macro != null) pt = Substituter.macroParams(pt, in.args);
        List<LogicalLine> body = new ArrayList<>();
        LogicalLine mend = null;
        while (in.next < in.size()) {
            LogicalLine l = in.line(in.next++);
            if (isMendOrMacro(l.text, "MEND")) { mend = l; break; }
            if (isMendOrMacro(l.text, "MACRO"))
                diag(Diagnostic.Severity.ERROR, "macro-nested", "MACRO inside a macro definition", site(l, 0, 0, in));
            body.add(l);
        }
        State rec = skipping ? State.SKIP : state;
        record(proto, rec);
        if (mend != null) record(mend, rec);
        if (skipping) for (LogicalLine l : body) record(l, State.SKIP);
        if (mend == null) {
            diag(Diagnostic.Severity.ERROR, "macro-eof", "MACRO without MEND", macroSite);
            return;
        }
        fold(macroSite, mend, "macro");
        if (skipping) return;
        if (in.macro != null) {
            diag(Diagnostic.Severity.ERROR, "macro-in-macro", "MACRO not allowed in a macro expansion", macroSite);
            return;
        }
        MacroSyntax.Prototype p = MacroSyntax.prototype(pt.s);
        if (p.error != null) {
            diag(Diagnostic.Severity.ERROR, "macro-proto", p.error, site(proto, 0, 0, in));
            return;
        }
        Site defSite = siteOf(pt, p.nameStart, p.nameEnd, in);
        MacroDef existing = macros.get(p.name);
        if (existing != null) {
            if (pass == 2 && !existing.definedInPass2) {
                existing.definedInPass2 = true;
                return;
            }
            if (conditional() || existing.conditional) {
                existing.conditional = true;
                return;
            }
            diag(Diagnostic.Severity.ERROR, "macro-redefined", "macro " + p.name + " already defined", defSite);
            return;
        }
        MacroDef m = new MacroDef(p.name, defSite);
        m.paramNames.add(p.label);
        m.paramNames.add(p.suffix);
        m.defaults.add("");
        m.defaults.add("");
        m.paramNames.addAll(p.params);
        m.defaults.addAll(p.defaults);
        m.body.addAll(body);
        m.definedInPass2 = pass == 2;
        m.conditional = conditional();
        if (Mnemonics.isInstruction(p.name) && pass == 1)
            diag(Diagnostic.Severity.INFO, "macro-shadowed", "macro " + p.name + " has an instruction's name and can't be called", defSite);
        macros.put(p.name, m);
    }

    /** A line that is whitespace, then the directive, then only a comment (the only form in which MEND ends a macro). */
    private static boolean isMendOrMacro(String s, String dir) {
        if (s.isEmpty() || (s.charAt(0) != ' ' && s.charAt(0) != '\t')) return false;
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        if (!s.startsWith(dir, i)) return false;
        int j = i + dir.length();
        while (j < s.length() && s.charAt(j) == ' ') j++;
        return j >= s.length() || s.charAt(j) == ';';
    }

    // =====================================================================
    // Variables
    // =====================================================================

    private void declareVariable(String name, Kind k, boolean local, Site site, Input in, boolean predefine) {
        if (local) {
            if (in.locals == null) {
                diag(Diagnostic.Severity.ERROR, "local-outside-macro", "LCL outside a macro", site);
                return;
            }
            // Re-declaring a local resets it; Hdr:Proc relies on this inside WHILE loops.
            Symbol s = new Symbol(name);
            s.kind = k;
            s.status = Status.DEFINED;
            s.value = initial(k);
            s.declaredInPass2 = pass == 2;
            s.definedInPass = pass;
            if (pass == 2 && site != null) s.defs.add(site);
            in.locals.put(name, s);
            return;
        }
        Symbol s = symbols.getOrCreate(name);
        if (s.kind != Kind.UNDEFINED && s.kind != k) {
            if (s.kind.isVariable())
                diag(Diagnostic.Severity.ERROR, "variable-type", name + " already declared as " + s.kind.description, site);
            else if (s.status != Status.UNDEFINED)
                diag(Diagnostic.Severity.ERROR, "redefined-kind", name + " already defined as " + s.kind.description, site);
            if (s.status != Status.UNDEFINED || s.kind.isVariable()) return;
        }
        Value init = initial(k);
        if (conditional() && s.kind == k && s.value != null && !init.sameAs(s.value)) init = Value.unknown("reset in an undecided branch");
        s.kind = k;
        s.status = Status.DEFINED;
        s.value = init;
        s.definedInPass = pass;
        if (pass == 2) s.declaredInPass2 = true;
        if (conditional()) s.conditional = true;
        if (!predefine) addDef(s, site);
        else if (pass == 2 && site != null && s.defs.isEmpty()) s.firstDef = site;
    }

    private static Value initial(Kind k) {
        return k == Kind.VAR_A ? Value.num(0) : k == Kind.VAR_L ? Value.FALSE : Value.str("");
    }

    private void setVariable(SplitLine sl, Directive d, Input in) {
        Kind want = d == Directive.SETA ? Kind.VAR_A : d == Directive.SETL ? Kind.VAR_L : Kind.VAR_S;
        Site site = siteOf(sl.text, sl.labelStart, sl.labelEnd, in);
        if (sl.labelKind != SplitLine.LabelKind.SYMBOL) {
            diag(Diagnostic.Severity.ERROR, "set-no-name", d + " needs a variable name", site);
            return;
        }
        Symbol s = lookupVariableSymbol(sl.label, in);
        boolean local = s != null && in.locals != null && in.locals.get(sl.label) == s;
        if (s == null || s.kind != want || (pass == 2 && !local && !s.declaredInPass2)) {
            diag(Diagnostic.Severity.ERROR, "set-undeclared", sl.label + " is not a declared "
                    + want.description, site);
            return;
        }
        Value v = eval(sl.text, sl.argStart, sl.argEnd, in, false);
        if (v.isUndefined()) {
            diag(Diagnostic.Severity.ERROR, "set-undefined", d + " expression uses an undefined symbol: " + v.why, site);
            v = Value.unknown("assigned from an undefined expression");
        } else if (!v.isUnknown()) {
            boolean ok = want == Kind.VAR_A ? v.asNumber() != null || v.kind == Value.Kind.PROGREL || v.kind == Value.Kind.REGREL
                    : want == Kind.VAR_L ? v.isBool() : v.isStr();
            if (!ok) {
                diag(Diagnostic.Severity.ERROR, "set-type", d + " needs a " + (want == Kind.VAR_A ? "number" : want == Kind.VAR_L ? "logical" : "string")
                        + ", got " + v.display(), site);
                v = Value.unknown("assigned a value of the wrong type");
            } else if (want == Kind.VAR_A && v.asNumber() != null) {
                v = Value.num(v.asNumber());
            }
        }
        if (conditional() && !v.sameAs(s.value)) v = Value.unknown("assigned in an undecided branch");
        s.value = v;
        if (pass == 2) {
            s.uses.add(site);
            s.sets.add(site);
        }
    }

    // =====================================================================
    // Directives
    // =====================================================================

    private void directive(Directive d, SplitLine sl, LogicalLine ll, Input in, boolean lineUnknown) {
        Site site = siteOf(sl.text, sl.opStart, sl.opEnd, in);
        switch (d) {
            case IF: {
                State outer = state;
                record(ll, outer);
                Frame f = new Frame(Frame.Type.IF, outer, site(ll, 0, 0, null));
                f.clause = f.site;
                Tri c = outer == State.SKIP ? Tri.FALSE : condition(sl, in, site, lineUnknown);
                state = f.clause(c);
                frames.push(f);
                return;
            }
            case ELIF: case ELSE: {
                Frame f = frames.peek();
                if (f == null || f.type != Frame.Type.IF) {
                    record(ll, state);
                    diag(Diagnostic.Severity.ERROR, "structure", d + " without IF", site);
                    return;
                }
                record(ll, f.outer);
                // Each clause folds on its own, up to the line before the next.
                foldBefore(f.clause, ll, "if");
                f.clause = site(ll, 0, 0, null);
                if (f.sawElse)
                    diag(Diagnostic.Severity.WARNING, "else-after-else", d + " after ELSE: objasm accepts this silently, "
                            + "and skips everything up to the ENDIF", site);
                Tri c;
                if (d == Directive.ELSE) {
                    f.sawElse = true;
                    c = Tri.TRUE;
                } else if (f.outer == State.SKIP || f.taken == Tri.TRUE) {
                    c = Tri.FALSE;     // not evaluated
                } else {
                    // Evaluate in the enclosing state, so uses are recorded and approximations judged there.
                    State save = state;
                    state = f.outer;
                    c = condition(sl, in, site, lineUnknown);
                    state = save;
                }
                state = f.clause(c);
                return;
            }
            case ENDIF: {
                Frame f = frames.peek();
                if (f == null || f.type != Frame.Type.IF) {
                    record(ll, state);
                    diag(Diagnostic.Severity.ERROR, "structure", "ENDIF without IF", site);
                    return;
                }
                frames.pop();
                state = f.outer;
                record(ll, state);
                foldBefore(f.clause, ll, "if");
                return;
            }
            case WHILE: {
                State outer = state;
                record(ll, outer);
                Frame f = new Frame(Frame.Type.WHILE, outer, site(ll, 0, 0, null));
                f.input = in;
                f.whileLine = in.next - 1;
                String key = System.identityHashCode(in) + ":" + f.whileLine;
                if (outer == State.SKIP) {
                    state = State.SKIP;
                } else {
                    Tri c = condition(sl, in, site, lineUnknown);
                    int n = whileCounts.merge(key, 1, Integer::sum);
                    if (n > WHILE_CAP) {
                        diag(Diagnostic.Severity.WARNING, "while-cap", "WHILE loop stopped after " + WHILE_CAP + " iterations", site);
                        noteUnknown(site, "iteration cap reached");
                        c = Tri.UNKNOWN;
                    }
                    if (c == Tri.FALSE) {
                        state = State.SKIP;
                        whileCounts.remove(key);
                    } else if (c == Tri.UNKNOWN || outer == State.UNKNOWN) {
                        state = State.UNKNOWN;
                        f.once = true;
                        whileCounts.remove(key);
                    } else {
                        state = outer;
                    }
                }
                frames.push(f);
                return;
            }
            case WEND: {
                Frame f = frames.peek();
                if (f == null || f.type != Frame.Type.WHILE) {
                    record(ll, state);
                    diag(Diagnostic.Severity.ERROR, "structure", "WEND without WHILE", site);
                    return;
                }
                frames.pop();
                record(ll, f.outer);
                fold(f.site, ll, "while");
                if (state != State.SKIP && !f.once && f.input == in) {
                    in.next = f.whileLine;       // go round again: the WHILE line is processed afresh
                    state = f.outer;
                    return;
                }
                state = f.outer;
                return;
            }
            case MACRO: {
                record(ll, state);
                defineMacro(ll, in, site(ll, 0, 0, null));
                return;
            }
            case MEND: case MEXIT: {
                record(ll, state);
                if (in.macro == null) {
                    if (d == Directive.MEND) diag(Diagnostic.Severity.ERROR, "structure", "MEND outside a macro", site);
                    else diag(Diagnostic.Severity.ERROR, "structure", "MEXIT outside a macro", site);
                    return;
                }
                exitMacro(in, d == Directive.MEXIT);
                return;
            }
            case END: {
                record(ll, state);
                if (in.macro != null) {
                    diag(Diagnostic.Severity.ERROR, "structure", "END inside a macro", site);
                    return;
                }
                if (inputs.size() == 1) {
                    closeFile(in);
                    passEnded = true;
                } else {
                    closeFile(in);
                }
                return;
            }
            default:
        }
        // Everything below is only reached when not skipping.
        record(ll, lineUnknown ? State.UNKNOWN : state);
        switch (d) {
            case GET: case INCLUDE: get(sl, ll, in, site); return;
            case LNK: {
                if (pass == 2) metric("lnk");
                if (area != null) dumpPool(area);    // objasm dumps the pool at the end of each linked file
                // ...and starts a fresh literal list, so nothing is shared across the link.
                for (Area ar : areas.values()) ar.placed.clear();
                Input target = in;
                get(sl, ll, in, site);
                if (inputs.peek() != target) {
                    // Replace the current file with the linked one.
                    Input linked = inputs.pop();
                    Frame lf = frames.isEmpty() ? null : frames.pop();
                    closeFile(target);
                    inputs.push(linked);
                    if (lf != null) frames.push(lf);
                }
                return;
            }
            case GBLA: case GBLL: case GBLS: case LCLA: case LCLL: case LCLS: {
                Kind k = d == Directive.GBLA || d == Directive.LCLA ? Kind.VAR_A
                        : d == Directive.GBLL || d == Directive.LCLL ? Kind.VAR_L : Kind.VAR_S;
                boolean local = d.name().startsWith("LCL");
                if (sl.argStart < 0) { diag(Diagnostic.Severity.ERROR, "syntax", d + " needs a name", site); return; }
                int e = sl.argStart;
                String s = sl.text.s;
                int st = e;
                if (e < sl.argEnd && s.charAt(e) == '|') {
                    int q = s.indexOf('|', e + 1);
                    if (q > e) { declareVariable(s.substring(e + 1, q), k, local, siteOf(sl.text, e, q + 1, in), in, false); return; }
                }
                while (e < sl.argEnd && LineSplitter.symChar(s.charAt(e))) e++;
                if (e == st) { diag(Diagnostic.Severity.ERROR, "syntax", d + " needs a name", site); return; }
                declareVariable(s.substring(st, e), k, local, siteOf(sl.text, st, e, in), in, false);
                return;
            }
            case SETA: case SETL: case SETS: setVariable(sl, d, in); return;
            case EQU: equ(sl, in, site); return;
            case RN: case FN: case SN: case DN: case QN: case CN: case CP: registerName(d, sl, in, site); return;
            case RLIST: rlist(sl, in, site); return;
            case MAP: map(sl, in, site); return;
            case FIELD: field(sl, in, site); return;
            case SPACE: case FILL: {
                List<int[]> a = LineSplitter.splitArgs(sl.text.s, sl.argStart, sl.argEnd);
                Value n = sl.argStart < 0 ? Value.unknown("missing size") : eval(sl.text, a.get(0)[0], a.get(0)[1], in, false);
                for (int k = 1; k < a.size(); k++) eval(sl.text, a.get(k)[0], a.get(k)[1], in, false);
                defineLineLabel(sl, in, n.isNum() ? n.num : 0);
                advance(n, site);
                return;
            }
            case ALIGN: {
                List<int[]> a = LineSplitter.splitArgs(sl.text.s, sl.argStart, sl.argEnd);
                long n = 4, off = 0;
                if (sl.argStart >= 0) {
                    Value v = eval(sl.text, a.get(0)[0], a.get(0)[1], in, false);
                    if (v.isNum()) n = v.unsigned(); else inexact("ALIGN amount unknown");
                    if (a.size() > 1) {
                        Value o = eval(sl.text, a.get(1)[0], a.get(1)[1], in, false);
                        if (o.isNum()) off = o.unsigned(); else inexact("ALIGN offset unknown");
                    }
                }
                if (area != null) {
                    if (n == 0 || (n & (n - 1)) != 0) diag(Diagnostic.Severity.ERROR, "align", "ALIGN needs a power of two", site);
                    else area.alignWithOffset(n, off);
                    if (conditional()) inexact("ALIGN in an undecided region");
                }
                defineLineLabel(sl, in, 0);
                return;
            }
            case AREA: areaDirective(sl, ll, in, site); return;
            case ROUT: {
                if (area != null) area.align(4);
                newScope();
                closeRout(ll, ll.firstLine);
                currentRoutStart = ll.firstLine;
                currentRoutFile = ll.file.name;
                currentRoutSource = ll.file;
                if (sl.labelKind == SplitLine.LabelKind.SYMBOL) {
                    routine = sl.label;
                    defineLineLabel(sl, in, 0);
                    Symbol s = symbols.get(sl.label);
                    if (s != null) s.routine = true;
                } else {
                    routine = null;
                    if (sl.hasLabel()) defineLineLabel(sl, in, 0);
                }
                return;
            }
            case LTORG:
                if (area != null) dumpPool(area);
                defineLineLabel(sl, in, 0);
                return;
            case EXPORT: case GLOBAL: case EXPORTAS: case IMPORT: case EXTERN:
            case STRONG: case KEEP: case REQUIRE: case LEAF: case LEADR: case ALIAS:
                symbolList(d, sl, in, site);
                return;
            case ASSERT: {
                Value v = eval(sl.text, sl.argStart, sl.argEnd, in, true);
                if (v.isBool() && !v.bool && conditional()) { if (pass == 2) metric("assert-in-unknown-region"); }
                else if (v.isBool() && !v.bool) diag(Diagnostic.Severity.ERROR, "assert", "ASSERT fails (objasm would stop here)", site);
                else if (v.isVague()) { if (pass == 2) metric("assert-unknown"); }
                return;
            }
            case INFO: {
                List<int[]> a = LineSplitter.splitArgs(sl.text.s, sl.argStart, sl.argEnd);
                if (sl.argStart < 0) return;
                Value v = eval(sl.text, a.get(0)[0], a.get(0)[1], in, true);
                for (int k = 1; k < a.size(); k++) eval(sl.text, a.get(k)[0], a.get(k)[1], in, false);
                if (v.isNum() && v.num != 0 && conditional()) {
                    if (pass == 2) metric("info-error-in-unknown-region");
                } else if (v.isNum() && v.num != 0) {
                    String msg = a.size() > 1 ? sl.text.s.substring(a.get(1)[0], a.get(1)[1]) : "";
                    diag(Diagnostic.Severity.ERROR, "info-error", "INFO raises an error (objasm would stop here): " + msg, site);
                }
                return;
            }
            case OPT: case TTL: case SUBT:
                if (sl.argStart >= 0 && d == Directive.OPT) eval(sl.text, sl.argStart, sl.argEnd, in, false);
                return;
            case CODE16: case THUMB: case THUMBX:
                thumb = true;
                ual = d != Directive.CODE16;
                if (area != null) area.align(2);
                defineLineLabel(sl, in, 0);
                return;
            case CODE32: case ARM:
                thumb = false;
                ual = d == Directive.ARM;
                if (area != null) area.align(4);
                defineLineLabel(sl, in, 0);
                return;
            case ORG: {
                Value v = eval(sl.text, sl.argStart, sl.argEnd, in, false);
                if (area != null && v.isNum()) area.org = v.unsigned();
                return;
            }
            case BIN: case INCBIN: {
                String name = sl.args().trim();
                FileResolver.Result r = resolver.resolve(name, enclosingFiles());
                defineLineLabel(sl, in, 0);
                if (r.path == null) {
                    diag(Diagnostic.Severity.ERROR, "bin-unresolved", "binary file not found: " + name, site);
                    inexact("binary file not found");
                    return;
                }
                try {
                    if (area != null) area.pc += Files.size(r.path);
                } catch (IOException e) {
                    inexact("binary file unreadable");
                }
                return;
            }
            case DATA: case ENTRY: case NOFP: case AOF: case AOUT:
                defineLineLabel(sl, in, 0);
                return;
            default:
                if (d.isData()) { data(d, sl, in, site); return; }
                diag(Diagnostic.Severity.INFO, "unhandled-directive", "directive " + d + " not modelled", site);
        }
    }

    private void metric(String name) { metrics.merge(name, 1, Integer::sum); }

    /** Marks the location counter as approximate from here on, saying why once per area. */
    private void inexact(String why) {
        if (area == null) return;
        if (area.exact && pass == 2) {
            diag(Diagnostic.Severity.INFO, "approximate", "location in area " + area.name + " is approximate from here: " + why, currentSite);
        }
        area.exact = false;
    }

    private Site currentSite;
    /** Areas for which a label phase difference has been reported (only the first is useful). */
    private final Set<String> phaseReported = new java.util.HashSet<>();

    private void advance(Value n, Site site) {
        if (area == null) return;
        if (n.isNum()) area.pc += n.unsigned();
        else inexact("SPACE/FILL size unknown: " + n.why);
        if (conditional()) inexact("SPACE/FILL in an undecided region");
    }

    @SuppressWarnings("fallthrough")
    private void equ(SplitLine sl, Input in, Site site) {
        if (sl.labelKind != SplitLine.LabelKind.SYMBOL) {
            diag(Diagnostic.Severity.ERROR, "equ-no-label", "EQU needs a label", site);
            return;
        }
        if (sl.argStart < 0) {
            diag(Diagnostic.Severity.ERROR, "equ-missing", "EQU needs an expression", site);
            return;
        }
        List<int[]> a = LineSplitter.splitArgs(sl.text.s, sl.argStart, sl.argEnd);
        Value v = eval(sl.text, a.get(0)[0], a.get(0)[1], in, false);
        boolean approx = lastApproximate;
        Kind k;
        switch (v.kind) {
            case NUM: case UNKNOWN: case UNDEFINED: k = Kind.CONSTANT; break;
            case PROGREL: case RELOC: k = Kind.ADDRESS; break;
            case REGREL: k = Kind.REG_RELATIVE; break;
            case STR:
                if (v.asNumber() != null) { v = Value.num(v.asNumber()); k = Kind.CONSTANT; break; }
                // fall through
            default:
                diag(Diagnostic.Severity.ERROR, "equ-type", "EQU needs a numeric or address value, got " + v.display(), site);
                v = Value.unknown("bad EQU value");
                k = Kind.CONSTANT;
        }
        Symbol s = define(sl.label, k, v, 0, siteOf(sl.text, sl.labelStart, sl.labelEnd, in));
        s.approximate = approx;
    }

    private void registerName(Directive d, SplitLine sl, Input in, Site site) {
        if (sl.labelKind != SplitLine.LabelKind.SYMBOL) {
            diag(Diagnostic.Severity.ERROR, "rn-no-label", d + " needs a label", site);
            return;
        }
        Value v = eval(sl.text, sl.argStart, sl.argEnd, in, false);
        Kind k;
        switch (d) {
            case RN: k = Kind.REGISTER; break;
            case FN: k = Kind.FP_REGISTER; break;
            case SN: case DN: case QN: k = Kind.EXT_REGISTER; break;
            case CN: k = Kind.COPROC_REGISTER; break;
            default: k = Kind.COPROCESSOR;
        }
        if (v.isNum()) v = Value.num(v.num);
        define(sl.label, k, v, 0, siteOf(sl.text, sl.labelStart, sl.labelEnd, in));
    }

    private void rlist(SplitLine sl, Input in, Site site) {
        if (sl.labelKind != SplitLine.LabelKind.SYMBOL) {
            diag(Diagnostic.Severity.ERROR, "rn-no-label", "RLIST needs a label", site);
            return;
        }
        String s = sl.text.s;
        int a = s.indexOf('{', Math.max(0, sl.argStart)), b = s.lastIndexOf('}', sl.argEnd);
        Value v;
        if (sl.argStart < 0 || a < 0 || b < a) {
            v = Value.unknown("bad register list");
        } else {
            int mask = 0;
            boolean ok = true;
            for (int[] r : LineSplitter.splitArgs(s, a + 1, b)) {
                if (r[0] >= r[1]) continue;
                int dash = s.indexOf('-', r[0]);
                if (dash >= 0 && dash < r[1]) {
                    Value lo = eval(sl.text, r[0], dash, in, false), hi = eval(sl.text, dash + 1, r[1], in, false);
                    if (lo.isNum() && hi.isNum()) for (int k = lo.num; k <= hi.num && k < 16; k++) mask |= 1 << k;
                    else ok = false;
                } else {
                    Value x = eval(sl.text, r[0], r[1], in, false);
                    if (x.isNum() && x.num >= 0 && x.num < 16) mask |= 1 << x.num; else ok = false;
                }
            }
            v = ok ? Value.num(mask) : Value.unknown("register list");
        }
        define(sl.label, Kind.REGISTER_LIST, v, 0, siteOf(sl.text, sl.labelStart, sl.labelEnd, in));
    }

    /** ^ / MAP: set the storage counter. */
    private void map(SplitLine sl, Input in, Site site) {
        List<int[]> a = LineSplitter.splitArgs(sl.text.s, sl.argStart, sl.argEnd);
        if (sl.argStart < 0) { diag(Diagnostic.Severity.ERROR, "syntax", "MAP needs an expression", site); return; }
        Value v = eval(sl.text, a.get(0)[0], a.get(0)[1], in, false);
        if (a.size() > 1) {
            Value r = eval(sl.text, a.get(1)[0], a.get(1)[1], in, false);
            if (r.isNum() && v.isNum() && r.num >= 0 && r.num < 16) v = Value.regRel(1 << r.num, v.num);
            else if (!r.isVague() && !v.isVague()) {
                diag(Diagnostic.Severity.ERROR, "map", "bad MAP base register", site);
                v = Value.unknown("bad MAP");
            } else v = Value.unknown("MAP base unknown");
        }
        if (v.isUndefined()) v = Value.unknown("MAP uses an undefined symbol");
        storage = v;
    }

    /** # / FIELD: define a field at the storage counter, then advance it. */
    private void field(SplitLine sl, Input in, Site site) {
        Value n = sl.argStart < 0 ? Value.num(0) : eval(sl.text, sl.argStart, sl.argEnd, in, false);
        if (sl.labelKind == SplitLine.LabelKind.SYMBOL) {
            Value v = storage;
            Kind k = Kind.FIELD;
            Symbol s = define(sl.label, k, v, n.isNum() ? n.num : 0, siteOf(sl.text, sl.labelStart, sl.labelEnd, in));
            if (v.isUnknown()) s.approximate = false;
        } else if (sl.hasLabel()) {
            diag(Diagnostic.Severity.ERROR, "bad-label", "invalid label for FIELD", site);
        }
        Integer k = n.asNumber();
        if (k == null) {
            storage = n.isVague() ? (storage.isUnknown() ? storage : Value.unknown("FIELD size unknown")) : Value.unknown("FIELD size not a number");
            return;
        }
        switch (storage.kind) {
            case NUM: storage = Value.num(storage.num + k); break;
            case REGREL: storage = Value.regRel(storage.base, storage.num + k); break;
            case PROGREL: storage = Value.progRel(storage.base, storage.num + k); break;
            default:
        }
        if (conditional() && !storage.isUnknown()) storage = Value.unknown("FIELD in an undecided branch");
    }

    private void data(Directive d, SplitLine sl, Input in, Site site) {
        int unit, align;
        switch (d) {
            case DCB: unit = 1; align = 1; break;
            case DCW: unit = 2; align = 2; break;
            case DCWU: unit = 2; align = 1; break;
            case DCD: case DCDO: case DCFS: unit = 4; align = 4; break;
            case DCDU: case DCFSU: unit = 4; align = 1; break;
            case DCQ: case DCFD: unit = 8; align = 4; break;
            case DCQU: case DCFDU: unit = 8; align = 1; break;
            case DCFH: case DCFB: unit = 2; align = 2; break;
            case DCFHU: case DCFBU: unit = 2; align = 1; break;
            case DCI_N: unit = 2; align = 2; break;
            case DCI_W: unit = 4; align = 2; break;
            default: unit = thumb ? 2 : 4; align = unit;   // DCI
        }
        if (area != null) area.align(align);
        long total = 0;
        boolean known = true;
        boolean fp = d.name().startsWith("DCF");
        for (int[] r : LineSplitter.splitArgs(sl.text.s, sl.argStart, sl.argEnd)) {
            if (r[0] >= r[1]) continue;
            if (fp) { total += unit; continue; }
            Value v = eval(sl.text, r[0], r[1], in, false);
            if (d == Directive.DCB && v.isStr()) total += v.str.length();
            else if (d == Directive.DCB && v.isUnknown() && sl.text.s.charAt(r[0]) == '"') { known = false; total += 1; }
            else total += unit;
        }
        defineLineLabel(sl, in, (int) total);
        if (area != null) {
            area.pc += total;
            if (!known) inexact("data of unknown size");
            if (conditional()) inexact("data in an undecided region");
        }
    }

    private void areaDirective(SplitLine sl, LogicalLine ll, Input in, Site site) {
        List<int[]> a = LineSplitter.splitArgs(sl.text.s, sl.argStart, sl.argEnd);
        if (sl.argStart < 0) { diag(Diagnostic.Severity.ERROR, "syntax", "AREA needs a name", site); return; }
        String name = sl.text.s.substring(a.get(0)[0], a.get(0)[1]);
        if (name.startsWith("|") && name.endsWith("|") && name.length() > 1) name = name.substring(1, name.length() - 1);
        if (area != null) dumpPool(area);
        Area ar = areas.get(name);
        if (ar == null) {
            ar = new Area(nextAreaId++, name);
            areas.put(name, ar);
        }
        for (int k = 1; k < a.size(); k++) {
            String attr = sl.text.s.substring(a.get(k)[0], a.get(k)[1]).trim().toUpperCase();
            if (attr.equals("ABS")) ar.absolute = true;
            if (attr.equals("CODE")) ar.code = true;
            if (attr.startsWith("ALIGN")) {
                int eq = attr.indexOf('=');
                if (eq > 0) {
                    Value v = eval(sl.text, a.get(k)[0] + sl.text.s.substring(a.get(k)[0], a.get(k)[1]).indexOf('=') + 1, a.get(k)[1], in, false);
                    if (!v.isNum()) ar.exact = false;   // area alignment unknown
                }
            }
        }
        area = ar;
        newScope();
        routine = null;
        // The area name is a symbol too, labelling the start of the area.
        Site ns = siteOf(sl.text, a.get(0)[0], a.get(0)[1], in);
        Symbol s = define(name, Kind.LABEL, pcValue(), 0, ns);
        s.areaName = name;
    }

    /** EXPORT, IMPORT and friends: a symbol, optionally with [attributes] and further operands. */
    private void symbolList(Directive d, SplitLine sl, Input in, Site site) {
        if (sl.argStart < 0) return;
        String s = sl.text.s;
        List<int[]> parts = LineSplitter.splitArgs(s, sl.argStart, sl.argEnd);
        boolean first = true;
        for (int[] p : parts) {
            int i = p[0];
            String name = null;
            int st = i, en = i;
            if (i < p[1] && s.charAt(i) == '|') {
                int q = s.indexOf('|', i + 1);
                if (q > i && q < p[1]) { name = s.substring(i + 1, q); en = q + 1; }
            } else {
                while (en < p[1] && LineSplitter.symChar(s.charAt(en))) en++;
                if (en > st && LineSplitter.symStart(s.charAt(st))) name = s.substring(st, en);
            }
            if (name == null) continue;
            if (!first && (d == Directive.EXPORTAS || d == Directive.ALIAS)) break;   // second operand is an external name
            first = false;
            Site ns = siteOf(sl.text, st, en, in);
            Symbol sym = symbols.getOrCreate(name);
            switch (d) {
                case IMPORT: case EXTERN: {
                    if (sym.status == Status.DEFINED && sym.kind != Kind.EXTERNAL && sym.definedInPass == pass) {
                        diag(Diagnostic.Severity.ERROR, "redefined", name + " already defined", ns);
                        break;
                    }
                    if (sym.kind == Kind.EXTERNAL && sym.definedInPass == pass) { addDef(sym, ns); break; }
                    define(name, Kind.EXTERNAL, Value.reloc(name), 0, ns);
                    break;
                }
                case EXPORT: case GLOBAL: case EXPORTAS:
                    sym.exported = true;
                    if (pass == 2) sym.uses.add(ns);
                    break;
                default:
                    if (pass == 2) sym.uses.add(ns);
            }
        }
    }

    private List<Path> enclosingFiles() {
        List<Path> l = new ArrayList<>();
        for (Input i : inputs) if (i.file != null && !i.file.name.startsWith(PREDEFINE)) l.add(i.file.path);
        return l;
    }

    private void get(SplitLine sl, LogicalLine ll, Input in, Site site) {
        String name = sl.argStart < 0 ? "" : sl.text.s.substring(sl.argStart, sl.argEnd);
        int sp = name.indexOf(' ');
        if (sp >= 0) name = name.substring(0, sp);
        if (name.isEmpty()) { diag(Diagnostic.Severity.ERROR, "get-syntax", "GET needs a file name", site); return; }
        if (inputs.size() > NEST_CAP) { diag(Diagnostic.Severity.ERROR, "nesting", "GET nesting too deep", site); return; }
        FileResolver.Result r = resolver.resolve(name, enclosingFiles());
        Site gs = siteOf(sl.text, sl.argStart, sl.argStart + name.length(), in);
        if (r.path == null) {
            diag(Diagnostic.Severity.ERROR, "get-unresolved", "GET " + name + (r.expanded.equals(name) ? "" : " (" + r.expanded + ")")
                    + ": file not found", gs);
            return;
        }
        if (r.caseFolded && pass == 1)
            diag(Diagnostic.Severity.WARNING, "get-case", "GET " + name + " found only by ignoring case: " + r.path, gs);
        SourceFile f = load(r.path);
        if (f == null) {
            diag(Diagnostic.Severity.ERROR, "get-unreadable", "can't read " + r.path, gs);
            return;
        }
        if (pass == 2) includes.add(new Object[] {gs, f.name});
        filesRead.add(f.name);
        Input gi = new Input(f);
        Frame fr = new Frame(Frame.Type.GET, state, gs);
        gi.frame = fr;
        frames.push(fr);
        inputs.push(gi);
    }

    /**
     * The cache also carries unsaved editor text: the language server puts a
     * {@link SourceFile} built from an open document's text under that file's
     * key, so every entry point that reads the file sees the editor's version.
     */
    private SourceFile load(Path p) {
        Path key = p.toAbsolutePath().normalize();
        // Not toRealPath(): the objs/ link farm's names are what objasm searches
        // relative to, and what the editor opens.
        SourceFile f = cache.get(key);
        if (f != null) return f;
        try {
            f = SourceFile.read(key);
        } catch (IOException e) {
            return null;
        }
        cache.put(key, f);
        return f;
    }

    // =====================================================================
    // After both passes
    // =====================================================================

    private void finish() {
        // Attach references to local labels collected in pass 2.
        metrics.put("lines-processed", linesProcessed);
        metrics.put("files", filesRead.size());
        metrics.put("unknown-conditions", unknownConditions.size());
        int shaded = 0, unknownLines = 0, macroSkipped = 0;
        for (int[][] c : lineCounts.values()) {
            for (int[] x : c) {
                if (classify(x) == 1) shaded++;
                else if (classify(x) == 2) unknownLines++;
                else if (classify(x) == 3) macroSkipped++;
            }
        }
        metrics.put("shaded-lines", shaded);
        metrics.put("unknown-lines", unknownLines);
        metrics.put("macro-skipped-lines", macroSkipped);
    }

    /**
     * 0: assembled or never processed; 1: shade (skipped every time, outside
     * macro expansions); 2: undecided; 3: skipped in every macro expansion.
     */
    public static int classify(int[] x) {
        if (x[0] > 0) return 0;
        if (x[2] > 0) return 2;
        if (x[3] > 0) return 3;
        return x[1] > 0 ? 1 : 0;
    }

    /** Ranges of lines in a file with the given classification. */
    public static List<int[]> ranges(int[][] counts, int kind) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        for (int line = 1; line <= counts.length; line++) {
            boolean in = line < counts.length && classify(counts[line]) == kind;
            if (in && start < 0) start = line;
            if (!in && start >= 0) { out.add(new int[] {start, line - 1}); start = -1; }
        }
        return out;
    }
}
