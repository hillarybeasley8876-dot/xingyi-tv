#!/bin/sh
# Acceptance for the job lexicon-core: the oracle ran and is green, it is the lead's file as it was, and the
# implementation does not know the strings it is examined on. Prints LEXICON OK.
set -u
cd "$(dirname "$0")/../.."
ok=1
out=build/accept-lexicon-core.txt
mkdir -p build
sh tests/agent/run_core.sh > "$out" 2>&1
grep -q "LEXICON ORACLE RAN: [0-9][0-9]* checks" "$out" || { echo "the oracle did not run (is Lexicon.IMPLEMENTED true?)"; ok=0; }
grep -q "agent core: [0-9][0-9]* passed, 0 failed" "$out" || { echo "core checks are not green: $(grep 'agent core:' "$out")"; ok=0; }
if grep -q "FAIL lexicon" "$out"; then echo "lexicon checks fail:"; grep "FAIL lexicon" "$out" | head -20; ok=0; fi
# the oracle and its fixture are the lead's: as they are on master
git diff --quiet master -- tests/agent/LexiconOracleChecks.java tests/agent/fixtures/history-2026-11-07.json \
    || { echo "the oracle or its fixture differs from master"; ok=0; }
# the code may not know the names it is examined on (comments may tell the story)
for f in src/xyz/erapi0neer/xingyi/agent/Lexicon*.java; do
    [ -f "$f" ] || continue
    code=$(sed -e 's#//.*$##' "$f" | awk '/\/\*/{c=1} !c{print} /\*\//{c=0}')
    for w in TABLE TIBA Tibo tibo MiniMax MINIMAX minimax OpenAI openai OPENA 质朴 制储 智谱 CLOUD Claude claude VIDIA NVIDIA nvidia \
             必亚迪 比亚迪 Anthropic anthropic ANTROPIC 提博 MediaAlpha; do
        if printf '%s\n' "$code" | grep -q "$w"; then echo "$f knows the examined string $w"; ok=0; fi
    done
done
[ "$ok" = "1" ] && echo "LEXICON OK"
exit 0
