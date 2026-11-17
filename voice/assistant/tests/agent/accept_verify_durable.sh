#!/bin/sh
# Acceptance for job verify-durable: the bridge builds with this tree's sources and its JVM suite passes against them;
# the unverified list has a validator, a generated NEXT.md section, a resumable runner and the status line.
# Builds into build-wt-verify-durable/. Prints "VERIFY DURABLE OK".
# A native .exe (python, node, aapt2) reads Git Bash's /h/... as H:\h\... while the launching shell has
# MSYS_NO_PATHCONV=1 (the m31w runner's shell does): clear it, so this script does not depend on who ran it.
unset MSYS_NO_PATHCONV 2>/dev/null || true
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# 演练用自己的一把锁（2026-11-16）：它往锁里塞的是假的持有者，跟真机那一轮共用会让下一次把残留的 pid
# 当成「有个 verify.sh 还活着」，整段锁演练被跳过（pid 还会被复用指到 sleep 之类的无关进程上）。
LOCK="$ROOT/build/verify-wait-drill/lock"
export XINGYI_VERIFY_LOCK="$LOCK"
mkdir -p "$(dirname "$LOCK")" || exit 1
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-verify-durable sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-verify-durable sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
has Unverified.java 'public static java.util.List<String> problems('
has Unverified.java 'public static String markdown(String json)'
has Unverified.java 'public static String line(String json, int max)'
has Unverified.java 'public static String without(String json, String id)'
ls "$ROOT/tests/agent/unverified.json" >/dev/null 2>&1 || { echo "unverified.json missing"; ok=0; }
ls "$ROOT/tests/agent/unverified_doc.py" >/dev/null 2>&1 || { echo "unverified_doc.py missing"; ok=0; }
ls "$ROOT/tests/agent/unverified_status.py" >/dev/null 2>&1 || { echo "unverified_status.py missing"; ok=0; }
ls "$ROOT/tests/agent/verify.sh" >/dev/null 2>&1 || { echo "verify.sh missing"; ok=0; }
# NEXT.md is the lead's file: the runner reverts it before acceptance, so the acceptance writes the section itself
# through the job's generator and then checks that the generator says the file is current.
python "$ROOT/tests/agent/unverified_doc.py" > "$ROOT/build/accept-unverified-write.log" 2>&1 || { echo "unverified_doc.py does not write NEXT.md"; ok=0; }
python "$ROOT/tests/agent/unverified_doc.py" --check > "$ROOT/build/accept-unverified-doc.log" 2>&1 || { echo "NEXT.md's 还没验的 section is out of date"; ok=0; }
# 无参数那一路要「照着账本念」：账本里有条目就得逐条印 WAITING，空账本就得什么都不印、退出 0。
# 285e656 把最后两条验完、账本是空的（NEXT.md 里记着这是有意的），所以「必须印 WAITING」不能拿真账本当前提；
# 换成真账本按条数判，另拿一份假账本证明它真的会念。两边都查，比只看真账本更严。
if sh "$ROOT/tests/agent/verify.sh" > "$ROOT/build/accept-verify-list.log" 2>&1; then
    n=$(grep -c '"id"' "$ROOT/tests/agent/unverified.json" 2>/dev/null) || n=0
    have=$(grep -c '^WAITING' "$ROOT/build/accept-verify-list.log" 2>/dev/null) || have=0
    [ "${have:-0}" = "${n:-0}" ] || { echo "verify.sh lists nothing（账本 $n 条，只念出 ${have:-0} 条）"; ok=0; }
    if [ "${n:-0}" = 0 ] && grep -q -E '^(RUNNING|SUMMARY)' "$ROOT/build/accept-verify-list.log"; then
        echo "verify.sh 无参数运行时账本是空的，不该印 RUNNING/SUMMARY"; ok=0
    fi
else
    echo "verify.sh lists nothing（无参数那一路退出就不是 0）"; ok=0
fi
# 假账本一条（script 指一条真在磁盘上的：指着不存在的路径是「账本自相矛盾」，verify.sh 会记 PROBLEM、退出 1）
cat > "$ROOT/build/accept-verify-fixture.json" <<'JSON'
{ "entries": [ { "id": "drill-list-me", "feature": "演练：账本里有条目就要念出来",
  "why": "无参数那一路是照着账本念的", "needs": ["亮屏"], "since": "2026-11-16",
  "script": "tests/agent/verify.sh" } ] }
JSON
UNVERIFIED_JSON=build/accept-verify-fixture.json sh "$ROOT/tests/agent/verify.sh" > "$ROOT/build/accept-verify-list-fixture.log" 2>&1 \
    && grep -q '^WAITING drill-list-me' "$ROOT/build/accept-verify-list-fixture.log" \
    || { echo "verify.sh lists nothing（假账本里明明有一条）"; ok=0; }
grep -q 'UnverifiedChecks.run()' "$ROOT/tests/agent/SkillChecks.java" || { echo "SkillChecks never runs UnverifiedChecks"; ok=0; }
grep -q "import android\." "$A/Unverified.java" && { echo "Unverified.java must stay pure (no android.*)"; ok=0; }

# ---- verify-wait 的离线演练（2026-11-12 22:06）：场景说「等 20 分钟再来」，等的是工具，不是人 ----
# 假账本和假脚本都建在 build/ 下，跑完清掉。XINGYI_VERIFY_SLEEP=1 把等待变成 sleep 0（verify.sh 也因此不碰电视：
# 读 dumpsys power 那一路只在真跑的时候走），所以下面几条几秒钟就跑完，一条 adb 都不发。
D="$ROOT/build/verify-wait-drill"
rm -rf "$D" "$ROOT/build/verify-wait-baseline"
mkdir -p "$D"
cat > "$D/refuse-once.sh" <<'SH'
#!/bin/sh
# 第一次拒绝（像 probe.refuse 那样印出还差多少分钟），第二次写证据退出 0
d=$(dirname "$0")
c=$(cat "$d/once.count" 2>/dev/null || echo 0)
c=$((c + 1))
echo "$c" > "$d/once.count"
echo "[   0.1] drill-refuse-once 第 $c 次"
if [ "$c" -lt 2 ]; then
    echo "[   0.1] drill-refuse-once not run: 最后一次按键是 0.4 分钟前（不到 20 分钟）：有人在用电视，不跑"
    exit 3
fi
echo '{"id": "drill-refuse-once", "ok": true}' > "$d/evidence.json"
exit 0
SH
cat > "$D/always-refuse.sh" <<'SH'
#!/bin/sh
# 一直拒绝，而且理由里没有「还差多少分钟」：那一行只能写「不清楚」，不许猜
echo "[   0.1] drill not run: 连不上电视（adb）：不跑"
exit 3
SH
cat > "$D/idle-refuse.sh" <<'SH'
#!/bin/sh
# 一直拒绝，理由是「还差多少分钟就够 20 分钟没人动」：这一类等得出来，预算用完才放弃
echo "[   0.1] drill not run: 最后一次按键是 0.4 分钟前（不到 20 分钟）：有人在用电视，不跑"
exit 3
SH
cat > "$D/wait-once.sh" <<'SH'
#!/bin/sh
# 第一次用 wait 类的理由拒绝（像 probe.refuse 那样印出还差多少分钟），第二次写证据退出 0
d=$(dirname "$0")
c=$(cat "$d/wait.count" 2>/dev/null || echo 0)
c=$((c + 1))
echo "$c" > "$d/wait.count"
echo "[   0.1] drill-wait-once 第 $c 次"
if [ "$c" -lt 2 ]; then
    echo "[   0.1] drill-wait-once not run: 最后一次按键是 1.0 分钟前（不到 20 分钟）：有人在用电视，不跑"
    exit 3
fi
echo '{"id": "drill-wait-once", "ok": true}' > "$d/evidence.json"
exit 0
SH
cat > "$D/standby-refuse.sh" <<'SH'
#!/bin/sh
# 等一百年也不会好的一类：电视在待机，没人会来点亮它
echo "[   0.1] drill-standby not run: 电视在待机：这个场景要一台亮屏的电视，不跑"
exit 3
SH
cat > "$D/silent-refuse.sh" <<'SH'
#!/bin/sh
# 退出 3，可是一个字都不印：说不清为什么不能跑
exit 3
SH
cat > "$D/unknown-minutes.sh" <<'SH'
#!/bin/sh
# wait 类，可是「还差多少分钟」读不出来（分钟数不是数字）：那一行只能写「不清楚」，不许猜
echo "[   0.1] drill-unknown-minutes not run: 最后一次按键是 ? 分钟前（不到 20 分钟）：有人在用电视，不跑"
exit 3
SH
cat > "$D/fails.sh" <<'SH'
#!/bin/sh
echo "[   0.1] drill 跑完了，可是断言不过"
exit 1
SH
cat > "$D/no-evidence.sh" <<'SH'
#!/bin/sh
echo "[   0.1] drill 退出 0，可是什么都没写"
exit 0
SH
cat > "$D/pristine.json" <<'JSON'
{
 "entries": [
  {
   "id": "drill-refuse-once",
   "feature": "演练：第一次拒绝，第二次验过",
   "why": "拒绝要由工具等，不该是人等",
   "needs": ["亮屏", "20 分钟没人动"],
   "since": "2026-11-12",
   "script": "build/verify-wait-drill/refuse-once.sh"
  },
  {
   "id": "drill-always-refuse",
   "feature": "演练：一直拒绝",
   "why": "预算用完要照旧拒绝，条目留在文件里",
   "needs": ["亮屏"],
   "since": "2026-11-12",
   "script": "build/verify-wait-drill/always-refuse.sh"
  },
  {
   "id": "drill-idle-refuse",
   "feature": "演练：一直用「还差多少分钟」的理由拒绝",
   "why": "等得出来的那一类要由工具等到预算用完，条目留在文件里",
   "needs": ["亮屏", "20 分钟没人动"],
   "since": "2026-11-14",
   "script": "build/verify-wait-drill/idle-refuse.sh"
  },
  {
   "id": "drill-wait-once",
   "feature": "演练：wait 类拒绝一次，第二次验过",
   "why": "该等的那一类要重试到验过，不是重试到预算用完",
   "needs": ["亮屏", "20 分钟没人动"],
   "since": "2026-11-14",
   "script": "build/verify-wait-drill/wait-once.sh"
  },
  {
   "id": "drill-standby",
   "feature": "演练：电视在待机，等不到",
   "why": "等不到的那一类要立刻换下一条，一分钟预算都不许花",
   "needs": ["亮屏"],
   "since": "2026-11-14",
   "script": "build/verify-wait-drill/standby-refuse.sh"
  },
  {
   "id": "drill-silent",
   "feature": "演练：退出 3 但一个字都不印",
   "why": "说不清为什么不能跑就交回给人，不猜着等",
   "needs": ["亮屏"],
   "since": "2026-11-14",
   "script": "build/verify-wait-drill/silent-refuse.sh"
  },
  {
   "id": "drill-unknown-minutes",
   "feature": "演练：wait 类，可是分钟数读不出来",
   "why": "读不出还差多少分钟就写不清楚，不许猜一个数",
   "needs": ["亮屏", "20 分钟没人动"],
   "since": "2026-11-14",
   "script": "build/verify-wait-drill/unknown-minutes.sh"
  },
  {
   "id": "drill-fails",
   "feature": "演练：真失败",
   "why": "退出 1 不是拒绝，一次都不许重试",
   "needs": ["亮屏"],
   "since": "2026-11-12",
   "script": "build/verify-wait-drill/fails.sh"
  },
  {
   "id": "drill-no-evidence",
   "feature": "演练：退出 0 但没证据",
   "why": "没证据就是没验过，一次都不许重试",
   "needs": ["亮屏"],
   "since": "2026-11-12",
   "script": "build/verify-wait-drill/no-evidence.sh"
  }
 ]
}
JSON
drill_bad() { echo "verify-wait drill $1: $2"; ok=0; }
want() { grep -q -F -- "$3" "$D/$1.out" || drill_bad "$1" "$2：输出里没有「$3」"; }
nowant() { grep -q -F -- "$3" "$D/$1.out" && drill_bad "$1" "$2：输出里不该有「$3」"; return 0; }
count() { grep -c -F -- "$2" "$D/$1.out" 2>/dev/null || true; }
lineof() { grep -n -m1 -F -- "$2" "$D/$1.out" 2>/dev/null | cut -d: -f1; }
# 每条演练都从一份干净账本开始：一条验过以后账本是要变的
drill() {
    n=$1; shift
    cp "$D/pristine.json" "$D/ledger.json"
    rm -f "$D/evidence.json" "$D/once.count" "$D/wait.count"
    ( cd "$ROOT" && UNVERIFIED_JSON=build/verify-wait-drill/ledger.json \
        XINGYI_EVIDENCE=build/verify-wait-drill/evidence.json XINGYI_VERIFY_SLEEP=1 \
        sh tests/agent/verify.sh "$@" ) > "$D/$n.out" 2>&1
    RC=$?
}


# A：第一次拒绝、第二次验过 —— 等的是工具。顺序要是 REFUSED -> WAITING（还差多少分钟）-> RUNNING -> VERIFIED
drill A --run --only drill-refuse-once --wait 1 --every 0
[ "$RC" = 0 ] || drill_bad A "退出码是 $RC，要 0"
r=$(lineof A "REFUSED drill-refuse-once"); w=$(lineof A "电视还没空闲"); v=$(lineof A "VERIFIED drill-refuse-once")
if [ -z "$r" ] || [ -z "$w" ] || [ -z "$v" ] || [ "$r" -ge "$w" ] || [ "$w" -ge "$v" ]; then
    drill_bad A "顺序不对（REFUSED 在 $r 行、WAITING 在 $w 行、VERIFIED 在 $v 行）"
fi
# N 是从上一条的拒绝理由里算出来的（20 - 0.4），不是猜的
want A "还差多少分钟要算出来" "电视还没空闲 19.6 分钟"
want A "验过了要有总结" "SUMMARY verified 1, refused 0, failed 0"
grep -q -F 'drill-refuse-once' "$D/ledger.json" && drill_bad A "验过了，那一条还在账本里"
echo "  drill A: $(grep -m1 -F 'WAITING drill-refuse-once  电视还没空闲' "$D/A.out")"
echo "  drill A: $(grep -m1 -F 'SUMMARY' "$D/A.out")；账本里还剩 $(grep -c '"id"' "$D/ledger.json" 2>/dev/null || true) 条（跑之前 $(grep -c '"id"' "$D/pristine.json") 条）"

# B：一直拒绝、--wait 0 —— 今天的行为一个字不变：一条 REFUSED，账本没动，status 还是 0
drill B --run --only drill-always-refuse --wait 0
[ "$RC" = 0 ] || drill_bad B "拒绝不是失败，退出码要是 0（拿到 $RC）"
[ "$(count B 'REFUSED drill-always-refuse')" = 1 ] || drill_bad B "要正好一条 REFUSED"
nowant B "预算 0 就不该等" "电视还没空闲"
want B "拒绝要算进总结" "SUMMARY verified 0, refused 1, failed 0"
cmp -s "$D/pristine.json" "$D/ledger.json" || drill_bad B "账本不该动"
echo "  drill B: $(grep -m1 -F 'REFUSED drill-always-refuse' "$D/B.out")"

# C：退出 1 是真失败 —— 立刻停，一次都不许重试（预算给着也不许）
drill C --run --only drill-fails --wait 1 --every 0
[ "$RC" = 1 ] || drill_bad C "真失败要退出 1（拿到 $RC）"
[ "$(count C 'RUNNING drill-fails')" = 1 ] || drill_bad C "不许重试（RUNNING 出现了 $(count C 'RUNNING drill-fails') 次）"
want C "真失败照旧印 FAILED" "FAILED drill-fails  脚本退出 1"
nowant C "真失败不是拒绝，不该等" "电视还没空闲"
want C "失败要算进总结" "SUMMARY verified 0, refused 0, failed 1"
cmp -s "$D/pristine.json" "$D/ledger.json" || drill_bad C "账本不该动"

# D：退出 0 可是没证据 —— 一样立刻停
drill D --run --only drill-no-evidence --wait 1 --every 0
[ "$RC" = 1 ] || drill_bad D "没证据要退出 1（拿到 $RC）"
[ "$(count D 'RUNNING drill-no-evidence')" = 1 ] || drill_bad D "不许重试"
want D "没证据照旧印 NO EVIDENCE" "NO EVIDENCE drill-no-evidence"
nowant D "没证据不该等" "电视还没空闲"
cmp -s "$D/pristine.json" "$D/ledger.json" || drill_bad D "账本不该动"

# E：一直用「还差多少分钟」的理由拒绝、预算给 1 分钟 —— 等完预算照旧拒绝，条目留在文件里。
#    （2026-11-14：E 原来打的是 drill-always-refuse「连不上电视」。按拒绝分两类的规矩那是 "now"，等不到，
#    「等完预算」和「不清楚」两条断言都到不了，所以 E 改打 drill-idle-refuse，断言一条不减；「不清楚」那条
#    搬到下面的 J 节，用一个 wait 类但分钟数读不出来的理由把它逼出来。）
drill E --run --only drill-idle-refuse --wait 1 --every 0
[ "$RC" = 0 ] || drill_bad E "拒绝不是失败，退出码要是 0（拿到 $RC）"
want E "还差多少分钟要算出来" "电视还没空闲 19.6 分钟"
want E "预算用完照旧拒绝" "REFUSED drill-idle-refuse  等了 1.0 分钟电视还是没空闲"
nowant E "拒绝过就是没验过" "VERIFIED"
cmp -s "$D/pristine.json" "$D/ledger.json" || drill_bad E "账本不该动"
echo "  drill E: $(grep -m1 -F 'WAITING drill-idle-refuse  电视还没空闲' "$D/E.out")"
echo "  drill E: $(grep -m1 -F 'REFUSED drill-idle-refuse  等了' "$D/E.out")"

# F：无参数那条老路径要逐字相同（accept 脚本自己跑的就是它）。基线是 HEAD 里的 verify.sh：
#    放到 build/verify-wait-baseline/verify.sh，它 cd 的 ../.. 正好还是仓库根。
mkdir -p "$ROOT/build/verify-wait-baseline"
if git -C "$ROOT" show HEAD:tests/agent/verify.sh > "$ROOT/build/verify-wait-baseline/verify.sh" 2>/dev/null; then
    ( cd "$ROOT" && sh build/verify-wait-baseline/verify.sh ) > "$D/F-old.out" 2>&1
    oldrc=$?
    ( cd "$ROOT" && sh tests/agent/verify.sh ) > "$D/F-new.out" 2>&1
    newrc=$?
    cmp -s "$D/F-old.out" "$D/F-new.out" || { drill_bad F "无参数路径的输出变了"; diff "$D/F-old.out" "$D/F-new.out" | head -10; }
    [ "$oldrc" = "$newrc" ] || drill_bad F "退出码从 $oldrc 变成 $newrc"
    nowant2() { grep -q -F -- "$2" "$D/$1" && drill_bad F "$3：输出里不该有「$2」"; return 0; }
    nowant2 F-new.out "RUNNING" "无参数不跑东西"
    nowant2 F-new.out "SUMMARY" "无参数不跑东西"
    echo "  drill F: 无参数路径与 HEAD 的 verify.sh 逐字相同（$(wc -l < "$D/F-new.out" | tr -d ' ') 行，退出 $newrc）"
else
    drill_bad F "取不到基线（git show HEAD:tests/agent/verify.sh）"
fi

# ---- verify-wait-classes 的离线演练（2026-11-14 03:25）：该等的等、该放下的放下 ----
# 拒绝的理由不是一种。「最后一次按键是 1.0 分钟前（不到 20 分钟）」等 19 分钟就成立了；「电视在待机」等一百年
# 也不会好——没人来点亮它。--wait 45 在一条永远等不到的条目上把预算花光，后面四条一条都跑不到：工具报的一直
# 是「还在等」，可它等的是一个不会来的状态。

# G：wait 类 —— 该等的等：第一次拒绝（还差 19.0 分钟），重试以后验掉
drill G --run --only drill-wait-once --wait 5 --every 0
[ "$RC" = 0 ] || drill_bad G "退出码是 $RC，要 0"
r=$(lineof G "REFUSED drill-wait-once"); w=$(lineof G "电视还没空闲"); v=$(lineof G "VERIFIED drill-wait-once")
if [ -z "$r" ] || [ -z "$w" ] || [ -z "$v" ] || [ "$r" -ge "$w" ] || [ "$w" -ge "$v" ]; then
    drill_bad G "顺序不对（REFUSED 在 $r 行、WAITING 在 $w 行、VERIFIED 在 $v 行）"
fi
[ "$(count G 'RUNNING drill-wait-once')" = 2 ] || drill_bad G "要正好跑两次（拿到 $(count G 'RUNNING drill-wait-once') 次）"
want G "还差多少分钟要算出来" "电视还没空闲 19.0 分钟"
want G "验过了要有总结" "SUMMARY verified 1, refused 0, failed 0, waited "
grep -q -F 'drill-wait-once' "$D/ledger.json" && drill_bad G "验过了，那一条还在账本里"
echo "  drill G: $(grep -m1 -F 'WAITING drill-wait-once  电视还没空闲' "$D/G.out")"
echo "  drill G: $(grep -m1 -F 'SUMMARY' "$D/G.out")"

# H：now 类 —— 该放下的放下：电视在待机，一条 REFUSED，一次都不重试，预算一分钟都没花掉，条目留在账本里
drill H --run --only drill-standby --wait 5 --every 0
[ "$RC" = 0 ] || drill_bad H "拒绝不是失败，退出码要是 0（拿到 $RC）"
[ "$(count H 'REFUSED drill-standby')" = 1 ] || drill_bad H "要正好一条 REFUSED（拿到 $(count H 'REFUSED drill-standby') 条）"
[ "$(count H 'RUNNING drill-standby')" = 1 ] || drill_bad H "不许重试（RUNNING 出现了 $(count H 'RUNNING drill-standby') 次）"
[ "$(count H 'WAITING')" = 1 ] || drill_bad H "除了开头那条清单，不该有第二行 WAITING（拿到 $(count H 'WAITING') 行）"
nowant H "等不到的就不该等" "电视还没空闲"
want H "理由要抄出来，还要说清等不到" "REFUSED drill-standby  电视在待机：这个场景要一台亮屏的电视，不跑（这个等不到，条目留在文件里，下次自己再来 / 或者先让它变成能跑的样子）"
want H "预算一分钟都没花掉" "SUMMARY verified 0, refused 1, failed 0, waited 0.0"
grep -q -F 'drill-standby' "$D/ledger.json" || drill_bad H "条目要留在账本里"
cmp -s "$D/pristine.json" "$D/ledger.json" || drill_bad H "账本不该动"
echo "  drill H: $(grep -m1 -F 'REFUSED drill-standby' "$D/H.out")"
echo "  drill H: $(grep -m1 -F 'SUMMARY' "$D/H.out")"

# I：退出 3 可是一个字都不印 —— 说不清为什么不能跑就交回给人，不猜着等，事后也不静默
drill I --run --only drill-silent --wait 5 --every 0
[ "$RC" = 0 ] || drill_bad I "拒绝不是失败，退出码要是 0（拿到 $RC）"
[ "$(count I 'RUNNING drill-silent')" = 1 ] || drill_bad I "不许重试（RUNNING 出现了 $(count I 'RUNNING drill-silent') 次）"
want I "说不清要说出来" "REFUSED drill-silent  说不清为什么不能跑（这个等不到，条目留在文件里，下次自己再来 / 或者先让它变成能跑的样子）"
nowant I "不许猜着等" "电视还没空闲"
want I "脚本什么都没印，这件事也要说出来" "（脚本一个字都没印）"
want I "预算一分钟都没花掉" "SUMMARY verified 0, refused 1, failed 0, waited 0.0"
cmp -s "$D/pristine.json" "$D/ledger.json" || drill_bad I "账本不该动"
echo "  drill I: $(grep -m1 -F 'REFUSED drill-silent' "$D/I.out")"

# J：wait 类，可是「还差多少分钟」读不出来 —— 那一行只能写「不清楚」，不许猜一个数（这条覆盖原来是 E 的）
drill J --run --only drill-unknown-minutes --wait 1 --every 0
[ "$RC" = 0 ] || drill_bad J "拒绝不是失败，退出码要是 0（拿到 $RC）"
want J "拿不到就说不清楚，不猜" "电视还没空闲 不清楚 分钟"
want J "预算用完照旧拒绝" "REFUSED drill-unknown-minutes  等了 1.0 分钟电视还是没空闲"
nowant J "拒绝过就是没验过" "VERIFIED"
cmp -s "$D/pristine.json" "$D/ledger.json" || drill_bad J "账本不该动"
echo "  drill J: $(grep -m1 -F 'WAITING drill-unknown-minutes  电视还没空闲' "$D/J.out")"

# K：两个一起跑要挡住（2026-11-14 12:30 真机撞出来的）：两个 verify.sh 共用 build/verify-out.txt 和 verify-rc，
#    一个的尾行会被抄进另一个的拒绝理由，两边还各自开场景抢电视。锁在一个活着的 pid 手上 -> 第二个立刻退出 1，
#    一条场景都不许跑。锁在一个死掉的 pid 手上 -> 接过来照常跑（一次崩溃不许把之后所有的跑都挡在门外）。
# 清掉演练用的锁：只清自己那把（死 pid / 没 pid），发现有个活着的 verify.sh 就跳过并说出来——
# 无条件 rm -rf 会把真在跑的那一轮的锁拆了，正好是这一轮要防的事。
clear_lock() {
    h=$(cat "$LOCK/pid" 2>/dev/null || true)
    if [ -n "$h" ] && kill -0 "$h" 2>/dev/null; then
        # pid 活着不等于那是 verify.sh：演练自己往锁里塞过 `sleep 30 &` 的 pid，进程退出以后这个 pid 会被系统
        # 复用（2026-11-16 接手时三次撞上：一个 sleep 顶着 1428834/1471805 让 O 一直跳过）。命令行里没有
        # verify.sh 就是残锁。读不到 /proc 时按「活着」算——宁可跳过，也不拆一个真的。
        if grep -q "verify.sh" "/proc/$h/cmdline" 2>/dev/null; then
            echo "现在有 verify.sh 在跑（pid $h），跳过锁的演练"; LOCK_BUSY=1; ok=0; return 1
        fi
        echo "锁里那个 pid $h 不是 verify.sh，当残锁清掉"
    fi
    rm -rf "$LOCK" "$LOCK.stale."*
    return 0
}
LOCK_BUSY=0
clear_lock || true
mkdir -p "$LOCK"
sleep 300 &
holder=$!
echo "$holder" > "$LOCK/pid"
cp "$D/pristine.json" "$D/ledger.json"
( cd "$ROOT" && UNVERIFIED_JSON=build/verify-wait-drill/ledger.json XINGYI_VERIFY_SLEEP=1 \
    sh tests/agent/verify.sh --run --only drill-refuse-once --wait 1 --every 0 ) > "$D/K.out" 2>&1
RC=$?
kill "$holder" 2>/dev/null
[ "$RC" = 1 ] || drill_bad K "有一个在跑，第二个要退出 1（拿到 $RC）"
want K "要说清是谁在跑" "已经有一个在跑（pid $holder）"
nowant K "第二个一条场景都不许跑" "RUNNING drill-refuse-once"
nowant K "第二个不许动账本" "VERIFIED"
cmp -s "$D/pristine.json" "$D/ledger.json" || drill_bad K "第二个不该动账本"
echo "  drill K: $(grep -m1 -F '已经有一个在跑' "$D/K.out")"

# L：锁里是一个已经死掉的 pid —— 接过来，照常跑
echo 999999 > "$LOCK/pid"
drill L --run --only drill-refuse-once --wait 0
[ "$RC" = 0 ] || drill_bad L "接过死锁以后要照常跑（退出码 $RC）"
want L "接锁要说一声" "接过一个没人管的锁（里面是 pid 999999）"
want L "接过来就照常列账本" "WAITING drill-refuse-once"
[ -d "$LOCK" ] && drill_bad L "跑完了要把锁放掉"
echo "  drill L: $(grep -m1 -F '接过一个没人管的锁' "$D/L.out")"

# ---- 锁的属主、信号退出、残锁接管（2026-11-16 评审的三条）----
# 场景在后台跑（XINGYI_VERIFY_SCENARIO=slow），trap 才有东西可杀；slow 脚本拿到锁、写出 marker、睡到被杀为止。
cat > "$D/slow.sh" <<'SH'
#!/bin/sh
d=$(dirname "$0")
printf 'running %s\n' "$$" > "$d/slow.started"
i=0
while [ "$i" -lt 300 ]; do sleep 0.1; i=$((i + 1)); done
printf 'slow %s\n' "$$" > "$d/slow.evidence.json"
exit 0
SH
cat > "$D/slow.json" <<'JSON'
{
 "entries": [
  {
   "id": "drill-slow",
   "feature": "演练：一个跑得很慢、可以被 TERM 掉的场景",
   "why": "锁要在 TERM 之后才放得掉，属主才认得出谁是谁",
   "needs": ["亮屏"],
   "since": "2026-11-16",
   "script": "build/verify-wait-drill/slow.sh"
  }
 ]
}
JSON
# 等一个文件出现（等场景拿到锁），最多 100 × 0.1 秒
await_file() { i=0; while [ "$i" -lt 100 ]; do [ -e "$1" ] && return 0; sleep 0.1; i=$((i + 1)); done; return 1; }
# 等锁目录消失（等于等持有者真正退出），最多 50 × 0.1 秒
await_nolock() { i=0; while [ "$i" -lt 50 ]; do [ -d "$LOCK" ] || return 0; sleep 0.1; i=$((i + 1)); done; return 1; }
drill_slow() {   # $1 = 输出名；在后台起一个 --run --wait，pid 写进 $D/$1.pid
    rm -f "$D/slow.started" "$D/slow.evidence.json" "$D/$1.rc"
    # 直接后台起 verify.sh（它自己 cd 到仓库根）：包一层 ( cd … && … ) 的话 $! 是那个子 shell，
    # kill 它就只是杀了个壳，verify.sh 还在跑——正是这一轮要防的那种「杀错 pid」。
    UNVERIFIED_JSON=build/verify-wait-drill/slow.json XINGYI_VERIFY_SLEEP=1 \
        sh "$ROOT/tests/agent/verify.sh" --run --wait 1 --every 0 > "$D/$1.out" 2>&1 &
    echo $! > "$D/$1.pid"
}
# 第二个实例：同一条账本、同一条场景，几秒就该被挡回去（exit 1）
bystander() {
    ( cd "$ROOT" && UNVERIFIED_JSON=build/verify-wait-drill/slow.json XINGYI_VERIFY_SLEEP=1 \
        sh tests/agent/verify.sh --run --only drill-slow --wait 1 --every 0 ) > "$D/$1.out" 2>&1
    echo $? > "$D/$1.rc"
}

# M：TERM —— 拿到锁以后 kill -TERM，要 ①以 143 退出 ②锁目录放掉 ③期间第二个实例一直被挡 ④不许「锁没了人还活着」。
#    「人」指的是场景（它才是碰电视的那个）：slow.sh 把自己的 pid 写进 slow.started，锁消失的那一刻它必须已经没了。
#    验外层 sh 在同一毫秒死掉是不合题意的——一个 shell 总要把 exit 走完；11-14 12:33 那个窟窿是场景活下来。
clear_lock || true
cp "$D/slow.json" "$D/ledger.json"
drill_slow M
mpid=$(cat "$D/M.pid")
if await_file "$D/slow.started"; then
    spid=$(sed -n 's/^running \([0-9][0-9]*\)$/\1/p' "$D/slow.started" | head -1)
    # ①③ 先趁它还持锁，让第二个实例撞上去：必须被挡回去（exit 1），一条场景都不许跑
    bystander M-second
    [ "$(cat "$D/M-second.rc")" = 1 ] || drill_bad M "第二个实例要被挡（退出码 $(cat "$D/M-second.rc")）"
    grep -q -F "已经有一个在跑（pid $mpid）" "$D/M-second.out" || drill_bad M "第二个实例没说是谁在跑（pid $mpid）"
    grep -q -F 'RUNNING drill-slow' "$D/M-second.out" && drill_bad M "第二个实例跑起来了"
    cmp -s "$D/slow.json" "$D/ledger.json" || drill_bad M "账本不该动"
    # ②④ 发 TERM；锁消失的那一刻，场景必须已经没了
    kill -TERM "$mpid" 2>/dev/null
    lockgone=0
    i=0
    while [ "$i" -lt 50 ]; do
        if [ ! -d "$LOCK" ]; then
            lockgone=1
            if [ -n "$spid" ] && kill -0 "$spid" 2>/dev/null; then
                drill_bad M "锁没了，场景还活着（scenario pid $spid）"
                kill -KILL "$spid" 2>/dev/null
            fi
            break
        fi
        sleep 0.1; i=$((i + 1))
    done
    [ "$lockgone" = 1 ] || drill_bad M "TERM 之后 5 秒锁目录还挂着"
    i=0
    while [ "$i" -lt 50 ] && kill -0 "$mpid" 2>/dev/null; do sleep 0.1; i=$((i + 1)); done
    kill -0 "$mpid" 2>/dev/null && { drill_bad M "TERM 之后 5 秒还没退出（pid $mpid）"; kill -KILL "$mpid" 2>/dev/null; }
    wait "$mpid" 2>/dev/null
    MRC=$?
    printf '%s\n' "$MRC" > "$D/M.rc"
    [ "$MRC" = 143 ] || drill_bad M "TERM 要以 143 退出（拿到 $MRC）"
else
    drill_bad M "场景一直没拿到锁（没写出 slow.started）"
    kill -TERM "$mpid" 2>/dev/null
fi
echo "  drill M: TERM 后退出 $(cat "$D/M.rc" 2>/dev/null)；$(grep -m1 -F '已经有一个在跑' "$D/M-second.out" 2>/dev/null)"

# N：属主 —— A 拿锁；B 撞上去被挡（它退出时不许删 A 的锁）；等 A 自然退出，B 的锁没了但也没删错人。
#    以前 trap 'rm -rf' 不看属主：B 一退出就把 A 的锁删了（哪怕 A 还活得好好的）。
clear_lock || true
cp "$D/slow.json" "$D/ledger.json"
drill_slow N-a
apid=$(cat "$D/N-a.pid")
if await_file "$D/slow.started"; then
    # B 在 A 还持锁的时候退出：它的 release 读到的 pid 是 A 的，就不许删
    bystander N-b
    [ "$(cat "$D/N-b.rc")" = 1 ] || drill_bad N "B 要被挡（退出码 $(cat "$D/N-b.rc")）"
    h=$(cat "$LOCK/pid" 2>/dev/null)
    [ "$h" = "$apid" ] || drill_bad N "B 退出以后锁该还是 A 的 pid $apid（拿到「$h」）"
    [ -d "$LOCK" ] || drill_bad N "B 退出时把 A 的锁删了"
    [ -f "$LOCK/pid" ] || drill_bad N "B 退出时把 A 的 pid 文件删了"
    # 第三个实例仍然被挡——A 还活着、锁还在
    bystander N-third
    [ "$(cat "$D/N-third.rc")" = 1 ] || drill_bad N "第三个实例要被挡（退出码 $(cat "$D/N-third.rc")）"
    grep -q -F "已经有一个在跑（pid $apid）" "$D/N-third.out" || drill_bad N "第三个实例该看到 A 在跑"
    # A 自然退出，它自己的锁要放掉；场景也得等它没了再往下走（不然它会跑进 drill O，O 的「现在有 verify.sh 在跑」就是它）
    spid=$(sed -n 's/^running \([0-9][0-9]*\)$/\1/p' "$D/slow.started" | head -1)
    kill -TERM "$apid" 2>/dev/null
    i=0
    while [ "$i" -lt 50 ] && [ -d "$LOCK" ]; do sleep 0.1; i=$((i + 1)); done
    [ -d "$LOCK" ] && drill_bad N "A 退了以后自己的锁还挂着"
    wait "$apid" 2>/dev/null
    i=0
    while [ "$i" -lt 50 ] && [ -n "$spid" ] && kill -0 "$spid" 2>/dev/null; do sleep 0.1; i=$((i + 1)); done
    [ -n "$spid" ] && kill -0 "$spid" 2>/dev/null && { drill_bad N "A 退了，场景还活着（scenario pid $spid）"; kill -KILL "$spid" 2>/dev/null; }
else
    drill_bad N "A 没拿到锁"
    kill -TERM "$apid" 2>/dev/null
fi
echo "  drill N: B 被挡 $(cat "$D/N-b.rc" 2>/dev/null)，A 退出后锁 $( [ -d "$LOCK" ] && echo 还在 || echo 放掉了 )"

# O：只有目录、没有 pid 的锁 —— 读不到 pid 不等于死锁：先等、不打印「接过…」，而且持有者后来写出 pid，
#    第二个实例仍不许进去。锁是新的（mtime 刚刚），所以 10 秒那一段不会触发；持有者在 2 秒后写出一个活着的 pid。
clear_lock || true
mkdir -p "$LOCK"
cp "$D/slow.json" "$D/ledger.json"
sleep 30 &
oholder=$!
( sleep 2; printf '%s\n' "$oholder" > "$LOCK/pid" ) &
later=$!
bystander O
obrc=$(cat "$D/O.rc")
wait "$later"
nowant O "读不到 pid 不等于死锁，不许说接过" "接过"
nowant O "pid 空的时候也不许跑" "RUNNING"
[ "$obrc" = 1 ] || drill_bad O "第二个实例要被挡（退出码 $obrc）"
grep -q -F "已经有一个在跑（pid $oholder）" "$D/O.out" || drill_bad O "持有者写出 pid 以后仍要说清是谁在跑（pid $oholder）"
[ -d "$LOCK" ] || drill_bad O "O 不该把别人的锁拆了"
clear_lock || true
kill "$oholder" 2>/dev/null
echo "  drill O: $(grep -m1 -F '已经有一个在跑' "$D/O.out" 2>/dev/null)"

rm -rf "$D" "$ROOT/build/verify-wait-baseline"

[ $ok = 1 ] && echo VERIFY DURABLE OK
