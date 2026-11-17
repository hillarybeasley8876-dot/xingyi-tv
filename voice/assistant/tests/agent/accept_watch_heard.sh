#!/bin/sh
# Acceptance for job watch-heard: the bridge builds with this tree's sources and its JVM suite passes against them; the
# heard titles, the numbered titles and the slimmed search answer are in place. Builds into the bridge's
# build-wt-watch-heard/. Prints "WATCH HEARD OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-watch-heard sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-watch-heard sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
has TencentVideo.java 'Pinyin.sameSound('
has TencentVideo.java '"heard"'
has TencentVideo.java 'public static String slim('
has tv/TvTools.java 'TencentVideo.slim('
grep -q 'tencentHeard()' "$ROOT/tests/agent/MovieChecks.java" || { echo "MovieChecks has no tencentHeard() group"; ok=0; }
grep -q "import android\." "$A/TencentVideo.java" && { echo "TencentVideo.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo WATCH HEARD OK
