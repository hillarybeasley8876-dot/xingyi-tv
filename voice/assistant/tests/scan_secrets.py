"""Scan for the two route keys without printing them: TV logcat, the TV's shared diagnostic files, every APK
(raw and inflated entries) and the whole H:\\tools\\mitv\\voice tree (raw bytes, UTF-8 and UTF-16LE).

The keys are read from HKCU\\Environment and only used to build search probes in memory. The report holds counts.
Probe sets per key: the first 16 characters (as the release checklist asks), the last 16, and every
16-character window at a fixed stride. The MiniMax key is a JWT, so its first 16 characters are the base64 of
the standard {"alg":"RS256", header that every such token starts with; a hit on that probe alone is not a leak,
which is why the distinctive windows are scanned too.

    python scan_secrets.py [--report ../../evidence-0.5/secret-scan.json]
"""
import argparse
import ctypes
import io
import json
import pathlib
import subprocess
import sys
import winreg
import zipfile

ctypes.windll.kernel32.SetPriorityClass(ctypes.windll.kernel32.GetCurrentProcess(), 0x4000)  # below normal
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
ADB = [r'H:\tools\platform-tools\adb.exe', '-s', '192.168.31.162:5555']
TREE = pathlib.Path(r'H:\tools\mitv\voice')
APP_FILES = '/sdcard/Android/data/xyz.erapi0neer.xingyi/files/'


def probes():
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Environment') as reg:
        keys = {'MINIMAX_API_KEY': winreg.QueryValueEx(reg, 'MINIMAX_API_KEY')[0],
                'GATEWAY_API_KEY': winreg.QueryValueEx(reg, 'GATEWAY_API_KEY')[0]}
    sets = {}
    for name, key in keys.items():
        windows = {key[i:i + 16] for i in range(0, len(key) - 15, 8)} | {key[-16:]}
        sets[name] = {'first16': [key[:16]], 'last16': [key[-16:]], 'windows': sorted(windows)}
    return sets


def encodings(text):
    return (text.encode('utf-8'), text.encode('utf-16-le'))


class Scanner:
    def __init__(self, sets):
        self.sets = {name: {kind: [b for p in values for b in encodings(p)] for kind, values in kinds.items()}
                     for name, kinds in sets.items()}

    def hits(self, data):
        found = {}
        for name, kinds in self.sets.items():
            for kind, needles in kinds.items():
                count = sum(data.count(n) for n in needles)
                if count:
                    found['%s.%s' % (name, kind)] = count
        return found


def apk_entries(data):
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            return [(info.filename, archive.read(info)) for info in archive.infolist()]
    except zipfile.BadZipFile:
        return []


def merge(total, found):
    for key, value in found.items():
        total[key] = total.get(key, 0) + value


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--report', type=pathlib.Path)
    options = parser.parse_args()
    scanner = Scanner(probes())
    report = {'probes': {'per_key': ['first16', 'last16', 'windows (16 chars, stride 8)'],
                         'encodings': ['utf-8', 'utf-16-le'], 'printed': False}}

    log = subprocess.run(ADB + ['logcat', '-d', '-b', 'all'], capture_output=True, timeout=120).stdout
    report['tv_logcat'] = {'bytes': len(log), 'lines': log.count(b'\n'),
                           'xingyi_lines': sum(1 for line in log.split(b'\n') if b'xingyi' in line),
                           'hits': scanner.hits(log)}
    listing = subprocess.run(ADB + ['shell', 'ls ' + APP_FILES], capture_output=True, timeout=30).stdout.decode()
    shared = {}
    for name in listing.split():
        data = subprocess.run(ADB + ['exec-out', 'cat ' + APP_FILES + name], capture_output=True, timeout=30).stdout
        shared[name] = {'bytes': len(data), 'hits': scanner.hits(data)}
    report['tv_shared_diagnostics'] = shared

    files = hits_total = 0
    tree_hits, apks = {}, {}
    for path in sorted(TREE.rglob('*')):
        if not path.is_file():
            continue
        data = path.read_bytes()
        files += 1
        found = scanner.hits(data)
        if path.suffix.lower() in ('.apk', '.zip', '.jar'):
            inner = {}
            for entry, content in apk_entries(data):
                merge(inner, scanner.hits(content))
            apks[str(path.relative_to(TREE))] = {'raw': found, 'inflated_entries': inner}
            merge(found, inner)
        if found:
            tree_hits[str(path.relative_to(TREE))] = found
            hits_total += sum(found.values())
    report['source_tree'] = {'root': str(TREE), 'files': files, 'files_with_hits': tree_hits, 'apks': apks}
    clean = (not report['tv_logcat']['hits'] and not any(v['hits'] for v in shared.values()) and not tree_hits)
    report['clean'] = clean
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if options.report:
        options.report.write_text(text, encoding='utf-8')


if __name__ == '__main__':
    main()
