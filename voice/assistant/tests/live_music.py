"""On the paired TV, confirm the owner's music request with a real D-pad event."""
import json
import re
import shlex
import subprocess
import time
from pathlib import Path

ADB = [r'H:\tools\platform-tools\adb.exe', '-s', '192.0.2.10:5555']
APP = 'xyz.erapi0neer.xingyi'
FILES = '/sdcard/Android/data/' + APP + '/files/'
OUT = Path(__file__).resolve().parents[2] / 'evidence-0.4'
PROMPT = '打开网易云音乐，播放林俊杰的《不为谁而作的歌》'

def shell(command):
    return subprocess.check_output(ADB + ['shell', command], timeout=15).decode('utf-8').strip()

def eventually(read, predicate, seconds=65):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        value = read()
        if predicate(value):
            return value
        time.sleep(.5)
    raise RuntimeError('Timed out waiting for expected state: ' + str(value)[:350])

def debug(op, value=''):
    shell('am broadcast -p ' + APP + ' -a ' + APP + '.DEBUG --es op ' + op + ' --es text ' + shlex.quote(value))

def player():
    dump = shell('dumpsys media_session')
    for section in re.split(r'(?m)^\s*package=', dump)[1:]:
        if section.splitlines()[0].strip() != 'com.netease.cloudmusic.tv':
            continue
        state = re.search(r'state=PlaybackState \{state=(\d+)', section)
        meta = re.search(r'(?m)^\s*metadata:size=\d+, description=(.+)$', section)
        if state and meta:
            return {'state': int(state[1]), 'description': meta[1].strip()}
    return {}

if __name__ == '__main__':
    OUT.mkdir(exist_ok=True)
    shell('am start -W -n ' + APP + '/.MainActivity')
    shell(': > ' + FILES + 'last-result.txt')
    shell(': > ' + FILES + 'harness-trace.txt')
    before = player()
    debug('ask', PROMPT)
    preview = eventually(lambda: shell('cat ' + FILES + 'last-result.txt'), lambda s: '按「确认执行」' in s)
    assert '在网易云音乐播放林俊杰的《不为谁而作的歌》' in preview, preview
    shell('input keyevent KEYCODE_DPAD_CENTER')
    observed = eventually(player, lambda p: p.get('state') == 3 and
                          p.get('description') == '不为谁而作的歌 (Twilight), 林俊杰, 上一首情歌 (国语精选)', 45)
    def trace():
        debug('trace')
        return shell('cat ' + FILES + 'harness-trace.txt')
    timeline = eventually(trace, lambda s: 'turn_end' in s)
    assert 'after_tool · play_netease_music · observed' in timeline, timeline
    assert timeline.count('confirmation_granted') == timeline.count('tool_start · play_netease_music') == 1, timeline
    result = {'prompt': PROMPT, 'before': before, 'preview': preview, 'observed': observed, 'timeline': timeline}
    (OUT / 'music.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))
