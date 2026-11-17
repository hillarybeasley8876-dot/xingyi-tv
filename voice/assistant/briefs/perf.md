# Brief: perf — a cacheable prompt, a timing trace for every task, and B站 started while the model plans

## Why
The user (2026-11-05): 「harness也要优化 性能上」. Measured from the PC on the TV's network (tests/agent/latency_probe.py):
a model round on the gateway is 1.6–2.6 s when the prompt prefix is cached (5018 of 5020 tokens cached) and ~3.4 s
when it is not. The engine appends 「## 关于用户」 (facts + the habit summary, whose counts change after nearly every
task) to the SYSTEM message, so the cached prefix breaks almost every time. Nobody can say where a slow task spent its
time either: there is no timing at all. And a cold B站 needs ~15 s, which today only starts after the model's first
round (~2 s).

## What to build
1. `AgentLoop.java` (pure core):
   - the system message is exactly `contract.system` — nothing appended; when `req.profile` is not empty the FIRST user
     message's JSON object (the `input` built in `run`, which already carries utterance/environment/previous_task/
     recent_turns) gets the key 「关于用户」 with `req.profile` as its value. Nothing else about the messages changes.
   - timing: after every model call `emit("timing", task, json)` with `{"kind":"model","round":r,"ms":m,"calls":n}`
     (n = tool calls in that reply), and after every tool call `{"kind":"tool","name":tool,"ms":m,"ok":true|false}`; `ms`
     comes from the loop's own `clock` (tests use a fake clock), as `JSONObject.toString()`. A failure to build the JSON
     never breaks the loop.
2. `src/xyz/erapi0neer/xingyi/agent/Perf.java` (pure):
   - `public static JSONObject summary(List<JSONObject> timings, long wallMs, long firstSpeechMs)` →
     `{"rounds", "model_ms" (sum), "first_round_ms", "max_round_ms", "tools_ms" (sum), "slowest_tool", "slowest_tool_ms",
     "wall_ms", "first_speech_ms"}` (first_speech_ms −1 when nothing was said);
   - `public static JSONObject rollup(List<JSONObject> summaries)` → medians over the given summaries:
     `{"tasks", "median_wall_ms", "median_first_round_ms", "median_round_ms" (over all rounds' average per task),
     "median_first_speech_ms" (tasks that spoke only)}`; empty input → `{"tasks":0}`.
3. `AgentEngine.java` (Android side, the host):
   - `onEvent` case `"timing"`: parse the JSON and keep it in a per-task list (the task being run; at most 200 entries);
   - first speech: the first time `speak(...)` actually speaks for the running task, remember ms since the task started;
   - when a task ends (where `recordHabits` is called, and at the end of `runQuick`), append one line to
     `files/agent/perf.jsonl`: `{"at", "utterance" (first 40 chars), "source", "quick": true|false, "summary": Perf.summary(...)}`;
     keep the last 300 lines; never throw;
   - `state()` gets `"perf": Perf.rollup(last 20 summaries)` (read from the file, cheap: it is small);
   - B站 started early: right after the quick path declines a request and before the model's first round, when the
     utterance mentions B站/b站/哔哩/bilibili (case-insensitive) and the foreground app is not B站 (`tools.foreground()`),
     start B站's launch intent without waiting (the same way `open_app` starts an app; reuse TvTools' helpers). The model
     then plans while B站 cold-starts; `bili_search` already waits for it.

## Checks
`tests/agent/PerfChecks.java` (an empty stub; `AgentCoreTest.main` already calls `PerfChecks.run()`). At least 30 checks:
the system message equals the contract's system text exactly with and without a profile; the profile appears under
「关于用户」 in the first user message (and not when it is empty); timing events for a scripted two-round task with two
tools (kinds, rounds, names, ok flags, ms from the fake clock); a tool that throws still gets a timing event with
ok=false; `summary` (sums, max, slowest tool, first round, wall, first speech, −1); `rollup` (medians for odd and even
counts, tasks that never spoke excluded from the speech median, empty input).

## Acceptance
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 696 (666 before this job).
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`.
- `sh tests/agent/accept_perf.sh` → builds the TV app with your sources (the XiaoAi bridge's `build.sh`, which also
  compiles `agent/tv`), runs the bridge's JVM suite and checks the wiring; prints `PERF OK`.
