"""One-step requests skip the plan; plans that are written get short. Run once; refuses to apply twice.

The loop lets one-step connectors run without a plan and answers with their own result when it proves itself
(AgentLoop.DIRECT / directAnswer): a plan costs the first round about a second of writing (2026-11-05 calibration:
R1 1.5-3.2 s with a plan, 0.7-1.3 s without; round1_probe: ~170 output tokens, most of them the plan)."""
import json, os

P = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'assets', 'agent', 'contract.json')
c = json.load(open(P, encoding='utf-8'))
s = c['system']
assert '一个连接器一步就能做完的请求' not in s, 'already applied'

old1 = '1. 先理解。每个任务的第一个工具调用必须是 plan：'
new1 = '1. 先理解。需要两步以上的任务，第一个工具调用必须是 plan：'
old1b = 'steps 列出 1 到 6 个具体步骤，done_when 写出怎样算完成。'
new1b = ('steps 列出 1 到 6 个具体步骤，done_when 写出怎样算完成。三项都写短（goal 不超过 25 个字，每步不超过 15 个字，'
         'done_when 不超过 20 个字）：plan 写得越长，用户等得越久。\n'
         '   - 一个连接器一步就能做完的请求，不要调用 plan，直接调用这个连接器：点歌 netease_play、播放列表 netease_playlist、'
         '只搜索不播放的 bili_search、set_volume、adjust_volume、media_control、sleep_timer、switch_input、open_app、'
         'open_settings、交给小爱的 xiaoai_ask。做成后系统会直接把结果告诉用户；没做成，或者还要接着点选、播放，你会看到'
         '结果，再 plan 或回答。')
old3 = '   - 没有调用 plan 之前，系统只允许读取类工具（screen_read、tv_status、list_apps、media_now_playing 等），操作类工具会被拒绝。'
new3 = ('   - 没有调用 plan 之前，系统只允许读取类工具（screen_read、tv_status、list_apps、media_now_playing 等）和上面这些'
        '连接器；点选、按键、输入、打开链接、安装等操作会被拒绝。')
# resuming: re-plan first, or the first click is refused (calibration "cont" took 5 rounds without this)
old4 = '先看当前屏幕，从第一个未完成的步骤接着做，已完成的不要重做；'
new4 = ('先调用 plan（steps 只写还没完成的步骤，可以和 screen_read 放在同一轮），再从第一个未完成的步骤接着做，'
        '已完成的不要重做；')
for old in (old1, old1b, old3, old4):
    assert s.count(old) == 1, 'anchor missing: ' + old[:30]
c['system'] = s.replace(old1, new1).replace(old1b, new1b).replace(old3, new3).replace(old4, new4)

done = False
for t in c['tools']:
    f = t['function']
    if f['name'] == 'plan':
        old = '任务开始时登记你对用户意图的理解和执行计划，会显示在电视上。'
        assert f['description'].count(old) == 1, 'plan description changed'
        f['description'] = f['description'].replace(
            old, '需要两步以上的任务开始时，登记你对用户意图的理解和执行计划，会显示在电视上；一个连接器一步能做完的请求不用 plan。')
        done = True
assert done, 'no plan tool'

with open(P, 'w', encoding='utf-8', newline='\n') as f:
    f.write(json.dumps(c, ensure_ascii=False, indent=1) + '\n')
print('contract: one-step requests skip the plan; plans stay short')
