# -*- coding: utf-8 -*-
"""Cuts a raw answer of Tencent Video's search (0.1-1.1 MB) down to what TencentVideo.pick reads (about 15 KB), in the
shape the tencent-search-*.json fixtures have: every box and the normal list, only the items that are shows
(doc.dataType 2), and of each only doc.dataType/id and videoInfo.title/typeName/year/area/subTitle/descrip/status/
playSites (showName, enName, totalEpisode, the first episodeInfoList entry).

    python slim_tencent.py raw.json tencent-search-name.json
"""
import io, json, sys


def site(p):
    eps = p.get('episodeInfoList') or []
    return {'showName': p.get('showName', ''), 'enName': p.get('enName', ''), 'totalEpisode': p.get('totalEpisode', 0),
            'episodeInfoList': [{'id': e.get('id', ''), 'title': e.get('title', ''), 'url': e.get('url', '')} for e in eps[:1]]}


def item(i):
    doc, v = i.get('doc') or {}, i.get('videoInfo') or {}
    if doc.get('dataType') != 2:
        return None
    return {'doc': {'dataType': 2, 'id': doc.get('id', '')},
            'videoInfo': {'title': v.get('title', ''), 'typeName': v.get('typeName', ''), 'year': v.get('year', 0),
                          'area': v.get('area', ''), 'subTitle': v.get('subTitle', ''),
                          'descrip': (v.get('descrip') or '')[:64], 'status': v.get('status', 0),
                          'playSites': [site(p) for p in v.get('playSites') or []]}}


def slim(raw):
    data = raw.get('data') or {}
    boxes = []
    for b in data.get('areaBoxList') or []:
        items = [x for x in (item(i) for i in b.get('itemList') or []) if x]
        if items:
            boxes.append({'boxId': str(b.get('boxId', '')), 'itemList': items})
    normal = [x for x in (item(i) for i in (data.get('normalList') or {}).get('itemList') or []) if x]
    return {'data': {'errcode': data.get('errcode', 0), 'errmsg': data.get('errmsg', ''), 'areaBoxList': boxes,
                     'normalList': {'itemList': normal}}}


if __name__ == '__main__':
    raw = json.load(io.open(sys.argv[1], encoding='utf-8'))
    out = json.dumps(slim(raw), ensure_ascii=False, separators=(',', ':'))
    io.open(sys.argv[2], 'w', encoding='utf-8', newline='').write(out)
    print(sys.argv[2], len(out.encode('utf-8')), 'bytes')
