#!/bin/sh
# Acceptance for job board: the bridge builds with this tree's sources and its JVM suite passes against them; the board
# command writes the five sections from the job states on disk, and Board.java's rules match board.mjs's format.
# Builds into build-wt-board/. Prints "BOARD OK".
# The job's shell may have MSYS_NO_PATHCONV=1 (the runner's own shell does): a native .exe then gets /h/... literally
# and reads it as H:\h\..., so every path handed to node.exe/aapt2 goes through cygpath -w, and the bridge build gets
# the variable cleared (build.sh passes -I "$JAR" to aapt2.exe and relies on MSYS converting it).
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
WIN() { cygpath -w "$1" 2>/dev/null || printf '%s' "$1"; }
mkdir -p "$ROOT/build"
ok=1
if ! (unset MSYS_NO_PATHCONV; XINGYI="$ROOT" BRIDGE_BUILD=build-wt-board sh "$BRIDGE/build.sh") > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-board sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
ls "$ROOT/tools/board/board.mjs" >/dev/null 2>&1 || { echo "tools/board/board.mjs missing"; ok=0; }
ls "$A/Board.java" >/dev/null 2>&1 || { echo "Board.java missing"; ok=0; }
ls "$ROOT/tests/agent/BoardChecks.java" >/dev/null 2>&1 || { echo "BoardChecks.java missing"; ok=0; }
ls "$ROOT/tests/agent/board-fixtures" >/dev/null 2>&1 || { echo "tests/agent/board-fixtures missing"; ok=0; }
grep -q 'BoardChecks.run()' "$ROOT/tests/agent/SkillChecks.java" || { echo "SkillChecks never runs BoardChecks"; ok=0; }
# real states, a copy: the command must not write status/BOARD.md during an acceptance
if ! /h/tools/node/node-v24.22.2-win-x64/node.exe "$(WIN "$ROOT/tools/board/board.mjs")" --out "$(WIN "$ROOT/build/BOARD-test.md")" > "$ROOT/build/accept-board-cmd.log" 2>&1; then
    echo "board.mjs failed:"; tail -5 "$ROOT/build/accept-board-cmd.log"; ok=0
fi
for h in '## 在跑' '## 等合并' '## 今天完成并已合并' '## 卡住' '## 消耗'; do
    grep -q -F "$h" "$ROOT/build/BOARD-test.md" 2>/dev/null || { echo "BOARD.md lacks «$h»"; ok=0; }
done
[ $ok = 1 ] && echo BOARD OK
