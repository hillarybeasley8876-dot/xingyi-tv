# -*- coding: utf-8 -*-
"""面板在说话时逐字上屏（2026-11-11 改的东西，只有真机能看见）。

前置条件：电视醒着、桌面在最前面、最近 20 分钟没人动过它。不满足就返回 3 并说明为什么，一个字都不碰电视。
做什么：起一个静音任务（source=test），3 秒时拍一张照片并读 `dumpsys window windows` 里的窗口表。
算过：任务还在跑的时候，窗口表里有星仪自己的面板窗口（Hud 加的是 TYPE_ACCESSIBILITY_OVERLAY 的 view，
只有它是 xyz.erapi0neer.xiaoaibridge 的窗口）。窗口不在，屏上就没有那句话——静音来源验不了这个。
收尾：应用没开别的，音量没动，桌面留在最前面。
"""
import os, sys, time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import probe as p

ID = "panel-while-speaking"
TASK = "看一下电视现在开了多久"


def check(r):
    """@return (ok, notes, shots)"""
    notes, shots = [], []
    t0 = time.time()
    proc = p.start_tool("tvtask.py", TASK, 40)
    time.sleep(3.0)
    running = proc.poll() is None
    win = p.hud()
    shots.append(p.shot("panel-while-speaking-3s.png"))
    text = p.ui_text()
    out = p.finish(proc, 60)
    notes.append("静音任务「%s」起来 %.1f 秒时还在跑：%s" % (TASK, time.time() - t0, running))
    notes.append("窗口表里星仪的面板窗口 %d 个：%s" % (len(win), " / ".join(win)[:180] or "（一个都没有）"))
    notes.append("屏上的字（给人看的，判的是窗口）：" + (text[:160] or "（读不到）"))
    reply = [l.strip() for l in out.splitlines() if l.strip().startswith("reply:")]
    notes.append("任务的回答：" + (reply[-1][:120] if reply else "（没有）"))
    if not running:
        notes.append("3 秒时任务已经结束了，这一张照片证不了「说话时」面板在——不算过")
    return bool(win) and running, notes, shots


def main():
    why = p.refuse()
    if why:
        p.say(ID, "not run:", why)
        return 3
    r = p.Restore()
    try:
        ok, notes, shots = check(r)
    finally:
        r.close()
    p.evidence(ID, ok, notes, shots)
    p.say(ID, "PASSED" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
