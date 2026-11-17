"""Memory probe for the 2 GB TV: what an app costs and what the system does about it. Read-only except for the app
that is launched and, with --trim, `am kill` on that app's background processes.

python tools/memprobe.py baseline                     idle snapshot: free memory, biggest processes, kernel limits
python tools/memprobe.py app tv.danmaku.bili [--trim] [--seconds 45]
                                                     launch the app (it takes over the TV screen!), sample every 3 s,
                                                     optionally trim its background processes, go HOME, stop the app

Samples cost little: /proc/meminfo, one `ps -A` and a few /proc reads. No screenshots (they cost memory too).
Results go to out/mem-<time>.json and a summary table on stdout.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ADB = os.environ.get('ADB_EXE', r'H:\tools\platform-tools\adb.exe')
TV = os.environ.get('TV', '192.168.31.162:5555')
PKG = 'xyz.erapi0neer.xiaoaibridge'
HERE = Path(__file__).resolve().parent.parent
NO_WINDOW = 0x08000000


def adb(*args, timeout=30):
    try:
        r = subprocess.run([ADB, '-s', TV, *args], capture_output=True, timeout=timeout, creationflags=NO_WINDOW)
    except subprocess.TimeoutExpired:
        return 124, '(timed out)'
    return r.returncode, (r.stdout + r.stderr).decode('utf-8', 'replace')


def sh(command, timeout=30):
    return adb('shell', command, timeout=timeout)[1]


# ---- parsing (pure) --------------------------------------------------------------------------------------------

def parse_meminfo(text):
    """{'MemTotal': kB, ...} from /proc/meminfo."""
    out = {}
    for line in text.splitlines():
        m = re.match(r'(\w+)(?:\(\w+\))?:\s+(\d+)\s*kB', line)
        if m:
            out[m.group(1)] = int(m.group(2))
    return out


def parse_ps(text):
    """[(pid, name, rss_kb)] from Android's `ps -A` (USER PID PPID VSZ RSS WCHAN ADDR S NAME)."""
    rows = []
    for line in text.splitlines():
        parts = line.split()
        if len(parts) >= 9 and parts[1].isdigit() and parts[4].isdigit():
            rows.append((int(parts[1]), parts[-1], int(parts[4])))
    return rows


def parse_proc_lines(text):
    """{pid: (oom_score_adj, vmrss_kb)} from lines 'PID ADJ VmRSS: N kB' written by PROC_COMMAND."""
    out = {}
    for line in text.splitlines():
        m = re.match(r'(\d+)\s+(-?\d+)\s+VmRSS:\s+(\d+)\s*kB', line.strip())
        if m:
            out[int(m.group(1))] = (int(m.group(2)), int(m.group(3)))
    return out


def package_of(process_name):
    return process_name.split(':', 1)[0]


def by_package(rows, package):
    """Rows of one app: the main process and its ':helper' processes."""
    return [r for r in rows if package_of(r[1]) == package]


# ---- sampling --------------------------------------------------------------------------------------------------

def sample(watch):
    """One reading: kernel memory plus every process of the watched packages (RSS, oom_score_adj)."""
    meminfo = parse_meminfo(sh('cat /proc/meminfo'))
    rows = parse_ps(sh('ps -A'))
    watched = [r for r in rows if package_of(r[1]) in watch]
    pids = ' '.join(str(r[0]) for r in watched)
    adj = parse_proc_lines(sh(f'for p in {pids}; do echo "$p $(cat /proc/$p/oom_score_adj) $(grep VmRSS /proc/$p/status)"; done')) if pids else {}
    return {
        't': round(time.time(), 1),
        'available_mb': meminfo.get('MemAvailable', 0) // 1024,
        'free_mb': meminfo.get('MemFree', 0) // 1024,
        'cached_mb': meminfo.get('Cached', 0) // 1024,
        'swap_free_mb': meminfo.get('SwapFree', 0) // 1024,
        'procs': [{'pid': pid, 'name': name, 'rss_mb': rss // 1024, 'adj': adj.get(pid, (None, 0))[0]}
                  for pid, name, rss in watched],
        'top': [{'name': n, 'rss_mb': r // 1024} for _, n, r in sorted(rows, key=lambda x: -x[2])[:10]],
    }


def describe(s, package):
    mine = by_package([(p['pid'], p['name'], p['rss_mb']) for p in s['procs']], package)
    app = ' '.join(f"{p[1].split(':', 1)[-1] if ':' in p[1] else 'main'}={p[2]}" for p in mine) or 'not running'
    alive = 'bridge up' if any(package_of(p['name']) == PKG for p in s['procs']) else 'BRIDGE GONE'
    return f"avail {s['available_mb']:4d} MB | free {s['free_mb']:4d} | {alive} | {package}: {app}"


# ---- scenarios -------------------------------------------------------------------------------------------------

def baseline():
    print('-- idle baseline (read-only)')
    s = sample({PKG})
    print(describe(s, PKG))
    print('biggest processes:', ', '.join(f"{t['name']} {t['rss_mb']}" for t in s['top']))
    kernel = sh('cat /sys/module/lowmemorykiller/parameters/minfree /sys/module/lowmemorykiller/parameters/adj 2>&1; cat /proc/swaps')
    print('kernel limits:\n' + kernel.strip())
    return {'baseline': s, 'kernel': kernel}


def run_app(package, trim, seconds):
    watch = {package, PKG}
    result = {'package': package, 'trim': trim, 'samples': []}
    print(f'-- {package}: this launches the app on the TV screen')
    before = sample(watch)
    result['before'] = before
    print('before :', describe(before, package))
    sh(f'monkey -p {package} -c android.intent.category.LAUNCHER 1')
    started = time.time()
    trimmed_at = None
    while time.time() - started < seconds:
        time.sleep(3)
        s = sample(watch)
        s['since_launch'] = round(time.time() - started, 1)
        result['samples'].append(s)
        print(f"+{s['since_launch']:5.1f}s :", describe(s, package))
        if trim and trimmed_at is None and s['since_launch'] >= seconds / 2:
            sh(f'am kill {package}')
            trimmed_at = s['since_launch']
            print(f'         `am kill {package}` sent (kills only background processes that are safe to lose)')
    result['trimmed_at'] = trimmed_at
    sh('input keyevent 3')  # HOME
    time.sleep(2)
    sh(f'am force-stop {package}')
    time.sleep(3)
    after = sample(watch)
    result['after'] = after
    print('after  :', describe(after, package))
    low = min((s['available_mb'] for s in result['samples']), default=before['available_mb'])
    gone = [s for s in result['samples'] if not any(package_of(p['name']) == PKG for p in s['procs'])]
    print(f"lowest available memory {low} MB (was {before['available_mb']}); bridge missing in {len(gone)} of {len(result['samples'])} samples")
    return result


def watch(package, seconds):
    """Samples only: no launching, no stopping. For measuring while something else (星仪, the user) drives the app."""
    watched = {package, PKG}
    result = {'package': package, 'mode': 'watch', 'samples': []}
    started = time.time()
    while time.time() - started < seconds:
        s = sample(watched)
        s['since_start'] = round(time.time() - started, 1)
        result['samples'].append(s)
        print(f"+{s['since_start']:5.1f}s :", describe(s, package), flush=True)
        time.sleep(3)
    samples = result['samples']
    if samples:
        low = min(s['available_mb'] for s in samples)
        gone = sum(1 for s in samples if not any(package_of(p['name']) == PKG for p in s['procs']))
        print(f"lowest available memory {low} MB; bridge missing in {gone} of {len(samples)} samples")
    return result


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='cmd', required=True)
    sub.add_parser('baseline')
    app = sub.add_parser('app')
    app.add_argument('package')
    app.add_argument('--trim', action='store_true')
    app.add_argument('--seconds', type=float, default=45)
    w = sub.add_parser('watch')
    w.add_argument('package')
    w.add_argument('--seconds', type=float, default=90)
    args = parser.parse_args()
    if args.cmd == 'baseline':
        data = baseline()
    elif args.cmd == 'watch':
        data = watch(args.package, args.seconds)
    else:
        data = run_app(args.package, args.trim, args.seconds)
    out = HERE / 'out'
    out.mkdir(exist_ok=True)
    path = out / time.strftime(f'mem-{args.cmd}-%Y%m%d-%H%M%S.json')
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding='utf-8')
    print('saved', path)


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main()
