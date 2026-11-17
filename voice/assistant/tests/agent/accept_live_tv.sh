#!/bin/sh
# Acceptance for job live-tv: the bridge builds with this tree's sources and its JVM suite passes against them; the
# quick answer for a channel, the gate on switch_input and the contract's line are in place. Builds into the bridge's
# build-wt-live-tv/. Prints "LIVE TV OK".
# A native .exe (python, node, aapt2) reads Git Bash's /h/... as H:\h\... while the launching shell has
# MSYS_NO_PATHCONV=1 (the m31w runner's shell does): clear it, so this script does not depend on who ran it.
unset MSYS_NO_PATHCONV 2>/dev/null || true
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-live-tv sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-live-tv sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
has QuickCommand.java 'public static boolean namesInput('
has QuickCommand.java '"watch"'
has TvGuide.java 'public static String cannotWatch('
has tv/TvTools.java 'QuickCommand.namesInput('
has tv/TvTools.java 'TvGuide.cannotWatch('
has tv/TvTools.java '"has_app"'
grep -q 'live()' "$ROOT/tests/agent/QuickChecks.java" || { echo "QuickChecks has no live() group"; ok=0; }
grep -q '用户要看电视台的直播' "$ROOT/assets/agent/contract.json" || { echo "contract.json lacks the live-TV line"; ok=0; }
grep -q '"watch"' "$ROOT/assets/agent/contract.json" && { echo "contract.json must not list watch"; ok=0; }
python "$ROOT/tests/agent/prompts_doc.py" --check > "$ROOT/build/accept-prompts.log" 2>&1 || { echo "PROMPTS.md is not up to date"; ok=0; }
[ $ok = 1 ] && echo LIVE TV OK
