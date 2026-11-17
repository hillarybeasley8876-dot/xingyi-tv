"""Run a bounded Xingyi production request. Package installation is exclusively a harness tool action."""
import argparse
import json
import re
import runpy
import subprocess
import tempfile
import threading
import time
from urllib.parse import quote
from pathlib import Path
from deploy_voice_mcp import APP, ADB, VOICE, TOOLS, Run

PKG = "tv.danmaku.bili"
REFERENCE = VOICE / "bili-install/bilibili-official-arm32.apk"
SEARCH = "星辰亻旅行者"
CASES = {
    "status": ("请读取电视当前实际媒体音量，回答当前数值和最大值。","get_status",None),
    "list": ("请读取电视可打开的应用列表，列出实际名称，并告诉我哔哩哔哩在不在列表里。","list_apps",None),
    "install": ("安装哔哩哔哩官方 Android 客户端。","install_app","安装哔哩哔哩官方 Android 客户端（tv.danmaku.bili）"),
    "open": ("打开哔哩哔哩","open_app","打开 哔哩哔哩"),
    "search": ("打开B站搜索"+SEARCH,"search_video","在 哔哩哔哩 搜索「"+SEARCH+"」"),
}


def trace(run, target, started):
    last = ""
    for _ in range(8):
        run.shell("am broadcast -p "+APP+" -a "+APP+".DEBUG --es op trace")
        time.sleep(.22)
        path="/sdcard/Android/data/"+APP+"/files/harness-trace.txt"
        stamp=run.shell("stat -c %Y "+path,ok=False)
        if not stamp.isdigit() or int(stamp)<started:continue
        last = run.shell("cat "+path,ok=False)
        if re.search(r"(?m)^before_tool · "+target+r" · ",last) and "turn_end" in last: break
    return [row for row in last.splitlines() if re.match(r"^(?:before_tool|tool_start|after_tool|turn_end|turn_cancel|confirmation_(?:requested|granted)|model_route) · ",row)]


def ui(run):
    if run.front()!=PKG: return []
    # The host receives named flags only. No screen image, title history, query/account values or SMS data.
    rows=run.shell("""timeout 5 uiautomator dump --compressed /proc/self/fd/1 2>/dev/null | tr '<' '\\n' | while IFS= read -r s; do
    case "$s" in *'package="tv.danmaku.bili"'*) ;; *) continue;; esac; echo visible;
    case "$s" in *隐私政策*|*用户协议*|*个人信息保护*) echo legal_text;; esac
    case "$s" in *'text="同意"'*|*'text="同意并继续"'*|*'text="同意并使用"'*) echo agree_control;; esac
    case "$s" in *'text="不同意"'*|*'text="不同意并退出"'*|*'text="拒绝"'*) echo decline_control;; esac
    case "$s" in *扫码登录*|*手机号登录*|*短信验证码*|*登录注册*) echo login_control;; esac
    case "$s" in *搜索*) echo search_ui;; esac
    case "$s" in *星辰亻旅行者*) echo query_visible;; esac
    case "$s" in *'text="综合"'*|*'text="视频"'*|*'text="番剧"'*) echo results_tab;; esac
    done | sort -u""",timeout=8,ok=False).splitlines()
    return sorted(set(rows)&{"visible","legal_text","agree_control","decline_control","login_control","search_ui","query_visible","results_tab"})


def volume(run):
    text=run.shell("media volume --stream 3 --get")
    match=re.search(r"volume is (\d+) in range \[(\d+)\.\.(\d+)\]",text)
    if not match: raise RuntimeError("Unable to read media volume")
    return [int(v) for v in match.groups()]


def progress_ui(run):
    if run.front()!=APP:return []
    # Emit progress values plus constrained labels only. The rest of the hierarchy stays on the device.
    data=run.shell("""timeout 5 uiautomator dump --compressed /proc/self/fd/1 2>/dev/null | tr '<' '\\n' | while IFS= read -r n; do
     case "$n" in *'package="xyz.erapi0neer.xingyi"'*) ;; *) continue;; esac
     case "$n" in *执行过程*|*工作台*|*执行记录*|*工具进度*|*任务进度*) echo workflow;; esac
     case "$n" in *正在从哔哩哔哩官方下载*) echo downloading;; esac
     printf '%s\\n' "$n" | sed -nE 's/.*已下载[[:space:]]*([0-9]{1,3})%.*/percent=\\1/p'
     case "$n" in *下载完成*|*已验证官方签名*) echo verifying;; esac
     case "$n" in *交给*安装器*|*正在核对电视实际安装*) echo installing;; esac
     case "$n" in *核对通过*已安装*|*已安装哔哩哔哩官方*|*'tv.danmaku.bili 已安装'*) echo completed;; esac
     done | sort -u""",timeout=7,ok=False)
    return sorted({s for s in data.splitlines() if s in {"workflow","downloading","verifying","installing","completed"}
                   or re.fullmatch(r"percent=(?:100|[1-9]?\d)",s)})


def save_star_screen(run,path):
    def foreground():
        return run.front()==APP and " u0 "+APP+"/" in run.shell("dumpsys window windows | grep -m1 mCurrentFocus",ok=False)
    if path.exists() or not foreground():return False
    frame=subprocess.run([str(ADB),"-s",run.serial,"exec-out","screencap","-p"],capture_output=True,timeout=8)
    if frame.returncode or not frame.stdout.startswith(b"\x89PNG\r\n\x1a\n") or not foreground():return False
    path.parent.mkdir(parents=True,exist_ok=True)
    with path.open("xb") as output:output.write(frame.stdout)
    return True


def observe_install_ui(serial,folder,finished,observations):
    run=Run(serial);run.deadline=time.monotonic()+440
    began=time.monotonic()
    for index in range(28):
        if finished.is_set():return
        try:
            stages=progress_ui(run)
            if stages:
                observations.append({"seconds_after_confirm":round(time.monotonic()-began,1),"labels":stages})
                if any(s.startswith("percent=") or s in {"verifying","installing"} for s in stages):
                    snap=folder/"harness-install-progress.png"
                    if save_star_screen(run,snap):observations[-1]["screenshot"]=snap.name
        except (RuntimeError,subprocess.TimeoutExpired): pass
        if finished.wait(1.5 if index<10 else 9):return


def manifest(run,path):
    version,digest=run.local_info(path,PKG)
    signer=TOOLS/"android-sdk/android-15/apksigner.bat"
    certs=run.command([signer,"verify","--print-certs",path],timeout=35)
    fingerprints=re.findall(r"(?im)^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})\s*$",certs)
    if not fingerprints: raise RuntimeError("APK signing certificate could not be verified")
    return {"package":PKG,"version_code":version,"sha256":digest,"signer_sha256":sorted(fingerprints)}


def installed_identity(run,reference):
    version=run.version(PKG)
    if version is None:return None
    digest=run.installed_hash(PKG)
    # Byte-identical APK carries the same cryptographically verified certificate.
    if digest==reference["sha256"] and version==reference["version_code"]:
        return dict(reference)
    # Diagnose a different installed release without sending an APK onto the TV.
    base=next((p[8:] for p in run.shell("pm path "+PKG).splitlines() if p.startswith("package:") and p.endswith("/base.apk")),"")
    if not re.fullmatch(r"/data/app/[A-Za-z0-9=/_.+~-]+/base\.apk",base):raise RuntimeError("Unexpected installed base APK path")
    with tempfile.TemporaryDirectory(prefix="xingyi-bili-verified-") as temp:
        out=Path(temp)/"base.apk"
        run.adb("pull",base,str(out),timeout=80)
        return manifest(run,out)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case",choices=CASES,required=True)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--reference",type=Path,default=REFERENCE)
    parser.add_argument("--verify-existing",action="store_true",help="Test the harness's idempotent already-installed result, not a fresh installation")
    args=parser.parse_args()
    if args.output.exists():parser.error("Choose a fresh output path")
    if args.verify_existing and args.case!="install":parser.error("--verify-existing only applies to install")
    q,tool,operation=CASES[args.case]
    r=Run("192.168.31.162:5555"); r.deadline=time.monotonic()+(580 if args.case=="install" else 125)
    if r.shell("getprop ro.product.model")!="MiTV-ASTP0" or "mWakefulness=Awake" not in r.shell("dumpsys power | grep -m1 mWakefulness="):
        raise RuntimeError("Expected awake MiTV not available")
    remote=runpy.run_path(str(VOICE/"assistant/voice_remote.pyw"))
    send,review=remote["send_transcript"],remote["review_request"]
    base=r.volume()
    old_front=r.front()
    report={"case":args.case,"query":q,"input":"production VOICE_QUERY / request-bound VOICE_REVIEW",
            "foreground_before":old_front,"volume_before":base,"install_commands_by_driver":0,
            "observed_tool_starts":0,"verified":False}
    watch_stop=threading.Event();watch=None
    try:
        initial=r.version(PKG)
        report["installed_version_before"]=initial
        if args.case in ("install","open","search"):
            official=manifest(r,args.reference)
            report["official_apk"]=official
            if args.verify_existing and installed_identity(r,official)!=official:
                raise RuntimeError("Exact official release must already be installed for this check")
            if args.case=="install" and initial is not None and not args.verify_existing:
                report["installed"]=installed_identity(r,official)
                raise RuntimeError("Already installed; this run cannot claim a new harness install")
            if args.case!="install" and installed_identity(r,official)!=official:raise RuntimeError("Verified official client is not installed")
        elif args.case=="status": report["volume_range"]=volume(r)
        if args.case=="search":
            flags=ui(r);report["ui_before"]=flags
            if "visible" not in flags:raise RuntimeError("Bilibili foreground and onboarding state not established")
            if ("legal_text" in flags and ("agree_control" in flags or "decline_control" in flags)) or "login_control" in flags:
                raise RuntimeError("Bilibili consent or login screen; no action taken")
            search=r.shell("cmd package resolve-activity --brief -a android.intent.action.VIEW -d "
                           "'bilibili://search?keyword="+quote(SEARCH)+"' -p "+PKG)
            if not any(s.strip().startswith(PKG+"/") for s in search.splitlines()):
                raise RuntimeError("Official Bilibili search URI not resolvable for the installed client")
        started=int(r.shell("date +%s"))
        request=send(q); report["submitted"]=True; approved=False; approved_token=None
        end=time.monotonic()+(75 if args.case=="install" else 68)
        while time.monotonic()<end:
            try:cur=review(request)
            except RuntimeError:
                if not approved:raise
                # Approval is single-use even if its acknowledgement or a later status read is lost.
                report["status_read_retried"]=True;time.sleep(1.25);continue
            report["review_state"]=cur.state
            if cur.state=="pending":
                lines=cur.text.strip().splitlines(); expected="1. "+operation if operation else ""
                if approved and cur.preview_b64==approved_token:
                    time.sleep(.8);continue
                if approved or operation is None or lines!=["第 1 次操作确认",expected]:
                    review(request,"cancel")
                    raise RuntimeError("Unexpected or additional preview; cancelled")
                if args.case=="install" and not args.verify_existing and r.version(PKG) is not None:
                    raise RuntimeError("Package present before harness approval")
                report["approved_preview"]=cur.text
                approved=True;approved_token=cur.preview_b64;report["confirm_calls"]=1
                if args.case=="install":end=time.monotonic()+420
                try:
                    ack=review(request,"confirm",cur.preview_b64)
                    report["confirm_ack"]=ack.state
                except RuntimeError:
                    report["confirm_ack"]="unknown"
                if args.case=="install":
                    report["progress_snapshots"]=[]
                    watch=threading.Thread(target=observe_install_ui,
                        args=(r.serial,args.output.parent,watch_stop,report["progress_snapshots"]),daemon=True)
                    watch.start()
            elif cur.state=="done":
                report["answer"]=cur.text[:700];break
            elif cur.state!="working":raise RuntimeError("Request unexpectedly lost")
            time.sleep(.55)
        else:raise RuntimeError("Bounded wait expired")
        rows=trace(r,tool,started);report["trace"]=rows
        count=sum(row.startswith("tool_start · "+tool+" · ") for row in rows)
        report["observed_tool_starts"]=count
        success=(count==1 and any(row.startswith("after_tool · "+tool+" · ") and not re.search(r" · (error|failed|unconfirmed)$",row) for row in rows)
                 and any(row.startswith("turn_end · ") for row in rows) and (operation is None or approved))
        if args.case=="status":
            now=volume(r); report["volume_after_independent"]=now
            success=success and now==report["volume_range"] and str(now[0]) in cur.text and str(now[2]) in cur.text
        elif args.case=="list":
            # Verify membership against independently installed apps with real launchers.
            apps="\n".join(r.shell("cmd package query-activities --brief -a android.intent.action.MAIN "
                                 "-c android.intent.category."+category,ok=False)
                          for category in ("LAUNCHER","LEANBACK_LAUNCHER"))
            netease="com.netease.cloudmusic.tv/" in apps
            bili=PKG+"/" in apps
            negative=bool(re.search(r"(?:没有|未安装|不在|未发现|未找到|没有找到|不包含|不包括|未包含|未列出|没安装|未出现在)[^。\n]{0,18}(?:哔哩哔哩|B站)|"
                                    r"(?:哔哩哔哩|B站)[^。\n]{0,15}(?:未安装|不在|没有安装|未出现在|未列入|未列出)",cur.text))
            report["independent_netease_launcher"]=netease
            report["independent_bilibili_launcher"]=bili
            report["answer_says_bili_absent"]=negative
            success=(success and netease and "网易云音乐" in cur.text and ("哔哩哔哩" in cur.text or "B站" in cur.text)
                     and negative!=bili)
        elif args.case=="install":
            report["installed"]=installed_identity(r,official)
            report["package_installed_verified"]=report["installed"]==official
            report["fresh_install_from_absent"]=initial is None and report["package_installed_verified"]
            expected="installed" if initial is None else "already_installed"
            report["harness_install_acknowledged"]=("after_tool · install_app · "+expected) in rows
            success=success and report["package_installed_verified"] and report["harness_install_acknowledged"]
            watch_stop.set()
            if watch:watch.join(8)
            report["completion_ui"]=progress_ui(r)
            if success and "completed" in report["completion_ui"]:
                snap=args.output.parent/"harness-install-complete.png"
                if save_star_screen(r,snap):report["completion_screenshot"]=snap.name
        elif args.case=="open":
            time.sleep(.6); report["foreground"]=r.front();report["client_ui"]=ui(r)
            success=success and report["foreground"]==PKG
        elif args.case=="search":
            flags=ui(r);report["client_ui"]=flags
            success=success and r.front()==PKG and "query_visible" in flags and "results_tab" in flags
        report["verified"]=bool(success)
    except (RuntimeError,ValueError) as exc:
        report["error"]=str(exc)[:250]
    finally:
        watch_stop.set()
        if watch:watch.join(8)
        report["volume_after"]=r.volume()
        args.output.parent.mkdir(parents=True,exist_ok=True)
        with args.output.open("x",encoding="utf8") as out:json.dump(report,out,ensure_ascii=False,indent=2)
    print(json.dumps({k:report.get(k) for k in ("case","verified","observed_tool_starts","error","client_ui")},ensure_ascii=True))
    return 0 if report["verified"] else 1

if __name__=="__main__":raise SystemExit(main())
