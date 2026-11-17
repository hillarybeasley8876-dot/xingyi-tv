# -*- coding: utf-8 -*-
"""读一帧播放页的截图：左下角进度条的比例，和左上角那一行剧集名。只吃本地 png，一条 adb 都不碰。

    python tests/agent/realpath/screen.py <png> [这一集的长度秒数]

为什么读画面：云视听极光的播放页整页是 SurfaceView 画的，`uiautomator dump` 在上面只给一个空节点
（2026-11-14 11:10 手写探针在正片里每 20 秒暂停、dump 一次，15 次全是 `nodes 1 | texts 0 | descs 0`），
浮层上的 00:MM:SS 和剧集名一个都读不到。可屏幕上明明有字，而且字体固定、位置固定——所以读像素。

这一版只读两样，都不认字：

  · 进度条：左下角那一条线，亮的一段是已看过的、暗的一段是没看的，比例乘这一集的长度就是秒数。
    2833 秒铺在 675 px 上，一分钟 14.2 px；线是纯色、边界锐利，比例读到亚像素，误差一两秒。
  · 剧集名：和 fixture 里一帧已知写着「别无选择 第5集」的，在同一块区域比字形掩膜（见 has_show_text）。

**不做数字 OCR**：lead 试过按列切字形 + 模板匹配，9 帧 54 个字形里错 6 个（某一帧的 2 和 4 长得几乎一样）。
右下角那个 00:47:13 同样是这张图、这套字，所以总长由调用方给（`duration_s`），这里只出比例。

几何：fixture 是 960x540（原分辨率 1920x1080，正好放缩 2 倍）。真机 screencap 出来是 1920x1080，
读之前先缩到 960x540，所以下面每个常量都按 960 宽写。
"""
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
PLAYER = os.path.normpath(os.path.join(HERE, "..", "fixtures", "realpath", "player"))
TEMPLATE = os.path.join(PLAYER, "player-00h04m01.png")   # 已知左上角写着「别无选择 第5集」的那一帧

FRAME_W, FRAME_H = 960, 540    # fixture 的尺寸；原分辨率 1920x1080，正好 2 倍
BAR_Y0, BAR_Y1 = 500, 508      # 进度条那两行只在这里面找：y=504/505 是真帧，475 起是广告的装饰线
BAR_X0 = 160, 170              # 线的左端；11 帧真机 + 2 张广告页一律 164（±4 px 给 1920→960 的 LANCZOS 留余量）
BAR_X2 = 832, 845              # 线的右端；一律 838
BAR_PAIR = 1                   # 相邻两行给出同一 (x0, x2)，±1 px：真帧 504/505 一致，孤立的 1 px 装饰线过不去
BRIGHT = 150                   # 已看过的那一段是 245；广告的橙色底是 140，黑边是 0-1
TRACK_MIN = 15                 # 没看过的那一段是 25；线外面是背景，0-1
MIN_FILL = 10                  # 亮的一段至少这么长才算进度条：2833 秒铺在 675 px 上，10 px 约 42 秒
MIN_RATIO = 0.01               # 或者占整条线 >= 1%：线比真机短的时候（比例尺变了）px 数跟着缩，占比不变
                               # 两条是「或」：任何一条不满足就是 short。写成「与」等于没有下限——675 px 的线上
                               # 1% 才 6.75 px，20 px 的填充占比 2.96%、怎么都过，MIN_FILL 改回 40 也红不了
MIN_TRACK = 300                # 整条线至少这么长；真的那一条是 164..838，675 px
GAP = 3                        # 填充右端那个过渡像素会掉到 7；连着 3 个背景像素才算线到头了
LINE_OFF, LINE_DARK = 3, 40    # 上下 3 行在同一段上平均 <= 40：是一条线，不是一整块亮区
MIN_TAIL, MIN_CONTRAST = 20, 60   # 暗的那一截至少 20 px（拿它定暗电平），亮暗差至少 60
GLYPH = 200                    # 字是纯白 255；广告的橙色底 140，黑边 0
TITLE_Y0, TITLE_Y1, TITLE_X0, TITLE_X1 = 18, 46, 40, 190   # 左上角那行字：字形占 y 21..38 / x 46..179
MIN_COVER, MIN_IOU = 0.9, 0.4  # 见 has_show_text：11 帧实测 cover 是 1.000（10 帧）和 0.000（1 帧）

_tmpl = None


def _load(path):
    """一帧截图，灰度，缩到 960x540（fixture 的尺寸）。"""
    im = Image.open(path).convert("L")
    if im.size != (FRAME_W, FRAME_H):
        im = im.resize((FRAME_W, FRAME_H), Image.LANCZOS)
    return np.asarray(im).astype(np.float64)


def _spans(row):
    """一行里「不暗于 TRACK_MIN」的连续段；中间不超过 GAP 个像素的缺口并成一段。

    为什么要并：填充右端是抗锯齿的，某一帧那里是 `245 134 7 29 25`——那个 7 比背景亮不了多少，
    不并的话整条线会在那里断成两截，右端就找不到了。
    """
    out, a, miss = [], None, 0
    for x, v in enumerate(row):
        if v >= TRACK_MIN:
            if a is None:
                a = x
            miss = 0
        elif a is not None:
            miss += 1
            if miss > GAP:
                out.append((a, x - miss))
                a = None
    if a is not None:
        out.append((a, len(row) - 1))
    return out


def _thin(img, y, a, b):
    """这一段是不是「一条线」：上下 LINE_OFF 行在同一段上得是暗的。

    广告卡片、整片橙色的广告底都是一大块亮区（no-bar-14 那一帧下面 12% 全是橙色，平均 140-240），
    只看「有一段亮像素」会把它们当成进度条；这一条把它们挡掉——真的进度条上下三行是 0-1。
    """
    h = img.shape[0]
    for dy in (-LINE_OFF, LINE_OFF):
        yy = y + dy
        if 0 <= yy < h and float(img[yy][a:b + 1].mean()) > LINE_DARK:
            return False
    return True


def _same_span(img, y, a, b):
    """y 和 y+1 两行是不是同一条线：下一行也有一条不短于 MIN_TRACK 的段，两端都在 ±BAR_PAIR 以内。

    真的进度条两行染料一样亮、端点一样（11 帧真机 504/505 都是 164..838，一个像素都不差）；广告的装饰线只有
    一行亮，挨着的那一行是背景——两条一比就分开了。
    """
    if y + 1 >= img.shape[0]:
        return False
    for a2, b2 in _spans(img[y + 1]):
        if b2 - a2 + 1 >= MIN_TRACK and abs(a2 - a) <= BAR_PAIR and abs(b2 - b) <= BAR_PAIR:
            return True
    return False


def _line(img, y):
    """一行里那条进度线：@return (x0, x1, x2, 已看长度) 或 ("short", x0, x1, x2) 或 None。

    几何先过，再谈填充：两端位置不对、或者只有一行（下一行对不上）的，根本不是这条进度条。几何对了、填充却
    短到不够 MIN_FILL / MIN_RATIO 的，回 ("short", …)——「线在，可才走了几像素」和「压根没有线」是两件事，
    调用方要能分开说。
    """
    row = img[y]
    for a, b in _spans(row):
        if not BAR_X0[0] <= a <= BAR_X0[1] or not BAR_X2[0] <= b <= BAR_X2[1]:
            continue
        if b - a + 1 < MIN_TRACK or not _thin(img, y, a, b) or not _same_span(img, y, a, b):
            continue
        x1 = a                                   # 亮的一段必须从线的左端起：进度条就是从左边填起来的
        while x1 + 1 <= b and row[x1 + 1] >= BRIGHT:
            x1 += 1
        if b - x1 < MIN_TAIL:                    # 一直填到右端：那是整条都亮，拿它定不了暗电平
            continue
        fill = float(np.median(row[a + 2:x1 + 1]))
        track = float(np.median(row[x1 + 1:b + 1]))
        if fill - track < MIN_CONTRAST:
            continue
        filled = x1 - a + 1
        if filled < MIN_FILL or filled / float(b - a + 1) < MIN_RATIO:
            return "short", a, x1, b
        # 按亮度算覆盖率再求和：这一段有几个像素是「填了一半」的（抗锯齿 + 1920 缩到 960 的边缘），
        # 求和出来的就是已看长度，亚像素。整数端点那条约定在 9 帧上最大偏 6.9 秒，这条最大偏 2.5 秒。
        cov = np.clip((row[a:b + 1] - track) / (fill - track), 0.0, 1.0)
        return a, x1, b, float(cov.sum())
    return None


def bar(path):
    """进度条：`{"x0": 左端, "x1": 填充右端, "x2": 轨右端, "y": 基线, "fill": 已看长度}`，读不到就 None。

    `fill` 是按亮度算出来的已看长度（亚像素，浮点），`position()` 的比例从它来；四个整数端点是给人看的。

    两种「没有」要分开：

      · 线在，只是填充太短 → `{"reason": "short", "fill_px": N, "x0", "x2", "y"}`。刚进正片那几十秒就是
        这个样子，说「没进度条」会让上层以为广告还没完。
      · 压根没有线 → None（广告卡片挡住、还没进正片、这一行根本不是进度条）。

    **不是取第一个命中**：窗口是固定的 500..508，`_line()` 自己会按几何和两行一致筛，可万一广告的装饰线
    也在这一带、也凑巧是两行，就得先扫完：整条的优先，实在只有短的才认短的。
    """
    img = _load(path)
    short = None
    for y in range(BAR_Y0, BAR_Y1 + 1):
        got = _line(img, y)
        if got is None:
            continue
        if got[0] == "short":
            _, a, x1, b = got
            n = int(x1 - a + 1)
            if short is None or n > short["fill_px"]:      # 几条短的就认最长的那个（最接近能读的一条）
                short = {"reason": "short", "fill_px": n,
                         "x0": int(a), "x2": int(b), "y": int(y)}
            continue
        x0, x1, x2, fill = got
        return {"x0": int(x0), "x1": int(x1), "x2": int(x2), "y": int(y), "fill": round(fill, 3)}
    return short


def position(path, duration_s=None, why=None):
    """进度条上的位置，或 None（这一帧上没有能读的位置）。

    比例是「已看长度 / 整条线的长度」；`duration_s` 给了才有秒数——总长读不出来（右下角那个 00:47:13
    是同一张图、同一套字，这版不做数字 OCR），得由调用方给。

    `since` / `total` 两个字段这一版先写成 None：浮层上的 00:MM:SS 和剧集名都靠模板比对，不做 OCR。

    `why` 是个可选的 dict，传进来就把「为什么没有」写进去（"short" 连同 `fill_px`，或者 "none"）：
    位置读不出来时上层还得说得出话——「进度条在，可才走了 N px」跟「一直没见到进度条」是两回事。
    """
    b = bar(path)
    if b is None or b.get("reason") == "short":
        if why is not None:
            why.clear()
            why["reason"] = "none" if b is None else "short"
            if b is not None:
                why["fill_px"] = b["fill_px"]
        return None
    ratio = b["fill"] / float(b["x2"] + 1 - b["x0"])
    return {"s": None if duration_s is None else ratio * float(duration_s),
            "ratio": ratio, "bar": b, "since": None, "total": None}


def _template():
    """那一行字的字形掩膜，从 fixture 里那帧已知写着「别无选择 第5集」的上裁下来（只算一次）。"""
    global _tmpl
    if _tmpl is None:
        _tmpl = _load(TEMPLATE)[TITLE_Y0:TITLE_Y1, TITLE_X0:TITLE_X1] >= GLYPH
    return _tmpl


def has_show_text(path):
    """画面左上角那一行字是不是「别无选择 第5集」。

    比的是字形掩膜，两个数都要过：

      · cover — 模板里那一行字的每一个笔画像素，这一帧里是不是都亮着（≥ GLYPH）。
        对背景不敏感：广告的橙色底上那行白字，cover 还是 1.000（`player-no-bar-14.png` 就是这一帧，
        **它是一张广告页**——右上角写着「广告 | 按右键 关闭」，左上角照样有剧名）。这一条只认
        「那行字在不在」，**不认「正片开没开」**：标题在广告里就有，2026-11-14 12:08 就是拿它当成了正片。
      · iou — 交并比。一整块白（比如正好一帧很亮的画面）cover 会是 1.0，可它不是那行字，
        iou 只有 733/4200 = 0.17，这里就挡住了。这一条认「那块地方是不是只有那行字」。

    11 帧实测：9 帧有进度条的 cover 1.000 / iou 1.000；`player-no-bar-01.png`（还没进正片）
    cover 0.000；`player-no-bar-14.png` cover 1.000 / iou 0.808。所以 0.9 和 0.4 都不是勉强过的阈值。

    认不出的事：这一版分不开「第5集」和「第3集」——同一套字、同一个位置，只差一个数字的那几个笔画，
    cover 大概还有 0.9 上下。要真的分开得做字形识别，那正是 lead 试过、错了 6 个字形的那件事。
    """
    t = _template()
    c = _load(path)[TITLE_Y0:TITLE_Y1, TITLE_X0:TITLE_X1] >= GLYPH
    inter, union = float((t & c).sum()), float((t | c).sum())
    cover = inter / float(t.sum()) if t.sum() else 0.0
    iou = inter / union if union else 0.0
    return cover >= MIN_COVER and iou >= MIN_IOU


def main(argv):
    if not 2 <= len(argv) <= 3:
        print("用法：python tests/agent/realpath/screen.py <png> [这一集的长度秒数]")
        return 2
    path = argv[1]
    duration = float(argv[2]) if len(argv) > 2 else None
    b = bar(path)
    why = {}
    pos = position(path, duration, why=why)
    print("bar=%s position_s=%s why=%s has_show_text=%s"
          % (b, None if pos is None or pos["s"] is None else round(pos["s"], 1), why, has_show_text(path)))
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main(sys.argv))
