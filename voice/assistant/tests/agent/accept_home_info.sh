#!/bin/sh
# Acceptance for job home-info: the TV app builds with this tree's sources, the bridge's JVM suite passes against them,
# the API the home screen calls is there. Builds into the bridge's build-wt-home/ so parallel jobs do not collide.
# Prints "HOME INFO OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-home sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-home sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
check() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
check Almanac.java 'public static String homeLine(int y, int m, int d)'
check Weather.java 'public static String homeLine(JSONObject forecast, long now, java.util.TimeZone tz)'
check tv/AgentEngine.java 'JSONObject homeInfo()'
check tv/AgentProvider.java '"home"'
grep -q "import android\." "$A/HomeNotes.java" && { echo "HomeNotes.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo HOME INFO OK
