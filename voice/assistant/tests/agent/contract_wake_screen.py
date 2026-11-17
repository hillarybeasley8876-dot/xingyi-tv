"""Adds wake_screen and the environment.screen rule. Run once; refuses to apply twice.

Why: a scheduled task (「明天早上7点放新闻联播」) and any request made while the TV is in standby need the picture, and
with the screen off the TV does not respond to clicks (HARNESS 已知限制). environment.screen now says whether it is on."""
import json, os

P = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'assets', 'agent', 'contract.json')
c = json.load(open(P, encoding='utf-8'))
assert 'wake_screen' not in [t['function']['name'] for t in c['tools']], 'already applied'
c['tools'].append({'type': 'function', 'function': {
    'name': 'wake_screen',
    'description': '点亮屏幕（电视在待机时）。返回屏幕是否已经亮了；屏幕本来就开着时什么都不做。',
    'parameters': {'type': 'object', 'properties': {}, 'required': []}}})
s = c['system']
anchor = '\n\n## 应用经验\n'
assert s.count(anchor) == 1 and 'environment.screen' not in s, 'anchor missing or already applied'
RULE = ('- environment.screen 是“关着”时电视在待机：要看画面、打开应用、读屏或点选的事（放视频、打开B站、看设置），先调用 '
        'wake_screen 再做；放歌、调音量、关电视、预约和随手记不用点亮。\n')
c['system'] = s.replace(anchor, '\n' + RULE + '\n## 应用经验\n')
with open(P, 'w', encoding='utf-8', newline='\n') as f:
    f.write(json.dumps(c, ensure_ascii=False, indent=1) + '\n')
print('contract: wake_screen, %d tools' % len(c['tools']))
