#!/bin/sh
# Acceptance for job ktcp-state: the bridge builds with this tree's sources and its JVM suite passes against them; the
# audio-state reader, the idempotent pause / resume for 云视听极光 and the confirmed switch_input are in place.
# Builds into the bridge's build-wt-ktcp-state/. Prints "KTCP STATE OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-ktcp-state sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-ktcp-state sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
has AudioPlayers.java 'public static String state(String dump, int uid)'
has PlayerPages.java 'public static boolean jiguangNeedsKey('
has PlayerPages.java 'public static boolean jiguangSettled('
has tv/TvTools.java 'LocalAdb::audioPlayers'
has tv/TvTools.java 'AudioPlayers.state('
has tv/TvTools.java 'PlayerPages.jiguangNeedsKey('
has tv/TvTools.java 'ExternalSourceActivity'
has tv/TvTools.java '刚才打开的是'
grep -q 'audioPlayers()' "$ROOT/tests/agent/SkillChecks.java" || { echo "SkillChecks has no audioPlayers() group"; ok=0; }
grep -q "import android\." "$A/AudioPlayers.java" && { echo "AudioPlayers.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo KTCP STATE OK
