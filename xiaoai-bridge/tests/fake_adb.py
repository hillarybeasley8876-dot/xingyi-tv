"""A stand-in for adb.exe for tests/adb_recover_test.py: answers like a TV whose ADB debugging was just switched on.
Every call is appended to $FAKE_ADB_LOG.
$FAKE_ADB_INSTALL=fail      `install` fails
$FAKE_ADB_GUARD=missing     the bridge has no WRITE_SECURE_SETTINGS (status says permission=missing)
$FAKE_ADB_GUARD=old         an older build without the "adb guard:" status line
$FAKE_ADB_GUARD=deaf        the guard never switches ADB debugging back on after a flip"""
import json
import os
import sys
from pathlib import Path

args = sys.argv[1:]
log = Path(os.environ['FAKE_ADB_LOG'])
state_file = log.with_suffix('.state')
state = json.loads(state_file.read_text()) if state_file.exists() else {}
guard_mode = os.environ.get('FAKE_ADB_GUARD', 'ok')
with log.open('a', encoding='utf-8') as f:
    f.write(' '.join(args) + '\n')


def save():
    state_file.write_text(json.dumps(state))


def reply(text='', code=0):
    sys.stdout.write(text + ('\n' if text else ''))
    sys.exit(code)


def status_text():
    lines = ['reader: 正在听小爱', 'READ_LOGS: true']
    if guard_mode != 'old':
        permission = 'missing' if guard_mode == 'missing' else 'granted'
        lines.append(f'adb guard: permission={permission} adb_enabled=1 listening=yes enables_2min=0 last_restart=never')
        if state.get('flipped') and guard_mode == 'ok':
            lines.append('adb guard last action: 11-04 21:50:12 guard: adb_enabled 0 -> 1 (the TV had switched it off), listener back after 640 ms')
    lines.append('--- log ---')
    return '\n'.join(lines)


if args[:1] == ['connect']:
    reply('connected to ' + args[1])
if args[:1] == ['devices']:
    reply('List of devices attached')
if args[:2] == ['-s', args[1]] and args[2:3] == ['get-state']:
    reply('device')
if args[:2] == ['-s', args[1]] and args[2:3] == ['install']:
    if os.environ.get('FAKE_ADB_INSTALL') == 'fail':
        reply('Failure [INSTALL_FAILED_VERSION_DOWNGRADE]', 1)
    reply('Performing Streamed Install\nSuccess')
if args[:2] == ['-s', args[1]] and args[2:3] == ['shell']:
    command = ' '.join(args[3:])
    if 'settings get secure enabled_accessibility_services' in command:
        reply(state.get('a11y', 'com.xiaomi.voicecontrol/com.xiaomi.voicecontrol.VoiceAccessibilityService'))
    if 'settings put secure enabled_accessibility_services' in command:
        state['a11y'] = command.split('enabled_accessibility_services', 1)[1].strip()
        save()
        reply()
    if 'settings put global adb_enabled 0' in command:
        state['flipped'] = True
        save()
        reply()
    if 'cat' in command and 'status.txt' in command:
        reply(status_text())
    reply('ok')
reply('', 0)
