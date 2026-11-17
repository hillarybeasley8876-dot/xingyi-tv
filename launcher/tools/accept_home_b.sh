#!/bin/sh
# Acceptance for job home-b: the apk builds, the PC checks pass, and the design's pieces are in place. Prints "HOME B OK".
cd "$(dirname "$0")/.."
S=src/xyz/erapi0neer/orrery
ok=1
sh build.sh > build-accept.log 2>&1 && tail -1 build-accept.log | grep -q "orrery.apk" || { echo "build FAILED:"; tail -25 build-accept.log; ok=0; }
sh tools/shelves_check.sh > build-shelves.log 2>&1 || { echo "shelves_check FAILED:"; grep FAIL build-shelves.log | head; ok=0; }
grep -q "^FAIL" build-shelves.log && { echo "shelves_check has FAIL lines"; ok=0; }
sh tools/home_check.sh > build-home.log 2>&1 || { echo "home_check FAILED:"; grep FAIL build-home.log | head; ok=0; }
grep -q "^FAIL" build-home.log && { echo "home_check has FAIL lines"; ok=0; }
n=$(grep -c "^ok " build-home.log); [ "$n" -ge 20 ] || { echo "home_check has $n ok lines, needs 20"; ok=0; }
check() { grep -q -F "$2" "$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
check $S/HomeUi.java 'NOW PLAYING'
check $S/HomeUi.java 'void setInfo(String line, String nowTitle, String nowArtist)'
check $S/HomeInfo.java 'content://xyz.erapi0neer.xiaoaibridge.agent'
check $S/HomeInfo.java '"home"'
check $S/HomeLayout.java 'static String infoLine(String lunar, String weather)'
check AndroidManifest.xml 'xyz.erapi0neer.xingyi.permission.USE_LOCAL_MCP'
grep -q "上下键 换分类" $S/HomeUi.java && { echo "the key hint is still in HomeUi"; ok=0; }
grep -q "RULE_X," $S/HomeUi.java && grep -q "c.drawRect(RULE_X" $S/HomeUi.java && { echo "the rule at RULE_X is still drawn"; ok=0; }
grep -q "import android\." $S/HomeLayout.java && { echo "HomeLayout must stay pure (no android.*)"; ok=0; }
rm -f build-accept.log build-shelves.log build-home.log
[ $ok = 1 ] && echo HOME B OK
