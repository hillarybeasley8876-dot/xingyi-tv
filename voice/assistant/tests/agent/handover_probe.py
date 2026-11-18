"""One live test of the XiaoAi → 星仪 hand-over with screenshots along the way (2026-11-05 evening).

python tests/agent/handover_probe.py "<sentence>" [--shots 1.5,3,5,9,15]
Sends the sentence through XiaoAi's text entry (as xiaoai_say.py does: the same pipeline as speaking to it), takes
screenshots at the given seconds, then prints the bridge's log lines for this request. Screenshots go to
assistant-out/tmp/handover/. Needs the screen on (a sleeping TV screenshots black).
"""
import os, subprocess, sys, threading, time

ADB = [r"H:\tools\platform-tools\adb.exe", "-s", "192.0.2.10:5555"]
OUT = r"H:\tools\mitv\voice\assistant-out\tmp\handover"
HERE = os.path.dirname(os.path.abspath(__file__))


def shot(at, t0, name):
    time.sleep(max(0, at - (time.time() - t0)))
    data = subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True, timeout=20).stdout
    with open(os.path.join(OUT, name), "wb") as f:
        f.write(data)


def main():
    text = sys.argv[1]
    shots = [float(x) for x in (sys.argv[sys.argv.index("--shots") + 1] if "--shots" in sys.argv else "1.5,3,5,9,15").split(",")]
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        os.remove(os.path.join(OUT, f))
    t0 = time.time()
    threads = [threading.Thread(target=shot, args=(s, t0, "t%04.1f.png" % s)) for s in shots]
    for th in threads:
        th.start()
    say = subprocess.run([sys.executable, os.path.join(HERE, "xiaoai_say.py"), text, "--wait", "60"],
                         capture_output=True, timeout=120, env=dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUTF8="1"))
    for th in threads:
        th.join()
    print(say.stdout.decode("utf-8", "replace")[-1500:])
    log = subprocess.run(["sh", r"H:\tools\mitv\xiaoai-bridge\bridge.sh", "log"], capture_output=True, timeout=60).stdout.decode("utf-8", "replace")
    print("--- bridge log (last lines) ---")
    print("\n".join(log.strip().splitlines()[-6:]))
    print("shots:", sorted(os.listdir(OUT)))


if __name__ == "__main__":
    main()
