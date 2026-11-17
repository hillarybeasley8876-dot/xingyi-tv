"""Open a real TV-local install preview and continue it once through stock XiaoAI; never press Confirm."""
import argparse
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

from deploy_voice_mcp import ADB, APP, Run
from probe_native_handoff import ask
from verify_one_song import relay
from verify_quiet_reply import TAGS, inspect, speech_receipt, spoken_result

PKG = "tv.danmaku.bili"
DRAFT = "安装哔哩哔哩官方客户端"
CONTINUE = "继续执行"
ADDRESSED = "星仪继续执行"
NONE = "当前没有等待继续的任务。"


def ui(r):
    if r.front() != APP:
        return []
    xml = r.shell("f=$(mktemp /data/local/tmp/xingyi-continue.XXXXXX) || exit 1; "
                  "trap 'rm -f \"$f\"' EXIT; uiautomator dump --compressed \"$f\" >/dev/null && cat \"$f\"",
                  timeout=8, ok=False)
    match = re.search(r"<hierarchy\b.*?</hierarchy>", xml, re.S)
    return [n.attrib for n in ET.fromstring(match[0]).iter("node") if n.get("package") == APP] if match else []


def button(tree, name):
    return next((n for n in tree if n.get("text") == name and n.get("class") == "android.widget.Button"
                 and n.get("enabled") == "true"), None)


@contextmanager
def voice(r):
    pid = r.shell("pidof com.xiaomi.voicecontrol")
    assert pid.isdigit()
    collector = subprocess.Popen([str(ADB), "-s", r.serial, "shell", "logcat", "-T", "1", "--pid="+pid,
                                 "-b", "main", "-v", "threadtime", "-v", "monotonic", *TAGS, "*:S"],
                                 stdout=subprocess.PIPE)
    lines = []
    def collect():
        for row in collector.stdout:
            if len(lines) < 18000 and len(row) < 4096:
                lines.append(row.decode("utf8", "replace"))
    t = threading.Thread(target=collect, daemon=True)
    t.start()
    try:
        time.sleep(.5)
        lines.clear()
        yield lines
    finally:
        collector.terminate()
        collector.wait(timeout=5)
        t.join(2)


def completed_voice(lines, text):
    rec = {"spoken_submitted": True, "spoken_hash": hashlib.sha256(text.encode()).hexdigest()}
    details = spoken_result(list(lines), rec, "status")
    return all(details.get(k) for k in ("service_received","player_started","player_finished"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output",type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Choose a fresh evidence file")
    r = Run("192.168.31.162:5555")
    r.deadline = time.monotonic()+100
    assert r.shell("getprop ro.product.model") == "MiTV-ASTP0"
    assert r.version(PKG) == 8911100, "benign verification requires the official client already installed"
    report={"origin":"TV share draft + actual Send button; Xiaomi post-ASR continuation",
            "confirmation_keys":0, "native_submissions":0, "verified":False}
    try:
        # A share populates the edit box; only a tap on the actual Send button begins the turn.
        r.shell("am start -W -n "+APP+"/.MainActivity -a android.intent.action.SEND -t text/plain"
                +" --es android.intent.extra.TEXT "+shlex.quote(DRAFT))
        if "mInputShown=true" in r.shell("dumpsys input_method | grep -m1 mInputShown") and r.front()==APP:
            r.shell("input keyevent KEYCODE_BACK")
            time.sleep(.35)
        tree=ui(r)
        assert any(n.get("class")=="android.widget.EditText" and n.get("text")==DRAFT for n in tree), "draft not displayed; refusing to replace other work"
        send=button(tree,"发送")
        assert send and not button(tree,"确认执行"), "old preview or missing Send"
        # This TV input stack focuses buttons on touch but requires a DPAD activation.
        if send.get("focused")!="true":
            x1,y1,x2,y2=map(int,re.findall(r"[0-9]+",send["bounds"]))
            r.shell(f"input tap {(x1+x2)//2} {(y1+y2)//2}")
            tree=ui(r)
            send=button(tree,"发送")
        if not button(tree,"确认执行"):
            for _ in range(4):
                if send and send.get("focused")=="true":break
                if not any(n.get("focused")=="true" and (n["class"]=="android.widget.EditText" or n.get("text") in ("手机语音","发送")) for n in tree):
                    break
                r.shell("input keyevent KEYCODE_DPAD_RIGHT")
                tree=ui(r);send=button(tree,"发送")
            assert send and send.get("focused")=="true" and not button(tree,"确认执行"), "Send did not gain focus"
            r.shell("input keyevent KEYCODE_DPAD_CENTER")
        report["activated_focused_send_button"]=True
        end=time.monotonic()+12
        while time.monotonic()<end:
            tree=ui(r)
            if button(tree,"确认执行") and any(PKG in n.get("text","") for n in tree): break
        else: raise AssertionError("real install preview not visible")
        report["real_preview_visible"]=True
        before=relay(r)["accepted_count"]
        with voice(r) as lines:
            ask(r,CONTINUE)
            report["native_submissions"]+=1
            end=time.monotonic()+24
            while time.monotonic()<end:
                now=relay(r)
                rec=speech_receipt(r,DRAFT)
                verified_speech=spoken_result(list(lines),rec,"status")
                wanted = any("电视已安装哔哩哔哩官方 Android 客户端" in line and "8.91.1" in line and
                             "=vc=mmp=" in line and "playTts:" in line for line in lines)
                if (now["accepted_count"]-before)==1 and now.get("last_cause")=="continue_resumed" and wanted and verified_speech.get("player_finished"):break
                time.sleep(.5)
            report.update(resume_cause=now.get("last_cause"),resume_delta=now["accepted_count"]-before,
                          control_seen=now.get("control_seen"),control_matched=now.get("control_matched"),control_decision=now.get("control_decision"),
                          result_spoken=bool(wanted and all(verified_speech.get(k) for k in ("service_received","player_started","player_finished"))),
                          native_resume=inspect(list(lines),CONTINUE))
        tree=ui(r)
        texts="\n".join(n.get("text","") for n in tree)
        report["continued_visible"]=("收到本轮语音确认，继续当前步骤" in texts and "核对电视上已安装的包名和签名" in texts)
        report["had_pending_after"]=bool(button(tree,"确认执行"))
        if not (report["resume_delta"]==1 and report["resume_cause"]=="continue_resumed" and report["result_spoken"]
                and report["continued_visible"] and tree and not report["had_pending_after"]):
            return 1
        print("Verified existing preview consumed once, installation read back and result spoken.",flush=True)
        def own_window():
            window = r.shell("dumpsys window windows | grep -m1 mCurrentFocus")
            return r.front()==APP and re.search(r"\bu0 "+re.escape(APP)+"/",window) is not None
        if own_window() and any(n.get("class")=="android.widget.EditText" and n.get("text")==DRAFT for n in tree):
            png=subprocess.check_output([str(ADB),"-s",r.serial,"exec-out","screencap","-p"],timeout=8)
            if png.startswith(b"\x89PNG\r\n\x1a\n") and own_window():
                image=Path(__file__).resolve().parents[2]/"evidence-bili"/"harness-install-complete.png"
                image.parent.mkdir(parents=True,exist_ok=True)
                image.write_bytes(png)
                report["verified_ui_image"]=image.name
        # No-pending control is safe only after proving the same visible preview finished.
        before=relay(r)["accepted_count"]
        with voice(r) as lines:
            ask(r,ADDRESSED)
            report["native_submissions"]+=1
            end=time.monotonic()+18
            while time.monotonic()<end:
                now=relay(r)
                if now["accepted_count"]-before==1 and now.get("last_cause")=="continue_no_preview" and completed_voice(lines,NONE):break
                time.sleep(.5)
            report.update(idle_cause=now.get("last_cause"),idle_delta=now["accepted_count"]-before,idle_spoken=completed_voice(lines,NONE),
                          native_idle=inspect(list(lines),ADDRESSED))
        report["verified"]=report["idle_delta"]==1 and report["idle_cause"]=="continue_no_preview" and report["idle_spoken"]
        return 0 if report["verified"] else 1
    finally:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        with args.output.open("x",encoding="utf8") as f:json.dump(report,f,ensure_ascii=False,indent=2)
        print(json.dumps(report,ensure_ascii=True))


if __name__=="__main__":
    raise SystemExit(main())
