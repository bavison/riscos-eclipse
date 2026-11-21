#!/bin/sh
# Builds the engine and its tests with javac alone; no dependencies.
set -e
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes build/test-classes
javac -d build/classes --release 17 -Xlint:all,-serial $(find src -name '*.java')
# Resources (anything in src that isn't Java) go in beside the classes.
(cd src && find . -type f ! -name '*.java' -exec sh -c 'mkdir -p "../build/classes/$(dirname "$1")" && cp "$1" "../build/classes/$1"' sh {} \;)
if [ -n "$(find test -name "*.java" 2>/dev/null)" ]; then
  javac -d build/test-classes -cp build/classes --release 17 $(find test -name '*.java')
fi
(cd build/classes && jar cfe ../objasm-index.jar com.piccolosystems.riscos.asmindex.cli.Main $(find . -type f))
echo "built build/objasm-index.jar"
