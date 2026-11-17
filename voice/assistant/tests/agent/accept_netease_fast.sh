#!/bin/sh
# Acceptance for job netease-fast: the bridge builds with this tree's sources and its JVM suite passes against them;
# keys may go through the TV's remote service, a sleeping TV wakes before NetEase, 伴奏 brings the player page back.
# Builds into the bridge's build-wt-netease-fast/ so parallel jobs do not collide. Prints "NETEASE FAST OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-netease-fast sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-netease-fast sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
hasnt() { grep -q -F "$2" "$A/$1" 2>/dev/null && { echo "should not be in $1: $2"; ok=0; }; }
has KeyNames.java 'public static String of(int keyCode)'
has KeyNames.java 'public static boolean ok(String httpResponse)'
has tv/RemoteKeys.java 'static boolean press(String name)'
has tv/RemoteKeys.java 'new Socket'
hasnt tv/RemoteKeys.java 'HttpURLConnection'
has tv/ScreenWake.java 'public static boolean ensureOn(Context c'
has tv/TvTools.java 'RemoteKeys.press('
has tv/TvTools.java 'ScreenWake.ensureOn('
has tv/TvTools.java 'nmcloudmusictv://ng/player"'
grep -q 'remoteKeys()' "$ROOT/tests/agent/SkillChecks.java" || { echo "SkillChecks has no remoteKeys() group"; ok=0; }
grep -q 'usesCleartextTraffic="false"' "$BRIDGE/AndroidManifest.xml" || { echo "the bridge manifest's cleartext switch changed"; ok=0; }
grep -q "import android\." "$A/KeyNames.java" && { echo "KeyNames.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo NETEASE FAST OK
