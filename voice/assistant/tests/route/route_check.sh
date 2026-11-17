#!/bin/sh
# The model router's contract (RouteContractTest), offline. Prints "ROUTE OK n scenarios".
# ModelRouter lives outside the agent core (it needs android.content.Context in its signatures), so run_core never
# compiled it; the contract only ran on the TV through a DUMP broadcast. Here it compiles against android.jar and runs
# on the JVM with the real org.json first on the class path (android.jar's org.json is stubs).
set -eu
cd "$(dirname "$0")/../.."
JDK="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin"
ANDROID=/h/tools/android-sdk/android-35/android.jar
LIB=tests/lib/json-20240303.jar
OUT=build/route-check
rm -rf "$OUT" && mkdir -p "$OUT"
W() { cygpath -w "$1"; }
"$JDK/javac.exe" -J-XX:ActiveProcessorCount=2 -encoding UTF-8 -nowarn -source 8 -target 8 -Xlint:-options -implicit:class \
    -sourcepath "$(W src)" -cp "$(W "$ANDROID")" -d "$(W "$OUT")" "$(W tests/route/RouteContractRun.java)"
"$JDK/java.exe" -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8 -cp "$(W "$OUT");$(W "$LIB");$(W "$ANDROID")" \
    xyz.erapi0neer.xingyi.RouteContractRun
