#!/bin/sh
# Acceptance for job quick-commands: the TV app builds with this tree's sources, the bridge's JVM suite passes against
# them, and the quick path is wired into the engine. Prints "QUICK OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
check() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
check QuickCommand.java 'public static QuickCommand match(String utterance, Collection<String> appLabels)'
check QuickCommand.java 'public String reply(JSONObject result)'
check QuickCommand.java 'static int number(String s)'
check tv/AgentEngine.java 'QuickCommand.match('
check tv/AgentEngine.java 'private boolean runQuick(QuickCommand q, TvTools tools, String text, String source, int gen)'
grep -q "android\." "$A/QuickCommand.java" && { echo "QuickCommand.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo QUICK OK
