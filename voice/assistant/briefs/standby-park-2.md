# Brief: standby-park-2 — put the home screen on top before closing a parked app, and check that it stayed closed

## Why (the TV's own log, 2026-11-09)
The standby park (job standby-park) closed NetEase at 11:53 and again at 13:53. The persistent system log
(/data/log/logcat.log.03.*) shows why it had to do it twice:

    11:53:04.180  ActivityManager: Force stopping com.netease.cloudmusic.tv appid=10022 user=0: from pid 6423
    11:53:04.275  ActivityManager: Start proc 6426:com.netease.cloudmusic.tv/u0a22 for activity com.netease.cloudmusic.tv/.activity.MainActivity

95 ms after the force-stop the system started NetEase again *for an activity*: NetEase was the top activity when the TV
went to standby, so stopping it made the system bring the top activity straight back — and NetEase burned its 0.4 core
for two more hours. The guard's record said 「关掉了它」 both times; nothing checked.

## What to build (all in `MemoryGuard`, the TV glue; keep `MemoryCare` pure)
1. Before `stopApps` in `park(...)`, put the home screen on top: `app.startActivity(new Intent(Intent.ACTION_MAIN)
   .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))` (Android 9 lets a background app start
   an activity; in standby it does not light the screen — no key events, no power key), then wait about 1.5 s.
2. After `stopApps`, wait about 3 s and read the processes again (`MemoryCare.byPackage(once(app, LocalAdb::processes))`,
   as `low()` does). A package that runs again was not parked: leave its parked.json entry out (or remove it), record
   「关掉后又被系统拉起来了」 for it instead of 「关掉了」 (one line through `note`), and do not try that package again for
   6 hours (a per-package cooldown in memory). Packages that stayed closed are recorded as now.
3. If a pure helper makes the wording testable, put it in `MemoryCare` (e.g. `parkSays` with the packages that came
   back) and check it in `tests/agent/MemoryCareChecks.java`; at least 3 new checks.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` (parts of it make the API refuse
  requests), and do not open `src/xyz/erapi0neer/xingyi/LocalAdb.java` — use only the calls `MemoryGuard` already makes
  (`once(app, LocalAdb::processes)`, `once(app, a -> { a.stopApps(list); return null; })`).
- No TV, no adb. Nothing on the heartbeat may throw (catch `Exception | LinkageError` as the guard does); the sleeps run on
  the heartbeat's thread, never on the main thread.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → its last line `agent core: N passed, 0 failed` with N ≥ 3607
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `grep -c "CATEGORY_HOME" src/xyz/erapi0neer/xingyi/agent/tv/MemoryGuard.java` → at least 1
