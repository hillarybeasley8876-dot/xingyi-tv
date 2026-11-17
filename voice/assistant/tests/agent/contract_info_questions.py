"""Information questions are answered by web search and voice, never by opening an app; news and rumors are reported,
not refused. Run once; refuses to apply twice.

Why (2026-11-05 21:00 on the TV): 「今天听说那个杨永浩出事了好像网上都在传」 then 「我说的是姚润浩叠纸的那个姚润浩」 with B站
in front: 星仪 searched the web, then opened B站's search for 「姚润浩叠纸」, then refused (「不能帮你传播」). The user:
「为什么搜索用的是bilibili 你应该自己联网搜啊 不开任何软件」."""
import json, os

P = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'assets', 'agent', 'contract.json')
c = json.load(open(P, encoding='utf-8'))
s = c['system']
assert '不要打开任何应用' not in s, 'already applied'
old = '   - 用户只是问一件需要最新信息的事（天气、新闻、比分、赛程、价格、汇率、航班、上映、热搜），不要调用 plan：直接调用 web_search，再用一两句话回答。'
assert s.count(old) == 1, 'anchor'
new = ('   - 用户只是问一件需要最新信息的事（天气、新闻、比分、赛程、价格、汇率、航班、上映、热搜、某个人或某件事最近怎么了、网上在传什么），'
       '包括对上一个问题的补充（“我说的是…那个…”），不要调用 plan：直接调用 web_search，再用一两句话回答。只是问问题时不要打开任何应用、'
       '也不要在应用里搜（哪怕前台正开着 B站），除非用户明确说了要在哪个应用里看或搜。')
s = s.replace(old, new)
old2 = '- 需要最新信息时用 web_search 查，不要凭记忆回答；'
assert s.count(old2) == 1, 'anchor 2'
new2 = ('- 问到某人“出事了”、网上的传闻和热搜时，照样用 web_search 查，如实说权威媒体和当事方说了什么、哪些还没被证实；'
        '没有可靠消息就说没查到可靠消息。不要拒绝回答，也不要替用户下结论或编造。\n'
        '- 需要最新信息时用 web_search 查，不要凭记忆回答；')
s = s.replace(old2, new2)
c['system'] = s
with open(P, 'w', encoding='utf-8', newline='\n') as f:
    f.write(json.dumps(c, ensure_ascii=False, indent=1) + '\n')
print('contract: information questions stay on the web')
