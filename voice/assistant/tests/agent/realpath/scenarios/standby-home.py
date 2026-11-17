# -*- coding: utf-8 -*-
"""熄屏以后，守护要把桌面放回最上面（2026-11-12 10:38：浏览器在桌面后面，熄屏后它自己浮到最上面）。

前置条件：电视醒着、桌面在最前面、最近 20 分钟没人动过它。不满足就返回 3 并说明为什么，一个字都不碰电视。
做什么：开浏览器之前记下电视自己的时间（`since`），把浏览器放到最前面，按 HOME（桌面回到最上面），按 POWER(26)
熄屏，然后**静等** 90 秒观察窗口——一个键都不按，一次都不读最前面的 activity。

判据读什么：窗口结束、点亮之前读一次 `dumpsys usagestats`，看系统自己按毫秒记下的事件顺序；规则和守护自己的
`MemoryCare.surfacedAfter` 是同一条。2026-11-14 10:39 是这个场景第三次栽在同一个坑里：它每 2 秒读一次
`mResumedActivity`，可熄屏时所有界面都暂停，那个字段是空的；浏览器只浮上来 1 秒（10:39:05 上来、10:39:06 退回
后台），第一次读（熄屏后 2 秒）就已经错过。守护那一次做对了（10:39:33 桌面回来，熄屏后第 28 秒），是场景看错了
地方——系统的记录一直在那儿，判据没去读它。

算过：熄屏那一秒起 10 秒内有不是桌面的应用浮到过最上面，而且在那之后 60 秒内桌面回到最上面。没有任何东西浮上来
（守护没事可做）、或者事件里根本没有 SCREEN_NON_INTERACTIVE（屏幕没关成），都是这次验不了：写一份
`build/realpath-attempts/` 下的时间线，返回 3，不写证据文件——没验成就没有证据，可看到了什么不能丢。
收尾：浏览器停掉（开始前就在跑的，不停），屏幕回到开始时的样子（开始时是醒着的就点亮），音量没动，桌面留在最前面。

这台电视不认 WAKEUP(224)，也不认 SLEEP(223)——2026-11-14 量的：10:17 按 224 屏幕没亮，10:38 按 223 过了 5 秒还
醒着，两次都是退回 POWER(26) 才成。26 是来回切的键，所以熄屏只在醒着时按它、点亮只在熄着时按它：两个前提少一个，
按下去的就是反的那一半。原来那套「先 223/224、不行再退回 26」每次白等 5~8 秒，还把「按 HOME → 熄屏」的间隔从
真实情形的一两秒拉成了七秒。
"""
import os, sys, time
from datetime import datetime, timedelta

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import probe as p

ID = "standby-home"
BROWSER = "com.phlox.tvwebbrowser"
WATCH_S, WITHIN_S = 90, 60            # 静等 90 秒（守护心跳 20 秒，够两次以上）；桌面要在熄屏后 60 秒内回来
SURF_BEFORE_S, SURF_AFTER_S = 1, 10   # 浮上来的窗口：熄屏那一秒起 10 秒（和 MemoryCare.surfacedAfter 同一条规则）
FG = "MOVE_TO_FOREGROUND"
SCREEN_OFF = "SCREEN_NON_INTERACTIVE"
AT = "%Y-%m-%d %H:%M:%S"


def screen_off():
    """熄屏：只用 POWER(26)，而且**只在醒着时**按——26 是来回切的键，已经熄着再按一下就把屏幕点回来了。
    @return 熄屏真的成了吗（屏幕关不掉，这个场景就无从谈起）"""
    if not p.awake():
        p.say("screen already off")
        return True
    p.key(26)
    return bool(p.wait_for(lambda: not p.awake(), 6, 0.5))


def screen_back(was_awake):
    """屏幕回到开始时的样子：还是只用 26，而且**只在熄着时**按（醒着按它就是关屏）。"""
    if was_awake and not p.awake():
        p.key(26)
        p.wait_for(p.awake, 8, 0.5)
    elif not was_awake and p.awake():
        p.key(26)
        p.wait_for(lambda: not p.awake(), 8, 0.5)
    p.say("screen back:", "awake" if p.awake() else "asleep", "(started", "awake)" if was_awake else "asleep)")


def at_dt(at):
    """一条事件的时间，电视的本地时间。格式是 parse_usage 认过的那个，认不出来就让它抛——判据宁可炸掉，
    也不要在错的时间上算出一个「过」。"""
    return datetime.strptime(at, AT)


def judge(ev, since, notes):
    """判据：系统自己记下的事件顺序，规则和守护自己的 MemoryCare.surfacedAfter 一致。

    三步，和守护那边一一对应：
      · off = since 之后第一个 SCREEN_NON_INTERACTIVE；没有 → 屏幕没关成，这次验不了（None）；
      · 浮上来了 = 窗口里第一个不是桌面的 MOVE_TO_FOREGROUND；没有 → 守护没事可做，验不了（None）；
      · 放回去了 = 那一条之后第一个桌面的 MOVE_TO_FOREGROUND，熄屏后 60 秒内 → 过；没有、或者晚于 60 秒
        （比如只在重新点亮那一下才回来）→ 不过（False）。
    @return True / False / None
    """
    notes.append("窗口里系统记了 %d 条事件（%s 起）：" % (len(ev), since))
    for e in ev:
        notes.append("    %s  %-22s %s" % (e["at"], e["type"], e["pkg"]))
    ev = [e for e in ev if e["at"] >= since]          # since 之前的不是这一次的事
    off = next((e for e in ev if e["type"] == SCREEN_OFF), None)
    if off is None:
        notes.append("系统的事件里没有 %s：屏幕没关成，这次验不了" % SCREEN_OFF)
        return None
    t_off = at_dt(off["at"])
    # 窗口下界写成「熄屏前一秒」再开区间：秒级的时间戳下，> off - 1 s 就是「熄屏那一秒起」。系统可能先记浮上来
    # 再记熄屏（2026-11-14 10:39:05 就是），同一秒里靠前的那一条不能在时间比较里被丢掉。
    lo, hi = t_off - timedelta(seconds=SURF_BEFORE_S), t_off + timedelta(seconds=SURF_AFTER_S)
    up, up_i = None, -1
    for i, e in enumerate(ev):
        if e["type"] != FG or p.HOME_PKG in e["pkg"]:
            continue
        if lo < at_dt(e["at"]) <= hi:
            up, up_i = e, i
            break
    if up is None:
        notes.append("熄屏那一秒起 %d 秒内，没有不是桌面的应用浮到最上面：守护没事可做，这次验不了" % SURF_AFTER_S)
        return None
    notes.append("浮上来了：%s %s（熄屏后 %.0f 秒）"
                 % (up["at"], up["pkg"], (at_dt(up["at"]) - t_off).total_seconds()))
    back = next((e for e in ev[up_i + 1:] if e["type"] == FG and p.HOME_PKG in e["pkg"]), None)
    if back is None:
        notes.append("浮上来以后，桌面一次都没再回到最上面：算不过")
        return False
    d = (at_dt(back["at"]) - t_off).total_seconds()
    notes.append("桌面回到最上面：%s（熄屏后 %.0f 秒，判据是 %d 秒内）" % (back["at"], d, WITHIN_S))
    if not 0 <= d <= WITHIN_S:
        notes.append("算不过：桌面是熄屏后 %.0f 秒才回来的，晚于 %d 秒（守护心跳 20 秒；"
                     "比如只在重新点亮那一下才回来，就是这个样子）" % (d, WITHIN_S))
        return False
    return True


def check(r):
    """@return (ok, notes, shots)；ok 是 None 表示这次验不了（守护没事可做，或者屏幕没关成）。"""
    notes, shots = [], []
    since = p.tv_datetime()
    notes.append("开浏览器之前记下电视的时间：%s（判据读的系统事件从这一刻起）" % since)
    r.started(BROWSER)
    p.sh("monkey -p %s 1 >/dev/null 2>&1" % BROWSER)
    up = p.wait_for(lambda: BROWSER in p.resumed(), 8, 0.4)
    notes.append("浏览器在前：%s（前面是 %s）" % (bool(up), p.resumed() or "?"))
    if not up:
        notes.append("浏览器没起来，后面那两步无从谈起")
        return False, notes, shots
    p.home()
    time.sleep(1.5)
    notes.append("按了 HOME，前面是 %s" % (p.resumed() or "?"))
    if not screen_off():
        notes.append("屏幕关不掉（按了 26 还是醒着）：熄屏这一步没成，这次验不了")
        return None, notes, shots
    notes.append("熄屏了：静等 %d 秒观察窗口，一个键都不按、一次都不读最前面的 activity"
                 "（守护心跳 20 秒，够两次以上）" % WATCH_S)
    time.sleep(WATCH_S)
    ok = judge(p.usage_events(since), since, notes)
    notes.append("判了什么：%s" % ("过" if ok else "不过" if ok is False else "这次验不了"))
    return ok, notes, shots


def main():
    why = p.refuse()
    if why:
        p.say(ID, "not run:", why)
        return 3
    was_awake = p.awake()
    r = p.Restore(apps=[BROWSER])
    try:
        ok, notes, shots = check(r)
    finally:
        screen_back(was_awake)
        r.close()
    shots.append(p.shot("standby-home-end.png"))
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
