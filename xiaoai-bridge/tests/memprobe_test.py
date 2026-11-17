"""Offline checks for tools/memprobe.py parsing and sampling: python -m unittest tests/memprobe_test.py"""
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'tools'))
import memprobe  # noqa: E402

MEMINFO = """MemTotal:        1962376 kB
MemFree:          102400 kB
MemAvailable:     640320 kB
Buffers:           12000 kB
Cached:           480000 kB
SwapCached:            0 kB
Active(anon):     600000 kB
SwapTotal:             0 kB
SwapFree:              0 kB
"""

PS = """USER           PID  PPID     VSZ    RSS WCHAN            ADDR S NAME
root             1     0   30000   2000 SyS_epoll_wait      0 S init
u0_a45        2345   300 1900000 380000 SyS_epoll_wait      0 S tv.danmaku.bili
u0_a45        2400   300 1800000 372000 SyS_epoll_wait      0 S tv.danmaku.bili:web
u0_a45        2500   300 1700000 301000 SyS_epoll_wait      0 S tv.danmaku.bili:download
u0_a45        2600   300 1700000 294000 SyS_epoll_wait      0 S tv.danmaku.bili:player
u0_a77         900   300 1500000  80000 SyS_epoll_wait      0 S xyz.erapi0neer.xiaoaibridge
system        1500   300 2400000 150000 SyS_epoll_wait      0 S system_server
u0_a12        1700   300  900000  60000 0                    0 S com.example.other
"""

PROC = """2345 0 VmRSS:\t  380000 kB
2400 900 VmRSS:\t  372000 kB
2500 900 VmRSS:\t  301000 kB
2600 100 VmRSS:\t  294000 kB
900 -700 VmRSS:\t   80000 kB
"""


class Parsing(unittest.TestCase):
    def test_meminfo(self):
        m = memprobe.parse_meminfo(MEMINFO)
        self.assertEqual((m['MemTotal'], m['MemAvailable'], m['Cached'], m['SwapFree']), (1962376, 640320, 480000, 0))
        self.assertEqual(m['Active'], 600000, 'Active(anon) is read as Active')

    def test_ps_keeps_pid_name_and_rss(self):
        rows = memprobe.parse_ps(PS)
        self.assertEqual(len(rows), 8)
        self.assertIn((2500, 'tv.danmaku.bili:download', 301000), rows)
        self.assertEqual(memprobe.parse_ps('garbage\nline two'), [])

    def test_proc_lines(self):
        p = memprobe.parse_proc_lines(PROC)
        self.assertEqual(p[2400], (900, 372000))
        self.assertEqual(p[900], (-700, 80000))
        self.assertEqual(memprobe.parse_proc_lines('2345 0 VmRSS: gone'), {})

    def test_packages_group_the_helper_processes(self):
        rows = memprobe.parse_ps(PS)
        names = [r[1] for r in memprobe.by_package(rows, 'tv.danmaku.bili')]
        self.assertEqual(names, ['tv.danmaku.bili', 'tv.danmaku.bili:web', 'tv.danmaku.bili:download', 'tv.danmaku.bili:player'])
        self.assertEqual(memprobe.package_of('tv.danmaku.bili:web'), 'tv.danmaku.bili')


class Sampling(unittest.TestCase):
    def fake_sh(self, command, timeout=30):
        if command == 'cat /proc/meminfo':
            return MEMINFO
        if command == 'ps -A':
            return PS
        if command.startswith('for p in '):
            self.loop = command
            return PROC
        raise AssertionError(command)

    def test_sample_reads_only_the_watched_packages(self):
        with mock.patch.object(memprobe, 'sh', side_effect=self.fake_sh):
            s = memprobe.sample({'tv.danmaku.bili', memprobe.PKG})
        self.assertEqual(s['available_mb'], 625)
        names = sorted(p['name'] for p in s['procs'])
        self.assertEqual(len(names), 5)
        self.assertNotIn('com.example.other', names)
        self.assertIn('for p in 2345 2400 2500 2600 900;', self.loop)
        web = next(p for p in s['procs'] if p['name'].endswith(':web'))
        self.assertEqual((web['rss_mb'], web['adj']), (363, 900))
        self.assertEqual(s['top'][0]['name'], 'tv.danmaku.bili')

    def test_describe_says_when_the_bridge_is_gone(self):
        with mock.patch.object(memprobe, 'sh', side_effect=self.fake_sh):
            s = memprobe.sample({'tv.danmaku.bili', memprobe.PKG})
        text = memprobe.describe(s, 'tv.danmaku.bili')
        self.assertIn('bridge up', text)
        self.assertIn('web=363', text)
        s['procs'] = [p for p in s['procs'] if not p['name'].startswith(memprobe.PKG)]
        self.assertIn('BRIDGE GONE', memprobe.describe(s, 'tv.danmaku.bili'))

    def test_watch_only_reads(self):
        commands = []

        def recording_sh(command, timeout=30):
            commands.append(command)
            return self.fake_sh(command, timeout)

        clock = iter(range(0, 1000))  # one second per call; a loop reads the clock three times
        with mock.patch.object(memprobe, 'sh', side_effect=recording_sh), \
                mock.patch.object(memprobe.time, 'time', side_effect=lambda: next(clock)), \
                mock.patch.object(memprobe.time, 'sleep'):
            result = memprobe.watch('tv.danmaku.bili', 10)
        self.assertGreaterEqual(len(result['samples']), 2)
        for c in commands:
            self.assertFalse(any(w in c for w in ('monkey', 'am start', 'force-stop', 'am kill', 'input ')), c)


if __name__ == '__main__':
    unittest.main()
