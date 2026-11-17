"""A/B the web search's reasoning effort: default vs low (2026-11-06 overnight pass). PC only, the TV is not touched.

Usage: python tests/agent/search_effort_probe.py [repeats]
Reads GATEWAY_API_KEY from the environment; never prints it.
"""
import json
import os
import sys
import time
import urllib.request

BASE = "https://gateway.example.com/v1/responses"
KEY = os.environ.get("GATEWAY_API_KEY", "")
INSTRUCTIONS = ("现在是 2026-11-06 08:00 星期五（北京时间），用户在上海徐汇。你是电视上的语音助手的联网搜索：用中文、口语、"
                "先给结论，三四句话以内，不要列表、不要网址、不要 markdown；查不到就直说。")
QUERIES = ["最近有什么科技新闻", "林俊杰最近发新歌了吗", "一美元现在换多少人民币", "OpenAI 最近有什么新动向"]


def ask(query, effort):
    body = {"model": "gpt-5.4", "instructions": INSTRUCTIONS,
            "input": [{"role": "user", "content": query}],
            "tools": [{"type": "web_search"}], "max_output_tokens": 600}
    if effort:
        body["reasoning"] = {"effort": effort}
    req = urllib.request.Request(BASE, data=json.dumps(body).encode("utf-8"), method="POST",
                                 headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            j = json.loads(r.read().decode("utf-8"))
    except Exception as e:  # noqa: BLE001 - a probe reports whatever went wrong
        return time.time() - start, "ERROR " + str(e)[:200], False
    text, searched = "", False
    for item in j.get("output", []):
        if item.get("type") == "web_search_call":
            searched = True
        for c in item.get("content", []) or []:
            if c.get("type") == "output_text":
                text += c.get("text", "")
    return time.time() - start, text.strip().replace("\n", " "), searched


def main():
    if not KEY:
        sys.exit("GATEWAY_API_KEY is not set")
    repeats = int(sys.argv[1]) if len(sys.argv) > 1 else 2
    rows = []
    for r in range(repeats):
        for q in QUERIES:
            for effort in ([None, "low"] if r % 2 == 0 else ["low", None]):
                secs, text, searched = ask(q, effort)
                rows.append((q, effort or "default", secs, searched, text))
                print(f"{q[:12]:12s} {effort or 'default':8s} {secs:5.1f}s searched={searched} {text[:110]}", flush=True)
    for effort in ("default", "low"):
        t = sorted(x[2] for x in rows if x[1] == effort and not x[4].startswith("ERROR"))
        if t:
            print(f"{effort:8s} n={len(t)} median={t[len(t) // 2]:.1f}s min={t[0]:.1f}s max={t[-1]:.1f}s")


if __name__ == "__main__":
    main()
