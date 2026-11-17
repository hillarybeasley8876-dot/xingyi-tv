#!/bin/sh
# Acceptance for job tv-wiring: the TV app builds with this tree's sources, the bridge's JVM suite passes against them,
# and the wiring exists. Prints "WIRING OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
check() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
check Tool.java 'int round();'
check SearchBudget.java 'String check(int round)'
check Observation.java 'static String describe(String appLabel, String playingTitle)'
check PlayerPages.java 'static boolean isPlayer(String pkg, String activity)'
check tv/TvTools.java 'new Base("web_search")'
check tv/TvTools.java 'String observe()'
check tv/AgentEngine.java 'case "say":'
check tv/AgentEngine.java '.observe('
[ $ok = 1 ] && echo WIRING OK
