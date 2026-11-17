#!/bin/sh
# Build the XiaoAi bridge APK without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Works from any checkout (main repo or a worker worktree). Signed with the launcher's debug key, the same key as
# Xingyi and Orrery (signature-level permissions between these apps depend on it).
# The two pure modules (MusicIntent, MusicResolver) live in package xyz.erapi0neer.xingyi so they can move into Xingyi unchanged.
set -eu
cd "$(dirname "$0")"
# machine paths: give ANDROID_SDK / JAVA_HOME / KS / XINGYI to build elsewhere
SDK=${ANDROID_SDK:-/h/tools/android-sdk}
BT=$SDK/android-15
JAR=$SDK/android-35/android.jar
# JAVA_HOME may be a Windows path with a trailing backslash (it is on this machine): normalise it
JAVA_HOME=$(cygpath -u "${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot}"); export JAVA_HOME
JDK="${JAVA_HOME%/}/bin"
KS=${KS:-../launcher/debug.keystore}
# BRIDGE_BUILD: another build directory (one name inside this one), so parallel job acceptances do not collide
B=${BRIDGE_BUILD:-build}
python -c "from pathlib import Path; import shutil; root=Path.cwd().resolve(); t=(root/'$B').resolve(); assert t.parent==root and (root/'build.sh').is_file(); shutil.rmtree(t) if t.exists() else None"
mkdir -p $B/classes $B/dex $B/res
# 星仪's agent runtime is compiled in from 星仪's source tree (the single copy); javac pulls in only what it references.
XINGYI=${XINGYI:-$(cd ../voice/assistant && pwd)}
"$BT/aapt2.exe" compile --dir res -o $B/res/res.zip
"$BT/aapt2.exe" link -I "$JAR" --manifest AndroidManifest.xml -A "$XINGYI/assets" -o $B/unsigned.apk $B/res/res.zip --min-sdk-version 23 --target-sdk-version 28
{ find src -name '*.java'; find "$XINGYI/src/xyz/erapi0neer/xingyi/agent" -name '*.java' | xargs -n 50 cygpath -m; cygpath -m "$XINGYI/src/xyz/erapi0neer/xingyi/Legacy.java"; } > $B/sources.txt
"$JDK/javac.exe" -J-XX:ActiveProcessorCount=2 -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:-options -implicit:class \
    -sourcepath "$(cygpath -w "$XINGYI/src")" \
    -bootclasspath "$(cygpath -w "$JAR");$(cygpath -w "$BT/core-lambda-stubs.jar")" -d $B/classes @$B/sources.txt
# Too many class files for one cmd.exe command line: hand d8 a jar instead.
"$JDK/jar.exe" cf $B/classes.jar -C $B/classes .
"$BT/d8.bat" --release --thread-count 2 --min-api 23 --lib "$(cygpath -w "$JAR")" --output $B/dex $B/classes.jar
cp $B/unsigned.apk $B/with-dex.apk
python -c "import zipfile; z=zipfile.ZipFile('$B/with-dex.apk','a',zipfile.ZIP_DEFLATED); z.write('$B/dex/classes.dex','classes.dex'); z.close()"
"$BT/zipalign.exe" -f -p 4 $B/with-dex.apk $B/aligned.apk
"$BT/apksigner.bat" sign --ks "$(cygpath -w "$KS")" --ks-pass pass:android --key-pass pass:android --out $B/xiaoai-bridge.apk $B/aligned.apk
"$BT/apksigner.bat" verify $B/xiaoai-bridge.apk && ls -la $B/xiaoai-bridge.apk
