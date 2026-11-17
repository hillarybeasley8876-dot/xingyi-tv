#!/bin/sh
# Acceptance for job explore: the TV app builds with this tree's sources (the bridge's build.sh also compiles agent/*),
# the bridge's JVM suite passes against them, the public API the lead wires is there, and the classes stay pure.
# Builds into the bridge's build-wt-explore/ so parallel jobs do not collide. Prints "EXPLORE OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-explore sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-explore sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
check() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
check AppIntents.java 'public static JSONObject parse(String dump, String pkg)'
check AppIntents.java 'public static String summary(JSONObject parsed, int maxChars)'
check AppNotes.java 'public static String add(JSONObject book, String pkg, String label, String note, long now)'
check AppNotes.java 'public static List<String> notes(JSONObject book, String pkg)'
check AppNotes.java 'public static List<String> remove(JSONObject book, String pkg, String which)'
check AppNotes.java 'public static List<Skills.Skill> skills(JSONObject book)'
check ExploreTools.java 'public static List<Tool> create(Dumper dumper, Apps apps, MemoryTools.Store notes)'
for f in AppIntents.java AppNotes.java ExploreTools.java; do
    grep -q "android\." "$A/$f" && { echo "$f must stay pure (no android.*)"; ok=0; }
done
[ $ok = 1 ] && echo EXPLORE OK
