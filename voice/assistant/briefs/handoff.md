# Brief: handoff — 一页「停在哪、下一步先做什么」，由任务状态生成

## Why (the user's yardstick: 人能掌控; and every night this week)
`status/BOARD.md` answers 「现在是什么情况」 for the person who already knows the project — five tables of job states.
It does not answer the other question, the one that matters after a restart at 3 a.m. or to whoever picks this up next:
**what is the single next thing to do, what is stuck on a person rather than on code, and what must not be touched.**
That answer is written by hand today, in `NEXT.md`, and it goes stale the moment a job merges — the section at the top
of `NEXT.md` was written at 04:05 and described three jobs that had merged by 09:00.

The facts are on disk: `state.json` per job has status, rounds, `history[].cost`, `needs_lead`/`failed`, the worker's
last words, and the acceptance failures; `tests/agent/unverified.json` has what is installed but not yet seen on the
TV; `briefs/QUEUE.md` has the queue's prose but its ordering is prose too.

## What to build

### 1. `tools/handoff/handoff.mjs` — the page's logic (`tools/m31w/` is the lead's)
`export function handoff(states, opts)` → a markdown string, pure — it takes the job states it is given and returns
text. Reuse `tools/board/board.mjs`'s reading side by calling its own exported functions where they fit (import it;
do not copy its tables), and `tools/cost/cost.mjs` for money. The lead wires the CLI into `run.mjs` afterwards; do
not edit `run.mjs`.

`node tools/handoff/handoff.mjs [--out <path>] [--jobs <dir>] [--unverified <path>]` writes `status/HANDOFF.md`
(default), prints its path, and must work with `$SKYRUSH_OUT/jobs` and `tests/agent/unverified.json` as defaults.
Six sections, in this order, each always present (a section with nothing to say prints `（没有）`):

```markdown
# 接手：此刻停在哪（生成于 2026-11-12 11:20）

## 一句话
八单在凌晨合并、真机验证清空；现在没有在跑的单，也没有卡在我这里的。

## 下一步（按顺序，最上面的先做）
1. 装这一版 APK：`cd ../xiaoai-bridge && sh build.sh && sh bridge.sh install`（standby-home 的规则在桥里）
2. …

## 卡在人这里的
- ……（`needs_lead` / `failed` / 验收连红的单，一条一行：`<id>：<为什么>`；没有就「没有」）

## 还没在真机上验过的
- ……（`unverified.json` 的每一条；空就「一条都没有：装上的东西都在真机上见过」）

## 不要动
- `tests/agent/AgentCoreTest.java` 里 `speechChecks()` 附近那段（打印它会把会话拦停）
- `src/…/LocalAdb.java`、`assets/agent/contract.json`（lead 的）
- 装应用、改系统设置：要用户点头

## 今天花了多少
- ……（`cost.mjs` 的合计和不完全的部分，一句）
```

Rules:
- **一句话**: with jobs running → 「N 单在跑」; `needs_lead` present → 「M 单卡在人这里」; both empty and no unverified
  entry → 「没有在跑的、没有卡住人的、装上的东西都在真机上见过」. One sentence, never more.
- **下一步**: at most three items, each `1. <what> · <why one sentence> · <command>`. Sources, in this order:
  ① a job whose acceptance is red on a command (it needs a rerun) → 「重跑验收」; ② `unverified.json` non-empty →
  「上电视验 <first id>」 with the script path when the entry names one; ③ jobs merged since `--since` (default 24 h)
  与 APK 的时间戳相比更新的 → 「装这一版」; ④ nothing → 「没有必须现在做的：读 `HARNESS.md` 的最后一节」。
  When there are more than three, say how many are left.
- **卡在人这里的**: `needs_lead`, `failed`, `outage`, `wrong_model`, and `accepted_mechanical` more than 24 h not merged
  (a job the lead forgot). Each line carries the worker's last words, cut to 100 characters.
- **还没在真机上验过的**: one line per entry: `<id>：<feature>（要 <needs>；脚本 <script|没有>）`.
- **不要动**: a fixed list (the two files, installs need the user's word) — written here so it survives a context reset.
- **今天花了多少**: `cost.mjs`'s total for the window, plus its 「N 单没有成本，合计是下限」 sentence when it says so.

### 2. `Handoff.java` (new, pure, package `xyz.erapi0neer.xingyi.agent`) — the same rules in Java
`public static String page(List<Job> jobs, List<String> unverified, long nowMs, long sinceMs)` with
`Job {id, status, rounds, tools, updatedMs, merged, last, costUsd}`. Same six sections, same order, same sentences.
The Java side is what the checks read; it must produce **byte-identical text** for the same input as the JS one — the
checks prove that by running both on the same fixtures (the JS side through `node`, the way `accept_resume.sh` does).

### 3. Checks — `tests/agent/HandoffChecks.java`, at least 16, run from the last line of `SkillChecks.run()`
- the six sections in order, always present; empty case → the 「没有」 lines;
- 一句话 for: running only / needs_lead only / everything clear / both;
- 下一步 source order: a red job before an unverified entry before a stale install; at most three; the count line when
  there are more; nothing to do → the fallback sentence;
- 卡在人这里的 holds `failed` and a 25-hour-old accepted job, not a fresh one;
- 不要动 holds both file paths;
- 今天花了多少 says 「下限」 when a cost is missing;
- JS and Java agree byte for byte on the fixtures (skip with one line saying so when `node` is not on PATH).

### 4. Fixtures — `tests/agent/handoff-fixtures/` (the new directory; `tests/agent/fixtures/` is the lead's)
`states-empty.json`, `states-busy.json` (one running, one needs_lead, one accepted-but-unmerged 25 h old),
`states-clear.json`, `unverified-two.json`, `unverified-none.json`. Inputs only; no expected output files — the checks
carry the expected sentences.

### 5. `tests/agent/accept_handoff.sh` → `HANDOFF OK`
- `node tools/handoff/handoff.mjs --out build/HANDOFF-test.md` on the **real** job directory: exit 0, the six
  headings present, `status/HANDOFF.md` not written;
- the real run's 一句话 is one sentence (ends in `。` and holds no second `。`);
- `sh tests/agent/run_core.sh` → `0 failed`; `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`.

## Rules
- Own only: `tools/handoff/handoff.mjs`, `src/xyz/erapi0neer/xingyi/agent/Handoff.java`, `tests/agent/HandoffChecks.java`,
  `tests/agent/handoff-fixtures/`, `tests/agent/SkillChecks.java` (the one line at the end of `run()`),
  `tests/agent/accept_handoff.sh`, `status/HANDOFF.md` (generated).
- **Never open** `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`. No TV, no adb, no
  network. Do not edit `tools/m31w/`, `tools/board/`, `tools/cost/` — import them, never change them.
- `Handoff.java` is Java 8 and pure (no android import, org.json only).

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed`, N at least 5575
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_handoff.sh` → `HANDOFF OK`
