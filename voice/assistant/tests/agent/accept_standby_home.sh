#!/bin/sh
# Acceptance for job standby-home-4: 「熄屏那一刻浮上来的是谁」 is read from the system's own usage log, in the first
# heartbeat after the screen went off — not at the ACTION_SCREEN_OFF broadcast, which the TV proved too late three
# times (2026-11-12 10:11: front_at_off was already com.phlox.tvwebbrowser when the broadcast arrived, the firmware
# having killed the desktop and let the browser up before it). The whole receiver is gone with it.
# Offline only — the real hand test on the TV is the lead's (see status/improve/standby-home-4.md, ## Requests).
# Prints "STANDBY HOME OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
C="$ROOT/tests/agent/MemoryCareChecks.java"
mkdir -p "$ROOT/build"
ok=1

if ! sh "$ROOT/tests/agent/run_core.sh" > "$ROOT/build/accept-core.log" 2>&1; then
    echo "core checks FAILED:"; tail -20 "$ROOT/build/accept-core.log"; ok=0
fi
grep -q -E 'agent core: [0-9]+ passed, 0 failed' "$ROOT/build/accept-core.log" || { echo "the core run did not end 0 failed"; ok=0; }
if ! sh "$ROOT/tests/agent/android_check.sh" > "$ROOT/build/accept-android.log" 2>&1; then
    echo "android check FAILED:"; tail -20 "$ROOT/build/accept-android.log"; ok=0
fi
grep -q 'ANDROID CHECK OK' "$ROOT/build/accept-android.log" || { echo "no ANDROID CHECK OK"; ok=0; }

has() { grep -q -F "$2" "$1" 2>/dev/null || { echo "missing: $2"; ok=0; }; }
hasnt() { grep -q -F "$2" "$1" 2>/dev/null && { echo "still there: $2"; ok=0; }; }

# the rule itself: the events and the moment the screen went off, two package names out
has "$A/MemoryCare.java" 'public static String surfacedAfter(List<Usage.Event> events, long offAt)'
has "$A/MemoryCare.java" 'public static boolean homeBack(String surfaced, String home)'
has "$A/MemoryCare.java" 'e.type == Usage.FOREGROUND'
has "$A/MemoryCare.java" 'offAt - 1_000L'
has "$A/MemoryCare.java" 'offAt + 10_000L'
has "$A/MemoryCare.java" 'public static long lastScreenOffAt(List<Usage.Event> events)'
has "$A/MemoryCare.java" 'public static String lastScreenOffPkg(List<Usage.Event> events)'
has "$A/MemoryCare.java" 'public static String homeBackSays(String label)'
hasnt "$A/MemoryCare.java" 'frontAtOff'
hasnt "$A/MemoryCare.java" 'focusedNow'
hasnt "$A/MemoryCare.java" 'homeBack(String frontAtOff'
hasnt "$A/MemoryCare.java" 'litFront'
hasnt "$A/MemoryCare.java" 'homeRunning'
hasnt "$A/MemoryCare.java" 'import android'

# the guard: the hour's events now hold the screen going off, and the rule is asked from them
has "$A/tv/MemoryGuard.java" 'type == Usage.SCREEN_OFF'
has "$A/tv/MemoryGuard.java" 'MemoryCare.lastScreenOffAt(sw)'
has "$A/tv/MemoryGuard.java" 'MemoryCare.surfacedAfter(sw, offAt)'
has "$A/tv/MemoryGuard.java" 'MemoryCare.homeBack(surfaced, home)'
has "$A/tv/MemoryGuard.java" 'if (offAt == 0) { homeBackDone = true; return; }'
has "$A/tv/MemoryGuard.java" 'private boolean homeBackWould(List<Usage.Event> sw)'
has "$A/tv/MemoryGuard.java" '"surfaced_after_off"'
has "$A/tv/MemoryGuard.java" '"screen_off_at_s_ago"'
has "$A/tv/MemoryGuard.java" '"last_screen_off"'
hasnt "$A/tv/MemoryGuard.java" 'litFront'
hasnt "$A/tv/MemoryGuard.java" 'homeRunning'

# the broadcast implementation is gone: it was measured wrong on the TV three times
hasnt "$A/tv/MemoryGuard.java" 'screenReceiver'
hasnt "$A/tv/MemoryGuard.java" 'frontAtOff'
hasnt "$A/tv/MemoryGuard.java" 'registerReceiver'
hasnt "$A/tv/MemoryGuard.java" 'unregisterReceiver'
hasnt "$A/tv/MemoryGuard.java" 'BroadcastReceiver'
hasnt "$A/tv/MemoryGuard.java" 'IntentFilter'
hasnt "$A/tv/MemoryGuard.java" 'screenChanged'
hasnt "$A/tv/MemoryGuard.java" 'readFrontAtOff'
hasnt "$A/tv/MemoryGuard.java" 'FRONT_READ_MS'
hasnt "$A/tv/MemoryGuard.java" 'Intent.ACTION_SCREEN_OFF'
hasnt "$A/tv/MemoryGuard.java" 'Intent.ACTION_SCREEN_ON'
hasnt "$A/tv/MemoryGuard.java" 'xingyi-screen-off-front'
hasnt "$A/tv/MemoryGuard.java" 'dispose'
hasnt "$A/tv/MemoryGuard.java" '"front_at_off"'
hasnt "$A/tv/MemoryGuard.java" '"home_back_done"'

# every case of the rule, one line per case
for label in \
    '10:05: what surfaced at screen-off is the browser, not the desktop that was in front before it' \
    '10:05: the browser surfaced behind the desktop: the desktop goes back on top' \
    'a video in front and the system bringing the desktop back: the desktop is what surfaced, nothing is put back' \
    'the desktop is what surfaced: nothing to do' \
    'an app that came to the front five seconds before the screen went off is not what surfaced at screen-off' \
    'a second of tolerance before screen-off: the system writes those two events in either order' \
    'nine seconds after screen-off still counts: what surfaces is sometimes logged late' \
    'ten seconds is the edge of the window and it is in it' \
    'eleven seconds after screen-off is outside the window' \
    'no app came to the front around screen-off: no answer, and the screen-off event itself is never the answer' \
    'nothing surfaced: no guess, nothing put back' \
    'only a background event for the browser: the desktop is what is in front, nothing to do' \
    'two apps come to the front in the window: the last one is what the user will see' \
    'the same package twice: it is the answer once' \
    '20:06: the HDMI source page surfaced behind the desktop: the desktop goes back on top' \
    '10:11: the broadcast read the browser as the front at screen-off, and the log agrees' \
    'surfaced at screen-off and not the desktop: ' \
    'a package that only looks like the desktop is not the desktop' \
    "a process name with :sub is not the desktop's package" \
    'the rule has no overlay list: whatever surfaced and is not the desktop goes back behind it' \
    'no home screen resolved: nothing to put back' \
    'nothing surfaced: nothing put back, no throw' \
    'both unreadable: no throw, nothing done' \
    'no usage events at all: no answer, no throw' \
    'two standby periods in one hour: each is read around its own screen-off' \
    'the screen-off' \
    "two of them in the hour: the last one is this period's start" \
    'no screen-off event in the hour: nothing says where the period started' \
    'no events at all: no throw, no answer' \
    "the screen-off event is the system's own (android)" \
    'no screen-off event: no package for it either' \
    'one homeBack, two package names: the old three-argument form and frontAtOff are gone' \
    'surfacedAfter takes the events and the moment the screen went off' \
    'the note names the app that surfaced and says the desktop is back'
do
    has "$C" "$label"
done

# the usage-log group: the guard's source is read, so what it does on the TV is checked here
for label in \
    'the screen on/off receiver is gone: what it read was already too late (10:11)' \
    'and frontAtOff, the field it filled, is gone with it' \
    'nothing is registered for a broadcast any more' \
    'and neither import is left behind' \
    'screenChanged(Intent) is gone: there is no broadcast to handle' \
    'the window manager read that ran after the broadcast is gone too' \
    'and how long such a read still counted is gone with it' \
    'the guard does not listen for the screen at all any more' \
    'the thread that read the front after the broadcast is gone' \
    'dispose() unregistered the receiver, so it is gone with the receiver' \
    'the constructor makes the record files and nothing else: no receiver is built or registered there' \
    'switches(Context, long) is still there to read' \
    'the app coming to the front is still collected' \
    'and the app leaving it' \
    'the screen going off (16, SCREEN_NON_INTERACTIVE) is collected too: that is where a standby starts' \
    "an hour of them: the screen is off now, so the last screen-off in it is this period's start" \
    'the events keep their own time, type and package' \
    'homeBack(long, long) is still there to read' \
    'the lit branch still starts the next period over' \
    'once per screen-off period, at the first heartbeat after the threshold' \
    "the heartbeat reads the system's own usage log itself, on the wall clock the events are stamped with" \
    'and takes the last screen-off in it as where this standby period starts' \
    'no screen-off event in the hour: nothing is known about this period, so nothing is put back' \
    'and that is given up on before the rule is asked, not after' \
    'the rule is asked which package came to the front around that moment' \
    'and whether the desktop has to go back on top' \
    'the window manager is read only after the rule said yes: it is no longer the criterion' \
    'and only to make sure the desktop is not on top already, or that the read said anything at all' \
    'the front is read once, and only when the desktop is about to go back' \
    'then the desktop is put on top and the note names what surfaced' \
    'the period is done either way, so the next heartbeat does not look again' \
    'of the rule passes what surfaced, not what is in front now' \
    'the heartbeat and the dry run, and nothing else, ask the rule' \
    'homeBackWould is still there to read' \
    'it is given the events the plan already read, so the dry run asks about the same hour' \
    'the dry run does nothing while the screen is lit' \
    'the same last screen-off' \
    'and the same rule, on the same events' \
    'asked exactly as the heartbeat asks it, with no read of its own' \
    'plan() is still there to read' \
    'the state carries what the rule found at the last screen-off' \
    'and how many seconds ago that screen-off was' \
    'and which package wrote the screen-off event' \
    'and neither field that came from the broadcast is left in the state' \
    'both are worked out from the same events the rule used, not from a second read' \
    'the dry run in the same plan is given those same events' \
    'the two debug fields have a method each, so the plan stays one line per field' \
    'the rule takes the events and the moment the screen went off' \
    'and the answer is two package names' \
    'the window is a second before and ten seconds after, written down rather than guessed at' \
    'only a package coming to the front counts as surfacing' \
    "the last screen-off is read on the pure side: the Android package cannot see Usage.Event's fields" \
    'frontAtOff is gone from the rule too, not only from the guard' \
    'and what is in front now is no longer an input to it' \
    'one homeBack is declared: no second criterion left beside the new one' \
    'and the rule stays pure' \
    'usageLog();'
do
    has "$C" "$label"
done

[ $ok = 1 ] && echo STANDBY HOME OK
