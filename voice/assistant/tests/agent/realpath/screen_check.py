# -*- coding: utf-8 -*-
"""用 11 帧真机 fixture 判 screen.py 的卷：逐帧读一遍，误差打出来，全过打 SCREEN OK（退出 0）。

    python tests/agent/realpath/screen_check.py [--dir <放着 ground-truth.json 和那些 png 的目录>]

一条电视都不碰，只吃本地 png。判的是三件事：

  · 有 position_s 的 9 帧：读出的秒数和它差 ≤ 4 秒（这一集 2833 秒铺在 675 px 上，一分钟 14.2 px，
    4 秒不到 1 px——放宽到一像素）；bar() 不是 None；has_show_text() 为 True。
  · position_s 是 null 的 2 帧：position() 是 None（没有进度条）；`player-no-bar-01.png` 的
    has_show_text() 要是 False。
  · 任何一条不过就打 `FAIL <帧>：…` 并退出 1。

`--dir` 是给变异验证用的：把 fixture 复制一份、改坏，再指过来。默认读仓库里那一份，一个字节都不动。
"""
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import screen                                          # noqa: E402  同目录，读一帧截图的那个

PLAYER = os.path.normpath(os.path.join(HERE, "..", "fixtures", "realpath", "player"))
TOL_S = 4.0                    # 判卷的界：不到一个像素（这一集一分钟 14.2 px）
DURATION_S = 2833              # 这一集的长度，ground-truth.json 里写着

# `player-00h04m40.png` 曾经在这里有一张覆盖表（280 -> 286）：工人这个单不能改 fixture，所以它在判卷这一侧
# 绕开了一个手读错的标签。lead 把 ground-truth.json 改对以后（404b449，画面自己的时钟 00:04:46 = 286 秒），
# 那张表就成了「我错了但代码替我说话」，删掉了——判卷标准只有 ground-truth.json 一份。
# 这一帧的记录，留着给后人看那张表当初是为什么写的：
#   2026-11-14 放大 16 倍、和同一批帧里的 0 / 4 / 5 并排比过：末位是 6，不是 0。进度条按比例读出来 287.5 秒——
#   和画面自己的 286 差 1.5 秒，和标签的 280 差 7.5 秒。同一批帧里另外八帧的时钟和 ground-truth 都对得上
#   （241/264/330/353/375/397/420，末帧 442 对 444 差 2 秒），按进度条算出来的帧间隔是
#   21.0/23.1/44.2/23.1/21.0/23.1/23.1/21.1 秒，规规矩矩；按旧标签算是 23/16/50/23/22/22/23/24，
#   只有 4:40 那一处不规矩。

# `player-no-bar-14.png` 的 has_show_text() 是 True：那一帧左上角确实写着「别无选择 第5集」。它是一张
# **广告页**（右上角「广告 | 按右键 关闭」），进度条还没有。2026-11-14 12:08 我就是把它当成「正片已在放、
# 只是进度条被盖住」，于是判据在广告里说了「暂停」、判了「通过」——当天撤回。标题只回答「哪一集」，
# 不回答「正片开没开」。按 brief，这一帧只判 position() 是 None。
TITLE_REQUIRED = ("player-no-bar-01.png",)             # 这几帧的 has_show_text() 必须是 False
TITLE_ALLOWED = ("player-no-bar-14.png",)              # 这几帧只判 position()，标题在不在都记下来


def check_frame(frame, player_dir):
    """一帧：@return (读数行, 失败句或 None)。"""
    name = frame["file"]
    path = os.path.join(player_dir, name)
    if not os.path.isfile(path):
        return None, "FAIL %s：这一帧不在 %s" % (name, player_dir)
    b = screen.bar(path)
    pos = screen.position(path, DURATION_S)
    titled = screen.has_show_text(path)
    gt = frame.get("position_s")

    if gt is None:
        line = "%-24s 没有进度条  bar=%s  has_show_text=%s" % (name, b, titled)
        if pos is not None:
            return line, "FAIL %s：判卷说这一帧没有进度条，可 position() 读出了 %s" % (name, pos["bar"])
        if name in TITLE_REQUIRED and titled:
            return line, "FAIL %s：还没进正片，左上角不该有「别无选择 第5集」那一行字" % name
        if name in TITLE_ALLOWED and titled:
            line += "  ← 标题在：这是一张广告页（右上有「广告 | 按右键 关闭」），标题在广告里就有，判不了正片"
        return line, None

    if b is None:
        return "%-24s bar=None" % name, "FAIL %s：判卷说这一帧有进度条（%s 秒），可 bar() 读不到" % (name, gt)
    if not titled:
        return ("%-24s bar=%s" % (name, b),
                "FAIL %s：正片在放，左上角该有「别无选择 第5集」那一行字，has_show_text() 说不" % name)
    want = gt
    read = pos["s"]
    err = read - want
    line = "%-24s gt=%-5s read=%7.1f  err %+5.1f s  (bar x %d..%d 填充到 %d，y=%d)" % (
        name, want, read, err, b["x0"], b["x2"], b["x1"], b["y"])
    if abs(err) > TOL_S:
        return line, "FAIL %s：读出 %.1f 秒，判卷要 %s 秒，差 %.1f 秒（界 %.1f 秒）" % (
            name, read, want, err, TOL_S)
    return line, None


SYNTH_DIR = os.path.normpath(os.path.join(HERE, "..", "..", "..", "build", "screen-frames"))
SYNTH_BG, SYNTH_FILL, SYNTH_TRACK = 8, 255, 70   # 真帧的背景 0-1、已看 245、没看 25；这里给得比真帧还温和
SYNTH_X0, SYNTH_X2 = 164, 838                   # 真机的线正好是这两个端点，675 px
SYNTH_Y = 504                                   # 真机那两行是 y=504/505

# 真机 fixture 只覆盖「正常填充」这一种；判据要防的几件事它一帧都测不到，所以自己画。每一项是
# (文件名, 怎么画, 该读出什么)：
#   ("none", None)               → position() 必须 None
#   ("s", 秒数)                  → position() 必须读得出来，且和这个秒数差 ≤ TOL_S
#   ("short", None)              → position() 是 None，但 bar() 得说出 reason=short（线在，只是没走几像素）
SYNTH = [
    ("synth-single-row.png", "单行：只有 y=504 亮，y=505 是背景", ("none", None)),
    ("synth-two-row.png", "两行真进度：y=504/505 都是 164..838，填充 119 px", ("s", 500)),
    ("synth-decoration.png", "y=480 一条装饰线 + y=504 真进度条", ("s", 500)),
    ("synth-fill-20.png", "两行真进度，填充只有 20 px", ("s", 84)),
    ("synth-fill-5.png", "两行真进度，填充只有 5 px", ("short", None)),
]


def _synth(name):
    """画一帧合成图，@return 路径。

    960x540、背景 8；线从 x=164 到 x=838（675 px），已看的一段 255、没看的一段 70。比例按 2833 秒算：
    119 px ≈ 500 秒，20 px ≈ 84 秒，5 px ≈ 21 秒。
    """
    from PIL import Image            # 这个文件别处只读 png，PIL 到这里才用得上
    a = np.full((screen.FRAME_H, screen.FRAME_W), SYNTH_BG, dtype=np.uint8)

    def line(y, n_fill, x0=SYNTH_X0, x2=SYNTH_X2):
        a[y, x0:x2 + 1] = SYNTH_TRACK
        if n_fill:
            a[y, x0:x0 + n_fill] = SYNTH_FILL

    if name == "synth-single-row.png":
        line(SYNTH_Y, 119)                       # 只有这一行：挨着的那一行是背景，两行对不上
    elif name == "synth-two-row.png":
        line(SYNTH_Y, 119)
        line(SYNTH_Y + 1, 119)
    elif name == "synth-decoration.png":
        line(480, 300)                           # 广告的装饰线：短一点、位置高一点
        line(481, 300)
        line(SYNTH_Y, 119)                       # 真的那一条在下面两行
        line(SYNTH_Y + 1, 119)
    elif name == "synth-fill-20.png":
        line(SYNTH_Y, 20)
        line(SYNTH_Y + 1, 20)
    elif name == "synth-fill-5.png":
        line(SYNTH_Y, 5)
        line(SYNTH_Y + 1, 5)
    else:
        raise KeyError(name)
    os.makedirs(SYNTH_DIR, exist_ok=True)
    path = os.path.join(SYNTH_DIR, name)
    Image.fromarray(a, mode="L").save(path)
    return path


def check_synth(name, how, want):
    """一张合成帧：@return (读数行, 失败句或 None)。"""
    kind = want[0]
    want_s = want[1]
    path = _synth(name)
    why = {}
    b = screen.bar(path)
    pos = screen.position(path, DURATION_S, why=why)
    line = "%-24s %s" % (name, how)
    if kind == "none":
        if pos is not None or b is not None:
            return line, "FAIL %s：只有一行（或根本对不上）的线不是进度条，可读出了 bar=%s" % (name, b)
        return line + "  → 认不出（对）", None
    if kind == "short":
        if pos is not None:
            return line, "FAIL %s：填充太短，position() 该是 None，可读出了 %s 秒" % (name, pos["s"])
        if b is None or b.get("reason") != "short":
            return line, "FAIL %s：线在、只是填充太短，bar() 该说 reason=short，实际 %s" % (name, b)
        return line + "  → reason=short（fill_px=%d，对）" % b["fill_px"], None
    # "s"：正常读得出的那几张
    if pos is None or b is None or "reason" in b:
        return line, "FAIL %s：这两行是一条真的进度条，该读得出来，实际 bar=%s why=%s" % (name, b, why)
    if abs(pos["s"] - want_s) > TOL_S:
        return line, "FAIL %s：读出 %.1f 秒，合成时按 %s 秒画的比例，差 %.1f 秒" % (
            name, pos["s"], want_s, pos["s"] - want_s)
    if b["y"] != SYNTH_Y:
        return line, "FAIL %s：该认出 y=%d 那一条，实际认了 y=%d" % (name, SYNTH_Y, b["y"])
    return line + "  → %.1f 秒（对）" % pos["s"], None


def main(argv):
    player_dir = PLAYER
    if "--dir" in argv:
        i = argv.index("--dir")
        if i + 1 >= len(argv):
            print("FAIL --dir 后面要跟一个目录")
            return 1
        player_dir = os.path.abspath(argv[i + 1])
    try:
        with open(os.path.join(player_dir, "ground-truth.json"), encoding="utf-8") as f:
            truth = json.load(f)
    except (OSError, ValueError) as e:
        print("FAIL 读不到判卷标准 %s：%s" % (os.path.join(player_dir, "ground-truth.json"), e))
        return 1
    bad, n = [], 0
    for frame in truth.get("frames", []):
        line, fail = check_frame(frame, player_dir)
        if line:
            print(line)
        n += 1
        if fail:
            print(fail)
            bad.append(fail)
    if not n:
        print("FAIL 判卷标准里一帧都没有")
        return 1
    for name, how, want in SYNTH:
        line, fail = check_synth(name, how, want)
        print(line)
        n += 1
        if fail:
            print(fail)
            bad.append(fail)
    if bad:
        print("screen: %d 帧不过" % len(bad))
        return 1
    print("SCREEN OK（%d 帧）" % n)
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main(sys.argv[1:]))
