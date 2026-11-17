#!/bin/sh
# Android check: compiles every source under src/ (the agent core, the TV side in agent/tv, the model client) against
# Android's android.jar alone, where org.json's JSONException is CHECKED and only Android's own java.* exists. The offline
# tests (run_core.sh) use the desktop org.json, where it is unchecked, so they cannot catch that; and they do not compile
# agent/tv, where a core member used without being public broke the bridge's build three times on 2026-11-06 (caught by
# the install gate). Prints "ANDROID CHECK OK" on success.
set -eu
cd "$(dirname "$0")/../.."
JDK="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin"
ANDROID=/h/tools/android-sdk/android-35/android.jar
OUT=build/android-check
rm -rf "$OUT"
mkdir -p "$OUT"
"$JDK/javac.exe" -J-XX:ActiveProcessorCount=2 -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 \
    -cp "$(cygpath -w "$ANDROID")" -d "$(cygpath -w "$OUT")" $(find src -name '*.java')
# Android throws "Unsupported flags: 256" for these (it crashed the bridge once)
if grep -rn -E "UNICODE_CHARACTER_CLASS|\(\?U\)" --include='*.java' src; then
    echo "ANDROID CHECK FAILED: Pattern.UNICODE_CHARACTER_CLASS / (?U) is not supported on Android"
    exit 1
fi
echo "ANDROID CHECK OK"
