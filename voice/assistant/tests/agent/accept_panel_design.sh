#!/bin/sh
# Acceptance for job panel-design: the TV app builds with this tree's sources, the bridge's JVM suite passes against
# them, the design's named parts are there, and onDraw allocates nothing. Builds into the bridge's build-wt-panel/ so
# parallel jobs do not collide. Prints "PANEL DESIGN OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-panel sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-panel sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
check() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
check tv/Hud.java 'static StaticLayout balanced(CharSequence text, TextPaint paint, int width, int maxLines, float spacingMult)'
check tv/Hud.java 'BREAK_STRATEGY_HIGH_QUALITY'
check tv/Hud.java '384'
check tv/AgentDebug.java '"running-long"'
check tv/AgentDebug.java '"failed"'
check tv/AgentDebug.java '"confirm-chips"'
check tv/AgentDebug.java '"install-consent"'
check tv/AgentDebug.java '"chain-noplan"'
check tv/Hud.java 'public final List<String[]> trace;'
check tv/AgentEngine.java 'trace'
# no allocation while drawing: onDraw must not create paints, layouts, typefaces or paths
awk '/protected void onDraw\(Canvas c\)/{f=1} f&&/new (Paint|TextPaint|StaticLayout|Path)\(|Typeface\.create|StaticLayout\.Builder|balanced\(/{print "allocates while drawing: " $0; bad=1} f&&/^        }$/{f=0} END{exit bad}' "$A/tv/Hud.java" || ok=0
[ $ok = 1 ] && echo PANEL DESIGN OK
