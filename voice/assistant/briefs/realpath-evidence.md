# Brief: realpath-evidence — 每条真机证据都在检查器的规则下，每条证据都有能重跑它的脚本

## Why (2026-11-12 20:30, found while finishing the karaoke check)
`tests/agent/realpath/check.py` only looks at `tests/agent/evidence/real-*.json` — one level, no subdirectories. The
evening's real-TV evidence for the sleeping-TV and standby paths was written by hand into
`tests/agent/evidence/realpath/real-*.json`, so the checker has never seen it; and their `id`s (`ktcp-pause-real`,
`episode-open-real`, `ktcp-seek-real`, `standby-home-real`, `tencent-open-sleeping`) are the *unverified-list* names,
not scenario ids, so even in the right place they would fail `id == file name` and 「scenarios.json 里没有这个场景」.
The result: five real verifications that a fresh session cannot tell from never-run. That is the same failure the
`reported-2` job fixed for the ledger — evidence that exists but nobody can check is not evidence.

## What to build

### 1. `tests/agent/realpath/check.py` — 放错地方的证据要列出来，`--strict` 时算错
- After the existing loop over `tests/agent/evidence/real-*.json`: any `*.json` under
  `tests/agent/evidence/realpath/` gets one line each: 「WHERE real-<name>.json：放在 evidence/realpath/ 里，检查看不到
  （要放在 evidence/ 根下、文件名 real-<场景 id>.json）」.
- **A `WHERE` line is a notice, not a problem**: `problems()` keeps returning only real problems, so `check.py` prints
  `REALPATH OK` on today's tree. Add `check.py --strict`: with it the same lines become problems (exit 1, and the
  summary says 「加 --strict 时不认放错地方的证据」). This is what lets the lead move the files in a later step and
  then turn the strictness on without a half-broken tree in between.
- Everything else stays as it is (the registry rules, the six evidence rules, `REALPATH OK`).

### 2. Three scenarios that the missing evidence belongs to — `tests/agent/realpath/scenarios/`
Each in the existing shape: module docstring (preconditions, what it does, what counts as passed, what it restores),
`ID`, `check(r)` returning `(ok, notes, shots)`, `main()` that calls `p.refuse()`, runs `check` inside
`p.Restore(apps=[...])`, calls `p.evidence(ID, ok, notes, shots)` and returns `0` / `1` / `3`. Muted: `p.set_volume(0)`
at the start is NOT needed — `Restore` puts the volume back and the scenarios must not change it themselves; the lead
runs them at night with the TV's own volume, and nothing here plays audible content beyond a few seconds of a song the
scenario starts itself. (If you think a scenario needs silence, say so in `## Requests`.)
- `seek-one-minute.py` — id `seek-one-minute`: put 云视听极光 playing (the `episode-open` scenario's own way: the
  `tenvideo2://` link, as a silent-source `tvtask` 「我想看别无选择第5集」), then 「快进一分钟」 through `tvtask`; pause at
  the end, take the pause overlay, resume; the evidence notes hold the position before and after and the tool's own
  reply. Passed when the reply says 已快进 and the two positions differ by about a minute (the overlay text has
  `00:MM:SS` — parse it; if either overlay cannot be read, `ok` is false with the reason, never a guess).
- `standby-home.py` — id `standby-home`: the browser is put in front, HOME, screen off; every 2 s for 90 s read the
  resumed activity. Passed when the desktop is the resumed activity within 60 s (the guard's heartbeat is 20 s).
  Restores: browser force-stopped, screen back to the state it started in (asleep stays asleep).
- `sleeping-episode.py` — id `sleeping-episode`: refuses unless the TV is asleep; says 「我想看别无选择第5集」 through
  `xiaoai_say.py` (the real path — a silent-source request cannot wake the screen by design); passed when the screen
  is on within 15 s and `TVPlayerActivity` is in front within 45 s. Restores: player stopped, screen off again, the
  words removed from 星仪's history (`p.forget`).

### 3. `tests/agent/realpath/scenarios.json` — three entries
`seek-one-minute` (needs 亮屏、桌面在前、20 分钟没人动, hours 3), `standby-home` (needs 亮屏、桌面在前、20 分钟没人动,
hours 2), `sleeping-episode` (needs 待机, hours 2). All `unverified: ""`.

### 4. Checks — `tests/agent/RealPathChecks.java`, at least 8 more
- the registry now has 10 scenarios and holds the three new ids (update the count check from 7 to 10 and the id list);
- the new wrong-place rule: a sentence names `evidence/realpath/` and the file;
- each new scenario's script exists, has `main`, and names its own `ID` equal to the registry id (read the file's text
  for `ID = "<id>"`).
Keep every existing check passing.

### 5. `tests/agent/accept_real_path.sh`
Its `run.py --list` grep counts the six old ids (`grep -c 'panel-while-speaking\|…'` ≥ 6) — make it count the three new
ids too and require ≥ 9 (or list them from the registry; either is fine).

## Not in this job (the lead does it, because `tests/agent/evidence/` is lead-owned)
Moving the five existing files out of `evidence/realpath/` to `evidence/real-<scenario id>.json` with their `id` field
renamed (`ktcp-pause-real`→`pause-through-xiaoai`, `episode-open-real`→`episode-open`, `ktcp-seek-real`→`seek-one-minute`,
`standby-home-real`→`standby-home`, `tencent-open-sleeping`→`sleeping-episode`). Write in `## Requests` the exact
five `mv` + edit commands you verified against `check.py` (dry-run them on a copy under `build/`), so the lead can run
them as written.

## Rules
- Own only: `tests/agent/realpath/check.py`, `tests/agent/realpath/scenarios/*.py`,
  `tests/agent/realpath/scenarios.json`, `tests/agent/RealPathChecks.java`, `tests/agent/accept_real_path.sh`.
- **Never open** `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`. **No TV, no adb, no
  network** — the scenarios are for the lead to run; do not run them, do not even try (`p.refuse()` calls adb).
- `tests/agent/evidence/` is the lead's: touch no file there, not even to dry-run (use a copy under `build/`).

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed`, N at least 5624
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_real_path.sh` → `REALPATH OK`
- `python tests/agent/realpath/check.py` on the real tree prints `REALPATH OK` **and** five `WHERE` lines (one per file
  in `evidence/realpath/`); `python tests/agent/realpath/check.py --strict` exits 1 and names the same five.
