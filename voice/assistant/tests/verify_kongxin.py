"""One original Xiaomi request per case, independent MediaSession and spoken output receipts."""
import argparse
import hashlib
import json
import re
import subprocess
import threading
import time
import unicodedata
from pathlib import Path

from deploy_voice_mcp import APP, ADB, Run
from probe_native_handoff import ask
from verify_one_song import parsed, relay
from verify_quiet_reply import TAGS, inspect, speech_receipt, spoken_result

STUDIO = (435288399, "空心", "光泽", "光泽")
DUET = (3407803435, "空心 (Live版)", "黄霄雲/刘端端", "天赐的声音第七季 第6期")
CASES = {"title": ("", STUDIO), "liu": ("刘端端", DUET), "huang": ("黄霄云", DUET)}


def normalize(text):
    return " ".join(unicodedata.normalize("NFKC", text).casefold().split())


def song_receipt(run, artist):
    key = hashlib.sha256((artist+"\n空心").encode()).hexdigest()
    return parsed(run.shell("am broadcast -p "+APP+" -a "+APP+".DEBUG --es op song_receipt --es target_hash "+key))


def media(run, target):
    snapshot = run.shell("dumpsys media_session | grep -E '^ *(package=|state=|active=|metadata:)'")
    for section in re.split(r"(?m)^\s*package=", snapshot)[1:]:
        if section.splitlines()[0].strip() != "com.netease.cloudmusic.tv":
            continue
        st = re.search(r"state=PlaybackState \{state=(\d+), position=(-?\d+).*?speed=([.\d-]+), updated=(\d+)", section)
        meta = re.search(r"(?m)^\s*metadata:.*?description=([^\r\n]+)", section)
        fields = meta[1].strip().split(", ") if meta else []
        relevant = len(fields) == 3 and "空心" in fields[0]
        exact = relevant and normalize(fields[0]) == normalize(target[1]) and normalize(fields[2]) == normalize(target[3])
        if exact:
            actual_credits = [normalize(x).replace("雲","云") for x in fields[1].split("/")]
            wanted_credits = [normalize(x).replace("雲","云") for x in target[2].split("/")]
            exact = sorted(actual_credits) == sorted(wanted_credits)
        playing = bool(st and st[1] == "3" and float(st[3]) > 0 and re.search(r"(?m)^\s*active=true\s*$", section))
        return {"exact_playing": bool(exact and playing), "state": int(st[1]) if st else None,
                "updated": int(st[4]) if st else None, "position": int(st[2]) if st else None,
                "title": fields[0] if relevant else None, "credits": fields[1] if relevant else None,
                "album": fields[2] if relevant else None}
    return {"exact_playing":False,"state":None,"updated":None}


def player_notice(run):
    # Return only fixed labels, never the outside application's UI tree or music lyrics.
    if run.front() != "com.netease.cloudmusic.tv":
        return "player_not_in_foreground"
    script = """timeout 5 uiautomator dump --compressed /proc/self/fd/1 2>/dev/null | tr '<' '\\n' |
    while IFS= read -r n; do
     case "$n" in *'package="com.netease.cloudmusic.tv"'*) ;; *) continue;; esac
     case "$n" in *暂无版权*|*没有版权*|*无版权*) echo copyright_notice;;
       *仅可试听*|*试听结束*|*仅支持试听*|*首试听*) echo trial_notice;;
       *开通会员后播放*|*会员专享歌曲*|*购买后播放*|*购买后可播放*|*会员歌曲暂不能播放*) echo payment_notice;; esac
    done | sort -u"""
    labels = set(run.shell(script, timeout=8, ok=False).splitlines())
    return sorted(labels & {"copyright_notice","trial_notice","payment_notice"}) or "no_explicit_notice_observed"


def music_tts(lines, receipt, target):
    result = spoken_result(lines, receipt, "kongxin")
    digest = receipt.get("spoken_hash")
    for line in lines:
        m = re.search(r"=vc=mmp=\s*:\s*.*?playTts: <(.+)>\s*$", line)
        if m and digest and hashlib.sha256(m[1].encode()).hexdigest() == digest:
            text = normalize(m[1]).replace("雲","云")
            result["semantic_match"] = (receipt.get("spoken_stage") == "music" and "空心" in text and "播放" in text
                and all(n in text for n in normalize(target[2]).replace("雲","云").split("/")))
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--case", choices=CASES, required=True)
    p.add_argument("--query")
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--seconds", type=int, default=46)
    args = p.parse_args()
    artist,target = CASES[args.case]
    query = args.query or "网易云音乐播放"+(artist+"的" if artist else "")+"空心"
    if args.output.exists() or not 15 <= args.seconds <= 110 or "空心" not in query or (artist and artist not in query):
        p.error("Use a new report, a matching song request and a bounded test duration")
    r = Run("192.0.2.10:5555")
    r.deadline = time.monotonic()+args.seconds+30
    if r.shell("getprop ro.product.model") != "MiTV-ASTP0" or "mWakefulness=Awake" not in r.shell("dumpsys power | grep -m1 mWakefulness="):
        raise RuntimeError("Target TV is not awake")
    pid = r.shell("pidof com.xiaomi.voicecontrol")
    if not pid.isdigit(): raise RuntimeError("Missing Xiaomi voice PID")
    base = relay(r)
    if not base.get("enabled") or base.get("phase") not in ("armed","accepted"):
        raise RuntimeError("Xiaomi relay is not ready")
    prev = song_receipt(r,artist)
    prev_voice = speech_receipt(r,query)
    before = media(r,target)
    log = subprocess.Popen([str(ADB),"-s",r.serial,"shell","logcat","-b","main","-T","1","--pid="+pid,
        "-v","threadtime","-v","monotonic",*TAGS,"*:S"],stdout=subprocess.PIPE)
    lines = []
    def collect():
        for line in log.stdout:
            if len(line)<4096 and len(lines)<18000: lines.append(line.decode("utf8","replace"))
    thread = threading.Thread(target=collect,daemon=True); thread.start()
    report = {"query":query,"case":args.case,"origin":"Xiaomi native post-ASR","native_submissions":0,
              "direct_player_starts":0,"direct_mcp_requests":0,"confirmation_keys":0,"verified":False,
              "target":{"track_id":target[0],"title":target[1],"credits":target[2],"album":target[3]},
              "before":before,"observations":[]}
    try:
        time.sleep(.4); lines.clear()
        report["native_submissions"] = 1
        ask(r,query)
        stop = time.monotonic()+args.seconds
        since = None
        while True:
            now = time.monotonic()
            state, rec, speech = relay(r),song_receipt(r,artist),speech_receipt(r,query)
            active = media(r,target)
            if active["exact_playing"]:
                if since is None: since=now
            else: since=None
            delta = state.get("accepted_count",0)-base["accepted_count"]
            voice = music_tts(list(lines),speech,target)
            new_receipt = rec.get("matched") and rec.get("seq",0)>prev.get("seq",0)
            new_speech = speech.get("matched") and speech.get("spoken_seq",0)>prev_voice.get("spoken_seq",0)
            reached = bool(new_receipt and rec.get("track_id")==target[0] and (
                (rec.get("status")=="observed" and rec.get("intent_submitted")) or
                (rec.get("status")=="already_playing" and not rec.get("intent_submitted"))))
            stable = since is not None and now-since>=1.2
            native = inspect(list(lines),query)
            route = "not_verified"
            if delta==1 and state.get("last_cause") in {"confirmed_refusal","explicit_refusal","fresh_wrong_recording"} and reached and stable:
                route = "mcp"
            if (delta==0 and stable and (not before["exact_playing"] or before["updated"]!=active.get("updated"))
                    and native["matched_dialogs"]==1 and native["reply_classes"]==["ordinary"]):
                route = "native"
            obs={"handoff_delta":delta,"cause":state.get("last_cause"),"song_receipt":rec,
                 "speech_stage":speech.get("spoken_stage"),"media":active}
            if not report["observations"] or obs != report["observations"][-1]: report["observations"].append(obs)
            report.update(route=route,stable_target_playing=bool(stable),harness_voice=voice,new_spoken_receipt=bool(new_speech),
                          last_cause=state.get("last_cause"),native_trace=native,handoff_delta=delta)
            report["verified"] = route=="native" or (route=="mcp" and bool(new_speech) and all(voice.get(k) for k in
                ("service_received","semantic_match","player_started","player_finished")))
            if report["verified"] or now>=stop: break
            time.sleep(.5)
        report["player_ui"]=player_notice(r)
        if isinstance(report["player_ui"],list):
            report["verified"]=False
        report["full_length_entitlement"]="not established by brief playback observation"
    finally:
        log.terminate(); log.wait(timeout=5); thread.join(2)
        args.output.parent.mkdir(parents=True,exist_ok=True)
        with args.output.open("x",encoding="utf8") as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({k:report.get(k) for k in ("case","verified","route","last_cause","stable_target_playing","player_ui","harness_voice")}))
    return 0 if report["verified"] else 1

if __name__=="__main__": raise SystemExit(main())
