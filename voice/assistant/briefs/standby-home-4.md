# Brief: standby-home-4 — 判据换成「熄屏后第一个到前台的包」，从系统的使用记录里读

## Why (真机三次事故 + 电视自己的事件日志，2026-11-11 20:06 与 2026-11-12 10:05/10:11)
`standby-home` 和 `standby-home-2/3` 都没修好这件事，因为三次都量错了地方：

- `standby-home`：看「桌面的进程死没死」当旁证。10:05 固件没杀桌面（pid 还在），规则判「不用管」，浏览器照样顶上来。
- `standby-home-3`：改成在 `ACTION_SCREEN_OFF` 广播里读前台。10:11 装完的版本把答案写进了 `MEMGUARD` 状态：
  `"front_at_off":"com.phlox.tvwebbrowser"`、`"home_back_done":true` —— **广播到达时前台已经是浏览器了**，
  固件在广播之前就完成了「杀桌面 → 浏览器浮上来」。信号本身晚了，不是采样频率问题。

电视自己的使用记录（`adb shell dumpsys usagestats`）里有精确的事件顺序，三次事故一模一样：

```
10:05:20 MOVE_TO_FOREGROUND xyz.erapi0neer.orrery   HomeActivity     ← HOME 之后桌面在前
10:05:23 MOVE_TO_FOREGROUND com.phlox.tvwebbrowser  MainActivity     ← 熄屏那一刻浮上来的
10:05:23 SCREEN_NON_INTERACTIVE android
10:05:23 MOVE_TO_BACKGROUND com.phlox.tvwebbrowser
（20:07:53 同型；20:06:05 那次浮上来的是 com.xiaomi.mitv.tvplayer/.ExternalSourceActivity）
```

所以判据是：**熄屏前后那一两秒里，第一个到前台的应用是谁**。它不是桌面 → 用户开电视会看到它 → 把桌面放回去。
用户看视频然后关电视时，视频被固件杀掉、系统重新拉起的是桌面，第一个到前台的就是桌面 → 什么都不做
（这是**假阳性比漏报更糟**的地方：不能把用户正在看的东西盖掉）。

## What to build

### 1. `MemoryCare.java`（纯）— 一条新规则
```java
/** 熄屏的那一刻浮上来的是谁：{@code events} 里时间在 [offAt - 1000, offAt + 10000] 之内、类型是 FOREGROUND 的
 *  最后一个事件的包名；没有就给 ""。 */
public static String surfacedAfter(java.util.List<Usage.Event> events, long offAt)
/** 要不要把桌面放回去：{@code surfaced} 非空、不等于 {@code home}。 */
public static boolean homeBack(String surfaced, String home)
```
`Usage.Event` 已经有 `at` / `type` / `pkg` 三个字段（同一个包，字段是包内可见），`Usage.FOREGROUND == 1`。
`-1000` 的容差是因为系统写这两条事件的先后在同一秒内可能颠倒；`+10000` 是因为浮上来的应用偶尔晚几秒才记上。
旧的 `homeBack(String frontAtOff, String focusedNow, String home)` **删掉**（只有 `MemoryGuard` 一个调用方，也在你的事里），
不要留两套判据。

### 2. `tv/MemoryGuard.java` — 在熄屏后的第一次心跳里读使用记录
- 删掉整条广播链：`screenReceiver`、`frontAtOff`、`screenChanged`、`readFrontAtOff`、`FRONT_READ_MS`、`dispose()`。
  它被真机证伪了（10:11 的 `front_at_off`），留着只会误导。
- `switches(Context, now)` 现在只收 `MOVE_TO_FOREGROUND` / `MOVE_TO_BACKGROUND`；**改成把 `SCREEN_OFF`（16）也收进来**
  （`Usage.SCREEN_OFF`；`UsageEvents.Event.SCREEN_NON_INTERACTIVE` 的常量是 16）。一小时窗口够用：
  屏幕现在是关的，这个窗口里最后一个 `SCREEN_OFF` 就是这一段的开始。
- `homeBack(now, offFor)`：`offFor >= HOME_BACK_OFF_MS` 的第一次（`homeBackDone` 还是那套每段一次）：
  读 `switches()`，取最后一个 `SCREEN_OFF` 的 `at`（没有 → `homeBackDone = true; return;`），
  `MemoryCare.surfacedAfter(events, offAt)`，再 `MemoryCare.homeBack(surfaced, homePackage())`；
  true → `homeOnTop(); note(...)`，false → `homeBackDone = true`。**不再读 `FrontApp.pkg` 当输入**（现在的
  `focusedNow` 那两句检查只在需要放回桌面时用来确认桌面确实不在前——可以保留，但它不再是判据）。
- `homeBackWould()`（`home_back_would`）用同一条规则、同样的 `switches()`。
- 调试字段：把 `front_at_off` / `home_back_done` 换成 `surfaced_after_off`（规则取到的包名，取不到就是 `""`）
  和 `last_screen_off`（那个事件的包名不是关键，`at` 不能直接念，给一个 `距离现在多少秒` 的整数 `screen_off_at_s_ago`）。
  这两个是 lead 在电视上验证时要读的，别删。

### 3. 检查 — `tests/agent/MemoryCareChecks.java`，至少 14 条新的
- 纯规则：`surfacedAfter` 在 10:05 的真实序列上给浏览器（桌面在前、浏览器在熄屏那一秒）→ true；
  在「视频在前、熄屏后系统拉起桌面」上给桌面 → false；窗口外的事件不算（熄屏前 5 秒的 MOVE_TO_FOREGROUND）；
  没有 FOCUS 事件 → `""` → false；`home` 为空 → false；同一个包两次 → 最后一个。
- 源码检查（像现有 `screenOffReceiver()` 那样读文件）：`MemoryGuard` 里 **没有** `screenReceiver` / `frontAtOff` /
  `registerReceiver`；`switches` 收 `SCREEN_OFF`；`homeBack` 调 `MemoryCare.surfacedAfter`；`home_back_would` 也调它；
  状态里有 `surfaced_after_off`。
- 把 `MemoryCareChecks.run()` 里对旧三参数 `homeBack` 的调用改到新签名。

### 4. `tests/agent/accept_standby_home.sh` — 跟着改 grep 词
它现在按旧老词（`frontAtOff`、`ACTION_SCREEN_OFF`、`registerReceiver`）查，会全红。改成查新规则的字面量，
并**加上「旧的广播实现已经不在」的否定检查**（`!src.contains("registerReceiver")` 这一类的 shell 版）。

## 事故的真实事件序（做 fixture 用）
```json
[{"at":1789000000000,"type":2,"pkg":"com.ktcp.video"},{"at":1789000003000,"type":1,"pkg":"xyz.erapi0neer.orrery"},
 {"at":1789000006000,"type":1,"pkg":"com.phlox.tvwebbrowser"},{"at":1789000006000,"type":16,"pkg":"android"}]
```
（`type` 用 1 = FOREGROUND、2 = MOVE_TO_BACKGROUND、16 = SCREEN_OFF；`at` 是任意毫秒，只要顺序和间隔对。）

## Rules
- 只改你自己的文件：`src/xyz/erapi0neer/xingyi/agent/MemoryCare.java`、`src/.../agent/tv/MemoryGuard.java`、
  `tests/agent/MemoryCareChecks.java`、`tests/agent/accept_standby_home.sh`。
- **不要打开** `tests/agent/AgentCoreTest.java`、`src/xyz/erapi0neer/xingyi/LocalAdb.java`。不碰电视、不碰 adb、不联网。
- `MemoryCare.java` 保持纯（Java 8，无 android import）；`MemoryGuard.java` 是安卓侧，可以少一个 import（BroadcastReceiver），
  不许加新的。
- 真机验证是 lead 的事：装完之后 `voice/assistant-out/tmp/lead/k1111/h1.py` 必须让桌面在几秒内回来，写进 `## Requests`。

## Acceptance（自己全部跑一遍）
- `sh tests/agent/run_core.sh` → 最后一行 `agent core: N passed, 0 failed`，N 至少 5538
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_standby_home.sh` → `STANDBY HOME OK`
