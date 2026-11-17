#!/bin/sh
# Acceptance for job quick-real: the bridge builds with this tree's sources, the bridge's JVM suite (which pins which
# quick commands wait for XiaoAi) passes against them, QuickChecks has the real() group and QuickCommand stays pure.
# Builds into the bridge's build-wt-quick-real/ so parallel jobs do not collide. Prints "QUICK REAL OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-quick-real sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-quick-real sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
grep -q 'real()' "$ROOT/tests/agent/QuickChecks.java" || { echo "QuickChecks has no real() group"; ok=0; }
grep -q '切换到' "$A/QuickCommand.java" || { echo "QuickCommand does not know 切换到"; ok=0; }
grep -q '显示器' "$A/QuickCommand.java" || { echo "QuickCommand does not know 显示器"; ok=0; }
grep -q "import android\." "$A/QuickCommand.java" && { echo "QuickCommand.java must stay pure (no android.*)"; ok=0; }
grep -q 'UNICODE_CHARACTER_CLASS\|(?U)' "$A/QuickCommand.java" && { echo "QuickCommand.java uses a flag Android rejects"; ok=0; }
[ $ok = 1 ] && echo QUICK REAL OK
