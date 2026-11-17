#!/bin/sh
# Acceptance for job watch-quick: the bridge builds with this tree's sources and its JVM suite passes against them; the
# quick command, the strict lookup and the new picking rules are in place. Builds into the bridge's
# build-wt-watch-quick/. Prints "WATCH QUICK OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-watch-quick sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-watch-quick sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
has TencentVideo.java 'static String plain('
has TencentVideo.java 'public final boolean upcoming'
has TencentVideo.java 'public final String how'
has TencentVideo.java 'public static boolean sure('
has QuickCommand.java '"tencent_video_open"'
has QuickCommand.java '"strict"'
has tv/TvTools.java 'TencentVideo.sure('
has tv/TvTools.java '"not_sure"'
has tv/TvTools.java '还没上线'
has Feeds.java '4 * 1024 * 1024'
grep -q 'tencentWatch()' "$ROOT/tests/agent/MovieChecks.java" || { echo "MovieChecks has no tencentWatch() group"; ok=0; }
grep -q 'watch()' "$ROOT/tests/agent/QuickChecks.java" || { echo "QuickChecks has no watch() group"; ok=0; }
grep -q "import android\." "$A/TencentVideo.java" && { echo "TencentVideo.java must stay pure (no android.*)"; ok=0; }
# the contract is not this job's to change: strict stays out of it
grep -q '"strict"' "$ROOT/assets/agent/contract.json" && { echo "contract.json must not list strict"; ok=0; }
[ $ok = 1 ] && echo WATCH QUICK OK
