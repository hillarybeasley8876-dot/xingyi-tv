#!/bin/sh
# Acceptance for job perf: the TV app builds with this tree's sources (the bridge's build.sh also compiles agent/tv),
# the bridge's JVM suite passes against them, and the timing trace, the cacheable prompt and the B站 early start are
# wired. Prints "PERF OK".
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
check Perf.java 'public static JSONObject summary(List<JSONObject> timings, long wallMs, long firstSpeechMs)'
check Perf.java 'public static JSONObject rollup(List<JSONObject> summaries)'
check AgentLoop.java '"timing"'
check AgentLoop.java '关于用户'
check tv/AgentEngine.java 'perf.jsonl'
check tv/AgentEngine.java 'Perf.rollup('
check tv/AgentEngine.java 'case "timing"'
grep -q '## 关于用户' "$A/AgentLoop.java" && { echo "AgentLoop.java still appends ## 关于用户 to the system message"; ok=0; }
grep -q "android\." "$A/Perf.java" && { echo "Perf.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo PERF OK
