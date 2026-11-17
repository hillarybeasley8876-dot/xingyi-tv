#!/bin/sh
# Acceptance for job habits: the TV app builds with this tree's sources (the bridge's build.sh also compiles agent/tv),
# the bridge's JVM suite passes against them, and habits are recorded and summarised in the engine. Prints "HABITS OK".
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
check Habits.java 'public static List<JSONObject> callsFromTranscript(JSONArray transcript)'
check Habits.java 'public static List<JSONObject> events(String tool, JSONObject args, JSONObject result, long now, int hour)'
check Habits.java 'public static void record(JSONObject habits, List<JSONObject> events, long now)'
check Habits.java 'public static int forget(JSONObject habits, String what)'
check Habits.java 'public static List<String> summarize(JSONObject habits, long now)'
check MemoryTools.java 'public static List<Tool> create(Store store, Store habits)'
check tv/AgentEngine.java 'Habits.callsFromTranscript('
check tv/AgentEngine.java 'Habits.summarize('
check tv/AgentEngine.java 'public void habit(String kind, String value)'
grep -q "android\." "$A/Habits.java" && { echo "Habits.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo HABITS OK
