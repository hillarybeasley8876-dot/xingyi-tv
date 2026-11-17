# Brief: song-verify — the bridge never calls the song that was already playing a success

## Why (2026-11-08 14:21 on the TV, from `files/bridge-log.txt`)
The user said 「我要听你要的全拿走」. The bridge's song path (`Takeover.song` → `verifySong`) found 胡彦斌《你要的全拿走》
(id 529668356, a confident pick) and sent NetEase the play intent. NetEase had been killed in the background and started
cold: it first resumed the song it had before (蔡依林/周杰伦《布拉格广场》) and switched to the new one only later. So:
```
14:21:22.670  小爱 点歌 529668356 胡彦斌《你要的全拿走》 score=320 ← 我要听你要的全拿走
14:21:38.556    原版没有播放(playing 蔡依林/周杰伦《布拉格广场 (单元剧《上班女郎》主题曲)》 看我72变)，改用网易云搜索「你要 全拿走」
14:21:38.649    ✓ 网易云正在播放 蔡依林/周杰伦《布拉格广场 (单元剧《上班女郎》主题曲)》
```
Four things went wrong:
1. After 9 s + 6 s the requested song had not shown up yet, so the bridge fell back to NetEase's keyword search — while
   the right song was still on its way (it played a little later). The search queued other versions (a cover by 祺夜
   played after it).
2. The fallback keywords were 「你要 全拿走」: `keywords(it)` split the title at its 「的」 as if 「你要」 were a singer.
   With a confident pick, the pick's own singer and title are what to search for: 「胡彦斌 你要的全拿走」.
3. `verifyAny` (and the keyword step of `verifySong`) took **the song that was already there** as success 0.1 s later:
   the log said ✓, a toast on the TV said 「正在播放 蔡依林/周杰伦《布拉格广场》」, and 星仪's history recorded it.
4. `done(np)` calls `engine.played(np.artist, np.title)`, which writes the history line 「播放<artist>的<title>」 as if
   the user had said that; the model then reads those made-up words in `recent_turns`. `AgentEngine` now has
   `played(String said, String artist, String title)` (the user's words; already in the 星仪 tree): use it.

## What to build
1. **`src/xyz/erapi0neer/xiaoaibridge/SongArrival.java`** (new, pure Java, no `android.*`): the decision whether what
   NetEase reports now is the request arriving.
   - `static boolean arrived(NowPlaying before, NowPlaying now, String wantedTitle, String keywords)`:
     false when `now` is null or not playing. With a `wantedTitle`: the titles are the same song (use the same rule as
     `Takeover.sameTitle`: `MusicResolver.norm(MusicResolver.baseTitle(..))`; move it here and have `Takeover` call it).
     Without one (a keyword search, an artist, 私人 FM, 我喜欢的音乐): true unless `now` is the same song as `before`
     (same title by that rule and same artist, ignoring case and spaces) — and even then true when the keywords name
     that song or its singer (the user asked for what was there; normalised `keywords` contains the normalised title or
     artist).
   - `static String fallbackKeywords(String pickArtist, String pickTitle, String intentKeywords)`: with a pick,
     `pickArtist + " " + pickTitle` (just the title when the artist is empty); otherwise the intent's keywords.
2. **`Takeover`**:
   - When a request is taken (before any play intent), read `NeteasePlayer.nowPlaying()` once and keep it as `before`
     for this generation (null is fine: nothing was there).
   - `waitFor` and every success test go through `SongArrival.arrived(before, now, wanted, keywords)`.
   - `verifySong`: first wait **14 s** (a cold NetEase start resumes its old song first; the 11-08 case needed more
     than 9); while it waits, if NetEase reports the **before** song playing, that is not a failure: keep waiting. Then
     send the play intent once more and wait **8 s**. Only then fall back to the keyword search, with
     `SongArrival.fallbackKeywords(pick.artist, pick.title, keywords(it))`, and wait 9 s for something that `arrived`.
   - `verifyAny`: success only through `arrived` (so the old song never counts); 12 s.
   - The log line on success stays `  ✓ 网易云正在播放 <artist>《<title>》`; on a fallback that played a different
     version than the pick, add `（不是原来那版）` after it.
   - `done(...)` gets the user's words and calls `engine.played(text, np.artist, np.title)`; thread `text` from
     `handle` through `song` / `artist` / the FAVORITES-FM-DAILY branch.
3. **`tests/SongArrivalTest.java`** (new, in the same style as `tests/LocalAdbStrayTest.java`: a `check` helper,
   prints `PASS n passed, 0 failed (SongArrival)`, exits 1 on a failure): at least 16 checks, including the 11-08 case
   (before 布拉格广场 paused; now 布拉格广场 playing; wanted 你要的全拿走 → false; keywords 「胡彦斌 你要的全拿走」 → false),
   the right song arriving (with a 「 (Live)」 or 「（伴奏）」-free title variant the old rule accepted), nothing before,
   paused now, the user asking for the very song that was paused (keywords name it → true), an artist run whose first
   song is a different one (true), the same song with another artist (a cover: false for `arrived` without keywords?
   no — different artist means a different recording: true), null inputs, and `fallbackKeywords` with and without a pick.

## Keep
Everything else in `Takeover` (the hand-over to 星仪, `described`, the B站 routing, `fail` handing the request to 星仪).
`NowPlaying` stays as it is. `tests/run_all.sh` picks the new test up by its name.

## Acceptance (run all of them yourself before you finish)
- `sh build.sh` → the last line names `xiaoai-bridge.apk`
- `sh tests/run_all.sh` → `ALL JVM TESTS PASSED` and `PASS n passed, 0 failed (SongArrival)` with n ≥ 16
- `sh tests/accept_song_verify.sh` → `SONG VERIFY OK`

## Files you own
`src/xyz/erapi0neer/xiaoaibridge/Takeover.java`, `src/xyz/erapi0neer/xiaoaibridge/SongArrival.java`,
`tests/SongArrivalTest.java`.
