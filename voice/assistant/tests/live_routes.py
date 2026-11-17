"""0.5 acceptance on the owner's MiTV: route order, balance classification, gateway fallback with a real tool call,
the ten-minute pause and the re-probe. Uses only the DUMP-protected debug broadcast and reads state; no setting
is changed.

    python live_routes.py first      # in-app diagnostics, 1st and 2nd question (official 402 -> gateway, then paused)
    python live_routes.py reprobe    # run 10+ minutes later: the official route must be probed again
    python live_routes.py screen     # 看当前屏幕: one screenshot via the app's own local ADB identity -> model

Evidence goes to ../../evidence-0.5/live-<step>.json. No credential is read or written by this script.
"""
import ctypes
import json
import pathlib
import re
import shlex
import subprocess
import sys
import time

ctypes.windll.kernel32.SetPriorityClass(ctypes.windll.kernel32.GetCurrentProcess(), 0x4000)  # below normal
ADB = [r'H:\tools\platform-tools\adb.exe', '-s', '192.168.31.162:5555']
APP = 'xyz.erapi0neer.xingyi'
FILES = '/sdcard/Android/data/' + APP + '/files/'
OUT = pathlib.Path(__file__).resolve().parents[2] / 'evidence-0.5'
QUESTION = '现在音量是多少'


def shell(command, check=True):
    result = subprocess.run(ADB + ['shell', command], capture_output=True, timeout=30)
    if check and result.returncode:
        raise RuntimeError('adb shell failed: ' + command[:80])
    return result.stdout.decode('utf-8', 'replace').replace('\r', '').strip()


def debug(op, text=None):
    command = 'am broadcast -p %s -a %s.DEBUG --es op %s' % (APP, APP, op)
    if text is not None:
        command += ' --es text ' + shlex.quote(text)
    shell(command)


def read(name):
    return shell('cat ' + FILES + name, check=False)


def eventually(produce, accept, seconds=90, pause=0.5):
    end = time.monotonic() + seconds
    value = None
    while time.monotonic() < end:
        value = produce()
        if accept(value):
            return value
        time.sleep(pause)
    raise RuntimeError('timed out; last value: ' + str(value)[:400])


def volume():
    return int(re.search(r'volume is (\d+)', shell('media volume --stream 3 --get')).group(1))


def foreground():
    match = re.search(r'mResumedActivity: ActivityRecord\{\S+ \S+ (\S+)', shell('dumpsys activity activities'))
    return match.group(1) if match else ''


def trace():
    shell(': > ' + FILES + 'harness-trace.txt')
    debug('trace')
    return eventually(lambda: read('harness-trace.txt'), lambda s: 'turn_end' in s or 'turn_error' in s, 15, 0.25)


def ask(text):
    shell(': > ' + FILES + 'last-result.txt')
    started = time.time()
    debug('ask', text)
    answer = eventually(lambda: read('last-result.txt'), lambda s: bool(s) and '正在处理' not in s, 150)
    elapsed = round(time.time() - started, 1)
    timeline = trace()
    route = [line for line in timeline.splitlines() if line.startswith('model_route')]
    return {'question': text, 'asked_at': time.strftime('%H:%M:%S', time.localtime(started)),
            'seconds': elapsed, 'answer': answer, 'route_events': route, 'trace': timeline}


def attempts(event):
    match = re.search(r'official attempts (\d+)', event)
    return int(match.group(1)) if match else -1


def bring_assistant():
    shell('am start -W -n %s/.MainActivity' % APP)
    eventually(foreground, lambda f: f.startswith(APP), 15)


def first():
    report = {'foreground_before': foreground(), 'volume_before': volume()}
    bring_assistant()
    shell(': > ' + FILES + 'last-result.txt')
    debug('harness_tests')
    report['diagnostics'] = eventually(lambda: read('last-result.txt'), lambda s: 'AgentHarness' in s, 60)
    assert report['diagnostics'].startswith('PASS'), report['diagnostics']

    one = ask(QUESTION)
    level = volume()
    report['first'] = one
    assert one['route_events'], one['trace']
    # the prefix up to the failure name: a fallback turn appends 「; gateway: rate limited」 and its own code to the
    # detail (2026-11-16), and this ran exactly on the turns it was meant to observe
    assert all(e.startswith('model_route · gateway · official: insufficient balance')
               for e in one['route_events']), one['route_events']
    assert attempts(one['route_events'][0]) == attempts(one['route_events'][-1]) >= 1, one['route_events']
    assert 'tool_start · get_status · running' in one['trace'] and 'after_tool · get_status · ok' in one['trace'], one
    assert str(level) in one['answer'], (level, one['answer'])
    report['volume_read_by_adb'] = level

    two = ask(QUESTION)
    report['second'] = two
    # the pause shows in the detail either way, but a fallback turn puts 「; gateway: rate limited」 between the reason
    # and the parenthesis and the gateway's own code first (2026-11-16 review): look for both parts, not one string
    assert two['route_events'] and all('official: insufficient balance' in e and 'paused until ' in e
                                       for e in two['route_events']), two['route_events']
    assert attempts(two['route_events'][-1]) == attempts(one['route_events'][-1]), 'official retried during the pause'
    assert str(volume()) in two['answer'], two['answer']
    time.sleep(1.5)
    (OUT / 'assistant-0.5.png').write_bytes(subprocess.check_output(ADB + ['exec-out', 'screencap', '-p'], timeout=30))
    report['volume_after'] = volume()
    assert report['volume_after'] == report['volume_before'], 'volume changed'
    return report


def reprobe():
    report = {'foreground_before': foreground()}
    bring_assistant()
    three = ask(QUESTION)
    report['third'] = three
    events = three['route_events']
    assert events and events[0].startswith('model_route · gateway · official: insufficient balance'), events
    assert 'tool_start · get_status · running' in three['trace'] and str(volume()) in three['answer'], three
    report['official_attempts'] = attempts(events[-1])
    return report


def screen():
    """Look-at-screen through the app's own 127.0.0.1 ADB identity, then the routed model with the image.
    A successful capture proves the private ADB key survived the debug/release reinstall."""
    report = {'foreground_before': foreground()}
    bring_assistant()
    report['behind_assistant'] = re.findall(r'\* Task\{\S+ #\d+ \S+ A=(\S+)', shell('dumpsys activity recents'))[:3]
    shell(': > ' + FILES + 'last-result.txt')
    debug('screen')
    def button():
        shell('uiautomator dump /sdcard/xingyi-ui.xml', check=False)
        xml = shell('cat /sdcard/xingyi-ui.xml', check=False)
        match = re.search(r'text="截取并分析"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        return match
    match = eventually(button, bool, 15, 0.5)
    shell('rm -f /sdcard/xingyi-ui.xml', check=False)
    x1, y1, x2, y2 = map(int, match.groups())
    shell('input tap %d %d' % ((x1 + x2) // 2, (y1 + y2) // 2))
    started = time.time()
    answer = eventually(lambda: read('last-result.txt'),
                        lambda s: bool(s) and not s.startswith('正在'), 150)
    report['seconds'] = round(time.time() - started, 1)
    report['answer'] = answer
    report['trace'] = trace()
    report['route_events'] = [l for l in report['trace'].splitlines() if l.startswith('model_route')]
    report['foreground_after'] = foreground()
    assert not answer.startswith('无法查看屏幕'), answer
    assert report['route_events'] and 'turn_end · complete' in report['trace'], report['trace']
    return report


if __name__ == '__main__':
    step = sys.argv[1] if len(sys.argv) > 1 else 'first'
    OUT.mkdir(exist_ok=True)
    result = {'first': first, 'reprobe': reprobe, 'screen': screen}[step]()
    text = json.dumps(result, ensure_ascii=False, indent=2)
    (OUT / ('live-%s.json' % step)).write_text(text, encoding='utf-8')
    sys.stdout.buffer.write((text + '\n').encode('utf-8'))
