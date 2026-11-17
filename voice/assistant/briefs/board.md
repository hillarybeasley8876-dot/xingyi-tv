# Brief: board — 一页状态板：在跑什么、卡在哪、消耗多少、怎么停

## Why (the user's yardstick: 人能掌控, 资源有边界; and what happened 2026-11-10/11)
Across the nights the user asked 「好了吗」 several times and there was no answer but a paragraph I wrote by hand:
which worker jobs were running, how many rounds each had burned, what had passed acceptance but not been merged, and
what was waiting on the TV. The facts were all on disk — `voice/assistant-out/jobs/<id>/state.json` holds status, rounds,
per-round tool counts, commit and the worker's last words — but reading them meant a `node tools/m31w/run.mjs status` dump
of seventy lines, most of it ancient.

## What to build

### 1. `tools/board/board.mjs` — the board's logic (the lead wires it into `run.mjs`)
`tools/m31w/` is the lead's, so the worker cannot put a file there. Write the logic as
`tools/board/board.mjs` with `export function board(states, opts)` → the markdown string; it is pure — it takes the job
states it is given and returns text. **The lead adds the `board` branch to `tools/m31w/run.mjs`** (a one-line import
plus reading `$SKYRUSH_OUT/jobs/<id>/state.json` and writing the file); do not edit `run.mjs`.

`node tools/board/board.mjs --out <path> [--since <hours>] [--jobs <dir>]` must also work on its own, reading the
states from `$SKYRUSH_OUT/jobs` (or `--jobs`), writing `--out` (default `status/BOARD.md`) and printing its path: that
is what the acceptance runs, and what the lead's `run.mjs board` will call. Content:

```markdown
# 状态板 2026-11-12 02:30

## 在跑
| 单 | 第几轮 | 这个会话已用工具次数 | 开始 | 最后一句（截 80 字） |
| verify-durable | 1/6 | 12 | 02:16 | … |

## 等合并（过了验收，我还没合）
| 单 | 完成于 | 提交 | 验收 |
| reported | 01:58 | — | needs_lead：台账闸门 REPORTED OK，核心检查数达不到我在 spec 里写错的那个数 |

## 今天完成并已合并
| 单 | 提交 | 轮数 | 工具次数 |

## 卡住 / 需要我出手
- 一条一行：`<id>  <status>  <为什么>`（`needs_lead`、`failed`、或 `running` 超过 90 分钟未更新）

## 消耗
- 今天：N 个单、R 轮、T 次工具调用（按每轮 `history[].tools` 汇总）；最费的一次：`<id>`（M 次）
- 上限：同时在跑最多 2 单（超了就写在这里）
```
Rules: a job is 在跑 when its status is `running`; 等合并 when `accepted_mechanical` and its commit is not reachable from
the current `HEAD` of the main repository (`git merge-base --is-ancestor <commit> HEAD` in `--repo`); 完成 when it is
reachable; 卡住 for `needs_lead` / `failed` / `outage`, and for `running` whose `state.json` has not changed in 90 minutes.
`--since` limits the last two tables to jobs finished that recently (default 24 hours). With no jobs at all the file says
`没有在跑的、没有等合并的。` instead of empty tables.

### 2. `NEXT.md` — its head points at the board
`briefs/board.md` is the lead's, but `NEXT.md` is not: the lead will add the line. Write in your report the exact line
the lead should put under the title: `状态：见 status/BOARD.md（node tools/m31w/run.mjs board 重新生成）`.

### 3. Checks (`tests/agent/BoardChecks.java`, at least 12, run from the last line of `SkillChecks.run()`)
The formatting is where the mistakes would be, so it is a pure function and a Java port cannot drift:
the logic lives in `tools/board/board.mjs` (`export function board(states, opts)` → the markdown string) and its CLI
mode writes the file. Checks in Java read fixtures — three `state.json` samples under `tests/agent/board-fixtures/`
(`tests/agent/fixtures/` is the lead's, use the new directory; running with history, accepted with a commit that is not
in `HEAD`, needs_lead) — through the same rules:
`tests/agent/BoardChecks.java` checks a **Java copy of the rules** (`Board.java`, pure: `markdown(List<Job> jobs, long now,
int hours)` where `Job` is `{id, status, rounds, maxRounds, tools, updatedMs, commit, merged, last}`) — the markdown shape,
the five sections in order, a job in 等合并 when `!merged`, 卡住 for `needs_lead` and for `running` older than 90 minutes,
`--since` filtering, the empty case, and that `board.mjs` and `Board.java` agree on the format string (check the literal
`## 在跑` and the table headers appear in both files' text).
At least 12 checks.

## Rules
- Only the files you own: `tools/board/board.mjs` (new; `tools/m31w/` is the lead's), `tests/agent/BoardChecks.java`
  (new), `src/xyz/erapi0neer/xingyi/agent/Board.java` (new, pure), `tests/agent/board-fixtures/*.json` (new),
  `status/BOARD.md` (generated), and the single line at the end of `tests/agent/SkillChecks.run()`. Never open or grep
  `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`. Do not touch `tools/m31w/run.mjs`,
  `NEXT.md`, `HARNESS.md`, the bridge. No TV.

## Acceptance (run all of them yourself before you finish)
- `node tools/board/board.mjs --out build/BOARD-test.md` → the file has the five sections and a real job's id
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed`, N ≥ 5022 (the job file's pattern allows for both)
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_board.sh` → `BOARD OK`
