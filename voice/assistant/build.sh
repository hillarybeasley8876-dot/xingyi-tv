#!/bin/sh
# Usage: bash build.sh          release build/xingyi.apk (debuggable=false)
#        bash build.sh debug    build/xingyi-debug.apk, same package, version and signature; only for provisioning
set -eu
cd "$(dirname "$0")"
VARIANT=${1:-release}
SDK=/h/tools/android-sdk
BT=$SDK/android-15
JAR=$SDK/android-35/android.jar
JDK="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin"
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
export PATH="$JDK:$PATH"
MANIFEST=AndroidManifest.xml
OUT=build/xingyi.apk
python clean_build.py
if [ "$VARIANT" = debug ]; then
  MANIFEST=build/AndroidManifest.debug.xml
  OUT=build/xingyi-debug.apk
  sed 's/android:debuggable="false"/android:debuggable="true"/' AndroidManifest.xml > "$MANIFEST"
  grep -q 'android:debuggable="true"' "$MANIFEST"
elif [ "$VARIANT" != release ]; then
  echo "unknown variant: $VARIANT" >&2; exit 2
fi
mkdir -p build/res
"$BT/aapt2.exe" compile --dir res -o build/res/res.zip
"$BT/aapt2.exe" link -I "$JAR" --manifest "$MANIFEST" -A assets -o build/unsigned.apk build/res/res.zip --min-sdk-version 23 --target-sdk-version 28
find src -name '*.java' > build/sources.txt
"$JDK/javac.exe" -J-XX:ActiveProcessorCount=2 -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:-options -bootclasspath "$(cygpath -w "$JAR");$(cygpath -w "$BT/core-lambda-stubs.jar")" -d build/classes @build/sources.txt
# Too many class files for one cmd.exe command line: hand d8 a jar instead.
rm -f build/classes.jar
"$JDK/jar.exe" cf build/classes.jar -C build/classes .
"$BT/d8.bat" --release --thread-count 2 --min-api 23 --lib "$(cygpath -w "$JAR")" --output build/dex build/classes.jar
cp build/unsigned.apk build/with-dex.apk
python -c "import sys, zipfile; z = zipfile.ZipFile(sys.argv[1], 'a', zipfile.ZIP_DEFLATED); z.write(sys.argv[2], 'classes.dex'); z.close()" build/with-dex.apk build/dex/classes.dex
"$BT/zipalign.exe" -f -p 4 build/with-dex.apk build/aligned.apk
"$BT/apksigner.bat" sign --ks ../../launcher/debug.keystore --ks-pass pass:android --key-pass pass:android --out "$OUT" build/aligned.apk
"$BT/apksigner.bat" verify "$OUT"
