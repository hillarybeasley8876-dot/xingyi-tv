# -*- coding: utf-8 -*-
"""The real TV, driven through adb: every function here runs a command on the Xiaomi TV at 192.168.31.162:5555 — the
screen, keys, apps, the audio service and 星仪's own debug receiver. Nothing in this package is offline; the only part
CI runs is check.py, which reads files and never imports this module.

Moved in from voice/assistant-out/tmp/lead/k1111/session8.py and k1110/probe.py (2026-11-11) and trimmed to what the six
real-path scenarios need. Each scenario is its own script under scenarios/ with its own main(); there is no main() here.
"""
import json, os, re, subprocess, sys, time, urllib.request
from datetime import datetime, timedelta

from PIL import Image

ADB = "H:/tools/platform-tools/adb.exe"
DEV = "192.168.31.162:5555"
TV = "http://192.168.31.162:6095"        # the TV's own remote service: a key without `input keyevent`'s ~0.85 s
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
TESTS = os.path.join(ROOT, "tests", "agent")       # tvtask.py and xiaoai_say.py live here
EVIDENCE = os.path.join(TESTS, "evidence")         # the lead's directory: a scenario writes real-<id>.json into it
OUT = os.environ.get("REALPATH_OUT") or os.path.join(HERE, "probe-out")
R = "xyz.erapi0neer.xiaoaibridge/xyz.erapi0neer.xingyi.agent.tv.AgentDebug"
NETEASE, KTCP = "com.netease.cloudmusic.tv", "com.ktcp.video"
HOME_PKG, PLAYER = "orrery", "TVPlayerActivity"
IDLE_MIN = 20                           # a key or a touch newer than this is somebody using the TV
EPISODE_S = 2833                        # 「别无选择 第5集」的长度，tests/agent/fixtures/realpath/player/ground-truth.json
                                        # 里写着。进度条只能读比例，秒数要靠它换算——右下角那个 00:47:13 和进度
                                        # 是同一张图、同一套字，读不出来（见 screen.py 开头）。
T0 = time.time()
notes = []

if HERE not in sys.path:                # rehearse.py 按 spec 载入这个文件时，它自己的目录不一定在 sys.path 上
    sys.path.insert(0, HERE)
import screen                            # noqa: E402  读一帧截图的纯函数：进度条比例 + 左上角那行剧集名


def say(*a):
    line = "[%5.1f] " % (time.time() - T0) + " ".join(str(x) for x in a)
    print(line, flush=True)
    notes.append(line)
    return line


class CaptureFailed(OSError):
    """截一屏没截成：screencap 超时、pull 超时/非零、拉回来的不是一张能解的 png。

    为什么单开一个类、还是 OSError 的子孙：截图失败是**环境**的事（电视卡了、网断了、adb 掉了），不是判据
    说「这条不过」。场景得把它和「验出来不对」分开报——不然一次截图超时就会被写成一条 `ok:false` 的证据，
    跟「暂停没停住」混在一起。继承 OSError，原来 `except OSError` 的地方不用改也接得住。
    """


def sh(cmd, timeout=20):
    r = subprocess.run([ADB, "-s", DEV, "shell", cmd], capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", "replace")


def tv_clock():
    return sh("date +%H:%M:%S.%N").strip()[:12]


def tv_datetime():
    """The TV's own wall clock, `2026-11-14 10:39:05` — the form `dumpsys usagestats` writes its event times in, so a
    scenario can say 「从这一刻起的事件」 without converting anything."""
    return sh("date '+%Y-%m-%d %H:%M:%S'").strip()


def now_ms():
    """The TV's own epoch milliseconds — what FORGET_CHECKS --el since takes."""
    return int(sh("date +%s").strip() or "0") * 1000


def reachable():
    return "realpath-ok" in sh("echo realpath-ok", timeout=15)


def awake():
    return "mWakefulness=Awake" in sh("dumpsys power | grep -m1 mWakefulness=")


def resumed():
    m = re.search(r"mResumedActivity: ActivityRecord\{\S+ u0 (\S+) ",
                  sh("dumpsys activity activities | grep -m1 mResumedActivity"))
    return m.group(1) if m else ""


def idle_minutes():
    """How long ago the last key or touch was; -1 when the TV does not say."""
    m = re.search(r"mLastUserActivityTime=\d+ \((\d+) ms ago\)",
                  sh("dumpsys power | grep -m1 mLastUserActivityTime="))
    return int(m.group(1)) / 60000.0 if m else -1.0


def remote(key):
    t = time.time()
    try:
        with urllib.request.urlopen(TV + "/controller?action=keyevent&keycode=" + key, timeout=4) as r:
            body = r.read().decode("utf-8", "replace")
    except Exception as e:
        body = "ERROR " + repr(e)
    return (time.time() - t) * 1000, body.strip().replace("\n", " ")[:160]


def session(pkg=NETEASE):
    """The app's media session (state 3 = playing), or None when it keeps none. 云视听极光 keeps none."""
    d = sh("dumpsys media_session")
    i = d.find("package=" + pkg)
    if i < 0:
        return None
    block = d[i:i + 2500]
    st = re.search(r"state=PlaybackState \{state=(\d+), position=(\d+).*?actions=(\d+)", block, re.S)
    meta = re.search(r"metadata:size=\d+, description=([^\n]*)", block)
    return {"state": int(st.group(1)) if st else -1, "position": int(st.group(2)) if st else -1,
            "actions": int(st.group(3)) if st else -1, "meta": meta.group(1).strip() if meta else ""}


def app_uid(pkg):
    m = re.search(r"userId=(\d+)", sh("dumpsys package %s | grep -m1 userId=" % pkg))
    return m.group(1) if m else ""


def audio_state(pkg=KTCP):
    """started / paused / '' for the app's own media players, as `dumpsys audio` lists them inside `players:` — only
    the asked uid's USAGE_MEDIA lines, and only inside that block (the event log underneath says `started` for players
    that stopped minutes ago). 云视听极光 keeps no media session, so this is the only place its pause is visible."""
    uid = app_uid(pkg)
    out, inside = [], False
    for l in sh("dumpsys audio", timeout=25).splitlines():
        if l.strip() == "players:":
            inside = True
            continue
        if inside:
            if not l.strip():
                break
            m = re.search(r"type:(\S+) -- u/pid:(\d+)/\d+ -- state:(\w+) -- attr:AudioAttributes: usage=(\w+)", l)
            if m and m.group(2) == uid and "SoundPool" not in m.group(1) and m.group(4) == "USAGE_MEDIA":
                out.append(m.group(3))
    return "started" if "started" in out else "paused" if "paused" in out else ""


def audio_events(since, keep=("player piid", "new player", "releasing")):
    """The audio service's own event lines at or after HH:MM:SS — who acted on the player, and when. 2026-11-11 20:03:
    小爱的语音助手按了暂停、星仪的键 164 ms 后又按回去，两边互相抵消，只有这些行说得清是谁动的。"""
    out = []
    for l in sh("dumpsys audio", timeout=25).splitlines():
        m = re.match(r"\d\d-\d\d (\d\d:\d\d:\d\d):\d+ (.*)", l.strip())
        if m and m.group(1) >= since and any(k in m.group(2) for k in keep):
            out.append(m.group(1) + " " + m.group(2)[:110])
    return out


# `dumpsys usagestats` 的一行事件，像
#     time="2026-11-14 10:39:05" type=MOVE_TO_FOREGROUND package=com.phlox.tvwebbrowser class=… flags=0x0
USAGE_LINE = re.compile(r'time="(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)"\s+type=(\S+)(?:\s+package=(\S+))?')
USAGE_TYPES = ("MOVE_TO_FOREGROUND", "MOVE_TO_BACKGROUND", "SCREEN_INTERACTIVE", "SCREEN_NON_INTERACTIVE")


def parse_usage(text, since=None):
    """系统自己按毫秒记下的事件顺序里那四种（前台/后台/亮屏/熄屏），`time ≥ since` 的。

    保持原来的先后，一个字都不重排：同一秒里的先后就是系统记的先后，2026-11-14 10:39:05 浏览器浮上来那条排在
    SCREEN_NON_INTERACTIVE **前面**，判据要看的正是这个。一字不差的重复行去掉（整份 dump 里同一段偶尔印两遍）。

    `since` 是 `2026-11-14 10:38:45` 这样的字符串，和事件时间同一种写法，按字符串比就是按时间比；不给就是全要。
    纯函数：fixture 文件的文字直接喂给它就能测，一条 adb 都不碰。
    """
    out, seen = [], set()
    for line in (text or "").splitlines():
        s = line.strip()
        m = USAGE_LINE.search(s)
        if not m or m.group(2) not in USAGE_TYPES:
            continue
        at = m.group(1)
        if since and at < str(since):
            continue
        if s in seen:
            continue
        seen.add(s)
        out.append({"at": at, "type": m.group(2), "pkg": m.group(3) or ""})
    return out


def usage_events(since, timeout=60):
    """真机版：整份 `dumpsys usagestats` 很长，先在电视上按日期前缀 grep 掉不相关的行再拉回来，交给 parse_usage。

    连着第二天的日期一起 grep：一条场景的观察窗口只有一分多钟，可它可能正好跨过午夜，只认 `since` 那一天的话
    跨过去以后的事件会一条都拉不回来，而缺了事件看起来和「什么都没发生」一模一样。
    """
    days = [str(since or "")[:10]]
    try:
        days.append((datetime.strptime(days[0], "%Y-%m-%d") + timedelta(days=1)).strftime("%Y-%m-%d"))
    except ValueError:
        pass
    pat = "|".join('time="%s' % d for d in days)
    return parse_usage(sh("dumpsys usagestats | grep -E '%s'" % pat, timeout=timeout), since)


def shot(name):
    os.makedirs(OUT, exist_ok=True)
    sh("screencap -p /sdcard/probe.png", timeout=15)
    subprocess.run([ADB, "-s", DEV, "pull", "/sdcard/probe.png", os.path.join(OUT, name)], capture_output=True)
    sh("rm -f /sdcard/probe.png")
    say("screenshot", name)
    return os.path.join("probe-out", name)


def wait_for(pred, secs, step=0.25):
    end = time.time() + secs
    while time.time() < end:
        v = pred()
        if v:
            return v
        time.sleep(step)
    return None


def start_tool(tool, text, timeout=60):
    """A 星仪 task in the background: tvtask.py is the silent test source, xiaoai_say.py is the real path (the
    sentence goes through XiaoAi's own text entry, the way speech does)."""
    args = ["--timeout", str(timeout)] if tool == "tvtask.py" else ["--wait", str(timeout)]
    return subprocess.Popen([sys.executable, os.path.join(TESTS, tool), text] + args,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", errors="replace")


def finish(proc, timeout=120):
    """The task's own output, the last lines said; the process is killed rather than left behind."""
    try:
        out = proc.communicate(timeout=timeout)[0] or ""
    except subprocess.TimeoutExpired:
        proc.kill()
        out = proc.communicate()[0] or ""
    for l in out.splitlines()[-14:]:
        if l.strip():
            say("    " + l.strip()[:200])
    return out


def run_tool(tool, text, timeout=60):
    """One task, waited for. @return what it printed and how long it took."""
    t = time.time()
    proc = start_tool(tool, text, timeout)
    out = finish(proc, timeout + 40)
    secs = time.time() - t
    say("%s 「%s」 %.1f s" % (tool.split(".")[0], text, secs))
    return {"secs": secs, "out": out, "code": proc.returncode}


def state():
    """The STATE broadcast, parsed: the engine's own view (busy, the task, …); {} when it cannot be read.
    It does NOT carry hints — AgentEngine.state() never put them there. Use memory() for those (2026-11-12 22:47:
    hint-card-not-a-switch read hints from here, got None on both sides, and could not have passed however the TV
    behaved)."""
    out = sh("am broadcast -n %s -a xyz.erapi0neer.xingyi.agent.STATE" % R, timeout=30)
    m = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
    if not m:
        return {}
    try:
        return json.loads(m.group(1)).get("state") or {}
    except ValueError:
        return {}


def memory():
    """The MEMORY broadcast, parsed: facts, aliases, habits, notes, schedules and `hints` (what the 星仪 app's 记忆 page
    reads); {} when it cannot be read."""
    out = sh("am broadcast -n %s -a xyz.erapi0neer.xingyi.agent.MEMORY" % R, timeout=30)
    m = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
    if not m:
        return {}
    try:
        return json.loads(m.group(1)).get("data") or {}
    except ValueError:
        return {}


def setting(name):
    """A secure setting, as the TV has it."""
    return sh("settings get secure " + name, timeout=15).strip()


def hud():
    """The panel's own windows: Hud adds the panel as a TYPE_ACCESSIBILITY_OVERLAY view of the bridge process, so it
    is a window of xyz.erapi0neer.xiaoaibridge and nothing else on the TV is."""
    lines = sh("dumpsys window windows | grep -i xiaoaibridge", timeout=25).splitlines()
    return [l.strip() for l in lines if "Window{" in l]


def ui_text():
    """Every string the front window's view tree carries, or "" when it cannot be dumped.

    **在播放页上它永远是空串。** 2026-11-14 11:10 量清楚了：云视听极光的播放页整页是 SurfaceView 画的，
    `uiautomator dump` 在上面只给一个空节点——手写探针在正片里每 20 秒暂停一次、dump 一次，15 次全是
    `nodes 1 | texts 0 | descs 0`，可屏幕上明明写着 00:05:30、00:47:13、别无选择 第5集。

    所以：桌面、面板、设置页这些普通 view 组成的界面上用它没问题；要读播放页（进度、剧集名）就用
    `frame()` 截一屏再交给 `screen.position()` / `screen.has_show_text()`——那两样读的是像素。
    """
    out = sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml; rm -f /sdcard/ui.xml", timeout=40)
    return " ".join(t for t in re.findall(r'text="([^"]*)"', out) if t.strip())


def frame(tag="frame"):
    """电视上截一屏，`pull` 回本地一份 png，@return 那个 png 的路径。

    读播放页只能靠它（原因见 `ui_text()`）。落在 `build/realpath-frames/` 下，不进仓库；一次一张，
    文件名带时刻，所以一条场景里连着截好几屏也不会互相盖掉。

    **一张要用的图，三条都得过**：screencap 跑完没超时、pull 回来 rc=0、拉回来的东西 PIL 解得开。
    任何一条不过就抛 `CaptureFailed`——截不到就是截不到，不拿一张半截的图去读。

    为什么拿 `Image.open(...).verify()` 判，而不是拿「文件够不够大」判：真机那种全黑的帧（`black-screen-home`
    就是）只有 6 KB，按大小判会把它当坏图，一条本来该验「黑屏没黑在判据上」的场景会被误判。verify() 只回答
    「这个文件的头和块对不对」，跟画面黑不黑无关。坏文件在抛之前删掉，免得下一圈的 `frame()` 撞名读到它。
    """
    d = os.path.join(ROOT, "build", "realpath-frames")
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, "%s-%s.png" % (tag, datetime.now().strftime("%H%M%S-%f")))
    try:
        sh("screencap -p /sdcard/probe-frame.png", timeout=20)
    except subprocess.TimeoutExpired:
        raise CaptureFailed("screencap 20 秒没回来：电视那边卡住了")
    try:
        r = subprocess.run([ADB, "-s", DEV, "pull", "/sdcard/probe-frame.png", path],
                           capture_output=True, timeout=60)
    except subprocess.TimeoutExpired:
        try:
            os.remove(path)                     # pull 超时会留下半截文件，删掉
        except OSError:
            pass
        raise CaptureFailed("pull 60 秒没回来：图太大或者 adb 掉线了")
    sh("rm -f /sdcard/probe-frame.png")         # 清远端不占超时预算，失败也不要紧
    if r.returncode != 0:
        raise CaptureFailed("pull 回来 rc=%d：%s" % (
            r.returncode, r.stderr.decode("utf-8", "replace").strip()[:200] or "没有错误输出"))
    if not os.path.isfile(path):
        raise CaptureFailed("截图没拉回来：adb -s %s pull /sdcard/probe-frame.png %s" % (DEV, path))
    try:
        with Image.open(path) as im:
            im.verify()
    except Exception as e:                      # PIL 坏图抛的类不止一种（UnidentifiedImageError/OSError/…）
        try:
            os.remove(path)                     # 半截的图留着只会被下一圈当真的读
        except OSError:
            pass
        raise CaptureFailed("拉回来的不是一张完整的 png（%s）：%s" % (type(e).__name__, e))
    say("frame", os.path.relpath(path, ROOT).replace("\\", "/"))
    return path


def own_app():
    """The developer's own test app (its screen is black), read out of OwnApps.java so it cannot drift from the core."""
    try:
        with open(os.path.join(ROOT, "src", "xyz", "erapi0neer", "xingyi", "agent", "OwnApps.java"),
                  encoding="utf-8") as f:
            text = f.read()
    except OSError:
        return "xyz.erapi0neer.fluidlab"
    at = text.find("HIDDEN")
    pkgs = re.findall(r'"([a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+)"', text[at if at >= 0 else 0:])
    return pkgs[0] if pkgs else "xyz.erapi0neer.fluidlab"


def home():
    """HOME through the remote service; `input keyevent 3` when that did not bring the home screen up."""
    ms, body = remote("home")
    if wait_for(lambda: HOME_PKG in resumed(), 2.5, 0.25):
        return "6095 %.0f ms" % ms
    sh("input keyevent 3")
    return "input (6095 said %s)" % body[:60]


def volume():
    m = re.search(r"volume is (\d+)", sh("media volume --stream 3 --get"))
    return int(m.group(1)) if m else -1


def set_volume(v):
    if v >= 0:
        sh("media volume --stream 3 --set %d" % v)


def key(code):
    sh("input keyevent %d" % code)


def play_pause(want, pkg=KTCP):
    """Bring the app's player to `want` (paused / started) with the one key that is needed. Both keys are toggles in
    云视听极光, so a key sent when it is already in that state would undo it (2026-11-11 20:03)."""
    s = audio_state(pkg)
    if s == want:
        return "already " + want
    if PLAYER not in resumed():
        return "no key (front is %s, state %s)" % (resumed(), s or "none")
    key(127 if want == "paused" else 126)
    ok = wait_for(lambda: audio_state(pkg) == want, 4, 0.4)
    return "key -> " + (want if ok else "still " + (audio_state(pkg) or "none"))


def seen(pos, titled):
    """一帧截图读出来的那一句，给 notes 用：进度条读到什么、左上角那行字在不在。"""
    if pos is None:
        return "进度条读不到；左上角那行字%s" % ("在" if titled else "不在")
    where = "约 %d 秒" % int(round(pos["s"])) if pos["s"] is not None else "（没给总长，只有比例）"
    return "进度条比例 %.4f（%s）；左上角那行字%s" % (pos["ratio"], where, "在" if titled else "不在")


def wait_for_show(notes, cap_s=240, quiet_s=100, every_s=15, duration_s=EPISODE_S):
    """等广告放完、正片开始放。从「播放页在前、音频服务在放」那一刻起调它。

    为什么先静等 quiet_s 秒、一个键都不按：广告里按暂停可能把广告拉长，也可能根本不认；在广告里动手，等于把
    「正片能不能被暂停」验在广告上。11-12 03:16 那次手写探针 session7m.py 干等了 124 秒才动手，「暂停」「快进
    一分钟」两条才真的验到了正片；三条注册场景一条都不等，于是在广告里说「暂停」，广告能不能被暂停从来没人量过。

    为什么不靠音频服务判断广告完没完：广告和正片在 `dumpsys audio` 的 players 里都叫 started，音频服务分不出这两
    个（它只在广告结束那一下短暂地说 none）。

    为什么读画面而不读浮层上的字：2026-11-14 11:10 量清楚了——播放页整页是 SurfaceView 画的，
    `uiautomator dump` 在上面只给一个空节点（15 次全是 nodes 1 / texts 0），`ui_text()` 在播放页永远返回空串。
    这里原来等的是浮层文字里的 00:MM:SS，那东西从写出来那天就读不到：11-14 那一跑 pause-through-xiaoai
    等了 247 秒，notes 记「广告一直没放完」，可系统自己的记录说广告 11:04:13 就结束了，正片一直在放。
    现在每 every_s 秒暂停、截一屏、读像素，**只有进度条能证明正片在放**：
      · `screen.position()` 读得出进度条（比例乘 duration_s 就是秒数）——正片在放。
    标题证明不了。2026-11-14 12:08 真机截到的**广告页**左上角照样写着「别无选择 第5集」（雀巢咖啡那条，
    右上还有「广告 | 按右键 关闭」）；最早那一版把「有标题」也算正片在放，于是在广告里说了「暂停」、
    在音频服务真的停下以后判了「通过」——一份假证据，当场撤回了。`screen.has_show_text()` 只用来回答
    「这一集对不对」，不用来回答「正片开没开」。

    s713-2026-11-12.txt 的时间线（tvtask 说完 4.2 s，播放页 4.3 s 就到了最前面）：
        [ 74.8] tvtask 「我想看别无选择第5集」 4.2 s
        [194.7] after the ad: state none        ← 广告刚结束那一下，音频服务里这个应用一个播放器都没有
        [202.3]    overlay t-episode: key -> paused / key -> started
        [209.2] xiaoai_say 「暂停」 6.7 s
    广告放了大约 120 秒：quiet_s=100 秒静等，之后每 every_s 秒看一次画面，第一次看大约在第 115 秒。

    @return 正片在放时 {"at": 从调用起多少秒, "frame": 那一帧 png 的路径, "position": screen.position() 的结果
            （或 None）, "titled": 左上角那行字在不在, "resumed_at": 继续放的时刻（time.time() 的值）}；
            到 cap_s 还没看到时 None——往 notes 记一句为什么，这次验不了。没看到就是没看到，不猜。
    """
    t0, last, n, saw_short = time.time(), None, 0, False
    while time.time() - t0 < quiet_s:           # 静等：一个键都不按，只看音频服务变没变
        s = audio_state()
        if last is None:
            last = s                            # 第一次读是基准，不记（调用它的场景自己已经记过一句）
        elif s != last:
            notes.append("t+%.0f s 音频服务说 %s" % (time.time() - t0, s or "none"))
            last = s
        time.sleep(5)
    while True:
        left = cap_s - (time.time() - t0)
        if left <= 0:
            break
        time.sleep(min(every_s, left))
        n += 1
        got = play_pause("paused")              # 卡在广告→正片那一下会回 no key / still none：不算错，下一圈再看
        if "no key" in got or "still" in got:
            notes.append("第 %d 次（%.0f 秒）暂停键没按下去：%s；不算错，下一圈再看"
                         % (n, time.time() - t0, got))
        time.sleep(1.2)
        try:
            png = frame("wait-for-show-%d" % n)
        except CaptureFailed as e:
            notes.append("第 %d 次（%.0f 秒）这一屏没截成：%s；不算错，下一圈再看"
                         % (n, time.time() - t0, e))
            continue
        why, pos, titled = {}, screen.position(png, duration_s, why=why), screen.has_show_text(png)
        # 继续放：按不下去就再试一次。还按不下去就别拿 resumed_at 当「刚恢复」的时刻——场景用它算
        # 「两次暂停之间片子自己走了多久」，那一刻其实还在暂停，算出来的 walked 是假的。
        back = play_pause("started")
        if "no key" in back or "still" in back:
            back = play_pause("started")
        resumed = not ("no key" in back or "still" in back)
        if not resumed:
            notes.append("第 %d 次（%.0f 秒）恢复键没按下去：%s" % (n, time.time() - t0, back))
        notes.append("第 %d 次看画面（%.0f 秒）：%s" % (n, time.time() - t0, seen(pos, titled)))
        # 只有进度条证明正片在放。标题证明不了：2026-11-14 12:08 真机截到的广告页，左上角照样写着
        # 「别无选择 第5集」——那一版判据把「有标题」也算正片在放，于是在广告里说了「暂停」，判了「通过」。
        if pos is not None:
            if not resumed:
                # 明明是正片，可暂停下去以后按不回来：这一屏照样不能用（「恢复键没按下去」已经记了），
                # 更不能报「正片在放」让场景拿一个还在暂停的 resumed_at 去算走了多久。
                notes.append("进度条读得出，可恢复键没按下去，这一屏算不了，这次验不了")
                return None
            notes.append("正片在放了（第 %d 次看，%.0f 秒）：进度条读得出比例" % (n, time.time() - t0))
            return {"at": time.time() - t0, "frame": png, "position": pos, "titled": titled,
                    "resumed_at": time.time()}
        if why.get("reason") == "short" and not saw_short:
            saw_short = True
            notes.append("进度条出现了，可填充只有 %d px（约 %d 秒）"
                         % (why["fill_px"], round(why["fill_px"] * duration_s / 675.0)))
    if not saw_short:
        notes.append("广告一直没放完（等了 %.0f 秒，画面上一次都没出现进度条），这次验不了"
                     % (time.time() - t0))
    return None


def forget(since_ms):
    """Take what was said to XiaoAi in this run back out of 星仪's history (the xiaoai source is remembered)."""
    out = sh("am broadcast -n %s -a xyz.erapi0neer.xingyi.agent.FORGET_CHECKS --es sources xiaoai --el since %d"
             % (R, since_ms), timeout=30)
    return re.sub(r',"request".*', '', out.strip().splitlines()[-1])[:120] if out.strip() else "-"


def refuse(front=None, want="桌面"):
    """Why this scenario must not run now, as a sentence; None when it may. The TV has to be reachable and awake, on
    the screen the scenario starts from, and untouched for 20 minutes — a key newer than that is somebody using it,
    and this job never takes the TV out of anyone's hands."""
    if not reachable():
        return "连不上电视（adb %s -s %s）：不跑" % (ADB, DEV)
    if not awake():
        return "电视在待机：这个场景要一台亮屏的电视，不跑"
    if not (front or (lambda: HOME_PKG in resumed()))():
        return "前面不是%s（现在是 %s）：有人在用电视，或者上一个场景没收干净，不跑" % (want, resumed() or "?")
    idle = idle_minutes()
    if idle < IDLE_MIN:
        return "最后一次按键是 %.1f 分钟前（不到 %d 分钟）：有人在用电视，不跑" % (idle, IDLE_MIN)
    return None


# 拒绝分两类（verify-wait-classes, 2026-11-14）：等一会儿会好的，和等一百年也不会好的。
# 只有空闲时间那一类等得出来：还差多少分钟就够 20 分钟没人动，等够了它自己就成立。电视在待机、前面不是桌面、
# 读屏服务没开、音频服务说没在放、sleeping-episode 要的待机——这些等人来也不会变，在那种条目上把 --wait 的
# 预算花光，后面几条一条都跑不到。
IDLE_REFUSE = re.compile(r"分钟前\s*[（(]\s*不到\s*\d+\s*分钟\s*[）)]")
# 场景印拒绝的那一行：p.say(ID, "not run:", why)，偶尔是 "could not run:"
REFUSE_MARK = re.compile(r"(?:could\s+)?not\s+run:\s*(.*)$")
# refuse() 那几句的开头：场景把 why 原样印出来（没有 not run: 前缀）时靠它们认
REFUSE_SENT = ("连不上电视（adb", "电视在待机：", "前面不是", "最后一次按键是")


def refuse_class(why):
    """这一句拒绝属于哪一类："wait"（等一下会好的）或者 "now"（现在就不行，等也没用）。

    认不出的句子返回 "now"：等下去没结果的时候，该赶紧把条目交还给人，而不是烧掉半小时预算。认不出这件事
    本身由 verify.sh 印出来（它读 refusal_of，读不到就写「说不清为什么不能跑」）。
    """
    return "wait" if why and IDLE_REFUSE.search(str(why)) else "now"


def refusal_of(text):
    """一整段场景输出里那一句拒绝，去掉前缀（时间戳、id、not run:）；一句都没有就 None。"""
    lines = (text or "").splitlines()
    for line in lines:
        m = REFUSE_MARK.search(line)
        if m and m.group(1).strip():
            return m.group(1).strip()
    for line in lines:
        for k in REFUSE_SENT:
            i = line.find(k)
            if i >= 0 and line[i:].strip():
                return line[i:].strip()
    return None


def runner_name():
    """The file name of the script that is running right now: basename(sys.argv[0]). "" when there is no script —
    an empty argv, or `python -c` — because then nobody knows who is running and unknown is not guessed."""
    argv0 = sys.argv[0] if sys.argv else ""
    name = os.path.basename(argv0)
    return "" if name in ("", "-c") else name


def evidence(sid, ok, notes_=None, shots=None, by=None):
    """tests/agent/evidence/real-<id>.json — the one file a run leaves behind. Written only when the scenario really
    ran on the TV; a refusal writes nothing, because there is no verification to record.

    `by` is the file name of the script that produced this evidence, and it answers one question: 「这份证据是不是那个
    场景脚本自己跑出来的」. Left out, it is basename(sys.argv[0]) — verify.sh runs the scenario script itself, so that
    is `<id>.py` and not `verify.sh`. Given, it is used as given (a file name, not a path). With no script name to read
    (`python -c`, an empty argv) it is "": 不知道，不猜.

    2026-11-13 01:40: five of the ten evidence files were written by hand-written probes (session7m.py, h1.py, h2.py,
    h3.py) while their scenario scripts had never run at all. The behaviour was verified; 「the script itself passes」
    was not, and nothing in the file said which of the two had happened — check.py could not tell the two apart
    because the files looked exactly the same."""
    doc = {"id": sid, "at": datetime.now().astimezone().isoformat(timespec="seconds"), "ok": bool(ok),
           "by": runner_name() if by is None else str(by),
           "notes": [str(n) for n in (notes_ or [])], "shots": [str(s) for s in (shots or [])]}
    os.makedirs(EVIDENCE, exist_ok=True)
    path = os.path.join(EVIDENCE, "real-%s.json" % sid)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
    say("evidence", os.path.relpath(path, ROOT).replace("\\", "/"), "ok" if ok else "FAILED")
    return path


def attempt(sid, notes_=None):
    """「这次验不了」时用：notes 逐句 say() 出来（跑的日志里看得见），再写一份
    build/realpath-attempts/<sid>-<YYYYmmdd-HHMMSS>.json。

    不写 tests/agent/evidence/：没验成就没有证据，那一格只放真的跑成了的验证。可看到了什么不能丢——2026-11-14
    10:39 standby-home 返回 None，它那 90 秒里看到的东西一句都没留下，那份时间线是事后从 dumpsys usagestats 里
    拼回来的。
    """
    rows = [str(n) for n in (notes_ or [])]
    for n in rows:
        say("    " + n)
    at = datetime.now().astimezone()
    d = os.path.join(ROOT, "build", "realpath-attempts")
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, "%s-%s.json" % (sid, at.strftime("%Y%m%d-%H%M%S")))
    doc = {"id": sid, "at": at.isoformat(timespec="seconds"), "notes": rows}
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
    say("attempt", os.path.relpath(path, ROOT).replace("\\", "/"), "%d 句" % len(rows))
    return path


class Restore:
    """What a scenario puts back, however it ends: the volume, the apps it started (only the ones that were not
    running before it), what was said to XiaoAi, and the home screen in front."""

    def __init__(self, apps=(), leave_home=True):
        self.apps = list(apps)
        self.leave_home = leave_home
        self.vol = volume()
        self.running = {a: sh("pidof " + a).strip() for a in self.apps}
        self.since = now_ms()
        self.t0 = time.time()

    def started(self, pkg):
        """An app this run is about to start: it is stopped again at the end, unless it was already running (call this
        before the app is started, so the pid it records is the one from before)."""
        if pkg not in self.apps:
            self.apps.append(pkg)
            self.running[pkg] = sh("pidof " + pkg).strip()
        return pkg

    def close(self):
        say("cleanup")
        if self.leave_home and awake():
            home()
            time.sleep(1.5)
        for a in self.apps:
            if not self.running.get(a):
                sh("am force-stop " + a)
        set_volume(self.vol)
        say("forget:", forget(self.since))
        if self.leave_home and awake() and HOME_PKG not in resumed():
            home()
        say("left: awake", awake(), "| front", resumed(), "| volume", volume(),
            "| %.0f s" % (time.time() - self.t0))
