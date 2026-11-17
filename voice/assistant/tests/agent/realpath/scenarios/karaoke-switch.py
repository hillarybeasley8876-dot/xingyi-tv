# -*- coding: utf-8 -*-
"""网易云的「切到伴奏 / 切回原唱」（11-08 14:24–14:29 真实请求连续三次失败；11-12 19:33 又见过一次 unconfirmed
和 90 秒无回答——那一次是网关在任务中途断了，19:47 重试两次都成功，所以这是一条**要能重复跑**的检查，
不是一次性的结论）。

前置条件：电视醒着、桌面在最前、最近 20 分钟没人动过它。它自己放一首歌（静音来源），不等广告，不碰用户正在看的东西。
做什么：播放 → 「切到伴奏」→ 「切回原唱」，每一步读工具自己的回答。
算过：两次回答都是 done/answered，并且话里带着「已切到伴奏」「已切回原唱」；只有 unconfirmed / 失败才算不过。
收尾：关掉网易云（本次启动的）、音量没动、桌面留在最前面。
"""
import os, sys, time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import probe as p

ID = "karaoke-switch"
NETEASE = "com.netease.cloudmusic.tv"
SONG = "播放汪苏泷的晴"


def check(r):
    """@return (ok, notes, shots)"""
    notes, shots = [], []
    out = p.run_tool("tvtask.py", SONG, 60)["out"]
    notes.append("放歌：" + " | ".join(l.strip() for l in out.splitlines() if l.strip().startswith("reply:"))[-160:])
    started = p.wait_for(lambda: p.audio_state(NETEASE) in ("started", "paused"), 25, 1.0)
    notes.append("网易云在放（音频服务的 USAGE_MEDIA 播放器）：%s" % bool(started))
    time.sleep(2)
    shots.append(p.shot(ID + "-before.png"))
    mid = [l.strip() for l in (p.run_tool("tvtask.py", "切到伴奏", 90)["out"] or "").splitlines() if l.strip()]
    said_on = " ".join(mid[-3:])
    notes.append("切到伴奏：" + said_on[-200:])
    time.sleep(2)
    shots.append(p.shot(ID + "-accomp.png"))
    back = [l.strip() for l in (p.run_tool("tvtask.py", "切回原唱", 90)["out"] or "").splitlines() if l.strip()]
    said_off = " ".join(back[-3:])
    notes.append("切回原唱：" + said_off[-200:])
    time.sleep(2)
    shots.append(p.shot(ID + "-orig.png"))
    ok = bool(started) and "已切到伴奏" in said_on and "已切回原唱" in said_off
    if not ok:
        missing = []
        if not started:
            missing.append("放歌之后音频服务没说在放")
        if "已切到伴奏" not in said_on:
            missing.append("切到伴奏的话里没有「已切到伴奏」")
        if "已切回原唱" not in said_off:
            missing.append("切回原唱的话里没有「已切回原唱」")
        notes.append("没过的原因：" + "；".join(missing))
    return ok, notes, shots


def main():
    why = p.refuse()
    if why:
        p.say(ID, "not run:", why)
        return 3
    r = p.Restore(apps=[NETEASE])
    try:
        ok, notes, shots = check(r)
    finally:
        r.close()
    p.evidence(ID, ok, notes, shots)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
