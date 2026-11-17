"""Probe the gateway's Responses API with the web_search tool: latency and what comes back.
python probe_websearch.py [--save DIR] "今天上海天气怎么样" ...   (key from GATEWAY_API_KEY, never printed)
--save writes each raw reply to DIR/websearch-<n>.json (fixtures for the offline checks)."""
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

KEY = os.environ["GATEWAY_API_KEY"]
MODEL = os.environ.get("SEARCH_MODEL", "gpt-5.4-mini")
NOW = time.strftime("%Y-%m-%d %H:%M") + " " + "一二三四五六日"[time.localtime().tm_wday].join(["星期", ""])
INSTRUCTIONS = (f"你在帮中国大陆一户人家的电视语音助手查实时信息。现在是 {NOW}（北京时间）。先联网搜索，再用一两句中文说清结论："
                "数字、日期、地点要具体；价格、汇率、油价、金价按人民币和国内的报价说。可以说来源名称，不要念网址，不要用 markdown，不要列表。")


def ask(question):
    body = {"model": MODEL, "instructions": INSTRUCTIONS, "input": [{"role": "user", "content": question}],
            "tools": [{"type": "web_search"}], "max_output_tokens": 800}
    req = urllib.request.Request("https://gateway.example.com/v1/responses", data=json.dumps(body).encode(),
                                 headers={"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"})
    t = time.time()
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            raw = r.read().decode("utf-8", "replace")
            status = r.status
    except urllib.error.HTTPError as e:
        raw, status = e.read().decode("utf-8", "replace"), e.code
    return status, time.time() - t, raw


args = sys.argv[1:]
save = None
if args[:1] == ["--save"]:
    save, args = Path(args[1]), args[2:]
    save.mkdir(parents=True, exist_ok=True)
for n, q in enumerate(args, 1):
    status, seconds, raw = ask(q)
    print(f"== {q}  -> HTTP {status} in {seconds:.1f}s, {len(raw)} bytes")
    if save:
        (save / f"websearch-{n}.json").write_text(raw, encoding="utf-8")
    try:
        data = json.loads(raw)
    except ValueError:
        print("  not JSON:", raw[:200])
        continue
    kinds = [o.get("type") for o in data.get("output", [])]
    print("  output types:", kinds, "| model:", data.get("model"), "| status:", data.get("status"))
    text = data.get("output_text") or " ".join(c.get("text", "") for o in data.get("output", []) if o.get("type") == "message"
                                              for c in o.get("content", []) if c.get("type") == "output_text")
    print("  text:", text[:400].replace("\n", " "))
    if status != 200:
        print("  error:", raw[:300])
