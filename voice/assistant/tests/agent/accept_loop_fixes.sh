#!/bin/sh
# Acceptance for job loop-fixes: the TV app builds with this tree's sources, the bridge's JVM suite passes against them,
# and the three fixes are in place. Builds into the bridge's build-wt-loop/ so parallel jobs do not collide.
# Prints "LOOP FIXES OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-loop sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-loop sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
grep -rq "电视已经关了" "$A/QuickCommand.java" "$A/tv/TvTools.java" && { echo "「电视已经关了」 is still said"; ok=0; }
grep -q "屏幕关了，电视还在待机，叫一声小爱同学就能用" "$A/QuickCommand.java" || { echo "the quick screen-off reply is missing"; ok=0; }
grep -q "class InstallerCache" "$A/InstallerCache.java" 2>/dev/null || { echo "InstallerCache.java missing"; ok=0; }
grep -q "InstallerCache" "$A/tv/TvTools.java" || { echo "find_installer does not use InstallerCache"; ok=0; }
grep -q "import android\." "$A/InstallerCache.java" && { echo "InstallerCache.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo LOOP FIXES OK
