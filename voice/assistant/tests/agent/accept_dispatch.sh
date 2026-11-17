#!/bin/sh
# Acceptance for job dispatch: the TV app builds with this tree's sources (the bridge's build.sh also compiles agent/tv),
# the bridge's JVM suite passes against them, the public API the lead wires after merging is there, and the pure
# classes stay pure. Builds into the bridge's build-wt-dispatch/ so parallel jobs do not collide. Prints "DISPATCH OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-dispatch sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-dispatch sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
check() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
pure() { for f in "$@"; do grep -q "android\." "$A/$f" && { echo "$f must stay pure (no android.*)"; ok=0; }; done; }
check Dispatch.java 'public static Route route('
check Dispatch.java 'public static boolean side(QuickCommand q)'
check Dispatch.java 'ANSWER_WINDOW_MS = 180_000'
check tv/AgentEngine.java 'Dispatch.route('
check tv/AgentEngine.java 'Dispatch.ANSWER_WINDOW_MS'
check tv/AgentEngine.java 'return "side"'
check tv/AgentEngine.java 'pending > 0'
pure Dispatch.java
grep -q "pending > 1" "$A/tv/AgentEngine.java" && { echo "resume still checks pending > 1"; ok=0; }
[ $ok = 1 ] && echo DISPATCH OK
