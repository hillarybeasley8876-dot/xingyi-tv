#!/bin/sh
# Acceptance for job data-polish: the bridge builds with this tree's sources and its JVM suite passes against them; the
# five data answers changed where the brief says. Builds into the bridge's build-wt-data-polish/. Prints "DATA POLISH OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-data-polish sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-data-polish sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
grep -q '"last"' "$A/tv/TvTools.java" || { echo "the nba_games tool never reads last"; ok=0; }
grep -q '这周' "$A/Bangumi.java" || { echo "Bangumi does not know 这周"; ok=0; }
grep -q '昨天' "$A/News.java" || { echo "News never says 昨天"; ok=0; }
for f in Sports.java News.java BiliVideos.java Alerts.java Bangumi.java; do
    grep -q "import android\." "$A/$f" && { echo "$f must stay pure (no android.*)"; ok=0; }
done
[ $ok = 1 ] && echo DATA POLISH OK
