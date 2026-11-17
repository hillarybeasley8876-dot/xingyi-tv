"""Builds assets/agent/store_apps.json: the Xiaomi TV app store's whole catalog (category 25 「全部应用」, 118 apps,
pulled 2026-11-05 with appstore/store_client.py) with the names people actually say. install_app installs only what is
in here, so a package name is never guessed.

python tests/agent/store_catalog.py <store_all.json>   (python ../../appstore/store_client.py --json category 25 --all)
"""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', '..', 'assets', 'agent', 'store_apps.json')
# What people call the TV builds (the store lists them under their licence names).
ALIASES = {
    'com.gitvdemo.video': ['爱奇艺', '奇异果', '爱奇艺TV', '爱奇艺电视版'],
    'com.ktcp.video': ['腾讯视频', '极光', '腾讯视频TV', '云视听极光TV'],
    'com.xiaodianshi.tv.yst': ['B站电视版', '小电视', '哔哩哔哩电视版', '哔哩哔哩TV'],
    'com.hunantv.license': ['芒果', '芒果TV', '芒果tv'],
    'com.kwai.tv.yst': ['快手', '快手TV'],
    'com.sohuott.tv.vod': ['搜狐视频', '搜狐'],
    'cn.miguvideo.migutv': ['咪咕视频', '咪咕'],
    'com.newtv.cboxtv': ['央视频', 'CCTV', '央视'],
    'com.tencent.qqmusictv': ['QQ音乐', 'qq音乐'],
    'com.kugou.android.tv': ['酷狗', '酷狗音乐'],
    'com.tencent.karaoketv': ['全民K歌', '全民k歌'],
    'com.fmxos.app.smarttv': ['喜马拉雅', '喜马拉雅FM'],
    'com.hpplay.happyplay.aw': ['乐播', '乐播投屏'],
    'com.quark.yun.tv': ['夸克', '夸克网盘'],
    'com.xunlei.downloadprovider.hd': ['迅雷', '迅雷云盘'],
    'rca.rc.tvtaobao': ['淘宝', '电视淘宝'],
    'com.letv.tv': ['乐视', '乐视视频'],
    'cn.com.wasu.main': ['华数'],
    'com.vcinema.client.tv': ['南瓜电影'],
    'com.bestv.ott.baseservices': ['百视通'],
    'com.tencent.start.tv': ['START', '腾讯START', '腾讯云游戏'],
    'com.dangbei.dbmusic': ['当贝音乐'],
    'com.changba.sd': ['唱吧'],
    'cn.jj.tv': ['JJ斗地主'],
    'com.netease.cloudmusic.tv': ['网易云', '网易云音乐'],
}
# Never installed by 星仪: the PatchWall home screen and the shopping app were removed on purpose (ads), see restore.sh.
EXCLUDED = {'com.mitv.tvhome', 'com.xiaomi.mitv.shop'}


def main():
    src = json.load(open(sys.argv[1], encoding='utf-8'))
    apps = src['apps'] if isinstance(src, dict) else src
    out = []
    for a in apps:
        pkg = a['package']
        if pkg in EXCLUDED:
            continue
        out.append({'package': pkg, 'name': a['name'], 'aliases': ALIASES.get(pkg, [])})
    missing = set(ALIASES) - {a['package'] for a in out}
    assert not missing, 'aliases for packages not in the store: %s' % missing
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write(json.dumps({'source': '小米电视应用商店「全部应用」，2026-11-05', 'apps': out}, ensure_ascii=False, indent=1) + '\n')
    print('store_apps.json: %d apps (%d excluded)' % (len(out), len(apps) - len(out)))


if __name__ == '__main__':
    main()
