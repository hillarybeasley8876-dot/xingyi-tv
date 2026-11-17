# Brief: standby-home-2 — 守卫判断「桌面被顶掉」的旁证是错的

## Why (真机验出来的，2026-11-12 03:16 和 03:27 两次)
`session7.py` 的 H 段在真机上失败：亮屏 → 浏览器在前 → HOME（桌面在前）→ 熄屏。熄屏 3.6 秒后前台变成浏览器，
之后 **108 秒守卫一次都没动**，`recent` 里也没有新记录。同一台电视 01:55 对网易云音乐是动过的
（「待机时网易云音乐浮到了最上面，已经把桌面放回去」）。

原因查清了，不靠猜：

```
$ adb shell ps -A | grep -E 'orrery|phlox'
u0_a28  21432 ... com.phlox.tvwebbrowser
u0_a24  21696 ... xyz.erapi0neer.orrery        ← 桌面进程还活着
$ adb shell am broadcast … MEMGUARD --es mode state
{"real_front":"com.phlox.tvwebbrowser", …, "home_back_would":false, "recent":[ … 没有 03:16 那条 ]}
```

`MemoryCare.homeBack(focusedNow, home, homeRunning)` 只在 `!homeRunning` 时动手——它相信「熄屏时固件会杀掉
前台应用，所以桌面进程死了就说明桌面被顶掉了」。今晚固件**没有**杀桌面：浏览器在前，桌面进程照样活着，于是规则
判定「不用管」，而实际上用户开电视看到的是浏览器。**判据失效，不是守卫没跑**（20 秒一次的心跳，108 秒里至少
跳了五次）。

正确的判据是**看结果，不看旁证**：熄屏前那一刻在前的是谁。熄屏前桌面在前、熄屏后前台不是桌面，就是被顶掉了，
与桌面进程死没死无关。熄屏前本来就是某个应用在前（看视频、HDMI），固件自己的行为算数，不动。

## What to build

### 1. `MemoryCare.java` — a rule that takes what was in front when the screen went off
```java
/** 熄屏时桌面在前、现在前台却不是桌面：桌面被顶掉了（看谁在前，不看桌面进程死没死）。
 *  litFront: 最后一次亮屏时读到的前台包名；focusedNow: 现在的前台。两者相同、或 litFront 不是桌面、或不认识，都是 false。 */
public static boolean homeBack(String litFront, String focusedNow, String home)
```
Keep the old three-argument `homeBack` **out** of the file (nothing else calls it: `MemoryGuard` is the only caller and
it is yours too). The javadoc above replaces the old paragraph that explains the process-death reasoning; say in it,
in one sentence, why the process check is gone (2026-11-12 03:16, the firmware left the desktop's process alive behind
the browser).

### 2. `tv/MemoryGuard.java` — remember what was in front while the screen was lit
- New field `private String litFront = "";` — "the package in front on the most recent tick with the screen lit".
- In `homeBack(long now, long offFor)`: while `offFor == 0` (the screen is lit), read the front (through the same
  `FrontApp.pkg(once(app, LocalAdb::focusedApp))` the method already uses) and keep it in `litFront` **only when the
  read succeeds and is not empty**; a failed read keeps the last known one. Reset `homeBackDone = false` as today.
- At the first tick with `offFor >= HOME_BACK_OFF_MS`: read `focusedNow`; when `focusedNow` is empty or equals `home`,
  done for this period (as today); otherwise call the new `MemoryCare.homeBack(litFront, focusedNow, home)`. True →
  `homeOnTop(); note(…); homeBackDone = true;`. False → `homeBackDone = true` (the same once-per-period rule).
- No other change; `home_back_would` must use the same rule with `litFront`, so the dry run tells the truth.
- Do not let a failed read throw out of `homeBack`: keep it inside the existing try.

### 3. The guard's note — say who was on top and that the user was looking at the desktop
`MemoryCare.homeBackSays(label)` stays `「待机时」 + label + 「浮到了最上面，已经把桌面放回去」`. Only the label comes
from `focusedNow` as today. No new sentence.

### 4. Checks — `tests/agent/MemoryCareChecks.java`, at least 12 more inside the existing `homeBack()` group
- The group's existing checks (lines ~220–233) call the old three-argument form: rewrite them to the new rule, keeping
  the cases they already cover (the browser surfaced behind the desktop → true; the desktop in front → false; an empty
  read → false; 云视听极光 behind the desktop → true; a browser "home" is never taken for the desktop).
- New cases: lit desktop + now the browser → true; lit browser (the user was watching it) + now the browser → false;
  lit browser + now the desktop → false; lit desktop + now empty → false; empty `litFront` → false; `home` empty or
  null → false; `focusedNow` null → false.
- A wiring group (read the source, as the file already does for the guard): `MemoryGuard.java` holds `litFront`, assigns
  it inside the `offFor` lit branch, and calls `MemoryCare.homeBack(` with three arguments; the old
  `homeRunning`-based call is gone. Read the file with the helper the file already uses for source reads — if there is
  none, `Files.readString(Paths.get("src/xyz/erapi0neer/xingyi/agent/tv/MemoryGuard.java"))` the way the existing
  checks read `TvTools.java`.

`MemoryCareChecks` is called from `AgentCoreTest`, which you must not edit — add to the existing `run()` list of the
file, which is yours.

### 5. `tests/agent/accept_standby_home.sh` — prints `STANDBY HOME OK` (offline only, no TV)
- `sh tests/agent/run_core.sh` → `0 failed`
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `grep` the three rules of `MemoryCare.homeBack` above out of `MemoryCareChecks.java`: one line per case, all present
  (`grep -F` each label).
- Nothing on the TV.

## Rules
- Own only: `src/xyz/erapi0neer/xingyi/agent/MemoryCare.java`, `src/xyz/erapi0neer/xingyi/agent/tv/MemoryGuard.java`,
  `tests/agent/MemoryCareChecks.java`, `tests/agent/accept_standby_home.sh`. **Never open** `tests/agent/AgentCoreTest.java`
  or `src/xyz/erapi0neer/xingyi/LocalAdb.java`. No TV, no adb, no network.
- `MemoryCare.java` stays pure (Java 8, no android import); `MemoryGuard.java` is Android-side and may not gain imports.
- A false positive is worse than the bug: the rule must never put the desktop back while something the user is watching
  was in front at screen-off. Say so in the javadoc.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed`, N at least 5043
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_standby_home.sh` → `STANDBY HOME OK`
- The real TV run is the **lead's** (a hand test on the home screen is the only proof that counts here; it is written
  into NEXT.md as `standby-home-real` and runs after the next install).
