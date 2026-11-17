"""A silent sweep of everyday questions on the TV (source test: nothing is spoken or remembered, no app is opened): each answer, its
time to first show and wall time, for a quality table. python tests/agent/silent_sweep.py [--out FILE]
Only questions and reads — nothing that plays, opens an app or changes a setting.
"""
import json, os, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from tvtask import broadcast  # noqa: E402

QUESTIONS = [
    "现在几点", "今天几号", "今天农历几号", "今天什么节气", "明天上班吗", "还有几天过年", "明年清明放几天假",
    "今年还剩多少天", "明天上海天气怎么样", "今天上海空气质量怎么样", "一美元现在换多少人民币", "最近有什么科技新闻",
    "林俊杰最近发新歌了吗", "用一句话解释一下什么是量子纠缠", "讲个简短的冷笑话", "你记得我什么", "我定了哪些预约",
    "我让你记了什么", "昨天晚上我看了什么", "现在放的是什么歌", "音量现在多少", "星仪你都能做什么", "再说一遍",
    "后天出门要带伞吗", "今天看了多久电视", "这周末天气怎么样", "今天是今年的第几天",
    # added 07:40-08:10 (quick wordings, TV health)
    "明天星期几", "下周一是几号", "还有几天放假", "几点天黑", "外面天气怎么样", "电视怎么这么卡",
    "明年国庆放几天假", "周六天气怎么样", "明天要带伞吗", "今天最高温度多少", "北京周六天气怎么样",
]


def main():
    out_path = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else os.path.join(
        r"H:\tools\mitv\voice\assistant-out\tmp", "silent_sweep.jsonl")
    rows = []
    for q in QUESTIONS:
        t0 = time.time()
        r = subprocess.run([sys.executable, os.path.join(HERE, "tvtask.py"), q, "--source", "test", "--timeout", "90"],
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
        reply = ""
        for line in r.stdout.splitlines():
            if line.strip().startswith("reply:"):
                reply = line.strip()[6:].strip()
        perf = (broadcast("STATE")["state"].get("perf_recent") or [{}])[-1]
        row = {"q": q, "reply": reply, "wall_ms": perf.get("wall_ms"), "shown_ms": perf.get("first_shown_ms"),
               "rounds": perf.get("rounds"), "quick": perf.get("quick"), "slowest_tool": perf.get("slowest_tool"),
               "secs": round(time.time() - t0, 1)}
        rows.append(row)
        print("%-18s %5s ms  r=%s  %s" % (q[:18], row["wall_ms"], row["rounds"], reply[:90]), flush=True)
        time.sleep(1.5)
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    print("wrote", out_path)


if __name__ == "__main__":
    main()
