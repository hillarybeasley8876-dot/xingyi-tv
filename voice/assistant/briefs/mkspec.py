# -*- coding: utf-8 -*-
"""Writes a job spec for the m31w runner with the check-count pattern computed from a number, so no backslash ever
passes through a shell (a heredoc folds two into one).

    python mkspec.py <id> <min-count> <minutes> "<title>" <accept-script> "<ACCEPT WORD>" <owned file>...
"""
import io, json, re, sys


def at_least(n):
    """A regex for an integer >= n (4 digits; 5 digits and more are allowed too)."""
    s = str(n)
    assert len(s) == 4, 'four-digit counts only'
    d = chr(92) + 'd'
    parts = []
    # same length, same prefix up to i, a larger digit at i, anything after
    for i in range(len(s)):
        lo = int(s[i]) + (1 if i < len(s) - 1 else 0)
        if lo > 9:
            continue
        cls = str(lo) if lo == 9 else '[%d-9]' % lo
        parts.append(s[:i] + cls + d * (len(s) - i - 1))
    parts.append('[1-9]' + d + '{4,}')
    return '(' + '|'.join(reversed(parts)) + ')'


if __name__ == '__main__':
    jid, count, minutes, title, script, word = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4], sys.argv[5], sys.argv[6]
    owns = sys.argv[7:]
    # a bare file name is an acceptance script under tests/agent/: board-money was dispatched with
    # "accept_cost.sh" and four rounds died on `sh accept_cost.sh` (exit 127) before anyone noticed (2026-11-12).
    if '/' not in script and chr(92) not in script:
        script = 'tests/agent/' + script
    rx = 're:agent core: ' + at_least(count) + ' passed, 0 failed'
    spec = {"id": jid, "title": title, "brief": "briefs/%s.md" % jid, "isolation": "worktree", "maxRounds": 6, "timeoutMin": minutes,
            "owns": owns,
            "accept": [{"name": "core-checks", "cmd": "sh tests/agent/run_core.sh", "timeoutS": 500, "must": [rx]},
                       {"name": "android-check", "cmd": "sh tests/agent/android_check.sh", "timeoutS": 400, "must": ["ANDROID CHECK OK"]},
                       {"name": "bridge", "cmd": "sh " + script, "timeoutS": 900, "must": [word]}]}
    io.open('briefs/jobs/%s.json' % jid, 'w', encoding='utf-8', newline='').write(json.dumps(spec, ensure_ascii=False, indent=1) + '\n')
    pat = rx[3:]
    ok = all(bool(re.search(pat, 'agent core: %d passed, 0 failed' % k)) == (k >= count) for k in
             list(range(count - 30, count + 30)) + [count + 100, count + 1000, 9999, 10000, 12345, 1000, 999])
    print(jid, 'needs >=', count, '| pattern verified:', ok, '|', pat)
    sys.exit(0 if ok else 1)
