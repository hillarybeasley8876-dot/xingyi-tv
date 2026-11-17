# Brief: front-truth — XiaoAi's hidden panel is never "the app in front"; 伴奏 never sends the model to NetEase's home

## Why (the TV, 2026-11-09 evening, real use)
- The transcript of 「然后关闭显示器」 (20:41) shows what the model was told: `"foreground":"小爱同学（com.xiaomi.voicecontrol）"`,
  `"now_playing":"网易云 …"`. The user was on NetEase. Every sentence said to XiaoAi makes XiaoAi's own two
  APPLICATION_OVERLAY windows (hidden since 2026-11-08 with appops, `mAppOpVisibility=false`, but still sending
  accessibility events) fire `TYPE_WINDOW_STATE_CHANGED`. `Eyes.onAccessibilityEvent` takes the package of any such event
  as the new foreground, and the app behind sends no new event when XiaoAi goes quiet — so for every voice request the
  model hears that the user is "in 小爱同学".
- 「切换到伴奏模式」 (20:11:56): NetEase's player page was in front (system log: `PlayerActivityV2` resumed at 20:11:48.9,
  nothing else resumed until the agent's own open_app at 20:12:01). The agent opened NetEase (its home page,
  `MainActivity`), pressed BACK, played the same song again from the start, and only then switched: 105 s, 11 rounds.
  `netease_karaoke`'s own reject text also tells the model 「open_app 网易云音乐」 — which opens NetEase's home page, never
  the player.
- The window manager knows the real answer: `dumpsys window` → `mFocusedApp=… ActivityRecord{… u0 <pkg>/<activity> t5}}`
  is the activity with focus and is never an overlay. The lead added `LocalAdb.focusedApp()` (public, returns
  `"package/full.activity.Class"` or `""`, one loopback shell call). Do not open `LocalAdb.java`; call it like the other
  `LocalAdb::…` methods (`adb.call(LocalAdb::focusedApp)` in TvTools, `once(c, LocalAdb::focusedApp)` in MemoryGuard).

## What to build
1. **`src/xyz/erapi0neer/xingyi/agent/FrontApp.java`** (new, pure, no android.*):
   - `public static boolean counts(String pkg, boolean activity, String own)`: false for null/empty `pkg` and for `own`
     (our own package); for a package in `MemoryCare.OVERLAYS` (XiaoAi, the wake-up service, the system UIs, "android")
     true **only** when `activity` is true (a real full-screen activity of that package, e.g. XiaoAi's video
     recommendation page — the bridge closes that page and must keep seeing it); any other package: true.
   - `public static String pkg(String focused)` / `public static String cls(String focused)`: split `LocalAdb.focusedApp()`'s
     `"package/Class"`; `""` for null, empty or text without one `/` between two non-empty parts.
2. **`Eyes.onAccessibilityEvent`** for `TYPE_WINDOW_STATE_CHANGED` with a package: work out `activity` = the event's class
   is a real activity of that package (`getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0)` succeeds; any
   exception or a null class → false). Cache the answer per `pkg + "/" + cls` (a small map, at most 64 entries, cleared
   when full) — events come often. Only when `FrontApp.counts(pkg, activity, getPackageName())`: set `foregroundPackage`,
   and set `foregroundClass` / `foregroundClassAt` when `activity || looksLikeScreen(cls)` (today's rule). The event
   counters (`changes`, `lastChangeAt`) stay exactly as they are.
3. **`Eyes.capture()`**: take the root's package as the foreground only when `FrontApp.counts(pkg, false, getPackageName())`
   (an overlay package's window is never taken from a capture).
4. **`TvTools.foreground()`**: when Eyes has no package, ask `LocalAdb::focusedApp` (not `focusedWindow`) and use
   `FrontApp.pkg(...)`.
5. **`netease_karaoke`** in `TvTools` (only its precondition and its reject texts; the key path stays as it is):
   - when the Eyes-based check says NetEase's player page is not in front, ask once `adb.call(LocalAdb::focusedApp)`; if
     `FrontApp.pkg` is NetEase and `PlayerPages.isPlayer(NETEASE, FrontApp.cls(...))`, carry on as if the check passed.
   - otherwise reject with a text built by a new pure `Karaoke.notInFront(String where, String title, String artist)`:
     with a NetEase session (playing or paused, title known — use `nowPlaying()`), 「网易云的播放页不在前台（现在是<where>）。
     网易云在放《<title>》：先用 netease_play（title=<title>，artist=<artist>）重新打开这首歌的播放页（会从头放），再切伴奏或原唱；
     不要用 open_app，它打开的是网易云首页」 (leave out the artist part when it is empty); with no NetEase session,
     「网易云没有在放歌（现在是<where>）：先用 netease_play 放一首歌，再切伴奏或原唱」.
6. **`MemoryGuard.front()`**: use `LocalAdb::focusedApp` instead of `focusedWindow` and `FrontApp.pkg(...)` for the package.

## Checks (in `tests/agent/ScreenChecks.java`, a new group `front()` called from its `run()`)
- `FrontApp.counts`: voicecontrol not-activity → false, voicecontrol activity → true, com.android.systemui not-activity →
  false, com.xiaomi.mitv.systemui not-activity → false, NetEase not-activity → true, NetEase activity → true, own package
  activity → false, "" → false, null → false.
- `FrontApp.pkg / cls` on `com.netease.cloudmusic.tv/com.netease.cloudmusic.tv.activity.newplayer.v2.PlayerActivityV2`,
  on `xyz.erapi0neer.orrery/xyz.erapi0neer.orrery.HomeActivity`, on `""`, on `null`, on `garbage`, on `/x`, on `x/`.
- `Karaoke.notInFront`: with a title and artist it names netease_play, the title, the artist and says not to use open_app;
  with a title and no artist there is no empty 「artist=」; with no title it says 先用 netease_play 放一首歌.
- At least 20 new checks in all.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`
  (parts of them make the API refuse requests). No TV, no adb, no network.
- `Eyes` runs on the TV's main thread: no blocking calls there besides the cached PackageManager lookup.
- The bridge compiles these sources (`BridgeService` reads `Eyes.foregroundClass()` to close XiaoAi's video page): the
  acceptance script builds the bridge and runs its JVM suite against your tree.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 3689
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_front_truth.sh` → `FRONT TRUTH OK`
