#!/bin/sh
# Build the Orrery launcher APK without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner
set -e
cd "$(dirname "$0")"
# machine paths: give ANDROID_SDK / JAVA_HOME / KS to build elsewhere
SDK=${ANDROID_SDK:-/h/tools/android-sdk}
BT=$SDK/android-15
JAR=$SDK/android-35/android.jar
# JAVA_HOME may be a Windows path with a trailing backslash (it is on this machine): normalise it
JAVA_HOME=$(cygpath -u "${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot}"); export JAVA_HOME
JDK="${JAVA_HOME%/}/bin"
export PATH="$JDK:$PATH"
python -c "from pathlib import Path; import shutil; root=Path.cwd().resolve(); target=(root/'build').resolve(); assert (root/'build.sh').is_file() and (root/'AndroidManifest.xml').is_file() and target.parent==root and target.name=='build'; shutil.rmtree(target) if target.exists() else None"
mkdir -p build/res build/gen build/classes build/dex

"$BT/aapt2.exe" compile --dir res -o build/res/res.zip
"$BT/aapt2.exe" link -I "$JAR" --manifest AndroidManifest.xml -A assets -0 bin \
    --java build/gen -o build/unsigned.apk build/res/res.zip --min-sdk-version 23 --target-sdk-version 28

find src build/gen -name '*.java' > build/sources.txt
"$JDK/javac.exe" -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:-options \
    -bootclasspath "$(cygpath -w "$JAR");$(cygpath -w "$BT/core-lambda-stubs.jar")" -d build/classes @build/sources.txt

CLASSES=$(cd build/classes && find . -name '*.class' | sed 's#^\./##')
(cd build/classes && "$BT/d8.bat" --release --min-api 23 --lib "$(cygpath -w "$JAR")" --output ../dex $CLASSES)

cp build/unsigned.apk build/with-dex.apk
python -c "import zipfile; z=zipfile.ZipFile('build/with-dex.apk','a',zipfile.ZIP_DEFLATED); z.write('build/dex/classes.dex','classes.dex'); z.close()"
"$BT/zipalign.exe" -f -p 4 build/with-dex.apk build/aligned.apk

KS=${KS:-debug.keystore}
[ -f $KS ] || "$JDK/keytool.exe" -genkeypair -keystore $KS -storepass android -keypass android \
    -alias orrery -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Orrery" >/dev/null 2>&1
"$BT/apksigner.bat" sign --ks $KS --ks-pass pass:android --key-pass pass:android \
    --out build/orrery.apk build/aligned.apk
"$BT/apksigner.bat" verify build/orrery.apk && ls -la build/orrery.apk
