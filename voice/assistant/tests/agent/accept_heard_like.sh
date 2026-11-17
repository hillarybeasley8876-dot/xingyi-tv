#!/bin/sh
# Acceptance for job heard-like: the TV app builds with this tree's sources (the bridge's build.sh compiles agent/tv),
# the bridge's JVM suite passes against them, the API the lead relies on is there, Heard stays pure and the environment
# carries heard_like. Builds into the bridge's build-wt-heard/ so parallel jobs do not collide. Prints "HEARD OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-heard sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-heard sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
check() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
check Heard.java 'public static List<Hint> hints(String utterance, List<String[]> earlier)'
check Heard.java 'public static String line(List<Hint> hints)'
check Heard.java 'public final String said, meant, when;'
check tv/AgentEngine.java '"heard_like"'
check tv/AgentEngine.java 'Heard.hints('
grep -q "import android\." "$A/Heard.java" && { echo "Heard.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo HEARD OK
