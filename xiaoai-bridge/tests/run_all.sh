#!/bin/sh
# Compile every source file and run every JVM test of the bridge (no device needed). Prints "ALL JVM TESTS PASSED" and
# exits 0 only if everything passes. Tests are classes named *Test with a main(); they must only touch pure classes
# (android.* is on the compile classpath but NOT on the run classpath — calling Android stubs throws "Stub!").
set -eu
cd "$(dirname "$0")/.."
JDK=$(cygpath -u "${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot}"); JDK="${JDK%/}/bin"
LIB=tests/lib/json-20240303.jar
ANDROID=${ANDROID_SDK:-/h/tools/android-sdk}/android-35/android.jar
OUT=${BRIDGE_BUILD:-build}/jvmtest
rm -rf "$OUT" && mkdir -p "$OUT/src" "$OUT/tests"
find src -name '*.java' > "$OUT/src.txt"
find tests -name '*.java' > "$OUT/tests.txt"
# Git Bash would rewrite a ';'-separated list: hand javac/java Windows paths, as build.sh does
# 1) production code against android.jar alone: Android's org.json (checked JSONException) exactly as on the TV.
#    星仪's agent runtime comes from 星仪's source tree, as in build.sh.
XINGYI=${XINGYI:-$(cd ../voice/assistant && pwd)}   # the engine sits next to the relay (H:/tools/mitv, or the public repository)
"$JDK/javac.exe" -encoding UTF-8 -nowarn -source 8 -target 8 -Xlint:-options -implicit:class -sourcepath "$(cygpath -w "$XINGYI/src")" -cp "$(cygpath -w "$ANDROID")" -d "$OUT/src" @"$OUT/src.txt"
# 2) tests against the compiled code and the real org.json; they run without android.jar
"$JDK/javac.exe" -encoding UTF-8 -nowarn -source 8 -target 8 -Xlint:-options \
    -cp "$(cygpath -w "$OUT/src");$(cygpath -w "$LIB")" -d "$OUT/tests" @"$OUT/tests.txt"
CP_RUN="$(cygpath -w "$OUT/tests");$(cygpath -w "$OUT/src");$(cygpath -w "$LIB");$(cygpath -w "$ANDROID")"
fail=0
for f in $(find tests -name '*Test.java' | sort); do
  cls=$(sed -n 's/^package \(.*\);/\1/p' "$f").$(basename "$f" .java)
  arg=""
  [ "$(basename "$f")" = "MusicResolverTest.java" ] && arg=tests/music/fixtures/netease_search.json
  if ! "$JDK/java.exe" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP_RUN" "$cls" $arg; then
    echo "FAILED: $cls"; fail=1
  fi
done
[ $fail -eq 0 ] && echo "ALL JVM TESTS PASSED"
exit $fail
