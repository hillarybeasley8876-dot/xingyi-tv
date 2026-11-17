#!/bin/sh
# Acceptance for job bili-fullscreen: the bridge builds with this tree's sources and its JVM suite passes against them;
# the full-screen helper is in place and used where a B站 video was seen playing. Builds into the bridge's
# build-wt-bili-fullscreen/. Prints "BILI FULLSCREEN OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-bili-fullscreen sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-bili-fullscreen sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
has PlayerPages.java 'public static int biliFullScreenControl(String screenDigestText)'
has PlayerPages.java 'public static boolean biliIsFullScreen(String screenDigestText)'
has tv/TvTools.java 'biliFullScreen('
has tv/TvTools.java '"fullscreen"'
has tv/TvTools.java 'adb.tap(960, 300)'
grep -q 'biliFull()' "$ROOT/tests/agent/SkillChecks.java" || { echo "SkillChecks has no biliFull() group"; ok=0; }
grep -q "import android\." "$A/PlayerPages.java" && { echo "PlayerPages.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo BILI FULLSCREEN OK
