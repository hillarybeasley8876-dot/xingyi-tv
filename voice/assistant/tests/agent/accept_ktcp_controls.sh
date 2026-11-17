#!/bin/sh
# Acceptance for job ktcp-controls: the bridge builds with this tree's sources and its JVM suite passes against them;
# media_control sends its key to 云视听极光's window when its player is in front and no media session is live. Builds
# into the bridge's build-wt-ktcp-controls/. Prints "KTCP CONTROLS OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-ktcp-controls sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-ktcp-controls sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
has PlayerPages.java 'public static boolean isJiguangPlayer(String pkg, String cls)'
has tv/TvTools.java '云视听极光已暂停'
has tv/TvTools.java '云视听极光继续播放了'
grep -q 'jiguangPlayer()' "$ROOT/tests/agent/SkillChecks.java" || { echo "SkillChecks has no jiguangPlayer() group"; ok=0; }
grep -q "import android\." "$A/PlayerPages.java" && { echo "PlayerPages.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo KTCP CONTROLS OK
