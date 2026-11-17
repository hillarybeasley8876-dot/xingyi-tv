#!/bin/sh
# Acceptance for job front-truth: the bridge builds with this tree's sources and its JVM suite passes against them; Eyes,
# TvTools and MemoryGuard use FrontApp / focusedApp; netease_karaoke no longer sends the model to open_app. Builds into
# the bridge's build-wt-front-truth/ so parallel jobs do not collide. Prints "FRONT TRUTH OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-front-truth sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-front-truth sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
hasnt() { grep -q -F "$2" "$A/$1" 2>/dev/null && { echo "still in $1: $2"; ok=0; }; }
has FrontApp.java 'public static boolean counts(String pkg, boolean activity, String own)'
has FrontApp.java 'public static String pkg(String focused)'
has FrontApp.java 'public static String cls(String focused)'
has Karaoke.java 'static String notInFront(String where, String title, String artist)'
has tv/Eyes.java 'FrontApp.counts('
has tv/Eyes.java 'getActivityInfo('
has tv/TvTools.java 'LocalAdb::focusedApp'
has tv/MemoryGuard.java 'LocalAdb::focusedApp'
hasnt tv/TvTools.java 'open_app 网易云音乐，或者 netease_play 放这首歌'
grep -q 'front()' "$ROOT/tests/agent/ScreenChecks.java" || { echo "ScreenChecks has no front() group"; ok=0; }
for f in FrontApp.java Karaoke.java; do
    grep -q "import android\." "$A/$f" && { echo "$f must stay pure (no android.*)"; ok=0; }
done
[ $ok = 1 ] && echo FRONT TRUTH OK
