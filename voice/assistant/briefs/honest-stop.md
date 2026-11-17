# Brief: when a task stops early, say truthfully what was done and what is on the TV now

## Why
2026-11-04 22:58, on the TV: "让星仪打开B站搜索星辰旅行者，播放最新的一个视频". The agent got there — the video was
playing — but it spent its 16th model round on a `screen_read`, the loop stopped at the round limit and 星仪 said
"这件事比预想的复杂，我先停下了，已经做到：看一下屏幕。说“星仪继续”可以接着做。" Two things are wrong:
1. "已经做到：看一下屏幕" — `Ledger.lastSuccess()` returns the last successful action, and reads (screen_read, wait_for,
   media_now_playing…) succeed all the time. The last thing that changed the TV was "点选「…示例小镇…」".
2. Nothing about what is on the TV now, although a video was playing. The host can tell (front app, playing media); the
   core cannot, so the host gets a hook.
Also every round ran exactly one tool, so 16 rounds were too few for a three-step task.
The transcript of that run: `tests/agent/evidence/bili-latest-2026-11-04/transcript.json` (read it).

## What to build
1. `Ledger`: actions record whether they only read. Replace `action(String tool, String what, String status, boolean ok)` with
   `action(String tool, String what, String status, boolean ok, boolean read)` and update its callers (all in `AgentLoop`).
   The action JSON gets `"read": true` for reads only (no key for the others, so old files and the model's view stay as they
   were). `lastSuccess()` returns the `what` of the last action with ok=true that is not a read; "" if none. `toJson` /
   `fromJson` keep the flag.
2. `AgentLoop`: pass `gate.risk == Tool.Gate.Risk.READ` as `read`. The META tools (plan, plan_update, ask_user, finish) are
   not recorded as actions; keep it so.
3. `AgentLoop.Limits`: `rounds` default 16 → 20 (calls 36, failures 3, millis 150 000 unchanged).
4. An optional observer of the TV for that stop reply:
   - `public interface Observer { String now() throws Exception; }` nested in `AgentLoop`;
   - `public void observe(Observer o)` sets it (null clears) — a setter, so the constructor and its callers stay unchanged;
   - when the loop stops because of rounds / deadline / message count (the only use of `stoppedReply`), the reply is
     `"这件事比预想的复杂，我先停下了"` + (`"，已经做到：" + done` when done is not empty) + `"。"` +
     (`"现在" + seen + "。"` when the observer is set and returns a non-empty string without throwing) + `"说“星仪继续”可以接着做。"`;
     `seen` is trimmed and one trailing `。` dropped first. The observer is called at most once per run; an exception from it is ignored.
5. `Tool.Turn` gets `void say(String text);` — a short line the host may speak right now (the host decides who hears it).
   The Turn in `AgentLoop.run` implements it as `emit("say", task, text)`; blank text emits nothing. Add `say` to the event
   list in the `Listener` javadoc.

## Checks
`tests/agent/StopChecks.java` (an empty stub; `AgentCoreTest.main` calls `StopChecks.run()`). Use `AgentCoreTest`'s helpers
(`Script`, `Fake`, `Events`, `loop`, `o`, `plan`) and `AgentCoreTest.check`. At least:
- a run that hits the round limit (20 rounds) where the last non-read success is a click and reads come after it: the reply
  contains the click's description and not the read's; with an observer returning `前台是哔哩哔哩，正在播放《示例小镇》。` the
  reply contains `现在前台是哔哩哔哩，正在播放《示例小镇》。` (one 。) and the observer was called exactly once;
- observer throwing, returning "" or null, or not set → no `现在` in the reply;
- no successful non-read action → no `已经做到` in the reply;
- `new AgentLoop.Limits()` defaults: rounds 20, calls 36, failures 3, millis 150 000;
- a Fake tool whose `run()` calls `turn.say("我查一下")` → the listener saw a `say` event with that text; `say("  ")` → no event
  (give your check its own small `Listener` that records type and text);
- Ledger round trip (`toJson` → `fromJson`) keeps the read flags and `lastSuccess()`; `forModel()` shows `"read": true` on reads only.
The existing `roundLimit()` check in `AgentCoreTest` encodes 16 rounds (`read.runs.size() == 15`). Update that one number to the
new limit (19) and its label: the only existing check you may change, and only that.

## Acceptance
- `sh tests/agent/run_core.sh` prints `agent core: N passed, 0 failed` with N ≥ 156 (144 before your job).
- `sh tests/agent/android_check.sh` prints `ANDROID CHECK OK`.
- The signatures above exist verbatim (the runner greps for them).
