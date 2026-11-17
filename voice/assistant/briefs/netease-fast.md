# Brief: netease-fast — keys through the TV's own remote service, 伴奏 brings the player page back, a sleeping TV wakes for a song

## Why (measured on the TV on 2026-11-10 01:44–01:47, with the user's go-ahead)
1. **Keys are slow.** Every key 星仪 presses goes through `input keyevent` over the loopback ADB shell, and each such call
   starts a Java program on this CPU: ~0.85 s before the key lands. 「切换到伴奏模式」's seven keys took 9.5 s on 11-09.
   The TV ships its own remote-control service (what Xiaomi's phone remote uses) on port 6095:
   `GET http://<TV's LAN IPv4>:6095/controller?action=keyevent&keycode=<name>` answers
   `{ "status":0, "msg":"success", "data":{} }` in **31–58 ms** and the key is injected like a remote press (the
   window manager logs `Virtual KeyEvent` DOWN, then UP ~185 ms later). Measured working names: `down`, `right`, `up`,
   `home`, `enter` — and `enter` toggles NetEase's 随心唱 mic button like OK (label flipped 开启随心唱 → 按 上 键调节人声 →
   back, both times within 1.2 s). The whole 随心唱 path (down, right ×4, up) took 2.2 s instead of ~9.5 s. The service
   listens on the LAN address only (`::ffff:192.168.31.162:6095`), **not** on 127.0.0.1.
2. **伴奏 sent the model to NetEase's home page** (fixed in words by front-truth; now in deed). With NetEase's process
   alive, `nmcloudmusictv://ng/player` (no RESOURCE_ID) through `TvRedirectActivity` brings **the playing song's
   player page** back in 0.15–0.2 s, keeps the position, and skips NetEase's ~4.5 s launch page (with a RESOURCE_ID,
   or through `voice://…/search`, NetEase always goes through its launch page).
3. **A song asked for while the TV sleeps never starts.** NetEase's launch page (`CommentCalenderActivity`, a logo on a
   see-through background, ~4.5–5.7 s) does not move on while the screen is off: on 11-09 20:11 the song sat there for
   17 s until the user said 「屏幕打开」. Pressing OK on that page does not skip it — it played a different song (so never
   press a key while it is up). The user wants music to stop when the screen goes off (2026-11-10), so a song asked
   for on a sleeping TV wakes the screen first, exactly as `openBiliVideo` already does for B站 videos.

## What to build
1. **`src/xyz/erapi0neer/xingyi/agent/KeyNames.java`** (new, pure): `public static String of(int keyCode)` → the remote
   service's name: 19 `up`, 20 `down`, 21 `left`, 22 `right`, 23 `enter` (DPAD_CENTER: the service's `enter` is the OK
   key), 66 `enter`, 4 `back`, 3 `home`, 82 `menu`, 24 `volumeup`, 25 `volumedown`; **null for every other code**
   (26 POWER stays on `input keyevent`: its callers check the power state first). `public static boolean ok(String
   httpResponse)` → true only for an HTTP status line with 200 and a JSON body whose `status` is 0 (the body may be
   spread over lines, as above); false for anything else, null, empty, broken JSON.
2. **`src/xyz/erapi0neer/xingyi/agent/tv/RemoteKeys.java`** (new): `static boolean press(String name)` sends one key.
   The TV's LAN IPv4 from `NetworkInterface` (an up, non-loopback interface's site-local `Inet4Address`), cached 60 s.
   **Use a plain `java.net.Socket`**, not `HttpURLConnection`: the bridge's manifest says `usesCleartextTraffic="false"`
   (on purpose; leave it) and Android would refuse a cleartext `http://` URL. Write
   `GET /controller?action=keyevent&keycode=<name> HTTP/1.0\r\nHost: <ip>:6095\r\nConnection: close\r\n\r\n`, read at
   most 4 KB, `KeyNames.ok(...)`. Connect timeout 800 ms, read timeout 1500 ms. Any failure → false, and the service is
   not tried again for 60 s (a remembered "down until" time). Never throws.
3. **`TvTools.Adb.keys(int... codes)`**: when every code has a `KeyNames.of` name and there are at most 4 codes, send
   them one by one with `RemoteKeys.press`, 250 ms apart (each press is held ~185 ms by the service), then wait 200 ms
   so the last key's UP has landed; if a press fails, send that key and all after it the old way (one
   `a.keyEvents(rest)` call, as today). More than 4 codes, or any code without a name → exactly as today (one
   `input keyevent` call, back to back). Nothing else about how tools press keys changes; `micKeys` keeps its gaps.
4. **`src/xyz/erapi0neer/xingyi/agent/tv/ScreenWake.java`** (new): `public static boolean ensureOn(Context c,
   java.util.function.BooleanSupplier cancelled) throws Exception` — screen already on → true; else the power key once
   through a fresh `LocalAdb` (`LocalAdb l = new LocalAdb(c); l.connect(); l.keyEvents(new int[]{KeyEvent.KEYCODE_POWER});`
   then `l.cancel()` in a finally — the same calls TvTools makes; do not open `LocalAdb.java`), then wait up to 4 s
   for `PowerManager.isInteractive()` (200 ms steps, stop early when `cancelled` says so). The bridge will call this
   too (a later job), so keep it free of TvTools. Use it in `openBiliVideo` in place of its inline wake block (same
   behaviour, same test-source rejection before it).
5. **Wake before NetEase**: `netease_play` (both the title path and `playArtist`), `netease_playlist` and
   `replayParked` call a small helper first: screen off and `t.source()` is `test` → `fail("rejected", "这是电脑上的静音
   测试，电视在待机：不点亮屏幕放歌，如实说这一步测试时跳过。")`; otherwise `ScreenWake.ensureOn(context, t::cancelled)`; when it woke
   the screen, the tool's message gets 「（先点亮了屏幕）」 appended, as openBiliVideo does.
6. **`netease_karaoke` brings the player page back**: when `playerInFront` (front-truth) is false but NetEase has a session
   that is playing or paused, start `new Intent(Intent.ACTION_VIEW, Uri.parse("nmcloudmusictv://ng/player"))
   .setComponent(<TvRedirectActivity of NetEase>).addFlags(FLAG_ACTIVITY_NEW_TASK)` (there is a `NETEASE_VOICE`
   ComponentName for that activity already — check it is `com.netease.cloudmusic.tv/.activity.TvRedirectActivity`), wait
   up to 3 s for the player page to be in front (Eyes, then `playerInFront` once more), then carry on with the key path.
   Only with no NetEase session at all, or when the page still is not there, reject — with `Karaoke.notInFront(...)`,
   whose session text now says to use netease_play only when the page could not be brought back.
7. Keep every message Chinese and short; keep the rest of the tool code as it is.

## Checks (in `tests/agent/SkillChecks.java`, a new group `remoteKeys()` called from its `run()`)
- `KeyNames.of` for each mapped code above, and null for 26, 0, 85, 126, 999, -1.
- `KeyNames.ok` on the real reply (`HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\n"status":0,\n"msg":"success",
  \n"data":{}}`), on status 1, on HTTP 404 with a status-0 body, on a reply with no body, on broken JSON, on "", on null.
- `Karaoke.notInFront` still names netease_play and still warns about open_app.
- At least 20 new checks.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network in anything you run (RemoteKeys is never called by the checks).
- Android API 28, Java 8: `java.util.function.BooleanSupplier` is fine (API 24+); no `Socket` call on the main thread
  (tools run on the task thread already).

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 3809
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_netease_fast.sh` → `NETEASE FAST OK`
