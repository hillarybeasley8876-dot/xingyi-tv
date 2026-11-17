# -*- coding: utf-8 -*-
"""Cuts a raw answer of Tencent Video's episode list (GetPageData, page_id vsite_episode_list; 6-100 KB) down to what
the engine reads, in the same shape as the raw answer:

    data.module_list_datas[].module_datas[]:
        module_params: tabs (a JSON string: [{begin, end, page_context, selected}]), sub_title, is_all_data, has_next
        item_data_lists.item_datas[]: item_type, item_params {title, vid, play_title, duration, is_trailer,
                                       video_subtitle, uni_imgtag (a JSON string, only the tags that have a text)}

    python slim_tencent_episodes.py raw.json tencent-episodes-name.json
"""
import io, json, sys


def tags(raw):
    try:
        t = json.loads(raw or '{}')
    except ValueError:
        return ''
    kept = {k: {'text': v.get('text', '')} for k, v in t.items() if isinstance(v, dict) and v.get('text')}
    return json.dumps(kept, ensure_ascii=False, separators=(',', ':')) if kept else ''


def tabs(raw):
    try:
        t = json.loads(raw or '[]')
    except ValueError:
        return ''
    kept = [{'begin': x.get('begin'), 'end': x.get('end'), 'selected': bool(x.get('selected')),
             'page_context': x.get('page_context', '')} for x in t if isinstance(x, dict)]
    return json.dumps(kept, ensure_ascii=False, separators=(',', ':')) if kept else ''


def item(i):
    p = i.get('item_params') or {}
    out = {'item_type': i.get('item_type', '')}
    if i.get('item_type') == '1':
        out['item_params'] = {'title': p.get('title', ''), 'vid': p.get('vid', ''), 'play_title': p.get('play_title', ''),
                              'duration': p.get('duration', ''), 'is_trailer': p.get('is_trailer', ''),
                              'video_subtitle': p.get('video_subtitle', ''), 'uni_imgtag': tags(p.get('uni_imgtag'))}
    else:
        out['item_params'] = {'title': p.get('title', '')}
    return out


def slim(raw):
    data = raw.get('data') or {}
    lists = []
    for ml in data.get('module_list_datas') or []:
        mods = []
        for md in ml.get('module_datas') or []:
            mp = md.get('module_params') or {}
            items = ((md.get('item_data_lists') or {}).get('item_datas')) or []
            mods.append({'module_params': {'tabs': tabs(mp.get('tabs')), 'sub_title': mp.get('sub_title', ''),
                                           'is_all_data': mp.get('is_all_data', ''), 'has_next': mp.get('has_next', '')},
                         'item_data_lists': {'item_datas': [item(i) for i in items]}})
        lists.append({'module_datas': mods})
    return {'ret': raw.get('ret', 0), 'msg': raw.get('msg', ''), 'data': {'module_list_datas': lists}}


if __name__ == '__main__':
    raw = json.load(io.open(sys.argv[1], encoding='utf-8'))
    out = json.dumps(slim(raw), ensure_ascii=False, separators=(',', ':'))
    io.open(sys.argv[2], 'w', encoding='utf-8', newline='').write(out)
    print(sys.argv[2], len(out.encode('utf-8')), 'bytes')
