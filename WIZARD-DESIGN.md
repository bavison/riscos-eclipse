# Importing a RISC OS build tree into Eclipse

Design of the import wizard, and of how a tree's configurations are kept up to
date afterwards. All of this is in the `com.piccolosystems.riscos.lsp` bundle,
except the C side (`com.piccolosystems.riscos.cdt`).

## What the user does

*File › Import… › RISC OS › Build tree*:

1. **Tree.** Choose the tree's `RiscOS` directory. The wizard checks that it
   has `Env/`, `BuildSys/ModuleDB` and `BuildSys/Components/`.
2. **Configurations.** A checklist of the Env files that set `BUILD`, with
   one to make active. Each one ticked becomes a
   configuration.
3. **Components.** The components with code in the ticked configurations'
   Components files, all ticked. Each one ticked becomes a project.
4. **External projects** (optional; they can also be added later). Directories outside the tree that build
   against its exports, each with a component name.
5. **Finish.** A background job runs buildscan for each configuration and
   creates the projects. Components whose makefile couldn't be dry-run get a
   warning in the Problems view rather than failing the import.

## Afterwards

* **Active configuration.** There is one active configuration per tree,
  chosen from the *RISC OS* menu or the toolbar's drop-down lists. Changing
  it points the language server at that configuration's settings, which it
  indexes without restarting, and has CDT index the C projects again with
  that configuration's settings.
* **Rescanning.** *RISC OS › Rescan build tree* runs buildscan again, after a
  change to a makefile, a Components file or an Env file.
* **External projects later.** *RISC OS › Add external project…* (also on a
  project's context menu) does page 4 of the wizard on its own: it adds a
  directory outside the tree as a component, runs buildscan for it in every
  configuration, and creates its project. Removing one is deleting the
  project. The wizard's page 4 is only a shortcut for doing the same at
  import time.
* **Where the settings live.** buildscan's output goes in the plugin's
  workspace state area
  (`.metadata/.plugins/com.piccolosystems.riscos.lsp/trees/<tree>/<Env>/`, with
  `/` in the Env name as `_`), not in the tree.
* **Problems.** Makefiles buildscan couldn't dry-run in the active
  configuration show in the Problems view, as warnings on their project.

## Projects

* **One project per component directory**, at the directory (`Sources/Kernel`,
  …), named after it as the tree's paths are (`Wimp` for WindowManager);
  components sharing a directory (ADFSFiler, SCSIFiler, SDFSFiler…) share the
  project. Where two directories have the same name (`build`), the
  component's name is used instead, and where another tree's project has
  the name, the tree's name follows in brackets (`Wimp (BCM2835-2)`).
  Libraries of headers only get one too (`TCPIPLibs/headers`). Editors open the files in `objs/`, as
  intended.
* **One project for the tree itself**, at `RiscOS/`, named after the tree.
  The exported headers (`Export/APCS-32/Hdr/…`), Env files and Components
  files are workspace files then. That matters because go to definition
  often lands in `Hdr:Macros` and the like, and only workspace files of the
  right content type are sent to the language server. The component projects
  are nested inside it, which Eclipse allows, and the Project Explorer shows
  them that way.

Each project's `.project`, `.settings/` and `.cproject` go into its
directory in the tree. EGit then finds each component's own git repository
by itself, so history, blame and diffs work in Eclipse. To keep `git status`
clean, the wizard adds those names to each repository's `.git/info/exclude`,
which is local and never committed.

Every project the wizard creates has its text file encoding set to
ISO-8859-1 (Latin-1), which RISC OS sources use. Without it, Eclipse uses
the host's default, usually UTF-8, which garbles characters such as `£` in
comments, and warns that the project has no explicit encoding.

(The alternative, projects in the workspace linking to the component
directories, writes nothing into the tree, but EGit doesn't follow linked
folders, so there would be no git integration.)

## C and C++ (CDT)

The C side is a separate, optional bundle (`com.piccolosystems.riscos.cdt`,
in its own feature), so the assembler support works without CDT installed.

Each component project with C files becomes a C/C++ makefile project, made
as CDT's *Makefile Project with Existing Code* makes one (no toolchain,
managed building off), with one configuration, *RISC OS*. Rather than one
CDT configuration per Env file, that configuration follows the tree's active
configuration, so the RISC OS menu switches C and assembler together, and
the C projects are indexed again when it does.

CDT's own indexer is used. The configuration's only language-settings
provider is the bundle's own, which gives each file the include paths,
macros and forced includes buildscan recorded in `compile_commands.json`
(a header gets those of the first compiled file of its component). The
host compiler's built-ins never come in: what Norcroft predefines is in
`norcroft-compat.h`, which every file includes first. Reading the
compilation database itself decides exactly how `-include` and `-isystem`
are taken.

From C to assembler: Ctrl+click on a name the assembler of the component, or
of a library it links with, `EXPORT`s offers *Open Assembler Definition*,
and the C editor's menu has that and *Find References in Assembler*, whose
results go to the Search view. (CDT's own F3 is unchanged: to the C
definition, or the prototype.) From assembler to C, the language server does
it: see `asmindex/README.md`.

(The alternative is CDT's LSP editor with clangd, which reads
`compile_commands.json` directly and is the parser the clang check uses, but
needs clangd installed.)

## Content types

Only files of the `armasm` content type get colouring and reach the language
server. `*.s` already is. Besides those, assembler reads `.hdr` files (in
link farms) and many files with no extension at all: mostly the exported
headers (`Hdr:Interface`, `Hdr:Global`), and some components' own, such as
the Kernel's `objs/s/`.

`.hdr` is part of the content type, in the syntax bundle.

The extensionless ones are added by name, as the server reports the files
it reads: to the content type of the project that holds them (stored in the
project's `.settings/org.eclipse.core.runtime.prefs`), and to the
workspace's own content-type settings too. LSP4E needs the latter, as it
decides whether a file belongs to a language server from its name and
contents alone, not its project's settings. So a file elsewhere in the
workspace with one of those names (`Macros`, say) is taken as assembler too.

## Assumptions

* Any number of trees per workspace, each with its own active
  configuration. One language server indexes all of them, and CDT reads
  all of their C settings. The RISC OS menu's commands act on the tree of
  the file being edited or selected, asking which if that doesn't say.
* The host has `bash` and GNU `make` 4.0 or later (on Windows from MSYS2,
  on macOS from Homebrew), for the dry runs.

## Bundles

| Bundle | Contents |
|---|---|
| `com.piccolosystems.riscos` | Syntax colouring, and the content types |
| `com.piccolosystems.riscos.lsp` | The language server client, the wizard, the active configuration, and buildscan, called directly from the bundled `objasm-index.jar` |
| `com.piccolosystems.riscos.cdt` | Optional: C/C++ projects for the components, and their language settings for CDT |
