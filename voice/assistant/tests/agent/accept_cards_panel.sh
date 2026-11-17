#!/bin/sh
# Acceptance for job cards-panel: the TV app builds with this tree's sources (the bridge's build.sh compiles agent/tv),
# the bridge's JVM suite passes against them, and the API the lead wires is there. Builds into the bridge's
# build-wt-cards-panel/ so parallel jobs do not collide. Prints "CARDS PANEL OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-cards-panel sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-cards-panel sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
check() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
check tv/Hud.java 'public final JSONObject card;'
check tv/Hud.java 'HudCards.of('
check tv/HudCards.java 'static HudCards of(JSONObject card, float d, int innerWidth)'
check tv/HudCards.java 'float height()'
check tv/HudCards.java 'void draw(Canvas c, float x, float y)'
check tv/HudCards.java '"weather"'
check tv/HudCards.java '"stocks"'
check tv/HudCards.java '"list"'
check tv/AgentDebug.java '"card-weather"'
check tv/AgentDebug.java '"card-stocks"'
check tv/AgentDebug.java '"card-list"'
# no allocation while drawing: draw() must not create paints, layouts, typefaces or paths
awk '/void draw\(Canvas c, float x, float y\)/{f=1} f&&/new (Paint|TextPaint|StaticLayout|Path)\(|Typeface\.create|StaticLayout\.Builder/{print "allocates while drawing: " $0; bad=1} f&&/^    }/{f=0} END{exit bad}' "$A/tv/HudCards.java" || ok=0
[ $ok = 1 ] && echo CARDS PANEL OK
