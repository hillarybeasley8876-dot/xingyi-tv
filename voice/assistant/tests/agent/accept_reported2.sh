#!/bin/sh
# Acceptance for reported-2: the gate refuses a proof it cannot see.
#   1. the real ledger passes, and the two entries that have no revision before the fix say NO PROOF (never VOID);
#   2. a one-entry ledger whose check file is real and tracked but did not exist at its `before` must fail with VOID —
#      2f02281 created tests/agent/ReportedChecks.java, so at 2f02281^ there is nothing to read the label out of.
# Prints "REPORTED2 OK".
set -u
cd "$(dirname "$0")/../.."
# relative to the repository root: reported.sh runs java with that as its working directory, and a Git Bash path
# (/h/tools/…) is not a path the JVM can open
TMP=build/accept-reported2
ok=1

out=$(sh tests/agent/reported.sh 2>&1)
rc=$?
echo "$out" | tail -12
[ "$rc" -eq 0 ] || { echo "FAIL: the real ledger does not pass the gate (exit $rc)"; ok=0; }
echo "$out" | grep -q "REPORTED OK" || { echo "FAIL: no REPORTED OK"; ok=0; }
for id in panel-never-came-up self-check-blind-to-panel; do
    echo "$out" | grep -q "NO PROOF $id" || { echo "FAIL: no NO PROOF $id"; ok=0; }
done
if echo "$out" | grep -q "VOID"; then
    echo "FAIL: the real ledger has a VOID in it"
    ok=0
fi

mkdir -p "$TMP"
cat > "$TMP/one.json" <<'JSON'
{"entries":[{"id":"a-proof-nobody-can-see","said":"（accept_reported2 的临时台账）","when":"2026-11-11","symptom":"检查文件在 before 那个版本上根本不存在：说「这条检查在修复前不存在」什么也没证明","check":{"file":"tests/agent/ReportedChecks.java","label":"a sound one-entry ledger has no problems"},"fix":"2f02281","before":"2f02281^","verified":"test","evidence":"","unverified_because":""}]}
JSON
vout=$(REPORTED_JSON="$TMP/one.json" sh tests/agent/reported.sh 2>&1)
vrc=$?
echo "$vout" | tail -4
[ "$vrc" -ne 0 ] || { echo "FAIL: a ledger that cannot be proven still exits 0"; ok=0; }
echo "$vout" | grep -q "VOID a-proof-nobody-can-see" || { echo "FAIL: no VOID line for the temp ledger"; ok=0; }
echo "$vout" | grep -q "REPORTED FAILED" || { echo "FAIL: the temp ledger does not say REPORTED FAILED"; ok=0; }

[ "$ok" = 1 ] && echo "REPORTED2 OK"
[ "$ok" = 1 ]
