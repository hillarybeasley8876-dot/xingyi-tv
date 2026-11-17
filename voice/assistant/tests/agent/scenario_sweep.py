"""A silent sweep of everyday requests beyond music, on the real TV (source test: nothing is spoken, nothing is remembered,
and while the TV sleeps nothing is opened or pressed). Runs only while the screen is off: if the TV wakes (someone is
using it) the request in flight is cancelled and the sweep waits. One process at a time (the gateway is shared).

python tests/agent/scenario_sweep.py [--only 影视,金融] [--out FILE]
Writes one JSON line per request: category, request, reply, wall/first-shown ms, rounds, quick, tools used.
"""
import base64, json, os, re, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from tvtask import ADB, broadcast  # noqa: E402

SCENARIOS = {
    "影视": ["今晚央视一套放什么", "湖南卫视现在在播什么", "最近有什么好看的电影", "狂飙豆瓣多少分", "繁花在哪个平台能看",
           "庆余年第二季一共几集", "这周有什么新番", "影视飓风最近发了什么视频", "B站现在什么最火", "何同学在直播吗"],
    "资讯": ["今天有什么新闻", "最近AI圈有什么新闻", "百度热搜第一是什么", "网易云热歌榜前三是什么"],
    "体育": ["湖人昨天赢了吗", "今天有NBA比赛吗", "双色球最新开奖号码是多少"],
    "金融": ["茅台股价多少", "今天A股怎么样", "美股昨晚怎么样", "黄金多少钱一克", "一百欧元换多少人民币", "今天油价多少",
           "比特币现在多少钱"],
    "天气": ["今晚会下雨吗", "明天穿什么合适", "最近有台风吗", "最近有地震吗", "广州明天天气怎么样"],
    "日历": ["纽约现在几点", "今天宜搬家吗", "下一个节日是什么", "冬至是哪天", "历史上的今天发生了什么"],
    "知识": ["珠穆朗玛峰有多高", "一百二十八乘以三十七等于多少", "华氏一百度是多少摄氏度", "再见用日语怎么说",
           "床前明月光下一句是什么", "番茄炒蛋怎么做", "感冒了吃什么好", "推荐一本适合周末看的书"],
    "电视": ["电视WiFi信号好吗", "电视内存还够吗", "电视开机多久了", "电视上装了哪些应用", "现在连的是哪个WiFi"],
    "闲聊": ["我今天好累", "给我讲个睡前故事", "你和小爱有什么区别"],
}


def adb_shell(cmd):
    return subprocess.run(ADB + ["shell", cmd], capture_output=True, timeout=20).stdout.decode("utf-8", "replace")


def asleep():
    return "mWakefulness=Asleep" in adb_shell("dumpsys power | grep -m1 mWakefulness=")


def run_one(text, timeout=90):
    """(task state at the end, seconds, cancelled because the TV woke)."""
    before = (broadcast("STATE")["state"].get("task") or {}).get("id")
    first = broadcast("SUBMIT", "--es", "text_b64", base64.b64encode(text.encode()).decode(), "--es", "source", "test")
    request = first.get("request", -1)
    t0, seen_busy, state = time.time(), False, first["state"]
    while time.time() - t0 < timeout:
        time.sleep(1.0)
        if not asleep():
            broadcast("CANCEL")
            return state, time.time() - t0, True
        try:
            state = broadcast("STATE")["state"]
        except (RuntimeError, ValueError, subprocess.TimeoutExpired):
            continue
        task = state.get("task") or {}
        seen_busy |= state["busy"]
        ours = state.get("request") == request if isinstance(request, int) and request >= 0 and "request" in state else True
        if ours and not state["busy"] and (seen_busy or task.get("id") not in (None, before)):
            break
    return state, time.time() - t0, False


def main():
    only = sys.argv[sys.argv.index("--only") + 1].split(",") if "--only" in sys.argv else list(SCENARIOS)
    out_path = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else os.path.join(
        r"H:\tools\mitv\voice\assistant-out\tmp", "scenario_sweep.jsonl")
    with open(out_path, "w", encoding="utf-8", newline="\n") as out:
        for cat in only:
            for q in SCENARIOS[cat]:
                while not asleep():
                    print("  (TV is awake: waiting)", flush=True)
                    time.sleep(30)
                state, secs, cancelled = run_one(q)
                task = state.get("task") or {}
                perf = (state.get("perf_recent") or [{}])[-1]
                row = {"cat": cat, "q": q, "reply": task.get("last_reply", ""), "state": task.get("state"),
                       "wall_ms": perf.get("wall_ms"), "shown_ms": perf.get("first_shown_ms"), "rounds": perf.get("rounds"),
                       "quick": perf.get("quick"), "tools": [a.get("tool") + ("" if a.get("ok") else "✗") for a in task.get("actions", [])],
                       "secs": round(secs, 1), "cancelled": cancelled}
                out.write(json.dumps(row, ensure_ascii=False) + "\n")
                out.flush()
                print("%-4s %-16s %6s ms r=%-2s %-28s %s" % (cat, q[:16], row["wall_ms"], row["rounds"],
                      ",".join(row["tools"])[:28], re.sub(r"\s+", " ", row["reply"])[:110]), flush=True)
                time.sleep(1.5)
    print("wrote", out_path)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
