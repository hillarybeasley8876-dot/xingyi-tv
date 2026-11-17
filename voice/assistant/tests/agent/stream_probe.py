"""Does the gateway stream chat completions (SSE) with tool calls, and how soon does the first sentence arrive?

python tests/agent/stream_probe.py ["question"] [--tools] [--raw FILE] [--nostream]
Reads GATEWAY_API_KEY from the environment (never printed). Prints timings and the shape of the deltas;
--raw saves the SSE lines (fixtures for StreamChecks), --nostream prints the plain answer's message fields instead.
"""
import json, os, ssl, subprocess, sys, time, urllib.request

BASE = "https://gateway.example.com"
MODEL = "MiniMax-M3.1-Flash"


def key():
    k = os.environ.get("GATEWAY_API_KEY")
    if k:
        return k
    out = subprocess.run(["powershell", "-NoProfile", "-Command",
                          "[Environment]::GetEnvironmentVariable('GATEWAY_API_KEY','User')"],
                         capture_output=True, text=True).stdout.strip()
    return out


TOOLS = [
    {"type": "function", "function": {"name": "web_search", "description": "联网搜索实时信息（天气、新闻、比分）。",
                                      "parameters": {"type": "object", "properties": {"query": {"type": "string"}},
                                                     "required": ["query"]}}},
    {"type": "function", "function": {"name": "set_volume", "description": "把媒体音量设为 level（0-100）。",
                                      "parameters": {"type": "object", "properties": {"level": {"type": "integer"}},
                                                     "required": ["level"]}}},
]


def main():
    args = sys.argv[1:]
    use_tools = "--tools" in args
    raw_path = args[args.index("--raw") + 1] if "--raw" in args else None
    words = [a for i, a in enumerate(args) if not a.startswith("--") and (i == 0 or args[i - 1] != "--raw")]
    q = words[0] if words else "详细讲讲为什么P和NP问题这么难"
    body = {"model": MODEL, "reasoning_effort": "low", "max_completion_tokens": 2048, "stream": True,
            "messages": [{"role": "system", "content": "你是电视上的语音助手星仪，回答会被读出来：口语、不用列表和符号。"},
                         {"role": "user", "content": q}]}
    if use_tools:
        body["tools"] = TOOLS
        body["tool_choice"] = "auto"
    if "--nostream" in args:
        body["stream"] = False
        req = urllib.request.Request(BASE + "/v1/chat/completions", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json", "Authorization": "Bearer " + key()})
        with urllib.request.urlopen(req, timeout=60, context=ssl.create_default_context()) as r:
            env = json.loads(r.read().decode("utf-8"))
        print("envelope keys", sorted(env.keys()))
        ch = env["choices"][0]
        print("choice keys", sorted(ch.keys()), "finish", ch.get("finish_reason"))
        m = ch["message"]
        print("message keys", sorted(m.keys()))
        print({k: (v if not isinstance(v, str) else v[:80]) for k, v in m.items()})
        return
    raw = open(raw_path, "w", encoding="utf-8", newline=chr(10)) if raw_path else None
    req = urllib.request.Request(BASE + "/v1/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer " + key(),
                                          "Accept": "text/event-stream"})
    t0 = time.time()
    first_byte = first_content = first_sentence = None
    content, reasoning, calls, kinds = "", "", {}, set()
    finish = None
    with urllib.request.urlopen(req, timeout=60, context=ssl.create_default_context()) as r:
        print("status", r.status, r.headers.get("Content-Type"))
        for chunk_bytes in r:
            line = chunk_bytes.decode("utf-8", "replace").strip()
            if raw:
                raw.write(line + chr(10))
            if first_byte is None:
                first_byte = time.time() - t0
            if not line.startswith("data:"):
                if line:
                    kinds.add("non-data:" + line[:30])
                continue
            data = line[5:].strip()
            if data == "[DONE]":
                kinds.add("[DONE]")
                break
            try:
                chunk = json.loads(data)
            except ValueError:
                kinds.add("bad-json")
                continue
            kinds.update("top." + k for k in chunk.keys())
            for ch in chunk.get("choices", []):
                d = ch.get("delta", {})
                kinds.update("delta." + k for k in d.keys())
                if ch.get("finish_reason"):
                    finish = ch["finish_reason"]
                if d.get("reasoning_content"):
                    reasoning += d["reasoning_content"]
                if d.get("content"):
                    if first_content is None:
                        first_content = time.time() - t0
                    content += d["content"]
                    if first_sentence is None and any(p in content for p in "。！？"):
                        first_sentence = time.time() - t0
                for tc in d.get("tool_calls") or []:
                    i = tc.get("index", 0)
                    c = calls.setdefault(i, {"id": "", "name": "", "arguments": ""})
                    c["id"] = c["id"] or tc.get("id", "")
                    f = tc.get("function", {})
                    c["name"] += f.get("name", "") or ""
                    c["arguments"] += f.get("arguments", "") or ""
    done = time.time() - t0
    if raw:
        raw.close()
    fmt = lambda x: "-" if x is None else "%.2fs" % x
    print("first byte", fmt(first_byte), "first content", fmt(first_content), "first sentence", fmt(first_sentence),
          "done", fmt(done), "finish", finish)
    print("kinds", sorted(kinds))
    print("reasoning chars", len(reasoning), "content chars", len(content))
    print("content head:", content[:160].replace(chr(10), " "))
    for i, c in sorted(calls.items()):
        print("tool call", i, c["name"], c["arguments"][:200], "id?", bool(c["id"]))


if __name__ == "__main__":
    main()
