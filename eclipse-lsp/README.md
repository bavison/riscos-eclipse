# Eclipse client for the objasm indexer

An Eclipse bundle, `com.piccolosystems.riscos.lsp`, that imports a RISC OS
build tree as projects, connects the language server in `../asmindex` to ARM
assembler and BBC BASIC files through LSP4E, shades code the active
configuration doesn't assemble, and edits Messages files and tokenised BASIC
converted.

## What it contains

| File | Purpose |
|---|---|
| `plugin.xml` | The LSP4E server definition, mapped to the syntax bundle's `com.piccolosystems.riscos.armasm` content type. The import wizard, the RISC OS menu, a problem marker for makefiles that couldn't be scanned, two annotation types for shading, and the preference pages |
| `ImportWizard` | *File › Import… › RISC OS › Build Tree*: the tree, its configurations (Env files), its components and any external projects (see `../WIZARD-DESIGN.md`) |
| `TreeImport` | What the wizard and the menu do, in a background job: runs buildscan (from the bundled jar, in-process) for each configuration into the plugin's state area, creates the projects (Latin-1, with Eclipse's files added to `.git/info/exclude`), and points the server at the active configuration |
| `BuildTree` | The imported tree's details, kept in the workspace's preferences |
| `Commands` | The RISC OS menu: *Active Configuration ›*, *Rescan Build Tree*, *Add External Project…*, *Insert NUL* and *Import Build Tree…* |
| `ConfigurationCombo` | The toolbar's drop-down list of each tree's configurations, showing and switching the active one |
| `TreeParticipant` | The extension point through which the CDT bundle takes part in importing and switching configurations |
| `ObjasmServer` | Starts `java -Xmx<heap> -jar objasm-index.jar lsp`, and passes every build tree's active settings directory as `initializationOptions` |
| `RiscOSLanguageClient` | LSP4E's client, with handlers for `riscos/inactiveRegions` and `riscos/status` |
| `InactiveRegions` | Turns `riscos/inactiveRegions` into annotations in every open editor on that file |
| `RiscOSTextEditor`, `ConvertedFiles` | The editor, and files edited converted (below) |
| `RiscOSLinks` | Ctrl+hover: *Open Declaration*, *Open Assignment* (a variable's) and *Open Definition*, labelled alike |
| `FileSymbols` | In the Project Explorer, an assembler or BASIC file opens out into its symbols, sorted as CDT sorts a C file's |
| `LinkFarms`, `MirroredFilesFilter`, `LinkRedirect`, `LinkUris` | Each `objs/` link farm file is shown, searched and opened by one name only (the link's, by default): the other is hidden and marked derived, an editor opened by it is reopened by the one shown, and the server's links are turned into files without resolving the links |
| `SourceFileTypes` | Makes the extensionless files the assembler reads (exported headers, for instance) assembler files |
| `OutlineWatchdog` | Asks again for the symbols of an Outline left showing "Computing symbols…" by LSP4E |
| `Startup` | What is done once the workbench has started |
| `Preferences` | *Preferences › RISC OS*: whether to show information diagnostics, which names link farm files go by, and whether saving removes spaces at line ends. *› Advanced*: the settings directories, one per tree (set by the wizard and the RISC OS menu), the heap size, the Java command and the jar, the last three blank by default. The server's stderr goes to `objasm-index.log` in the plugin's state area (`<workspace>/.metadata/.plugins/com.piccolosystems.riscos.lsp/`) |

Everything else (go to definition, find references, hover, completion,
outline, folding, diagnostics, mark occurrences) is LSP4E's standard
handling of what the server offers.

## Files edited converted

BBC BASIC and Messages files open in the *RISC OS Text Editor*: the Generic
Code Editor (`RiscOSTextEditor`), except that a file it can't show as it is
opens converted, through a small file system (`ConvertedFiles`, URI scheme
`riscos-text:`) that converts the file as it is read and converts it back as
it is written. Colouring, the language server and folding all see the text.

* **NULs** (Messages files, where a NUL ends each message) are shown as ␀.
  *RISC OS › Insert NUL* (Ctrl+Alt+0) types one. Everything else is Latin-1,
  and a character Latin-1 can't hold is refused when saving.
* **Tokenised BASIC** (`,ffb`) is shown as text and saved tokenised, by the
  same rules as BASIC's own tokeniser and Edit's (`asmindex`'s
  `text/BasicTokens`). Line numbers are hidden unless the program refers to
  them (GOTO, GOSUB, RESTORE…), as in Edit; but rather than renumbering on
  every save, each unchanged line keeps its number, and new lines are
  numbered between their neighbours. Problems Edit would mention (mismatched
  quotes or brackets, line numbers out of order) are shown after saving.
  Keywords run together, as in crunched programs (`EXTERR`), are listed with
  a space between them, so that they come back as keywords.

A program opened and saved unchanged comes back byte for byte, except for
those spaces, and except where a program has a keyword stored as text, which
text can't express: for instance a word such as `VOICE` in a comment inside
assembler, written before it was a keyword. Saving makes it a token.

## Shading

Lines that no entry point of the configuration assembles get the annotation
type `com.piccolosystems.riscos.inactive`. Its default appearance is a light
grey background, like CDT's inactive code. Macro body lines skipped in every
expansion get `com.piccolosystems.riscos.skippedInEveryExpansion`, which is off
by default. Both can be changed under *General › Editors › Text Editors ›
Annotations*.

## Building

`mvn verify` in the repository root builds this bundle, the syntax and CDT
bundles, the two features and the update site (`site/target/repository`),
using Tycho, and runs the tests in `../eclipse-tests`. The
build compiles the language server and puts `objasm-index.jar` inside this
bundle. GitHub Actions runs it on every push and keeps the update site as an
artifact.

## Trying it

1. Install the update site, as the user guide describes.
2. *File › Import… › RISC OS › Build Tree*, choose the tree's `RiscOS`
   directory and at least one Env file, and finish. The scan runs in the
   background.
3. Open an assembler file through a component's `objs/` link farm.

The content type covers `*.s` and `*.hdr`. Extensionless files the server
reads (the exported headers, and the Kernel's `s/` files) are added to the
content type as the server reports them, for their project and for the
workspace (see `SourceFileTypes`).
