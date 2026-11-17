#!/bin/sh
# NetEase catalog checks (pure Java + org.json): prints the test's PASS line. Usage: sh tests/run_catalog.sh
set -eu
cd "$(dirname "$0")/.."
JDK="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin"
OUT=build/nc
rm -rf "$OUT" && mkdir -p "$OUT"
"$JDK/javac.exe" -J-XX:ActiveProcessorCount=2 --release 8 -encoding UTF-8 -nowarn -Xlint:-options \
    -cp tests/lib/json-20240303.jar -sourcepath src -d "$OUT" tests/NeteaseCatalogTest.java
"$JDK/java.exe" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT;tests/lib/json-20240303.jar" xyz.erapi0neer.xingyi.NeteaseCatalogTest
