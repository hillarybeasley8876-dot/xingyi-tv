#!/bin/sh
# The model router's contract (RouteContractTest), offline. Prints "ROUTE OK n scenarios".
# ModelRouter lives outside the agent core (it needs android.content.Context in its signatures), so run_core never
# compiled it; the contract only ran on the TV through a DUMP broadcast. Here it compiles against android.jar and runs
# on the JVM with the real org.json first on the class path (android.jar's org.json is stubs).
set -eu
cd "$(dirname "$0")/../.."
. tests/env.sh   # JDK, ANDROID_JAR, SEP, P: this machine by default, JAVA_HOME / ANDROID_JAR elsewhere
need_android
LIB=tests/lib/json-20240303.jar
OUT=build/route-check
rm -rf "$OUT" && mkdir -p "$OUT"
"$JDK/javac" -J-XX:ActiveProcessorCount=2 -encoding UTF-8 -nowarn -source 8 -target 8 -Xlint:-options -implicit:class \
    -sourcepath "$(P src)" -cp "$(P "$ANDROID_JAR")" -d "$(P "$OUT")" "$(P tests/route/RouteContractRun.java)"
"$JDK/java" -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8 -cp "$(P "$OUT")$SEP$(P "$LIB")$SEP$(P "$ANDROID_JAR")" \
    xyz.erapi0neer.xingyi.RouteContractRun
