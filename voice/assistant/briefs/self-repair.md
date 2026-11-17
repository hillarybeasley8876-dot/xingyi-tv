# Brief: self-repair — 自检不只报告，能修的当场修好并再验一遍

## Why (the user, 2026-11-12 01:30: 「主要是harness目前的问题是 他修不了系统的问题」)
Tonight the panel did not come up and 星仪 said, three times in a row, 「自检正常：模型、天气、本机调试、读屏都没问题」.
`self_check` only *probes*: it never fixes anything, and two of the things it calls fine can be broken in ways it does not
see (the panel's window not adding, the accessibility service listed but not bound).

The repairs that are possible from inside the bridge (it holds `WRITE_SECURE_SETTINGS` and can drive the TV over its own
loopback ADB) were measured on this TV:

| symptom / probe fails | the repair | how it is checked afterwards |
|---|---|---|
| 读屏: `Eyes.get() == null` (the service is listed but not bound, or was dropped) | `LocalAdb.rebindAccessibility(<component>)` (already in `LocalAdb`, lead's; do not edit that file) | `Eyes.get() != null` within 8 s (poll 500 ms) |
| 面板: `eyes.hud().probe()` false | `eyes.hud().drop()` then `probe()` once more | `probe()` true |
| 本机调试: `LocalAdb.connect()` throws while `adb_enabled` reads 0 | `Settings.Global.putInt(ADB_ENABLED, 1)` with the bridge's own `WRITE_SECURE_SETTINGS` (the ADB guard writes it the same way, `AdbWatch`) | `LocalAdb.connect()` succeeds within 6 s |
| 小爱的卡片: `adb.call(LocalAdb::focusedWindow)` never shows `com.xiaomi.voicecontrol` but its overlay is visible (the appop has been reset) | `adb.xiaoaiOverlays(false)` | `appops get com.xiaomi.voicecontrol SYSTEM_ALERT_WINDOW` says `ignore` |

Measured: the rebind brought the service back in 1.5 s (2026-11-12 01:33, by hand on the TV).

What is **not** fixable from there, and must be said as such instead of "正常": anything needing root, the firmware's own
decisions (it force-stops the app in front at every screen-off unless whitelisted), a system app that crashes, and ADB
switched off by the firmware when `WRITE_SECURE_SETTINGS` is missing.

## What to build

### 1. `Repairs.java` (new, pure, package `xyz.erapi0neer.xingyi.agent`)
```java
public static final String EYES = "eyes", PANEL = "panel", ADB = "adb", XIAOI = "xiaoi";
/** The steps that mend this symptom, in the order they are tried; more than one when the first may not be enough. */
public static List<String> plan(String symptom)
/** What a step is called to the user: 「读屏服务重新连上」 / 「面板窗口重新加上」 / 「ADB 调试写回打开」 / 「小爱的卡片又关掉」. */
public static String stepSays(String step)
/** 「修好了：读屏服务重新连上、面板窗口重新加上。修不了的：小爱的卡片（要重启电视）」 */
public static String report(List<String> fixed, List<String> unfixable)
/** 「自检正常：模型（备用网关，0.6 秒）、天气、本机调试、读屏、面板都没问题。」 — unchanged shape from TvTools today, but
 *  made here so a check that was repaired says so: {@code mended} lists the ones that were broken and are fine now. */
public static String normal(List<String> ok, List<String> mended)
```
`plan` (symptom → steps): `EYES` → `["rebind-eyes"]`; `PANEL` → `["reset-panel"]`; `ADB` → `["enable-adb"]`;
`XIAOI` → `["hide-xiaoai"]`. An unknown symptom → an empty list. `stepSays` for each of the four steps and `""` for an
unknown one.
`report(fixed, unfixable)`: with both empty → `"没有要修的：一切正常。"`; fixed only →
`"修好了：读屏服务重新连上。"`; unfixable only → `"修不了的：小爱的卡片（要重启电视）。"` (the parenthetical is the user's
next step, see below); both → `"修好了：…。修不了的：…。"`.
The unfixable entry's text is the symptom's name plus what the user can do, and `Repairs.cannot(String symptom)` gives it:
`EYES` → `读屏服务（要重启电视）`, `PANEL` → `面板（重启电视后恢复）`, `ADB` → `ADB 调试（要在设置里的账号与安全里重新打开）`,
`XIAOI` → `小爱的卡片（要重启电视）`.
`normal(ok, mended)`: `"自检正常：" + ok joined by 、 + (mended.isEmpty() ? "" : "，其中 " + mended joined by 、 + " 刚才坏了、已经修好") + "。"`.

### 2. `tv/SelfRepair.java` (new, package `xyz.erapi0neer.xingyi.agent.tv`, Android side)
```java
/** Checks the four things above and, when one fails, does what Repairs.plan says and checks again.
 *  @return {"ok":[…], "mended":[…], "broken":[…], "unfixable":[…], "say":"…"} — the labels in Chinese as above. */
static org.json.JSONObject run(TvTools tools) throws Exception
```
- The component for the rebind: `new android.content.ComponentName(c, Eyes.class).flattenToShortString()`, `c = tools.context`.
- `eyes`: `Eyes.get() != null` → ok. Otherwise, if the rebind ran and `Eyes.get() != null` within 8 s → `mended`;
  if the rebind threw or the service did not come back → `unfixable`.
- `panel`: only when `Eyes.get() != null` and `eyes.hud() != null`; `probe()` → ok; else reset and probe once →
  `mended`; still false → `unfixable`. When there is no HUD, `panel` is not checked at all (nothing to say).
- `adb`: `LocalAdb.connect()` on a `new LocalAdb(c)` → ok; on failure, if
  `c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)` is not granted → `unfixable`
  immediately (nothing can be done from here); otherwise `Settings.Global.putInt(c.getContentResolver(),
  Settings.Global.ADB_ENABLED, 1)` and connect again (500 ms apart, at most 6 s) → `mended`, else `unfixable`.
- `xiaoi`: `adb.call(LocalAdb::overlayHidden)` — the lead has just added it (true when XiaoAi's overlay appop is
  `ignore`, i.e. its cards stay hidden). hidden → ok; not hidden → `adb.call(a -> { a.xiaoaiOverlays(false); return
  null; })`, read again → `mended`, else `unfixable`.
- Every ADB or settings call is wrapped: an exception makes that check `unfixable` with its `Repairs.cannot` text, never
  an exception out of `run`.
- `say` = `Repairs.normal(ok, mended)` when `broken` and `unfixable` are both empty, else `Repairs.report(mended,
  unfixable)` with the broken ones that are not fixable added to `unfixable`.

### 3. `tv/TvTools.java` — tool `self_check` only
After the model, weather and adb checks that are there now, call `SelfRepair.run(this)` and fold its result in: the
panel line replaces the `面板` line added tonight; the eyes line becomes `SelfRepair`'s (`ok` / `mended` / `unfixable`).
The tool's message becomes `SelfRepair`'s `say` when anything was mended or is unfixable; otherwise the message is what
it is today with `Repairs.normal` in place of the hand-built string. `problems` in the result counts `broken` +
`unfixable`. Nothing else in that file; no new `import` lines.
`eyes: true` in `STATE` keeps meaning `Eyes.get() != null`.

### 4. `assets/agent/contract.json` is the lead's — do not touch it. The lead adds the self-repair line.

## Checks (`tests/agent/…`, at least 40 in all)
- `RepairsChecks.java` (new; `AgentCoreTest` is off limits, so call it from `SkillChecks.run()` — the last line of that
  method, `RepairsChecks.run();` — the file you already own): `plan` for the four
  symptoms and an unknown one; `stepSays` for the four steps and an unknown one; `cannot` for the four; `report` for the
  four combinations above (exact strings); `normal` with and without `mended`, and that `normal` keeps the shape
  「自检正常：…。」.
- `tests/agent/SkillChecks.java`: a group that checks `TvTools.java` wires `SelfRepair.run(this)` into `self_check` and
  that `SelfRepair.java` exists with `static JSONObject run(TvTools`.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`
  (`LocalAdb.rebindAccessibility(String)`, `LocalAdb.xiaoaiOverlays(boolean)`, `LocalAdb.connect()` are called the way
  the file already calls `enableAccessibility` / `xiaoaiOverlays`).
  No TV, no adb, no network. Java 8 / Android API 28.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N at least 40 more than before you
  started (the job's check requires the number in the job file)
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_self_repair.sh` → `SELF REPAIR OK`
