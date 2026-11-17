# -*- coding: utf-8 -*-
"""对真实播放说「暂停」（2026-11-11 20:03：小爱的语音助手暂停了，星仪的键 164 ms 后又把它放回去，没人发现）。

前置条件：电视醒着、桌面在最前面、最近 20 分钟没人动过它。不满足就返回 3 并说明为什么，一个字都不碰电视。
做什么：这一集自己开——tvtask.py 说「我想看别无选择第5集」（静音来源，不占小爱的那条路），等播放页到最前面、
等音频服务说这个应用在放，然后 probe.wait_for_show() 先等广告放完（广告和正片在音频服务里都叫 started，只有
画面上的进度说得清——播放页整页是 SurfaceView 画的，浮层上的字一个都读不到；11-12 03:16 那次手写探针干等了 124 秒才动手），再通过 xiaoai_say.py 说「暂停」——真实路径，
这句话真的说给小爱。（2026-11-14 以前这条不自己开，要「先跑 episode-open」；可 episode-open 收尾把云视听极光停了，
账本里排在它前面的每一条结束时播放页都不在前面，于是这条永远等不到——现在自己开，不再等谁。）
算过：说「暂停」之前音频服务说 started（正片在放以后读的，不是脚本开头读的），说完 3 秒以后同一个播放器说 paused。
开播没起来也是一次真跑：notes 写清是播放页没到最前面、还是音频服务一直没开始，算不过——这和「现在不能跑」不是
一回事，那种情况一个字节都不碰电视。广告一直没放完是第三种：这次验不了（返回 3，不写证据文件）——在广告里说
「暂停」验的不是正片，一份指错原因的失败报告比没有还糟。静音来源对有副作用的工具是拒执的，验不了暂停这件事；
只有真机上的音频服务说得清。
收尾：把这一集放回去（暂停是我们按的），自己开起来的云视听极光停掉，说给小爱的这句话用 FORGET_CHECKS 从星仪的
历史里拿掉，广告声音压到 3、收尾放回原样，桌面留在最前面。
"""
import os, sys, time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import probe as p

ID = "pause-through-xiaoai"
OPEN, TASK = "我想看别无选择第5集", "暂停"
PLAYER_S, AUDIO_S = 25, 40      # 播放页到最前面 / 音频服务开始放：广告也叫「在放」，广告放没放完由 wait_for_show 等


def said(out, n=2):
    """任务自己说了什么：最后 n 行 reply:/error:，没有就是「（没有）」。"""
    lines = [l.strip() for l in (out or "").splitlines() if l.strip().startswith(("reply:", "error:"))]
    return " | ".join(lines[-n:])[:200] or "（没有）"


def check(r):
    """@return (ok, notes, shots)；ok 是 None 表示这次验不了（广告一直没放完）。"""
    notes, shots = [], []
    r.started(p.KTCP)                                   # 开播之前：这个应用是这一跑自己开起来的，收尾时停掉
    p.set_volume(3)                                     # 广告很响；Restore 会放回去（episode-open 一直这么做）
    open_out = p.run_tool("tvtask.py", OPEN, 40)["out"]
    notes.append("「%s」的回答：%s" % (OPEN, said(open_out)))
    up = p.wait_for(lambda: p.PLAYER in p.resumed(), PLAYER_S, 0.4)
    notes.append("「%s」说完，播放页在前：%s（前面是 %s）" % (OPEN, bool(up), p.resumed() or "?"))
    if not up:
        notes.append("开播没起来：%d 秒里播放页一直没到最前面，无从说「%s」（这次是真跑了、没跑成）"
                     % (PLAYER_S, TASK))
        return False, notes, shots
    playing = p.wait_for(lambda: p.audio_state() == "started", AUDIO_S, 1.0)
    if not playing:
        notes.append("开播没起来：播放页在前，可 %d 秒里音频服务一直没开始放（广告也没声），"
                     "无从等广告放完（这次是真跑了、没跑成）" % AUDIO_S)
        return False, notes, shots
    if p.wait_for_show(notes) is None:                  # 广告没放完：在广告里说「暂停」验的不是正片
        return None, notes, shots
    before = p.audio_state()
    notes.append("正片在放、说「%s」之前，音频服务说：%s" % (TASK, before or "none"))
    since = p.tv_clock()[:8]                            # 只要说「暂停」前后的事件，开播那一分钟的不算
    out = p.run_tool("xiaoai_say.py", TASK, 30)["out"]
    time.sleep(3.0)
    after = p.audio_state()
    shots.append(p.shot("pause-through-xiaoai.png"))
    notes.append("说完 3 秒以后，音频服务说：%s" % (after or "none"))
    reply = [l.strip() for l in out.splitlines() if l.strip().startswith(("reply:", "- "))]
    notes.append("任务做了什么：" + (" | ".join(reply[-3:])[:200] or "（没有）"))
    for e in p.audio_events(since)[-10:]:
        notes.append("  音频服务的事件：" + e)
    ok = before == "started" and after == "paused"
    if not ok:
        # 两条都得先排除「验不了」：
        #   · 说「暂停」之前压根没在放，还能是因为 wait_for_show 结束以后恢复键按不下去（它会把这一屏标成
        #     「算不了」、自己回 None；可调用方在这一版之前只看 None，看不出这两种）。那种情况下「说不清
        #     暂停成没成」是这次没验，不是「小爱和星仪互相抵消」——别拿一份指错原因的失败报告去凑数。
        #   · 别的才是真跑了、没通过：before 在放、说完 3 秒却不是 paused。
        if before != "started" and any("恢复键没按下去" in n for n in notes):
            notes.append("恢复键没按下去，说「暂停」之前那一刻片子是不是在放说不清：这次验不了，不算不过")
            return None, notes, shots
        notes.append("没有停在 paused：小爱的语音助手和星仪的键可能又互相抵消了（看上面事件行的时间）")
    return ok, notes, shots


def main():
    why = p.refuse()
    if why:
        p.say(ID, "not run:", why)
        return 3
    r = p.Restore([p.KTCP])
    notes = []
    try:
        try:
            ok, notes, shots = check(r)
        except p.CaptureFailed as e:
            # 一屏没截成不是判据失败：电视卡住、pull 拉回来半张，这些说明不了小爱的暂停对不对。
            # 记一笔「试过」、一句为什么，返回 3（这次验不了），不写证据文件。
            p.attempt(ID, notes + [str(e)])
            p.say(ID, "could not run:", "截不到图，这次验不了：%s" % e)
            return 3
    finally:
        p.say("put the episode back:", p.play_pause("started"))
        r.close()
    if ok is None:
        p.attempt(ID, notes)
        p.say(ID, "could not run:", notes[-1] if notes else "?")
        return 3
    p.evidence(ID, ok, notes, shots)
    p.say(ID, "PASSED" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
