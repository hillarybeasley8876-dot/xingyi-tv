# Brief: standby-home — after standby the home screen is what is on top, not the app that was left behind it

## Why (on the TV, 2026-11-11 20:07 — reproduced with the browser, and seen at 20:06 with the HDMI source page)
This TV's firmware force-stops the app in front whenever the screen goes off, unless it is on its own whitelist:

```
20:07:53.063  PowerManagerService: gotoSleepStayAliveProcess current:xyz.erapi0neer.orrery not in whitelist
20:07:53.087  ActivityManager: Force stopping xyz.erapi0neer.orrery
```
Orrery is our home screen. When nothing else is open, the system starts the home screen again and all is well. But
when another app was left behind it (the user said 「回到桌面」 and then 「关闭屏幕」; or pressed HOME on the remote),
that app's page becomes the resumed activity the moment the home screen is killed —
`ResumedActivity: com.phlox.tvwebbrowser/.activity.main.MainActivity` in the test — and the next time the TV is switched
on, the user is looking at that app instead of the home screen.

`MemoryGuard` already has what is needed: `tick()` runs every 20 seconds from the heartbeat, `screenOffFor(now)` tells
how long the screen has been off, `homeOnTop()` starts the home screen without lighting the display (the standby park
uses it), `once(app, LocalAdb::focusedApp)` + `FrontApp.pkg(...)` read the app that really is in front, and `note(...)`
writes a line into the guard's notes.

## What to build

How to tell this case from "the user switched the screen off while looking at an app" (a video, the HDMI source — the
firmware's own behaviour stands there): **the home screen's process is gone.** It is killed only when it was the app
in front at screen-off; while another app is in front it stays alive behind it.

### 1. `MemoryCare.java` (pure)
```java
/** The screen is off: has the home screen to be put back on top? focusedNow: mFocusedApp's package now; home: the
 *  home screen's package; homeRunning: whether the home screen still has a process. */
public static boolean homeBack(String focusedNow, String home, boolean homeRunning)
```
true only when `home` is not empty, `focusedNow` is not empty and not `home`, and `homeRunning` is false.

```java
/** The guard's note: 「待机时哔哩哔哩浮到了最上面，已经把桌面放回去」. */
public static String homeBackSays(String label)
```

### 2. `tv/MemoryGuard.java`
- A new step in `tick()`, `homeBack(now, offFor)`, before `park`: once per screen-off period (a boolean field that is
  cleared when the screen is seen on again), at the first tick at which the screen has been off for at least 3
  seconds:
  - `home` = the package that answers `Intent.ACTION_MAIN` + `Intent.CATEGORY_HOME`
    (`app.getPackageManager().resolveActivity(..., 0)`; null or no package → do nothing, mark the period done);
  - `focusedNow` = `FrontApp.pkg(once(app, LocalAdb::focusedApp))`; when it is `home` or empty → nothing to do, mark
    the period done;
  - `homeRunning` = `MemoryCare.byPackage(once(app, LocalAdb::processes)).containsKey(home)` (the same read `low()`
    uses);
  - when `MemoryCare.homeBack(focusedNow, home, homeRunning)` → `homeOnTop()` and
    `note(System.currentTimeMillis(), MemoryCare.homeBackSays(label(app, focusedNow)))`; mark the period done.
  An ADB read that fails (IOException) leaves it for the next tick (do not mark the period done); nothing thrown here
  may stop `park` and `compile` from running (catch inside the step).
- `plan()` (the `MEMGUARD --es mode plan` dry run) gets `"home_back_would": true|false`: the same decision with what
  can be read right now, without doing anything (false when a read fails or the screen is on).
- Nothing else changes (the park still calls `homeOnTop()` itself).

## Checks (`tests/agent/MemoryCareChecks.java`, a new group `homeBack()` called from `run()`)
`homeBack("com.phlox.tvwebbrowser", "xyz.erapi0neer.orrery", false)` true; the same with `homeRunning` true → false
(the user switched the screen off in that app); `focusedNow` equal to `home` → false; `focusedNow` empty or null →
false; `home` empty or null → false; `homeBackSays("哔哩哔哩")` is `待机时哔哩哔哩浮到了最上面，已经把桌面放回去`.
At least 8 checks.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`
  (`LocalAdb::focusedApp` is called exactly as `MemoryGuard.front` already calls it).
  No TV, no adb, no network. Java 8 / Android API 28.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N at least 8 more than before you
  started (the job's check requires the number in the job file)
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_standby_home.sh` → `STANDBY HOME OK`
