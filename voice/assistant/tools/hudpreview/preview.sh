#!/bin/sh
# Off-screen panel samples from a source tree, on the TV, without touching the running bridge (lead tool, 2026-11-07).
#   sh tools/hudpreview/preview.sh [source tree, default this repo] [out dir]
# Builds a tiny app (package xyz.erapi0neer.hudpreview: one receiver, nothing starts by itself) with the tree's agent
# sources, installs it next to the bridge, renders AgentDebug's samples and pulls hud-*.png to the out dir.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
XINGYI="$(cd "${1:-$HERE/../..}" && pwd)"
OUT="${2:-/h/tools/mitv/voice/assistant-out/tmp/hudpreview}"
SDK=/h/tools/android-sdk
BT=$SDK/android-15
JAR=$SDK/android-35/android.jar
JDK="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin"
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
KS=/h/tools/mitv/launcher/debug.keystore
B=/h/tools/mitv/voice/assistant-out/tmp/hudpreview-build
ADB="/h/tools/platform-tools/adb.exe -s 192.168.31.162:5555"
rm -rf "$B"; mkdir -p "$B/classes" "$B/dex" "$OUT"
"$BT/aapt2.exe" link -I "$JAR" --manifest "$(cygpath -m "$HERE/AndroidManifest.xml")" -o "$B/unsigned.apk" --min-sdk-version 23 --target-sdk-version 28
{ cygpath -m "$HERE/src/xyz/erapi0neer/hudpreview/Render.java"; cygpath -m "$XINGYI/src/xyz/erapi0neer/xingyi/agent/tv/AgentDebug.java"; } > "$B/sources.txt"
"$JDK/javac.exe" -J-XX:ActiveProcessorCount=2 -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:-options -implicit:class \
    -sourcepath "$(cygpath -w "$XINGYI/src")" \
    -bootclasspath "$(cygpath -w "$JAR");$(cygpath -w "$BT/core-lambda-stubs.jar")" -d "$(cygpath -w "$B/classes")" @"$(cygpath -w "$B/sources.txt")"
"$JDK/jar.exe" cf "$(cygpath -w "$B/classes.jar")" -C "$(cygpath -w "$B/classes")" .
"$BT/d8.bat" --release --thread-count 2 --min-api 23 --lib "$(cygpath -w "$JAR")" --output "$(cygpath -w "$B/dex")" "$(cygpath -w "$B/classes.jar")"
cp "$B/unsigned.apk" "$B/with-dex.apk"
python -c "import zipfile; z=zipfile.ZipFile(r'$(cygpath -w "$B/with-dex.apk")','a',zipfile.ZIP_DEFLATED); z.write(r'$(cygpath -w "$B/dex/classes.dex")','classes.dex'); z.close()"
"$BT/zipalign.exe" -f -p 4 "$(cygpath -w "$B/with-dex.apk")" "$(cygpath -w "$B/aligned.apk")"
"$BT/apksigner.bat" sign --ks "$(cygpath -w "$KS")" --ks-pass pass:android --key-pass pass:android --out "$(cygpath -w "$B/hudpreview.apk")" "$(cygpath -w "$B/aligned.apk")"
export MSYS_NO_PATHCONV=1
$ADB install -r "$(cygpath -m "$B/hudpreview.apk")" | tail -1
R=$($ADB shell am broadcast -n xyz.erapi0neer.hudpreview/.Render -a xyz.erapi0neer.hudpreview.RENDER | tail -1)
echo "$R"
DIR=/sdcard/Android/data/xyz.erapi0neer.hudpreview/files
for f in $($ADB shell ls $DIR | tr -d '\r'); do $ADB pull "$DIR/$f" "$(cygpath -m "$OUT")/$f" > /dev/null; done
ls "$OUT"
