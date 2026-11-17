"""Writes PROMPTS.md: every text this harness puts in front of a model, taken from the code itself (contract.json and
the Java sources), so the document and the running prompts cannot drift apart.

python tests/agent/prompts_doc.py          regenerate PROMPTS.md
python tests/agent/prompts_doc.py --check  exit 1 and show what changed when PROMPTS.md is out of date
"""
import difflib, json, os, re, sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
BRIDGE = os.path.normpath(os.path.join(ROOT, '..', '..', 'xiaoai-bridge'))
AGENT = os.path.join(ROOT, 'src', 'xyz', 'erapi0neer', 'xingyi', 'agent')
OUT = os.path.join(ROOT, 'PROMPTS.md')


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


# ---- Java source helpers ---------------------------------------------------------------------------------------

def skip_literal(src, i):
    """src[i] is a quote: the index just past the matching closing quote."""
    q, i = src[i], i + 1
    while src[i] != q:
        i += 2 if src[i] == '\\' else 1
    return i + 1


def literal(src, i):
    """The Java string literal starting at src[i] == '"', unescaped, and the index after it."""
    out, j = [], i + 1
    while src[j] != '"':
        if src[j] == '\\':
            nxt = src[j + 1]
            out.append({'n': '\n', 't': '\t', '"': '"', "'": "'", '\\': '\\'}.get(nxt, nxt))
            j += 2
        else:
            out.append(src[j])
            j += 1
    return ''.join(out), j + 1


def expression(src, i):
    """A string expression from src[i]: literals joined with +, other terms shown as {term}. Ends at , ) or ; on
    its own level."""
    parts, n = [], len(src)
    while True:
        while src[i] in ' \t\r\n':
            i += 1
        if src[i] == '"':
            text, i = literal(src, i)
            parts.append(text)
        else:
            depth, j = 0, i
            while j < n:
                c = src[j]
                if c in '"\'':
                    j = skip_literal(src, j)
                    continue
                if c in '([{':
                    depth += 1
                elif c in ')]}':
                    if depth == 0:
                        break
                    depth -= 1
                elif depth == 0 and c in '+,;':
                    break
                j += 1
            parts.append('{' + ' '.join(src[i:j].split()) + '}')
            i = j
        while src[i] in ' \t\r\n':
            i += 1
        if src[i] != '+':
            return ''.join(parts)
        i += 1


def after(src, pattern, start=0):
    """The string expression that begins right after the first match of pattern (a regex)."""
    m = re.compile(pattern).search(src, start)
    if not m:
        raise SystemExit('anchor not found: ' + pattern)
    return expression(src, m.end())


def block(src, pattern):
    """The source text of the method or block whose header matches pattern, braces included, dedented."""
    m = re.compile(pattern).search(src)
    if not m:
        raise SystemExit('anchor not found: ' + pattern)
    line_start = src.rfind('\n', 0, m.start()) + 1
    i, depth = src.index('{', m.start()), 0
    while True:
        c = src[i]
        if c in '"\'':
            i = skip_literal(src, i)
            continue
        if src.startswith('//', i):
            i = src.index('\n', i)
            continue
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                break
        i += 1
    lines = src[line_start:i + 1].split('\n')
    indent = min(len(l) - len(l.lstrip()) for l in lines if l.strip())
    return '\n'.join(l[indent:] for l in lines)


def between(src, start_pattern, end_pattern):
    """The lines from the one matching start_pattern to the one matching end_pattern, dedented."""
    s = re.compile(start_pattern).search(src)
    e = re.compile(end_pattern).search(src, s.end()) if s else None
    if not s or not e:
        raise SystemExit('anchors not found: %s .. %s' % (start_pattern, end_pattern))
    a, b = src.rfind('\n', 0, s.start()) + 1, src.find('\n', e.end())
    lines = src[a:b].split('\n')
    indent = min(len(l) - len(l.lstrip()) for l in lines if l.strip())
    return '\n'.join(l[indent:] for l in lines)


def tool_at(src, pos):
    """The tool (new Base("name")) whose body contains pos."""
    m = None
    for m in re.finditer(r'new Base\((?:[^()"]*,\s*)*"([a-z_]+)"', src[:pos]):
        pass
    return m.group(1) if m else '?'


def number(src, pattern):
    m = re.search(pattern, src)
    if not m:
        raise SystemExit('anchor not found: ' + pattern)
    return m.group(1)


def fence(text, lang='text'):
    longest = max([len(r) for r in re.findall(r'`+', text)] + [2])
    ticks = '`' * (longest + 1)
    return ticks + lang + '\n' + text.rstrip('\n') + '\n' + ticks


# ---- the document -------------------------------------------------------------------------------------------------

def build():
    contract = json.loads(read(os.path.join(ROOT, 'assets', 'agent', 'contract.json')))
    loop = read(os.path.join(AGENT, 'AgentLoop.java'))
    engine = read(os.path.join(AGENT, 'tv', 'AgentEngine.java'))
    tvtools = read(os.path.join(AGENT, 'tv', 'TvTools.java'))
    search = read(os.path.join(AGENT, 'SearchBudget.java'))
    adb = read(os.path.join(AGENT, 'AdbUnavailable.java'))
    web = read(os.path.join(AGENT, 'WebSearch.java'))
    cap = read(os.path.join(AGENT, 'DailyCap.java'))
    client = read(os.path.join(ROOT, 'src', 'xyz', 'erapi0neer', 'xingyi', 'MiniMaxClient.java'))
    takeover = read(os.path.join(BRIDGE, 'src', 'xyz', 'erapi0neer', 'xiaoaibridge', 'Takeover.java'))
    llm = read(os.path.join(BRIDGE, 'src', 'xyz', 'erapi0neer', 'xiaoaibridge', 'Llm.java'))
    out = []
    w = out.append

    w('# 星仪提示词全文')
    w('')
    w('> 由 `python tests/agent/prompts_doc.py` 从 `assets/agent/contract.json` 和源码生成，和运行中的文字逐字一致。'
      '改了任何提示词、工具说明或下面列出的句子，都要重新生成；`--check` 在本文件过期时报错。'
      '非字面量的部分写成 `{表达式}`；拼接复杂的地方直接贴源码。')
    w('')

    w('## 1. 一轮请求由什么组成')
    w('')
    w('发给模型的 `messages`，按顺序：')
    w('')
    w('1. `system`：`contract.json` 的 `system` 原文（第 2 节），一个字都不追加，好让网关的提示缓存命中。')
    w('2. 有记忆时，先一条 `user`：「关于用户」加记住的事实、自动整理的习惯和还没划掉的随手记（`AgentEngine.profileText`）；'
      '它排在原话前面，没变的时候和系统提示一起命中提示缓存。')
    w('3. 然后一条 `user`：一个 JSON 对象，字段见下面 `AgentLoop.run` 的代码：`utterance`（按专名表改成正确写法的原话）、'
      '`environment`（`AgentEngine.environment`：时间、前台应用、音量、正在播放、屏幕开没开；交接时附 `note`，见第 6 节）、'
      '`previous_task`（上一项任务，2 小时内，加 `minutes_ago`）、`recent_turns`（最近 6 次问答，3 小时内）。')
    w('4. 之后每一轮：模型的 `assistant` 消息（带 `tool_calls`），然后每个调用一条 `tool` 消息，按模型给的顺序；'
      '执行顺序是 plan 先、ask_user/finish 最后。系统的话夹在工具结果里（第 4、5 节）。')
    gates = contract.get('gates', {})
    w('5. 工具定义：`contract.json` 的 `tools` 共 %d 个（第 3 节）。其中 %d 个公开数据工具按需发送（`contract.json` → `gates`，'
      '`Contract.gatedIn` / `toolsFor` / `systemFor`）：请求、它追问的上一句、它回答的那个问题、刚读出的列表（`last_list`）里'
      '有对应的词才发这个工具和规则里提到它的那几行；都没有时每轮发 %d 个、系统提示去掉这些行，前缀固定，网关的提示缓存照常命中。'
      '升级到强模型后发全部工具；没发的工具模型照样能调（`Contract.check` 和电视都认）。' % (
        len(contract['tools']), len(gates), len(contract['tools']) - len(gates)))
    w('')
    w('上限（`AgentLoop.Limits` 和 `run`）：%s 轮模型、%s 次工具调用、%s 秒；累计 %s 次失败时提示换方法；消息超过 %s 条就收尾；'
      '只保留最近 2 份屏幕，更早的换成「（较早的画面已省略）」。没有按 token 计数，也没有对长任务做摘要压缩。' % (
          number(loop, r'public int rounds = (\d+)'), number(loop, r'calls = (\d+)'),
          int(number(loop, r'public long millis = ([\d_]+)').replace('_', '')) // 1000,
          number(loop, r'failures = (\d+)'), number(loop, r'messages\.length\(\) > (\d+)')))
    w('')
    w('模型参数（`MiniMaxClient`）：`reasoning_effort=%s`，`max_completion_tokens=%s`，`stream=false`。' % (
        number(client, r'"reasoning_effort", "(\w+)"'), number(client, r'"max_completion_tokens", (\d+)')))
    w('')
    w('第一条 `user` 消息的构造（`AgentLoop.run`）：')
    w('')
    w(fence(between(loop, r'JSONObject input = new JSONObject\(\)', r'messages\.put\(message\("user", input\.toString\(\)\)\);'), 'java'))
    w('')
    w('「关于用户」那条消息的第一行：`%s`。' % after(loop, r'static final String PROFILE_HEADER =\s*').replace('\n', '\\n'))
    w('')
    w('`environment`（`AgentEngine`）：')
    w('')
    w(fence(block(engine, r'private JSONObject environment\(TvTools tools, String said\)'), 'java'))
    w('')
    w('`关于用户`（`AgentEngine`）：')
    w('')
    w(fence(block(engine, r'private String profileText\(\)'), 'java'))
    w('')

    w('## 2. 主系统提示（`contract.json` → `system`）')
    w('')
    w(fence(contract['system']))
    w('')

    w('## 3. 工具说明（%d 个，`contract.json` → `tools`）' % len(contract['tools']))
    w('')
    for t in contract['tools']:
        f = t['function']
        w('### `%s`' % f['name'])
        w('')
        w(f['description'])
        if f['name'] in gates:
            w('')
            w('按需发送，触发词：`%s`' % (gates[f['name']] if len(gates[f['name']]) <= 300 else gates[f['name']][:300] + '…'))
        params = f.get('parameters', {})
        required = set(params.get('required', []))
        for name, p in params.get('properties', {}).items():
            kind = p.get('type', '')
            if kind == 'array':
                kind = 'array<%s>' % p.get('items', {}).get('type', '')
            extra = '，取值 ' + ' / '.join(str(v) for v in p['enum']) if 'enum' in p else ''
            desc = p.get('description', '')
            w('- `%s`（%s，%s%s）%s' % (name, kind, '必填' if name in required else '可选', extra, '：' + desc if desc else ''))
        w('')

    w('## 3b. 应用经验（按需加载，`contract.json` → `skills`）')
    w('')
    w('请求的话里有匹配词、前台是这个应用、或者是一句短的追问 / 「继续」而上一句匹配时，放进原话那条消息的 `应用经验`；'
      '任务中途用了这个应用的连接器、或者 open_app / open_link 打开它时，附在那个工具结果的 `应用经验` 里；一个任务里每个只给一次'
      '（`Skills.java`）。')
    w('')
    for k in contract.get('skills', []):
        w('### %s' % k['name'])
        w('')
        w('匹配词：%s；前台包名：%s；连接器：%s' % ('、'.join(k['match']), '、'.join(k['packages']) or '—', '、'.join(k['tools']) or '—'))
        w('')
        w(fence(k['text']))
        w('')

    w('## 4. 循环塞进工具结果的话（`AgentLoop` 及其常量）')
    w('')
    w('| 哪里 | 原文 |')
    w('|---|---|')
    row = lambda where, text: w('| %s | %s |' % (where, text.replace('|', '\\|').replace('\n', '<br>')))
    row('`AgentLoop.NEED_PLAN`（没有计划时的操作）', after(loop, r'static final String NEED_PLAN =\s*'))
    row('`AgentLoop.JUST_ASKING`（只是提问时打开应用）', after(loop, r'static final String JUST_ASKING =\s*'))
    row('`AgentLoop.NO_SECOND_ASK`（回答了问题又被追问）', after(loop, r'static final String NO_SECOND_ASK =\s*'))
    row('`AgentLoop.SHORTER`（回答超过 LONG_ANSWER 字）', after(loop, r'static final String SHORTER =\s*'))
    row('`AgentLoop.shorterAfter`（太长、第一页已读出时）', after(loop, r'static String shorterAfter\(String read\) \{\s*return\s*'))
    for m in re.finditer(r'\.put\("(harness_note|loop_note)",\s*', loop):
        row('`%s`' % m.group(1), expression(loop, m.end()))
    for m in re.finditer(r'result\(false, "(\w+)",\s*(?=")', loop):
        row('`result(false, "%s", …)`' % m.group(1), expression(loop, m.end()))
    for name in ('SAME_ROUND', 'TASK_DONE', 'FOUND'):
        row('`SearchBudget.%s`' % name, after(search, r'static final String %s =\s*' % name))
    for name in ('MESSAGE', 'NOTE'):
        row('`AdbUnavailable.%s`' % name, after(adb, r'public static final String %s =\s*' % name))
    row('`DailyCap.take`（付费搜索到了每天的上限）', after(cap, r'if \(used >= max\)\s*return\s*'))
    w('')

    w('## 5. 工具闸门的拒绝理由（`TvTools` → `Gate.deny`）')
    w('')
    w('| 工具 | 原文 |')
    w('|---|---|')
    for m in re.finditer(r'Gate\.deny\(\s*(?=")', tvtools):
        row('`%s`' % tool_at(tvtools, m.start()), expression(tvtools, m.end()))
    w('')

    w('## 6. `environment.note`：交给星仪时附带的说明')
    w('')
    w('接管模式下接力描述小爱自己做了什么（`xiaoai-bridge` `Takeover.xiaoaiNote`）：')
    w('')
    w(fence(block(takeover, r'static String xiaoaiNote\('), 'java'))
    w('')
    w('进程被系统清理后自动续做（`AgentEngine.resume`）：')
    w('')
    w(fence(after(engine, r'String note =\s*')))
    w('')

    w('## 7. 看画面（`screen_look` → 视觉模型）')
    w('')
    w('`system`：')
    w('')
    w(fence(after(tvtools, r'\.put\("role", "system"\)\.put\("content",\s*')))
    w('')
    w('`user`：模型在 `question` 里写的问题 + 截图（1280 宽 JPEG）。')
    w('')

    w('## 8. 联网搜索（`WebSearch.instructions`，发给 `%s`）' % number(web, r'DEFAULT_MODEL = "([^"]+)"'))
    w('')
    w(fence(after(web, r'static String instructions\(String now, String place\) \{\s*return\s*')))
    w('')

    w('## 9. 接力的旧兜底回答（`xiaoai-bridge` `Llm.PROMPT`，现在只有 `bridge.sh ask` 调试广播用）')
    w('')
    w(fence(after(llm, r'private static final String PROMPT =\s*')))
    return '\n'.join(out) + '\n'


def main():
    text = build()
    if '--check' in sys.argv[1:]:
        old = read(OUT) if os.path.exists(OUT) else ''
        if old == text:
            print('PROMPTS.md is up to date')
            return 0
        diff = difflib.unified_diff(old.splitlines(), text.splitlines(), 'PROMPTS.md', 'from the code', lineterm='', n=1)
        print('\n'.join(list(diff)[:60]))
        print('PROMPTS.md is out of date: run python tests/agent/prompts_doc.py')
        return 1
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    print('PROMPTS.md written (%d lines)' % text.count('\n'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
