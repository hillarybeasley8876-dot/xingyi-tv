#!/bin/sh
# Acceptance for job schedules: the TV app builds with this tree's sources (the bridge's build.sh also compiles agent/tv),
# the bridge's JVM suite passes against them, the public API the lead wires after merging is there, and the pure
# classes stay pure. Builds into the bridge's build-wt-schedules/ so parallel jobs do not collide. Prints "SCHEDULES OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-schedules sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-schedules sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
check() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
pure() { for f in "$@"; do grep -q "android\." "$A/$f" && { echo "$f must stay pure (no android.*)"; ok=0; }; done; }
check Schedules.java 'public static String add(JSONObject book, JSONObject args, long now, TimeZone tz)'
check Schedules.java 'public static String describe(JSONObject item, long now, TimeZone tz)'
check Schedules.java 'public static List<JSONObject> due(JSONObject book, long now)'
check Schedules.java 'public static void fired(JSONObject book, int id, long now, TimeZone tz)'
check Schedules.java 'public static List<JSONObject> skipMissed(JSONObject book, long now, long graceMs, TimeZone tz)'
check Schedules.java 'public static String list(JSONObject book, long now, TimeZone tz)'
check Schedules.java 'public static List<String> cancel(JSONObject book, String which, long now, TimeZone tz)'
check ScheduleTools.java 'public static List<Tool> create(Store store, LongSupplier clock, TimeZone tz)'
check tv/Scheduler.java 'public static synchronized Scheduler get(Context context)'
check tv/Scheduler.java 'public synchronized void tick()'
check tv/Scheduler.java 'public ScheduleTools.Store store()'
pure Schedules.java ScheduleTools.java
[ $ok = 1 ] && echo SCHEDULES OK
