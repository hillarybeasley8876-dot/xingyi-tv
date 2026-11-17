#!/bin/sh
# The home screen's type layer from a source tree, rendered on the TV off-screen and laid over a still of the flow on
# the PC (lead tool, 2026-11-08). Does not touch the installed home screen.
#   sh tools/homepreview/preview.sh [source tree, default this repo] [out dir] [art.png]
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
TREE="$(cd "${1:-$HERE/../..}" && pwd)"
OUT="${2:-/h/tools/mitv/launcher-out/tmp/homepreview}"
ART="${3:-/h/tools/mitv/voice/assistant-out/tmp/lead/home/art-now.png}"
SDK=/h/tools/android-sdk
BT=$SDK/android-15
JAR=$SDK/android-35/android.jar
JDK="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin"
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
KS=/h/tools/mitv/launcher/debug.keystore
B=/h/tools/mitv/launcher-out/tmp/homepreview-build
ADB="/h/tools/platform-tools/adb.exe -s 192.168.31.162:5555"
rm -rf "$B"; mkdir -p "$B/classes" "$B/dex" "$OUT"
"$BT/aapt2.exe" link -I "$JAR" --manifest "$(cygpath -m "$HERE/AndroidManifest.xml")" -A "$(cygpath -m "$TREE/assets")" -o "$B/unsigned.apk" --min-sdk-version 23 --target-sdk-version 28
{ cygpath -m "$HERE/PreviewRender.java"; for f in HomeUi HomeLayout Shelves; do [ -f "$TREE/src/xyz/erapi0neer/orrery/$f.java" ] && cygpath -m "$TREE/src/xyz/erapi0neer/orrery/$f.java"; done; } > "$B/sources.txt"
"$JDK/javac.exe" -J-XX:ActiveProcessorCount=2 -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:-options -implicit:class \
    -sourcepath "$(cygpath -w "$TREE/src")" \
    -bootclasspath "$(cygpath -w "$JAR");$(cygpath -w "$BT/core-lambda-stubs.jar")" -d "$(cygpath -w "$B/classes")" @"$(cygpath -w "$B/sources.txt")"
"$JDK/jar.exe" cf "$(cygpath -w "$B/classes.jar")" -C "$(cygpath -w "$B/classes")" .
"$BT/d8.bat" --release --thread-count 2 --min-api 26 --lib "$(cygpath -w "$JAR")" --output "$(cygpath -w "$B/dex")" "$(cygpath -w "$B/classes.jar")"
cp "$B/unsigned.apk" "$B/with-dex.apk"
python -c "import zipfile; z=zipfile.ZipFile(r'$(cygpath -w "$B/with-dex.apk")','a',zipfile.ZIP_DEFLATED); z.write(r'$(cygpath -w "$B/dex/classes.dex")','classes.dex'); z.close()"
"$BT/zipalign.exe" -f -p 4 "$(cygpath -w "$B/with-dex.apk")" "$(cygpath -w "$B/aligned.apk")"
"$BT/apksigner.bat" sign --ks "$(cygpath -w "$KS")" --ks-pass pass:android --key-pass pass:android --out "$(cygpath -w "$B/homepreview.apk")" "$(cygpath -w "$B/aligned.apk")"
export MSYS_NO_PATHCONV=1
$ADB install -r "$(cygpath -m "$B/homepreview.apk")" | tail -1
$ADB shell am broadcast -n xyz.erapi0neer.homepreview/xyz.erapi0neer.orrery.PreviewRender -a xyz.erapi0neer.homepreview.RENDER | tail -1
DIR=/sdcard/Android/data/xyz.erapi0neer.homepreview/files
for f in $($ADB shell ls $DIR | tr -d '\r'); do $ADB pull "$DIR/$f" "$(cygpath -m "$OUT")/ui-$f" > /dev/null; done
PYTHONUTF8=1 python - "$(cygpath -m "$ART")" "$(cygpath -m "$OUT")" <<'EOF'
import sys, os
from PIL import Image
art, out = sys.argv[1], sys.argv[2]
base = Image.open(art).convert('RGBA')
for f in sorted(os.listdir(out)):
    if f.startswith('ui-') and f.endswith('.png'):
        ui = Image.open(os.path.join(out, f)).convert('RGBA')
        Image.alpha_composite(base, ui).convert('RGB').save(os.path.join(out, f[3:]))
        print(os.path.join(out, f[3:]))
EOF
