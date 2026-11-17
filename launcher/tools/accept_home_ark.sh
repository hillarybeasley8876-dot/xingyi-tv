#!/bin/sh
# Acceptance for job home-ark: the apk builds, the PC checks pass, and the terminal's pieces are in place.
# Prints "HOME ARK OK".
cd "$(dirname "$0")/.."
S=src/xyz/erapi0neer/orrery
ok=1
sh build.sh > build-accept.log 2>&1 && tail -1 build-accept.log | grep -q "orrery.apk" || { echo "build FAILED:"; tail -25 build-accept.log; ok=0; }
sh tools/shelves_check.sh > build-shelves.log 2>&1 || { echo "shelves_check FAILED:"; grep FAIL build-shelves.log | head; ok=0; }
grep -q "^FAIL" build-shelves.log && { echo "shelves_check has FAIL lines"; ok=0; }
sh tools/home_check.sh > build-home.log 2>&1 || { echo "home_check FAILED:"; grep FAIL build-home.log | head; ok=0; }
grep -q "^FAIL" build-home.log && { echo "home_check has FAIL lines"; ok=0; }
n=$(grep -c "^ok " build-home.log); [ "$n" -ge 67 ] || { echo "home_check has $n ok lines, needs 67 (37 before + 30)"; ok=0; }
check() { grep -q -F "$2" "$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
check $S/HomeUi.java 'TERMINAL'
check $S/HomeUi.java 'android.graphics.Camera'
check $S/HomeUi.java 'XINGYI TERMINAL'
check $S/HomeUi.java 'void setInfo(String line, String nowTitle, String nowArtist)'
check $S/HomeUi.java 'void setAgent(String word)'
check $S/HomeUi.java 'Oswald-Variable.ttf'
check $S/HomeLayout.java 'static String english(String label, String pkg)'
check $S/HomeLayout.java 'XINGYI · ASSISTANT'
grep -q "import android\." $S/HomeLayout.java && { echo "HomeLayout must stay pure (no android.*)"; ok=0; }
rm -f build-accept.log build-shelves.log build-home.log
[ $ok = 1 ] && echo HOME ARK OK
