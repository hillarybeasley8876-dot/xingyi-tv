#!/bin/sh
# Acceptance for job phone-console: the TV app builds with this tree's sources (and carries phone.html), the bridge's
# JVM suite passes against them, the pieces are in place, PhoneApi stays pure. Builds into the bridge's build-wt-phone/
# so parallel jobs do not collide. Prints "PHONE CONSOLE OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-phone sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-phone sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
python -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); sys.exit(0 if 'assets/agent/phone.html' in z.namelist() else 1)" "$(cygpath -w "$BRIDGE/build-wt-phone/xiaoai-bridge.apk")" 2>/dev/null || { echo "phone.html is not in the built apk"; ok=0; }
check() { grep -q -F "$2" "$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
check "$A/PhoneApi.java" 'interface Engine'
check "$A/PhoneApi.java" '/api/pair/start'
check "$A/PhoneApi.java" '/api/say'
check "$A/tv/PhoneServer.java" '18780'
check "$A/tv/AgentEngine.java" 'phone-quiet'
check "$ROOT/assets/agent/phone.html" '/api/say'
check "$ROOT/assets/agent/phone.html" 'localStorage'
grep -q "import android\." "$A/PhoneApi.java" && { echo "PhoneApi.java must stay pure (no android.*)"; ok=0; }
grep -qE "https?://[a-z0-9.-]+\.(com|net|org|cn)/" "$ROOT/assets/agent/phone.html" && { echo "phone.html must not load anything from the internet"; ok=0; }
[ $ok = 1 ] && echo PHONE CONSOLE OK
