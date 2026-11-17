#!/bin/sh
# Acceptance for job tencent-open: the bridge builds with this tree's sources and its JVM suite passes against them; the
# tool, the search parser and the POST fetch are in place. Builds into the bridge's build-wt-tencent-open/.
# Prints "TENCENT OPEN OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-tencent-open sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-tencent-open sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
has TencentVideo.java 'pick('
has TencentVideo.java 'detailLink('
has TencentVideo.java 'playLink('
has Feeds.java 'public interface Post'
has Feeds.java 'public static Post httpsPost()'
has tv/TvTools.java '"tencent_video_open"'
has tv/TvTools.java 'TencentVideo.pick('
has tv/TvTools.java 'SearchActivity'
has AgentLoop.java '"tencent_video_open"'
has Movies.java 'tencent_video_open'
grep -q 'tencent()' "$ROOT/tests/agent/MovieChecks.java" || { echo "MovieChecks has no tencent() group"; ok=0; }
grep -q "import android\." "$A/TencentVideo.java" && { echo "TencentVideo.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo TENCENT OPEN OK
