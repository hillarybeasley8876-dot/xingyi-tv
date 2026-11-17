"""Reboot the TV and watch what happens to adb: does it come back by itself, without anyone touching the remote?

python tools/reboot_test.py [--max-minutes 6] [--force]

On 2026-11-04 14:16 the TV rebooted and came back with ADB debugging switched off. The bridge's guard writes the setting
back on; this is the end-to-end check of that, on the real firmware. It reboots the TV, so it refuses while something is
playing or an app other than the launcher/settings is in front (--force skips the check). If the guard does not work
the remote is needed once more. Evidence goes to out/reboot-test-<time>.txt.
"""
import argparse
import re
import sys
import time
import urllib.request

import adb_recover as ar

LAUNCHER = 'xyz.erapi0neer.orrery'
QUIET_APPS = {LAUNCHER, 'com.xiaomi.mitv.settings', 'com.xiaomi.mitv.systemui', ''}
PLAYING = 3  # PlaybackState.STATE_PLAYING


# ---- pure parts (tests/reboot_test_test.py) ------------------------------------------------------------------

def parse_wakefulness(text):
    m = re.search(r'mWakefulness=(\w+)', text)
    return m.group(1) if m else ''


def parse_focused_package(text):
    """The app in front, from mFocusedApp. mCurrentFocus is usually the Xiaomi settings overlay, whatever is in front."""
    m = re.search(r'mFocusedApp=.*?ActivityRecord\{\w+ u\d+ ([\w.]+)/', text)
    return m.group(1) if m else ''


def parse_playing(text):
    """True when any media session reports PlaybackState.STATE_PLAYING."""
    return any(int(m.group(1)) == PLAYING for m in re.finditer(r'state=PlaybackState \{state=(\d+)', text))


def idle_verdict(wakefulness, focused, playing):
    """(ok to reboot, why not). Asleep counts as idle whatever was in front."""
    if playing:
        return False, 'a media session is playing'
    if wakefulness != 'Asleep' and focused not in QUIET_APPS:
        return False, f'{focused} is in front'
    return True, ''


def timeline(samples, min_down=10.0):
    """samples: [(seconds since the reboot command, port open)] in time order.
    down_at: first closed sample. up_at: first open sample after the port had been closed for at least min_down seconds
    (a closed stretch shorter than that is adbd restarting, not the TV)."""
    down_at = closed_since = None
    for t, up in samples:
        if not up:
            closed_since = t if closed_since is None else closed_since
            down_at = t if down_at is None else down_at
        else:
            if closed_since is not None and t - closed_since >= min_down:
                return {'down_at': down_at, 'up_at': t}
            closed_since = None
    return {'down_at': down_at, 'up_at': None}


def lines_for_boot(lines, boot_count):
    """The adb-watch lines written in the given boot: each carries "boot_count=N" in its facts. Selecting by boot rather
    than by line number survives the file being trimmed and never mistakes an error message for a record."""
    return [l for l in lines if f'boot_count={boot_count} ' in l or f'boot_count={boot_count}]' in l]


def verdict(came_back, new_watch_lines):
    """What the evidence says, in the order of the story. new_watch_lines: adb-watch.txt lines of the new boot."""
    text = '\n'.join(new_watch_lines)
    reset = 'adb_enabled 1 -> 0' in text
    guarded = 'guard: adb_enabled 0 -> 1' in text
    if not came_back:
        return 'FAILED: adb did not come back by itself' + (' (the guard acted, but the listener stayed down)' if guarded else '')
    if guarded:
        return 'PASSED: the boot switched ADB debugging off and the guard switched it back on'
    if reset:
        return 'UNCLEAR: ADB debugging was seen switched off, but there is no record of the guard repairing it'
    if not new_watch_lines:
        return 'UNKNOWN: the bridge wrote nothing for this boot (not started yet, or its record could not be read)'
    return 'NOT TESTED: ADB debugging stayed on through this reboot, so the guard was not needed'


# ---- live parts ----------------------------------------------------------------------------------------------

def tv_alive():
    """The TV's own remote-control service answers (the TV is up and on the network, whatever adb does)."""
    try:
        with urllib.request.urlopen(f'http://{ar.HOST}:6095/request?action=isalive', timeout=3) as r:
            return b'"status":0' in r.read(400).replace(b' ', b'')
    except (OSError, ValueError):
        return False


def calm_device(calm_seconds=15, limit=150):
    """adb flaps between offline and device while adbd is restarted (the guard does that right after boot): wait for a
    stretch of calm before reading anything."""
    end = time.time() + limit
    calm_since = None
    while time.time() < end:
        code, state = ar.adb('-s', ar.TV, 'get-state', timeout=8)
        if code == 0 and state.strip() == 'device':
            calm_since = calm_since or time.time()
            if time.time() - calm_since >= calm_seconds:
                return True
        else:
            calm_since = None
            ar.adb('connect', ar.TV, timeout=10)
        time.sleep(1.0)
    return False


def watch_lines():
    _, text = ar.sh_retry(f'cat {ar.FILES}/adb-watch.txt 2>/dev/null')
    return [] if ar.transport_error(text) else text.splitlines()


def boot_count():
    _, text = ar.sh_retry('settings get global boot_count')
    return text.strip() if not ar.transport_error(text) else ''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--max-minutes', type=float, default=6)
    parser.add_argument('--force', action='store_true')
    args = parser.parse_args()
    ev = ar.Evidence('reboot-test')
    if not ar.wait_for_device(30):
        ev.say('adb does not reach the TV: nothing to do')
        return 3
    _, power = ar.sh('dumpsys power | grep mWakefulness')
    _, window = ar.sh('dumpsys window | grep mFocusedApp')
    _, media = ar.sh('dumpsys media_session')
    wake, focused, playing = parse_wakefulness(power), parse_focused_package(window), parse_playing(media)
    ev.say(f'{time.strftime("%H:%M:%S")} before: wakefulness={wake or "?"} in front={focused or "-"} playing={playing}')
    ok, why = idle_verdict(wake, focused, playing)
    if not ok and not args.force:
        ev.say(f'not rebooting: {why} (--force to override)')
        return 4
    guard, text = ar.guard_status()
    ev.say('guard before: ' + ' '.join(f'{k}={v}' for k, v in guard.items()))
    if guard.get('permission') != 'granted':
        ev.say('the guard has no permission: the test would only show the remote is needed again')
        return 5
    for title, command in (('settings', 'settings get global boot_count; settings get global adb_enabled; settings get global developer_status'),
                           ('uptime', 'cat /proc/uptime; getprop sys.boot.reason')):
        ev.section('before: ' + title, command)
    boot_before = boot_count()
    ev.say(f'boot_count before: {boot_before or "?"}')

    ev.say(f'{time.strftime("%H:%M:%S")} rebooting the TV now')
    started = time.time()
    ar.adb('-s', ar.TV, 'reboot', timeout=20)
    samples, last_report = [], 0
    while time.time() - started < args.max_minutes * 60:
        t = round(time.time() - started, 1)
        samples.append((t, ar.port_open(1.0)))
        found = timeline(samples)
        if found['up_at'] is not None:
            break
        if t - last_report >= 20:
            last_report = t
            ev.say(f'  +{t:5.0f}s adb port {"open" if samples[-1][1] else "closed"}  TV service {"answers" if tv_alive() else "silent"}')
        time.sleep(1.0)
    found = timeline(samples)
    came_back = found['up_at'] is not None
    ev.say(f'{time.strftime("%H:%M:%S")} went down after {found["down_at"]} s; ' +
           (f'adb answers again after {found["up_at"]} s' if came_back else f'adb port still closed after {args.max_minutes:g} min'))
    if not came_back:
        ev.say(f'the TV itself {"answers" if tv_alive() else "does not answer"} on its remote-control port')
        ev.say('verdict: ' + verdict(False, []))
        ev.say(f'\nevidence: {ev.path}')
        return 1
    if not calm_device():
        ev.say('the port is open but adb never settled into the device state (authorization?)')
        return 3
    ev.say('-- after the reboot (adb has been calm for 15 s)')
    boot_after = boot_count()
    ev.say(f'boot_count after: {boot_after or "?"}' + ('  (the TV really rebooted)' if boot_after and boot_after != boot_before else '  (NOT changed)'))
    ev.section('after: settings', 'settings get global boot_count; settings get global adb_enabled; settings get global developer_status')
    ev.section('after: uptime and boot reason', 'cat /proc/uptime; getprop sys.boot.reason; getprop persist.sys.boot.reason')
    ev.section('after: power and screen', 'dumpsys power | grep -E "mWakefulness|Display Power"; dumpsys window | grep mFocusedApp')
    ev.section('after: boot and bridge start', "logcat -b events -d | grep -E 'boot_progress_(system_run|ams_ready|enable_screen)|am_proc_start.*xiaoaibridge' | head -8")
    ev.section('after: accessibility', 'settings get secure enabled_accessibility_services; settings get secure accessibility_enabled')
    ev.section('after: bridge log', f'head -n 4 {ar.FILES}/bridge-log.txt 2>/dev/null')
    new_lines = lines_for_boot(watch_lines(), boot_after)
    ev.say(f'adb-watch.txt, lines of boot {boot_after}:')
    for line in new_lines or ['(none)']:
        ev.say('    ' + line)
    guard, text = ar.guard_status()
    ev.say('guard after: ' + ' '.join(f'{k}={v}' for k, v in guard.items()))
    ev.say('verdict: ' + verdict(True, new_lines))
    ev.say(f'\nevidence: {ev.path}')
    return 0


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    sys.exit(main())
