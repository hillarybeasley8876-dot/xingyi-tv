#!/bin/sh
# Android check: compiles every source under src/ (the agent core, the TV side in agent/tv, the model client) against
# Android's android.jar alone, where org.json's JSONException is CHECKED and only Android's own java.* exists. The offline
# tests (run_core.sh) use the desktop org.json, where it is unchecked, so they cannot catch that; and they do not compile
# agent/tv, where a core member used without being public broke the bridge's build three times on 2026-11-06 (caught by
# the install gate). Prints "ANDROID CHECK OK" on success.
set -eu
cd "$(dirname "$0")/../.."
. tests/env.sh   # JDK, ANDROID_JAR, P: this machine by default, JAVA_HOME / ANDROID_JAR elsewhere
need_android
OUT=build/android-check
rm -rf "$OUT"
mkdir -p "$OUT"
"$JDK/javac" -J-XX:ActiveProcessorCount=2 -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 \
    -cp "$(P "$ANDROID_JAR")" -d "$(P "$OUT")" $(find src -name '*.java')
# Android throws "Unsupported flags: 256" for these (it crashed the bridge once)
if grep -rn -E "UNICODE_CHARACTER_CLASS|\(\?U\)" --include='*.java' src; then
    echo "ANDROID CHECK FAILED: Pattern.UNICODE_CHARACTER_CLASS / (?U) is not supported on Android"
    exit 1
fi
echo "ANDROID CHECK OK"
