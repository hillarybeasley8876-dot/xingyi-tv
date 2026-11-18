"""End-to-end check on the TV: a sentence enters XiaoAi (its own text entry, i.e. what it hears after recognition),
the bridge takes it over, NetEase plays, XiaoAi's own reaction is recorded. Pauses the music and goes HOME at the end.

python e2e.py "<sentence>" [--expect-title 你还要我怎样] [--keep-playing] [--out out/e2e-1.json]
"""
import argparse, base64, json, os, re, subprocess, sys, time
from pathlib import Path

ADB = r'H:\tools\platform-tools\adb.exe'
TV = os.environ.get('TV', '192.0.2.10:5555')
PKG = 'xyz.erapi0neer.xiaoaibridge'
FILES = f'/sdcard/Android/data/{PKG}/files'
CODEX_LOG = Path(r'H:\tools\mitv\voice\evidence-0.6\wifi-check.log')
HERE = Path(__file__).resolve().parent.parent
NO_WINDOW = 0x08000000


def adb(*args, timeout=30, binary=False):
    r = subprocess.run([ADB, '-s', TV, *args], capture_output=True, timeout=timeout, creationflags=NO_WINDOW)
    return r.stdout if binary else r.stdout.decode('utf-8', 'replace')


def sh(cmd, timeout=30):
    return adb('shell', cmd, timeout=timeout)


def codex_busy():
    if not CODEX_LOG.exists():
        return False
    last = (CODEX_LOG.read_text(encoding='utf-8', errors='replace').strip().splitlines() or [''])[-1]
    return 'Original WLAN confirmed' not in last and time.time() - CODEX_LOG.stat().st_mtime < 300


def now_playing():
    sh(f'am broadcast -a {PKG}.STATUS')
    time.sleep(1.5)
    status = sh(f'cat {FILES}/status.txt')
    m = re.search(r'^now playing: (.*)$', status, re.M)
    return (m.group(1).strip() if m else ''), status


def xiaoai_lines(since_epoch):
    raw = sh('logcat -d -v threadtime -v uid -v epoch', timeout=60)
    keep = re.compile(r'EventOnFullResult text|onConversationFinished|startTextQuery|playTts|handlePlayTts|StopTts|stopTts|'
                      r'Template\.|"name":"(Play|Speak|Open|Launch|Search)[A-Za-z]*"|ActivityManager: START|XiaoaiBridge')
    out = []
    for line in raw.splitlines():
        m = re.match(r'\s*(\d{10})\.(\d+)', line)
        if not m or int(m.group(1)) + int(m.group(2)[:3]) / 1000 < since_epoch:
            continue
        if keep.search(line):
            out.append(line.strip()[:400])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('sentence')
    ap.add_argument('--expect-title', default='')
    ap.add_argument('--keep-playing', action='store_true')
    ap.add_argument('--wait', type=float, default=25)
    ap.add_argument('--out', default='')
    a = ap.parse_args()
    if codex_busy():
        print('Codex is in the middle of a TV run; try again in a minute.')
        return 2
    tv_now = float(sh('date +%s.%N').strip()[:14])
    before, _ = now_playing()
    sh(f'rm -f {FILES}/status.txt')
    # XiaoAi's exported text entry: START, then STOP with the query (what VendorQuery in Xingyi does)
    sh('am startservice -n com.xiaomi.voicecontrol/.VoiceControlService -a com.xiaomi.mitv.voice.EXTERNAL_TEXT_START')
    time.sleep(0.3)
    q = a.sentence.replace("'", '')
    sh(f"am startservice -n com.xiaomi.voicecontrol/.VoiceControlService -a com.xiaomi.mitv.voice.EXTERNAL_TEXT_STOP --es query '{q}'")
    t0 = time.time()
    seen, timeline = '', []
    while time.time() - t0 < a.wait:
        np, status = now_playing()
        if np != seen:
            timeline.append([round(time.time() - t0, 1), np])
            seen = np
        if a.expect_title and a.expect_title in np and np.startswith('playing'):
            break
        time.sleep(1)
    time.sleep(3)  # let XiaoAi finish whatever it was doing, so its reaction is in the log
    np, status = now_playing()
    shot = HERE / 'out' / (Path(a.out).stem + '.png' if a.out else 'e2e.png')
    shot.parent.mkdir(exist_ok=True)
    shot.write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    bridge_log = status.split('--- log ---', 1)[-1].strip().splitlines()
    report = {
        'sentence': a.sentence,
        'before': before,
        'after': np,
        'playing_expected': bool(a.expect_title) and a.expect_title in np and np.startswith('playing'),
        'timeline_s': timeline,
        'bridge_log': bridge_log[-8:],
        'xiaoai_and_activity_log': xiaoai_lines(tv_now - 1),
        'screenshot': str(shot),
    }
    if not a.keep_playing:
        sh('input keyevent KEYCODE_MEDIA_PAUSE')
        time.sleep(0.5)
        sh('input keyevent KEYCODE_HOME')
        report['cleanup'] = 'paused, HOME'
    text = json.dumps(report, ensure_ascii=False, indent=1)
    if a.out:
        out = HERE / a.out
        out.parent.mkdir(exist_ok=True)
        out.write_text(text, encoding='utf-8', newline='\n')
    print(text)
    return 0 if (report['playing_expected'] or not a.expect_title) else 1


if __name__ == '__main__':
    sys.exit(main())
