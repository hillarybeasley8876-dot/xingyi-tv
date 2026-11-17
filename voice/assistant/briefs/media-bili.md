# Brief: media-bili — B站's media session is read (the parser only knew sessions tagged 「MediaSession」)

## Why (the TV, 2026-11-10 23:52)
While B站 played a video, the engine's `MediaSessions.current(dump)` returned **null** for the TV's own
`dumpsys media_session` (saved as `tests/agent/fixtures/media-bili-playing.txt`). The session is there:
```
  Sessions Stack - have 1 sessions:
    HeadsetControlPlayAdapter.Session tv.danmaku.bili/HeadsetControlPlayAdapter.Session (userId=0)
      ownerPid=18915, ownerUid=10049, userId=0
      package=tv.danmaku.bili
      ...
      active=true
      ...
      state=PlaybackState {state=3, position=2103, buffered position=0, speed=1.0, updated=30616127, actions=847, …}
      ...
      metadata:size=9, description=动态视频｜2026，它来了…, 自动连播, null
      queueTitle=null, size=0
```
but `MediaSessions.parse` starts a session only at a line matching `^\s*MediaSession (\S+?)/` — NetEase's session
happens to be tagged `MediaSession`; B站's tag is `HeadsetControlPlayAdapter.Session`. So on the TV, for B站: `open_link`
never saw the video play (it waited its whole time and answered 「还在加载」 80–90 s after a link whose video had been
playing for a minute — the same link plays 31 s after it is sent), `media_now_playing` said 「没有正在播放的媒体」, the
environment had no `now_playing`, and the guard could not tell that B站 was playing.

## What to build (`src/xyz/erapi0neer/xingyi/agent/MediaSessions.java`)
A session block starts at **any** session header of the dump, whatever its tag: a line inside the sessions stack of
the form `<tag> <package>/<tag…> (userId=<n>)` (one tag token, a package name with at least one dot, a slash, anything
without spaces, then `(userId=N)` at the end of the line). The `package=` line inside the block still sets the package
(as today). Lines that merely mention a session — `Media button session is tv.danmaku.bili/… (userId=0)` (four words
before the package) and `Global priority session is null` — are not headers. Everything else about parsing stays as it
is (state, position, updated, metadata, queue, `Audio playback` ends the stack).

## Checks (`tests/agent/ScreenChecks.java`, a new group `mediaTags()` called from its run())
- `media-bili-playing.txt`: `parse` gives exactly one session — pkg `tv.danmaku.bili`, state `playing`, position 2103,
  active, title 「动态视频｜2026，它来了…」, artist 「自动连播」 — and `current` returns it.
- `tests/agent/fixtures/media-none.txt` (the TV with nothing playing, also from tonight): no sessions, `current` null.
- The existing fixture `tests/agent/fixtures/media_session.txt` and every existing MediaSessions check give what they
  gave before (run the suite: they are in ScreenChecks, MusicQueueChecks, MemoryCareChecks).
- Hand-made: two sessions with different tags (`MediaSession com.netease.cloudmusic.tv/MediaSession (userId=0)` paused
  and `HeadsetControlPlayAdapter.Session tv.danmaku.bili/HeadsetControlPlayAdapter.Session (userId=0)` playing) →
  two sessions, `current` is B站's; a `Media button session is …` line alone → no session; a header with a tag that has
  no dot and a package with digits and underscores (`Player com.example_app2.tv/Player (userId=10)`) → a session.
- At least 10 new checks.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. Java 8 / Android API 28 (no `Pattern.UNICODE_CHARACTER_CLASS`).
- The XiaoAi bridge compiles these sources; the acceptance script builds it and runs its JVM suite against your tree.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 4176
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_media_bili.sh` → `MEDIA BILI OK`
