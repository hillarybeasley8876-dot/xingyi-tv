#!/bin/sh
# Compile every source file and run every JVM test of the bridge (no device needed). Prints "ALL JVM TESTS PASSED" and
# exits 0 only if everything passes. Tests are classes named *Test with a main(); they must only touch pure classes
# (android.* is on the compile classpath but NOT on the run classpath — calling Android stubs throws "Stub!").
set -eu
cd "$(dirname "$0")/.."
LIB=tests/lib/json-20240303.jar
OUT=${BRIDGE_BUILD:-build}/jvmtest
rm -rf "$OUT" && mkdir -p "$OUT/src" "$OUT/tests"
find src -name '*.java' > "$OUT/src.txt"
find tests -name '*.java' > "$OUT/tests.txt"
# Git Bash would rewrite a ';'-separated list: P hands javac/java Windows paths there (env.sh), plain paths elsewhere
# 1) production code against android.jar alone: Android's org.json (checked JSONException) exactly as on the TV.
#    星仪's agent runtime comes from 星仪's source tree, as in build.sh.
XINGYI=${XINGYI:-$(cd ../voice/assistant && pwd)}   # the engine sits next to the relay (H:/tools/mitv, or the public repository)
. "$XINGYI/tests/env.sh"   # JDK, ANDROID_JAR, SEP, P: this machine by default, JAVA_HOME / ANDROID_JAR elsewhere
need_android
"$JDK/javac" -encoding UTF-8 -nowarn -source 8 -target 8 -Xlint:-options -implicit:class -sourcepath "$(P "$XINGYI/src")" -cp "$(P "$ANDROID_JAR")" -d "$OUT/src" @"$OUT/src.txt"
# 2) tests against the compiled code and the real org.json; they run without android.jar
"$JDK/javac" -encoding UTF-8 -nowarn -source 8 -target 8 -Xlint:-options \
    -cp "$(P "$OUT/src")$SEP$(P "$LIB")" -d "$OUT/tests" @"$OUT/tests.txt"
CP_RUN="$(P "$OUT/tests")$SEP$(P "$OUT/src")$SEP$(P "$LIB")$SEP$(P "$ANDROID_JAR")"
fail=0
for f in $(find tests -name '*Test.java' | sort); do
  cls=$(sed -n 's/^package \(.*\);/\1/p' "$f").$(basename "$f" .java)
  arg=""
  [ "$(basename "$f")" = "MusicResolverTest.java" ] && arg=tests/music/fixtures/netease_search.json
  if ! "$JDK/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP_RUN" "$cls" $arg; then
    echo "FAILED: $cls"; fail=1
  fi
done
[ $fail -eq 0 ] && echo "ALL JVM TESTS PASSED"
exit $fail
