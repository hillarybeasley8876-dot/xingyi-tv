"""Which model gives web_search the most correct answers? Runs the TV's search request (WebSearch.body: the Responses
API with the web_search tool and the TV's instructions) for each model on questions whose answers were checked by hand,
and prints latency, whether it searched, and the answer, with the expected facts next to it.

python tests/agent/search_eval.py [--models a,b] [--instructions current|v2|v3|v4] [--parallel 6] [--effort low] [--context low] [--lists]
--lists adds questions with several answers (a ranking, a night of games): printed with their length, not scored.
Answers checked 2026-11-05 ~16:40: nobelprize.org, api.frankfurter.app (ECB), api.open-meteo.com, ESPN's scoreboard.
The key comes from GATEWAY_API_KEY and is never printed.
"""
import concurrent.futures, json, os, sys, time, urllib.request

KEY = os.environ['GATEWAY_API_KEY']
NOW = '2026-11-05 16:40 星期四'
QUESTIONS = [
    ('nobel', '2026年诺贝尔文学奖给了谁', ['卡森', 'Carson']),
    ('usd', '现在一美元能换多少人民币', ['6.6', '6.7']),
    ('weather', '上海今天天气怎么样', ['阴', '19', '20', '14', '13']),
    ('rain', '上海明天会下雨吗', ['不', '没']),
    ('celtics', '今天早上凯尔特人和雄鹿谁赢了，比分多少', ['109', '102']),
    ('knicks', '今天早上尼克斯打得怎么样', ['135', '105']),
]
CURRENT = ('你在帮中国大陆一户人家的电视语音助手查实时信息。现在是 {now}（北京时间）。先联网搜索，再用一两句中文说清结论：'
           '数字、日期、地点要具体；价格、汇率、油价、金价按人民币和国内的报价说。可以说来源名称，不要念网址，不要用 markdown，不要列表。')
V2 = ('你在帮中国大陆一户人家的电视语音助手查实时信息。现在是 {now}（北京时间）。\n'
      '1. 先联网搜索，至少看两个来源；比分、价格、汇率、天气这类数字以权威来源（官方、交易所、气象台、联赛官网、主流媒体）为准，'
      '来源之间不一致时说出差异，不要编。\n'
      '2. 注意时效：只用和问题时间对得上的信息（“今天”就是上面的日期；北京时间的“今天早上”的美国比赛是美国前一天晚上），'
      '旧消息不要当成新的。\n'
      '3. 用一两句中文说结论，数字、日期、地点要具体；价格、汇率按人民币说。可以说来源名称，不要念网址，不要用 markdown，不要列表。')
# v3 (2026-11-05 night): the answer is now spoken as it comes (web_search final=true), so it must be short by itself.
V3 = V2.replace('3. 用一两句中文说结论，数字、日期、地点要具体；价格、汇率按人民币说。可以说来源名称，不要念网址，不要用 markdown，不要列表。',
                '3. 回答会在电视上直接读出来：只说结论，一两句、80 个字以内；数字、日期、地点要具体，价格、汇率按人民币说。'
                '来源说法一致时不要逐个列举，最多提一个来源名；不一致时用半句话说差异。不要念网址，不要用 markdown，不要列表。')

# v4 (2026-11-06): v3 shrank 「查一下目前大模型的榜单」 to three names; a list now gets its first five, 200 characters at most.
V4 = V2.replace('3. 用一两句中文说结论，数字、日期、地点要具体；价格、汇率按人民币说。可以说来源名称，不要念网址，不要用 markdown，不要列表。',
                '3. 回答会在电视上直接读出来，不要用 markdown 和列表，不要念网址。只问一件事（一个数字、一个结果）就一两句、80 个字以内；'
                '问榜单、排名、几场比赛、几个选项这类好几项的，按顺序只说前 5 项、每项带名字和关键数字（分数、比分、价格），其余的一句带过；'
                '榜单要用最新的，不要用停更的旧榜；整段回答最多 200 个字，句子要说完整。数字、日期、地点要具体，价格、汇率按人民币说。来源说法一致时最多提一个来源名，不一致时用半句话说差异。\n'
                '4. 某人在 X（推特）、微博上的发言网页上常常跟不上：只说你看到的最新一条和它的日期，并说明可能还有更新的。')
LISTS = [
    ('board', '查一下目前大模型的榜单', []),
    ('games', '今天早上NBA都有哪些比赛结果', []),
]

EFFORT = None   # --effort low|minimal|medium: the Responses API reasoning effort (None: the model's default)
CONTEXT = None  # --context low|medium|high: web_search's search_context_size


def ask(model, instructions, question):
    tool = {'type': 'web_search'}
    if CONTEXT:
        tool['search_context_size'] = CONTEXT
    body = {'model': model, 'instructions': instructions.format(now=NOW),
            'input': [{'role': 'user', 'content': question}], 'tools': [tool], 'max_output_tokens': 600}
    if EFFORT:
        body['reasoning'] = {'effort': EFFORT}
    req = urllib.request.Request('https://gateway.example.com/v1/responses', json.dumps(body).encode('utf-8'),
                                 {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + KEY})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            d = json.loads(r.read().decode('utf-8'))
    except Exception as e:  # report and go on
        return time.time() - t0, False, 'ERROR ' + str(e)[:120]
    searched = any(o.get('type') == 'web_search_call' for o in d.get('output', []))
    text = ''
    for o in d.get('output', []):
        if o.get('type') == 'message':
            for c in o.get('content', []):
                if c.get('type') in ('output_text', 'text'):
                    text += c.get('text', '')
    return time.time() - t0, searched, ' '.join(text.split())


def main():
    args = sys.argv[1:]
    models = (args[args.index('--models') + 1] if '--models' in args else 'gpt-5.4-mini,gpt-5.4,gpt-5.5,gpt-5.6,gpt-6-sol,gpt-6.1-sol').split(',')
    which = args[args.index('--instructions') + 1] if '--instructions' in args else 'current'
    instr = {'v2': V2, 'v3': V3, 'v4': V4}.get(which, CURRENT)
    par = int(args[args.index('--parallel') + 1]) if '--parallel' in args else 6
    global EFFORT, CONTEXT
    EFFORT = args[args.index('--effort') + 1] if '--effort' in args else None
    CONTEXT = args[args.index('--context') + 1] if '--context' in args else None
    questions = QUESTIONS + (LISTS if '--lists' in args else [])
    jobs = [(m, q) for m in models for q in questions]
    with concurrent.futures.ThreadPoolExecutor(par) as pool:
        results = list(pool.map(lambda mq: (mq, ask(mq[0], instr, mq[1][1])), jobs))
    score = {}
    for (m, (qid, text, expect)), (secs, searched, answer) in results:
        if not expect:  # a list question: shown whole, with its length
            print(f'{m:14s} {qid:8s} {secs:5.1f}s {"S" if searched else "-"} LIST {len(answer)} chars: {answer}')
            continue
        hits = sum(1 for e in expect if e in answer)
        ok = hits >= (1 if qid in ('nobel', 'usd', 'rain') else 2)
        score.setdefault(m, [0, [], []])
        score[m][0] += ok
        score[m][1].append(len(answer))
        score[m][2].append(secs)
        print(f'{m:14s} {qid:8s} {secs:5.1f}s {"S" if searched else "-"} {"OK " if ok else "BAD"} {answer[:150]}')
    print()
    for m, (ok, chars, secs) in score.items():
        secs, chars = sorted(secs), sorted(chars)
        print(f'{m:14s} correct {ok}/{len(QUESTIONS)}  median {secs[len(secs) // 2]:.1f}s  max {secs[-1]:.1f}s'
              f'  answer chars median {chars[len(chars) // 2]} max {chars[-1]}')


if __name__ == '__main__':
    main()
