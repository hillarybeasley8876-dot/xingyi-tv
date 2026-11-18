# -*- coding: utf-8 -*-
"""每条场景的判据，正反两面都在这里离线跑一遍 —— 一条电视都不碰。

    python tests/agent/realpath/rehearse.py     每条局面一行 ok，末尾 REHEARSE OK（退出 0）

为什么：真机场景的判据从来没被证明过会红。已经出过的两次都是这个形状——`hint-card-not-a-switch`（11-12 22:47）读
一个 probe 根本不提供的字段，前后两次都是 None，那条场景从写出来那天起就不可能通过，脚本跑一百次也不会红，只会
白占电视 20 分钟；反过来，一个永远判过的判据（`ok = True`、`MIN_JUMP` 写成 -999）跑一次「通过」就写进证据，之后
没人再碰它。这里的做法是：给一个假 probe，把每条场景的 `check()` 在几种**写死的局面**里跑一遍，好的必须过、坏的
必须不过。局面红不了，判据就白写。

怎么跑：假 probe（`types.ModuleType("probe")`，载入场景前塞进 `sys.modules`）给场景用到的每一个名字，
`main()` 不跑（那要电视）。场景里直接用的 `time` 也换成假的——`sleep()` 只把钟往前拨，一秒真时间都不占
（`standby-home` 读 90 秒、`episode-open` 等 8 次 15 秒，真睡要好几分钟）。假 `wait_for` 也拨钟：每求值一次
`pred()` 之前把钟往前拨 `step`，所以局面能写出「第 16 秒才亮」这种**发生了、可是太晚**的一档——判据的
时间界（`WAKE_S` / `PLAYER_S` / `WITHIN_S`）这才真的被逼到过。

局面怎么写：`CASES` 里每条是一个字典，开头三个键挨在一起 —— scenario（哪条场景）、name（局面名）、expect
（True / False / None 三选一）—— 然后 facts。`facts` 只写这个场景真会用到的那几样；序列是「第几次问的时候
是什么值」，到底了就重复最后一个：

    resumed_seq / audio_seq / ui_text_seq / hud_seq / awake_seq / memory_seq
    tool_reply（run_tool / start_tool 拿到的回答）、tool_replies（按那句话查）、proc_running、
    proc_done_at（start_tool 起的任务第几秒说完，按钟算）
    session_seq（网易云的 media session：probe.session() 那种字典，或者 None = 没有 session）
    audio_events / settings / tv_clock / own_app
    tv_datetime（电视自己的时间，判据的 since）、usage_events（dumpsys usagestats 的事件表，判据读的那一份）
    frame_seq（场景自己 p.frame() 截到的那一帧 png 的路径）、show_frame_seq（等广告时看到的帧）、
    show_at（广告在第几秒放完；不写或者 None 就是一直没放完）

播放页那三条（episode-open / pause-through-xiaoai / seek-one-minute）的判据读的是**画面**，所以局面给的是
帧而不是字：`frame_seq` / `show_frame_seq` 里写的是 `tests/agent/fixtures/realpath/player/` 下真机截图的路径
（AD_FRAME / AD_TITLED / SHOW_FRAME / SHOW_LATER，或者 `wrong_episode_frame()` 现做的那张「开错了集」的帧），
读它们的也是**真的** `screen.py`——进度条的比例和左上角那行字都是真的从像素里读出来的，不是又一个假值。
原来那三条押在 `ui_text()` 上的判据从写出来那天就不可能成立（播放页整页是 SurfaceView 画的，
`uiautomator dump` 在上面只有一个空节点），这一版把它们换成了读画面。

序列有两种写法，两种都在：

    resumed_seq: [OWN_ACT, OWN_ACT, HOME_ACT]                     # 第几次问的时候是什么值
    resumed_seq: [{"at": 0, "v": HOME_ACT}, {"at": 16.0, "v": PLAYER_ACT}]   # 钟到几点才发这个值

裸值写法每叫一次拨一格（到底了重复最后一个）；时间门写法按钟说话——钟还没到 `at` 就发**上一个**已经到点的值
（第一次之前发那个序列的 fallback），到点了就发它，和被叫过几次无关。`at` 的秒数从这条局面开始算（`Clock.now()`）。
两种写法都是「问的时候是什么」，只是拨格子的手不一样：一只数次数，一只看钟。

三个键要挨在一起（`scenario`、`name`、`expect`）：check.py 是文本扫这三个键来核对「有条目就得有条局面」的，
扫不到的写法就当成没有——不要把这三个键拆开写，也不要在别的字典里用这三个名字。

一条电视都不碰：假 probe 不跑 adb、不联网、不写文件；`shot()` 只回一个假文件名，`evidence()` 和 `attempt()` 只把
内容记进列表。跑完还会比对 `tests/agent/evidence/` 的文件清单（名字、大小、改动时刻），变了就打 FAIL。
"""
import importlib.util, os, re, sys, types

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import screen                                            # 真的那个：假 probe 读帧用的就是它

ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
SCEN = os.path.join(HERE, "scenarios")
EVIDENCE = os.path.join(ROOT, "tests", "agent", "evidence")
USAGE_FIXTURE = os.path.join(ROOT, "tests", "agent", "fixtures", "realpath",
                             "usagestats-standby-2026-11-14.txt")
PLAYER_DIR = os.path.join(ROOT, "tests", "agent", "fixtures", "realpath", "player")

# 抄 probe.py 的常量，只是为了让场景里那些 %s 有个值；假 probe 一个都不去用。
ADB, DEV = "H:/tools/platform-tools/adb.exe", "192.168.31.162:5555"
KTCP, NETEASE = "com.ktcp.video", "com.netease.cloudmusic.tv"
PLAYER, HOME_PKG = "TVPlayerActivity", "orrery"
OWN_APP = "xyz.erapi0neer.fluidlab"                      # probe.own_app() 读 OwnApps.java 拿到的那个黑屏应用
BROWSER = "com.phlox.tvwebbrowser"

HOME_ACT = "com.orrery.launcher/com.orrery.launcher.MainActivity"          # 含 HOME_PKG
PLAYER_ACT = "com.ktcp.video/com.ktcp.video.TVPlayerActivity"              # 含 PLAYER
BROWSER_ACT = BROWSER + "/" + BROWSER + ".MainActivity"
OWN_ACT = OWN_APP + "/" + OWN_APP + ".MainActivity"
HUD_WIN = "Window{a1b2c3 u0 xyz.erapi0neer.xiaoaibridge/xyz.erapi0neer.xingyi.agent.tv.HudPanel}"

OPEN5, SEEK, PAUSE = "我想看别无选择第5集", "快进一分钟", "暂停"
EPISODE_S = 2833                              # 抄 probe.EPISODE_S：进度条只能读比例，秒数靠它换算
SONG, ACCOMP, ORIG = "播放汪苏泷的晴", "切到伴奏", "切回原唱"
LJJ, NEXT = "放林俊杰的歌", "下一首"
# 网易云 session 的 description（标题, 歌手, 专辑），probe.session() 的 meta 读的就是这一串
JIANGNAN, KEXI, XIULIAN = "江南, 林俊杰, 第二天堂", "可惜没如果, 林俊杰, 新地球", "修炼爱情, 林俊杰, 因你而在"
BLACK, HINTS, PANEL, UPTIME = "屏幕黑了", "你看我现在每次给你提示提示提示提示提示", \
    "为什么现在HARNESS面板没有打开", "看一下电视现在开了多久"

# 播放页的帧：真机 fixture（tests/agent/fixtures/realpath/player/），读它们的也是真的 screen.py。
# 括号里是 screen.position() 读出来的秒数和 screen.has_show_text()。
AD_FRAME = os.path.join(PLAYER_DIR, "player-no-bar-01.png")      # 广告：没有进度条，左上角也没有剧集名
AD_TITLED = os.path.join(PLAYER_DIR, "player-no-bar-14.png")     # 广告页：进度条读不到，可标题在（标题在广告里就有）
SHOW_FRAME = os.path.join(PLAYER_DIR, "player-00h05m30.png")     # 正片：331.6 秒，标题在
SHOW_LATER = os.path.join(PLAYER_DIR, "player-00h06m37.png")      # 正片，晚一分多钟：398.8 秒，标题在

# standby-home 的判据读的那份系统记录：dumpsys usagestats 的四种事件
FG, BG, SCREEN_OFF = "MOVE_TO_FOREGROUND", "MOVE_TO_BACKGROUND", "SCREEN_NON_INTERACTIVE"
HOME_APP = "xyz.erapi0neer.orrery"                    # 桌面包的全名（HOME_PKG「orrery」是它的一截）
SINCE_1114 = "2026-11-14 10:38:45"                    # 2026-11-14 那次跑，开浏览器之前电视自己的时间
PARSE_ONLY = "realprobe_parse_only"                   # 下面那个只读一个纯函数用的模块名，不进 sys.modules


def parse_usage(text, since):
    """真的 probe.parse_usage() —— 局面表里那条真时序要用它从 fixture 原文造出来，一个字都不手抄。

    probe.py 是按 spec 载入的，**不注册进 sys.modules**：这个文件末尾的 real_probe_loaded() 查的正是「有没有真的
    probe 混进这次跑」（场景必须拿到假的那个）。这里要的只是它里面一个纯函数，载入它一条 adb 都不碰——probe.py
    的模块体只有 import 和常量，一个 subprocess 都不起。
    """
    spec = importlib.util.spec_from_file_location(PARSE_ONLY, os.path.join(HERE, "probe.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.parse_usage(text, since)


def usage(*rows):
    """一条事件表：(时间, 类型, 包名) 三元组 → 判据吃的那种字典。"""
    return [{"at": a, "type": t, "pkg": k} for a, t, k in rows]


def song(state, meta):
    """网易云的一次 session，和 probe.session() 回的一个样（state 3 在放、2 暂停、6 缓冲、10 正在切下一首）。"""
    return {"state": state, "position": 12000, "actions": 2360, "meta": meta}


def fixture_events():
    """2026-11-14 10:38:45-10:41:09 那份真时序，从 fixture 原文过一遍真的 parse_usage 造出来。

    读不到（fixture 不在、probe.py 读不了）就回空表：那条局面会红，红得对——它要的正是那份原文。
    """
    try:
        with open(USAGE_FIXTURE, encoding="utf-8") as f:
            return parse_usage(f.read(), SINCE_1114)
    except (OSError, AttributeError, ImportError):
        return []


REAL_1114 = fixture_events()


def wrong_episode_frame():
    """一帧「开错了集」的画面：拿一帧真的正片截图，把左上角那一块涂黑，@return 它的路径。

    为什么要现做一张：episode-open 的判据（画面左上角是不是「别无选择 第5集」那一行字）得能红一次，
    可仓库里 11 帧没有这样的画面——有进度条的 9 帧左上角全都写着那一行字。涂黑以后进度条还在
    （正片确实在放，`screen.position()` 读得出比例），只是那行字不在了。

    涂出来的那份落在 `build/rehearse-frames/` 下；fixture 一个字节都不动。
    """
    d = os.path.join(ROOT, "build", "rehearse-frames")
    path = os.path.join(d, "wrong-episode.png")
    if not os.path.isfile(path):
        os.makedirs(d, exist_ok=True)
        im = Image.open(SHOW_FRAME).convert("RGB")
        im.paste((0, 0, 0), (screen.TITLE_X0 - 4, screen.TITLE_Y0 - 4,
                             screen.TITLE_X1 + 4, screen.TITLE_Y1 + 4))
        im.save(path)
    return path


WRONG_EP = wrong_episode_frame()


class Clock(object):
    """假的 time：sleep() 只把钟往前拨，一秒真时间都不占；time() 读的是拨到哪了。

    now() 是离这条局面开始过了几秒——局面表里时间门的 `at` 按它算，所以一条局面自己从 0 开始走。
    """

    def __init__(self, start=1000.0):
        self.t = self.start = start

    def time(self):
        return self.t

    def now(self):
        return self.t - self.start

    def sleep(self, secs):
        # 钟是反复加出来的：不抹掉这点浮点噪声，十次 0.3 会读成 2.9999999999999996，3.0 那道时间门就晚一格才开。
        self.t = round(self.t + max(0.0, float(secs)), 9)


class Seq(object):
    """一串「第几次问的时候是什么值」：每次被叫往前拨一格，到底了就重复最后一个。

    场景每叫一次就拨一格——包括它顺手记 notes 时叫的那几次。所以写局面的时候，那几次也要算进去。

    另一种写法是**时间门**：序列写成 `[{"at": 0, "v": A}, {"at": 16.0, "v": B}]`（每一项都是带 `at` 的字典，
    按 `at` 排）——问的时候钟还没到 `at` 就发**上一个**已经到点的值（第一次之前发 `fallback`），到点了就发它。
    时间门只看钟，不数次数：同一个时刻问两次，两次拿到的是同一个值。裸值写法照旧每叫一次拨一格。
    """

    def __init__(self, values, fallback, clock):
        self.clock, self.fallback, self.i = clock, fallback, 0
        self.values = list(values) if values else [fallback]
        self.gate = sorted(self.values, key=lambda e: float(e["at"])) \
            if all(isinstance(e, dict) and "at" in e for e in self.values) else None

    def __call__(self):
        if self.gate is not None:
            return self.gated()
        v = self.values[min(self.i, len(self.values) - 1)]
        self.i += 1
        return v

    def gated(self):
        """钟走到哪就发哪一档：还没到第一条的 `at` 就发 fallback，到点了发它，之后一直是它。"""
        now, v = self.clock.now(), self.fallback
        for e in self.gate:
            if float(e["at"]) > now:
                break
            v = e["v"]
        return v


class FakeProc(object):
    """start_tool() 的那个后台任务：poll() 说它还在不在跑，finish() 拿它的输出。

    `done_at`（局面的 `proc_done_at`）给了的话按钟说话：钟（`Clock.now()`）还没到它就是还在跑，到了就是跑完了——
    next-song-through-xiaoai 边读 session 边等「说完」，要的是「第几秒说完」；没给就照旧看 `running`。
    """

    def __init__(self, out, running=True, done_at=None, clock=None):
        self.out, self.running, self.returncode = out, running, None if running else 0
        self.done_at, self.clock = done_at, clock

    def poll(self):
        if self.done_at is not None:
            return None if self.clock.now() < float(self.done_at) else 0
        return None if self.running else self.returncode


class FakeRestore(object):
    """场景的收尾对象：只记谁被它算成「自己开起来的」，什么都不做。"""

    def __init__(self, log):
        self.log, self.started_apps, self.closed = log, [], False

    def started(self, pkg):
        self.started_apps.append(pkg)

    def close(self):
        self.closed = True


class FakeCaptureFailed(OSError):
    """真 probe 里 `CaptureFailed` 的同款：截图没截成。局面写 `frame_raises: "capture"` 时由假 frame() 抛。"""


def fake_probe(facts, log, clock):
    """一个假的 probe 模块：场景用到的每一个名字都给上，一个都不去碰电视。

    `facts` 是这条局面写死的几样事实，`log` 记下被叫过的名字（收尾时用来核对 evidence() 没写文件），
    `clock` 是这条局面自己的钟（`wait_for` 拨的就是它，场景里的 `time` 也是它）。
    """
    m = types.ModuleType("probe")
    m.__fake__ = True                       # 收尾时确认场景 import 到的是它，不是真的 probe
    m.ADB, m.DEV, m.KTCP, m.PLAYER, m.HOME_PKG = ADB, DEV, KTCP, PLAYER, HOME_PKG
    m.NETEASE = NETEASE
    m.EPISODE_S = EPISODE_S
    # 和真 probe 里那个类同名同父子：场景 `except p.CaptureFailed` 在假模块下也得接得住。
    m.CaptureFailed = FakeCaptureFailed

    resumed = Seq(facts.get("resumed_seq"), PLAYER_ACT, clock)
    audio = Seq(facts.get("audio_seq"), "started", clock)
    ui = Seq(facts.get("ui_text_seq"), "", clock)
    frames = Seq(facts.get("frame_seq"), SHOW_FRAME, clock)          # 场景自己 p.frame() 拿到的帧
    show_frames = Seq(facts.get("show_frame_seq"), AD_FRAME, clock)  # 等广告时看到的帧（见 wait_for_show）
    hud = Seq(facts.get("hud_seq"), [], clock)
    wake = Seq(facts.get("awake_seq"), True, clock)
    memory = Seq(facts.get("memory_seq"), {}, clock)
    sessions = Seq(facts.get("session_seq"), None, clock)           # 网易云的 media session（None = 没有）
    replies = facts.get("tool_replies") or {}
    running = facts.get("proc_running", True)

    def note(*a):
        log.append(" ".join(str(x) for x in a))
        return log[-1]

    def reply_of(text):
        return replies.get(text, facts.get("tool_reply", ""))

    def refuse(front=None, want="桌面"):
        note("refuse", front, want)
        return None

    def resumed_():
        v = resumed()
        note("resumed ->", v)
        return v

    def awake_():
        v = wake()
        note("awake ->", v)
        return v

    def audio_state(pkg=KTCP):
        v = audio()
        note("audio_state(%s) ->" % pkg, v)
        return v

    def audio_events(since, keep=("player piid", "new player", "releasing")):
        return list(facts.get("audio_events", []))

    def ui_text():
        v = ui()
        note("ui_text ->", (v or "")[:60])
        return v

    def frame(tag="frame"):
        """场景自己截的那一屏：按局面里的 `frame_seq` 走，回一个真的 png 路径，一个字节都不写。

        回的是 fixture（或者 wrong_episode_frame() 现做的那张），所以场景里 `screen.position()` /
        `screen.has_show_text()` 读到的是**真的读法**——判据读画面的那一路在这里也是真的在跑，
        不是又一个假值。

        `frame_raises: "capture"` 的局面：这一路截图全失败（真 probe 里是 screencap 超时 / pull 不回来 /
        拉回来不是 png），抛 `FakeCaptureFailed`，和真 probe 抛 `CaptureFailed` 一个意思。
        """
        if facts.get("frame_raises") == "capture":
            note("frame", tag, "-> CaptureFailed")
            raise FakeCaptureFailed("screencap 20 秒没回来：电视那边卡住了")
        v = frames()
        note("frame", tag, "->", os.path.basename(v))
        return v

    def hud_():
        v = list(hud())
        note("hud ->", len(v))
        return v

    def memory_():
        v = dict(memory())
        note("memory ->", v)
        return v

    def session(pkg=NETEASE):
        """局面里的 `session_seq`：真 probe.session() 那种字典（state 3 = 在放），没有 session 就是 None。

        next-song-through-xiaoai 每 0.3 秒读一次、读到说完以后 15 秒——好几十次，数次数的写法得一格格数，
        所以那条场景的局面写时间门：钟到几点 session 变成什么（同一个时刻读几次都一样）。抄一份出去，场景改不了局面表。
        """
        v = sessions()
        v = dict(v) if v else None
        note("session(%s) ->" % pkg, v and (v.get("state"), v.get("meta")))
        return v

    def setting(name):
        return facts.get("settings", {}).get(name, "")

    def tv_clock():
        return facts.get("tv_clock", "02:40:11.123456")

    def tv_datetime():
        """电视自己的时间（`2026-11-14 10:38:45`）—— standby-home 的 since 就靠它。"""
        v = facts.get("tv_datetime", SINCE_1114)
        note("tv_datetime ->", v)
        return v

    def usage_events(since):
        """局面里写死的那份系统记录；判据读的整个输入就是它。抄一份出去，场景改不了局面表。"""
        ev = [dict(e) for e in facts.get("usage_events", [])]
        note("usage_events(%s) ->" % since, len(ev), "条")
        return ev

    def attempt(sid, notes_=None):
        """「这次验不了」留下的那份时间线：这里只记进 log，一个字节都不写盘。"""
        log.append("attempt %s notes=%d" % (sid, len(notes_ or [])))
        return os.path.join("build", "realpath-attempts", "%s-<fake>.json" % sid)

    def reachable():
        return True

    def own_app():
        return facts.get("own_app", OWN_APP)

    def wait_for(pred, secs, step=0.25):
        """求值 pred()，真了就回它，用尽了回 None；一秒真时间都不占，可钟是真的在走。

        `secs` 是**秒**，不是次数：每求值一次 `pred()` 之前把这条局面的钟往前拨 `step`。求值的时刻是进来的
        那一刻（一次都不拨，所以第一次求值在 t+0）、之后每 `step` 秒一次，最后一次求值在不晚于 `secs` 的
        那一刻——最多 `floor(secs / step) + 1` 次（`secs` 是 `step` 的整数倍时就是 `secs / step + 1` 次；
        那个 1e-9 是给 15/0.3 这种除不尽的商兜底的，不是放宽时间界）。`secs` 已经过了也还看最后一眼。
        """
        step = float(step) or 0.25
        secs = max(0.0, float(secs))
        k = 0
        while True:
            v = pred()
            if v:
                return v
            k += 1
            if k * step > secs + 1e-9:
                return None
            clock.sleep(step)

    def run_tool(tool, text, timeout=60):
        out = reply_of(text)
        note("run_tool", tool, text, "->", len(out or ""), "chars")
        return {"secs": 0.0, "out": out, "code": 0}

    def start_tool(tool, text, timeout=60):
        out = reply_of(text)
        note("start_tool", tool, text)
        return FakeProc(out, running, facts.get("proc_done_at"), clock)

    def finish(proc, timeout=120):
        note("finish", len(proc.out or ""), "chars")
        return proc.out

    def play_pause(want, pkg=KTCP):
        """默认回 `want`（按下去就成了）。

        `resume_stuck: True` 的局面：按「继续放」那次回 `"key -> still paused"`，音频服务那边也就一直是 paused，
        和真 probe 里「键发出去了、可播放器没动」一模一样——那种时刻 `wait_for_show` 不许报「正片在放」。
        别的方向照常成功：不写这个局面，暂停还是按得下去的。
        """
        note("play_pause", want, pkg)
        if facts.get("resume_stuck") and want == "started":
            return "key -> still paused"
        return want

    def wait_for_show(notes, cap_s=240, quiet_s=100, every_s=15, duration_s=EPISODE_S):
        """等广告放完：按局面给的帧序列走，读帧用的是**真的** screen.py，钟是真的在走（sleep 只拨钟）。

        和真 probe 一样只信画面：`screen.position()` 读得出进度条才算正片在放（标题在广告页里也有，
        2026-11-14 12:08 真机截到的那条雀巢广告左上角就写着「别无选择 第5集」，所以标题一条不算数）。
        钟到了 `show_at` 可那一帧读不出位置，就接着看，看到 cap_s 为止；局面不写 `show_at`（或者写 None）
        就是广告一直没放完，走满 cap_s 秒返回 None，并把 probe.wait_for_show 那句「广告一直没放完…」记进 notes。

        这一版和真 probe 逐条对齐（改一处必须改两处）：
          · 截图失败（`FakeCaptureFailed`）→ 记一句、下一圈再看，不算错；
          · 继续放那一下失败（`resume_stuck` 局面回 "key -> still paused"）→ 重试一次，还不行就记
            「恢复键没按下去」、这一屏不用、更不返回「正片在放」；
          · 进度条出现了、可填充太短（`screen.position` 的 why 说 short）→ notes 里写
            「进度条出现了，可填充只有 N px（约 M 秒）」，和「广告一直没放完」分开说。

        等广告时看到的帧走 `show_frame_seq`，**不**走场景自己的 `frame_seq`：等广告的这一两分钟里要看好几次
        画面，共用一条序列的话那几次就把场景自己截图的格子吃掉了，而「看几次」跟着 quiet_s / every_s 变——
        局面表就得跟着每一处重数格子。一条事实一条序列：等广告时看到什么写 show_frame_seq，
        场景自己截到什么写 frame_seq。

        静等的那 100 秒里不读音频服务：真 probe 每 5 秒读一次，只为在 notes 里记下广告→正片那一下的 none，
        判据一个字都不看它；假 probe 里读二十次只会把局面表里 audio_seq 的格子吃光。
        """
        at, t0 = facts.get("show_at"), clock.time()
        waited = 0.0
        while waited < quiet_s:                      # 静等：一个键都不按，只把钟往前拨
            clock.sleep(5.0)
            waited = clock.time() - t0
        n, saw_short = 0, False
        while True:
            left = cap_s - (clock.time() - t0)
            if left <= 0:
                break
            clock.sleep(min(every_s, left))
            n += 1
            try:
                png = show_frames()
            except FakeCaptureFailed as e:
                note("wait_for_show 第 %d 次看画面 -> CaptureFailed" % n)
                notes.append("第 %d 次（%.0f 秒）这一屏没截成：%s；不算错，下一圈再看"
                             % (n, clock.time() - t0, e))
                continue
            why = {}
            pos = screen.position(png, duration_s, why=why)
            titled = screen.has_show_text(png)
            note("wait_for_show 第 %d 次看画面 ->" % n, os.path.basename(png),
                 "有进度条" if pos else "没有进度条", "标题在" if titled else "标题不在")
            notes.append("第 %d 次看画面（%.0f 秒）：进度条%s；左上角那行字%s"
                         % (n, clock.time() - t0,
                            ("比例 %.4f（约 %d 秒）" % (pos["ratio"], int(round(pos["s"])))) if pos else "读不到",
                            "在" if titled else "不在"))
            back = play_pause("started")
            if "no key" in back or "still" in back:
                back = play_pause("started")
            resumed = not ("no key" in back or "still" in back)
            if not resumed:
                notes.append("第 %d 次（%.0f 秒）恢复键没按下去：%s" % (n, clock.time() - t0, back))
            if at is not None and clock.time() - t0 >= float(at) and pos is not None:
                if not resumed:
                    notes.append("进度条读得出，可恢复键没按下去，这一屏算不了，这次验不了")
                    return None
                notes.append("正片在放了（第 %d 次看，%.0f 秒）：进度条读得出比例" % (n, clock.time() - t0))
                return {"at": clock.time() - t0, "frame": png, "position": pos, "titled": titled,
                        "resumed_at": clock.time()}
            if why.get("reason") == "short" and not saw_short:
                saw_short = True
                notes.append("进度条出现了，可填充只有 %d px（约 %d 秒）"
                             % (why["fill_px"], round(why["fill_px"] * duration_s / 675.0)))
        if not saw_short:
            notes.append("广告一直没放完（等了 %.0f 秒，画面上一次都没出现进度条），这次验不了"
                         % (clock.time() - t0))
        return None

    def shot(name):
        note("shot", name)
        return os.path.join("probe-out", name)            # 假文件名：一个字节都不写

    def evidence(sid, ok, notes_=None, shots=None, by=None):
        log.append("evidence %s ok=%r notes=%d shots=%d" % (sid, ok, len(notes_ or []), len(shots or [])))

    def sh(cmd, timeout=20):
        note("sh", cmd[:60])
        return ""

    def say(*a):
        return note("say", *a)

    def key(code):
        note("key", code)

    def home():
        note("home")

    def set_volume(v):
        note("set_volume", v)

    m.refuse, m.resumed, m.awake, m.audio_state, m.audio_events = refuse, resumed_, awake_, audio_state, audio_events
    m.play_pause, m.run_tool, m.start_tool, m.finish, m.shot = play_pause, run_tool, start_tool, finish, shot
    m.ui_text, m.hud, m.setting, m.memory, m.own_app, m.home = ui_text, hud_, setting, memory_, own_app, home
    m.key, m.sh, m.say, m.set_volume, m.tv_clock, m.wait_for = key, sh, say, set_volume, tv_clock, wait_for
    m.evidence, m.reachable, m.wait_for_show = evidence, reachable, wait_for_show
    m.tv_datetime, m.usage_events, m.attempt = tv_datetime, usage_events, attempt
    m.frame = frame
    m.session = session

    class Restore(FakeRestore):
        """场景的收尾对象：started(pkg) 和 close() 两个方法，什么都不真做。"""

        def __init__(self, apps=(), leave_home=True):
            FakeRestore.__init__(self, log)
            self.apps, self.leave_home = list(apps), leave_home

    m.Restore = Restore
    return m


# ---- 局面表：每条场景至少一条「该过」和一条「该不过」 --------------------------------------------------------
# 三个键要挨在一起（scenario、name、expect）：check.py 是文本扫这三个键的。

CASES = [
    # black-screen-home：说完「屏幕黑了」，桌面（orrery）要回到最上面。
    {"scenario": "black-screen-home", "name": "黑屏以后桌面回到最上面", "expect": True,
     "facts": {"resumed_seq": [OWN_ACT, OWN_ACT, HOME_ACT],       # 起来 → 记 notes → 说完以后
               "tool_reply": "reply: 好的，已经回到桌面"}},
    {"scenario": "black-screen-home", "name": "说完还停在黑屏的测试应用上", "expect": False,
     "facts": {"resumed_seq": [OWN_ACT, OWN_ACT, OWN_ACT],
               "tool_reply": "reply: 我看看"}},
    {"scenario": "black-screen-home", "name": "那个黑屏应用根本没起来", "expect": False,
     "facts": {"resumed_seq": [HOME_ACT],                          # 8 秒里一次都没到过前面
               "tool_reply": "reply: 屏幕黑了吗"}},

    # panel-while-speaking：静音任务跑起来 3 秒时，面板的窗口还在、任务还在跑。
    {"scenario": "panel-while-speaking", "name": "说话时面板窗口在、任务还在跑", "expect": True,
     "facts": {"hud_seq": [[HUD_WIN]], "proc_running": True,
               "ui_text_seq": ["电视已经开了 3 小时 12 分钟"],
               "tool_reply": "reply: 电视开了 3 小时 12 分钟"}},
    {"scenario": "panel-while-speaking", "name": "窗口表里一个面板窗口都没有", "expect": False,
     "facts": {"hud_seq": [[]], "proc_running": True, "tool_reply": "reply: 电视开了 3 小时"}},
    {"scenario": "panel-while-speaking", "name": "三秒时任务已经跑完了", "expect": False,
     "facts": {"hud_seq": [[HUD_WIN]], "proc_running": False, "tool_reply": "reply: 电视开了 3 小时"}},

    # panel-self-check：面板窗口真的出现过，而且 self_check 的回答里说到面板。
    {"scenario": "panel-self-check", "name": "窗口出现过、回答里说到面板", "expect": True,
     "facts": {"hud_seq": [[HUD_WIN]],
               "tool_reply": "reply: 面板没有打开：读屏服务被系统回收了\n- 自检：面板窗口 0 个"}},
    {"scenario": "panel-self-check", "name": "窗口有，可回答里没有面板两个字", "expect": False,
     "facts": {"hud_seq": [[HUD_WIN]],
               "tool_reply": "reply: 一切正常\n- 自检：读屏服务在跑"}},
    {"scenario": "panel-self-check", "name": "窗口一次都没出现", "expect": False,
     "facts": {"hud_seq": [[]],
               "tool_reply": "reply: 面板没有打开\n- 自检：面板窗口 0 个"}},

    # hint-card-not-a-switch：一句只有「提示」的话，前后读到的 hints 要一样（读不出来不算过）。
    {"scenario": "hint-card-not-a-switch", "name": "前后两次 hints 都是 true", "expect": True,
     "facts": {"memory_seq": [{"hints": True}, {"hints": True}],
               "tool_reply": "reply: 我记住你说的话了"}},
    {"scenario": "hint-card-not-a-switch", "name": "提示被当成了开关，hints 从 true 变 false", "expect": False,
     "facts": {"memory_seq": [{"hints": True}, {"hints": False}],
               "tool_reply": "reply: 好的，以后不给提示了"}},
    {"scenario": "hint-card-not-a-switch", "name": "hints 从 MEMORY 里读不出来", "expect": False,
     "facts": {"memory_seq": [{}, {}], "tool_reply": "reply: 好的"}},

    # episode-open：画面左上角那一行字是「别无选择 第5集」才算开对了；正片在放可那行字对不上是开错了集；
    # 广告一直没放完（画面上既没有进度条、也没有剧集名）是验不了。
    # 判据读的是 wait_for_show 拿回来的那一帧（show_frame_seq），重看的那两次走场景自己的 frame_seq。
    {"scenario": "episode-open", "name": "广告放完，画面左上角写着「别无选择 第5集」", "expect": True,
     "facts": {"resumed_seq": [PLAYER_ACT], "audio_seq": ["started"],
               "show_at": 124, "show_frame_seq": [AD_FRAME, SHOW_FRAME],
               "tool_reply": "reply: 好的，看别无选择第5集"}},
    # 广告卡片把进度条盖住了，右上还有「广告 | 按右键 关闭」，左上角那行字却在——2026-11-14 12:08 真机上
    # 就是这样：把「有标题」当成正片在放，于是在广告里说了「暂停」、判了「通过」。现在标题不算数，这条是验不了。
    {"scenario": "episode-open", "name": "广告页：进度条读不到、标题在（标题在广告里就有），验不了", "expect": None,
     "facts": {"resumed_seq": [PLAYER_ACT], "audio_seq": ["started"],
               "show_at": 124, "show_frame_seq": [AD_FRAME, AD_TITLED],
               "tool_reply": "reply: 好的，看别无选择第5集"}},
    # 开错了集：进度条读得出比例（正片确实在放），可左上角那一块不是那行字——看了 3 次都不是。
    {"scenario": "episode-open", "name": "正片在放，可左上角那行字不是「别无选择 第5集」", "expect": False,
     "facts": {"resumed_seq": [PLAYER_ACT], "audio_seq": ["started"],
               "show_at": 124, "show_frame_seq": [AD_FRAME, WRONG_EP],
               "frame_seq": [WRONG_EP, WRONG_EP],
               "tool_reply": "reply: 好的"}},
    {"scenario": "episode-open", "name": "广告一直没放完：画面上既没有进度条也没有剧集名", "expect": None,
     "facts": {"resumed_seq": [PLAYER_ACT], "audio_seq": ["started"],
               "show_frame_seq": [AD_FRAME],                       # 没有 show_at：广告一直没放完
               "tool_reply": "reply: 好的"}},
    {"scenario": "episode-open", "name": "播放页根本没起来", "expect": False,
     "facts": {"resumed_seq": [HOME_ACT], "audio_seq": [""],
               "tool_reply": "reply: 我没找到别无选择"}},
    # 截图失败不是判据失败（这一条走 main()，不是走 check()）：广告放完了、正片在放，可左上角一直读不到
    # 那行字，脚本要截一屏再看——就在这时候 screencap 卡住。main() 得把 CaptureFailed 接住、记一次
    # attempt、说一句「这次验不了」、回 3；写成一份「开错了集」的失败报告比没有还糟。
    # 判的是三件事：回 3、attempt 恰好一次、evidence() 一次都没调（没验过就不写证据文件）。
    {"scenario": "episode-open", "name": "截不到图：main() 接住 CaptureFailed，记一次 attempt、不写证据、回 3",
     "expect": None, "entry": "main",
     "facts": {"resumed_seq": [PLAYER_ACT], "audio_seq": ["started"],
               "show_at": 124, "show_frame_seq": [AD_FRAME, WRONG_EP],
               "frame_raises": "capture",                          # 场景自己 p.frame() 的那一路全失败
               "tool_reply": "reply: 好的"}},

    # pause-through-xiaoai：这一集是场景自己开的（播放页到最前面、音频服务说在放、广告放完），说「暂停」之前在放、
    # 说完 3 秒 paused。audio_seq 三格：开播那次 wait_for 求值一次、说「暂停」之前记 before、说完 3 秒读 after。
    {"scenario": "pause-through-xiaoai", "name": "自己开起来一集，三秒后音频服务说 paused", "expect": True,
     "facts": {"resumed_seq": [PLAYER_ACT],                              # 开播那次 wait_for 要读到播放页
               "audio_seq": ["started", "started", "paused"],
               "show_at": 124, "show_frame_seq": [AD_FRAME, SHOW_FRAME],
               "tool_replies": {OPEN5: "reply: 好的，看别无选择第5集", PAUSE: "reply: 已暂停"},
               "audio_events": ["02:40:11 player piid: state:paused"]}},
    {"scenario": "pause-through-xiaoai", "name": "小爱暂停了，星仪的键 164 ms 后又放回去", "expect": False,
     "facts": {"resumed_seq": [PLAYER_ACT],
               "audio_seq": ["started", "started", "started"],
               "show_at": 124, "show_frame_seq": [AD_FRAME, SHOW_FRAME],
               "tool_replies": {OPEN5: "reply: 好的，看别无选择第5集", PAUSE: "reply: 已暂停"},
               "audio_events": ["02:40:11 player piid: state:paused", "02:40:11 player piid: state:started"]}},
    # 恢复键按不下去：广告放完了，可 wait_for_show 里「继续放」那一键回了 "key -> still paused"（重试一次
    # 也一样），音频服务那边就一直没在放。这时候它不许报「正片在放」——那一屏算不了，回 None；pause 调用方
    # 拿到 None 记一次 attempt、回 3。上一版会把 before 读成 paused、判一条「小爱和星仪互相抵消」的失败。
    {"scenario": "pause-through-xiaoai", "name": "恢复键按不下去：wait_for_show 不报「正片在放」，验不了",
     "expect": None,
     "facts": {"resumed_seq": [PLAYER_ACT],
               "audio_seq": ["started", "started", "started"],
               "show_at": 124, "show_frame_seq": [AD_FRAME, SHOW_FRAME],
               "resume_stuck": True,
               "tool_replies": {OPEN5: "reply: 好的，看别无选择第5集", PAUSE: "reply: 已暂停"}}},
    # 广告一直没放完：在广告里说「暂停」验的不是正片，这次验不了（不写证据，不是一份失败报告）。
    {"scenario": "pause-through-xiaoai", "name": "广告一直没放完：在广告里说「暂停」验不了", "expect": None,
     "facts": {"resumed_seq": [PLAYER_ACT],
               "audio_seq": ["started", "started", "started"],
               "show_frame_seq": [AD_FRAME],                           # 没有 show_at：广告一直没放完
               "tool_replies": {OPEN5: "reply: 好的，看别无选择第5集", PAUSE: "reply: 已暂停"}}},
    # 12:08 真机骗过判据的那一幕：广告放完了（show_at 到了），画面左上角写着剧名，可进度条读不到——
    # 标题在广告页里就有，不能当正片。上一版会在这里返回「正片在放」，然后在广告里说「暂停」、判「通过」。
    {"scenario": "pause-through-xiaoai", "name": "广告页标题在、进度条读不到：标题不算正片", "expect": None,
     "facts": {"resumed_seq": [PLAYER_ACT],
               "audio_seq": ["started", "started", "started"],
               "show_at": 1, "show_frame_seq": [AD_TITLED, AD_TITLED],  # 等广告超时，一帧真画面都没看到
               "tool_replies": {OPEN5: "reply: 好的，看别无选择第5集", PAUSE: "reply: 已暂停"}}},
    # 开播没起来：25 秒里播放页一次都没到最前面，音频服务一次都没读到（脚本在这里就退了，不去说「暂停」）。
    {"scenario": "pause-through-xiaoai", "name": "自己开的一集没起来，播放页一直没到最前面", "expect": False,
     "facts": {"resumed_seq": [HOME_ACT],
               "audio_seq": ["started"],
               "tool_replies": {OPEN5: "reply: 我没找到别无选择"}}},

    # karaoke-switch：两次回答都是 done/answered，话里带着「已切到伴奏」「已切回原唱」（只看最后三行）。
    {"scenario": "karaoke-switch", "name": "两次切换都说出了那句话", "expect": True,
     "facts": {"audio_seq": ["started"],
               "tool_replies": {SONG: "reply: 好的，放汪苏泷的晴", ACCOMP: "reply: 已切到伴奏",
                                ORIG: "reply: 已切回原唱"}}},
    {"scenario": "karaoke-switch", "name": "切回原唱那句没说「已切回原唱」", "expect": False,
     "facts": {"audio_seq": ["started"],
               "tool_replies": {SONG: "reply: 好的", ACCOMP: "reply: 已切到伴奏", ORIG: "reply: 好的"}}},
    {"scenario": "karaoke-switch", "name": "伴奏那句被埋在前面几行里，最后三行没有", "expect": False,
     "facts": {"audio_seq": ["started"],
               "tool_replies": {SONG: "reply: 好的", ORIG: "reply: 已切回原唱",
                                ACCOMP: "reply: 已切到伴奏\n- 试了播放器\n- 试了媒体键\n- 试了遥控器\n- 都没成"}}},
    {"scenario": "karaoke-switch", "name": "放歌之后音频服务没说在放", "expect": False,
     "facts": {"audio_seq": [""],
               "tool_replies": {ACCOMP: "reply: 已切到伴奏", ORIG: "reply: 已切回原唱"}}},

    # next-song-through-xiaoai：钟的走法——开播那次 wait_for 在第 0 秒读到在放，同一首多放 10 秒，第 10 秒说「下一首」，
    # proc_done_at 16 = 第 16 秒说完，之后再读 15 秒（读到第 31 秒）。session 全用时间门写：每 0.3 秒读一次，数格子数不过来。
    {"scenario": "next-song-through-xiaoai", "name": "小爱的切歌到了：江南换成可惜没如果，之后一直在放", "expect": True,
     "facts": {"session_seq": [{"at": 0, "v": song(3, JIANGNAN)},
                               {"at": 12.4, "v": song(10, JIANGNAN)},     # 正在切下一首
                               {"at": 12.8, "v": song(6, "")},            # 缓冲、标题还没上来：不算一首
                               {"at": 13.2, "v": song(3, KEXI)}],
               "proc_done_at": 16,
               "tool_replies": {LJJ: "reply: 好的，放林俊杰的歌", NEXT: "reply: 好的，下一首"}}},
    {"scenario": "next-song-through-xiaoai", "name": "小爱的那一下晚到了（说完 6 秒才换），还是只换一首", "expect": True,
     "facts": {"session_seq": [{"at": 0, "v": song(3, JIANGNAN)}, {"at": 22.0, "v": song(3, KEXI)}],
               "proc_done_at": 16,
               "tool_replies": {LJJ: "reply: 好的", NEXT: "reply: 小爱的那一下切过去了，星仪没有再切"}}},
    {"scenario": "next-song-through-xiaoai", "name": "11-16 的样子：说完 15 秒还是江南", "expect": False,
     "facts": {"session_seq": [song(3, JIANGNAN)],
               "proc_done_at": 16,
               "tool_replies": {LJJ: "reply: 好的", NEXT: "reply: 小爱已经切过了，现在还是网易云在放林俊杰的《江南》"}}},
    {"scenario": "next-song-through-xiaoai", "name": "多跳了：江南 → 可惜没如果 → 修炼爱情", "expect": False,
     "facts": {"session_seq": [{"at": 0, "v": song(3, JIANGNAN)}, {"at": 12.5, "v": song(3, KEXI)},
                               {"at": 14.5, "v": song(3, XIULIAN)}],
               "proc_done_at": 16,
               "tool_replies": {LJJ: "reply: 好的", NEXT: "reply: 已切到下一首"}}},
    # 第二下落在说完 12 秒以后：只读到说完那一刻的判据看不见它，读满 15 秒才看得见。
    {"scenario": "next-song-through-xiaoai", "name": "多跳了：第二下在说完 12 秒后才到", "expect": False,
     "facts": {"session_seq": [{"at": 0, "v": song(3, JIANGNAN)}, {"at": 12.5, "v": song(3, KEXI)},
                               {"at": 28.0, "v": song(3, XIULIAN)}],
               "proc_done_at": 16,
               "tool_replies": {LJJ: "reply: 好的", NEXT: "reply: 已切到下一首"}}},
    {"scenario": "next-song-through-xiaoai", "name": "开播没起来：25 秒里 session 一直不是 3", "expect": False,
     "facts": {"session_seq": [song(2, JIANGNAN)],
               "proc_done_at": 16,
               "tool_replies": {LJJ: "reply: 我没找到林俊杰的歌"}}},
    {"scenario": "next-song-through-xiaoai", "name": "换了一首以后网易云停了（state 2）", "expect": False,
     "facts": {"session_seq": [{"at": 0, "v": song(3, JIANGNAN)}, {"at": 13.0, "v": song(3, KEXI)},
                               {"at": 20.0, "v": song(2, KEXI)}],
               "proc_done_at": 16,
               "tool_replies": {LJJ: "reply: 好的", NEXT: "reply: 好的"}}},
    {"scenario": "next-song-through-xiaoai", "name": "换了一首以后网易云的 session 没了", "expect": False,
     "facts": {"session_seq": [{"at": 0, "v": song(3, JIANGNAN)}, {"at": 13.0, "v": song(3, KEXI)},
                               {"at": 20.0, "v": None}],
               "proc_done_at": 16,
               "tool_replies": {LJJ: "reply: 好的", NEXT: "reply: 好的"}}},
    {"scenario": "next-song-through-xiaoai", "name": "说之前 session 没有标题：看不出换没换，验不了", "expect": None,
     "facts": {"session_seq": [song(3, "")],
               "proc_done_at": 16,
               "tool_replies": {LJJ: "reply: 好的"}}},

    # seek-one-minute：广告放完以后，回答里有「已快进」，而且两次读出来的秒数差了 45–90 秒。
    # 两次是场景自己截的（frame_seq：before / after）；等广告时看到什么走 show_frame_seq。
    # 秒数是 screen.position() 从进度条的比例读出来的（331.6 / 398.8），不是浮层上的字。
    {"scenario": "seek-one-minute", "name": "两次读出来的进度差了 67 秒", "expect": True,
     "facts": {"resumed_seq": [PLAYER_ACT], "audio_seq": ["started"],
               "show_at": 124, "show_frame_seq": [AD_FRAME, SHOW_FRAME],
               "frame_seq": [SHOW_FRAME, SHOW_LATER],
               "tool_replies": {OPEN5: "reply: 好的", SEEK: "reply: 已快进 60 秒"}}},
    {"scenario": "seek-one-minute", "name": "两次读出来的进度一模一样", "expect": False,
     "facts": {"resumed_seq": [PLAYER_ACT], "audio_seq": ["started"],
               "show_at": 124, "show_frame_seq": [AD_FRAME, SHOW_FRAME],
               "frame_seq": [SHOW_FRAME, SHOW_FRAME],
               "tool_replies": {OPEN5: "reply: 好的", SEEK: "reply: 已快进 60 秒"}}},
    {"scenario": "seek-one-minute", "name": "进度差了一分钟，可回答里没有「已快进」", "expect": False,
     "facts": {"resumed_seq": [PLAYER_ACT], "audio_seq": ["started"],
               "show_at": 124, "show_frame_seq": [AD_FRAME, SHOW_FRAME],
               "frame_seq": [SHOW_FRAME, SHOW_LATER],
               "tool_replies": {OPEN5: "reply: 好的", SEEK: "reply: 好的，我试试"}}},
    # 等广告时画面上已经有进度条（广告放完了），可场景自己那一次截的帧上读不到进度条：读不到就不猜，算不过。
    {"scenario": "seek-one-minute", "name": "自己截的那一帧上读不到进度条", "expect": False,
     "facts": {"resumed_seq": [PLAYER_ACT], "audio_seq": ["started"],
               "show_at": 124, "show_frame_seq": [AD_FRAME, SHOW_FRAME],
               "frame_seq": [AD_FRAME, AD_FRAME],
               "tool_replies": {OPEN5: "reply: 好的", SEEK: "reply: 已快进 60 秒"}}},
    # 广告一直没放完：在广告里快进验的不是正片，这次验不了（不写证据，不是一份失败报告）。
    {"scenario": "seek-one-minute", "name": "广告一直没放完：在广告里快进验不了", "expect": None,
     "facts": {"resumed_seq": [PLAYER_ACT], "audio_seq": ["started"],
               "show_frame_seq": [AD_FRAME],                           # 没有 show_at：广告一直没放完
               "frame_seq": [AD_FRAME, AD_FRAME],
               "tool_replies": {OPEN5: "reply: 好的", SEEK: "reply: 已快进 60 秒"}}},

    # sleeping-episode：待机时说那句话，15 秒内屏幕要亮，45 秒内播放页要在最前面。
    {"scenario": "sleeping-episode", "name": "屏幕亮了、播放页到了最前面", "expect": True,
     "facts": {"awake_seq": [True], "resumed_seq": [PLAYER_ACT],
               "tool_reply": "reply: 好的，看别无选择第5集"}},
    {"scenario": "sleeping-episode", "name": "屏幕一直没亮", "expect": False,
     "facts": {"awake_seq": [False], "resumed_seq": [PLAYER_ACT], "tool_reply": "reply: 好的"}},
    {"scenario": "sleeping-episode", "name": "亮了，可播放页一直没到最前面", "expect": False,
     "facts": {"awake_seq": [True], "resumed_seq": [HOME_ACT], "tool_reply": "reply: 好的"}},
    # 「发生了，可是太晚」这一档只有时间门写得出：判据的 15 秒 / 45 秒是 wait_for 自己的放弃时刻，
    # 亮没亮、播放页在不在前这两件事，序列里都成立——晚的就是晚的。
    {"scenario": "sleeping-episode", "name": "第 16 秒才亮（太晚）", "expect": False,
     "facts": {"awake_seq": [{"at": 0, "v": False}, {"at": 16.0, "v": True}],   # 判据 15 秒：差一秒
               "resumed_seq": [PLAYER_ACT], "tool_reply": "reply: 好的"}},
    {"scenario": "sleeping-episode", "name": "第 3 秒就亮了", "expect": True,
     "facts": {"awake_seq": [{"at": 0, "v": False}, {"at": 3.0, "v": True}],     # 11-12 11:06 那次的时序
               "resumed_seq": [{"at": 0, "v": HOME_ACT}, {"at": 12.0, "v": PLAYER_ACT}],
               "tool_reply": "reply: 好的，看别无选择第5集"}},
    {"scenario": "sleeping-episode", "name": "亮了，可播放页第 60 秒才到（太晚）", "expect": False,
     "facts": {"awake_seq": [True],                                             # 一说就亮，亮这一半没问题
               "resumed_seq": [{"at": 0, "v": HOME_ACT}, {"at": 60.0, "v": PLAYER_ACT}],   # 判据 45 秒
               "tool_reply": "reply: 好的"}},

    # standby-home：判据读系统自己按毫秒记下的事件顺序（dumpsys usagestats），不再每 2 秒读 mResumedActivity——
    # 熄屏时所有界面都暂停，那个字段是空的；2026-11-14 10:39 浏览器只浮上来 1 秒（10:39:05 上、10:39:06 退），
    # 第一次读（熄屏后 2 秒）就已经错过，场景报「守护没事可做」，而守护 28 秒就把桌面放回去了。所以局面给的是
    # usage_events 那份事件表；resumed 只剩场景自己叫的那几次（等浏览器起来 + 两条 notes）。
    # 第一条局面用的是 fixture 原文过 parse_usage 造出来的真时序，一个字都没手抄。
    {"scenario": "standby-home", "name": "真时序：浏览器 10:39:05 浮上来、桌面 10:39:33 回来", "expect": True,
     "facts": {"awake_seq": [True, False], "resumed_seq": [BROWSER_ACT],
               "tv_datetime": SINCE_1114, "usage_events": REAL_1114}},
    # 下面两条的浮上来记在熄屏**后一秒**（系统偶尔晚一两秒才记上），所以它们逼的是 60 秒那道界，不是
    # 「同一秒里排在熄屏前面」那条——那一条由上面那条真时序的局面守着（去掉窗口下界的 - 1 s，只有它会红）。
    {"scenario": "standby-home", "name": "浮上来了，桌面只在重新点亮时（熄屏后 102 秒）才回来", "expect": False,
     "facts": {"awake_seq": [True, False], "resumed_seq": [BROWSER_ACT],
               "tv_datetime": "2026-11-14 10:59:00",
               "usage_events": usage(("2026-11-14 11:00:00", SCREEN_OFF, "android"),
                                     ("2026-11-14 11:00:01", FG, BROWSER),
                                     ("2026-11-14 11:00:02", BG, BROWSER),
                                     ("2026-11-14 11:01:42", FG, HOME_APP))}},
    # 逼到 60 秒那道界上：62 秒和 60 秒差两秒，判据松一点这条就绿了。
    {"scenario": "standby-home", "name": "浮上来了，桌面熄屏后 62 秒才回来", "expect": False,
     "facts": {"awake_seq": [True, False], "resumed_seq": [BROWSER_ACT],
               "tv_datetime": "2026-11-14 10:59:00",
               "usage_events": usage(("2026-11-14 11:00:00", SCREEN_OFF, "android"),
                                     ("2026-11-14 11:00:01", FG, BROWSER),
                                     ("2026-11-14 11:00:02", BG, BROWSER),
                                     ("2026-11-14 11:01:02", FG, HOME_APP))}},
    {"scenario": "standby-home", "name": "熄屏后只有桌面留在最上面，浏览器没浮上来", "expect": None,
     "facts": {"awake_seq": [True, False], "resumed_seq": [BROWSER_ACT],
               "tv_datetime": "2026-11-14 10:59:00",
               "usage_events": usage(("2026-11-14 11:00:00", FG, HOME_APP),
                                     ("2026-11-14 11:00:00", SCREEN_OFF, "android"),
                                     ("2026-11-14 11:00:01", BG, HOME_APP))}},
    {"scenario": "standby-home", "name": "根本没有 SCREEN_NON_INTERACTIVE（屏幕没关成）", "expect": None,
     "facts": {"awake_seq": [True, False], "resumed_seq": [BROWSER_ACT],
               "tv_datetime": "2026-11-14 10:59:00",
               "usage_events": usage(("2026-11-14 11:00:00", FG, BROWSER),
                                     ("2026-11-14 11:00:20", FG, HOME_APP),
                                     ("2026-11-14 11:00:20", BG, BROWSER))}},
]


def word(v):
    """true / false / none —— ok 和 expect 都按这三个词比。"""
    return "none" if v is None else ("true" if v else "false")


def load(sid):
    """把场景脚本当模块载入；probe 已经在 sys.modules 里，载入的就是那个假的。"""
    path = os.path.join(SCEN, sid + ".py")
    spec = importlib.util.spec_from_file_location("rehearse_" + sid.replace("-", "_"), path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def run_main(case):
    """跑一条**走 main()** 的局面：截图失败要让场景以「这次验不了」退出（rc 3），不是一份失败报告。

    走 check() 的局面追不到这条路：main() 里那个 `except p.CaptureFailed` 是场景自己的，写在 check() 外面。
    所以这里把假 probe 塞进 sys.modules、把场景当 `__main__` 跑一遍，看三件事：
      · main() 回 3（验不了）——不是 0/1（判过了/判不过了）；
      · `p.attempt()` 记了一次，`p.evidence()` 一次都没有：没有验证就没有证据文件，回 3 也不留待办之外的残渣；
      · notes 里那句话说得出来（截图失败那一句），别只剩一个空数组。

    @return (ok_line, fail_line)，和 run_case 一样的形状。
    """
    sid, name = case["scenario"], case["name"]
    clock = Clock()
    facts = case.get("facts") or {}
    log = []
    fake = fake_probe(facts, log, clock)
    tried, proved = [], []
    fake.attempt = lambda i, n=None: (tried.append(i), log.append("attempt %s" % i))[0]
    fake.evidence = lambda i, ok, n=None, s=None, by=None: (
        proved.append((i, ok)), log.append("evidence %s ok=%r" % (i, ok)))[0]
    had = sys.modules.get("probe")
    sys.modules["probe"] = fake
    try:
        mod = load(sid)
        mod.time = clock
        rc = mod.main()
    except Exception as e:                      # main() 自己炸了：截图失败没接住，就是这条局面的红
        return None, "FAIL %s %s：main() 抛了 %s: %s（截图失败该被 CaptureFailed 接住、rc 3）" % (
            sid, name, type(e).__name__, e)
    finally:
        if had is None:
            sys.modules.pop("probe", None)
        else:
            sys.modules["probe"] = had
    if rc != 3:
        return None, "FAIL %s %s：截图全失败，main() 该回 3（验不了），实际 %r" % (sid, name, rc)
    if len(tried) != 1:
        return None, "FAIL %s %s：main() 该调一次 p.attempt()，实际 %d 次" % (sid, name, len(tried))
    if proved:
        return None, "FAIL %s %s：截不到图就没验过，不该写证据，实际写了 %d 次" % (sid, name, len(proved))
    return "ok %s %s（expect 3）" % (sid, name), None


def run_case(case):
    """跑一条局面。@return (ok_line, fail_line)：对了 ok_line 有内容、fail_line 是 None，反过来。"""
    if case.get("entry") == "main":
        return run_main(case)
    sid, name, expect = case["scenario"], case["name"], case["expect"]
    clock = Clock()                          # 这条局面自己的钟：假 probe 拨它，场景里的 time 也是它
    fake = fake_probe(case.get("facts") or {}, [], clock)
    had = sys.modules.get("probe")
    sys.modules["probe"] = fake
    try:
        mod = load(sid)
        mod.time = clock                      # 场景里的 time.sleep() 只拨钟，一秒真时间都不占
        ok, notes, shots = mod.check(fake.Restore())      # 收尾对象是假 probe 的那个，什么都不真做
    except Exception as e:                      # 判据自己炸了：也算这处不对，写清是哪条、炸在哪
        return None, "FAIL %s %s：check() 抛了 %s: %s" % (sid, name, type(e).__name__, e)
    finally:
        if had is None:
            sys.modules.pop("probe", None)
        else:
            sys.modules["probe"] = had
    if word(ok) != word(expect):
        tail = (notes[-1] if notes else "（一句 notes 都没有）")[:160]
        return None, ("FAIL %s %s：判据给的是 %s，这条局面要的是 %s；最后一句 notes：%s"
                      % (sid, name, word(ok), word(expect), tail))
    if not isinstance(shots, list) or not isinstance(notes, list):
        return None, "FAIL %s %s：check() 要回 (ok, notes, shots)，notes 和 shots 都得是数组" % (sid, name)
    return "ok %s %s（expect %s）" % (sid, name, word(expect)), None


def table_problems():
    """局面表自己先得说得清：名字不重复、expect 只能是 True / False / None、场景脚本在。"""
    out, seen = [], set()
    for c in CASES:
        name = c.get("name")
        if not isinstance(name, str) or not name:
            out.append("FAIL 局面表：有一条局面没有名字（报告里要能点名）")
            continue
        if name in seen:
            out.append("FAIL 局面表：局面名「%s」用了两次（每条局面要自己的名字）" % name)
        seen.add(name)
        if c.get("expect") not in (True, False, None):
            out.append("FAIL 局面表：局面「%s」的 expect 是 %r，只能是 True / False / None" % (name, c.get("expect")))
        if not os.path.isfile(os.path.join(SCEN, str(c.get("scenario")) + ".py")):
            out.append("FAIL 局面表：局面「%s」指着场景「%s」，可那个脚本不在" % (name, c.get("scenario")))
    return out


def snapshot(d):
    """一个目录里每个文件的（大小、改动时刻）；用来证明跑完 evidence/ 一个字节都没动。"""
    out = {}
    for base, _, names in os.walk(d):
        for n in names:
            f = os.path.join(base, n)
            try:
                st = os.stat(f)
            except OSError:
                continue
            out[os.path.relpath(f, d)] = (st.st_size, st.st_mtime_ns)
    return out


def real_probe_loaded():
    """真的 probe.py 有没有在这次跑里被 import 过——它一 import 就会去连 adb。"""
    real = os.path.normpath(os.path.join(HERE, "probe.py"))
    return any(os.path.normpath(str(getattr(m, "__file__", "") or "")) == real
               for m in list(sys.modules.values()))


def main(argv=None):
    before = snapshot(EVIDENCE)
    bad = table_problems()
    for line in bad:
        print(line)
    for case in CASES:
        ok_line, fail = run_case(case)
        print(ok_line or fail)
        if fail:
            bad.append(fail)
    if real_probe_loaded():
        bad.append("FAIL 这次跑里真的 probe.py 被 import 了：假 probe 没顶住")
        print(bad[-1])
    after = snapshot(EVIDENCE)
    if after != before:
        bad.append("FAIL tests/agent/evidence/ 被动了：这次跑一个字都不该写出去")
        print(bad[-1])
    if bad:
        print("rehearse: %d 处不对" % len(bad))
        return 1
    print("REHEARSE OK")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
