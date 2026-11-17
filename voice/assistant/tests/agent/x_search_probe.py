"""Which gateway route can read X (Twitter)? 2026-11-06: 「Tibo 推特上关于重置的消息」 got an August post from the
web search, when he posts resets every week or two. Tries xAI's own search tools through the gateway.

python tests/agent/x_search_probe.py [--models grok-4.7,grok-4.5] [--q "..."]
The key comes from GATEWAY_API_KEY and is never printed.
"""
import json, os, sys, time, urllib.request, urllib.error

KEY = os.environ['GATEWAY_API_KEY']
BASE = 'https://gateway.example.com'
NOW = '2026-11-06 00:20 星期五'
Q = 'OpenAI 的 Tibo（X 账号 @thsottiaux）最近一次发推说重置 Codex 用量限制是什么时候？原话大意是什么？'


def post(path, body, timeout=90):
    req = urllib.request.Request(BASE + path, json.dumps(body).encode('utf-8'),
                                 {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + KEY})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return time.time() - t0, r.status, r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return time.time() - t0, e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:  # report and go on
        return time.time() - t0, 0, str(e)


def text_of(raw):
    try:
        d = json.loads(raw)
    except ValueError:
        return raw[:300]
    out = []
    for o in d.get('output', []) or []:
        if o.get('type') == 'message':
            for c in o.get('content', []):
                if c.get('type') in ('output_text', 'text'):
                    out.append(c.get('text', ''))
    if not out and d.get('choices'):
        out.append(d['choices'][0].get('message', {}).get('content') or '')
    kinds = sorted({o.get('type') for o in d.get('output', []) or []})
    return ' '.join(' '.join(out).split()), kinds


def main():
    args = sys.argv[1:]
    models = (args[args.index('--models') + 1] if '--models' in args else 'grok-4.7').split(',')
    q = args[args.index('--q') + 1] if '--q' in args else Q
    instr = f'现在是 {NOW}（北京时间）。先搜索再回答，用中文，说清每条帖子的日期。'
    for m in models:
        variants = [
            ('responses x_search+web_search', '/v1/responses',
             {'model': m, 'instructions': instr, 'input': [{'role': 'user', 'content': q}],
              'tools': [{'type': 'x_search'}, {'type': 'web_search'}], 'max_output_tokens': 800}),
            ('chat search_parameters x', '/v1/chat/completions',
             {'model': m, 'messages': [{'role': 'system', 'content': instr}, {'role': 'user', 'content': q}],
              'search_parameters': {'mode': 'on', 'sources': [{'type': 'x'}, {'type': 'web'}], 'return_citations': True},
              'max_tokens': 800}),
        ]
        for name, path, body in variants:
            secs, status, raw = post(path, body)
            res = text_of(raw) if status == 200 else (raw[:300], [])
            text, kinds = res if isinstance(res, tuple) else (res, [])
            print(f'{m:22s} {name:30s} {status} {secs:5.1f}s {kinds}\n    {text[:600]}\n', flush=True)


if __name__ == '__main__':
    main()
