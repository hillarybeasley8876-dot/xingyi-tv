#!/bin/sh
# Acceptance for job video-real: the bridge builds with this tree's sources and its JVM suite passes against them; the
# Tencent link, the B站 resend and login-dialog helper are in place. Builds into the bridge's build-wt-video-real/.
# Prints "VIDEO REAL OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-video-real sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-video-real sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
grep -q 'Uri.encode(keyword)' "$A/tv/TvTools.java" && { echo "the Tencent search still encodes the keyword"; ok=0; }
grep -q 'dismissBiliLogin' "$A/tv/TvTools.java" || { echo "no dismissBiliLogin in TvTools"; ok=0; }
grep -q 'isBiliLogin' "$ROOT/tests/agent/SkillChecks.java" || { echo "SkillChecks never tests isBiliLogin"; ok=0; }
[ $ok = 1 ] && echo VIDEO REAL OK
