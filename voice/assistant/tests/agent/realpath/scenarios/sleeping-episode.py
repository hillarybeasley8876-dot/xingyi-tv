# -*- coding: utf-8 -*-
"""待机时说「我想看别无选择第5集」：屏幕要亮，播放页要开到最前面（2026-11-12 11:06 的一次真机验证）。

前置条件：电视在待机（屏幕关着）。醒着就不跑——那是另一个场景（episode-open），而且醒着说明有人刚用过电视。
连不上电视同样不跑。不满足就返回 3 并说明为什么，一个字都不碰电视。
做什么：通过 xiaoai_say.py 说这句话——真实路径，这句话真的说给小爱。静音来源按设计唤不醒屏幕，验不了这件事。
算过：说完以后 15 秒内屏幕亮，45 秒内 TVPlayerActivity 在最前面。两个时刻都从「说完」算起，写进 notes。
收尾：把云视听极光停掉，重新熄屏（这个场景开始时电视就在待机，结束时要还回去），说给小爱的这句话用
FORGET_CHECKS 从星仪的历史里拿掉，音量没动。
"""
import os, sys, time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import probe as p

ID = "sleeping-episode"
SAID = "我想看别无选择第5集"
WAKE_S, PLAYER_S = 15, 45          # 说完以后多久要亮屏、多久播放页要在前


def refuse():
    """为什么这个场景现在不能跑；None 表示可以。和别的场景相反：它要一台待机的电视。"""
    if not p.reachable():
        return "连不上电视（adb %s -s %s）：不跑" % (p.ADB, p.DEV)
    if p.awake():
        return "电视是醒着的（前面是 %s）：这个场景要一台待机的电视，不跑" % (p.resumed() or "?")
    return None


def screen_off():
    """熄屏：SLEEP(223) 只关不切；这台电视要是不认它，再用 26（POWER，来回切的键）。已经熄着就什么都不按
    ——对着待机的电视按 26 会把它点亮。@return 真的熄了吗"""
    if not p.awake():
        return True
    p.key(223)
    if p.wait_for(lambda: not p.awake(), 5, 0.5):
        return True
    p.key(26)
    return bool(p.wait_for(lambda: not p.awake(), 5, 0.5))


def check(r):
    """@return (ok, notes, shots)"""
    notes, shots = [], []
    r.started(p.KTCP)
    proc = p.start_tool("xiaoai_say.py", SAID, 60)      # 后台跑：两个判据要边跑边看，不能等它说完再看
    t = time.time()
    woke = p.wait_for(p.awake, WAKE_S, 0.3)
    woke_at = time.time() - t
    notes.append("屏幕亮：%s（说出去以后 %.1f 秒，判据 %d 秒）" % (bool(woke), woke_at, WAKE_S))
    front = p.wait_for(lambda: p.PLAYER in p.resumed(), PLAYER_S - (time.time() - t), 0.5)
    front_at = time.time() - t
    notes.append("播放页在前：%s（说出去以后 %.1f 秒，判据 %d 秒；前面是 %s）"
                 % (bool(front), front_at, PLAYER_S, p.resumed() or "?"))
    out = p.finish(proc, 100)
    reply = [l.strip() for l in out.splitlines() if l.strip().startswith(("reply:", "error:", "- "))]
    notes.append("任务说了什么：" + (" | ".join(reply[-3:])[:200] or "（没有）"))
    time.sleep(3.0)
    shots.append(p.shot("sleeping-episode.png"))
    ok = bool(woke) and bool(front)
    if not ok:
        notes.append("算不过：%s%s" % ("屏幕没在 %d 秒内亮。" % WAKE_S if not woke else "",
                                     "播放页没在 %d 秒内到最前面。" % PLAYER_S if not front else ""))
    return ok, notes, shots


def main():
    why = refuse()
    if why:
        p.say(ID, "not run:", why)
        return 3
    r = p.Restore(apps=[p.KTCP], leave_home=False)
    try:
        ok, notes, shots = check(r)
    finally:
        p.sh("am force-stop " + p.KTCP)
        p.say("screen off again:", screen_off())
        r.close()
    p.evidence(ID, ok, notes, shots)
    p.say(ID, "PASSED" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
