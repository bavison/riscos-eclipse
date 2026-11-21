# objasm indexer engine and language server

An engine that reads RISC OS assembler source as objasm does, for indexing
it in an editor, with a language server on top of it. For each assembler
entry point it finds every symbol definition and use, evaluates constants,
and decides which conditional regions are assembled.

## Building and running

Plain Java 17 or later, no dependencies:

```sh
./build.sh                  # javac → build/objasm-index.jar
./test.sh                   # the engine's tests, then the BASIC and language server tests
java -jar build/objasm-index.jar buildscan TREE/RiscOS ENV SCAN_OUT   # the settings for one Env file
```

The tool on its own:

```sh
java -jar build/objasm-index.jar [--file PATH] [--out DIR] [--uses] [--quiet] \
     [--trace FILE:FIRST-LAST] ENVIRONMENT.json OBJASM.json...
```

* The inputs are buildscan's `environment.json` and one or more
  `<Component>/objasm.json`.
* `--file` restricts the run to entry points whose path ends with `PATH`.
* `--out` writes one JSON file per entry point, plus `summary.json` and
  `shading.json` (shaded ranges aggregated over every entry point).
* `--uses` includes every use site; without it, only counts.
* `--trace` prints the location counter for each pass-2 line in a range, for
  debugging.

## Language server

```sh
java -Xmx1500m -jar build/objasm-index.jar lsp [--settings SCAN_OUT] [--log FILE]
```

It speaks LSP over stdio, with no dependencies (JSON-RPC is written out by
hand on top of `util.Json`), so any LSP client can use it: LSP4E in Eclipse
(see `../eclipse-lsp/`) or VS Code. With the environment variable
`OBJASM_INDEX_TRACE` set, it also writes the start of every message in and
out to stderr. The client names the configuration in
`initializationOptions`:

```json
{"settings": "/path/to/buildscan/output", "showInfoDiagnostics": false}
```

`settings` is buildscan's output directory for one Env file
(`environment.json` and `<Component>/objasm.json`), or a list of them, one
per build tree. Each tree's entry points find files with their own tree's
variables. A directory that can't be read is reported as a warning, and the
others are still indexed.

To change configuration without restarting, the client sends
`workspace/didChangeConfiguration` with
`{"riscos": {"settings": DIRS}}` (a directory or a list), adding
`"reload": true` if DIRS are the same but buildscan has written them again. The server indexes everything
again from those settings; open documents keep their unsaved text.

### What it offers

| Request | Behaviour |
|---|---|
| `definition` | Symbols, macros, local labels, and `GET` file names. A symbol made in a macro has two targets: the outermost call, narrowed to the argument that names it (`WriteC` in `AddSWI WriteC`), then the line in the macro body |
| Variables | `declaration` gives a variable's `GBLA`, `LCLS`… (it has no `definition`, as LSP has no kind of link for an assignment); `workspace/executeCommand` `riscos.assignments` [uri, line, character] gives every `SETA`, `SETL` or `SETS` that assigns it, in every entry point of the configuration |
| `references`, `documentHighlight` | Every use in every entry point of the configuration. A use inside a macro expansion is shown at the call, and in the body only if the body names the symbol literally |
| `hover` | Kind and value, e.g. `XOS_WriteC: constant = &20000 (131072)`, and where it was defined. Where entry points give the same definition different values, each value and how many entry points have it. On a `$name`: the line after substitution, each different way it is assembled (worked out on demand like a macro expansion). On a macro call: the signature, the lines the call outputs (instructions and data, with the macros it calls expanded too, after substitution), the call's expansion with its arguments substituted and the lines not assembled marked (worked out on demand by assembling one entry point again, stopping after the call), and every symbol that call defined |
| `completion` | Symbols with kind and value; directives and macros (with signatures) in the operation field; local labels of the current routine after `%`; variables, and the macro's parameters inside a macro body, after `$` |
| `documentSymbol`, `workspace/symbol` | Symbols and macros, each at its outermost definition site, labels the file `EXPORT`s described as exported; for `documentSymbol`, also the files the file `GET`s, `INCLUDE`s or `LNK`s |
| `foldingRange` | `IF`, `WHILE`, `MACRO` and routines |
| `publishDiagnostics` | The engine's errors and warnings, at the outermost macro call where there is one. Information diagnostics only with `showInfoDiagnostics` |
| `riscos/inactiveRegions` | Notification per open document: `{textDocument: {uri, version}, regions: Range[], skippedInEveryExpansion: Range[]}` |
| `riscos/status` | Notification: `{state: "indexing" \| "idle", entryPoints}` |

Progress is also reported through `window/workDoneProgress` when the client
supports it.

A file read by several entry points (a header, say) gets the union of their
results. Its lines are shaded only where every entry point that reached the
line skipped it. An entry point that never reached a line, such as one that
never expanded a macro, doesn't count. This is the same rule as
`shading.json`.

### Editing

Open documents are tracked incrementally, and their unsaved text is what the
engine reads: the server puts it in the shared source cache that
`Assembler.load()` reads from. After a change, and 300 ms without another one,
the server re-runs the entry points that read the file, quickest first, and
keeps serving the previous results until each finishes. A further change
during a re-run restarts it with what is still to do. Closing a document
without saving goes back to the file on disk.

### Assembler and C

Assembler and C are joined by name, as the linker joins them, within a
component and the libraries it links with (from buildscan's
`summary.json`). The server reads each component's C files (from
`compile_commands.json`) lexically, for their uses, their functions and
variables with external linkage, and their prototypes and `extern`
declarations, following `#include`s through the component's include paths.

| Request | For a symbol shared with C |
|---|---|
| `definition` | On an `IMPORT`ed symbol: the C function or variable, in the component or a library it links with, or the assembler label a library `EXPORT`s; the `IMPORT` itself only if there is none |
| `declaration` | The `IMPORT` (or `EXPORT`) and the C prototypes. Other symbols have no declaration but their definition, so nothing is returned for them |
| `references` | Also every use in the C of the component, and of the components that link with it |
| `workspace/executeCommand` | For C editors: `riscos.asmDefinitions` [name, C file] gives where the assembler of the file's component or its libraries defines and `EXPORT`s the name; `riscos.asmReferences` [name, C file] where assembler there, or in the components that link with it, uses or defines it |

### BBC BASIC

The same server handles BBC BASIC V files (opened with the language ID
`bbcbasic`, or named `*.bas`), each file on its own: BASIC programs rarely
refer to other files, except through libraries loaded at run time. The
parser (`basic/BasicFile`) reads statements separated by newlines and
colons, keywords as BASIC's tokeniser reads them (`PRINTx` is PRINT x;
`TIMEOUT%` is a variable), and skips strings, REM, DATA and `*` commands.

| Request | Behaviour |
|---|---|
| `definition`, `declaration` | A PROC or FN call goes to its DEF. A variable's declaration is its LOCAL, parameter or DIM; one that has none is defined instead by where it is assigned (`=`, FOR, READ, INPUT, SWAP, SYS … TO, an assembler label, or an array element given a value), so that Go to Definition has somewhere to go. `riscos.assignments` [uri, line, character] gives the assignments of any variable. A line number goes to its line |
| `references`, `documentHighlight` | Calls and the DEF; a variable's every use in its scope, assignments highlighted as writes |
| `hover` | A routine's DEF line, the REM lines above it, and how often it is called; a variable's type, scope, and where it is assigned |
| `documentSymbol`, `workspace/symbol` | Procedures and functions (of open files, for the workspace). `workspace/executeCommand` `riscos.basicSymbols` [uri] gives a file's whether or not it is open, reading it from disk if not |
| `completion` | Routine names after PROC or FN; inside the string after SYS, the names of the SWIs the build tree defines (by `AddSWI` in the assembler it indexes, and in the components' CMHG files), with their numbers; otherwise variables in scope |
| `foldingRange` | Each routine (to its ENDPROC or `=` at its outer level, not one after THEN), the clauses of a multi-line IF (THEN ending its line; ELSE and ENDIF starting theirs) and of CASE, WHILE, REPEAT, FOR over several lines, and `[ … ]` assembler |
| Diagnostics | Calls to routines the file doesn't define (information only if it loads libraries, or if another BASIC file in the same directory defines them, as in a program built by joining files), a routine defined twice, a GOTO to a missing line, and unmatched block structure; with information diagnostics on, variables read but never assigned |

`foo`, `foo%` and `foo$` are different variables, and so are the arrays
`foo(`, `foo%(` and `foo$(`. Parameters and LOCAL make a variable local to
its routine; everything else is global. Tokenised programs are read
detokenised.

## Output

One JSON object per entry point:

| Field | Contents |
|---|---|
| `metrics` | Lines processed, files read, unknown conditions, shaded lines, time |
| `areas` | Each area's final size, and whether it is exact |
| `diagnostics` | Severity, code, message, pass and site |
| `files`, `includes` | Every file read, and each `GET` edge with its site |
| `symbols` | Name, kind, value, size, flags (`exported`, `routine`, `conditional`, `approximate`, `partly-defined`), area, definition sites, use sites |
| `unresolved` | Names used but never defined |
| `macros` | Name, signature, definition site, call sites |
| `localLabels` | Number, routine, value, definition, references |
| `regions` | Per file: `shaded`, `unknown` and `skippedInEveryExpansion` line ranges |
| `folding` | Per file: `[start, end, kind]` for `if`, `while`, `macro` and `rout` |
| `unknownConditions` | Each undecidable condition and the reason |

A site is `{file, line, col, len, via}`. `via` is the macro call that
produced the text, itself possibly with a `via`. For example, `OS_WriteC`:

```json
{
 "name": "OS_WriteC",
 "kind": "field",
 "value": "&00000000",
 "size": 1,
 "defs": [{"file": "Export/APCS-32/Hdr/Global/Macros", "line": 366, "col": 0, "len": 12,
           "via": {"file": "Export/APCS-32/Hdr/Interface/RISCOS", "line": 33, "col": 8, "len": 6}}],
 "uses": [{"file": "Sources/Kernel/objs/Super1.s", "line": 33, "col": 16, "len": 9}, "…"]
}
```

Here the definition is the line `$SWIClass._$SWIName # 1` in the `AddSWI`
macro's body in `Hdr:Macros`, and its `via` is the call `AddSWI WriteC` in
`Hdr:Interface/RISCOS`. File names are
the paths as objasm saw them, so files in a component come through its
`objs/` link farm, which is what the editor opens.

## How it works

* **Two passes, as objasm does.** Pass 1 builds the symbol table; pass 2
  re-reads everything with it. Pass 2 decides the shading, and all
  definition and use sites come from pass 2. Variables are reset between
  passes; labels and macros persist.
* **Three-valued conditions.** An `IF` whose condition the engine can't
  evaluate (it uses something the engine doesn't model, such as `:FSIZE:`)
  assembles both branches in an *unknown* state: nothing in them is shaded,
  symbols defined there are flagged `conditional`, and variables assigned
  there become unknown. *Undefined*, which is objasm's own error for an
  undefined symbol, is kept apart from *unknown*.
* **Positions through substitution.** Continuation joining, macro parameter
  substitution and `$` substitution each keep a map back to the original
  columns. A symbol built as `$SWIClass._$SWIName` therefore points at that
  text, with the macro call as its `via`.
* **Shading.** Each source line counts how often pass 2 assembled it,
  skipped it, or met it in an unknown state. A line is shaded only if it was
  skipped every time. A line in a macro body skipped in every expansion isn't
  shaded by default, since a body is a template and a branch no call takes
  in this configuration isn't dead code; it is reported separately as
  `skippedInEveryExpansion`. A `WHILE` body is shaded only if every iteration
  skipped it.
* **Instructions before macros.** As in objasm, a directive is recognised
  first, then an instruction, then a macro (the longest prefix of the
  operation). The mnemonic recogniser is written from Arm's public
  instruction set documentation: A32 (in both UAL and pre-UAL forms), T32,
  VFP/Advanced SIMD, and FPA. A macro that shares an instruction's name can
  never be called, and is reported as information.
* **Location counter.** ARM instructions are 4 bytes and `ADRL` is 8. Data
  directives, `ALIGN`, `SPACE` and `BIN` are sized exactly. The literal pool
  layout is decided in pass 1 and replayed in pass 2. Thumb
  sizes are approximate, and anything after an approximation is flagged.

## Source layout

| Package | Contents |
|---|---|
| `source` | Files, logical lines and position maps; `GET` name resolution |
| `subst` | Macro-parameter and `$` variable substitution |
| `parse` | Line splitting, directive table, instruction mnemonic recogniser |
| `expr` | Values (including Undefined and Unknown) and the expression evaluator |
| `sym` | Symbols, the symbol table and sites |
| `macro` | Macro definitions; prototype and argument parsing |
| `cond` | Three-valued IF/WHILE state |
| `loc` | Areas, literal pools and local labels |
| `target` | `-CPU`/`-APCS` options, built-in variables and APCS register names |
| `run` | The two-pass assembler driver and diagnostics |
| `out`, `cli`, `util` | JSON output, the command-line tool, a minimal JSON reader and writer |
| `buildscan` | Derives the settings from a build tree's Env file (see `../buildscan/README.md`) |
| `lsp` | The language server: `Transport` (framing), `EntryIndex` (one entry point's results, kept for queries), `Model` (an immutable snapshot of all of them), `Indexer` (background re-runs), `Server` (requests) |

`run.Settings` reads buildscan's output for both the tool and the server.
`tools/lsp_client.py` is a small scripted LSP client, for trying the server
from Python.
