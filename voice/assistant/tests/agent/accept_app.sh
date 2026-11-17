#!/bin/sh
# Acceptance for job app: the 星仪 APK builds from this tree, MainActivity follows the engine by request id and uses
# the 历史 / 记忆 calls, and the old in-app harness paths and the 小爱自动接手 button are gone. Prints "APP OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
X="$ROOT/src/xyz/erapi0neer/xingyi"
cd "$ROOT" || exit 1
mkdir -p build
ok=1
if ! sh build.sh > build/accept-app-build.log 2>&1 || [ ! -s build/xingyi.apk ]; then
    echo "app build FAILED:"; grep -v -E "^(注|ע):" build/accept-app-build.log | grep -E "error|错误|\.java:" | head -25; ok=0
fi
has() { grep -q -F "$2" "$X/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
hasnt() { grep -q -F "$2" "$X/$1" 2>/dev/null && { echo "still in $1: $2"; ok=0; }; }
has MainActivity.java 'AgentClient.submitTracked('
has MainActivity.java '"request"'
has MainActivity.java 'AgentClient.history('
has MainActivity.java 'AgentClient.memory('
has MainActivity.java 'AgentClient.forget('
has MainActivity.java 'AgentClient.hints('
has MainActivity.java '"side"'
has MainActivity.java 'moveTaskToBack(true)'
has AssistantLayout.java '"history"'
has AssistantLayout.java '"memory"'
hasnt MainActivity.java 'startHarness('
hasnt MainActivity.java 'forHandedOffMusic('
hasnt MainActivity.java 'agentSince'
hasnt MainActivity.java 'AutoRelaySettings.show('
hasnt MainActivity.java '"auto_relay"'
hasnt AssistantLayout.java '"auto_relay"'
[ $ok = 1 ] && echo APP OK
