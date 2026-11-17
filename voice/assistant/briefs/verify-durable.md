# Brief: verify-durable — 验证本身能从磁盘接着跑，「还没验」是文件里的一条记录

## Why (the user's yardstick: 进度可恢复; and the failure of 2026-11-11)
The morning's TV verification was a scheduled task inside a chat session. The session restarted, the task died with it,
the verification never ran, and **nobody noticed for ten hours** — the user found out by using the TV and getting
wrong behaviour. The harness's own verification has the weakest recovery of anything in the project: every other part
(worker jobs, check counts, ledgers) reads its state from disk and can be resumed; this one lived in a conversation.

The second half is honesty about what has not been checked. Tonight the same mistake happened twice: 「暂停已验证」
rested on silent-source tests that cannot exercise the action, and 「自检正常」 was said with the panel broken. What is
not yet verified has to be a record, not a memory.

## What to build

### 1. `tests/agent/unverified.json` — what is installed but not yet verified on the real path
```json
{"entries": [
  {"id": "ktcp-pause-real",
   "feature": "云视听极光的暂停/继续（对真实播放说给小爱）",
   "why": "静音来源对有副作用的工具是拒执的，验不到；01:30 装机后只在库检查里过",
   "needs": ["放一集", "亮屏"],
   "since": "2026-11-11",
   "script": "voice/assistant-out/tmp/lead/k1111/session7.py"}
]}
```
Fields: `id` kebab-case and unique · `feature` one line · `why` why the silent checks cannot stand in for it ·
`needs` the preconditions (`放一集`, `亮屏`, `有人在场`, `待机`, …) · `since` a date `YYYY-MM-DD` · `script` the thing that
would verify it (a path that must exist, or `""` when nothing does yet).
Seed it with six entries: `ktcp-pause-real` (as above), `ktcp-seek-real` (快进一分钟对真实播放), `episode-open-real`
(「我想看别无选择第5集」真机), `standby-home-real` (熄屏后桌面回最上面), `panel-while-speaking` (说话时逐字上屏),
`tencent-open-sleeping` (待机时「我想看 X」点亮屏幕并开播). Use what tonight's probe files show; `since` 2026-11-11,
`script` the `k1111` paths that exist, `""` where nothing does.

### 2. `Unverified.java` (new, pure, package `xyz.erapi0neer.xingyi.agent`)
```java
/** Everything wrong with the file, as sentences; empty when it is sound. root is the repository root. */
public static java.util.List<String> problems(String json, java.nio.file.Path root, long nowMs)
/** The markdown section for NEXT.md: 「## 还没验的」 then one bullet per entry, oldest first. */
public static String markdown(String json)
/** One line for bridge.sh status: 「还没在真实路径验：3 条（ktcp-pause-real, ktcp-seek-real, …）」 or 「」 when none. */
public static String line(String json, int max)
/** The same file with one entry gone — what a verification that passed writes back. */
public static String without(String json, String id) throws org.json.JSONException
```
`problems`: parses; `entries` is an array; `id` kebab-case and unique; `feature`, `why` not empty; `needs` is an array
whose items are not empty; `since` a `\d{4}-\d{2}-\d{2}` date not after `nowMs`; `script` is either empty or a path that
exists under `root`.
`markdown`: `"## 还没验的\n\n"` then `- **<id>** <feature> —— <why>（要 <needs joined by 、>；脚本 <script or 没有>）\n` per
entry, oldest `since` first (ties keep file order), then nothing else.
`line(json, max)`: the count and at most `max` ids; no entries → `""`.

### 3. `tests/agent/unverified_doc.py` — NEXT.md's section is generated, not typed
Its output on success is `UP TO DATE` (the acceptance runs `--check` and looks for it).
`python tests/agent/unverified_doc.py` reads `tests/agent/unverified.json`, takes the file's `## 还没验的` section (from
that heading to the next `## `) and replaces it with `Unverified.markdown`'s text (call the Java through
`build/agent-core-test` the way `reported.sh` does, or reimplement the formatting in Python — one of the two, say which
in the file's header). `--check` exits 1 when NEXT.md is out of date, 0 when it is current; the default writes it.

### 4. `tests/agent/verify.sh` — the resumable verification run
For each entry, in `since` order: print `WAITING <id>  needs …` and the script path; run a script only when it is given
**and** `--run` was passed, with `--only <id>` to pick one. When a run writes an evidence file and exits 0, the entry is
removed from `tests/agent/unverified.json` (through `Unverified.without`) and the line `VERIFIED <id>` is printed.
Without `--run` it only lists. It never touches the TV itself.

### 5. `tools/m31w/run.mjs` — board one line
`node tools/m31w/run.mjs status` gets nothing new; instead `bridge.sh status` (in `xiaoai-bridge/`) prints the
unverified line: the bridge's script calls `python /h/tools/mitv/voice/assistant/tests/agent/unverified_status.py` and
echoes its stdout as a `unverified:` line when it is not empty. `unverified_status.py` reads the same JSON through
`Unverified.line(json, 3)`. (The bridge repository is a separate git tree — the lead will commit that one line; write the
Python and say in your report what the lead has to add there, do not edit the bridge yourself.)

### 6. `tests/agent/UnverifiedChecks.java` — at least 18 checks
A sound one-entry file → no problems; each broken rule → one sentence naming it; `markdown` has the heading, one bullet
per entry, oldest first, and round-trips (its bullets hold every id); `line` with 0, 1, 3, 5 entries and `max` 3;
`without` removes exactly one and keeps the rest; `without` of an id that is not there leaves the file as it is.
Run it from `ReportedMain`'s sibling — no: `ReportedMain` belongs to another job; **call it from the last line of
`SkillChecks.run()`** (`UnverifiedChecks.run();`), the file you own.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. `Unverified.java` is Java 8, no android import. **Do not edit `tests/agent/reported.*`,
  `src/…/agent/Reported.java`, `NEXT.md`, `HARNESS.md`, `tests/agent/SkillChecks.java`'s existing groups** (add only the
  one line at the end of `run()`), and do not edit the bridge repository.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N at least 18 more than before you
  started (the job's check requires the number in the job file)
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_verify_durable.sh` → `VERIFY DURABLE OK`
