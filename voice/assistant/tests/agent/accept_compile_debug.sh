#!/bin/sh
# Acceptance for job compile-debug: the bridge builds with this tree's sources and its JVM suite passes against them;
# the on-demand compile is wired from AgentDebug to the guard. Builds into the bridge's build-wt-compile-debug/.
# Prints "COMPILE DEBUG OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-compile-debug sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-compile-debug sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
has AppCompile.java 'public static boolean validPackage(String'
has tv/MemoryGuard.java 'public String compileOnDemand(final String pkg)'
has tv/MemoryGuard.java '"compiling"'
has tv/AgentDebug.java '.COMPILE'
has tv/AgentDebug.java 'compileOnDemand('
[ $ok = 1 ] && echo COMPILE DEBUG OK
