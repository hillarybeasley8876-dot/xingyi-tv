#!/bin/sh
# Acceptance for job install-consent: the TV app builds with this tree's sources, the bridge's JVM suite passes against
# them, the API is there, ApkConsent stays pure, and the consent goes through the sensitive gate. Builds into the
# bridge's build-wt-consent/ so parallel jobs do not collide. Prints "INSTALL CONSENT OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
X="$ROOT/src/xyz/erapi0neer/xingyi"
A="$X/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-consent sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-consent sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
check() { grep -q -F "$2" "$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
check "$A/ApkConsent.java" 'public static boolean urlOk(String url)'
check "$A/ApkConsent.java" 'public static boolean blocked(String host)'
check "$A/ApkConsent.java" 'public static String[] subject(String dn)'
check "$A/ApkConsent.java" 'public static boolean debugKey(String dn, String sha256)'
check "$A/ApkConsent.java" 'public static boolean abiOk(List<String> zipEntries, String[] deviceAbis)'
check "$A/ApkConsent.java" 'public static String sentence(JSONObject facts)'
check "$A/ApkConsent.java" 'a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc'
check "$X/ConsentInstaller.java" 'installVerified('
check "$X/ConsentInstaller.java" 'CertificateFactory'
check "$A/tv/TvTools.java" 'ApkConsent.sentence('
check "$A/tv/TvTools.java" 'needs_consent'
grep -q "import android\." "$A/ApkConsent.java" && { echo "ApkConsent.java must stay pure (no android.*)"; ok=0; }
# the install half must be a sensitive gate: the user's 「好的」 is the only confirmation there is
grep -q 'Gate.sensitive(ApkConsent.sentence' "$A/tv/TvTools.java" || { echo "install_app {token} must ask through Gate.sensitive(ApkConsent.sentence(...))"; ok=0; }
[ $ok = 1 ] && echo INSTALL CONSENT OK
