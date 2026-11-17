# -*- coding: utf-8 -*-
"""提示卡不是开关：一句只有「提示」的话，不许把 profile.json 的 hints 换掉。

前置条件：电视醒着、桌面在最前面、最近 20 分钟没人动过它。不满足就返回 3 并说明为什么，一个字都不碰电视。
做什么：先从 MEMORY 广播里读 hints（STATE 里没有这个字段——2026-11-12 22:47 第一次真机就是这么白跑一遍的），
然后通过 xiaoai_say.py 说「你看我现在每次给你提示提示提示提示提示」，说完再读一次 hints。
为什么走真实路径：静音来源（source=test）对有副作用的工具是拒执的，在静音来源下「hints 没变」什么都证不了
——这正是 2026-11-11 20:03 那个错误的形状。所以这句话要说给小爱，像用户真说的一样。
算过：前后两次读到的 hints 一样。变了，就是星仪把一句抱怨当成了开关。
收尾：这句话是从 xiaoai 来的，会进星仪的记忆，用 FORGET_CHECKS 把它从历史里拿掉；音量没动，桌面留在最前面。
"""
import os, sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import probe as p

ID = "hint-card-not-a-switch"
TASK = "你看我现在每次给你提示提示提示提示提示"


def hints():
    """profile.json 的 hints，从 MEMORY 广播里读（STATE 里从来没有这个字段）；None 是读不出来（那就不算过）。"""
    return p.memory().get("hints")


def check(r):
    """@return (ok, notes, shots)"""
    notes, shots = [], []
    before = hints()
    notes.append("说之前 hints = %r" % (before,))
    out = p.run_tool("xiaoai_say.py", TASK, 60)["out"]
    after = hints()
    notes.append("说完之后 hints = %r" % (after,))
    acted = [l.strip() for l in out.splitlines()
             if l.strip().startswith("- ") and ("hints" in l or "提示" in l)]
    notes.append("任务里和提示有关的几行：" + (" | ".join(acted[-4:])[:200] or "（没有）"))
    reply = [l.strip() for l in out.splitlines() if l.strip().startswith("reply:")]
    notes.append("回答：" + (reply[-1][:120] if reply else "（没有）"))
    ok = before is not None and before == after
    if before is None or after is None:
        notes.append("hints 从 STATE 里读不出来，这次不算过")
    elif not ok:
        notes.append("hints 变了：一句只有「提示」的话被当成了开关")
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
