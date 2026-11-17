#!/bin/sh
# Acceptance for job tencent-episode: the bridge builds with this tree's sources and its JVM suite passes against them;
# the episode list reader, the episode link and the quick command's episode are in place. Builds into the bridge's
# build-wt-tencent-episode/. Prints "TENCENT EPISODE OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-tencent-episode sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-tencent-episode sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
has TencentVideo.java 'public static final String EPISODES'
has TencentVideo.java 'public static String episodesBody('
has TencentVideo.java 'public static Episodes episodes('
has TencentVideo.java 'public static String slimEpisodes('
has TencentVideo.java 'public static String playLink(String cid, String vid)'
has tv/TvTools.java 'TencentVideo.EPISODES'
has tv/TvTools.java '"no_episode"'
has tv/TvTools.java 'TencentVideo.slimEpisodes('
has QuickCommand.java '"episode"'
grep -q 'tencentEpisodes()' "$ROOT/tests/agent/MovieChecks.java" || { echo "MovieChecks has no tencentEpisodes() group"; ok=0; }
grep -q "import android\." "$A/TencentVideo.java" && { echo "TencentVideo.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo TENCENT EPISODE OK
