# Brief: reported-2 — 台账的证明不许是空的：文件在 `before` 就被造出来，等于没证明

## Why (the review of 2f02281, 2026-11-12 02:20–02:35)
The `reported` job did the right thing in shape: every problem the user reported now has an id, the user's words, a
named check and a revision where that check was not there yet. The gate that proves it has a hole, and two entries go
through it.

`tests/agent/reported.sh` step 3 does this when the check's file is not in `before`:

```
if ! git cat-file -e "$before:$file" 2>/dev/null; then
    echo "ABSENT $id: $file did not exist at $before"
    continue
fi
```

That treats a file created *after* the fix as proof of absence: of course the label is not there, the whole file is not
there. The two entries `panel-never-came-up` and `self-check-blind-to-panel` point their `check.file` at
`tests/agent/ReportedChecks.java`, which `2f02281` created — the same commit that shipped the ledger. At `c2ea46d^`
the file does not exist, the gate prints `ABSENT … did not exist at`, prints `REPORTED OK`, and the ledger presents
「这条检查在修复前不存在」 as proven when nothing has been proven at all.

There is a second, quieter hole of the same kind: `check.label` is any text that appears in the file, so
`pause-not-paused-said-to-xiaoai` names `jiguangNeedsKey` and `black-screen-left-open` names `static void blank()` — a
symbol that was in the file long before the fix, so "absent at `before`" would be true of nothing anyone ever checked.
A proof is only worth something when the text is a *check's own sentence* and that sentence arrived with the fix.

A third thing the review found: both panel entries read source text (`engine.contains("heardWords = w")`), not
behaviour. A check that reads a string in a source file says the line is there; it cannot say the panel went up. That
is what 「silent tests are not verification」 means in this repository, and the ledger says `verified: "test"` for
one of them.

## What to build

### 1. `reported.sh` — the gate fails when it cannot see the file at `before`
Replace the `if ! git cat-file -e …` branch: when `before` is not empty and the file does not exist at it, print
`VOID <id>: <file> did not exist at <before>, so nothing is proven` and set `status=1`. `ABSENT` is printed only when
the file *was* there and the label was not. An empty `before` means the entry claims no proof (see 2) and is printed
as `NO PROOF <id>: <why>` and skipped — it must not count as a failure, and it must not read like `ABSENT`.

Keep everything else in the script as it is (the compile step, the raw/tsv round trip, the untracked-file skip, the
`UNKNOWN FIX` / `UNKNOWN BEFORE` lines, `REPORTED FAILED` / `REPORTED OK`). Read the ledger's path from
`${REPORTED_JSON:-tests/agent/reported.json}` so the accept script can point it at a one-entry ledger in a temp
directory and expect exit 1 with `VOID`.

### 2. `Reported.java` — three more rules, each one sentence naming the entry id and the field
- `before` may be empty **only** when `unverified_because` is not empty and starts with `无证明` … no: do not overload
  `unverified_because`. Add an optional field `void_because` (a sentence). `before` non-empty → `void_because` must be
  empty or absent. `before` empty → `void_because` must be present and not empty. Problems:
  「<id>: before 是空的，得用 void_because 说清为什么这条没有修复前对照」 and the reverse.
- `check.label` must be a check's sentence, not a bare symbol: at least 12 characters, and the literal
  `"<label>"` (the label as one whole Java string, in double quotes both ends) must appear in `check.file`.
  Problems: 「<id>: check.label 太短，证不出一条检查」 and 「<id>: check.label 不是一条检查的句子（文件里找不到
  "<label>"）」. This is what makes an entry name a check rather than a method that happened to be there: a sentence
  in `check(…, "…")` / `is(…)` / `reply(…)` is a whole quoted string, while a symbol (`jiguangNeedsKey`,
  `static void blank()`) is not.
- Do not try to detect "the check only reads source text" from the code — that is too fuzzy to enforce mechanically.
  `verified` keeps its three values and its existing rules; what a source-level check cannot stand in for is said in
  the entry's own `unverified_because`, not in a rule.

(So: implement the two rules above. The third paragraph is the reasoning for leaving the rest alone, not a task.)

`ReportedMain --entries` prints `id⇥fix⇥before⇥file⇥label`; when `before` is empty print `-` in its place, because
`while IFS="$TAB" read` folds two adjacent tabs and an empty field would shift `file` into `before`. `reported.sh`
treats a `-` in that column as "no proof", prints `NO PROOF <id>` plus the entry's `void_because`, and never asks git
about it.

`table(json)`: an entry with an empty `before` ends with ` · 没有修复前对照` instead of ` · 修于 <fix>`; everything else
the same. `beforeOf` unchanged.

### 3. `tests/agent/reported.json` — make the two entries honest
- `panel-never-came-up`: `before: ""`, `void_because`: 「这条检查是 2f02281 才写进 ReportedChecks.java 的，跟修复同一个
  提交，没有修复前的对照；检查读的是 AgentEngine 的源码，行为要真机跑一次才算」.
- `self-check-blind-to-panel`: the same `before: ""` and a `void_because` saying the check is source-level and came
  with the ledger's own commit; `verified` stays `test` (it does hold the wiring) and `unverified_because` gets
  「面板坏了不要重启也能修好，只在 01:58 用故意弄坏验过一次，证据没进仓库」.
- `pause-not-paused-said-to-xiaoai`: its check should be the actual check in `SkillChecks.java` about the player's
  audio state, not the symbol `jiguangNeedsKey`. Open `tests/agent/SkillChecks.java`, find the `check(...)` sentence
  in that group whose text says what the pause path does (it mentions 云视听极光 or the player's state), and use a
  distinctive piece of that sentence (≥ 12 characters). If that group's sentences are all about the tab/page, say so
  in `void_because` instead and set `before: ""` — do not invent one.
- `black-screen-left-open`: `static void blank()` is a method name; use a distinctive `check(...)` sentence from
  `QuickChecks.java`'s blank group (the one about a black screen going back to the home screen). Same fallback if
  there is none.
- Every other entry: if its `label` is a symbol (`TencentVideo.slim`, `MemoryCare.homeBack`, `Hints.switchAsked`),
  replace it with a distinctive sentence from the corresponding check group; if that makes the entry unprovable at
  its `before`, set `before: ""` and a `void_because`. Do not change `fix`, `when`, `said`, `symptom`.

### 4. `ReportedChecks.java` — at least 12 more checks
- a sound one-entry ledger with a 12+ character label that appears as `"label` → no problems;
- a label of 8 characters → the 太短 problem; a label that is not preceded by a quote → the 不是句子 problem;
- `before: ""` without `void_because` → a problem; `before` with `void_because` → a problem; `before: ""` with it → none;
- `table` ends an empty-`before` entry with `没有修复前对照` and a normal one with `修于`;
- keep every existing check passing.

### 5. `tests/agent/accept_reported2.sh` — prints `REPORTED2 OK`
```
sh tests/agent/reported.sh          →  REPORTED OK  (and its output holds `NO PROOF panel-never-came-up` and `NO PROOF self-check-blind-to-panel`)
```
then, with a temp directory holding one ledger whose check file is real but whose `before` is a revision that does not
have that file (e.g. `before` = the commit that created the check file, label ≥ 12 chars, copied from a file that
exists today): `REPORTED_JSON=<temp> sh tests/agent/reported.sh` must exit non-zero and print `VOID <id>`. Print
`REPORTED2 OK` at the end. The temp ledger's check file must be a real tracked file whose `before` is a real revision
— pick one from the repository, do not write a file that only the check knows about.

## Rules

- Own only `tests/agent/reported.json`, `tests/agent/reported.sh`, `tests/agent/Reported.java`,
  `tests/agent/ReportedChecks.java`, `tests/agent/ReportedMain.java`, the new `tests/agent/accept_reported2.sh`.
  **Do not edit `tests/agent/SkillChecks.java`** (another job is changing its last line right now), `run_core.sh`,
  `AgentCoreTest.java` (never open it), `LocalAdb.java` (never open it), `briefs/`. No TV, no adb, no network.
- `Reported.java` stays Java 8, pure, `org.json` only.
- The two ledger entries this job is about must end up honest: either a proof the gate can show, or an empty `before`
  with `void_because` saying why there is none. A lying proof is worse than none.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N at least 5022
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/reported.sh` → `REPORTED OK`, and its output shows `NO PROOF panel-never-came-up` and
  `NO PROOF self-check-blind-to-panel` and no `VOID`
- `sh tests/agent/accept_reported2.sh` → `REPORTED2 OK`
