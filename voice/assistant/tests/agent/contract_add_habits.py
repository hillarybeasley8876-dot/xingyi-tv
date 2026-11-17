"""Adds the habits rule to the contract's 记忆 section. Run once; refuses to add twice."""
import json, os

P = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'assets', 'agent', 'contract.json')
c = json.load(open(P, encoding='utf-8'))
RULE = ("- 「关于用户」里标着“根据使用记录自动整理”的几行，是你从用户平时的使用里学到的习惯（常用应用、常搜的UP主、常听的歌手和歌、"
        "习惯的音量、常用信号源、多在什么时段），可能不准：用户的话含糊（放首歌、看看我的频道、老样子、音量调回平时的）时参考它；"
        "用户说得明确时照用户说的做，不要拿习惯替用户改主意。")
s = c['system']
assert '根据使用记录自动整理”的几行' not in s, 'habits rule already in the contract'
anchor = '\n- 不记一次性的请求'
assert s.count(anchor) == 1, 'anchor missing'
c['system'] = s.replace(anchor, '\n' + RULE + anchor)
with open(P, 'w', encoding='utf-8', newline='\n') as f:
    f.write(json.dumps(c, ensure_ascii=False, indent=1) + '\n')
print('contract: habits rule added')
