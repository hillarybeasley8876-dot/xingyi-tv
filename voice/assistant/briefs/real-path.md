# Brief: real-path — 真实路径的探测进仓库，和静音测试分开标

## Why (the user's yardstick: 反馈真实; and the failure of 2026-11-11 20:03)
Silent-source checks (`tvtask.py` with `source=test`) **cannot** exercise an action: the engine refuses tools with side
effects while the TV sleeps, on purpose. So a claim like 「暂停已验证」 resting on them proved nothing — on the TV the
real utterance 「暂停」 did not pause at all (XiaoAi's own voice agent paused it, 星仪's key toggled it back 164 ms
later). Tonight's real-path probes exist only in `voice/assistant-out/tmp/lead/k1111/` — a scratch directory that is
not in the repository, is not runnable by anyone else, and leaves no evidence file behind.

## What to build: `tests/agent/realpath/`

### 1. `probe.py` — the TV helpers, moved in and trimmed
From `voice/assistant-out/tmp/lead/k1111/session8.py` and `k1110/probe.py` (both are readable): `sh`, `tv_clock`, `awake`,
`resumed`, `remote`, `session`, `shot`, `wait_for`, `run_tool` (the `tvtask.py` / `xiaoai_say.py` runner), `state` (the
`STATE` broadcast, parsed), `setting` (read a secure setting), and the fixed path of `adb.exe`
(`H:/tools/platform-tools/adb.exe -s 192.0.2.10:5555`), with a module docstring saying it drives the real TV.
No `main()` in it; every scenario is its own script.

### 2. `scenarios/<id>.py` — one file per real-path scenario, each with `main()` returning 0 (passed) or 3 (could not run)
Each: states its preconditions in the docstring; refuses to run when the TV is asleep, when something other than the
home screen is in front, or when `mLastUserActivityTime` is under 20 minutes (someone is using it) — returning 3 and
saying why; restores what it changed (volume, apps it started, the XiaoAi history it added through `FORGET_CHECKS`);
leaves the home screen in front; and writes `tests/agent/evidence/real-<id>.json`:
```json
{"id": "panel-while-speaking", "at": "2026-11-12T02:40:11+08:00", "ok": true,
 "notes": ["panel up 1.4 s after the first partial words", "…"], "shots": ["probe-out/…png"]}
```
The six scenarios (each one a check of tonight's changes that only the TV can make):
- `panel-while-speaking` — a silent task, a screenshot 3 s in: the panel holds the sentence (the HUD is up). Passes when
  the screenshot's window list (`dumpsys window windows | grep xiaoaibridge`) holds the overlay while the task runs.
- `panel-self-check` — `self_check` in the panel sense: the tool's answer names 面板 and the HUD window appears.
- `black-screen-home` — the test app (package from `OwnApps`) put in front, then 「屏幕黑了」 through `tvtask.py`; the
  home screen has to be in front afterwards.
- `hint-card-not-a-switch` — read the hints setting (`profile.json`'s `hints`, through `STATE`), say
  「你看我现在每次给你提示提示提示提示提示」, read it again: it must not have changed.
- `episode-open` — 「我想看别无选择第5集」 (test source): the pause overlay names 第5集 (screenshot), then resume.
- `pause-through-xiaoai` — an episode playing, 「暂停」 said through `xiaoai_say.py`: the audio service's state for the app
  has to be `paused` 3 s later.
Keep each under three minutes; the whole set is meant for one sitting.

### 3. `scenarios.json` — the registry
```json
{"scenarios": [
  {"id": "panel-while-speaking", "what": "说话时逐字上屏", "needs": ["亮屏"], "script": "tests/agent/realpath/scenarios/panel-while-speaking.py",
   "hours": 3, "unverified": ""}
]}
```
`id` unique kebab-case · `what` one line · `needs` preconditions · `script` exists · `hours` how long it should take
(1–10) · `unverified` the id in `tests/agent/unverified.json` this scenario closes (may be `""`).

### 4. `check.py` — offline: the only part CI runs
`python tests/agent/realpath/check.py` (no TV):
- every scenario in `scenarios.json` has a script that exists and a `main` in it;
- every `tests/agent/evidence/real-<id>.json` that exists parses, has `id` equal to its file name, an `at` that is an
  ISO date not in the future, a boolean `ok`, and a `notes` array;
- for every evidence with `"ok": false`, `scenarios.json`'s `unverified` for that id must name an entry that is in
  `tests/agent/unverified.json` (the "failed verification comes back as a to-do" link);
- prints `REALPATH OK` (exit 0) or the problems (exit 1).

### 5. `python tests/agent/realpath/run.py --list` / `--only <id>` / `--all`
Prints what each scenario needs and how long it says it takes; `--only` / `--all` run them (on the TV), one at a time,
and print the evidence file's `ok`. It refuses to run two at once and stops the set at the first `3` (could not run).

### 6. Checks (`tests/agent/RealPathChecks.java`, at least 14, run from the last line of `SkillChecks.run()`)
`check.py`'s rules, each broken once, on texts passed in (give `check.py` functions that take the JSON text and the
root, the way `Reported.java` does): a scenario without a script; a script without `main`; evidence with a missing
`id`; an `at` in the future; `ok` not a boolean; `notes` not an array; a failed evidence whose `unverified` is `""`; a
failed evidence whose `unverified` names an id that is not in `unverified.json` (that file's shape: read only the
`entries[].id`s, tolerate it missing → a problem saying so); and the good case → no problems.

## Rules

- Only the files you own: everything under `tests/agent/realpath/`, `tests/agent/RealPathChecks.java`, and the single
  line at the end of `tests/agent/SkillChecks.run()`. **`tests/agent/evidence/` is the lead's** — the scenarios write
  `real-<id>.json` there when someone runs them on the TV; you check that the shape is right, you do not write one.
  Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
- **Do not run anything on the TV.** Another job is about to use it; the scenarios are for the lead to run. The
  acceptance has no TV step at all.
- Do not touch `tests/agent/unverified.json` (another job owns it), `src/`, the bridge, `NEXT.md`, `HARNESS.md`.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 5010 + the number your checks add
  (the job file's pattern allows for both)
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `python tests/agent/realpath/check.py` → `REALPATH OK`
- `python tests/agent/realpath/run.py --list` → lists six scenarios and touches nothing
