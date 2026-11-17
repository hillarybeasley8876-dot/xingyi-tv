"""One controlled Xiaomi turn, with the shipped on-TV service and real MCP runtime."""
import argparse
import json
import re
import shlex
import time
from deploy_voice_mcp import Run, VOICE, APP
from probe_native_handoff import ask
from probe_once_voice import account_selection

QUERY = "你能读取星仪助手内部的本机休眠定时吗"
CONTROL = "一加一等于几"
OUT = VOICE / "evidence-once" / "automatic.json"


def broadcast(tv, action, args=""):
    # Only public status fields are returned; the local account selector is never printed.
    cmd = "am broadcast -n " + APP + "/.AutoRelayDiagnostic -a " + APP + ".RELAY_" + action
    if action in ("TRIAL", "ENABLE"):
        cmd = account_selection() + 'test -n "$account" || exit 5\nresult=$(' + cmd + ' --es owner "$account" ' + args + '); '
    else:
        cmd = 'result=$(' + cmd + '); '
    raw = tv.shell(cmd + """printf '%s' "$result" | sed -n '/Broadcast completed:/p'""")
    matched = re.search(r'result=1, data="?(\{.*\})',raw)
    if not matched:
        raise RuntimeError("relay_status_unavailable")
    data = json.loads(matched[1])
    return {k:data[k] for k in ("phase","accepted_count","enabled","controlled_sample","known_test_accepted") if k in data}


def wait(tv, predicate, seconds):
    until = time.monotonic()+seconds
    value = {}
    while time.monotonic()<until:
        value = broadcast(tv,"STATUS")
        if predicate(value):
            break
        time.sleep(.45)
    return value


def main(enable, live=False):
    tv = Run("192.168.31.162:5555")
    tv.deadline = time.monotonic()+110
    assert tv.shell("getprop ro.product.model") == "MiTV-ASTP0" and tv.shell("getprop ro.product.device") == "mulan"
    tv.home_package = tv.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").splitlines()[-1].split('/')[0]
    apk = VOICE/("xingyi-0.7.1.apk" if live else "xingyi-0.7.apk")
    version, digest = tv.local_info(apk,APP)
    assert tv.version(APP) in (9,10,version)
    report = {"path":"Xiaomi native text/NLP, actual provider and on-TV MCP; no acoustic input"}
    trial_started = False
    try:
        if tv.installed_hash(APP)!=digest:
            tv.adb("install","-r",str(apk),timeout=15)
        assert tv.installed_hash(APP)==digest and tv.version(APP)==version
        report["installed_sha256"]=digest
        tv.home()
        if live:
            assert broadcast(tv,"STATUS")["enabled"], "owner_permission_not_preserved"
            broadcast(tv,"VERIFY")
        else:
            assert not broadcast(tv,"STATUS")["enabled"], "already_enabled"
            broadcast(tv,"TRIAL","--es expected "+shlex.quote(QUERY))
            trial_started=True
        armed=wait(tv,lambda s:s["phase"]=="armed",9)
        assert armed["phase"]=="armed", "reader_not_armed"
        before=armed["accepted_count"]
        ask(tv,CONTROL)
        time.sleep(7)
        r=broadcast(tv,"STATUS")
        report["native_control_count_unchanged"]=before==r["accepted_count"]
        assert report["native_control_count_unchanged"]
        tv.home()
        ask(tv,QUERY)
        report["original"]=QUERY
        state=wait(tv,lambda s:s["accepted_count"]>before,25)
        report["after"]=state
        report["one_utterance_accepted"]=state["accepted_count"]==before+1 and (not live or state["known_test_accepted"])
        if report["one_utterance_accepted"]:
            end=time.monotonic()+16
            while time.monotonic()<end:
                if tv.front()==APP:
                    # Only return whether the expected fresh result or title was displayed; no UI dump leaves the TV.
                    check=tv.shell("uiautomator dump /proc/self/fd/1 2>/dev/null | grep -oE '当前[^<>\"]{0,25}未设置[^<>\"]{0,18}(休眠|定时)|未设置定时休眠|没有[^<>\"]{0,12}休眠定时' | head -c 180",ok=False)
                    if check:
                        report["timer_answer_visible"]=True
                        break
                time.sleep(.5)
        state=broadcast(tv,"STATUS")
        report["at_most_once"]=state["accepted_count"]-before<=1
        report["passed"]=bool(report["one_utterance_accepted"] and report["at_most_once"] and report.get("timer_answer_visible"))
    finally:
        tv.deadline=time.monotonic()+16
        if trial_started:
            broadcast(tv,"DISABLE")
        tv.home()
        if enable and report.get("passed"):
            broadcast(tv,"ENABLE","--ez owner_opt_in true")
            final=wait(tv,lambda s:s.get("enabled") and s["phase"]=="armed",7)
            report["enabled"]=final.get("enabled") and final["phase"]=="armed"
        file = OUT.with_name("automatic-live.json") if live else OUT
        file.parent.mkdir(exist_ok=True)
        file.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf8")
        print(json.dumps(report,ensure_ascii=True))
    return 0 if report.get("passed") else 1


if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--enable-after-verified",action="store_true")
    parser.add_argument("--live-enabled",action="store_true")
    args=parser.parse_args()
    raise SystemExit(main(args.enable_after_verified,args.live_enabled))
