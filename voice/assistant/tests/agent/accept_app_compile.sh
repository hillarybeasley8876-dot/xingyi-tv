#!/bin/sh
# Acceptance for job app-compile: the bridge builds with this tree's sources and its JVM suite passes against them; the
# standby compile is wired into the guard on a thread of its own. Builds into the bridge's build-wt-app-compile/.
# Prints "APP COMPILE OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-app-compile sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-app-compile sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
has AppCompile.java 'public static Map<String, String> uncompiled(String dexoptText)'
has AppCompile.java 'public static String next('
has tv/MemoryGuard.java 'AppCompile.next('
has tv/MemoryGuard.java 'compileApp('
has tv/MemoryGuard.java 'new Thread('
has tv/MemoryGuard.java 'compile_would'
grep -q 'compiling()' "$ROOT/tests/agent/MemoryCareChecks.java" || { echo "MemoryCareChecks has no compiling() group"; ok=0; }
grep -q "import android\." "$A/AppCompile.java" && { echo "AppCompile.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo APP COMPILE OK
