#!/bin/sh
# The resumable verification run (verify-durable, 2026-11-12). What is still unverified is read from
# tests/agent/unverified.json, so a session that dies takes nothing with it: the next run lists the same entries.
#
#   sh tests/agent/verify.sh                             list what is waiting, in since order; nothing is run
#   sh tests/agent/verify.sh --run                       run every entry that names a script
#   sh tests/agent/verify.sh --run --only ktcp-pause-real
#   sh tests/agent/verify.sh --run --wait 25 --every 60  let this script wait (up to 25 min, retrying every 60 s)
#
# 退出 3 是场景在说「现在不能验」——拒绝，不是失败：条目留在文件里，status 还是 0。
# 场景开头那句「最近 20 分钟没人动过」以前是人在等（每跑一条就要手写个循环读 dumpsys power，2026-11-12 22:06）；
# --wait 给了这一轮的等待预算，就由这个脚本来等：每 --every 秒重跑同一条，直到它给出 0/1 或者预算用完。
# 预算用完照旧印 REFUSED 跳过。XINGYI_VERIFY_SLEEP 给了就 sleep 0（等待本身不碰设备），离线演练几秒跑得完。
#
# 拒绝的理由不是一种（2026-11-14 03:25）：只有「最后一次按键是 X 分钟前（不到 20 分钟）」等得出来，
# 别的（电视在待机、前面不是桌面、读屏服务没开、音频服务说没在放、sleeping-episode 要的待机）等人来也不会变。
# probe.refuse_class 分这两类，verify.sh 只等 "wait"；"now" 立刻印一条 REFUSED 换下一条，一分钟预算都不花——
# 今晚账本里五条要的电视状态互相排斥，在一条永远等不到的条目上把 45 分钟花光，后面四条一条都跑不到。
# 读不出拒绝理由（probe.refusal_of 是 None）也当 "now"：说不清就交回给人，不猜着等，并把场景最后几行抄进日志。
#
# It never touches the TV itself: it only runs the script an entry names, and only with --run. An entry leaves the
# file (through Unverified.without, so the rest of the text is untouched) only when that script exits 0 AND evidence
# appeared — the file in $XINGYI_EVIDENCE, or something under tests/agent/evidence/ written during the run.
# $UNVERIFIED_JSON points the whole thing at another file (a copy, to try --run without touching the real ledger).
set -u
cd "$(dirname "$0")/../.."
ROOT=$(pwd)
JSON=${UNVERIFIED_JSON:-tests/agent/unverified.json}
JDK="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin"
OUT=build/agent-core-test
LIB=tests/lib/json-20240303.jar
SEP=$(printf '\037')

RUN=0
ONLY=""
WAIT=0        # 这一轮 --run 的等待预算，分钟；0 = 今天的行为（遇见拒绝就跳过）
EVERY=60      # 重试间隔，秒
usage() {
    echo "用法：sh tests/agent/verify.sh [--run] [--only <id>] [--wait <分钟>] [--every <秒>]" >&2
    exit 2
}
# 分钟和秒都得是非负整数：一个负数会让 [ ] 的比较反过来，然后要么不等要么空转
num() {
    case "$2" in
        ''|*[!0-9]*) echo "verify: $1 要一个非负整数（拿到「$2」）" >&2; exit 2 ;;
    esac
}
while [ $# -gt 0 ]; do
    case "$1" in
        --run) RUN=1 ;;
        --only) [ $# -ge 2 ] || usage; ONLY="$2"; shift ;;
        --wait) [ $# -ge 2 ] || usage; num --wait "$2"; WAIT="$2"; shift ;;
        --every) [ $# -ge 2 ] || usage; num --every "$2"; EVERY="$2"; shift ;;
        *) usage ;;
    esac
    shift
done

# 秒 -> 分钟，一位小数；0 也印 0.0，免得「等了 0 分钟」看着像没跑过
mins() { awk -v s="$1" 'BEGIN { printf "%.1f", s / 60 }'; }

# sleep 不是按键：它不碰设备，电视的 mLastUserActivityTime 不会因为它变。可离线演练不能真等 20 分钟，
# 所以给了 XINGYI_VERIFY_SLEEP 就 sleep 0。
nap() {
    if [ -n "${XINGYI_VERIFY_SLEEP:-}" ]; then sleep 0; else sleep "$1"; fi
}

# 还要再空闲几分钟才够 20 分钟（verify-wait 的 WAITING 行里那个 N）。先从上一条的拒绝理由里读——probe.refuse
# 印的是「最后一次按键是 X 分钟前（不到 Y 分钟）」；读不到就自己问一次电视（probe.idle_minutes 读的就是
# dumpsys power 的 mLastUserActivityTime）；再读不到就说不清楚，不猜。
idle_left() {
    a=$(sed -n 's/.*最后一次按键是 \([0-9][0-9.]*\) 分钟前（不到 \([0-9][0-9]*\) 分钟）.*/\1 \2/p' "$1" 2>/dev/null | head -1)
    # 离线演练（XINGYI_VERIFY_SLEEP）不碰电视：读 dumpsys power 也是碰，所以那一路只在真跑的时候走
    if [ -z "$a" ] && [ -z "${XINGYI_VERIFY_SLEEP:-}" ] && [ -f tests/agent/realpath/probe.py ]; then
        a=$(python -c 'import sys; sys.path.insert(0, "tests/agent/realpath"); import probe; print("%.1f %d" % (probe.idle_minutes(), probe.IDLE_MIN))' 2>/dev/null)
    fi
    set -- ${a:-}
    [ $# -eq 2 ] || { printf '不清楚'; return; }
    case "$1" in ''|*[!0-9.]*) printf '不清楚'; return ;; esac
    case "$2" in ''|*[!0-9]*) printf '不清楚'; return ;; esac
    awk -v idle="$1" -v want="$2" 'BEGIN { d = want - idle; printf "%.1f", (d > 0 ? d : 0) }'
}

mkdir -p build || { echo "verify: 建不了 build/（锁和日志都放那儿）" >&2; exit 1; }
[ -f "$JSON" ] || { echo "verify: 没有 $JSON" >&2; exit 1; }

# 一次只许跑一个（2026-11-14 12:30 真机上撞出来的）：两个 verify.sh 同时跑，共用 build/verify-out.txt 和
# build/verify-rc，一个的尾行会被抄进另一个的拒绝理由里，还各自开着场景——12:28 那次就是：我把上一轮挂在管道
# 后面、TaskStop 只杀了外层，里面的 sh 活到 12:33，在电视上和第二轮抢同一个播放页。锁用一个目录（mkdir 是原子的），
# 里面写 pid；pid 死了（或不是 verify.sh）就把锁接过来，不让一次崩溃把之后所有的跑都挡在门外。
#
# 锁只在属主是自己时才放（2026-11-16 评审）：以前是 trap 'rm -rf build/verify.lock' EXIT INT TERM，一是收到
# TERM 只删锁不退出（「锁没了人还活着」，第二个实例正好进来），二是不看属主——B 退出时会把 A 的锁删掉。
# 锁的路径可以被演练改掉（2026-11-16 演练自己抓到的）：accept_verify_durable 会往锁里塞一个假的持有者
# （`sleep 30 &` 的 pid），跟真机那一轮共用一把锁的话，下一次跑会把它当成「有个 verify.sh 还活着」，整段锁演练
# 被跳过；pid 还会被系统复用，指到一个毫不相干的进程上。真机运行永远用默认值。
LOCK=${XINGYI_VERIFY_LOCK:-build/verify.lock}
# 锁可以住在别的目录里（演练就是这么用的）：父目录不存在时 mkdir 会一直失败，等满 200 轮才退出 1，
# 看上去像「有人占着锁」。自己建出来。
mkdir -p "$(dirname "$LOCK")" || { echo "verify: 建不了锁目录 $(dirname "$LOCK")" >&2; exit 1; }
LOCK_TMP=$LOCK.tmp.$$
child=""
release() {
    h=$(cat "$LOCK/pid" 2>/dev/null || true)
    [ "${h:-}" = "$$" ] && rm -rf "$LOCK"
    return 0
}
# INT/TERM：先停掉还在跑的场景，等它真没了，再放锁，再按 128+signal 退出。只放锁不退出是不行的：脚本会接着往下跑，
# 而第二个实例已经能拿到锁了。child 是场景自己的 pid（不是 tee 的），kill 它就是 kill 了里面的 adb/python。
# 放锁之前要等它退（2026-11-16 演练抓到的）：光发 TERM 就放锁，锁没了的那个瞬间场景还在收尾——正是 11-14 12:33
# 「锁没了人还活着」的同一个窟窿，只是窗口短。等不到就 KILL，再等；真等不到也照放，不能把第二个实例永远挡在门外。
stop() {
    if [ -n "${child:-}" ]; then
        kill "$child" 2>/dev/null
        k=0
        while [ "$k" -lt 20 ] && kill -0 "$child" 2>/dev/null; do sleep 0.1; k=$((k + 1)); done
        if kill -0 "$child" 2>/dev/null; then
            kill -KILL "$child" 2>/dev/null
            k=0
            while [ "$k" -lt 20 ] && kill -0 "$child" 2>/dev/null; do sleep 0.1; k=$((k + 1)); done
        fi
        wait "$child" 2>/dev/null
    fi
    release
    exit "$1"
}
trap release EXIT
trap 'stop 130' INT
trap 'stop 143' TERM

# 锁目录的 mtime 到没到 10 秒：没 pid 的锁要先等一会儿再判断，新人刚 mkdir、还没写 pid 的那个窗口不是残锁。
# find -mmin 只到分钟（不到一分钟一律是 0），所以先问 stat 要秒。
lock_age_ok() {
    now=$(date +%s)
    m=$(stat -c %Y "$LOCK" 2>/dev/null) || m=""
    case "$m" in
        ''|*[!0-9]*) [ -z "$(find "$LOCK" -maxdepth 0 -mmin +0 2>/dev/null)" ] && return 1; return 0 ;;
    esac
    [ $((now - m)) -ge 10 ]
}
# 接管用重命名而不是 rm -rf：两个实例同时判定这把锁是残的，只有一个的 mv 会成功，另一个看到锁目录还在、
# 接着按「有人在跑」处理。移走以后还要复核里面的 pid 仍是刚才那个死的——不然可能把别人刚拿到的活锁拆了。
steal_lock() {
    dead=$1
    mv "$LOCK" "$LOCK.stale.$$" 2>/dev/null || return 1
    h=$(cat "$LOCK.stale.$$/pid" 2>/dev/null || true)
    if [ "${h:-}" = "$dead" ]; then
        rm -rf "$LOCK.stale.$$"
        echo "verify: 接过一个没人管的锁（里面是 pid ${dead:-?}）" >&2
        return 0
    fi
    # 里面已经换人了：放回原处，当有人在跑
    mv "$LOCK.stale.$$" "$LOCK" 2>/dev/null || rm -rf "$LOCK.stale.$$"
    return 1
}
# 有人在跑就退出 1，不留第二份场景；这句话三处都要用
busy() {
    echo "verify: 已经有一个在跑（pid $1）——两个一起跑会互相覆盖 build/verify-out.txt 和 build/verify-rc，" \
         "还各自开场景抢电视。等它跑完，或者先停掉它" >&2
    exit 1
}
acquire_lock() {
    n=0
    err=""
    while [ "$n" -lt 200 ]; do
        n=$((n + 1))
        # 原子发布：先在自己的 tmp 里写好 pid，再 mv 过去。mv 到已有的目录上在 GNU mv 里是把源目录塞进去、
        # 返回 0（验过），所以 mv 之后要复核 tmp 没了、锁里的 pid 是自己，不能只看退出码。
        rm -rf "$LOCK_TMP"
        if mkdir "$LOCK_TMP" 2>/dev/null; then
            echo $$ > "$LOCK_TMP/pid"
            mv "$LOCK_TMP" "$LOCK" 2>/dev/null
            h=$(cat "$LOCK/pid" 2>/dev/null || true)
            if [ "${h:-}" = "$$" ] && [ ! -d "$LOCK_TMP" ]; then
                return 0
            fi
            rm -rf "$LOCK_TMP"
            # mv 塞进去的那一份也要清掉，不然别人的锁里会越积越多 tmp 目录
            rm -rf "$LOCK/$(basename "$LOCK_TMP")"
        else
            err=$(mkdir "$LOCK_TMP" 2>&1)
            mkdir -p build 2>/dev/null
        fi
        other=$(cat "$LOCK/pid" 2>/dev/null || true)
        if [ -n "$other" ] && kill -0 "$other" 2>/dev/null; then
            busy "$other"
        fi
        if [ -z "$other" ] && [ -e "$LOCK" ]; then
            # 目录在、pid 空：可能是新人刚 mkdir、还没写 pid 的那个窗口。等一下重读，最多 5 次（1 秒）；
            # 还是空、而且这把锁不新（10 秒），才当残锁。
            k=0
            while [ "$k" -lt 5 ]; do
                sleep 0.2
                other=$(cat "$LOCK/pid" 2>/dev/null || true)
                [ -n "$other" ] && break
                k=$((k + 1))
            done
            if [ -n "$other" ]; then
                kill -0 "$other" 2>/dev/null && busy "$other"
            elif lock_age_ok; then
                steal_lock "" && continue
                [ -e "$LOCK" ] && { echo "verify: build/verify.lock 读不出 pid，也动不了它——先手动看一眼" >&2; exit 1; }
                continue
            fi
        elif [ -n "$other" ]; then
            [ -e "$LOCK" ] || continue
            steal_lock "$other" && continue
        fi
        sleep 0.2
    done
    echo "verify: 等了 $((n / 5)) 秒还拿不到 build/verify.lock（最后一次的错误：${err:-没有}）" >&2
    exit 1
}
acquire_lock

# 场景为什么不能跑：probe.refusal_of 从这一轮的输出里找出那一句（找不到就是空串）。句子是中文，走文件不走
# argv——这台机器上 python 读命令行的编码不是 UTF-8，从 argv 进去的句子会被嚼烂。stdout 也要重新配成 UTF-8，
# 默认是 GBK，印出来的字节和 shell 里 grep 的原文对不上。
refusal_of() {
    python -c 'import sys; sys.path.insert(0, "tests/agent/realpath"); import probe; sys.stdout.reconfigure(encoding="utf-8"); print(probe.refusal_of(open(sys.argv[1], encoding="utf-8", errors="replace").read()) or "")' "$1" 2>/dev/null
}
# 这一句拒绝属于哪一类：wait（等一下会好的）/ now（等一百年也不会好）。分不出来（python 跑不起来）也当 now。
refuse_class() {
    printf '%s\n' "$1" > build/verify-refusal.txt
    python -c 'import sys; sys.path.insert(0, "tests/agent/realpath"); import probe; print(probe.refuse_class(open(sys.argv[1], encoding="utf-8").read()))' build/verify-refusal.txt 2>/dev/null
}
CLS="$OUT/xyz/erapi0neer/xingyi/agent/Unverified.class"
# 编出来的类要是比源码旧，就重编一次：拿旧类跑出来的清单是假的
if [ ! -f "$CLS" ] || [ -n "$(find src/xyz/erapi0neer/xingyi/agent -name '*.java' -newer "$CLS" 2>/dev/null | head -1)" ]; then
    "$JDK/javac.exe" -J-XX:ActiveProcessorCount=2 --release 8 -encoding UTF-8 -nowarn -Xlint:-options \
        -cp "$LIB" -d "$OUT" src/xyz/erapi0neer/xingyi/agent/*.java \
        || { echo "verify: 核心编译不过" >&2; exit 1; }
fi
un() {
    "$JDK/java.exe" -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8 \
        -cp "$OUT;$LIB" xyz.erapi0neer.xingyi.agent.Unverified "$@"
}

# 和 Unverified.find 一样的找法：root 下面，再往上三级（探针在仓库外的草稿树里）
resolve() {
    d="$ROOT"
    i=0
    while [ "$i" -le 3 ]; do
        [ -f "$d/$1" ] && { printf '%s\n' "$d/$1"; return 0; }
        d=$(dirname "$d")
        i=$((i + 1))
    done
    return 1
}

status=0
verified=0
refused=0
failed=0
# the JVM is a native .exe: Git Bash's /h/... is read as H:\h\... when the shell has MSYS_NO_PATHCONV=1, and a script
# that exists is then reported as missing (2026-11-12 04:16)
ROOT_WIN=$(cygpath -w "$ROOT" 2>/dev/null || printf '%s' "$ROOT")
un problems "$JSON" "$ROOT_WIN" > build/verify-problems.txt || exit 1
while IFS= read -r p; do
    [ -n "$p" ] || continue
    echo "PROBLEM $p"
    status=1
    # 账本本身有问题也是这一轮的失败：SUMMARY 说 failed 0 而退出码是 1，会让人以为没事
    failed=$((failed + 1))
done < build/verify-problems.txt
un list "$JSON" > build/verify-list.txt || exit 1

spent=0        # 这一轮已经等掉的秒数（--wait 是整轮的预算，不是每条一份）
waited=0       # 这一轮真等掉的秒数（SUMMARY 里那个 waited；演练里 nap 是 sleep 0，所以它老老实实是 0）
while IFS="$SEP" read -r id since script needs; do
    [ -n "$id" ] || continue
    [ -n "$ONLY" ] && [ "$ONLY" != "$id" ] && continue
    if [ -z "$script" ]; then
        echo "WAITING $id  needs $needs  script 没有  since $since"
        continue
    fi
    echo "WAITING $id  needs $needs  script $script  since $since"
    [ "$RUN" = 1 ] || continue
    path=$(resolve "$script") || { echo "MISSING $id  script $script 在磁盘上没有"; status=1; failed=$((failed + 1)); continue; }
    entry=0     # 这一条自己等掉的秒数（REFUSED 行里那个 X）
    rc_lost=0
    while : ; do
        echo "RUNNING $id  $path"
        t0=$(date +%s)
        : > build/verify-stamp
        # 拒绝的理由要从这一轮的输出里读（分类、"还差多少分钟"），所以输出留一份在 build/ 里——--wait 0 那一路
        # 也留，否则读不到理由就只能猜。脚本的退出码另外写进 build/verify-rc（POSIX sh 没有 PIPESTATUS）。
        #
        # 场景在后台跑、前台等（2026-11-16 评审）：以前是 `( 场景 ) | tee build/verify-out.txt`，$! 是 tee 的 pid，
        # kill 它只打断管道，里面真在跑的 adb/python 活下来（11-14 12:33 那次就是这样，电视停在播放页、音量 3）。
        # 现在把输出直接重定向进同一个文件，$! 就是这个场景自己——trap 里 kill 的才是它。
        case "$path" in
            *.sh) sh "$path" < /dev/null > build/verify-out.txt 2>&1 & ;;
            *) python "$path" < /dev/null > build/verify-out.txt 2>&1 & ;;
        esac
        # $! 是这条后台命令自己的 pid（不是子 shell 的、不是 tee 的）：trap 里 kill "$child" 才真杀得到
        # 场景里的 adb/python。不能只把 tee 放后台——那样 $! 是 tee，kill 它只打断管道。
        child=$!
        wait "$child"
        rc=$?
        child=""
        printf '%s\n' "$rc" > build/verify-rc
        # 要把这一轮的输出给前台的看（accept 的演练读的就是它）：等它跑完再抄一遍 stdout，和以前 tee 出来的字一样
        cat build/verify-out.txt 2>/dev/null
        rc=$(cat build/verify-rc 2>/dev/null)
        # 读不到退出码就当没验成：不知道结果的时候，往「还没验」那边倒
        case "$rc" in ''|*[!0-9]*) rc=1; rc_lost=1 ;; esac
        [ "$rc" -eq 3 ] || break
        # 退出 3 是场景自己说「现在不能验」（电视有人在用、在待机、读屏没开）——拒绝，不是失败：条目留在文件里，
        # 等下次再来。印成 FAILED 会让人以为东西坏了（2026-11-12 22:06：一次 --run 里后两条就是这么被印成 FAILED 的）。
        took=$(( $(date +%s) - t0 ))
        real=$took
        # 每轮至少算一秒：--every 0 配上一个一直拒绝的脚本，不这么算就是空转
        [ "$took" -ge 1 ] || took=1
        refusal=$(refusal_of build/verify-out.txt)
        class=$(refuse_class "$refusal")
        if [ "$class" != "wait" ]; then
            # 等不到的那一类（待机、前面不是桌面、读屏没开、音频服务说没在放、要的是待机的电视），还有读不出理由
            # 的那一类：立刻换下一条，一分钟预算都不花。把预算烧在一条不会变的状态上，后面几条一条都跑不到。
            if [ -n "$refusal" ]; then
                printf '%s\n' "REFUSED $id  $refusal（这个等不到，条目留在文件里，下次自己再来 / 或者先让它变成能跑的样子）"
            else
                printf '%s\n' "REFUSED $id  说不清为什么不能跑（这个等不到，条目留在文件里，下次自己再来 / 或者先让它变成能跑的样子）"
                # 不知道就抄下场景最后说了什么，给人看；不猜着等
                if [ -s build/verify-out.txt ]; then
                    tail -n 5 build/verify-out.txt | sed 's/^/    | /'
                else
                    printf '%s\n' "    | （脚本一个字都没印）"
                fi
            fi
            refused=$((refused + 1))
            break
        fi
        # 只有「还差多少分钟就够 20 分钟没人动」等得出来，预算允许就由这个脚本等：等 --every 秒，重跑同一条，
        # 直到它给出 0/1 或者预算用完。
        left=$((WAIT * 60 - spent - took))
        if [ "$left" -le 0 ] || [ "$left" -lt "$EVERY" ]; then
            echo "REFUSED $id  等了 $(mins "$entry") 分钟电视还是没空闲，条目留在文件里"
            refused=$((refused + 1))
            break
        fi
        echo "REFUSED $id  场景说现在不能验（退出 3），$EVERY 秒以后再来"
        echo "WAITING $id  电视还没空闲 $(idle_left build/verify-out.txt) 分钟（再等 $EVERY 秒，预算还剩 $(mins "$left") 分钟）"
        n0=$(date +%s)
        nap "$EVERY"
        napped=$(( $(date +%s) - n0 ))
        # waited 是真等掉的：这一轮跑掉的时间 + 真睡掉的时间（演练里 sleep 0，就老实算 0，不算成 EVERY）
        waited=$((waited + real + napped))
        spent=$((spent + EVERY + took))
        entry=$((entry + EVERY + took))
    done
    [ "$rc" -eq 3 ] && continue
    if [ "$rc" -ne 0 ]; then
        if [ "$rc_lost" = 1 ]; then
            echo "FAILED $id  读不到脚本的退出码（build/verify-rc），条目留在文件里"
        else
            echo "FAILED $id  脚本退出 $rc（条目留在文件里）"
        fi
        status=1
        failed=$((failed + 1))
        continue
    fi
    ev=""
    # 证据得是这一轮跑出来的：上一条留下的文件不算（不然一条什么都没干的脚本会被当成验过了）
    if [ -n "${XINGYI_EVIDENCE:-}" ] && [ -s "$XINGYI_EVIDENCE" ] && [ "$XINGYI_EVIDENCE" -nt build/verify-stamp ]; then
        ev="$XINGYI_EVIDENCE"
    else
        ev=$(find tests/agent/evidence -type f -newer build/verify-stamp 2>/dev/null | head -1)
    fi
    if [ -z "$ev" ]; then
        echo "NO EVIDENCE $id  脚本退出 0，可是没写出证据（条目留在文件里）"
        status=1
        failed=$((failed + 1))
        continue
    fi
    un without "$JSON" "$id" > build/verify-without.json \
        || { echo "FAILED $id  写不回 $JSON"; status=1; failed=$((failed + 1)); continue; }
    [ -s build/verify-without.json ] || { echo "FAILED $id  写回来的文件是空的（$JSON 没动）"; status=1; failed=$((failed + 1)); continue; }
    mv build/verify-without.json "$JSON"
    verified=$((verified + 1))
    echo "VERIFIED $id  evidence $ev"
done < build/verify-list.txt

[ "$RUN" = 1 ] && echo "SUMMARY verified $verified, refused $refused, failed $failed, waited $(mins "$waited")"
exit $status
