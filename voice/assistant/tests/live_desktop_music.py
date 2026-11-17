"""Exercise the paired PC's preview, cancellation and approval against the owner's TV."""
import base64
import json
import runpy
import secrets

import live_music as tv


def main():
    remote = runpy.run_path(str(tv.OUT.parent / 'assistant' / 'voice_remote.pyw'))
    send, review = remote['send_transcript'], remote['review_request']
    text = json.loads((tv.OUT / 'voice-asr.json').read_text(encoding='utf8'))['transcript']
    tv.shell('media dispatch pause')
    before = tv.player()
    rid = send(text)
    preview = tv.eventually(lambda: review(rid), lambda r: r.state == 'pending')
    assert '在网易云音乐播放林俊杰的《不为谁而作的歌》' in preview.text
    assert review(secrets.token_hex(16), 'confirm', preview.preview_b64).state == 'stale'
    changed = base64.b64encode('其它预览'.encode()).decode()
    assert review(rid, 'confirm', changed).state == 'stale'
    assert review(rid).state == 'pending' and review(rid, 'cancel').state == 'done'
    assert tv.player() == before

    rid = send(text)
    preview = tv.eventually(lambda: review(rid), lambda r: r.state == 'pending')
    assert review(rid, 'confirm', preview.preview_b64).state == 'working'
    done = tv.eventually(lambda: review(rid), lambda r: r.state == 'done')
    observed = tv.player()
    assert observed == {'state': 3, 'description': '不为谁而作的歌 (Twilight), 林俊杰, 上一首情歌 (国语精选)'}, observed
    assert '林俊杰' in done.text and '不为谁而作的歌' in done.text, done.text

    def read_trace():
        tv.debug('trace')
        return tv.shell('cat ' + tv.FILES + 'harness-trace.txt')

    trace = tv.eventually(read_trace, lambda s: 'turn_end' in s and 'play_netease_music' in s)
    assert 'after_tool · play_netease_music · observed' in trace, (done.text, trace)
    assert trace.count('tool_start · play_netease_music') == 1
    report = dict(transcript=text, preview=preview.text, approved_on='paired PC',
                  cancelled_preserved_state=True, foreign_request_and_changed_preview_rejected=True,
                  before=before, observed=observed, result_on_pc=done.text, trace=trace)
    (tv.OUT / 'desktop-review.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf8')
    print(done.text)


if __name__ == '__main__':
    main()
