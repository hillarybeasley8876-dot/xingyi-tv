#!/bin/sh
# Acceptance for job bili-loading: the bridge builds with this tree's sources and its JVM suite passes against them; a
# loading B站 page is an answer. Builds into the bridge's build-wt-bili-loading/. Prints "BILI LOADING OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-bili-loading sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-bili-loading sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
has AgentLoop.java 'static boolean loadingLink(String name, JSONObject result)'
has tv/TvTools.java '"loading"'
has tv/TvTools.java '视频还在加载，稍等就会自己开始播放'
has tv/TvTools.java '45000'
grep -q 'loading()' "$ROOT/tests/agent/BiliChecks.java" || { echo "BiliChecks has no loading() group"; ok=0; }
[ $ok = 1 ] && echo BILI LOADING OK
