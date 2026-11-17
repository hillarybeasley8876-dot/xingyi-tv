#!/bin/sh
# Acceptance for job self-repair: the bridge builds with this tree's sources and its JVM suite passes against them; the
# repair table, the repairer itself and the wiring into self_check are in place. Builds into build-wt-self-repair/.
# Prints "SELF REPAIR OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-self-repair sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-self-repair sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
has Repairs.java 'public static List<String> plan(String symptom)'
has Repairs.java 'public static String report('
has Repairs.java 'public static String normal('
has tv/SelfRepair.java 'static JSONObject run(TvTools'
has tv/SelfRepair.java 'LocalAdb::overlayHidden'
has tv/SelfRepair.java 'rebindAccessibility('
has tv/SelfRepair.java 'ADB_ENABLED'
has tv/TvTools.java 'SelfRepair.run(this)'
ls "$ROOT/tests/agent/RepairsChecks.java" >/dev/null 2>&1 || { echo "RepairsChecks.java missing"; ok=0; }
grep -q 'RepairsChecks.run()' "$ROOT/tests/agent/SkillChecks.java" || { echo "SkillChecks never runs RepairsChecks"; ok=0; }
grep -q "import android\." "$A/Repairs.java" && { echo "Repairs.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo SELF REPAIR OK
