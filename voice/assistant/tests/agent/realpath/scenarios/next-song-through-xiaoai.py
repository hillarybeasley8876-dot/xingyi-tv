# -*- coding: utf-8 -*-
"""对小爱说「下一首」：网易云的歌真的换了，而且只换一首（2026-11-16 19:30：网易云在放林俊杰的《江南》，小爱自己下发了
PlaybackController.Next，网易云没动；星仪被「小爱已经切过了」拦住，回答「小爱已经切过了，现在还是……《江南》」。
6d32cf8 让门看播放器自己说的：同一首、请求之前 3 秒以上就在放 = 小爱的那一下没到，星仪才切。这一条在真机上验它）。

前置条件：电视醒着、桌面在最前面、最近 20 分钟没人动过它。不满足就返回 3 并说明为什么，一个字节都不碰电视。
做什么：tvtask.py 说「放林俊杰的歌」（静音来源；只给歌手 = 连续播放一串，「下一首」才有地方去），等网易云的 media
session 说 state 3（在放），再多放 10 秒同一首——门要的是「请求之前 3 秒以上就在放」，小爱的切歌没到时星仪才看得出来。
然后通过 xiaoai_say.py 说「下一首」（真实路径：小爱自己的指令照样会下发），不阻塞；同时每 0.3 秒读一次 session，
从说之前一直读到说完以后 15 秒，记下标题变化的序列（连续重复的只记一次）。
算过：说完以后标题恰好变了一次（T1 → T2，之后不再变），最后读到的 session 在放、而且是 T2。一次都没变 = 11-16 的
样子（小爱的没到、星仪也没切）；变了两次或更多 = 多跳了（小爱和星仪各切了一下）；最后网易云停了或 session 没了，
都算不过。开播没起来（25 秒里 session 一直不是 3）也是一次真跑：算不过，notes 写清卡在哪一步——这和「现在不能跑」
不是一回事。说之前 session 没有标题：看不出换没换，这次验不了（返回 3，不写证据文件）。
收尾：停掉自己开起来的网易云，音量放回去（开播前压到 3），说给小爱的这句话用 FORGET_CHECKS 从星仪的历史里拿掉，
桌面留在最前面。
"""
import os, sys, time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import probe as p

ID = "next-song-through-xiaoai"
PLAY, TASK = "放林俊杰的歌", "下一首"
PLAY_S, EXTRA_S = 25, 10        # 等开播 / 开播以后同一首再多放几秒：门的 3 秒要从位置上看得出来
EVERY_S, AFTER_S = 0.3, 15      # 读 session 的间隔（真机上每次还要加一趟 dumpsys）/ 说完以后再读多久
SAY_S = 30                      # xiaoai_say.py --wait；读到 SAY_S + 30 + AFTER_S 秒还没说完就不等了
PLAYING = 3                     # PlaybackState.STATE_PLAYING


def said(out, n=2):
    """任务自己说了什么：最后 n 行 reply:/error:，没有就是「（没有）」。"""
    lines = [l.strip() for l in (out or "").splitlines() if l.strip().startswith(("reply:", "error:"))]
    return " | ".join(lines[-n:])[:200] or "（没有）"


def playing(s):
    return bool(s) and s.get("state") == PLAYING


def show(s):
    """一次读到的 session，写进 notes 的样子。"""
    if not s:
        return "（没有 session）"
    return "state=%s「%s」" % (s.get("state"), s.get("meta") or "没有标题")


def watch(proc):
    """说「下一首」的同时每 EVERY_S 秒读一次 session，一直读到说完以后 AFTER_S 秒。

    @return (reads, done)：reads 是 [(第几秒, session)]，done 是读到「说完了」的那一秒（到点了还没说完就是 None）。
    """
    t0, done, reads = time.time(), None, []
    while True:
        now = time.time() - t0
        reads.append((now, p.session(p.NETEASE)))
        if done is None and proc.poll() is not None:
            done = now
        if (done is not None and now >= done + AFTER_S) or now >= SAY_S + 30 + AFTER_S:
            return reads, done
        time.sleep(EVERY_S)


def titles(first, reads):
    """标题变化的序列：从说之前那一首开始，连续重复的只记一次；没有标题的那几次读（切歌途中）不算一首。"""
    seq = [first]
    for _, s in reads:
        meta = (s or {}).get("meta") or ""
        if meta and meta != seq[-1]:
            seq.append(meta)
    return seq


def verdict(seq, last):
    """为什么没过，一句话；过了是 None。seq 是标题变化的序列，last 是最后读到的 session。"""
    if len(seq) == 1:
        return "没切过去：小爱的切歌没到，星仪也没切（11-16 19:30 的样子），说完 %d 秒还是「%s」" % (AFTER_S, seq[0])
    if len(seq) > 2:
        return "多跳了：说一次「%s」标题变了 %d 次（%s）" % (TASK, len(seq) - 1, " → ".join(seq))
    if not last:
        return "切到「%s」以后网易云的 session 没了（网易云停了）" % seq[1]
    if last.get("state") != PLAYING:
        return "切到「%s」以后网易云停了：最后读到 %s" % (seq[1], show(last))
    if last.get("meta") != seq[1]:
        return "最后读到的不是切过去的那一首「%s」：%s" % (seq[1], show(last))
    return None


def check(r):
    """@return (ok, notes, shots)；ok 是 None 表示这次验不了（说之前 session 没有标题，看不出换没换）。"""
    notes, shots = [], []
    r.started(p.NETEASE)                                # 开播之前：这个应用是这一跑自己开起来的，收尾时停掉
    p.set_volume(3)                                     # Restore 会放回去
    out = p.run_tool("tvtask.py", PLAY, 40)["out"]
    notes.append("「%s」的回答：%s" % (PLAY, said(out)))
    if not p.wait_for(lambda: playing(p.session(p.NETEASE)), PLAY_S, 1.0):
        notes.append("开播没起来：%d 秒里网易云的 session 一直不是 state 3（最后读到 %s），无从说「%s」"
                     "（这次是真跑了、没跑成）" % (PLAY_S, show(p.session(p.NETEASE)), TASK))
        return False, notes, shots
    time.sleep(EXTRA_S)
    before = p.session(p.NETEASE)
    if not playing(before):
        notes.append("开播没起来：在放了，可多放 %d 秒以后读到 %s，无从说「%s」（这次是真跑了、没跑成）"
                     % (EXTRA_S, show(before), TASK))
        return False, notes, shots
    notes.append("说「%s」之前（开播以后又放了 %d 秒）：%s" % (TASK, EXTRA_S, show(before)))
    if not before.get("meta"):
        notes.append("网易云的 session 没有标题，看不出换没换歌：这次验不了，不算不过")
        return None, notes, shots
    proc = p.start_tool("xiaoai_say.py", TASK, SAY_S)
    reads, done = watch(proc)
    reply = p.finish(proc, 60)
    shots.append(p.shot(ID + ".png"))
    seq, last = titles(before["meta"], reads), reads[-1][1]
    notes.append("标题的序列（%d 次读，每 %.1f 秒一次）：%s" % (len(reads), EVERY_S, " → ".join("「%s」" % t for t in seq)))
    notes.append("说完：%s；最后读到 %s" % ("开口以后第 %.1f 秒" % done if done is not None else "到点了还没说完",
                                         show(last)))
    off = ["%.1f" % t for t, s in reads if not playing(s)]
    if off:
        notes.append("不在放的那几次读（开口以后第几秒）：%s" % "、".join(off[:12] + (["…"] if len(off) > 12 else [])))
    notes.append("星仪的回答：" + said(reply))
    why = verdict(seq, last)
    notes.append("没过的原因：" + why if why else "过了：只换了一首，最后在放的是「%s」" % seq[-1])
    return why is None, notes, shots


def main():
    why = p.refuse()
    if why:
        p.say(ID, "not run:", why)
        return 3
    r = p.Restore(apps=[p.NETEASE])
    try:
        ok, notes, shots = check(r)
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
