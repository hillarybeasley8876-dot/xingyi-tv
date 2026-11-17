"""Adds the "evidence from a connector is enough" rule to the contract (fewer rounds). Run once; refuses to add twice."""
import json, os

P = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'assets', 'agent', 'contract.json')
c = json.load(open(P, encoding='utf-8'))
RULE = ("   - 工具结果本身已经是证据时（netease_play 返回“网易云正在播放…”，screen_click 返回“正在播放《…》”，set_volume/adjust_volume "
        "返回了音量，open_app 返回已打开），直接 finish（可以和最后的 plan_update 放在同一轮），不要再调 media_now_playing、tv_status 或 "
        "screen_look 重复核对：每多一轮，用户要多等一到两秒。")
s = c['system']
assert '每多一轮，用户要多等一到两秒' not in s, 'rule already in the contract'
anchor = '\n5. 收尾。'
assert s.count(anchor) == 1, 'anchor missing'
c['system'] = s.replace(anchor, '\n' + RULE + anchor)
with open(P, 'w', encoding='utf-8', newline='\n') as f:
    f.write(json.dumps(c, ensure_ascii=False, indent=1) + '\n')
print('contract: evidence rule added')
