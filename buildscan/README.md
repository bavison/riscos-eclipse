# buildscan: RISC OS indexer settings from a build tree

buildscan derives indexer settings for every component in a
cross-compilation RISC OS build tree from the tree itself,
without the ROOL tools installed. The Eclipse import wizard is built on it.

## Usage

buildscan is part of the engine's jar (its code is in
`asmindex/src/.../buildscan`):

```sh
java -jar asmindex/build/objasm-index.jar buildscan TREE ENV OUTDIR \
     [--component NAME]... [--external DIR=NAME]... [--jobs N]
python3 -I buildscan/check_with_clang.py OUTDIR
```

* `TREE` is the tree's `RiscOS` directory, and `ENV` is an Env file name
  without `.sh`, e.g. `ROOL/BCM2835`.
* `--component` limits the run to the named components (repeatable).
* `--external DIR=NAME` adds a project outside the tree, built against this
  tree's exports, as component `NAME`.
* `--jobs` sets how many components are dry-run at once (default: one per
  processor).
* `check_with_clang.py` parses every generated entry with
  `clang -fsyntax-only`, as a stand-in for Eclipse CDT's indexer, and
  summarises the errors.

It needs `bash` and GNU `make` 4.0 or later, and Java 17 or later. On Windows they
are MSYS2's, and on macOS GNU make is Homebrew's `gmake`; buildscan finds
them itself. Nothing else: no ROOL tools, and Python only for the clang
check.

## What it does

Nothing about a particular tree is hard-coded. For the Env file given, it:

1. Sources `Env/<ENV>.sh` and records the variables it sets
   (`environment.json`).
2. Reads `BuildSys/Components/<BUILD>` (the Components file named by `$BUILD`)
   and `BuildSys/ModuleDB` to get each component's source directory, its type
   (allowing for `-type` overrides), `TARGET`/`INSTDIR`, and its `-options`.
3. Dry-runs each component's build with `make -n -B -k`, using the `rom` and
   `export PHASE=libs` targets. This prints the `armcc` and `armasm` command
   lines without running any tools.
4. Writes, per component:
   * `compile_commands.json`: each `armcc` command translated into clang/gcc
     form. It keeps include paths (`-I`/`-J`, in either case, with
     comma-separated lists split) and `-D`/`-U`, and adds `__APCS_32` or
     `__APCS_26` from `-APCS`. It also adds `-include norcroft-compat.h` and a
     generic 32-bit Arm target with `-undef -nostdinc -funsigned-char`, so no
     host macros or headers leak in. File paths point into the `objs/` link
     farm.
   * `objasm.json`: for each `armasm` command, the include path, `-PD`
     predefines and `-APCS`/`-CPU`/`--fpu` options. This is the objasm
     indexer's input.
5. Records the RISC OS paths the Env file sets (`HDR_PATH` for `Hdr:`, and
   so on, each a `:`-separated list of directories) in `environment.json`.

The only build-system knowledge in buildscan is the ROOL build conventions it
relies on: the ModuleDB and Components file formats, the `rom` and
`export PHASE=libs` targets, and the `objs/` link farm. That knowledge is
grouped in `CODE_TYPES` and `TARGETS` at the top of `BuildScan.java`. buildscan
writes `norcroft-compat.h` into the output directory, beside the settings.

`norcroft-compat.h` describes the Norcroft compiler, not any particular tree.
It defines Norcroft's predefined macros, removes host compiler macros, and
neutralises Norcroft keywords (`__swi(n)`, `__value_in_regs`, `__pure`,
`__irq`, `__packed`, `__global_reg(n)`, `__caller_narrow`, `__weak`), the
`__abs` operator, the internal keywords used by Norcroft's CLib headers
(`___type`, `___assert`, `___typeof`, `___select`, `___i`), and a few
intrinsics.

## Libraries

Components of type `EXP` (in the ModuleDB, or given `-type EXP` in the
Components file) are libraries: they are built only by their
`export PHASE=libs` target, for other components to link with. buildscan
dry-runs them too, so their C and assembler are indexed like any other.

For each component, `summary.json` also records the archives it links with
(`links`, the `.a` files on its `armlink` command lines), the archives it
exports (`exports`, the `.a` files it copies into the export tree), and from
those, the components whose exports it links with (`uses`). That is what lets
a symbol be followed from a component into the libraries it links with, and
back.

## Things to be aware of

* `make -n` still runs `$(shell …)`. With the shared makefiles, this means a
  dry run creates any missing `objs/` link-farm links. That is harmless, and
  arguably useful.
* The dependency files in a built tree (`*.d`, and others such as the
  Kernel's `*.dk` and RISC_OSLib's `*.m_d`) contain the absolute paths it was
  built at, so a tree downloaded from CI names paths that don't exist. The
  dry runs therefore give make one extra rule, `%:: ;` (with `--eval`, which
  make passes on to recursive makes): a file with no other rule needs nothing
  doing. make prefers the most specific rule that applies, so this only
  matters where it would otherwise stop with "No rule to make target". The
  makefiles are untouched, and a tree gives the same settings wherever it is
  unpacked. This needs GNU make 4.0 or later. (A makefile named in `MAKEFILES`
  would not do: the shared makefiles recurse with
  `-f ../$(firstword $(MAKEFILE_LIST))`.) The same rule also hides a makefile
  naming a source file that doesn't exist.
* A tree whose path contains a space is refused, with a message saying so.
  make splits words at spaces, and the shared makefiles' test for being in
  `objs/` then fails, so each level runs make again one `objs/` deeper,
  without end (creating the directories as it goes). The ROOL build can't
  work there either. Each dry run is also stopped after two minutes, as a
  guard against anything similar.
* The dry runs are the slow part, so components are dry-run in parallel.

## Variables srcbuild sets

The ROOL build runs each component's make from srcbuild, which first sets
some variables of its own, so buildscan sets them the same way for the dry
runs (they are recorded in `environment.json` under `set_as_srcbuild_would`):

* `BUILDLOCALELISTNUMERIC`: the territory number of each locale in
  `LOCALELIST` (or `LOCALE`), as two digits each. The numbers come from the
  exported `Global/Countries.h`; entries such as `1=UK` or `UK=1` in the list
  add to or override them. Messages uses this to find its resources.
* `LOCALE`: the first locale in the list.
* `RESOURCEDIR`: the Components file's `%Messages` directory, followed by
  `Resources/<BUILD>/<numbers>/<first locale>`.

