#!/bin/sh
# Acceptance for job quick-info-2: the bridge builds with this tree's sources, the bridge's JVM suite (which pins which
# quick commands wait for XiaoAi) passes against them, QuickChecks has the info2() group and QuickCommand stays pure.
# Builds into the bridge's build-wt-quick-info2/ so parallel jobs do not collide. Prints "QUICK INFO2 OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-quick-info2 sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-quick-info2 sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
grep -q 'info2()' "$ROOT/tests/agent/QuickChecks.java" || { echo "QuickChecks has no info2() group"; ok=0; }
grep -q '"last"' "$A/QuickCommand.java" || { echo "QuickCommand never asks nba_games for the last game"; ok=0; }
grep -q "import android\." "$A/QuickCommand.java" && { echo "QuickCommand.java must stay pure (no android.*)"; ok=0; }
grep -q 'UNICODE_CHARACTER_CLASS\|(?U)' "$A/QuickCommand.java" && { echo "QuickCommand.java uses a flag Android rejects"; ok=0; }
[ $ok = 1 ] && echo QUICK INFO2 OK
