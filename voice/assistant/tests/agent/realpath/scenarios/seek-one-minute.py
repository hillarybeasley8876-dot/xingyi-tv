# -*- coding: utf-8 -*-
"""「快进一分钟」要真的快进一分钟（2026-11-12 03:20：这条只有画面上的进度说得清，任务说「已快进」不算）。

前置条件：电视醒着、桌面在最前面、最近 20 分钟没人动过它。不满足就返回 3 并说明为什么，一个字都不碰电视。
做什么：照 episode-open 的老办法把云视听极光开起来（tvtask.py 说「我想看别无选择第5集」，静音来源），等播放页
和声音都起来；先 probe.wait_for_show() 等广告放完（广告和正片在音频服务里都叫 started，只有画面上的进度说得
清），再暂停一次截一屏、读进度条上的比例、继续放；然后通过 tvtask.py 说「快进一分钟」；再暂停一次截一屏、
读比例、继续放。
算过：任务自己的回答里有「已快进」，而且两次读出来的秒数差了 45 到 90 秒。读两次之间片子还在走（那几秒也写进
notes），所以不是刚好 60；两次暂停之间走了多久、扣掉以后跳了大约多少秒，也一并写下来，判据收紧时用得上。
画面上读不到进度条（广告卡片挡住、或者还没进正片），算不过并写清看到了什么——读不到就不猜。广告一直没放完
是另一回事：这次验不了（返回 3，不写证据文件）——在广告里快进验的不是正片。
收尾：这个场景自己开起来的云视听极光停掉（开始前就在跑的，不停），广告声音压到 3、收尾放回原样，
桌面留在最前面。
"""
import os, sys, time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import probe as p
import screen

ID = "seek-one-minute"
OPEN, SEEK = "我想看别无选择第5集", "快进一分钟"
MIN_JUMP, MAX_JUMP = 45, 90              # 「一分钟」放宽到的范围：两次读之间片子自己也在走


def overlay(notes, shots, tag):
    """暂停、截一屏、读进度条上的位置、继续放。@return (秒数或 None, 按下暂停的时刻, 继续放的时刻)

    读的是像素不是字：播放页整页是 SurfaceView 画的，`uiautomator dump` 在上面只有一个空节点
    （2026-11-14 11:10 量了 15 次，全是 nodes 1 / texts 0），浮层上的 00:MM:SS 一个都读不出来。
    进度条没有这个问题——它是一条纯色的线，比例读到亚像素，2833 秒铺在 675 px 上一分钟就是 14.2 px。
    """
    paused = time.time()
    p.play_pause("paused")
    time.sleep(1.2)
    png = p.frame("seek-one-minute-%s" % tag)
    shots.append(png)
    back = time.time()
    p.play_pause("started")
    pos = screen.position(png, p.EPISODE_S)
    if pos is None or pos["s"] is None:
        notes.append("%s：画面上读不到进度条（广告卡片挡住，或者还没进正片），读不到就不猜" % tag)
        return None, paused, back
    b = pos["bar"]
    notes.append("%s：进度条 x %d..%d（y=%d），填充到 %d，比例 %.4f -> %.1f 秒"
                 % (tag, b["x0"], b["x2"], b["y"], b["x1"], pos["ratio"], pos["s"]))
    return int(round(pos["s"])), paused, back


def check(r):
    """@return (ok, notes, shots)；ok 是 None 表示这次验不了（广告一直没放完）。"""
    notes, shots = [], []
    r.started(p.KTCP)
    p.set_volume(3)                                     # 广告很响；Restore 会放回去
    p.run_tool("tvtask.py", OPEN, 40)
    up = p.wait_for(lambda: p.PLAYER in p.resumed(), 20, 0.4)
    notes.append("「%s」说完，播放页在前：%s（前面是 %s）" % (OPEN, bool(up), p.resumed() or "?"))
    if not up:
        notes.append("播放页没起来，无从快进")
        return False, notes, shots
    playing = p.wait_for(lambda: p.audio_state() == "started", 30, 1.0)
    notes.append("音频服务说这个应用：%s" % (p.audio_state() or "none"))
    if not playing:
        notes.append("一直没开始放（广告也没声），画面上不会有进度")
        return False, notes, shots
    if p.wait_for_show(notes) is None:                  # 广告没放完：在广告里快进验的不是正片
        return None, notes, shots
    before, _, resumed_at = overlay(notes, shots, "before")
    if before is None:
        notes.append("正片在放，可这一次画面上读不到进度：算不过")
        return False, notes, shots
    out = p.run_tool("tvtask.py", SEEK, 40)["out"]
    reply = [l.strip() for l in out.splitlines() if l.strip().startswith(("reply:", "error:"))]
    notes.append("「%s」的回答：%s" % (SEEK, " | ".join(reply[-2:])[:200] or "（没有）"))
    after, paused_at, _ = overlay(notes, shots, "after")
    if after is None:
        notes.append("快进以后的画面读不到进度：算不过（前面的进度是 %d 秒）" % before)
        return False, notes, shots
    delta = after - before
    walked = paused_at - resumed_at
    # 判的是「快进跳了多少」，不是「两次读数差多少」：两次暂停之间片子自己一直在走，那一段不该算成快进。
    # 11-14 那一跑两次读之间走了 12 秒，差 67 秒里有 12 秒是自己走的；按 delta 判，一个什么都没做的
    # 「快进」只要等够 45 秒也能过。窗口还是 45..90（「一分钟」放宽）。
    jumped = delta - walked
    notes.append("进度 %d 秒 -> %d 秒，差 %d 秒；两次暂停之间片子自己走了 %.0f 秒（扣掉以后跳了大约 %.0f 秒）"
                 % (before, after, delta, walked, jumped))
    said = "已快进" in out
    notes.append("任务说「已快进」：%s" % said)
    ok = said and MIN_JUMP <= jumped <= MAX_JUMP
    if not ok:
        notes.append("算不过：要么回答里没有「已快进」，要么扣掉自己走的那段以后差得不是一分钟（判据 %d–%d 秒）"
                     % (MIN_JUMP, MAX_JUMP))
    return ok, notes, shots


def main():
    why = p.refuse()
    if why:
        p.say(ID, "not run:", why)
        return 3
    r = p.Restore(apps=[p.KTCP])
    notes = []
    try:
        try:
            ok, notes, shots = check(r)
        except p.CaptureFailed as e:
            # 一屏没截成不是判据失败：快进跳了几秒只能从画面上的进度条读，截不到就没有判据可用。
            # 记一笔「试过」、一句为什么，返回 3（这次验不了），不写证据文件。
            p.attempt(ID, notes + [str(e)])
            p.say(ID, "could not run:", "截不到图，这次验不了：%s" % e)
            return 3
    finally:
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
