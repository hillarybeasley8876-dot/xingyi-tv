# Brief: habits — 星仪 learns the user's habits by itself and uses them

## Why
The user (2026-11-05): 「harness应该是自动整理用户习惯的 这些你要做好」. The `memory` job let the user say 「记住…」; this job
makes 星仪 learn without being told: which apps they open, whom they search on B站, which artists and songs they play,
the volume they settle on, which HDMI input they use, and at what time of day — and puts a short, honest summary into
every prompt, so vague requests resolve the way this user means them (「放首歌」「看看我的频道」「老样子」「音量调回平时的」).
Everything stays on the TV (`files/agent/habits.json`).

## What to build
1. `src/xyz/erapi0neer/xingyi/agent/Habits.java` (pure Java, no android.*). The file is a JSONObject
   `{"events": [{"t": epochMillis, "h": hour 0–23, "kind": "...", "value": "..."}, ...]}`.
   - kinds: `app` (open_app.app), `bili` (bili_search.keyword), `artist` / `song` (netease_play.artist / .title),
     `playlist` (netease_playlist: its kind/name argument), `volume` (set_volume.level, or the `volume` an adjust_volume
     result reports), `input` (switch_input.name). Nothing else is recorded (no web_search queries, no screen text).
   - `public static List<JSONObject> callsFromTranscript(JSONArray transcript)`: the loop's transcript is an OpenAI-style
     message list; for every assistant `tool_calls[i]` (id, function.name, function.arguments as a JSON string) find the
     `role: tool` message with the same `tool_call_id`; return `{name, args (JSONObject), ok, result (JSONObject)}` for the
     calls whose result has `ok: true`. Unparseable pieces are skipped, never thrown.
   - `public static List<JSONObject> events(String tool, JSONObject args, JSONObject result, long now, int hour)`: 0..2
     events for one successful call (netease_play with both artist and title gives two). Values trimmed; empty → none;
     volume values only 0–100.
   - `public static void record(JSONObject habits, List<JSONObject> events, long now) throws JSONException`: append;
     drop events older than 90 days; keep at most 400 (the oldest go).
   - `public static int forget(JSONObject habits, String what) throws JSONException`: removes events whose value contains
     `what` (case-insensitive, spaces ignored); returns how many. Empty `what` removes nothing.
   - `public static List<String> summarize(JSONObject habits, long now)`: at most 8 lines, each ≤ 40 characters, Chinese,
     from the last 30 days only, most telling first; nothing below the thresholds:
     - 常用应用：哔哩哔哩（12次）、网易云音乐（7次） — top 3 apps with ≥ 3 events each;
     - B站常搜：星辰亻旅行者（5次） — top 2 keywords with ≥ 2;
     - 常听：林俊杰（6次）、薛之谦（3次） — top 3 artists with ≥ 2; 常点的歌：《晴天》（3次） — top 2 songs with ≥ 2;
     - 常听歌单：我喜欢的音乐（4次） — ≥ 2;
     - 习惯音量：30 左右 — the median of ≥ 3 volume events, rounded to 5;
     - 常用信号源：HDMI2（4次） — ≥ 2;
     - a time band when a kind has ≥ 5 events and ≥ 60 % fall in one band: append 「，多在晚上」 to that kind's line; bands:
       凌晨 0–5, 早上 6–8, 上午 9–11, 中午 12–13, 下午 14–17, 傍晚 18–19, 晚上 20–23.
     Counts compare values after trimming; equal counts: the most recent first.
2. `src/xyz/erapi0neer/xingyi/agent/MemoryTools.java`: add `public static List<Tool> create(Store store, Store habits)`;
   the existing `create(Store)` becomes `create(store, null)`. With a habits store, `forget` also runs `Habits.forget`
   on it and saves it when something went; the habit records it removed are reported in `removed` as
   「使用记录：N 条」 and counted in the message. Everything else unchanged (all existing MemoryChecks keep passing).
3. `src/xyz/erapi0neer/xingyi/agent/tv/AgentEngine.java` (Android side, the host):
   - the engine's `Store` gets `synchronized JSONObject habits()` (load `habits.json`, an empty object when missing or
     broken) and `synchronized void saveHabits(JSONObject h)` (same `save` as profile.json);
   - `public void habit(String kind, String value)`: records one event now (for the XiaoAi bridge's own song path, which
     never reaches the loop). Never throws.
   - after a model task ends (where `store.saveTranscript(l.transcript())` is), record the events of
     `Habits.callsFromTranscript(l.transcript())`; after a quick command that ran (`runQuick`, the tool's real result), record
     its events. Hour = the TV's local hour (`Calendar`). Any failure here is swallowed: habits never break a task.
   - when a task starts, `req.profile` = the facts as today, plus — when `Habits.summarize` has lines — a blank line,
     「（以下是根据使用记录自动整理的习惯，可能不准）」 and the lines, each prefixed 「- 」.
   - `MemoryTools.create(...)` gets the habits store as its second argument (load = `store.habits()`, save = `saveHabits`).

## Checks
`tests/agent/HabitChecks.java` (an empty stub; `AgentCoreTest.main` already calls `HabitChecks.run()`). At least 40
labelled checks: `callsFromTranscript` on a hand-made transcript (two calls, one failed, one with bad JSON arguments),
`events` for every kind and the cases that give none (empty value, volume 150, an unrelated tool), `record` (90-day
cut, the 401st event drops the oldest), `forget` (case/space-insensitive, counts, empty what), `summarize` for every line
and threshold (below → no line), the time band (5 events, 3 in 晚上 → none; 4 of 5 → 「，多在晚上」), the 8-line and
40-character caps, and `MemoryTools.create(store, habits)`'s forget reporting 「使用记录：N 条」 while `create(store)` behaves
as before.

## Acceptance
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 554 (514 before this job).
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`.
- `sh tests/agent/accept_habits.sh` → builds the TV app with your sources (the XiaoAi bridge's `build.sh`, which also
  compiles `agent/tv`), runs the bridge's JVM suite and checks the wiring; prints `HABITS OK`.
