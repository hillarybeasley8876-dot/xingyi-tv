#!/bin/sh
# Acceptance for job next-song-through-xiaoai: the real-path scenario exists, is registered and has its ledger entry,
# is rehearsed in at least five situations, and the offline checker agrees with the ledger. Nothing here touches the
# TV. Prints "NEXT SONG OK".
unset MSYS_NO_PATHCONV 2>/dev/null || true
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 1
mkdir -p build
ok=1
ID=next-song-through-xiaoai
S=tests/agent/realpath/scenarios/$ID.py
[ -f "$S" ] || { echo "$S missing"; ok=0; }
python tests/agent/realpath/rehearse.py > build/accept-next-song-rehearse.log 2>&1
grep -q "REHEARSE OK" build/accept-next-song-rehearse.log || { echo "rehearse.py did not say REHEARSE OK:"; tail -8 build/accept-next-song-rehearse.log; ok=0; }
n=$(grep -c "$ID" build/accept-next-song-rehearse.log)
[ "$n" -ge 5 ] || { echo "$ID: $n lines in the rehearsal, at least 5 situations wanted"; ok=0; }
python -I -c "
import json, sys
sid = '$ID'
reg = json.load(open('tests/agent/realpath/scenarios.json', encoding='utf-8'))['scenarios']
led = json.load(open('tests/agent/unverified.json', encoding='utf-8'))['entries']
r = [s for s in reg if s.get('id') == sid]
e = [x for x in led if x.get('id') == sid]
bad = []
if len(r) != 1: bad.append('scenarios.json: %d entries for %s' % (len(r), sid))
elif r[0].get('script') != 'tests/agent/realpath/scenarios/%s.py' % sid or r[0].get('unverified') != sid: bad.append('scenarios.json entry: script or unverified wrong')
if len(e) != 1: bad.append('unverified.json: %d entries for %s' % (len(e), sid))
elif e[0].get('script') != 'tests/agent/realpath/scenarios/%s.py' % sid or not e[0].get('needs') or '6d32cf8' not in e[0].get('why', ''): bad.append('unverified.json entry: script, needs or why (6d32cf8) wrong')
if len(led) != 1: bad.append('unverified.json has %d entries; only this one was to be added' % len(led))
print('\n'.join(bad))
sys.exit(1 if bad else 0)
" || ok=0
python tests/agent/realpath/check.py --strict > build/accept-next-song-check.log 2>&1 || { echo "check.py --strict failed:"; tail -6 build/accept-next-song-check.log; ok=0; }
[ $ok = 1 ] && echo "NEXT SONG OK"
[ $ok = 1 ]
