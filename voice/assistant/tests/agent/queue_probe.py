"""Two questions in a row, the second while the first is still being searched (2026-11-05 「连续输入…每次会覆盖上一次的结果」).

python tests/agent/queue_probe.py ["<first>" "<second>"] [--gap 2] [--shots 4,9,20,30]
Silent (source test: nothing is spoken or remembered), but the panel shows on the TV. Expects the second to be queued, both to be
answered in order, and prints each answer with its rounds; screenshots go to assistant-out/tmp/queue/.
"""
import base64, os, subprocess, sys, threading, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tvtask import ADB, broadcast  # noqa: E402

OUT = r"H:\tools\mitv\voice\assistant-out\tmp\queue"


def submit(text):
    return broadcast("SUBMIT", "--es", "text_b64", base64.b64encode(text.encode()).decode(), "--es", "source", "test")


def shot(at, t0):
    time.sleep(max(0, at - (time.time() - t0)))
    data = subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True, timeout=20).stdout
    with open(os.path.join(OUT, "t%04.1f.png" % at), "wb") as f:
        f.write(data)


def main():
    args = [a for a in sys.argv[1:]]
    gap = float(args[args.index("--gap") + 1]) if "--gap" in args else 2.0
    shots = [float(x) for x in (args[args.index("--shots") + 1] if "--shots" in args else "4,9,20,30").split(",")]
    words = [a for i, a in enumerate(args) if not a.startswith("--") and (i == 0 or not args[i - 1].startswith("--"))]
    first, second = (words + ["OpenAI最近有什么新动向", "明天上海天气怎么样"][len(words):])[:2]
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        os.remove(os.path.join(OUT, f))
    t0 = time.time()
    threads = [threading.Thread(target=shot, args=(s, t0)) for s in shots]
    for th in threads:
        th.start()
    a = submit(first)
    print(f"{time.time() - t0:5.1f}s first  -> {a['result']} (request {a.get('request')})", flush=True)
    time.sleep(gap)
    b = submit(second)
    print(f"{time.time() - t0:5.1f}s second -> {b['result']} (request {b.get('request')})", flush=True)
    while time.time() - t0 < 90:
        time.sleep(1.5)
        s = broadcast("STATE")["state"]
        if not s.get("busy"):
            break
    print(f"{time.time() - t0:5.1f}s idle", flush=True)
    for th in threads:
        th.join()
    hist = broadcast("HISTORY", "--ei", "limit", "3")["data"]
    for e in reversed(hist[:2]):
        print(f"  {e['said']} | {e['state']} | {e['replied'][:150]}")
    for p in (broadcast("STATE")["state"].get("perf_recent") or [])[-2:]:
        print(f"  perf: {p['said']} wall={p['wall_ms']} rounds={p['rounds']} slowest={p['slowest_tool']} {p['slowest_tool_ms']}")
    print("shots:", sorted(os.listdir(OUT)))


if __name__ == "__main__":
    main()
