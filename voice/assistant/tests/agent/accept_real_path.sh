#!/bin/sh
# Acceptance for job real-path: the bridge builds with this tree's sources and its JVM suite passes against them; the
# real-path scenarios, their registry, the offline checker and the runner are in place. Nothing here touches the TV.
# Builds into build-wt-real-path/. Prints "REALPATH OK".
# A native .exe (python, node, aapt2) reads Git Bash's /h/... as H:\h\... while the launching shell has
# MSYS_NO_PATHCONV=1 (the m31w runner's shell does): clear it, so this script does not depend on who ran it.
unset MSYS_NO_PATHCONV 2>/dev/null || true
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-real-path sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-real-path sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
for f in probe.py check.py run.py rehearse.py screen.py screen_check.py scenarios.json; do
    ls "$ROOT/tests/agent/realpath/$f" >/dev/null 2>&1 || { echo "tests/agent/realpath/$f missing"; ok=0; }
done
for id in panel-while-speaking panel-self-check black-screen-home hint-card-not-a-switch episode-open pause-through-xiaoai seek-one-minute standby-home sleeping-episode; do
    ls "$ROOT/tests/agent/realpath/scenarios/$id.py" >/dev/null 2>&1 || { echo "scenario $id missing"; ok=0; }
done
ls "$ROOT/tests/agent/RealPathChecks.java" >/dev/null 2>&1 || { echo "RealPathChecks.java missing"; ok=0; }
# 形状录制必须进了仓库：只在某个人的工作区里存在的话，别的 worktree 里这条规则会静默不跑（2026-11-13 02:00 就是这样
# 让 payload-shapes 第 1 轮红掉的）
git -C "$ROOT" ls-files --error-unmatch tests/agent/fixtures/realpath/payload-shapes.json >/dev/null 2>&1 \
    || { echo "tests/agent/fixtures/realpath/payload-shapes.json is not tracked (every other worktree runs without the shape rule)"; ok=0; }
grep -q 'RealPathChecks.run()' "$ROOT/tests/agent/SkillChecks.java" || { echo "SkillChecks never runs RealPathChecks"; ok=0; }
python "$ROOT/tests/agent/realpath/check.py" --strict > "$ROOT/build/accept-realpath-check.log" 2>&1 && grep -q 'REALPATH OK' "$ROOT/build/accept-realpath-check.log" || { echo "check.py does not pass"; cat "$ROOT/build/accept-realpath-check.log" | head -8; ok=0; }
# 每条场景的判据，正反两面都得在离线被证明过一次：假 probe 把 check() 在写死的局面里跑一遍，好的必须过、坏的必须不过。
# 一条电视都不碰（假 probe 不跑 adb，evidence() 只记列表），跑一次几秒。
python "$ROOT/tests/agent/realpath/rehearse.py" > "$ROOT/build/accept-rehearse.log" 2>&1 && grep -q 'REHEARSE OK' "$ROOT/build/accept-rehearse.log" || { echo "rehearse.py does not pass"; tail -12 "$ROOT/build/accept-rehearse.log"; ok=0; }
[ "$(grep -c '^ok ' "$ROOT/build/accept-rehearse.log")" -ge 20 ] || { echo "rehearse.py ran fewer than 20 cases"; ok=0; }
# evidence() 写出的 by 对不对：Java 那边测不了 Python，这条自测跑真的子进程（写进 build/，不碰真证据目录）
python "$ROOT/tests/agent/realpath/probe_by.py" > "$ROOT/build/accept-probe-by.log" 2>&1 && grep -q 'PROBE BY OK' "$ROOT/build/accept-probe-by.log" || { echo "probe_by.py does not pass"; tail -12 "$ROOT/build/accept-probe-by.log"; ok=0; }
# 判据的输入对不对：standby-home 的判据整个建在 dumpsys usagestats 读出来的那串事件上，parse_usage 拿 fixture
# 原文（2026-11-14 10:39 那次跑的真记录）离线过一遍：四种事件、原先后、since 过滤、重复行、坏文字不抛异常。
python "$ROOT/tests/agent/realpath/usage_check.py" > "$ROOT/build/accept-usage.log" 2>&1 && grep -q 'USAGE OK' "$ROOT/build/accept-usage.log" || { echo "usage_check.py does not pass"; tail -12 "$ROOT/build/accept-usage.log"; ok=0; }
# 读画面的判卷：播放页整页是 SurfaceView 画的，浮层上的字一个都读不到（2026-11-14 11:10 量了 15 次，
# 全是 nodes 1 / texts 0），三条场景的判据改读截图——进度条的比例和左上角那一行剧集名。
# 11 帧真机 fixture 逐帧读一遍（screen.py），全过才 SCREEN OK；一条电视都不碰，只吃本地 png。
python "$ROOT/tests/agent/realpath/screen_check.py" > "$ROOT/build/accept-screen.log" 2>&1 && grep -q 'SCREEN OK' "$ROOT/build/accept-screen.log" || { echo "screen_check.py does not pass"; tail -14 "$ROOT/build/accept-screen.log"; ok=0; }
grep -q 'player-00h04m01.png' "$ROOT/build/accept-screen.log" || { echo "screen_check.py did not read the fixtures frame by frame"; ok=0; }
python "$ROOT/tests/agent/realpath/run.py" --list > "$ROOT/build/accept-realpath-list.log" 2>&1 || { echo "run.py --list failed"; ok=0; }
[ "$(grep -c 'panel-while-speaking\|panel-self-check\|black-screen-home\|hint-card-not-a-switch\|episode-open\|pause-through-xiaoai\|seek-one-minute\|standby-home\|sleeping-episode' "$ROOT/build/accept-realpath-list.log")" -ge 9 ] || { echo "run.py --list does not list the nine"; ok=0; }
# 模型路由的契约（2026-11-14）：ModelRouter 用到 android.content.Context，run_core 编不到它，那份契约一直只在电视上
# 通过 DUMP 广播跑；现在离线也能跑，降级那条路（网关没容量 -> 换备用模型）才有门。
sh "$ROOT/tests/route/route_check.sh" > "$ROOT/build/accept-route.log" 2>&1 && grep -q 'ROUTE OK' "$ROOT/build/accept-route.log" || { echo "route_check.sh does not pass"; tail -12 "$ROOT/build/accept-route.log"; ok=0; }
[ $ok = 1 ] && echo REALPATH OK
