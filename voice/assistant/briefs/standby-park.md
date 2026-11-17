# Brief: standby-park — a media app paused for hours while the TV sleeps is closed, and 「继续播放」 brings the song back

## Why (measured on the TV, 2026-11-09 09:48, screen off since the night)
NetEase Music had been **paused** for about 19 hours (《你要的全拿走》, session state 2), yet it keeps creating and starting
AudioTracks (the track id changes every few minutes: 38799 → 38839) and, per /proc/<pid>/stat over 20 s, used
20.1 % of a core itself, plus media.codec 12.4 % and the audio HAL 8.6 % — about 0.4 of a core for nothing, plus
279 MB of the TV's 2 GB. A media STOP key does not change that (measured). The memory guard
(`MemoryGuard.tick`, `MemoryCare.guardStops`) only acts when available memory is below 350 MB, so it never helps here.
On this TV standby keeps the SoC on (XiaoAi listens), so this runs all night.

## What to build
1. **`MemoryCare` (pure, checked on the PC)**:
   - constants `PARK_SCREEN_OFF_MS = 30 min`, `PARK_PAUSED_MS = 2 h`;
   - `parkStops(List<MediaSessions.Session> sessions, long nowElapsed, long screenOffForMs, Set<String> thirdParty,
     Set<String> keep)` → the packages to close: only when `screenOffForMs >= PARK_SCREEN_OFF_MS`; a session whose
     package is in `thirdParty`, not one of 星仪's own packages (the set MemoryCare already has), not in `keep`, whose
     state is paused or stopped (never playing or buffering), and whose `updated > 0` and
     `nowElapsed - updated >= PARK_PAUSED_MS` (unknown time: leave it). Sorted, no duplicates.
   - `parkSays(List<String> labels, long pausedMs)` → one line for the guard's record, e.g.
     「电视待机中，网易云音乐暂停了 19 小时：关掉了它（暂停时它还在耗电、占着内存）」.
2. **`MemoryGuard` (TV glue)**: in `tick()`, independent of the memory figure but behind the same off switch and the same
   never-throw rule: keep `screenOffSince` (elapsed clock; 0 while the screen is on, set when first seen off); at most
   every 5 minutes, when the screen has been off ≥ 30 min, read the sessions (`MediaSessions.parse(once(app,
   LocalAdb::mediaSessions))`), compute `parkStops`, and when there is something: write
   `files/agent/parked.json` (per package: pkg, title, artist, at = wall clock), stop the apps the way the guard already
   does (`once(app, a -> { a.stopApps(list); return null; })`), and `note(...)` the `parkSays` line. Add
   `"park_would_stop"` (and the screen-off minutes) to `plan()` so the PC can check the choice without waiting.
3. **`TvTools` media_control play**: when the play key leaves nothing playing, no session plays or is paused, and
   `parked.json` has an entry from the last 24 hours for NetEase, play that song again the way `netease_play` does
   (`Legacy.playNetease(context, title, artist-or-null, t::cancelled)`), remove the entry, and say so in the result:
   「网易云在待机时关掉了，重新放《<title>》（从头开始）」. Nothing else about media_control changes.
4. **Checks in `tests/agent/MemoryCareChecks.java`** (already run by the core checks), at least 10: screen off 20 min →
   nothing; off 40 min and NetEase paused 3 h → NetEase; paused 1 h → nothing; playing → nothing; buffering → nothing;
   星仪's own package paused 3 h → nothing; a package not in thirdParty → nothing; `updated <= 0` → nothing; a package in
   keep → nothing; two parked apps sorted; `parkSays` names the app and the hours.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` (parts of it make the API refuse requests).
- No TV, no adb. Android: no `Pattern.UNICODE_CHARACTER_CLASS`; JSON strings read with `isNull` first; nothing on the
  heartbeat may throw (catch `Exception | LinkageError` as the guard does).

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → its last line `agent core: N passed, 0 failed` with N ≥ 3596
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `grep -c parkStops src/xyz/erapi0neer/xingyi/agent/tv/MemoryGuard.java` → at least 2 (tick and plan)
