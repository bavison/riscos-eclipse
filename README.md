# RISC OS sources in Eclipse

Eclipse support for RISC OS source trees: an import wizard that makes
projects of a built tree's components; for ARM assembler (objasm), an
indexer with a language server, for go to definition, find uses, hover,
completion, outline, folding and shading of code a configuration doesn't
assemble; BBC BASIC, text or tokenised; C through CDT, with each
component's include paths and macros; and colouring of CMHG and Messages
files.

To use it, see the [user guide](USER-GUIDE.md).

| Path | What it is |
|---|---|
| `eclipse-syntax/` | Eclipse bundle `com.piccolosystems.riscos`: the TextMate grammars, registered with TM4E. Those for assembler, BASIC and CMHG are copies of the ones in `bavison/language-riscos` |
| `eclipse-lsp/` | Eclipse bundle `com.piccolosystems.riscos.lsp`: the import wizard and RISC OS menu; connects the language server to assembler and BASIC files through LSP4E, and shades untaken `IF` branches; edits Messages files and tokenised BASIC converted |
| `eclipse-cdt/` | Eclipse bundle `com.piccolosystems.riscos.cdt` (optional, needs CDT): makes the components with C files C/C++ projects, with the include paths and macros of the active configuration |
| `eclipse-tests/` | Tests of the bundles in a headless Eclipse workbench, on an invented tree |
| `feature/`, `feature-cdt/`, `site/` | The features and the update site |
| `asmindex/` | The objasm indexer engine and its language server (plain Java 17, no dependencies), described in its `README.md` |
| `buildscan/` | How the indexer settings are derived from a build tree's Env file (the code is the `buildscan` command in `asmindex`), and the clang check for the C side |
| `WIZARD-DESIGN.md` | Design of the import wizard and the C side |
| `USER-GUIDE.md`, `docs/images/` | How to install and use it, with screenshots taken by `eclipse-tests` (`GuideScreenshotsTest`) |

## Publishing

`mvn verify` (with Java 21 or later, and Maven 3.9, as Tycho 4 needs) builds
the update site in `site/target/repository`, and CI does the same on every
push (on Windows and macOS too, for `main`). Two things are switched on from
the repository's settings:

* **Signing.** The update site is signed with PGP when the repository has the
  secrets `PGP_SECRET_KEYS`, an armoured private key, and `PGP_PASSPHRASE`, its
  passphrase. Users are then asked once whether to trust the key, instead of
  being asked about unsigned content on every install or update. The key is
  Piccolo Systems Ltd's, below.
* **The update site on GitHub Pages**, at `https://bavison.github.io/riscos-eclipse/`,
  for users to install from and get updates from. Set **Settings › Pages ›
  Source** to *GitHub Actions*, and add a repository variable `PUBLISH_SITE`
  set to `true`. Each push to `main` that builds and passes its tests then
  publishes the site.

## Signing key

The update site is signed with this key, whose public half is
[`piccolo-systems.asc`](piccolo-systems.asc). When Eclipse asks whether to
trust it, its fingerprint should be:

```
0F1F BB7A CF62 271A B3A8  3E1A 7925 FB74 F2BA E113
Piccolo Systems Ltd <code@piccolosystems.com>, RSA 3072, expires 2031-11-20
```

## Licence

MIT: see [LICENSE](LICENSE). Developed by Piccolo Systems Ltd. The TextMate
grammars for assembler, BASIC and CMHG come from
[language-riscos](https://github.com/bavison/language-riscos).
