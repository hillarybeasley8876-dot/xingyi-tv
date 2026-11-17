"""Runs tools/adb_recover.py against a fake adb and a local listener: python -m unittest tests/adb_recover_test.py"""
import os
import socket
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
SCRIPT = ROOT / 'tools' / 'adb_recover.py'
FAKE = HERE / 'fake_adb.cmd'

sys.path.insert(0, str(ROOT / 'tools'))
import adb_recover  # noqa: E402

# the line AdbGuardPolicy.describe writes on the TV (the same literals are asserted in AdbGuardPolicyTest.java)
GUARD_LINE = 'adb guard: permission=granted adb_enabled=1 listening=yes enables_2min=0 last_restart=never'


def run(tv, extra_env=None, args=()):
    work = Path(tempfile.mkdtemp())
    env = dict(os.environ, TV=tv, ADB_EXE=str(FAKE), FAKE_ADB_LOG=str(work / 'adb.log'), ADB_RECOVER_OUT=str(work / 'out'),
               PYTHONUTF8='1')
    env.update(extra_env or {})
    p = subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, env=env, timeout=180)
    log = (work / 'adb.log').read_text(encoding='utf-8') if (work / 'adb.log').exists() else ''
    evidence = sorted((work / 'out').glob('adb-recovery-*.txt')) if (work / 'out').exists() else []
    return p.returncode, p.stdout.decode('utf-8', 'replace'), log, (evidence[-1].read_text(encoding='utf-8') if evidence else '')


class Listener:
    def __init__(self):
        self.sock = socket.socket()
        self.sock.bind(('127.0.0.1', 0))
        self.sock.listen(5)
        self.tv = f'127.0.0.1:{self.sock.getsockname()[1]}'

    def close(self):
        self.sock.close()


def with_listener(extra_env=None, args=('--wait-minutes', '1', '--settle-seconds', '0')):
    listener = Listener()
    try:
        return run(listener.tv, extra_env, args)
    finally:
        listener.close()


class Parsing(unittest.TestCase):
    def test_guard_line(self):
        status = 'reader: x\n' + GUARD_LINE + '\nadb guard last action: 11-04 21:50:12 guard: adb_enabled 0 -> 1 (back)\n--- log ---'
        self.assertEqual(adb_recover.parse_guard(status), {'permission': 'granted', 'adb_enabled': '1', 'listening': 'yes',
                                                          'enables_2min': '0', 'last_restart': 'never'})
        self.assertEqual(adb_recover.last_guard_action(status), '11-04 21:50:12 guard: adb_enabled 0 -> 1 (back)')

    def test_older_build_has_no_guard_line(self):
        self.assertEqual(adb_recover.parse_guard('reader: x\nREAD_LOGS: true'), {})
        self.assertEqual(adb_recover.last_guard_action('reader: x'), '')

    def test_adb_transport_errors_are_told_from_command_output(self):
        for text in ('adb.exe: device offline', '  adb.exe: device offline\n', 'error: closed', 'error: device not found'):
            self.assertTrue(adb_recover.transport_error(text), text)
        for text in ('', '1\n37', 'permission=granted', '11-04 22:03:08  reboot: boot_count 37 -> 38',
                     '1\nadb.exe: device offline'):  # only the first line is adb's own
            self.assertFalse(adb_recover.transport_error(text), text)


class AdbRecover(unittest.TestCase):
    def test_whole_flow_once_the_listener_answers(self):
        code, out, log, evidence = with_listener()
        self.assertEqual(code, 0, out)
        lines = log.splitlines()
        self.assertTrue(any(' install -r ' in l and l.endswith('xiaoai-bridge.apk') for l in lines), 'installs the build')
        for permission in ('READ_LOGS', 'DUMP', 'WRITE_SECURE_SETTINGS'):
            self.assertTrue(any('pm grant xyz.erapi0neer.xiaoaibridge android.permission.' + permission in l for l in lines), permission)
        self.assertTrue(any('settings put secure enabled_accessibility_services' in l for l in lines), 'adds the missing accessibility service')
        self.assertTrue(any('am start-foreground-service' in l for l in lines), 'starts the service')
        self.assertTrue(any('am broadcast -a xyz.erapi0neer.xiaoaibridge.STATUS' in l for l in lines), 'asks the bridge for its status')
        rm = next(i for i, l in enumerate(lines) if 'rm -f' in l and 'status.txt' in l)
        asked = next(i for i, l in enumerate(lines) if 'STATUS' in l)
        self.assertLess(rm, asked, 'removes the old status file before asking, so a leftover is never read as fresh')
        # facts come before the install, while the window is open
        first_install = next(i for i, l in enumerate(lines) if ' install ' in l)
        first_facts = next(i for i, l in enumerate(lines) if 'adb_enabled' in l)
        self.assertLess(first_facts, first_install, 'records the outage facts before changing anything')
        for title in ('adb settings', 'who touched adb', 'accessibility', 'memory baseline'):
            self.assertIn(title, evidence)
        self.assertIn('guard: ok', out)
        # nothing in the snapshot may change the TV
        snapshot = [l for l in lines[:first_install] if ' shell ' in l]
        self.assertFalse(any(w in l for l in snapshot for w in ('settings put', 'pm grant', 'am force-stop', 'input ')), snapshot)
        # and without --test-guard ADB debugging is never switched off
        self.assertFalse(any('adb_enabled 0' in l for l in lines))

    def test_guard_missing_permission_is_reported(self):
        code, out, log, evidence = with_listener({'FAKE_ADB_GUARD': 'missing'})
        self.assertEqual(code, 0, out)
        self.assertIn('guard: NOT ok', out)

    def test_older_build_without_status_line_is_reported(self):
        code, out, log, evidence = with_listener({'FAKE_ADB_GUARD': 'old'})
        self.assertEqual(code, 0, out)
        self.assertIn('no status line yet', out)
        self.assertIn('guard: NOT ok', out)

    def test_guard_test_passes_when_the_guard_records_switching_it_back_on(self):
        code, out, log, evidence = with_listener(args=('--wait-minutes', '1', '--settle-seconds', '0', '--skip-snapshot',
                                                       '--skip-install', '--test-guard'))
        self.assertEqual(code, 0, out)
        self.assertTrue(any('settings put global adb_enabled 0' in l for l in log.splitlines()), 'flips the setting')
        self.assertIn('guard test: passed', out)
        self.assertFalse(any(' install ' in l for l in log.splitlines()), 'skip-install is respected')
        self.assertNotIn('adb settings', evidence, 'skip-snapshot is respected')

    def test_guard_test_is_not_proven_when_nothing_records_the_repair(self):
        code, out, log, evidence = with_listener({'FAKE_ADB_GUARD': 'deaf'}, ('--wait-minutes', '1', '--settle-seconds', '0',
                                                                             '--skip-snapshot', '--skip-install', '--test-guard'))
        self.assertEqual(code, 0, out)
        self.assertIn('NOT proven', out)

    def test_gives_up_when_the_listener_never_answers(self):
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0))
            tv = f'127.0.0.1:{s.getsockname()[1]}'
        code, out, log, evidence = run(tv, args=('--wait-minutes', '0.05'))
        self.assertEqual(code, 2)
        self.assertEqual(log, '', 'never touches adb without a listener')

    def test_failed_install_still_keeps_the_evidence_and_skips_the_rest(self):
        code, out, log, evidence = with_listener({'FAKE_ADB_INSTALL': 'fail'})
        self.assertEqual(code, 0, out)
        self.assertIn('who touched adb', evidence)
        self.assertFalse(any('pm grant' in l for l in log.splitlines()), 'no grants after a failed install')
        self.assertFalse(any('STATUS' in l for l in log.splitlines()), 'no guard check after a failed install')


if __name__ == '__main__':
    unittest.main()
