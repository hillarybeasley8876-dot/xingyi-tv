"""Write both model routes into the TV app's private files/config.json, then return to the release build.

    bash build.sh debug && bash build.sh        # same package, versionCode and signing certificate
    python provision_minimax.py [--report ../evidence-0.5/provision.json]

Routes written (config.json version 2):
    official  https://api.minimax.io        MiniMax-M3.1-Flash   key: HKCU\\Environment\\MINIMAX_API_KEY
    gateway   https://gateway.example.com  MiniMax-M3.1-Flash   key: HKCU\\Environment\\GATEWAY_API_KEY

Procedure: compare both APKs with the installed package (package, version, certificate) and record its
firstInstallTime -> install the debug build over it (app data is kept) -> hash every private file -> copy
files/ and shared_prefs/ to the app-private no_backup/provision-backup and verify the copy -> write config.json
from adb's stdin -> hash again: only files/config.json may differ, and it must match what was sent -> install the
release build (data kept) -> confirm debuggable is off, run-as is refused and firstInstallTime did not change.
If any check after the backup fails, config.json is restored from the backup before the release build goes back.

Credentials are read with winreg and reach the TV only through adb's stdin. They are never printed, never put on a
command line and never written to a PC file. Output lists file names, sizes, modes and 12-hex-digit SHA-256
prefixes only. The local ADB identity (files/xingyi-adb-key.pk8) must come out byte-identical.
"""
import argparse
import ctypes
import datetime
import hashlib
import io
import json
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import winreg

ctypes.windll.kernel32.SetPriorityClass(ctypes.windll.kernel32.GetCurrentProcess(), 0x4000)  # below normal
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

ROOT = pathlib.Path(__file__).resolve().parent
ADB = r'H:\tools\platform-tools\adb.exe'
SERIAL = '192.0.2.10:5555'
PKG = 'xyz.erapi0neer.xingyi'
DATA = '/data/user/0/' + PKG
BT = pathlib.Path(r'H:\tools\android-sdk\android-15')
DEBUG_APK = ROOT / 'build' / 'xingyi-debug.apk'
RELEASE_APK = ROOT / 'build' / 'xingyi.apk'
MODEL = 'MiniMax-M3.1-Flash'
OFFICIAL = 'https://api.minimax.io'
GATEWAY = 'https://gateway.example.com'
ADB_IDENTITY = './files/xingyi-adb-key.pk8'
BACKUP = 'no_backup/provision-backup'


class Stop(Exception):
    pass


def adb(*args, data=None, timeout=60, check=True, stderr=False):
    """argv never contains credentials; data (stdin) may. stderr=True appends the error stream."""
    result = subprocess.run([ADB, '-s', SERIAL, *args], input=data, capture_output=True, timeout=timeout)
    out = (result.stdout + (result.stderr if stderr else b'')).decode('utf-8', 'replace').replace('\r', '')
    if check and result.returncode:
        raise Stop('adb %s failed (exit %d): %s' % (args[0], result.returncode,
                                                    result.stderr.decode('utf-8', 'replace').strip()[:300]))
    return out


def as_app(command, data=None, check=True):
    # The command is a fixed string; it is single-quoted for the device shell and contains no secrets.
    assert "'" not in command
    return adb('shell', "run-as %s sh -c 'cd %s && %s'" % (PKG, DATA, command), data=data, check=check)


def keys():
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Environment') as reg:
        found = {}
        for route, name in (('official', 'MINIMAX_API_KEY'), ('gateway', 'GATEWAY_API_KEY')):
            try:
                value = winreg.QueryValueEx(reg, name)[0]
            except FileNotFoundError:
                raise Stop('HKCU\\Environment\\%s is missing' % name)
            if not (0 < len(value) <= 4096 and all(33 <= ord(c) <= 126 for c in value)):
                raise Stop('HKCU\\Environment\\%s is not a printable single-line key' % name)
            found[route] = value
    return found


def apk_facts(path):
    badging = subprocess.run([str(BT / 'aapt.exe'), 'dump', 'badging', str(path)], capture_output=True,
                             text=True, encoding='utf-8', errors='replace').stdout
    tree = subprocess.run([str(BT / 'aapt.exe'), 'dump', 'xmltree', str(path), 'AndroidManifest.xml'],
                          capture_output=True, text=True, encoding='utf-8', errors='replace').stdout
    certs = subprocess.run(['cmd', '/c', str(BT / 'apksigner.bat'), 'verify', '--print-certs', str(path)],
                           capture_output=True, text=True, encoding='utf-8', errors='replace').stdout
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    debuggable = re.search(r'android:debuggable\(0x0101000f\)=\(type 0x12\)(0x[0-9a-f]+)', tree)
    cert = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', certs)
    if not (package and cert):
        raise Stop('cannot read %s' % path.name)
    return {'package': package.group(1), 'versionCode': int(package.group(2)), 'versionName': package.group(3),
            'debuggable': bool(debuggable and debuggable.group(1) != '0x0'), 'certificate_sha256': cert.group(1),
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}


def installed():
    dump = adb('shell', 'dumpsys package ' + PKG)
    def field(name):
        match = re.search(r'\b%s=([^\n]+)' % name, dump)
        return match.group(1).strip() if match else ''
    return {'versionCode': int((re.search(r'versionCode=(\d+)', dump) or [0, 0])[1]),
            'versionName': field('versionName'), 'firstInstallTime': field('firstInstallTime'),
            'lastUpdateTime': field('lastUpdateTime'), 'debuggable': 'DEBUGGABLE' in field('pkgFlags')}


def installed_certificate():
    path = adb('shell', 'pm path ' + PKG).strip().replace('package:', '')
    folder = pathlib.Path(tempfile.mkdtemp(prefix='xingyi-installed-'))
    try:
        adb('pull', path, str(folder / 'base.apk'), timeout=120)
        return apk_facts(folder / 'base.apk')['certificate_sha256']
    finally:
        shutil.rmtree(folder, ignore_errors=True)


def manifest(prefix='.'):
    """{relative path: (mode, size, sha256)} for every private file, excluding caches and the backup copy.
    Filtering happens here: toybox find -path does not let * match a slash."""
    listing = as_app('find %s -type f -exec stat -c "%%a %%s %%n" {} + ; find %s -type f -exec sha256sum {} +'
                     % (prefix, prefix))
    modes, digests = {}, {}
    for line in listing.splitlines():
        stat = re.fullmatch(r'(\d{3,4}) (\d+) (.+)', line)
        digest = re.fullmatch(r'([0-9a-f]{64})  (.+)', line)
        if digest:
            digests[digest.group(2)] = digest.group(1)
        elif stat:
            modes[stat.group(3)] = (stat.group(1), int(stat.group(2)))
    if set(modes) != set(digests) or not modes:
        raise Stop('could not list the private files')
    strip = len(prefix)
    files = {'.' + path[strip:]: (modes[path][0], modes[path][1], digests[path]) for path in modes}
    skip = ('./cache/', './code_cache/', './' + BACKUP + '/')
    return {path: value for path, value in files.items() if not path.startswith(skip)}


def show(title, files):
    print(title)
    for path in sorted(files):
        mode, size, digest = files[path]
        print('   %-44s %4s %7d  %s' % (path, mode, size, digest[:12]))


def report_view(files):
    return {path: {'mode': m, 'size': s, 'sha256_12': d[:12]} for path, (m, s, d) in sorted(files.items())}


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--report', type=pathlib.Path, help='write a sanitized JSON record here')
    options = parser.parse_args()
    record = {'time': datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),
              'credentials_logged': False}
    secrets = keys()
    body = json.dumps({'version': 2,
                       'official': {'baseUrl': OFFICIAL, 'model': MODEL, 'apiKey': secrets['official']},
                       'gateway': {'baseUrl': GATEWAY, 'model': MODEL, 'apiKey': secrets['gateway']}},
                      separators=(',', ':')).encode('ascii')
    body_digest = hashlib.sha256(body).hexdigest()
    record['config'] = {'version': 2, 'routes': {'official': [OFFICIAL, MODEL], 'gateway': [GATEWAY, MODEL]},
                        'bytes': len(body), 'sha256_12': body_digest[:12]}

    debug, release = apk_facts(DEBUG_APK), apk_facts(RELEASE_APK)
    if not (debug['package'] == release['package'] == PKG and debug['versionCode'] == release['versionCode']
            and debug['certificate_sha256'] == release['certificate_sha256']
            and debug['debuggable'] and not release['debuggable']):
        raise Stop('build/xingyi-debug.apk and build/xingyi.apk must be the same package, version and certificate, '
                   'debuggable only in the debug build')
    record['apks'] = {'debug': debug, 'release': release}
    if adb('get-state').strip() != 'device':
        raise Stop('TV not connected over adb')
    before = installed()
    if before['versionCode'] > release['versionCode']:
        raise Stop('installed versionCode %d is newer than this build' % before['versionCode'])
    certificate = installed_certificate()
    if certificate != release['certificate_sha256']:
        raise Stop('installed app is signed with another certificate; never uninstall to fix this')
    record['installed_before'] = dict(before, certificate_sha256=certificate)
    print('Installed: %s (%d), first installed %s, same certificate.' % (
        before['versionName'], before['versionCode'], before['firstInstallTime']))

    print('Installing the debug build over it (data kept) ...')
    if 'Success' not in adb('install', '-r', str(DEBUG_APK), timeout=180):
        raise Stop('debug install failed; the installed app and its data are unchanged')
    restored = False
    try:
        as_app('true')
        files_before = manifest()
        show('Private files before provisioning (no_backup/provision-backup excluded):', files_before)
        if ADB_IDENTITY not in files_before:
            raise Stop('the local ADB identity is missing before provisioning; stopping without changes')
        record['files_before'] = report_view(files_before)

        as_app('rm -rf %s && mkdir -p %s && for d in files shared_prefs databases; do '
               'if [ -d $d ]; then cp -pR $d %s/; fi; done' % (BACKUP, BACKUP, BACKUP))
        backup = manifest('./' + BACKUP)
        expected = {p: v for p, v in files_before.items() if p.split('/')[1] in ('files', 'shared_prefs', 'databases')}
        if {p: v[1:] for p, v in backup.items()} != {p: v[1:] for p, v in expected.items()}:
            raise Stop('backup copy does not match the private files; nothing was changed')
        print('Backup verified: %d files copied to the app-private %s.' % (len(backup), BACKUP))
        record['backup'] = {'location': 'app-private ' + BACKUP, 'files': len(backup), 'verified': True}

        try:
            as_app('mkdir -p files && umask 077 && cat > files/config.json.new && chmod 600 files/config.json.new '
                   '&& mv files/config.json.new files/config.json', data=body)
            files_after = manifest()
            show('Private files after provisioning:', files_after)
            changed = sorted(p for p in set(files_before) | set(files_after)
                             if files_before.get(p) != files_after.get(p))
            if not set(changed) <= {'./files/config.json'}:
                raise Stop('unexpected private file changes: %s' % changed)
            if files_after['./files/config.json'][2] != body_digest or files_after['./files/config.json'][0] != '600':
                raise Stop('config.json on the TV does not match what was sent')
            if files_after[ADB_IDENTITY] != files_before[ADB_IDENTITY]:
                raise Stop('local ADB identity changed')
        except Exception:
            as_app('cp -p %s/files/config.json files/config.json' % BACKUP, check=False)
            restored = manifest().get('./files/config.json') == files_before.get('./files/config.json')
            print('Restored the previous config.json from the backup: %s' % restored)
            raise
        record['files_after'] = report_view(files_after)
        record['changed'] = changed
        print('%s; it matches the sent body (sha256 %s). ADB identity unchanged (%s).'
              % ('Only files/config.json changed' if changed else 'config.json already held these routes',
                 body_digest[:12], files_after[ADB_IDENTITY][2][:12]))
    finally:
        print('Installing the release build (data kept) ...')
        if 'Success' not in adb('install', '-r', str(RELEASE_APK), timeout=180):
            print('RELEASE INSTALL FAILED: the debug build is still installed. Install build/xingyi.apk with '
                  'adb install -r before leaving the TV.')
            raise Stop('release install failed')
    after = installed()
    refused = 'not debuggable' in adb('shell', 'run-as %s true' % PKG, check=False, stderr=True)
    if not (after['versionCode'] == release['versionCode'] and not after['debuggable'] and refused
            and after['firstInstallTime'] == before['firstInstallTime']):
        raise Stop('release checks failed: %s' % after)
    record['installed_after'] = dict(after, run_as_refused=refused)
    print('Release %s installed: debuggable off, run-as refused, first install time unchanged (%s).'
          % (after['versionName'], after['firstInstallTime']))
    if options.report:
        text = json.dumps(record, ensure_ascii=False, indent=2)
        if any(secret[i:i + 16] in text for secret in secrets.values() for i in range(0, len(secret) - 15)):
            raise Stop('refusing to write a report containing credential text')
        options.report.write_text(text, encoding='utf-8')
        print('Report:', options.report.resolve())


if __name__ == '__main__':
    try:
        main()
    except Stop as error:
        print('STOPPED:', error)
        sys.exit(1)
