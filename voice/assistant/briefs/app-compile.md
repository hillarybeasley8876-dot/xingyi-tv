# Brief: app-compile — an app the system could not compile is compiled in standby

## Why (the TV, 2026-11-10)
B站 took **61 s** from its link to its first page; the first 27 s its process logged nothing. `dumpsys package dexopt`
said `[tv.danmaku.bili] … arm: [status=run-from-apk] [reason=unknown]`: its code had never been compiled since it was
installed on 11-04, so every start unpacked and verified 38 dex files (263 MB). The system's nightly compile died each
time after 95 s (`dex2oat` is a 32-bit process here; its compact-dex step keeps all dex data in one buffer and ran out
of address space: `std::bad_alloc`). The lead compiled it by hand with that step off — 4 min 14 s, status
`speed-profile` — and the same link reached the page in **15 s**. After the next update of B站 (or any big app) it would
be back to `run-from-apk`, so 星仪 looks after it itself, in standby.

The lead added two fixed operations to `LocalAdb` (do not open that file):
- `String dexoptStates()` — the text of `dumpsys package dexopt` (read-only). A real one, from before the hand
  compile, is `tests/agent/fixtures/dexopt-before.txt`. Each package is a block:
  ```
    [tv.danmaku.bili]
      path: /data/app/tv.danmaku.bili-2h0Myns6YBr_VhoBdfuxvQ==/base.apk
        arm: [status=run-from-apk] [reason=unknown]
  ```
  (a package can have several `path:` lines — splits — each followed by its own status line).
- `boolean compileApp(String pkg)` — compiles one app with the compact-dex step off; true when the package manager
  said Success. **Takes minutes** and blocks its caller.

## What to build
1. `src/xyz/erapi0neer/xingyi/agent/AppCompile.java` (new, pure, no android.*):
   - `public static Map<String, String> uncompiled(String dexoptText)` → package → the `path:` of its base APK, for every
     package that has a path whose status is `run-from-apk` (LinkedHashMap, in the order of the text). Anything
     unreadable → an empty map.
   - `public static String next(Map<String, String> uncompiled, Set<String> userPackages, JSONObject tried, long now)` →
     the package to compile now, or null: only packages in `userPackages` (what the user installed; system apps are
     never touched), never one of 星仪's own (`xyz.erapi0neer.` prefix), and not one whose **same path** was tried within
     the last 7 days (`tried` is `{ "<pkg>": {"path": "...", "at": <ms>, "ok": true|false} }`; a new path — the app was
     updated — is tried again at once). The first that qualifies.
   - `public static JSONObject tried(JSONObject before, String pkg, String path, long at, boolean ok)` → the record with
     that entry added or replaced (a new object; at most 20 entries, the oldest dropped).
   - `public static String line(String label, boolean ok, long tookMs)` → the sentence for the guard's log:
     「给哔哩哔哩做了一次系统代码优化（用了 4 分钟），以后打开它会快很多」 / 「想给哔哩哔哩做系统代码优化，没成功（7 天后再试）」.
2. `MemoryGuard`: a standby step next to the park, in `tick()` after `park(now, offFor)`:
   - only when the screen has been off for at least 30 minutes, the last compile check was more than 6 hours ago (or
     never since this process started), no compile is running, and `available(app)` is at least 600 MB;
   - on a **thread of its own** (a 4-minute call must never run on the heartbeat; `tick()` returns at once): read
     `once(app, LocalAdb::dexoptStates)`, `users()`, the record `files/agent/compiled.json`; `AppCompile.next(...)`;
     nothing to do → just remember the check time; else `once(app, a -> a.compileApp(pkg))`, write the record
     (`AppCompile.tried`), and `note(System.currentTimeMillis(), AppCompile.line(label(app, pkg), ok, took))` so
     tv_status can say why the TV was busy. One package per check. Every exception is swallowed (and counted as not ok
     when it came from the compile).
   - `plan()` (the dry run that `AgentDebug MEMGUARD --es mode plan` prints) gets one more field `compile_would`: the
     package `AppCompile.next` would pick right now, or "" — read-only, it compiles nothing.
   - Keep `tick()`'s own work under a few milliseconds when there is nothing to do; `users()` and the record file are
     only read on the compile thread or in `plan()` (mind the `synchronized` methods: the compile thread must not hold
     the guard's lock while it compiles).

## Checks (`tests/agent/MemoryCareChecks.java`, a new group `compiling()` called from its run())
- `uncompiled` on the fixture: exactly `tv.danmaku.bili` → its base.apk path; on "" and null → empty; a block with two
  `path:` lines where only the second one's status is run-from-apk → that package, mapped to **the second path** (the
  one whose status is run-from-apk).
- `next`: B站 in userPackages → B站; not in userPackages → null; tried 3 days ago with the same path → null; tried 8
  days ago → B站; tried yesterday with another path → B站; a `xyz.erapi0neer.` package → never.
- `tried`: adds, replaces, keeps at most 20.
- `line`: both sentences, minutes rounded (254 s → 4 分钟; under a minute → 「不到 1 分钟」).
- At least 16 new checks.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. Java 8 / Android API 28; org.json's JSONException is checked on Android.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 4059
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_app_compile.sh` → `APP COMPILE OK`
