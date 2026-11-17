"""Compares calibrate.mjs runs of several models: rounds, model time and the ending per scenario.

python tests/agent/decide_compare.py <run.txt> [<run.txt> ...]   (AGENT_MODEL=<m> node calibrate.mjs … > <m>.txt)
"""
import os, re, sys


def parse(path):
    out, cur = {}, None
    for line in open(path, encoding='utf-8', errors='replace'):
        m = re.match(r'=== (\w+): ', line)
        if m:
            cur = out.setdefault(m.group(1), {'rounds': 0, 'ms': 0, 'end': '', 'expect': '', 'calls': []})
            continue
        if cur is None:
            continue
        m = re.match(r'\s+R(\d+) (\d+)ms (\S+)(.*)', line)
        if m:
            cur['rounds'] = max(cur['rounds'], int(m.group(1)))
            if m.group(3) not in ('FINAL:',):
                cur['calls'].append(m.group(3))
            if m.group(3) in ('FINAL:', 'finish'):
                cur['end'] = m.group(4).strip()[:70]
        m = re.match(r'\s+R\d+ answered from the tools: (.*)', line)
        if m:
            cur['end'] = 'direct: ' + m.group(1)[:62]
        m = re.match(r'\s+total model time (\d+) ms', line)
        if m:
            cur['ms'] = int(m.group(1))
        if 'EXPECT OK' in line:
            cur['expect'] = 'ok'
        elif 'EXPECT FAIL' in line:
            cur['expect'] = 'FAIL'
        elif 'ERROR' in line:
            cur['expect'] = 'ERR'
    return out


def main():
    runs = {os.path.basename(p).rsplit('.', 1)[0]: parse(p) for p in sys.argv[1:]}
    scenarios = []
    for r in runs.values():
        for s in r:
            if s not in scenarios:
                scenarios.append(s)
    for s in scenarios:
        print(s)
        for name, r in runs.items():
            v = r.get(s)
            if not v:
                continue
            print('  %-20s %2d rounds %6.1fs %-4s %s' % (name, v['rounds'], v['ms'] / 1000, v['expect'], v['end']))
    print()
    for name, r in runs.items():
        done = [v for v in r.values() if v['expect'] != 'ERR']
        print('%-20s scenarios %d, rounds %d, model time %.0fs, hit the 10-round cap %d' % (
            name, len(done), sum(v['rounds'] for v in done), sum(v['ms'] for v in done) / 1000,
            sum(1 for v in done if v['rounds'] >= 10)))


if __name__ == '__main__':
    main()
