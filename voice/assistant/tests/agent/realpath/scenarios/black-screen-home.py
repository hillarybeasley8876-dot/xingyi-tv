# -*- coding: utf-8 -*-
"""「屏幕黑了」要回到桌面（2026-11-11：自己的测试应用是黑屏，用户被留在黑屏前）。

前置条件：电视醒着、桌面在最前面、最近 20 分钟没人动过它。不满足就返回 3 并说明为什么，一个字都不碰电视。
做什么：把 OwnApps 里那个自己的测试应用放到前面（它的屏幕是黑的），等它真的在前，然后通过 tvtask.py 说
「屏幕黑了」。
算过：说完以后桌面（orrery）是最前面的 activity。屏幕黑着的时候只有真机看得见 ResumedActivity，
库检查看不见。
收尾：把这个场景自己开起来的测试应用停掉（开始前就在跑的，不停），音量没动，桌面留在最前面。
"""
import os, sys, time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import probe as p

ID = "black-screen-home"
TASK = "屏幕黑了"


def check(r):
    """@return (ok, notes, shots)"""
    notes, shots = [], []
    app = p.own_app()
    r.started(app)
    p.sh("monkey -p %s 1 >/dev/null 2>&1" % app)
    up = p.wait_for(lambda: app in p.resumed(), 8, 0.4)
    time.sleep(1.5)
    shots.append(p.shot("black-screen-home-before.png"))
    notes.append("测试应用 %s 在前：%s（前面是 %s）" % (app, bool(up), p.resumed() or "?"))
    if not up:
        notes.append("测试应用没起来，后面那一步无从谈起")
        return False, notes, shots
    out = p.run_tool("tvtask.py", TASK, 40)["out"]
    time.sleep(1.0)
    front = p.resumed()
    shots.append(p.shot("black-screen-home-after.png"))
    reply = [l.strip() for l in out.splitlines() if l.strip().startswith("reply:")]
    notes.append("「%s」说完以后前面是：%s" % (TASK, front or "?"))
    notes.append("回答：" + (reply[-1][:120] if reply else "（没有）"))
    ok = p.HOME_PKG in front
    if not ok:
        notes.append("桌面没有回到最上面：用户还被留在黑屏前")
    return ok, notes, shots


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
