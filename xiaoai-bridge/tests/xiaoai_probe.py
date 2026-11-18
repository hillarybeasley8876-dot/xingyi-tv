"""Ask XiaoAi a few questions through its text entry and record how it answers (instructions, texts, TTS timing).

python xiaoai_probe.py "<question>" ... [--gap 12]    -> out/xiaoai-probe-<time>.json
"""
import argparse, json, re, subprocess, time
from pathlib import Path

ADB = r'H:\tools\platform-tools\adb.exe'
TV = '192.0.2.10:5555'
NO_WINDOW = 0x08000000
OUT = Path(__file__).resolve().parent.parent / 'out'
KEEP = re.compile(r'EventOnFullResult text|recv inst tra|receive nlp instruction|playTts|stopTts|onAudioTrack|OnCompletion|'
                  r'=vc=mm=mod=|=vc=mmp=|ToastStream|StartAnswer|FinishAnswer|ActivityManager: START|XiaoaiBridge|onConversationFinished')


def sh(cmd, timeout=60):
    r = subprocess.run([ADB, '-s', TV, 'shell', cmd], capture_output=True, timeout=timeout, creationflags=NO_WINDOW)
    return r.stdout.decode('utf-8', 'replace')


def ask(q):
    sh('am startservice -n com.xiaomi.voicecontrol/.VoiceControlService -a com.xiaomi.mitv.voice.EXTERNAL_TEXT_START')
    time.sleep(0.3)
    sh("am startservice -n com.xiaomi.voicecontrol/.VoiceControlService -a com.xiaomi.mitv.voice.EXTERNAL_TEXT_STOP --es query '%s'" % q.replace("'", ''))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('questions', nargs='+')
    ap.add_argument('--gap', type=float, default=12)
    a = ap.parse_args()
    t_start = float(sh('date +%s.%N').strip()[:14])
    marks = []
    for q in a.questions:
        marks.append([q, float(sh('date +%s.%N').strip()[:14])])
        ask(q)
        time.sleep(a.gap)
    raw = sh('logcat -d -v epoch -v uid', timeout=90)
    rows = []
    for line in raw.splitlines():
        m = re.match(r'\s*(\d{10}\.\d+)', line)
        if m and float(m.group(1)) >= t_start - 0.5 and KEEP.search(line):
            rows.append(line.strip()[:420])
    OUT.mkdir(exist_ok=True)
    out = OUT / time.strftime('xiaoai-probe-%H%M%S.json')
    out.write_text(json.dumps({'asked': marks, 'log': rows}, ensure_ascii=False, indent=1), encoding='utf-8', newline='\n')
    print(out, len(rows), 'lines')


if __name__ == '__main__':
    main()
