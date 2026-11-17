# Brief: reported — 用户报的每个问题配一条可执行检查，并证明这条检查在修复前不存在

## Why (the user's yardstick, 2026-11-11; and the failure it caught hours ago)
The user's standard for a harness: 反馈真实 — 完成声明要有证据；最看重验证和恢复。On 2026-11-11 21:12 – 01:30 it failed
that: 星仪 said 「自检正常」「面板是好的」 four times while the panel did not come up, and this project's own claim
「暂停已验证」 rested on silent-source tests that cannot exercise the action. The gap is not a lack of checks —
`run_core.sh` runs 4884 of them — but that **no user-reported problem is tied to a named check, and nothing shows that
check was absent before the fix**. A check nobody has ever seen missing proves nothing.

## What to build

### 1. `tests/agent/reported.json` — the ledger, one entry per thing the user reported
```json
{"entries": [
  {"id": "hint-card-switched-by-a-complaint",
   "said": "你看我现在每次给你提示提示提示提示提示",
   "when": "2026-11-11",
   "symptom": "说的是右上角的面板，set_hints 却把亮屏提示卡关了",
   "check": {"file": "tests/agent/HintChecks.java", "label": "Hints.switchAsked"},
   "fix": "833d99c",
   "before": "833d99c^",
   "verified": "test",
   "evidence": "",
   "unverified_because": ""}
]}
```
Fields: `id` kebab-case and unique · `said` the user's words · `when` `YYYY-MM-DD` · `symptom` one sentence ·
`check` = `{file, label}` where `label` is a piece of text that appears in `file` (a `check(...)` label or a method name
is fine — the rule below looks for it literally) · `fix` a 7-character commit of this repository · `before` a git
revision where the check must **not** be there (`<fix>^` unless another revision is truer) · `verified` one of
`test` / `real` / `none` · `evidence` a path under `tests/agent/` (required when `verified` is `real`, must exist) ·
`unverified_because` a sentence (required when `verified` is `none`).

The eight entries to start with. For each, open the file named and take a label that is really there; if the check does
not exist yet, still write the entry with `verified: "none"` and
`unverified_because: "检查还没写（self-repair 单在做）"`, and leave `check.label` as the name the check should have
(it will be reported until the check exists — that is the point of the ledger).

| id | when | said (真话) | check file | fix | verified |
|---|---|---|---|---|---|
| `pause-not-paused-said-to-xiaoai` | 2026-11-11 | 暂停 | `SkillChecks.java`（jiguangNeedsKey / jiguangSettled 那组） | 5ad6439 | none（真实播放里还没验） |
| `panel-never-came-up` | 2026-11-11 | 你为什么面板跳不出来 | `SkillChecks.java`（面板 probe / listening 那组） | c2ea46d | none（说话中间逐字上屏没验过） |
| `self-check-blind-to-panel` | 2026-11-12 | 自检全是问题啊 | `SkillChecks.java`（self_check 里接了面板探测那条） | c2ea46d | test |
| `panel-taken-for-an-app` | 2026-11-11 | 为什么现在HARNESS面板没有打开 | `DispatchChecks.java`（asksOnly） | 833d99c | test |
| `black-screen-left-open` | 2026-11-11 | 你看现在屏幕就是错的呀什么都没有 | `QuickChecks.java`（blank() 组） | 833d99c | none（真机验过，证据没存进仓库） |
| `hint-card-switched-by-a-complaint` | 2026-11-11 | 你看我现在每次给你提示提示提示提示提示 | `HintChecks.java`（Hints.switchAsked） | 833d99c | test |
| `tv-sleeps-onto-another-app` | 2026-11-11 | （20:07 复现：熄屏后亮屏是浏览器） | `MemoryCareChecks.java`（homeBack 组） | 102cf35 | none（还没在真机上复现第二次） |
| `tencent-search-over-1mb` | 2026-11-11 | （搜索结果超过 1 MB 被当「没连上」） | `MovieChecks.java`（4 MB 上限那条） | d70ddb5 | test |

### 2. `Reported.java` (new, pure, package `xyz.erapi0neer.xingyi.agent`)
```java
/** Everything wrong with the ledger, as sentences; empty when it is sound.
 *  root is the repository root (tests/agent/… paths are read under it), nowMs the clock (so a future date is caught). */
public static java.util.List<String> problems(String json, java.nio.file.Path root, long nowMs)
/** One line per entry for the report: 「2026-11-11 id · 用户说「…」 → 检查 file · 修于 fix · 验证 test」. */
public static String table(String json)
/** The git revision whose copy of the check's file must NOT hold the label: "833d99c^". */
public static String beforeOf(String fix)
```
Rules for `problems` (each broken rule → one sentence naming the entry id and the field):
- the JSON parses and has an `entries` array (else one sentence saying so and nothing else);
- `id` kebab-case (`[a-z0-9]+(-[a-z0-9]+)*`), unique; `said` not empty; `when` `\d{4}-\d{2}-\d{2}` and not after `nowMs`
  (compare as a date, in the default zone); `symptom` not empty;
- `check.file` starts with `tests/agent/` and exists under `root`; `check.label` not empty and **is there** in that file;
- `fix` matches `[0-9a-f]{7}`; `before` not empty and ends with `^`;
- `verified` one of the three; `real` → `evidence` not empty and the file exists under `root`;
  `none` → `unverified_because` not empty; `test` → `evidence` may be empty.
(Whether the label is really absent at `before` is `reported.sh`'s job — it has git; see 3.)

### 3. `tests/agent/reported.sh` — the gate, prints `REPORTED OK`
In order:
1. `sh tests/agent/run_core.sh` when `build/agent-core-test` is missing (it compiles every `src/…/agent/*.java` and
   `tests/agent/*.java`, so `ReportedMain` comes with it): `javac -J-XX:ActiveProcessorCount=2 --release 8 -encoding UTF-8
   -nowarn -Xlint:-options -cp tests/lib/json-20240303.jar -d build/agent-core-test src/xyz/erapi0neer/xingyi/agent/*.java tests/agent/*.java`
   (use the JDK path `run_core.sh` uses, `"/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin"`).
2. `java -cp "build/agent-core-test;tests/lib/json-20240303.jar" xyz.erapi0neer.xingyi.agent.ReportedMain` →
   prints `table`, then each `problems` sentence, exit 1 when there is any.
3. For every entry whose `check.file` and `check.label` are fine: `git show <before>:<file>` (no worktree, no checkout)
   → when that revision's text **contains** the label: print `NOT ABSENT <id>: the check was already there at <before>`
   and fail; when the file does not exist at that revision, that counts as absent. When the entry's file is untracked in
   this revision, skip the step with a line saying so.
4. Print `REPORTED OK` when neither step failed.

### 4. `tests/agent/ReportedChecks.java` — at least 16 checks, run by `ReportedMain` before the gate
A sound one-entry ledger → no problems; each of the rules above broken once → exactly one problem naming that field; a
label not in its file → a problem; a missing evidence file → a problem; `none` without a reason → a problem; two equal
ids → a problem; `table` has one line per entry and holds the id; `beforeOf("833d99c")` is `833d99c^`.
Write the ledger and the files it points at into a temporary directory (`java.nio.file.Files.createTempDirectory`) for
these; nothing outside it.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. `Reported.java` is Java 8 and has no android import; `reported.sh` uses git and the JDK
  above only. **Do not edit `run_core.sh`, `SkillChecks.java`, `TvTools.java`** — another job (self-repair) is changing
  those at the same time.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 4884 (this job adds no core checks)
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/reported.sh` → `REPORTED OK`
