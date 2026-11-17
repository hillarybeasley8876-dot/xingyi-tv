#!/bin/sh
# Acceptance for job official-installs: the TV app builds with this tree's sources (the bridge's build.sh compiles agent/
# and the 0.x classes it uses), the bridge's JVM suite passes against them, the API is there, OfficialApps stays pure, the
# pinned keys are the ones the lead checked and nothing else installs. Builds into the bridge's build-wt-official/ so
# parallel jobs do not collide. Prints "OFFICIAL INSTALLS OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
X="$ROOT/src/xyz/erapi0neer/xingyi"
A="$X/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-official sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-official sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
check() { grep -q -F "$2" "$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
check "$A/OfficialApps.java" 'public static App find(String asked)'
check "$A/OfficialApps.java" 'public static String githubAsset(JSONObject release, String[] abis, App app)'
check "$A/OfficialApps.java" 'public static boolean redirectAllowed(App app, String url)'
check "$A/OfficialApps.java" 'public static boolean trusted(App app, String sha256hex)'
check "$A/OfficialApps.java" '1e5124be7e7fcb7a6462b47a42a8567863c6fccc6fe7708cd278be4f43047c75'
check "$A/OfficialApps.java" '93ba270f5521139ecafe4bb638ac5b1198bc548f62d9fd8f8580a079faf5910e'
check "$A/OfficialApps.java" '3f94437a010dc70e2340e0c6c27898a95276af5c1382d69a49a8ffc325600ef5'
check "$A/OfficialApps.java" 'com.phlox.tvwebbrowser'
check "$X/LocalAdb.java" 'installVerified(String pkgDir, String filename, String digest)'
check "$X/Legacy.java" 'installOfficial('
check "$A/tv/TvTools.java" 'OfficialApps.find('
grep -q "import android\." "$A/OfficialApps.java" && { echo "OfficialApps.java must stay pure (no android.*)"; ok=0; }
# only the lead-checked keys: any other 64-hex constant in OfficialApps is a key nobody checked
n=$(grep -oE '"[0-9a-f]{64}"' "$A/OfficialApps.java" | sort -u | wc -l)
[ "$n" = "3" ] || { echo "OfficialApps.java pins $n signing keys, the lead checked 3"; ok=0; }
[ $ok = 1 ] && echo OFFICIAL INSTALLS OK
