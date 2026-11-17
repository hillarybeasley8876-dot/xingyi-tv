"""First-round cost probe: where R1's time goes (plan tokens vs hidden reasoning), per reasoning_effort.

python tests/agent/round1_probe.py [--n 3] [--efforts low,minimal,none]
Sends the real contract and a typical planned utterance; prints ms, completion tokens, reasoning tokens and which
calls came back. The key comes from GATEWAY_API_KEY and is never printed.
"""
import http.client, json, os, ssl, sys, time

HOST = 'gateway.example.com'
HERE = os.path.dirname(os.path.abspath(__file__))
contract = json.load(open(os.path.join(HERE, '..', '..', 'assets', 'agent', 'contract.json'), encoding='utf-8'))
key = os.environ['GATEWAY_API_KEY']
args = sys.argv[1:]
n = int(args[args.index('--n') + 1]) if '--n' in args else 3
efforts = (args[args.index('--efforts') + 1] if '--efforts' in args else 'low,minimal,none').split(',')
model = args[args.index('--model') + 1] if '--model' in args else 'MiniMax-M3.1-Flash'
said = args[args.index('--say') + 1] if '--say' in args else '打开B站搜索星辰亻旅行者播放他播放量最高的一个视频'

user = {"utterance": said, "environment": {"time": "2026-11-05 10:40 星期四", "foreground": "Orrery 桌面",
        "volume": "30/100", "now_playing": None}}
conn = http.client.HTTPSConnection(HOST, 443, timeout=60, context=ssl.create_default_context())


def once(effort):
    body = {"model": model, "messages": [{"role": "system", "content": contract['system']},
            {"role": "user", "content": json.dumps(user, ensure_ascii=False)}],
            "tools": contract['tools'], "tool_choice": "auto", "max_completion_tokens": 2048, "stream": False}
    if effort != 'omit':
        body["reasoning_effort"] = effort
    data = json.dumps(body, ensure_ascii=False).encode('utf-8')
    t0 = time.perf_counter()
    conn.request('POST', '/v1/chat/completions', data, {"Content-Type": "application/json; charset=utf-8",
                                                        "Authorization": "Bearer " + key})
    r = conn.getresponse()
    raw = r.read()
    ms = (time.perf_counter() - t0) * 1000
    if r.status != 200:
        return f'HTTP {r.status} {raw[:160]!r}'
    o = json.loads(raw)
    u = o.get('usage') or {}
    det = u.get('completion_tokens_details') or {}
    msg = o['choices'][0]['message']
    calls = [c['function']['name'] for c in (msg.get('tool_calls') or [])]
    plan = next((c['function']['arguments'] for c in (msg.get('tool_calls') or []) if c['function']['name'] == 'plan'), '')
    rc = msg.get('reasoning_content') or ''
    think = '<think>' in str(msg.get('content') or '')
    cached = (u.get('prompt_tokens_details') or {}).get('cached_tokens')
    return (f'{ms:6.0f} ms  out {u.get("completion_tokens")}  reasoning {det.get("reasoning_tokens")}  '
            f'reasoning_content {len(rc)} ch  think-tag {think}  cached {cached}/{u.get("prompt_tokens")}  '
            f'calls {calls}  plan {len(plan)} ch')


for e in efforts:
    for i in range(n):
        try:
            print(f'{e:8s} #{i + 1}: {once(e)}', flush=True)
        except Exception as ex:  # keep probing the other efforts
            print(f'{e:8s} #{i + 1}: ERROR {type(ex).__name__}: {ex}', flush=True)
            conn.close()
            conn = http.client.HTTPSConnection(HOST, 443, timeout=60, context=ssl.create_default_context())
