#!/bin/sh
# Acceptance for job resume: the offline rules on the shipped reports, then a REAL rehearsal of tools/m31w/run.mjs
# with a fake worker (tools/resume/rehearse.mjs --kill-after 5 --resume), then the four facts the brief asks for,
# read straight out of the fresh report. Nothing here touches the TV, the network or the real jobs directory.
# Prints "RESUME OK".
# A native .exe (node, python) reads Git Bash's /h/... as H:\h\... while the launching shell has MSYS_NO_PATHCONV=1
# (the m31w runner's shell does): clear it, so this script does not depend on who ran it.
unset MSYS_NO_PATHCONV 2>/dev/null || true
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
NODE=/h/tools/node/node-v24.22.2-win-x64/node.exe
[ -x "$NODE" ] || NODE=node
REPORT="$ROOT/build/resume-report.json"
mkdir -p "$ROOT/build"
ok=1

# 1. the offline rules, on the reports in tests/agent/resume/reports/ (good.json is a real rehearsal's report)
if ! python "$ROOT/tests/agent/resume/check.py" > "$ROOT/build/accept-resume-fixtures.log" 2>&1; then
    echo "check.py on the shipped reports FAILED:"; tail -12 "$ROOT/build/accept-resume-fixtures.log"; ok=0
fi

# 2. the rehearsal itself: kill run.mjs mid-round, then run it again on the same state
if ! "$NODE" "$ROOT/tools/resume/rehearse.mjs" --kill-after 5 --resume --report --json \
        > "$REPORT" 2> "$ROOT/build/accept-resume-rehearse.log"; then
    echo "the rehearsal FAILED:"; tail -20 "$ROOT/build/accept-resume-rehearse.log"; ok=0
fi

for f in "$ROOT/tools/resume/rehearse.mjs" "$ROOT/tools/resume/stub.cs" "$ROOT/tests/agent/resume/check.py" \
         "$ROOT/tests/agent/ResumeChecks.java"; do
    ls "$f" >/dev/null 2>&1 || { echo "missing: $f"; ok=0; }
done
grep -q 'ResumeChecks.run()' "$ROOT/tests/agent/SkillChecks.java" || { echo "SkillChecks never runs ResumeChecks"; ok=0; }
grep -q 'SKYRUSH_OUT' "$ROOT/tools/resume/rehearse.mjs" || { echo "rehearse.mjs never mentions SKYRUSH_OUT"; ok=0; }

# 3. the fresh report through the rules
if [ -s "$REPORT" ]; then
    if ! python "$ROOT/tests/agent/resume/check.py" "$REPORT" > "$ROOT/build/accept-resume-report.log" 2>&1; then
        echo "the fresh report does not pass the rules:"; tail -12 "$ROOT/build/accept-resume-report.log"; ok=0
    fi
else
    echo "the rehearsal wrote no report"; ok=0
fi

# 4. the four facts, read out of the report itself and not through the rules above:
#    the first launch began at round 1; the second began at round 1 AGAIN (the killed round had no history entry);
#    the second launch's first prompt is the continuation sentence only if the state says sessionStarted;
#    the final history.length is the number of rounds the fake worker really ran to the end (the killed one left
#    nothing behind, so 2 asked / 1 finished / 1 killed is a history of 1).
if [ -s "$REPORT" ]; then
    if ! "$NODE" -e '
const fs = require("fs");
const r = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
const kill = (r.jobs || []).filter((j) => j.scenario === "kill")[0];
const bad = [];
if (!kill) {
  bad.push("the report has no kill job at all");
} else {
  const L = kill.launches || [];
  if (L.length < 2) bad.push("the kill job has " + L.length + " launch(es), not 2");
  else {
    if (L[0].beginRound !== 1) bad.push("the first launch began at round " + L[0].beginRound + ", not 1");
    if (L[1].beginRound !== 1) {
      bad.push("the second launch began at round " + L[1].beginRound + ", not 1 — the killed round was counted as done");
    }
    if (L[0].killed !== true) bad.push("the first launch was not killed");
    const started = !!(L[1].stateBefore && L[1].stateBefore.sessionStarted === true);
    const p = ((L[1].stub || [])[0] || {}).prompt || "";
    const cont = p.indexOf("The lead resumes this job.") === 0;
    if (started !== cont) {
      bad.push("stateBefore.sessionStarted=" + started + " but the first prompt " + (cont ? "IS" : "is NOT")
        + " the continuation sentence");
    }
    if (!cont && p.indexOf("# YOUR BRIEF") < 0) bad.push("and it is not the brief first prompt either");
    if (L[1].stateBefore && !(L[1].stateBefore.rounds > L[1].stateBefore.history)) {
      bad.push("the state the second launch read has rounds=" + L[1].stateBefore.rounds + " history="
        + L[1].stateBefore.history + " — there was no uncommitted round to re-run, so this proves nothing");
    }
    const runs = kill.stub.runs, finished = kill.stub.finished, killed = L.filter((x) => x.killed === true).length;
    if (kill.final.history !== finished) {
      bad.push("final history.length=" + kill.final.history + " but the fake worker finished " + finished + " round(s)");
    }
    if (runs !== finished + killed) {
      bad.push("the fake worker was asked to run " + runs + " time(s), finished " + finished + ", killed launches "
        + killed + " — those do not add up");
    }
    if (kill.final.rounds !== kill.final.history) {
      bad.push("final rounds=" + kill.final.rounds + " but history=" + kill.final.history);
    }
  }
}
for (const b of bad) console.log("  " + b);
process.exit(bad.length ? 1 : 0);
' "$REPORT"; then
        echo "the report does not show what the brief asks for (above)"; ok=0
    fi
fi

# nothing may be left behind: no worktree, no job/rehearse-* branch, no scratch dir.
# 演练的 id 是 `rehearse-<14 位时间戳>`（tools/resume/rehearse.mjs 的 stamp）；只认这个形状——真的单子也可能叫
# `job/rehearse-clock`（2026-11-14 03:56：它就是被这条检查当成「演练留下的分支」，红了但什么都没错）。
STAMPED='job/rehearse-[0-9]\{14\}$'
if git -C "$ROOT" worktree list | grep -q 'rehearse-[0-9]\{14\}'; then
    echo "a rehearsal worktree was left behind:"; git -C "$ROOT" worktree list | grep 'rehearse-[0-9]\{14\}'; ok=0
fi
if git -C "$ROOT" branch --list 'job/rehearse-*' | grep -q "$STAMPED"; then
    echo "a rehearsal branch was left behind:"; git -C "$ROOT" branch --list 'job/rehearse-*' | grep "$STAMPED"; ok=0
fi
if [ -s "$REPORT" ] && grep -q 'assistant-out/jobs' "$REPORT"; then
    echo "the report names the real jobs directory — the rehearsal wrote where other jobs live"; ok=0
fi

# 每一轮开始前 worker 的分支快进到主分支（refresh.mjs）：lead 派单之后提交的文件下一轮看得见、worker 的活不动、
# 跟不上时不动、快进后合并回去照样干净（2026-11-13：两单的第 1 轮都红在 worker 看不见的 lead 文件上）
node "$ROOT/tools/m31w/refresh-check.mjs" > "$ROOT/build/accept-refresh.log" 2>&1 && grep -q 'REFRESH OK' "$ROOT/build/accept-refresh.log" \n    || { echo "refresh-check failed:"; grep -E 'FAIL|Error' "$ROOT/build/accept-refresh.log" | head -8; ok=0; }

[ $ok = 1 ] && echo RESUME OK
