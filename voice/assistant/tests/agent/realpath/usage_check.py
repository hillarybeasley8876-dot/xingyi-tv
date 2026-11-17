# -*- coding: utf-8 -*-
"""probe.parse_usage() 读系统那份事件顺序读得对不对 —— 判据的输入对不对，一条电视都不碰。

    python tests/agent/realpath/usage_check.py     打印 USAGE OK（有一条不对就打印出来，exit 1）

为什么单测它：standby-home 的判据整个建在 `dumpsys usagestats` 读出来的那串事件上（2026-11-14 10:39 它读的是
熄屏时永远是空的 mResumedActivity，漏掉了只浮上来 1 秒的浏览器）。判据读错一个字，结论就是反的，而这件事在
真机上要花 90 秒才看得出来一次。这里的输入是 tests/agent/fixtures/realpath/usagestats-standby-2026-11-14.txt，
电视当时打印的原文，一个字都没改。

证的是这几样：
  · 11 行事件里那四种都在，别的一种（USER_INTERACTION）不要；
  · 先后一个都不许重排：同一秒里浏览器浮上来那条排在 SCREEN_NON_INTERACTIVE **前面**——判据看的正是这个；
  · `since` 过滤按时间说话；
  · 一字不差的重复行去掉；
  · 空文字、乱码、没有 time= 的行：一句异常都不许抛，返回空表。
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, HERE)
import probe                                                    # noqa: E402  真模块，只用一个纯函数

FIXTURE = os.path.join(ROOT, "tests", "agent", "fixtures", "realpath",
                       "usagestats-standby-2026-11-14.txt")
BROWSER, HOME = "com.phlox.tvwebbrowser", "xyz.erapi0neer.orrery"
ok, bad = 0, []


def check(cond, label):
    global ok
    if cond:
        ok += 1
        print("ok   " + label)
    else:
        bad.append(label)
        print("FAIL " + label)


def at(evs, i):
    return (evs[i]["at"], evs[i]["type"], evs[i]["pkg"]) if 0 <= i < len(evs) else None


def main():
    try:
        with open(FIXTURE, encoding="utf-8") as f:
            text = f.read()
    except OSError as e:
        print("FAIL fixture 读不出来：%s" % e)
        return 1

    evs = probe.parse_usage(text, "2026-11-14 10:38:45")
    check(len(evs) == 11, "fixture 里 11 行事件都取出来了（取了 %d 行）" % len(evs))
    check(set(e["type"] for e in evs) == set(probe.USAGE_TYPES),
          "四种事件都在，而且只有这四种：%s" % "、".join(sorted(set(e["type"] for e in evs))))
    check(all(set(e) == {"at", "type", "pkg"} and e["at"] and e["type"] for e in evs),
          "每条事件都带着 at / type / pkg 三个键，at 和 type 不空")

    # 先后：同一秒里，浏览器浮上来排在熄屏前面。这正是 2026-11-14 判据要认的那一条。
    i_off = next(i for i, e in enumerate(evs) if e["type"] == "SCREEN_NON_INTERACTIVE")
    i_up = next(i for i, e in enumerate(evs)
                if e["type"] == "MOVE_TO_FOREGROUND" and e["pkg"] == BROWSER and i > 3)
    check(evs[i_up]["at"] == evs[i_off]["at"] and i_up < i_off,
          "10:39:05 那一秒里浏览器浮上来（%s）在熄屏之前：原顺序没被重排" % evs[i_up]["at"])
    check(at(evs, i_off + 1) == ("2026-11-14 10:39:06", "MOVE_TO_BACKGROUND", BROWSER)
          and at(evs, i_off + 2) == ("2026-11-14 10:39:33", "MOVE_TO_FOREGROUND", HOME),
          "熄屏后面两条还是原来那个顺序：浏览器退到后台、28 秒后桌面回来")
    check([e["at"] for e in evs] == sorted(e["at"] for e in evs), "整张表按时间不倒着走")

    # since：按时间过滤，边界那一秒要算在里面
    check(len(probe.parse_usage(text, "2026-11-14 10:39:05")) == 7
          and at(probe.parse_usage(text, "2026-11-14 10:39:05"), 0)
          == ("2026-11-14 10:39:05", "MOVE_TO_FOREGROUND", BROWSER),
          "since=10:39:05 取到 7 条，第一条就是那一秒的浏览器浮上来（边界算在里面）")
    check(len(probe.parse_usage(text, "2026-11-14 10:39:06")) == 5, "since 往后挪一秒，少两条")
    check(len(probe.parse_usage(text, "2026-11-14 10:40:00")) == 2, "since=10:40:00 只剩重新点亮那两条")
    check(probe.parse_usage(text, "2026-11-15 00:00:00") == [], "since 在所有事件之后：一条都不要")
    check(len(probe.parse_usage(text)) == 11 and len(probe.parse_usage(text, "")) == 11,
          "不给 since（或者给空串）就是全要")

    # 重复行：dumpsys 偶尔把同一段印两遍，一字不差的重复去掉，先后不变
    dup = probe.parse_usage(text + text[text.index('time="2026-11-14 10:39:05" type=MOVE_TO_FOREGROUND'):]
                           .splitlines()[0] + "\n", "2026-11-14 10:38:45")
    check(len(dup) == 11 and dup == evs, "一字不差的重复行去掉一条，别的不动（取了 %d 行）" % len(dup))

    # 别的事件类型不要
    other = probe.parse_usage('time="2026-11-14 10:39:05" type=USER_INTERACTION package=com.x flags=0x0\n'
                              'time="2026-11-14 10:39:06" type=MOVE_TO_FOREGROUND package=com.x flags=0x0\n',
                              "2026-11-14 00:00:00")
    check(len(other) == 1 and other[0]["type"] == "MOVE_TO_FOREGROUND",
          "不是那四种的事件（USER_INTERACTION）丢掉，那四种照收")

    # 坏文字：一句异常都不许抛
    for label, junk in (("空文字", ""), ("None", None), ("乱码", "\x00\xff??? åäö\n\n"),
                        ("没有 time= 的行", "  type=MOVE_TO_FOREGROUND package=com.x\ndumpsys usagestats\n"),
                        ("时间写坏了", 'time="2026-11-14" type=MOVE_TO_FOREGROUND package=com.x\n'),
                        ("只有 package 没有 type", 'time="2026-11-14 10:39:05" package=com.x\n')):
        try:
            got = probe.parse_usage(junk, "2026-11-14 00:00:00")
            check(got == [], "%s：不抛异常，返回空表（%r）" % (label, got))
        except Exception as e:                                  # noqa: BLE001  这里就是要抓任何异常
            check(False, "%s：抛了 %s: %s" % (label, type(e).__name__, e))

    if bad:
        print("usage: %d 处不对" % len(bad))
        return 1
    print("USAGE OK")
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except AttributeError:
        pass
    sys.exit(main())
