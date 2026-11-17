#!/bin/sh
# XiaoAi music bridge on the TV.
#   bridge.sh install          install + grant READ_LOGS/DUMP/WRITE_SECURE_SETTINGS (development permissions) + start
#                              + bind Eyes (星仪's accessibility service) again
#   bridge.sh start | stop | uninstall
#   bridge.sh keepadb on|off   the bridge switches ADB debugging back on when the TV's firmware turns it off (on = grant
#                              WRITE_SECURE_SETTINGS, off = revoke it); see AdbGuardPolicy.java
#   bridge.sh adbwatch         pull + show files/adb-watch.txt (when the TV's adb listener went away / came back)
#   bridge.sh status           service state, permissions, NetEase now playing, last log lines
#   bridge.sh log              pull files/bridge-log.txt
#   bridge.sh say "<text>"     feed <text> to the song path as if XiaoAi had recognized it (DUMP-protected hook)
#   bridge.sh ask "<text>"     let the model answer <text> aloud, as if XiaoAi had refused it
#   bridge.sh mode all|refusals   all = 星仪接管全部请求 (XiaoAi only listens, acts, lends its voice); refusals = earlier
#   python provision.py        give the app its model config (key from the Windows user environment, never printed)
#   bridge.sh line "<line>"    feed one raw logcat line (tests the parser + uid check)
#   bridge.sh asr [n]          XiaoAi's recent recognition lines straight from logcat
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")"
TV=${TV:-192.168.31.162:5555}
A="/h/tools/platform-tools/adb.exe -s $TV"
PKG=xyz.erapi0neer.xiaoaibridge
D=/sdcard/Android/data/$PKG/files
b64() { printf '%s' "$1" | base64 -w0; }
W=$(pwd -W 2>/dev/null || pwd)   # MSYS_NO_PATHCONV keeps /h/... literal; Windows programs need H:/...
cmd=$1; shift
case "$cmd" in
  install)
    $A install -r build/xiaoai-bridge.apk || exit 1
    # which APK is on the TV now (2026-11-16): the handoff page used to take the newest APK on disk for the installed
    # one, so a build that was never installed made it say 「装上的东西都在真机上见过」 while an install was waiting
    # on the user. built_ms is the APK file's own time; tools/handoff reads this before it guesses from build*/.
    # Not under build/: build.sh deletes that whole directory first (11-18: the 11-16 record was gone after the rebuild).
    python -c "import hashlib,json,os,sys,time; p='build/xiaoai-bridge.apk'; json.dump({'built_ms': int(os.path.getmtime(p)*1000), 'installed_ms': int(time.time()*1000), 'sha256': hashlib.sha256(open(p,'rb').read()).hexdigest(), 'source': 'bridge.sh install'}, open('installed-apk.json','w'), indent=1)"         || echo "install recorded nowhere (installed-apk.json not written)" >&2
    $A shell pm grant $PKG android.permission.READ_LOGS || exit 1
    $A shell pm grant $PKG android.permission.DUMP || exit 1
    $A shell pm grant $PKG android.permission.PACKAGE_USAGE_STATS; $A shell appops set $PKG GET_USAGE_STATS allow
    # keeps ADB debugging on after the firmware switches it off at standby; `bridge.sh keepadb off` takes it away again
    $A shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS && echo "keepadb: on (revoke with: bridge.sh keepadb off)"
    # the permission gids only apply to a fresh process
    $A shell am force-stop $PKG
    $A shell am start-foreground-service -n $PKG/.BridgeService
    # Eyes (星仪's accessibility service) comes out of a reinstall + force-stop unbound, and sometimes dropped from the
    # enabled list: take it out and put it back (whatever else is enabled stays), which makes the system bind it again
    EYES=$PKG/xyz.erapi0neer.xingyi.agent.tv.Eyes
    CUR=$($A shell settings get secure enabled_accessibility_services | tr -d '\r')
    REST=$(printf '%s' "$CUR" | tr ':' '\n' | grep -v -x -F -e "$EYES" -e null -e '' | paste -sd: -)
    if [ -n "$REST" ]; then $A shell settings put secure enabled_accessibility_services "$REST"
    else $A shell settings delete secure enabled_accessibility_services >/dev/null; fi
    sleep 1
    $A shell settings put secure enabled_accessibility_services "${REST:+$REST:}$EYES"
    $A shell settings put secure accessibility_enabled 1
    # XiaoAi's own service may write the list back a few seconds later from the moment Eyes was out (2026-11-05
    # 12:48: re-added at :23, gone again at :28): watch for 12 s and put it back (no removal this time) if it went
    for try in 1 2 3; do
      sleep 4
      NOW=$($A shell settings get secure enabled_accessibility_services | tr -d '\r')
      [ "$NOW" = null ] && NOW=
      case ":$NOW:" in *":$EYES:"*) continue ;; esac
      $A shell settings put secure enabled_accessibility_services "${NOW:+$NOW:}$EYES"
      echo "eyes: put back after the list was rewritten (try $try)"
    done
    echo "eyes: re-enabled (${REST:+kept $REST})" ;;
  keepadb)
    case "${1:-}" in
      on) $A shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS && echo "keepadb: on" ;;
      off) $A shell pm revoke $PKG android.permission.WRITE_SECURE_SETTINGS && echo "keepadb: off" ;;
      *) echo "usage: bridge.sh keepadb on|off"; exit 2 ;;
    esac ;;
  adbwatch) mkdir -p out; $A pull $D/adb-watch.txt out/adb-watch.txt >/dev/null && cat out/adb-watch.txt ;;
  start) $A shell am start-foreground-service -n $PKG/.BridgeService ;;
  stop) $A shell am force-stop $PKG ;;
  uninstall) $A uninstall $PKG ;;
  status)
    $A shell am broadcast -a $PKG.STATUS >/dev/null; sleep 2
    $A shell cat $D/status.txt; echo
    $A shell dumpsys activity services $PKG | grep -E "ServiceRecord|isForeground" | head -3
    # what is installed but has not been checked on the real path yet: the list is a file (verify-durable, 2026-11-12)
    U=$(python "$W/../voice/assistant/tests/agent/unverified_status.py" 2>/dev/null)
    [ -n "$U" ] && echo "unverified: $U"
    echo "--- adb watch (last lines) ---"; $A shell tail -n 6 $D/adb-watch.txt 2>/dev/null ;;
  log) mkdir -p out; $A pull $D/bridge-log.txt out/bridge-log.txt >/dev/null && cat out/bridge-log.txt ;;
  say) $A shell am broadcast -a $PKG.SIMULATE --es text_b64 "$(b64 "$1")" >/dev/null && echo "sent: $1" ;;
  ask) $A shell am broadcast -a $PKG.ASK --es text_b64 "$(b64 "$1")" >/dev/null && echo "asked: $1" ;;
  mode)
    case "${1:-}" in
      all|refusals) $A shell am broadcast -a $PKG.MODE --es mode "$1" >/dev/null && echo "mode: $1" ;;
      *) echo "usage: bridge.sh mode all|refusals   (all = 星仪接管全部请求, refusals = 只接小爱做不到的)"; exit 2 ;;
    esac ;;
  enable) $A shell pm enable $PKG && $A shell am start-foreground-service -n $PKG/.BridgeService ;;
  disable) $A shell am force-stop $PKG; $A shell pm disable-user --user 0 $PKG ;;
  line) $A shell am broadcast -a $PKG.LINE --es line_b64 "$(b64 "$1")" >/dev/null && echo "sent line" ;;
  asr) $A shell logcat -d -v threadtime -v uid -v epoch -s '=vc=en=arp=:I' | grep onAsrResult | tail -n "${1:-10}" ;;
  *) echo "unknown: $cmd"; exit 2 ;;
esac
