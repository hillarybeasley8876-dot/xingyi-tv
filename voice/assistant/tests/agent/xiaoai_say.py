"""Say a sentence to XiaoAi through its text entry (same path as speech after recognition), then follow 星仪.

python tests/agent/xiaoai_say.py "让星仪把音量调到12" [--wait 40]
Prints the agent's task as it runs and the bridge's log tail at the end (who took the request, what was spoken).
"""
import argparse, json, re, subprocess, sys, time

ADB = [r"H:\tools\platform-tools\adb.exe", "-s", "192.168.31.162:5555"]
SERVICE = "com.xiaomi.voicecontrol/com.xiaomi.voicecontrol.VoiceControlService"
DEBUG = "xyz.erapi0neer.xiaoaibridge/xyz.erapi0neer.xingyi.agent.tv.AgentDebug"


def sh(*args, timeout=30):
    return subprocess.run(ADB + ["shell", *args], capture_output=True, timeout=timeout).stdout.decode("utf-8", "replace")


def state():
    out = sh("am", "broadcast", "-n", DEBUG, "-a", "xyz.erapi0neer.xingyi.agent.STATE")
    m = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
    return json.loads(m.group(1))["state"] if m else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("text")
    ap.add_argument("--wait", type=float, default=45)
    a = ap.parse_args()
    if "'" in a.text:
        sys.exit("text must not contain single quotes")
    before = ((state() or {}).get("task") or {}).get("id")
    sh(f"am startservice -n {SERVICE} -a com.xiaomi.mitv.voice.EXTERNAL_TEXT_START >/dev/null; sleep 0.3; "
       f"am startservice -n {SERVICE} -a com.xiaomi.mitv.voice.EXTERNAL_TEXT_STOP --es query '{a.text}' >/dev/null")
    t0, last, seen = time.time(), "", False
    while time.time() - t0 < a.wait:
        time.sleep(1.0)
        s = state()
        if not s:
            print(f"{time.time() - t0:5.1f}s (runtime restarting)")
            continue
        t = s.get("task") or {}
        line = f"{time.time() - t0:5.1f}s busy={s['busy']} awaiting={s['awaiting'] or '-'} task={t.get('id')} {t.get('state')} actions={len(t.get('actions', []))}"
        if line != last:
            print(line, flush=True)
            last = line
        seen |= s["busy"] or (t.get("id") not in (None, before))
        if seen and not s["busy"] and t.get("id") != before:
            break
    s = state() or {}
    t = s.get("task") or {}
    if t.get("id") != before:
        print(f"goal: {t.get('goal', '')}\n" + "\n".join(f"  - {x['tool']}: {x['what']} -> {x['status']}" for x in t.get("actions", [])))
        print(f"reply: {t.get('last_reply', '')}")
    else:
        print("(星仪 did not take this request)")
    sh("am", "broadcast", "-a", "xyz.erapi0neer.xiaoaibridge.STATUS", "-p", "xyz.erapi0neer.xiaoaibridge")
    time.sleep(1.5)
    status = subprocess.run(ADB + ["shell", "cat", "/sdcard/Android/data/xyz.erapi0neer.xiaoaibridge/files/status.txt"],
                            capture_output=True).stdout.decode("utf-8", "replace")
    tail = status.split("--- log ---", 1)[-1].strip().splitlines()[-8:]
    print("bridge log:\n  " + "\n  ".join(tail))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
