#!/bin/sh
# Builds, then runs the engine's unit tests and the language server's tests.
set -e
cd "$(dirname "$0")"
./build.sh >/dev/null
java -cp build/classes:build/test-classes com.piccolosystems.riscos.asmindex.Tests
java -cp build/classes:build/test-classes com.piccolosystems.riscos.asmindex.basic.BasicTests
java -cp build/classes:build/test-classes com.piccolosystems.riscos.asmindex.lsp.LspTests
