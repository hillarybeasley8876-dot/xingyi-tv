#!/bin/sh
# Acceptance for job media-bili: the bridge builds with this tree's sources and its JVM suite passes against them; the
# parser no longer needs the tag "MediaSession". Builds into the bridge's build-wt-media-bili/. Prints "MEDIA BILI OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-media-bili sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-media-bili sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
grep -q 'mediaTags()' "$ROOT/tests/agent/ScreenChecks.java" || { echo "ScreenChecks has no mediaTags() group"; ok=0; }
grep -q "import android\." "$A/MediaSessions.java" && { echo "MediaSessions.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo MEDIA BILI OK
