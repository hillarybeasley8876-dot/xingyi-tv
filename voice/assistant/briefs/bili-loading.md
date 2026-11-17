# Brief: bili-loading — a B站 video page that is still loading is the answer; nobody pokes at it

## Why (the TV, 2026-11-10 23:10, 「播放影视飓风最新的视频」 with B站 not running)
`open_link` reached the video page and waited 25 s for playback, then returned 「已用哔哩哔哩打开视频的播放页，还没读到播放状态」.
The video did start by itself a little later — but the model had gone on: `media_now_playing` (「没有正在播放的媒体」),
a plan, a click on 「展开更多」, OK (which opened the UP's page), BACK, play/pause (which paused the video that had
started meanwhile). The transcript is the evidence. On this TV a cold B站 plays 33–55 s after the link (the page is
there after ~15 s); B站 starts the video itself, nothing has to be pressed.

## What to build
1. `TvTools.openBiliVideo`: the wait for playback after the player page is up is **45 s** on a cold start (25 s now);
   warm stays 8 s.
2. When the player page is up and playback was still not seen after that wait, the result is
   `ok("observed", "已用哔哩哔哩打开这个视频的播放页，视频还在加载，稍等就会自己开始播放")` (with the 「（先点亮了屏幕）」 note
   where it applies, as today) and carries **`"loading": true`** and a `note`: 「B站会自己开始播放：不要点屏幕、不要按键
   （确认键是暂停），直接把这句话告诉用户」.
3. `AgentLoop`: such a result is the answer, like a link that was seen playing. `playedLink(name, result)` stays as it
   is; add `static boolean loadingLink(String name, JSONObject result)` (open_link, ok, `loading` true) and use it where
   `playedLink` is used to end the task with the tool's own sentence (line ~699: `ANSWERS.contains(n) || playedLink(…)`),
   and wherever else `playedLink` decides that the round's result is final (search the file for `playedLink`). `spoken`
   for open_link: with `playing` → 「正在播放《…》」 as today; with `loading` → the message.
4. Do not add `import` lines to TvTools.java (another job edits the same file).

## Checks (`tests/agent/BiliChecks.java`, next to the existing `playedLink` check, in a new group `loading()` called from
its run())
- `loadingLink("open_link", {ok:true, loading:true})` true; without `loading`, with `ok:false`, or for another tool →
  false; `playedLink` unchanged.
- In the loop (use the helpers the other loop checks in this file use — a scripted model and a fake `open_link` tool):
  a task whose `open_link` returns ok + `loading:true` + the message ends after that one round, state ANSWERED or DONE,
  with the message as the reply, and the model is **not** asked for another round; the same with `playing` still ends
  with 「正在播放《…》」; a plain `ok` open_link (a page, no `loading`, no `playing`) still goes back to the model.
- At least 7 new checks.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` (parts of it make the API refuse requests;
  look at how other `*Checks.java` files in `tests/agent/` build a loop) or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. Java 8 / Android API 28.
- If `python tests/agent/prompts_doc.py --check` fails after your change, run `python tests/agent/prompts_doc.py`.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 4140
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `python tests/agent/prompts_doc.py --check` → `PROMPTS.md is up to date`
- `sh tests/agent/accept_bili_loading.sh` → `BILI LOADING OK`
