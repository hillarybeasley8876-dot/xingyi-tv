#!/bin/sh
# Acceptance for job media-seek: the bridge builds with this tree's sources and its JVM suite passes against them; the
# quick command and the tool for 快进 / 快退 are in place. Builds into the bridge's build-wt-media-seek/.
# Prints "MEDIA SEEK OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-media-seek sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-media-seek sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
has QuickCommand.java '"media_seek"'
has PlayerPages.java 'public static String seekSays(int seconds)'
has tv/TvTools.java 'new Base("media_seek")'
has tv/TvTools.java 'KEYCODE_MEDIA_FAST_FORWARD'
has tv/TvTools.java 'KEYCODE_MEDIA_REWIND'
has tv/TvTools.java 'PlayerPages.seekSays('
has AgentLoop.java '"media_seek"'
grep -q 'seek()' "$ROOT/tests/agent/QuickChecks.java" || { echo "QuickChecks has no seek() group"; ok=0; }
grep -q '"media_seek"' "$ROOT/assets/agent/contract.json" && { echo "contract.json must not list media_seek"; ok=0; }
[ $ok = 1 ] && echo MEDIA SEEK OK
