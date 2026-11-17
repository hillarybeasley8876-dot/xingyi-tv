"""Search Bilibili once via Xingyi or Xiaomi; never click a result, consent, login, or Play."""
import argparse
import json
import re
import runpy
import subprocess
import threading
import time
from pathlib import Path
from deploy_voice_mcp import ADB, APP, Run, VOICE
from probe_native_handoff import ask
from verify_quiet_reply import TAGS, inspect, relay
from verify_tv_apps import PKG, SEARCH, trace

QUERY="打开B站搜索"+SEARCH


def surface(r):
    if r.front()!=PKG:return {"screen":"outside_bili","exact_keyword":False}
    component=r.shell("dumpsys window windows | grep -m1 mCurrentFocus",ok=False)
    name=re.search(r"u0 tv\.danmaku\.bili/([.\w$]+)",component)
    script=r"""timeout 6 uiautomator dump --compressed /proc/self/fd/1 2>/dev/null | tr '<' '\n' |
      while IFS= read -r n; do
        case "$n" in *'package="tv.danmaku.bili"'*) ;; *) continue;; esac
        echo in_app
        if test -n "$n"; then
          id=$(printf '%s\n' "$n" | sed -nE 's/.*resource-id="(tv.danmaku.bili:id\/[a-zA-Z0-9_]+)" class="([a-zA-Z0-9_.$]+)".*/\1|\2/p')
          case "$n" in *'text="星辰亻旅行者"'*|*'content-desc="星辰亻旅行者"'*) echo exact; echo "query_node=$id";; esac
          case "$n" in *'text="综合"'*|*'text="视频"'*|*'text="番剧"'*|*'text="用户"'*) echo search_tab;; esac
          case "$id" in *:id/title*|*video_title*|*search_result*|*result_card*|*empty*) echo "result_node=$id";; esac
          case "$n" in *没有搜到*|*未找到相关*|*暂无搜索结果*|*没有找到*|*暂时没有搜索结果*|*没有相关结果*|*什么都没有*|*啥都木有*) echo empty;; esac
          case "$n" in *请检查网络*|*网络连接失败*|*网络出错*|*服务器开小差*) echo network_error;; esac
          case "$n" in *'resource-id="tv.danmaku.bili:id/agree"'*'text='*|*'text="同意并继续"'*) echo consent;; esac
          case "$n" in *'text="扫码登录"'*|*'text="手机号登录"'*|*'text="短信验证码"'*) echo login;; esac
        fi
      done | sort -u"""
    raw=r.shell(script,timeout=9,ok=False)
    flags={v for v in raw.splitlines() if v in {"in_app","exact","search_tab","empty","network_error","consent","login"}}
    ids=lambda prefix: sorted({v[len(prefix):] for v in raw.splitlines() if re.fullmatch(prefix+r"tv\.danmaku\.bili:id/[a-zA-Z0-9_]+\|[a-zA-Z0-9_.$]+",v)})[:14]
    results=ids("result_node=")
    screen=("consent_required" if "consent" in flags else "login_required" if "login" in flags
            else "network_error" if "network_error" in flags else "empty" if "empty" in flags
            else "results" if "search_tab" in flags and results else "query_shown" if "exact" in flags else "unclassified")
    return {"component":name[1] if name else None,"screen":screen,"exact_keyword":"exact" in flags,
            "query_nodes":ids("query_node="),"candidate_result_nodes":results,"search_tabs":"search_tab" in flags}


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--mode",choices=("native","reviewed"),required=True)
    p.add_argument("--output",type=Path,required=True);a=p.parse_args()
    if a.output.exists():p.error("Choose a fresh output")
    r=Run("192.168.31.162:5555");r.deadline=time.monotonic()+106
    assert r.shell("getprop ro.product.model")=="MiTV-ASTP0" and "mWakefulness=Awake" in r.shell("dumpsys power | grep -m1 mWakefulness=")
    consent=json.loads((VOICE/"evidence-bili/authorized-consent.json").read_text(encoding="utf8"))
    assert consent["user_authorized"] and consent["verified_package"]==PKG and r.version(PKG)==8911100
    before=surface(r)
    if before["screen"] in ("login_required","consent_required"):raise RuntimeError(before["screen"])
    report={"mode":a.mode,"original":QUERY,"keyword":SEARCH,"before":before,"driver_search_intents":0,"consent_or_login_clicks":0,
            "played_video":False,"requests":0,"verified":False}
    b=relay(r);start=int(r.shell("date +%s"));lines=[];proc=None;worker=None
    try:
        if a.mode=="native":
            pid=r.shell("pidof com.xiaomi.voicecontrol");assert pid.isdecimal() and b["enabled"]
            proc=subprocess.Popen([str(ADB),"-s",r.serial,"shell","logcat","-T","1","--pid="+pid,"-b","main","-v","threadtime","-v","monotonic",*TAGS,"*:S"],stdout=subprocess.PIPE)
            def read():
                for row in proc.stdout:
                    if len(row)<4096 and len(lines)<12000:lines.append(row.decode("utf8","replace"))
            worker=threading.Thread(target=read,daemon=True);worker.start();time.sleep(.4);lines.clear()
            report["requests"]=1;ask(r,QUERY)
        else:
            remote=runpy.run_path(str(VOICE/"assistant/voice_remote.pyw"));report["requests"]=1
            rid=remote["send_transcript"](QUERY);approved=None
            end=time.monotonic()+50
            while time.monotonic()<end:
                rev=remote["review_request"](rid)
                if rev.state=="pending":
                    if approved!=rev.preview_b64:
                        allowed={"第 1 次操作确认\n1. 在 哔哩哔哩 搜索「"+SEARCH+"」",
                                 "第 1 次操作确认\n1. 在哔哩哔哩搜索「"+SEARCH+"」"}
                        if approved is not None or rev.text.strip() not in allowed:
                            remote["review_request"](rid,"cancel");raise RuntimeError("Unexpected preview; cancelled")
                        approved=rev.preview_b64;report["approved_preview"]=rev.text
                        assert remote["review_request"](rid,"confirm",approved).state=="working"
                if rev.state=="done":report["answer"]=rev.text[:300];break
                if rev.state=="stale":raise RuntimeError("Lost user request")
                time.sleep(.45)
        # Android permits a single active UiAutomation session. Observe the tool trace
        # until its own accessibility probe finishes, then make the independent UI read.
        if a.mode=="native":
            end=time.monotonic()+30
            while time.monotonic()<end:
                if any("after_tool · search_video ·" in row for row in trace(r,"search_video",start)):break
                time.sleep(.65)
        end=time.monotonic()+22
        while time.monotonic()<end:
            s=surface(r);report["ui"]=s
            if s["exact_keyword"] and s["screen"] in ("results","empty"):break
            if s["screen"] in ("consent_required","login_required"):break
            time.sleep(.6)
        after=relay(r);delta=after["accepted_count"]-b["accepted_count"]
        report["handoff_delta"]=delta;report["last_cause"]=after.get("last_cause")
        if proc:
            report["native"]=inspect(lines.copy(),QUERY)
        rows=trace(r,"search_video",start);report["harness_trace"]=rows
        starts=sum(row.startswith("tool_start · search_video · ") for row in rows)
        exact=s.get("exact_keyword") and s.get("screen") in ("results","empty")
        fresh_native=(a.mode=="native" and delta==0 and report["native"].get("matched_dialogs")==1 and not before.get("exact_keyword"))
        dispatched=starts==1 and (a.mode=="reviewed" or delta==1 and after.get("last_cause") in ("confirmed_refusal","explicit_refusal"))
        report["app_verified_result"]=any("after_tool · search_video · observed" in row for row in rows)
        report["verified"]=bool(exact and (dispatched and report["app_verified_result"] or fresh_native))
    except (ValueError,RuntimeError,AssertionError) as e:report["error"]=str(e)[:200]
    finally:
        if proc:
            proc.terminate();proc.wait(timeout=5);worker.join(2)
        a.output.parent.mkdir(parents=True,exist_ok=True)
        with a.output.open("x",encoding="utf8") as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({k:report.get(k) for k in ("mode","verified","handoff_delta","ui","error")},ensure_ascii=True))
    return 0 if report["verified"] else 1

if __name__=="__main__":raise SystemExit(main())
