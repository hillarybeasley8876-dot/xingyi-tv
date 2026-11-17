#!/bin/sh
# Acceptance for job find-installer: the TV app builds with this tree's sources, the bridge's JVM suite passes against
# them, the API is there, Installers stays pure and the tool keeps the links (no speakable). Builds into the bridge's
# build-wt-installer/ so parallel jobs do not collide. Prints "FIND INSTALLER OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-installer sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-installer sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
check() { grep -q -F "$2" "$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
check "$A/Installers.java" 'public static JSONObject parse(String text)'
check "$A/WebSearch.java" 'findInstaller('
check "$A/WebSearch.java" '只认开发者自己的官网、它官网上写明的下载地址或开发者自己的 CDN'
check "$A/tv/TvTools.java" 'new Base("find_installer")'
grep -q "import android\." "$A/Installers.java" && { echo "Installers.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo FIND INSTALLER OK
