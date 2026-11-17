#!/bin/sh
# Acceptance for job handoff: the offline rules on the fixtures (run_core), the core still compiles against Android
# alone (android_check), and the command itself — it reads the REAL job directory and writes only where --out says.
# Prints "HANDOFF OK".
# The launching shell may have MSYS_NO_PATHCONV=1 (the runner's does): a native .exe then gets /h/... literally and
# reads it as H:\h\..., so paths handed to node.exe go through cygpath -w and the variable is cleared here.
unset MSYS_NO_PATHCONV 2>/dev/null || true
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
NODE=/h/tools/node/node-v24.22.2-win-x64/node.exe
[ -x "$NODE" ] || NODE=node
OUT="$ROOT/build/HANDOFF-test.md"
mkdir -p "$ROOT/build"
ok=1
WIN() { cygpath -w "$1" 2>/dev/null || printf '%s' "$1"; }

for f in "$ROOT/tools/handoff/handoff.mjs" "$A/Handoff.java" "$ROOT/tests/agent/HandoffChecks.java" \
         "$ROOT/tests/agent/handoff-fixtures"; do
    ls "$f" >/dev/null 2>&1 || { echo "missing: $f"; ok=0; }
done
grep -q 'HandoffChecks.run()' "$ROOT/tests/agent/SkillChecks.java" || { echo "SkillChecks never runs HandoffChecks"; ok=0; }

# the offline rules and the Android compile: both have to be green here too, not only in the runner's gate
if ! sh "$ROOT/tests/agent/run_core.sh" > "$ROOT/build/accept-handoff-core.log" 2>&1; then
    echo "run_core FAILED:"; tail -20 "$ROOT/build/accept-handoff-core.log"; ok=0
fi
grep -q '0 failed' "$ROOT/build/accept-handoff-core.log" || { echo "core checks have failures"; ok=0; }
if ! sh "$ROOT/tests/agent/android_check.sh" > "$ROOT/build/accept-handoff-android.log" 2>&1; then
    echo "android_check FAILED:"; tail -20 "$ROOT/build/accept-handoff-android.log"; ok=0
fi
grep -q 'ANDROID CHECK OK' "$ROOT/build/accept-handoff-android.log" || { echo "no ANDROID CHECK OK"; ok=0; }

# the real job directory, written to a copy: the run must not touch status/HANDOFF.md
before=$(cksum "$ROOT/status/HANDOFF.md" 2>/dev/null || echo none)
if ! "$NODE" "$(WIN "$ROOT/tools/handoff/handoff.mjs")" --out "$(WIN "$OUT")" \
        > "$ROOT/build/accept-handoff-cmd.log" 2>&1; then
    echo "handoff.mjs failed:"; tail -8 "$ROOT/build/accept-handoff-cmd.log"; ok=0
fi
after=$(cksum "$ROOT/status/HANDOFF.md" 2>/dev/null || echo none)
[ "$before" = "$after" ] || { echo "the run wrote status/HANDOFF.md: $before -> $after"; ok=0; }

for h in '## 一句话' '## 下一步（按顺序，最上面的先做）' '## 卡在人这里的' '## 还没在真机上验过的' '## 不要动' '## 今天花了多少'; do
    grep -q -F "$h" "$OUT" 2>/dev/null || { echo "HANDOFF lacks «$h»"; ok=0; }
done
grep -q -F 'tests/agent/AgentCoreTest.java' "$OUT" 2>/dev/null || { echo "HANDOFF lost the 不要动 list"; ok=0; }

# 一句话 is one sentence: ends in 。 and holds no second 。
one=$(awk '/^## 一句话$/{ getline; getline; print; exit }' "$OUT" 2>/dev/null)
if [ -z "$one" ]; then
    echo "no 一句话 line in $OUT"; ok=0
else
    case "$one" in
        *。) ;;
        *) echo "一句话 does not end in 。: $one"; ok=0 ;;
    esac
    n=$(printf '%s' "$one" | awk '{ print gsub(/。/, "") }')
    [ "$n" = 1 ] || { echo "一句话 holds $n 。, not 1: $one"; ok=0; }
fi

[ $ok = 1 ] && echo HANDOFF OK
