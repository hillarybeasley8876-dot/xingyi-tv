#!/bin/sh
# Acceptance for job own-ui: the bridge builds with this tree's sources and its JVM suite passes against them; the test
# apps are hidden, a black screen leads home, set_hints only runs when asked for, and a question about 星仪's own
# panel opens nothing. Builds into the bridge's build-wt-own-ui/. Prints "OWN UI OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-own-ui sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-own-ui sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
has OwnApps.java 'public static boolean hidden(String pkg)'
has tv/TvTools.java 'OwnApps.hidden('
has QuickCommand.java '"blank"'
has tv/TvTools.java '"at_home"'
has Hints.java 'public static int switchAsked(String utterance)'
has HintTools.java 'switchAsked('
has Dispatch.java 'HARNESS'
grep -q 'blank()' "$ROOT/tests/agent/QuickChecks.java" || { echo "QuickChecks has no blank() group"; ok=0; }
grep -q 'ownApps()' "$ROOT/tests/agent/SkillChecks.java" || { echo "SkillChecks has no ownApps() group"; ok=0; }
grep -q "import android\." "$A/OwnApps.java" && { echo "OwnApps.java must stay pure (no android.*)"; ok=0; }
grep -q '"blank"' "$ROOT/assets/agent/contract.json" && { echo "contract.json must not list blank"; ok=0; }
[ $ok = 1 ] && echo OWN UI OK
