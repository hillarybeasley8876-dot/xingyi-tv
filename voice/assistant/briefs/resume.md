# Brief: resume — 被杀掉的单接着跑，而且有证据

## Why (the user's yardstick: 恢复能力, one of the two he weighs most; and the failure of 2026-11-11)
The harness's runner dies with its chat session. Tonight that cost real money: `board-money` ran six rounds, four of
them killed by a typo in the acceptance path (spec `sh accept_cost.sh` where the script lives in `tests/agent/`;
fixed in 2331b50 + 3a53075 + de097ff, and `mkspec.py` now refuses to emit a bare name), and nobody noticed until the
job was at `needs_lead`. `run.mjs` *claims* to resume —
`if (st.rounds > st.history.length) st.rounds = st.history.length;` and `st.sessionStarted ? '--resume' : '--session-id'`
— but **no check has ever proved it**, and the rules are subtle enough that a one-line change could break them silently:
a round is committed only when the acceptance passes, so a killed round leaves a dirty worktree and a `state.json` whose
`rounds` counts a round that has no `history` entry.

## What to build

### 1. `tools/resume/rehearse.mjs` — a rehearsal with a fake worker, on a throwaway job
Drives the real `tools/m31w/run.mjs` (you may *spawn* it, never edit it) against a fake job and a fake `claude` binary:
- `--out <dir>` writes a fixture `jobs/<id>/state.json` and `briefs/jobs/<id>.json` under a scratch directory, and a
  stub executable (a `.cmd` on Windows is fine; it must be reachable through `$SKYRUSH_CLAUDE`) that appends
  `argv` and its stdin to a file and emits one `{"type":"result","subtype":"success","total_cost_usd":0.01}` line plus
  one text message, so `run.mjs` sees a finished round.
- `--kill-after <sec>` kills `run.mjs` mid-round (the stub sleeps), then `--resume` runs `run.mjs` again on the same
  state. `--report` prints what happened: which round each launch began with, whether the second launch passed
  `--resume`, what the first prompt of the second launch said, and the state's `rounds` / `history.length` at the end.
- The rehearsal must run with `SKYRUSH_OUT`, `SKYRUSH_WT` and `SKYRUSH_ENV` inside the scratch dir — **never** the
  real `voice/assistant-out` (other sessions' jobs live there). Say in the file's header that this is why.

### 2. `tests/agent/resume/check.py` — offline rules, the part CI runs
`python tests/agent/resume/check.py` (no TV, no network, no `run.mjs`):
- reads a rehearsal report (JSON, `--report --json`) and checks: a round that has no `history` entry is not counted as
  done (`state.rounds == history.length` after the restart); the continuation prompt is the `The lead resumes this job.`
  sentence when `sessionStarted` is true and the brief's own first prompt when it is false; a session whose recorded
  model differs from the job's gets `--session-id <new>` and *not* `--resume`; `rounds >= maxRounds` never starts a
  round past `maxRounds - 1` (it goes to `needs_lead`).
- prints `RESUME OK` (exit 0) or the problems (exit 1).

### 3. `tests/agent/ResumeChecks.java`, at least 12 checks, run from the last line of `SkillChecks.run()`
The rules above, each broken once, on report texts passed in (take the JSON text and a root, like `Reported.java`):
a round counted without a history entry; `--resume` used after a model change; the brief's first prompt used where the
continuation sentence belongs, and the reverse; `rounds` past the cap; a report that names no launch at all; the good
case. Also: `rehearse.mjs` exists and mentions `SKYRUSH_OUT` (a rehearsal that writes into the real jobs directory is
the one mistake that would hurt someone else's work).

### 4. `tests/agent/accept_resume.sh`
Runs `check.py`, then `node tools/resume/rehearse.mjs --kill-after 5 --resume --report --json` into `build/`, and
requires the report to show: the first launch began at round 1, the second launch **began at round 1 again** (the
killed round had no history entry), the second launch's prompt is the continuation sentence only if the state says
`sessionStarted`, and the final `history.length` equals the number of rounds the stub was actually asked to run.
Prints `RESUME OK`.

## Rules
- Only the files you own: `tools/resume/`, `tests/agent/resume/`, `tests/agent/ResumeChecks.java`,
  `tests/agent/accept_resume.sh`, and the single line at the end of `tests/agent/SkillChecks.run()`.
  **Never edit `tools/m31w/run.mjs`** (lead's). If a rule you must verify lives there and looks wrong, write it in your
  report — proving it is your job, changing it is mine. Never open or grep `tests/agent/AgentCoreTest.java` or
  `src/xyz/erapi0neer/xingyi/LocalAdb.java`. No TV, no adb, no network.
- The rehearsal must be safe to run while other jobs are running: its own `SKYRUSH_OUT` / `SKYRUSH_WT`, its own job id
  (prefix `rehearse-`), and it must delete its scratch dir at the end unless `--keep`.

## Acceptance (run all of them yourself before you finish)
- `python tests/agent/resume/check.py` → `RESUME OK`
- `sh tests/agent/accept_resume.sh` → `RESUME OK` (it runs the rehearsal; that is the part that must actually work)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed`, N ≥ 5458
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
