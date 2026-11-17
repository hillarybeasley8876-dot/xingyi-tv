"""Moves the app-specific bullets of 「## 应用经验」 out of the system prompt into "skills", loaded on demand
(Skills.java / AgentLoop). Run once; refuses to apply twice.

Why (2026-11-05 audit, 「工具与 Skill」): how to work B站, NetEase and the settings pages went into every request,
including 「现在几点」; each new app would have grown the prompt for all of them."""
import json, os

P = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'assets', 'agent', 'contract.json')
c = json.load(open(P, encoding='utf-8'))
assert 'skills' not in c, 'already applied'
s = c['system']

BILI = [
    '- 哔哩哔哩（tv.danmaku.bili，手机版，很占内存，冷启动要十几秒）：搜索用 bili_search。',
    '- 用户点名一类视频（MAD、教程、合集、直播回放等）或一个标题时，',
    '- 要某个UP主播放量最高（最火）的视频时：',
]
NETEASE = ['- 网易云音乐：点歌、播放歌手的歌一律用 netease_play，']
SETTINGS = ['- 电视设置：查看或修改设置（WiFi、蓝牙、网络、图像声音、存储、关于本机等）用 open_settings 直达对应页面，']


def take(starts):
    """Removes the bullets that begin with these texts from the system prompt; returns them, joined."""
    global s
    out = []
    for start in starts:
        i = s.index(start)
        j = s.index('\n', i)
        out.append(s[i + 2:j])  # without the leading 「- 」
        s = s[:i] + s[j + 1:]
    return '\n'.join(out)


skills = [
    {'name': '哔哩哔哩', 'text': take(BILI),
     'match': ['B站', 'b站', '哔哩', 'bilibili', 'UP主', 'up主', '视频', '番剧', 'MAD', '投稿', '频道', '弹幕'],
     'packages': ['tv.danmaku.bili'], 'tools': ['bili_search']},
    {'name': '网易云音乐', 'text': take(NETEASE),
     'match': ['网易云', '歌', '音乐', '专辑', '单曲', '曲子', '唱', '听歌', '听首', '听一首', '想听', '听听'],  # not 「听」: 「今天听说…」
     'packages': ['com.netease.cloudmusic.tv'], 'tools': ['netease_play', 'netease_playlist']},
    {'name': '电视设置', 'text': take(SETTINGS),
     'match': ['设置', 'WiFi', 'wifi', 'Wi-Fi', '无线', '网络', '网速', '蓝牙', '存储', '内存', '本机', '系统版本', '型号', 'IP'],
     'packages': ['com.android.tv.settings', 'com.xiaomi.mitv.settings'], 'tools': ['open_settings']},
]
anchor = '## 应用经验\n'
assert s.count(anchor) == 1
s = s.replace(anchor, anchor + '- 用户消息里的「应用经验」是这次请求要用到的应用的操作经验，照着做；做到一半打开了另一个应用、或者用了它的连接器时，'
                               '那个工具的结果里也会附上它的「应用经验」。\n')
c['system'] = s
c['skills'] = skills
with open(P, 'w', encoding='utf-8', newline='\n') as f:
    f.write(json.dumps(c, ensure_ascii=False, indent=1) + '\n')
print('contract: %d skills, system %d chars' % (len(skills), len(s)))
for k in skills:
    print(' ', k['name'], len(k['text']), 'chars')
