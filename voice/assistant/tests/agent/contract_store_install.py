"""install_app takes an app name and installs from the Xiaomi TV app store too; the rule to install what is missing.
Run once; refuses to apply twice.

Why (2026-11-05): 「缺少的依赖也不自己安装」 — install_app only knew B站, so 「用爱奇艺看…」 on a TV without it ended in
「电视上没有爱奇艺」."""
import json, os

P = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'assets', 'agent', 'contract.json')
c = json.load(open(P, encoding='utf-8'))
done = False
for t in c['tools']:
    f = t['function']
    if f['name'] != 'install_app':
        continue
    assert 'app' not in f['parameters']['properties'], 'already applied'
    f['description'] = ('安装电视上还没有的应用：哔哩哔哩从官方网站下载并校验；其他应用从小米电视应用商店安装，只有商店目录里有的才能装'
                        '（比如 爱奇艺=银河奇异果、腾讯视频=云视听极光、芒果TV、QQ音乐、酷狗音乐、央视频、快手、喜马拉雅、全民K歌）。'
                        '用户这句话里点名的应用直接安装；不是用户点名的，系统会先问用户。会打开商店页面，装好要几十秒到几分钟，'
                        '电视在待机时先 wake_screen。')
    f['parameters'] = {'type': 'object', 'properties': {
        'app': {'type': 'string', 'description': '应用名（用户说的叫法或商店里的名字）或包名'}}, 'required': ['app']}
    done = True
assert done, 'no install_app'
s = c['system']
old = '- 安装应用、同意协议、付款、登录、删除等敏感操作由系统向用户确认，你照常调用工具，结果里会说明用户是否同意。'
assert s.count(old) == 1, 'anchor'
new = ('- 用户要用的应用电视上没装时，不要只说没装：先 install_app 装好（open_app 的结果会提示商店里有没有），再接着做原来的事。\n'
       '- 同意协议、付款、登录、删除，以及安装用户没点名的应用，都由系统向用户确认，你照常调用工具，结果里会说明用户是否同意。')
c['system'] = s.replace(old, new)
with open(P, 'w', encoding='utf-8', newline='\n') as f:
    f.write(json.dumps(c, ensure_ascii=False, indent=1) + '\n')
print('contract: install_app from the store')
