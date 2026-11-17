"""Adds the scene tools to the contract: schedules (schedule_task / list_schedules / cancel_schedule), quick notes
(note_add / note_list / note_done) and recall_history, with their rules. Run once; refuses to apply twice.

Why (2026-11-05 audit against a generic agent architecture: 主动性 / 场景能力「定时」「随手记」 / 记忆): 星仪 could not do
anything at a later time, kept no notes, and remembered nothing older than three hours."""
import json, os

P = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'assets', 'agent', 'contract.json')
c = json.load(open(P, encoding='utf-8'))
names = [t['function']['name'] for t in c['tools']]
assert 'schedule_task' not in names, 'already applied'


def tool(name, description, properties, required):
    return {'type': 'function', 'function': {'name': name, 'description': description,
            'parameters': {'type': 'object', 'properties': properties, 'required': required}}}


WHICH = {'type': 'string', 'description': '编号、内容里的一个词，或者 all（全部）'}
c['tools'] += [
    tool('schedule_task',
         '预约将来某个时间的事：到点时星仪照 what 这句话去做（kind=task，例如“播放新闻联播”“暂停”），或者到点把 what 读给用户'
         '（kind=remind，例如“该吃药了”）。时间按 environment.time 换算；只说了几点、没说哪天时，取下一个这个时间。返回预约好的时间。',
         {'kind': {'type': 'string', 'enum': ['task', 'remind'], 'description': 'task=到点照 what 去做；remind=到点读出 what'},
          'what': {'type': 'string', 'description': '到点要做的事或要读的提醒，写成用户会说的一句话，不超过 60 字'},
          'time': {'type': 'string', 'description': '几点，24 小时制 HH:mm，例如 07:00、23:30；和 in_minutes 二选一'},
          'date': {'type': 'string', 'description': '哪天，YYYY-MM-DD；只说了几点时不填'},
          'in_minutes': {'type': 'integer', 'description': '多少分钟以后，1 到 1440；和 time 二选一'},
          'repeat': {'type': 'string', 'enum': ['once', 'daily', 'weekdays', 'weekends'],
                     'description': '重复：once 只一次（默认）、daily 每天、weekdays 工作日、weekends 周末'}},
         ['kind', 'what']),
    tool('list_schedules', '列出所有预约（定时任务和到点提醒），带编号和下一次的时间。', {}, []),
    tool('cancel_schedule', '取消预约。', {'which': WHICH}, ['which']),
    tool('note_add',
         '随手记：记下一件用户要你帮忙记着的事（待办、要买的东西、要带的东西），一句话。没有具体提醒时间的用它；有时间的提醒用 '
         'schedule_task(kind=remind)；长期的偏好和称呼用 remember。密码、验证码、证件号不记。',
         {'text': {'type': 'string', 'description': '要记下的事，不超过 80 字，例如“明天买牛奶”'}}, ['text']),
    tool('note_list', '列出随手记里还没划掉的事，带编号和记下的时间。', {}, []),
    tool('note_done', '把随手记里做完的事划掉。', {'which': WHICH}, ['which']),
    tool('recall_history',
         '查最近 30 天里用户让星仪做过的事：当时说的话、星仪的回答、做完时在放什么、什么时候。用户说“昨天/上次/前几天/那天晚上那个…”'
         '而 recent_turns 里找不到时先查它。',
         {'query': {'type': 'string', 'description': '要找的词，例如“视频”“林俊杰”“B站”；不填就列最近的'},
          'days': {'type': 'integer', 'description': '查最近几天，1 到 30，默认 7'}}, []),
]

s = c['system']
assert '## 定时、随手记和回忆' not in s, 'already applied'
anchor = '\n## 记忆\n'
assert s.count(anchor) == 1, 'anchor missing'
SECTION = '''
## 定时、随手记和回忆
- 要在将来某个时间做的事（“明天早上7点放新闻联播”“每天晚上11点关电视”“半小时后暂停”）用 schedule_task(kind=task)，what 写成到时候要执行的一句话。到点时星仪会照这句话去做，并读出结果。不需要 plan。
- 闹钟和提醒小爱自己会设：environment.note 里小爱已经设了闹钟或提醒时，只告诉用户结果，不要再预约。小爱没处理、又要到点读一句话的（“8点提醒我吃药”）用 schedule_task(kind=remind)。“多少分钟后关电视”仍然用 sleep_timer。
- 用户问“我定了哪些”“把明天那个取消”时用 list_schedules / cancel_schedule。
- 用户说“记一下…”“帮我记着…”“别忘了…”而没有具体时间时，用 note_add 记进随手记；问“我让你记了什么”“我的待办”时用 note_list（「关于用户」里也列着还没划掉的）；说“…买了/办完了/划掉吧”时用 note_done。随手记是一次性的事，长期的偏好和称呼仍然用 remember。
- 用户说“昨天/上次/前几天/那天晚上看的那个…”，而 recent_turns 里找不到时，先用 recall_history 查最近 30 天做过的事，再照着办（比如再放一遍同一个视频）。
'''
c['system'] = s.replace(anchor, SECTION + anchor)

with open(P, 'w', encoding='utf-8', newline='\n') as f:
    f.write(json.dumps(c, ensure_ascii=False, indent=1) + '\n')
print('contract: %d tools, scene rules added' % len(c['tools']))
