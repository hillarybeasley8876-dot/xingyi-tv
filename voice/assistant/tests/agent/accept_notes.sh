#!/bin/sh
# Acceptance for job notes-history: the TV app builds with this tree's sources (the bridge's build.sh also compiles agent/tv),
# the bridge's JVM suite passes against them, the public API the lead wires after merging is there, and the pure
# classes stay pure. Builds into the bridge's build-wt-notes-history/ so parallel jobs do not collide. Prints "NOTES OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-notes-history sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-notes-history sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
check() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
pure() { for f in "$@"; do grep -q "android\." "$A/$f" && { echo "$f must stay pure (no android.*)"; ok=0; }; done; }
check Notes.java 'public static String add(JSONObject notes, String text, long now)'
check Notes.java 'public static String list(JSONObject notes, long now, TimeZone tz)'
check Notes.java 'public static List<String> done(JSONObject notes, String which)'
check Notes.java 'public static List<String> summary(JSONObject notes, long now, TimeZone tz)'
check NoteTools.java 'public static List<Tool> create(Store store, LongSupplier clock, TimeZone tz)'
check History.java 'public static void add(JSONObject log, String said'
check History.java 'public static List<JSONObject> query(JSONObject log, String query, int days, long now)'
check History.java 'public static String describe(JSONObject item, long now, TimeZone tz)'
check HistoryTools.java 'public static List<Tool> create(Store store, LongSupplier clock, TimeZone tz)'
pure Notes.java NoteTools.java History.java HistoryTools.java
[ $ok = 1 ] && echo NOTES OK
