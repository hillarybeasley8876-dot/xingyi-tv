#!/bin/sh
# The ledger gate (reported, 2026-11-12): every problem the user reported has a named check, and git shows that check
# was NOT there at the revision before the fix — a check nobody has ever seen missing proves nothing. A check file that
# did not exist at that revision proves nothing either (the fix created it), so that is VOID; an entry that has no
# revision before the fix at all says so itself and is NO PROOF, which is honest and not a failure.
# Usage: sh tests/agent/reported.sh   →  prints REPORTED OK   (REPORTED_JSON=… reads another ledger)
set -u
cd "$(dirname "$0")/../.."
JDK="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin"
OUT=build/agent-core-test
JSON=tests/lib/json-20240303.jar
TAB=$(printf '\t')
# the accept script points this at a one-entry ledger in a temporary directory
LEDGER=${REPORTED_JSON:-tests/agent/reported.json}

# 1. the core and the tests, once: run_core.sh leaves the same directory behind
if [ ! -d "$OUT" ]; then
    mkdir -p "$OUT"
    "$JDK/javac.exe" -J-XX:ActiveProcessorCount=2 --release 8 -encoding UTF-8 -nowarn -Xlint:-options -cp "$JSON" -d "$OUT" \
        src/xyz/erapi0neer/xingyi/agent/*.java tests/agent/*.java \
        || { echo "REPORTED FAILED: the core and the tests do not compile"; exit 1; }
fi

# 2. the ledger's own checks, the table, and every sentence that is wrong with it
# file.encoding: Java 17 writes System.out in the machine's code page, which turns 用户说「…」 into unreadable bytes
"$JDK/java.exe" -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8 \
    -cp "$OUT;$JSON" xyz.erapi0neer.xingyi.agent.ReportedMain "$LEDGER"
rc=$?
if [ "$rc" -ne 0 ]; then
    echo "REPORTED FAILED: the ledger is not sound (the sentences are above)"
    exit 1
fi

# 3. the check must NOT be in that file at the revision before the fix (git show: no worktree, no checkout)
mkdir -p build
"$JDK/java.exe" -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8 \
    -cp "$OUT;$JSON" xyz.erapi0neer.xingyi.agent.ReportedMain "$LEDGER" --entries \
    > build/reported-entries.raw || { echo "REPORTED FAILED: the entries could not be read"; exit 1; }
# a label that still carried Java's \r would never be found, and every entry would read as absent
tr -d '\r' < build/reported-entries.raw > build/reported-entries.tsv
status=0
while IFS="$TAB" read -r id fix before file label why; do
    [ -n "$id" ] || continue
    # no revision before the fix: the entry says why itself, git is never asked, and this is not a failure
    if [ "$before" = "-" ]; then
        echo "NO PROOF $id: $why"
        continue
    fi
    if ! git rev-parse --verify -q "$fix^{commit}" >/dev/null 2>&1; then
        echo "UNKNOWN FIX $id: $fix is not a commit of this repository"
        status=1
        continue
    fi
    if ! git rev-parse --verify -q "$before^{commit}" >/dev/null 2>&1; then
        echo "UNKNOWN BEFORE $id: $before is not a revision of this repository"
        status=1
        continue
    fi
    if ! git ls-files --error-unmatch "$file" >/dev/null 2>&1; then
        echo "SKIP $id: $file is untracked in this revision"
        continue
    fi
    # a file the fix itself created is not proof that the check was missing: nothing can be read there at all
    if ! git cat-file -e "$before:$file" 2>/dev/null; then
        echo "VOID $id: $file did not exist at $before, so nothing is proven"
        status=1
        continue
    fi
    if git show "$before:$file" | grep -F -q -e "$label"; then
        echo "NOT ABSENT $id: the check was already there at $before"
        status=1
    else
        echo "ABSENT $id: the check is not in $file at $before"
    fi
done < build/reported-entries.tsv

# 4.
if [ "$status" -eq 0 ]; then
    echo "REPORTED OK"
else
    echo "REPORTED FAILED: a check was already there before the fix, a check file was not there at all, or a revision is not in this repository"
fi
exit "$status"
