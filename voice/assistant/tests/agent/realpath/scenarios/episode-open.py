# -*- coding: utf-8 -*-
"""「我想看别无选择第5集」在真机上要开到第5集（2026-11-11：深链对真实播放开没开对集数，只有真机看得见）。

前置条件：电视醒着、桌面在最前面、最近 20 分钟没人动过它。不满足就返回 3 并说明为什么，一个字都不碰电视。
做什么：通过 tvtask.py（静音来源）说这句话，等播放页起来，然后 probe.wait_for_show() 等广告放完——它静等 100 秒
一个键都不按，之后每 15 秒暂停一次、截一屏、读像素、继续放，正片前有一两分钟广告（session7 等了 124 秒）。
拿到正片以后看那一帧左上角有没有「别无选择 第5集」那一行字，必要时再按原来的方式看一两次（每次之间 15 秒）。
算过：画面左上角那一行字和「别无选择 第5集」对得上（screen.has_show_text()，模板就是这一行字）。
正片在放（进度条读得出比例），可看了 3 次那行字都对不上，是开错了集，算不过；广告一直没放完是这次验不了
（返回 3，不写证据文件——没有验证，就没有记录）。
读的是像素不是字：播放页整页是 SurfaceView 画的，`uiautomator dump` 在上面只有一个空节点（2026-11-14 11:10
量了 15 次，全是 nodes 1 / texts 0），原来这里读 `ui_text()` 找「第5集」，从写出来那天就永远读不到。
收尾：广告声音压到 3，这个场景自己开起来的云视听极光停掉（开始前就在跑的，不停），音量放回原样，
桌面留在最前面。
"""
import os, sys, time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import probe as p
import screen

ID = "episode-open"
TASK = "我想看别无选择第5集"
CAP_S = 240                      # 跟着 probe.wait_for_show 的 cap_s 走：广告放不完就承认验不了，不再各算各的
LOOKS, GAP_S = 2, 15             # 拿到正片以后再按原来的方式看几次画面（每次之间 15 秒）


def look(notes, shots, i):
    """按原来的方式再看一次画面：暂停、截一屏、继续放。@return (截图路径, 左上角那行字在不在)"""
    p.play_pause("paused")
    time.sleep(1.2)
    png = p.frame("episode-open-%d" % i)
    shots.append(png)
    p.play_pause("started")
    titled = screen.has_show_text(png)
    notes.append("第 %d 次看画面：左上角「别无选择 第5集」那行字%s" % (i, "在" if titled else "不在"))
    return png, titled


def check(r):
    """@return (ok, notes, shots)；ok 是 None 表示这次验不了（广告还没放完）。"""
    notes, shots = [], []
    t0 = time.time()
    r.started(p.KTCP)
    p.set_volume(3)
    out = p.run_tool("tvtask.py", TASK, 40)["out"]
    up = p.wait_for(lambda: p.PLAYER in p.resumed(), 20, 0.4)
    notes.append("「%s」说完 %.0f 秒，播放页在前：%s" % (TASK, time.time() - t0, bool(up)))
    if not up:
        notes.append("播放页没起来：这句话没把第5集打开（前面是 %s）" % (p.resumed() or "?"))
        reply = [l.strip() for l in out.splitlines() if l.strip().startswith(("reply:", "error:"))]
        notes.append("任务说了什么：" + (" | ".join(reply[-2:])[:180] or "（没有）"))
        return False, notes, shots
    started = p.wait_for(lambda: p.audio_state() == "started", 30, 1.0)
    notes.append("音频服务说这个应用：%s" % (p.audio_state() or "none"))
    if not started:
        notes.append("一直没开始放（广告也没声），没法看画面")
        return False, notes, shots
    show = p.wait_for_show(notes, cap_s=CAP_S)         # 等广告放完：广告里读到的画面不算数
    if show is None:
        return None, notes, shots
    shots.append(show["frame"])
    pos = show["position"]
    notes.append("正片在放（%.0f 秒）：进度条比例 %s；左上角那行字%s"
                 % (show["at"],
                    ("%.4f（约 %d 秒）" % (pos["ratio"], int(round(pos["s"])))) if pos else "读不到",
                    "在" if show["titled"] else "不在"))
    titled = show["titled"]
    for i in range(LOOKS + 1):
        if titled:
            notes.append("画面左上角写着「别无选择 第5集」：开对了")
            return True, notes, shots
        if i < LOOKS:
            notes.append("画面上还没出现那一行字，等 %d 秒再看一次" % GAP_S)
            time.sleep(GAP_S)
            _, titled = look(notes, shots, i + 1)
    notes.append("正片在放（进度条读得出比例），可 %d 次看画面，左上角都不是「别无选择 第5集」那一行："
                 "开错了集，或者那一块被别的画面盖住了（照片在 shots 里）" % (LOOKS + 1))
    return False, notes, shots


def main():
    why = p.refuse()
    if why:
        p.say(ID, "not run:", why)
        return 3
    r = p.Restore()
    notes = []
    try:
        try:
            ok, notes, shots = check(r)
        except p.CaptureFailed as e:
            # 截图失败不是判据失败：这些照片/截图一条条都是判据的输入，截不到就说不上「开没开起来」。
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
