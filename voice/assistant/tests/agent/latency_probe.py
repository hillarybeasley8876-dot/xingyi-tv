"""Latency probe for 星仪's model route (the gateway), from the PC on the TV's network.

python tests/agent/latency_probe.py [--model MiniMax-M3.1-Flash] [--n 3]
Measures: TCP+TLS connect time; a real first-round request (the real contract, a typical utterance) on a fresh
connection each time vs. one kept-alive connection; and whether an identical prefix gets faster (prompt caching).
The key comes from GATEWAY_API_KEY and is never printed.
"""
import http.client, json, os, socket, ssl, sys, time

HOST = 'gateway.example.com'
HERE = os.path.dirname(os.path.abspath(__file__))
contract = json.load(open(os.path.join(HERE, '..', '..', 'assets', 'agent', 'contract.json'), encoding='utf-8'))
key = os.environ['GATEWAY_API_KEY']
args = sys.argv[1:]
model = args[args.index('--model') + 1] if '--model' in args else 'MiniMax-M3.1-Flash'
n = int(args[args.index('--n') + 1]) if '--n' in args else 3

user = {"utterance": "打开B站搜索星辰亻旅行者", "environment": {"time": "2026-11-05 10:10 星期四", "foreground": "Orrery 桌面",
        "volume": "30/100", "now_playing": None}}
body = json.dumps({"model": model, "messages": [{"role": "system", "content": contract['system']},
                   {"role": "user", "content": json.dumps(user, ensure_ascii=False)}],
                   "tools": contract['tools'], "tool_choice": "auto", "reasoning_effort": "low",
                   "max_completion_tokens": 2048, "stream": False}, ensure_ascii=False).encode('utf-8')
headers = {"Content-Type": "application/json; charset=utf-8", "Authorization": "Bearer " + key}
print(f'model {model}; request body {len(body) / 1024:.1f} KB (system {len(contract["system"])} chars, {len(contract["tools"])} tools)')

def connect_ms():
    t0 = time.perf_counter()
    s = socket.create_connection((HOST, 443), timeout=10)
    t1 = time.perf_counter()
    ssl.create_default_context().wrap_socket(s, server_hostname=HOST).close()
    t2 = time.perf_counter()
    return (t1 - t0) * 1000, (t2 - t1) * 1000

for i in range(3):
    tcp, tls = connect_ms()
    print(f'connect {i + 1}: tcp {tcp:.0f} ms, tls {tls:.0f} ms')

def call(conn):
    t0 = time.perf_counter()
    conn.request('POST', '/v1/chat/completions', body=body, headers=headers)
    r = conn.getresponse()
    data = r.read()
    ms = (time.perf_counter() - t0) * 1000
    usage = {}
    try:
        j = json.loads(data)
        usage = j.get('usage', {})
        calls = [c['function']['name'] for c in j['choices'][0]['message'].get('tool_calls') or []]
    except Exception:
        calls = ['?', r.status]
    cached = (usage.get('prompt_tokens_details') or {}).get('cached_tokens')
    return ms, r.status, calls, usage.get('prompt_tokens'), cached

print('-- fresh connection per request (what the TV does today: disconnect() after every round)')
for i in range(n):
    c = http.client.HTTPSConnection(HOST, timeout=60)
    print('  ', '%.0f ms status=%s calls=%s prompt_tokens=%s cached=%s' % call(c))
    c.close()
print('-- one kept-alive connection')
c = http.client.HTTPSConnection(HOST, timeout=60)
for i in range(n):
    print('  ', '%.0f ms status=%s calls=%s prompt_tokens=%s cached=%s' % call(c))
c.close()
