"""Adds set_hints and the quiet-card rule. Run once; refuses to apply twice."""
import json, os

P = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'assets', 'agent', 'contract.json')
c = json.load(open(P, encoding='utf-8'))
assert 'set_hints' not in [t['function']['name'] for t in c['tools']], 'already applied'
c['tools'].append({'type': 'function', 'function': {
    'name': 'set_hints',
    'description': '打开或关掉亮屏提示卡（电视亮屏时右上角安静显示今天的预约、没划掉的随手记和这个时段常做的事，不出声）。',
    'parameters': {'type': 'object', 'properties': {'on': {'type': 'boolean', 'description': 'true=打开，false=关掉'}},
                   'required': ['on']}}})
s = c['system']
anchor = '\n\n## 记忆\n'
assert s.count(anchor) == 1, 'anchor missing'
RULE = ('- 电视亮屏时，右上角会安静地弹一张提示卡（今天的预约、没划掉的随手记、这个时段常做的事），不出声。用户说“别弹提示了”“关掉提示”时用 '
        'set_hints(on=false)，说“打开提示”时用 set_hints(on=true)。')
c['system'] = s.replace(anchor, '\n' + RULE + anchor)
with open(P, 'w', encoding='utf-8', newline='\n') as f:
    f.write(json.dumps(c, ensure_ascii=False, indent=1) + '\n')
print('contract: set_hints, %d tools' % len(c['tools']))
