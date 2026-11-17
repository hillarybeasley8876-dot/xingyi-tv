"""Offline checks for tools/reboot_test.py (the parts that decide, not the ones that reboot):
python -m unittest tests/reboot_test_test.py"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'tools'))
import reboot_test as rt  # noqa: E402

# lines copied from the TV (2026-11-04 22:00)
FOCUS_LAUNCHER = ('  mFocusedApp=AppWindowToken{3f60f4 token=Token{bcce2c7 ActivityRecord{c48cc06 u0 '
                  'xyz.erapi0neer.orrery/.HomeActivity t2}}}')
FOCUS_BILI = ('  mFocusedApp=AppWindowToken{1a2b3c token=Token{4d5e6f ActivityRecord{7a8b9c u0 '
              'tv.danmaku.bili/.MainActivityV2 t9}}}')
NO_SESSIONS = '  Sessions Stack - have 0 sessions:'
PLAYING = ('  Sessions Stack - have 1 sessions:\n    MediaSession com.netease.cloudmusic.tv/cloudmusic (userId=0)\n'
           '      state=PlaybackState {state=3, position=81234, buffered position=0, speed=1.0, updated=5, actions=823}')
PAUSED = PLAYING.replace('state=3', 'state=2')


class Parsing(unittest.TestCase):
    def test_wakefulness(self):
        self.assertEqual(rt.parse_wakefulness('  mWakefulness=Awake\n  mWakefulnessChanging=false'), 'Awake')
        self.assertEqual(rt.parse_wakefulness('  mWakefulness=Asleep'), 'Asleep')
        self.assertEqual(rt.parse_wakefulness(''), '')

    def test_focused_package_ignores_the_settings_overlay(self):
        both = '  mCurrentFocus=Window{d66fd96 u0 settings_window}\n' + FOCUS_LAUNCHER
        self.assertEqual(rt.parse_focused_package(both), 'xyz.erapi0neer.orrery')
        self.assertEqual(rt.parse_focused_package(FOCUS_BILI), 'tv.danmaku.bili')
        self.assertEqual(rt.parse_focused_package('mFocusedApp=null'), '')

    def test_playing(self):
        self.assertFalse(rt.parse_playing(NO_SESSIONS))
        self.assertTrue(rt.parse_playing(PLAYING))
        self.assertFalse(rt.parse_playing(PAUSED), 'paused is not playing')
        self.assertTrue(rt.parse_playing(PAUSED + '\n' + PLAYING), 'any playing session counts')


class Idle(unittest.TestCase):
    def test_launcher_and_settings_are_quiet(self):
        self.assertEqual(rt.idle_verdict('Awake', 'xyz.erapi0neer.orrery', False), (True, ''))
        self.assertEqual(rt.idle_verdict('Awake', 'com.xiaomi.mitv.settings', False), (True, ''))

    def test_playing_blocks_even_in_standby(self):
        ok, why = rt.idle_verdict('Asleep', '', True)
        self.assertFalse(ok)
        self.assertIn('playing', why)

    def test_an_app_in_front_blocks_unless_the_screen_is_off(self):
        ok, why = rt.idle_verdict('Awake', 'tv.danmaku.bili', False)
        self.assertFalse(ok)
        self.assertIn('tv.danmaku.bili', why)
        self.assertTrue(rt.idle_verdict('Asleep', 'tv.danmaku.bili', False)[0])


class Timeline(unittest.TestCase):
    def test_normal_reboot(self):
        samples = [(0, True), (1, True), (3, False), (5, False), (40, False), (70, False), (82, True)]
        self.assertEqual(rt.timeline(samples), {'down_at': 3, 'up_at': 82})

    def test_never_went_down(self):
        self.assertEqual(rt.timeline([(0, True), (2, True), (4, True)]), {'down_at': None, 'up_at': None})

    def test_still_down(self):
        self.assertEqual(rt.timeline([(0, True), (2, False), (60, False)]), {'down_at': 2, 'up_at': None})

    def test_a_short_blip_is_adbd_restarting_not_the_tv(self):
        samples = [(0, True), (2, False), (4, True), (6, True)]
        self.assertEqual(rt.timeline(samples), {'down_at': 2, 'up_at': None})
        later = samples + [(10, False), (50, False), (60, True)]
        self.assertEqual(rt.timeline(later), {'down_at': 2, 'up_at': 60})


class Verdict(unittest.TestCase):
    def test_passed(self):
        lines = ['11-04 22:05:01  reboot: boot_count 37 -> 38', '11-04 22:05:02  adb_enabled 1 -> 0',
                 '11-04 22:05:02  guard: adb_enabled 0 -> 1 (the TV had switched it off), listener back after 700 ms']
        self.assertTrue(rt.verdict(True, lines).startswith('PASSED'))

    def test_not_tested_when_the_setting_survived_the_reboot(self):
        self.assertTrue(rt.verdict(True, ['11-04 22:05:01  reboot: boot_count 37 -> 38']).startswith('NOT TESTED'))

    def test_unclear_when_it_was_off_but_nobody_repaired_it(self):
        self.assertTrue(rt.verdict(True, ['11-04 22:05:02  adb_enabled 1 -> 0']).startswith('UNCLEAR'))

    def test_unknown_when_the_bridge_wrote_nothing(self):
        self.assertTrue(rt.verdict(True, []).startswith('UNKNOWN'))

    def test_failed(self):
        self.assertTrue(rt.verdict(False, []).startswith('FAILED'))
        self.assertIn('listener stayed down', rt.verdict(False, ['x guard: adb_enabled 0 -> 1 (y)']))


# adb-watch.txt exactly as the TV wrote it on 2026-11-04 (the reboot at 22:02 is the one this test was written for)
WATCH = [
    '11-04 21:41:37  watch started, adb 5555 is up  [adb_enabled=1 boot_count=37 uptime=26717s persist.adb.tcp.port=5555 service.adb.tcp.port=5555 guard=off]',
    '11-04 21:51:15  adb_enabled 1 -> 0  [adb_enabled=1 boot_count=37 uptime=27295s persist.adb.tcp.port=5555 service.adb.tcp.port=5555 guard=on]',
    '11-04 21:51:15  guard: adb_enabled 0 -> 1 (the TV had switched it off), listener back after 4 ms  [adb_enabled=1 boot_count=37 uptime=27295s persist.adb.tcp.port=5555 service.adb.tcp.port=5555 guard=on]',
    '11-04 22:03:08  adb 5555 up -> down  [adb_enabled=1 boot_count=38 uptime=22s persist.adb.tcp.port=5555 service.adb.tcp.port=5555 guard=on]',
    '11-04 22:03:08  reboot: boot_count 37 -> 38  [adb_enabled=1 boot_count=38 uptime=22s persist.adb.tcp.port=5555 service.adb.tcp.port=5555 guard=on]',
    '11-04 22:03:08  adb_enabled 1 -> 0  [adb_enabled=1 boot_count=38 uptime=22s persist.adb.tcp.port=5555 service.adb.tcp.port=5555 guard=on]',
    '11-04 22:03:08  guard: adb_enabled 0 -> 1 (the TV had switched it off), listener back after 409 ms  [adb_enabled=1 boot_count=38 uptime=22s persist.adb.tcp.port=5555 service.adb.tcp.port=5555 guard=on]',
]


class RealRecord(unittest.TestCase):
    def test_lines_are_picked_by_boot(self):
        boot38 = rt.lines_for_boot(WATCH, '38')
        self.assertEqual(len(boot38), 4)
        self.assertTrue(all('boot_count=38' in l for l in boot38))
        self.assertEqual(len(rt.lines_for_boot(WATCH, '37')), 3)
        self.assertEqual(rt.lines_for_boot(WATCH, '3'), [], 'boot 3 is not boot 37 or 38')
        self.assertEqual(rt.lines_for_boot(['adb.exe: device offline'], '38'), [], 'an adb error is not a record')

    def test_the_real_reboot_is_a_pass(self):
        # the first version of this test judged the same reboot "NOT TESTED": it read adb's "device offline" error as the file
        self.assertTrue(rt.verdict(True, rt.lines_for_boot(WATCH, '38')).startswith('PASSED'))


if __name__ == '__main__':
    unittest.main()
