#!/bin/sh
# Acceptance for job hints: the TV app builds with this tree's sources (the bridge's build.sh also compiles agent/tv),
# the bridge's JVM suite passes against them, the API the lead wires is there, and Hints stays pure. Builds into the
# bridge's build-wt-hints/ so parallel jobs do not collide. Prints "HINTS OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-hints sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-hints sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
check() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
check Hints.java 'public static List<String> card(JSONObject habits, JSONObject notes, JSONObject schedules, long now, TimeZone tz)'
check Hints.java 'public static boolean allowed(long lastShown, long now, TimeZone tz)'
check Hints.java 'MIN_GAP_MS = 3 * 3_600_000L'
check tv/Hud.java 'public boolean showHint(State hint)'
check tv/Hud.java '"hint"'
check tv/AgentDebug.java 'hud-hint.png'
grep -q "android\." "$A/Hints.java" && { echo "Hints.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo HINTS OK
