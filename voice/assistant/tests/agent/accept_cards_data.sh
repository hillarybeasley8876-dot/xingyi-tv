#!/bin/sh
# Acceptance for job cards-data: the TV app builds with this tree's sources (the bridge's build.sh compiles agent/tv),
# the bridge's JVM suite passes against them, the API the panel needs is there and the cards travel to it. Builds into
# the bridge's build-wt-cards-data/ so parallel jobs do not collide. Prints "CARDS DATA OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-cards-data sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-cards-data sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
check() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
check Cards.java 'public static JSONObject stocks(List<Quotes.Quote> quotes, Calendar now)'
check Cards.java 'public static JSONObject list(String title, JSONArray items)'
check Cards.java 'public static boolean valid(JSONObject card)'
check Weather.java 'public static JSONObject card(String place, JSONObject forecast, String day, long now)'
check Ledger.java 'public volatile JSONObject card;'
check tv/TvTools.java 'Cards.stocks('
check tv/TvTools.java 'Weather.card('
check tv/TvTools.java 'Cards.list('
check tv/AgentEngine.java 't.card'
for f in Cards.java Weather.java Ledger.java; do
    grep -q "import android\." "$A/$f" && { echo "$f must stay pure (no android.*)"; ok=0; }
done
[ $ok = 1 ] && echo CARDS DATA OK
