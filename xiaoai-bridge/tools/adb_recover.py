"""Wait for the TV's adb listener to come back, then use the window at once.

The firmware switches ADB debugging off at standby and nothing on this PC can switch it on again: someone has to do it
with the remote (Settings > Account & Security > ADB debugging). This waits for that and then, in the first seconds,
1. connects, 2. records the facts that explain the outage (read-only), 3. installs the current bridge build and
grants it WRITE_SECURE_SETTINGS (the guard that keeps ADB on after the next standby), 4. makes sure the accessibility
service is enabled, 5. checks that the guard is running.

python tools/adb_recover.py [--wait-minutes 120] [--skip-install] [--test-guard]

--test-guard switches ADB debugging off on purpose after the install, to prove the guard switches it back on (the
session drops for a moment; if the guard does not work the remote is needed once more). Evidence goes to
out/adb-recovery-<time>.txt.
"""
import argparse
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

ADB = os.environ.get('ADB_EXE', r'H:\tools\platform-tools\adb.exe')
TV = os.environ.get('TV', '192.0.2.10:5555')
HOST, _, PORT = TV.partition(':')
PORT = int(PORT or 5555)
PKG = 'xyz.erapi0neer.xiaoaibridge'
FILES = f'/sdcard/Android/data/{PKG}/files'
EYES = f'{PKG}/xyz.erapi0neer.xingyi.agent.tv.Eyes'
HERE = Path(__file__).resolve().parent.parent
APK = HERE / 'build' / 'xiaoai-bridge.apk'
NO_WINDOW = 0x08000000


def adb(*args, timeout=30):
    """(exit code, stdout and stderr as text); never raises on a timeout."""
    try:
        r = subprocess.run([ADB, *args], capture_output=True, timeout=timeout, creationflags=NO_WINDOW)
    except subprocess.TimeoutExpired:
        return 124, f'(timed out after {timeout} s)'
    return r.returncode, (r.stdout + r.stderr).decode('utf-8', 'replace').strip()


def sh(command, timeout=30):
    return adb('-s', TV, 'shell', command, timeout=timeout)


def transport_error(text):
    """adb's own failure text ('adb.exe: device offline', 'error: closed'), as opposed to the remote command's output."""
    first = text.strip().splitlines()[0] if text.strip() else ''
    return first.startswith(('adb.exe:', 'error:')) or 'device offline' in first


def sh_retry(command, timeout=30, tries=5, pause=3.0):
    """sh() that waits out adb's transient transport errors: the transport flaps for a few seconds whenever adbd is
    restarted, which the guard does right after every boot."""
    code, text = sh(command, timeout)
    for _ in range(tries - 1):
        if not transport_error(text):
            break
        time.sleep(pause)
        adb('connect', TV, timeout=10)
        code, text = sh(command, timeout)
    return code, text


def port_open(timeout=3.0):
    try:
        with socket.create_connection((HOST, PORT), timeout=timeout):
            return True
    except OSError:
        return False


class Evidence:
    def __init__(self, prefix='adb-recovery'):
        out = Path(os.environ.get('ADB_RECOVER_OUT') or HERE / 'out')
        out.mkdir(parents=True, exist_ok=True)
        self.path = out / time.strftime(f'{prefix}-%Y%m%d-%H%M%S.txt')
        self.file = open(self.path, 'w', encoding='utf-8')

    def say(self, text=''):
        print(text, flush=True)
        self.file.write(text + '\n')
        self.file.flush()

    def section(self, title, command, timeout=30):
        code, text = sh_retry(command, timeout)
        self.file.write(f'\n===== {title}  [{command}]  (exit {code})\n{text}\n')
        self.file.flush()
        return text


def wait_for_port(minutes):
    started = time.time()
    end = started + minutes * 60
    next_report = started + 600
    while time.time() < end:
        if port_open():
            return True
        if time.time() >= next_report:
            print(f'  still waiting for {TV} ({int((time.time() - started) // 60)} min)', flush=True)
            next_report += 600
        time.sleep(10)
    return False


def wait_for_device(seconds=90):
    end = time.time() + seconds
    asked = False
    while time.time() < end:
        adb('connect', TV, timeout=15)
        code, state = adb('-s', TV, 'get-state', timeout=10)
        if state.strip() == 'device':
            return True
        if 'unauthorized' in state and not asked:
            print('  the TV is asking to allow this PC: choose "always allow" with the remote', flush=True)
            asked = True
        time.sleep(3)
    return False


def snapshot(ev):
    """What explains the outage. Read-only and quick: the window may close again at the next standby."""
    ev.say('-- snapshot (read-only)')
    ev.section('time', 'date; cat /proc/uptime; getprop ro.build.display.id; getprop ro.build.version.release')
    ev.section('adb settings', 'settings get global adb_enabled; settings get global boot_count; settings get global developer_status; '
               'settings get global mitv_power_state')
    ev.section('adb properties', "getprop | grep -E 'adb|usb.config|bootreason|boot.reason|set_adb|mitv.*(power|standby)|deep'")
    ev.section('adbd process', 'getprop init.svc.adbd; ps -A | grep adbd')
    ev.section('power state', "dumpsys power | grep -E 'mWakefulness|Display Power|mScreenOn|mSleepTimeout|mHoldingWakeLockSuspendBlocker' | head -12")
    ev.section('dropbox entries (reboots, crashes)', "dumpsys dropbox | tail -n 45", 40)
    ev.section('events: power / kills around the outage',
               "logcat -b events -d | grep -E 'power_sleep|power_screen|screen_toggled|am_kill|am_force_stop|am_proc_died|boot_progress_start' | tail -n 60", 40)
    ev.section('who touched adb',
               "logcat -b main,system -d | grep -i -E 'adb_enabled|AdbService|set_adb_disabled|ADB debugging|usb debugging|UsbDeviceManager' | tail -n 60", 60)
    ev.section('our processes', f"dumpsys activity processes | grep -E 'ProcessRecord.*({PKG}|orrery|xingyi)' | head -8")
    ev.section('accessibility', 'settings get secure enabled_accessibility_services; settings get secure accessibility_enabled')
    ev.section('bridge log (old build)', f'tail -n 40 {FILES}/bridge-log.txt 2>/dev/null; ls -l {FILES} 2>/dev/null')
    ev.section('memory baseline', "grep -E 'MemTotal|MemFree|MemAvailable|SwapTotal|SwapFree' /proc/meminfo; dumpsys meminfo | head -n 30", 40)
    ev.section('low-memory killer', "cat /sys/module/lowmemorykiller/parameters/minfree /sys/module/lowmemorykiller/parameters/adj 2>&1; "
               "getprop | grep -E 'low_ram|lmk|heapsize|zram|swap'; cat /proc/swaps")


def install(ev):
    ev.say('-- install the bridge build and grant the guard permission')
    if not APK.is_file():
        ev.say(f'  missing {APK}: run `sh build.sh` first')
        return False
    code, text = adb('-s', TV, 'install', '-r', str(APK), timeout=180)
    ev.say(f'  install: {text.splitlines()[-1] if text else code}')
    if code or 'Success' not in text:
        return False
    for permission in ('READ_LOGS', 'DUMP', 'PACKAGE_USAGE_STATS', 'WRITE_SECURE_SETTINGS', 'ACCESS_COARSE_LOCATION'):
        code, text = sh(f'pm grant {PKG} android.permission.{permission}')
        ev.say(f'  grant {permission}: {"ok" if not code else text[:100]}')
    sh(f'appops set {PKG} GET_USAGE_STATS allow')
    sh(f'am force-stop {PKG}')  # the permission gids only apply to a fresh process
    code, text = sh(f'am start-foreground-service -n {PKG}/.BridgeService')
    ev.say(f'  start: {text.splitlines()[-1] if text else code}')
    return True


def ensure_eyes(ev):
    """The accessibility service must be listed; a force-stop removes it, and the engine puts it back only once running."""
    _, listed = sh('settings get secure enabled_accessibility_services')
    if EYES in listed:
        ev.say('-- accessibility service: enabled')
        return
    merged = EYES if listed.strip() in ('', 'null') else f'{listed.strip()}:{EYES}'
    sh(f'settings put secure enabled_accessibility_services {merged}')
    sh('settings put secure accessibility_enabled 1')
    ev.say('-- accessibility service: was missing, added')


def parse_guard(status_text):
    """{'permission': 'granted', 'adb_enabled': '1', 'listening': 'yes', ...} from the "adb guard:" line of status.txt
    (written by AdbGuardPolicy.describe on the TV), or {} when the line is missing (an older build)."""
    for line in status_text.splitlines():
        if line.startswith('adb guard: ') and 'permission=' in line:
            return dict(part.split('=', 1) for part in line[len('adb guard: '):].split() if '=' in part)
    return {}


def last_guard_action(status_text):
    """The guard's most recent action as the TV recorded it, or ''."""
    prefix = 'adb guard last action: '
    for line in status_text.splitlines():
        if line.startswith(prefix):
            return line[len(prefix):].strip()
    return ''


def guard_status(tries=4):
    """(parsed guard line, whole status.txt): asks the bridge to write its status and reads it back. The old file is
    removed first, so what is read is what the bridge wrote just now and never a leftover from before a restart."""
    text = ''
    for _ in range(tries):
        sh_retry(f'rm -f {FILES}/status.txt')
        sh_retry(f'am broadcast -a {PKG}.STATUS')
        time.sleep(2.5)
        _, text = sh_retry(f'cat {FILES}/status.txt 2>/dev/null')
        guard = parse_guard(text)
        if guard:
            return guard, text
    return {}, text


def verify_guard(ev, settle):
    """The guard is working when it may write the setting, the setting is on and the listener answers."""
    ev.say('-- guard check')
    time.sleep(settle)  # the service has just been started
    guard, text = guard_status()
    ev.say('  adb guard: ' + (' '.join(f'{k}={v}' for k, v in guard.items()) or 'no status line yet (older build, or the service is not running)'))
    ok = guard.get('permission') == 'granted' and guard.get('adb_enabled') == '1' and guard.get('listening') == 'yes'
    ev.say(f'  guard: {"ok" if ok else "NOT ok"}')
    return ok


def test_guard(ev):
    """Switches ADB debugging off on purpose. The proof is the guard's own record of switching it back on."""
    ev.say('-- guard test: switching ADB debugging off on purpose (if the guard does not bring it back, the remote is needed once more)')
    _, before_text = guard_status()
    before = last_guard_action(before_text)
    started = time.time()
    sh('settings put global adb_enabled 0', timeout=15)
    while time.time() - started < 40:
        time.sleep(1)
        if time.time() - started > 3 and port_open(1.5):
            break
    back = port_open(3)
    ev.say(f'  listener {"answers again after about %d s" % (time.time() - started) if back else "did NOT come back within 40 s: the remote is needed once more"}')
    if not back:
        return False
    if not wait_for_device(60):
        ev.say('  the listener answers but adb does not reach the device state yet')
        return False
    _, after_text = guard_status()
    after = last_guard_action(after_text)
    proven = after != before and 'adb_enabled 0 -> 1' in after
    ev.say(f'  last guard action: {after or "-"}')
    ev.say(f'  guard test: {"passed, the guard switched it back on" if proven else "NOT proven: it came back, but the guard has no record of doing it"}')
    return proven


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--wait-minutes', type=float, default=120)
    parser.add_argument('--skip-snapshot', action='store_true')
    parser.add_argument('--skip-install', action='store_true')
    parser.add_argument('--test-guard', action='store_true')
    parser.add_argument('--settle-seconds', type=float, default=8)
    args = parser.parse_args()
    print(f'waiting for {TV} (up to {args.wait_minutes:g} min); turn on Settings > Account & Security > ADB debugging', flush=True)
    if not wait_for_port(args.wait_minutes):
        print('the listener did not come back in time', flush=True)
        return 2
    ev = Evidence()
    ev.say(f'{time.strftime("%H:%M:%S")} the listener answers; connecting')
    if not wait_for_device():
        ev.say('adb never reached the device state (unauthorized?)')
        return 3
    if not args.skip_snapshot:
        snapshot(ev)
    if not args.skip_install:
        if not install(ev):
            ev.say(f'\nevidence: {ev.path}')
            return 0
        wait_for_device(60)
        ensure_eyes(ev)
    verify_guard(ev, args.settle_seconds if not args.skip_install else 0)
    if args.test_guard:
        test_guard(ev)
    ev.say(f'\nevidence: {ev.path}')
    return 0


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    sys.exit(main())
