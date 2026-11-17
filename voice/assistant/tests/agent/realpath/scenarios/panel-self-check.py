# -*- coding: utf-8 -*-
"""self_check 在面板这个意义上（2026-11-11 01:58 面板坏了四次、自检四次说正常）。

前置条件：电视醒着、桌面在最前面、最近 20 分钟没人动过它，而且读屏服务开着（面板是这个服务的窗口）。
不满足就返回 3 并说明为什么，一个字都不碰电视。
做什么：问「为什么现在HARNESS面板没有打开」，任务跑的时候盯 `dumpsys window windows` 里的面板窗口。
算过：面板窗口真的出现过，而且 self_check 的回答里说到面板（Repairs 的那句话）。回答里没有面板，
就是自检在看别的地方；窗口没出现，就是面板真的没起来——两样只有真机能看见。
收尾：音量没动，没开别的应用，桌面留在最前面。
"""
import os, sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import probe as p

ID = "panel-self-check"
TASK = "为什么现在HARNESS面板没有打开"


def eyes_on():
    """读屏服务（面板的宿主）是不是开着 —— enabled_accessibility_services 是 secure 设置。"""
    return "xyz.erapi0neer" in p.setting("enabled_accessibility_services")


def check(r):
    """@return (ok, notes, shots)"""
    notes, shots = [], []
    proc = p.start_tool("tvtask.py", TASK, 60)
    win = p.wait_for(lambda: p.hud() or None, 45, 0.5) or []
    shots.append(p.shot("panel-self-check.png"))
    out = p.finish(proc, 60)
    said = " ".join(l.strip() for l in out.splitlines() if l.strip().startswith(("reply:", "- ")))
    notes.append("任务跑的时候，面板窗口出现 %d 个：%s" % (len(win), " / ".join(win)[:180] or "（一个都没有）"))
    notes.append("self_check 说了什么：" + (said[-240:] or "（没有回答）"))
    ok = bool(win) and "面板" in said
    if not win:
        notes.append("面板的窗口一次都没出现：自检说正常也没用，屏上没有东西")
    elif "面板" not in said:
        notes.append("回答里没有「面板」两个字：自检没在看面板")
    return ok, notes, shots


def main():
    why = p.refuse()
    if why:
        p.say(ID, "not run:", why)
        return 3
    if not eyes_on():
        p.say(ID, "not run: 读屏服务没开（enabled_accessibility_services 里没有星仪），面板起不来，验不了")
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
