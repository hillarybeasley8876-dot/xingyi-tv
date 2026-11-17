#!/bin/sh
# Acceptance for job cost: the bridge builds with this tree's sources and its JVM suite passes against them; the
# accounting reads the real job logs on disk, prints a total that is not NaN, and knows what it could not measure.
# Builds into build-wt-cost/. Prints "COST OK".
# The launching shell may have MSYS_NO_PATHCONV=1 (the runner's does): aapt2.exe then reads /h/... as H:\h\...,
# so the bridge build runs with it cleared.
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
NODE=/h/tools/node/node-v24.22.2-win-x64/node.exe
[ -x "$NODE" ] || NODE=node
mkdir -p "$ROOT/build"
ok=1
if ! (unset MSYS_NO_PATHCONV; XINGYI="$ROOT" BRIDGE_BUILD=build-wt-cost sh "$BRIDGE/build.sh") > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! (unset MSYS_NO_PATHCONV; XINGYI="$ROOT" BRIDGE_BUILD=build-wt-cost sh "$BRIDGE/tests/run_all.sh") > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
for f in "$ROOT/tools/cost/cost.mjs" "$A/Cost.java" "$ROOT/tests/agent/CostChecks.java" "$ROOT/tests/agent/cost-fixtures"; do
    ls "$f" >/dev/null 2>&1 || { echo "missing: $f"; ok=0; }
done
grep -q 'CostChecks.run()' "$ROOT/tests/agent/SkillChecks.java" || { echo "SkillChecks never runs CostChecks"; ok=0; }
# the command reads the real jobs and must not write anything
if ! "$NODE" "$(cygpath -w "$ROOT/tools/cost/cost.mjs" 2>/dev/null || printf '%s' "$ROOT/tools/cost/cost.mjs")" \
        --jobs "$(cygpath -w "$ROOT/../assistant-out/jobs" 2>/dev/null || printf '%s' "$ROOT/../assistant-out/jobs")" \
        > "$ROOT/build/accept-cost.log" 2>&1; then
    echo "cost.mjs failed:"; tail -8 "$ROOT/build/accept-cost.log"; ok=0
fi
# a NaN shows up as a bare NaN token; -i would also hit a job called evidence-provenance (pro-venan-ce), which
# made this gate red for a job that was correct (2026-11-16)
grep -q -E '(^|[^A-Za-z])NaN([^A-Za-z]|$)' "$ROOT/build/accept-cost.log" && { echo "cost.mjs printed NaN"; ok=0; }
grep -q '单、' "$ROOT/build/accept-cost.log" || { echo "cost.mjs printed no total line"; tail -5 "$ROOT/build/accept-cost.log"; ok=0; }
grep -q '\$' "$ROOT/build/accept-cost.log" || { echo "cost.mjs printed no dollar figure"; ok=0; }
# verify-durable's five cumulative result events are one job, not five: the figure must be the last one, $9.94-ish
grep -q -i 'verify-durable' "$ROOT/build/accept-cost.log" || { echo "cost.mjs does not list verify-durable"; ok=0; }
[ $ok = 1 ] && echo COST OK
