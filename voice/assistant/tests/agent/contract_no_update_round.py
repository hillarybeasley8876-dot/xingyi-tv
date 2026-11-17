"""Tells the model it never needs a plan_update round before finish (finish(done) closes the steps). Run once."""
import json, os

P = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'assets', 'agent', 'contract.json')
c = json.load(open(P, encoding='utf-8'))
s = c['system']
old1 = '可以把最后的 plan_update 和 finish 放在同一轮。'
new1 = ('status 写 done 时系统会自动把所有步骤标成完成：收尾时直接调用 finish，不要先单独花一轮调 plan_update'
        '（每多一轮，用户要多等一到两秒）。')
old2 = '直接 finish（可以和最后的 plan_update 放在同一轮）'
new2 = '直接 finish（不用先 plan_update）'
assert s.count(old1) == 1 and s.count(old2) == 1, 'anchors missing (already applied?)'
c['system'] = s.replace(old1, new1).replace(old2, new2)
with open(P, 'w', encoding='utf-8', newline='\n') as f:
    f.write(json.dumps(c, ensure_ascii=False, indent=1) + '\n')
print('contract: finish closes the steps; no plan_update round')
