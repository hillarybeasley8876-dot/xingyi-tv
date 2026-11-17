#!/bin/sh
# Acceptance for job app-exit: the TV app builds with this tree's sources, the bridge's JVM suite passes against them,
# and media_control exit plus its quick command are in place. Builds into the bridge's build-wt-exit/ so parallel jobs
# do not collide. Prints "APP EXIT OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-exit sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-exit sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
grep -q '"exit"' "$A/tv/TvTools.java" || { echo "TvTools has no media_control exit"; ok=0; }
grep -q '回到桌面' "$A/tv/TvTools.java" || { echo "TvTools' exit does not say it went back to the home"; ok=0; }
grep -q '"exit"' "$A/QuickCommand.java" || { echo "QuickCommand never gives media_control exit"; ok=0; }
grep -q 'exits()' "$ROOT/tests/agent/QuickChecks.java" || { echo "QuickChecks has no exits() group"; ok=0; }
grep -q "import android\." "$A/QuickCommand.java" && { echo "QuickCommand.java must stay pure (no android.*)"; ok=0; }
grep -q '"exit"' "$ROOT/assets/agent/contract.json" || { echo "the contract lost media_control exit"; ok=0; }
[ $ok = 1 ] && echo APP EXIT OK
