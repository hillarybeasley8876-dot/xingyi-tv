"""Reference model of MusicIntent.parse + MusicResolver scoring (lead-owned oracle for the Java worker jobs).

python music_ref.py "<utterance>" ...   prints the parsed intent, the search queries and the pick (fixtures only).
"""
import json, re, sys, unicodedata
from pathlib import Path

FILLERS = ['小爱同学', '小爱', '星仪', '你好', '请你', '麻烦你', '麻烦', '帮我', '给我', '帮忙', '我想要', '我要', '我想',
           '想要', '我们', '能不能', '可不可以', '可以', '请', '嘿', '喂', '哎', '能']
NETEASE = re.compile(r'(用|在|打开|去|到|上|从)?(网易云音乐|网易云|网抑云)(app|tv)?(里面|里边|里|上面|上|中|的)?')
CONNECTORS = ['然后', '再', '并且', '并', '接着', '就', '帮我', '给我']
VERBS = sorted(['播放一首', '播放', '播一首', '播首', '播', '放一首', '放首', '放一个', '放个', '放点', '放些', '放',
                '来一首', '来首', '来一个', '来个', '来点', '来些', '听一首', '听首', '听听', '听一下', '听',
                '点一首', '点首', '点播', '唱一首', '唱首', '唱', '搜一下', '搜索', '搜', '找一下', '找一首', '找'],
               key=len, reverse=True)
QUANT = ['一首', '一个', '一下', '一些', '首', '个', '些', '点']
PARTICLES = ['好不好', '好吗', '可以吗', '谢谢', '吧', '啊', '呀', '哦', '呢', '嘛']
PAUSE = {'暂停', '暂停播放', '暂停一下', '停止', '停止播放', '停', '停一下', '别放了', '不听了', '关掉音乐', '关闭音乐'}
RESUME = {'继续', '继续播放', '接着放', '接着播', '接着听', '恢复播放', '播放'}
NEXT = {'下一首', '下一曲', '下首', '切歌', '换一首', '换首歌', '换一首歌', '换歌'}
PREV = {'上一首', '上一曲', '上首'}
FAV = re.compile(r'^(我|我的)?(喜欢|收藏|红心|最爱)的?(音乐|歌曲|歌单|歌|列表)?$')
FM = {'私人fm', '私人电台', '私人漫游', '私人雷达', '心动模式', '随便听听', '随便放放', '随便', '随机', '随机播放',
      '音乐', '歌', '歌曲', '好听的歌', '好听的', '好听的音乐', '随便放点歌', '随便来点歌', '随便来点', '随便放点'}
DAILY = {'每日推荐', '日推', '今日推荐', '今天的推荐', '每日推荐歌单', '每日歌曲推荐', '推荐歌曲', '推荐的歌'}
EXCLUDE = re.compile(r'音量|声音|亮度|屏幕|频道|电视剧|电影|视频|综艺|动画片|动漫|纪录片|节目|直播|mv|hdmi|信号源|设置|桌面|应用'
                     r'|第[0-9一二三四五六七八九十百]+集')
ARTIST = re.compile(r'^(.{1,20}?)唱?的(歌|歌曲|音乐|热门歌曲|热门歌|热歌|新歌|好歌|老歌|经典歌曲|经典老歌|代表作|成名曲|所有歌|全部歌曲|专辑)$')
VERSION = re.compile(r'live|现场|演唱会|remix|混音|伴奏|纯音乐|钢琴|吉他|cover|翻唱|翻自|原唱|dj|女声|男声|童声|加速|慢速|降调|升调'
                     r'|\d(\.\d+)?x|片段|demo|小样|acoustic|不插电|instrumental|karaoke|串烧|改编', re.I)
STRONG = {'播放', '播放一首', '播一首', '播首', '放一首', '放首', '来一首', '来首', '来点', '来些', '听一首', '听首', '点一首', '点首', '点播',
          '唱一首', '唱首'}
PRONOUNS = {'我们', '你们', '他们', '她们', '自己', '大家', '咱们'}


def strip_prefix(s, words):
    changed = True
    while changed:
        changed = False
        for w in words:
            if s.startswith(w) and len(s) > len(w):
                s = s[len(w):].lstrip()
                changed = True
                break
    return s


def strip_suffix(s, words):
    changed = True
    while changed:
        changed = False
        for w in words:
            if s.endswith(w) and len(s) > len(w):
                s = s[:-len(w)].rstrip()
                changed = True
                break
    return s


def intent(kind, netease, content='', splits=None, wants=False):
    return dict(kind=kind, netease=netease, content=content, artist='', splits=splits or [], wantsVersion=wants,
                explicitPlay=netease)


def parse(utt):
    if utt is None:
        return None
    s = unicodedata.normalize('NFKC', utt).strip()
    m = re.search(r'《([^》]+)》', s)
    title_hint = m.group(1).strip().lower() if m else None
    s = re.sub('[《》「」『』“”"‘’\']', '', s)
    s = re.sub('[，。！？、；：,!?;:…~～]+', ' ', s)
    s = re.sub(r'\s+', ' ', s).strip().lower()
    before = s
    s = strip_prefix(s, FILLERS)
    stripped = before[:len(before) - len(s)]
    wanted = any(w in stripped for w in ('我想', '我要', '想要', '帮我', '给我'))
    netease = False
    m = NETEASE.search(s)
    if m:
        netease = True
        s = re.sub(r'\s+', ' ', (s[:m.start()] + ' ' + s[m.end():])).strip()
        s = strip_prefix(s, CONNECTORS + FILLERS)
    s = strip_suffix(s, PARTICLES).strip()
    whole = s.replace(' ', '')
    if netease and whole in ('', '打开', '启动', '进入', '开'):
        return intent('OPEN_APP', True)
    for kind, words in (('PAUSE', PAUSE), ('RESUME', RESUME), ('NEXT', NEXT), ('PREVIOUS', PREV)):
        if whole in words:
            return intent(kind, netease)
    verb = next((v for v in VERBS if s.startswith(v)), None)
    if verb is None and not netease:
        return None
    explicit_play = netease or verb in STRONG or (verb is not None and wanted)
    c = s[len(verb):].strip() if verb else s
    for q in QUANT:
        if c.startswith(q) and len(c) > len(q):
            c = c[len(q):].strip()
            break
    c = strip_suffix(c, PARTICLES).strip()
    if not c:
        return intent('RESUME', True) if netease else None
    if not netease and (c[0] in '大小高低' or EXCLUDE.search(c)):
        return None
    cw = c.replace(' ', '')
    for kind, hit in (('FAVORITES', bool(FAV.match(cw) and re.search('喜欢|收藏|红心|最爱', cw))), ('PRIVATE_FM', cw in FM),
                      ('DAILY', cw in DAILY)):
        if hit:
            out = intent(kind, netease)
            out['explicitPlay'] = explicit_play
            return out
    m = ARTIST.match(c)
    artist = m.group(1).strip() if m else ''
    if '的' in artist or artist in PRONOUNS | {'我', '你', '他', '她'}:
        artist = ''
    splits = []
    if title_hint and title_hint in c:
        i = c.find(title_hint)
        a = re.sub('唱?的$', '', c[:i]).strip()
        splits.append([a if 2 <= len(a) <= 15 and a not in PRONOUNS else '', title_hint])
    else:
        for i, ch in enumerate(c):
            if ch == '的' and len(splits) < 3:
                a, t = c[:i].strip(), c[i + 1:].strip()
                if a.endswith('唱'):
                    a = a[:-1].strip()
                if 2 <= len(a) <= 15 and t and a not in PRONOUNS:
                    splits.append([a, t])
        m = re.match(r'^(.+?) by (.+)$', c)
        if m:
            splits.append([m.group(2).strip(), m.group(1).strip()])
    if not splits or splits[-1] != ['', c]:
        splits.append(['', c])
    out = intent('ARTIST' if artist else 'SONG', netease, c, splits, bool(VERSION.search(c)))
    out['artist'] = artist
    out['explicitPlay'] = explicit_play
    return out


# ---------------- resolver ----------------
def norm(s):
    s = unicodedata.normalize('NFKC', s or '').lower()
    return ''.join(ch for ch in s if not ch.isspace() and not unicodedata.category(ch).startswith(('P', 'S')))


def base_title(t):
    t = unicodedata.normalize('NFKC', t or '').strip()
    prev = None
    while prev != t:
        prev = t
        t = re.sub(r'\s*[(\[【][^()\[\]【】]*[)\]】]\s*$', '', t).strip()
        t = re.sub(r'\s+-\s+.*$', '', t).strip()
    return t or prev


ALBUM_VERSION = re.compile(r'live|concert|演唱会|現場|现场|第[零〇一二三四五六七八九十百0-9]+期|remix|混音|伴奏|纯音乐|翻唱|cover|翻自|(?<![a-z])dj(?![a-z])', re.I)
COMPILATION = re.compile(r'精选|精選|合辑|合輯|合集|金曲|greatest hits|compilation|best of', re.I)


def title_tagged(name):
    full = unicodedata.normalize('NFKC', name or '').strip()
    base = base_title(full)
    if base == full:
        return bool(re.search('伴奏|纯音乐|铃声', full))
    suffix = full[len(base):]
    return bool(VERSION.search(suffix) or '版' in suffix)


def lev(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def similarity(a, b):
    if not a or not b:
        return 0.0
    return 1 - lev(a, b) / max(len(a), len(b))


def score_song(song, rank, it):
    name = song.get('name') or ''
    album = (song.get('album') or {}).get('name') or ''
    title_n, full_n = norm(base_title(name)), norm(name)
    aliases = {norm(x) for x in (song.get('alias') or []) + (song.get('transNames') or []) if x}
    credits = [norm(a.get('name') or '') for a in song.get('artists') or []]
    content_n = norm(it['content'])
    tagged = title_tagged(name) or bool(ALBUM_VERSION.search(album))
    best = None
    for a, t in it['splits']:
        t_n, a_n = norm(t), norm(a)
        if t_n and (t_n == title_n or t_n == full_n or t_n in aliases):
            tm = 3
        elif len(title_n) >= 2 and title_n in content_n:
            tm = 2
        elif similarity(t_n, title_n) >= 0.8:
            tm = 1
        else:
            continue
        if not a_n:
            am = 1 if any(len(c) >= 2 and c in content_n for c in credits) else 0
        else:
            am = 2 if any(c == a_n or a_n in c for c in credits) else -1
        adj = (30 if it['wantsVersion'] else -150) if tagged else 0
        sc = tm * 100 + am * 40 + adj
        conf = ((tm == 3 and am >= 0) or (tm == 2 and am >= 1) or (tm == 1 and am == 2)) and not (tagged and not it['wantsVersion'])
        if best is None or sc > best[0]:
            best = (sc, conf)
    if best is None:
        return None
    sc = best[0] + max(0, 20 - rank) - (15 if COMPILATION.search(album) else 0) - (10 if song.get('fee') == 4 else 0)
    return dict(id=song['id'], title=name, artist='/'.join(a.get('name') or '' for a in song.get('artists') or []),
                album=album, score=sc, confident=best[1])


def queries(it):
    qs = []
    first = next((s for s in it['splits'] if s[0]), None)
    if first:
        qs.append(first[0] + ' ' + first[1])
    if it['content'] not in qs:
        qs.append(it['content'])
    if first and first[0] not in qs:
        qs.append(first[0])
    return qs[:3]


def resolve(it, search):
    best, asked = None, []
    for q in queries(it):
        asked.append(q)
        resp = search(q)
        for r, song in enumerate((resp.get('result') or {}).get('songs') or []):
            p = score_song(song, r, it)
            if p and p['score'] > 0 and (best is None or (p['confident'], p['score']) > (best['confident'], best['score'])):
                best = p
        if best and best['confident']:
            break
    return best, asked


if __name__ == '__main__':
    fx = json.loads((Path(__file__).parent.parent / 'fixtures' / 'netease_search.json').read_text(encoding='utf-8'))

    def search(q):
        return fx['songs'].get(q, {'code': 200, 'result': {'songCount': 0, 'songs': []}})

    for u in sys.argv[1:]:
        it = parse(u)
        print(u, '=>', it)
        if it and it['kind'] == 'SONG':
            pick, asked = resolve(it, search)
            print('   asked', asked)
            print('   pick', pick)
