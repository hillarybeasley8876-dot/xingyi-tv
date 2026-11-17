# Brief: standby-home-3 — 记住「熄屏那一刻前台是谁」要用熄屏事件，不是 20 秒心跳

## Why (审 standby-home-2 时看出来的，不是猜的)
`standby-home-2` 把规则改对了：不再看桌面进程死没死，而是看「熄屏时前台是不是桌面、现在是不是桌面」。
但它的输入——`litFront`——只在**亮屏时的心跳**里采样，而心跳是 `BootReceiver` 的 20 秒周期
（`xiaoai-bridge/src/…/BootReceiver.java:16 PERIOD_MS = 20_000`）。

真机复现的时序（`k1111/h1.py`，2026-11-12 03:27）：

```
[  6.0] browser in front: True
[  9.7] after HOME: xyz.erapi0neer.orrery      ← 桌面在前
[ 10.9] screen off                              ← 1.2 秒后熄屏
[ 14.5] t+ 3.6 s front=com.phlox.tvwebbrowser  ← 浏览器冒到前面
```

从桌面在前到熄屏只有 1.2 秒，20 秒一次的心跳只有约 6% 的机会落在这一秒里；绝大多数时候 `litFront` 还是浏览器，
新规则于是判「熄屏时前台的浏览器、现在还是浏览器 → 不用管」，**正是要修的那个场景不会触发**。

熄屏这一刻前台一定是桌面 —— 那是最可靠的一次采样，而且系统在广播里当场告诉我们。

## What to build

### 1. `tv/MemoryGuard.java` — a screen-off receiver that reads the front on the spot
- In `MemoryGuard(Context)`: register a receiver for `android.content.Intent.ACTION_SCREEN_OFF` **and**
  `ACTION_SCREEN_ON` on the application context (`c.getApplicationContext()`, no permission string needed for these
  two in API 28), held in a field so it is not collected. In `onReceive`: on SCREEN_OFF read
  `Eyes.foregroundPackage()` (static, and it is the accessibility service's fresh package — the same source the bridge
  uses for its BACK decision) and store it in `frontAtOff` **only when it is not empty**; on SCREEN_ON clear
  `frontAtOff = ""` so the next standby starts clean.
  `Eyes.foregroundPackage()` may be stale by the time a broadcast is handled; if you can read the window manager
  instead without blocking the broadcast thread (a thread of its own, `once(app, LocalAdb::focusedApp)`), prefer it
  and fall back to `Eyes.foregroundPackage()` — say in the javadoc which one it reads and why. **Never block the main
  thread**: a `Thread` of its own for the ADB read, or the accessibility value alone.
- `homeBack(long now, long offFor)`: the lit-tick branch keeps clearing `homeBackDone` and **no longer samples
  `litFront`** (delete that field and its reads from the beat — the receiver replaces it). After the off threshold:
  `if (frontAtOff.isEmpty()) { homeBackDone = true; return; }` — the broadcast is missed (the guard was created after
  the screen went off, or the process was restarted): do nothing, rather than yank a desktop over a video.
  Then the same test as today's `MemoryCare.homeBack(frontAtOff, focusedNow, home)`.
- `home_back_would` uses `frontAtOff` too, so the dry run tells the truth.
- Unregistering: the guard lives as long as the process; still, add a `dispose()` that unregisters, and have nothing
  call it (the same shape as `Hud.dispose`); the point is that a future caller can.

### 2. `MemoryCare.java` — no rule change
`homeBack(litFront, focusedNow, home)` keeps its signature and body from standby-home-2: rename the parameter from
`litFront` to `frontAtOff` and update the javadoc sentence that says where it comes from (the screen-off receiver).
Nothing else in the file.

### 3. Checks — `tests/agent/MemoryCareChecks.java`, at least 8 more, in a new `screenOffReceiver()` group
Read `tv/MemoryGuard.java` as text (the file is yours; use `Files.readString(Paths.get(...))` the way the other
source checks do):
- it registers `ACTION_SCREEN_OFF` and `ACTION_SCREEN_ON` on the application context;
- the SCREEN_ON branch clears the field; the SCREEN_OFF branch assigns it from a non-empty read;
- `homeBack` is called with that field (three arguments), and `MemoryCare.homeBack(frontAtOff, …)` is the call;
- no `litFront` sampling is left in the beat (the identifier `litFront` does not appear in the file);
- `dispose()` exists and calls `unregisterReceiver`.
And in the pure rule group: the same cases as the `homeBack()` group (the rule itself has not changed — the existing
ones stay and must keep passing).

### 4. `tests/agent/accept_standby_home.sh` — keep it passing
It greps labels out of `MemoryCareChecks.java`; add the new group's sentences to its grep list so a deletion is caught.

## Rules
- Own only: `src/xyz/erapi0neer/xingyi/agent/tv/MemoryGuard.java`, `src/xyz/erapi0neer/xingyi/agent/MemoryCare.java`,
  `tests/agent/MemoryCareChecks.java`, `tests/agent/accept_standby_home.sh`.
- **Never open** `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`. No TV, no adb, no
  network. Do not edit the bridge repository (the receiver lives in the assistant tree, which the bridge compiles in).
- `MemoryGuard.java` may gain the two android `Intent`/`BroadcastReceiver` imports and nothing else; `MemoryCare.java`
  stays pure (no android import).

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed`, N at least 5210
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_standby_home.sh` → `STANDBY HOME OK`
- The proof is the lead's: build the bridge, install, then `h1.py` — the desktop has to come back within a few
  seconds of the screen going off. Write that into `## Requests` as the line the lead runs.
