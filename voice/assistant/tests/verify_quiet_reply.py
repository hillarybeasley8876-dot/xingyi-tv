"""Issue one known Xiaomi request and observe its speech player and MCP separately."""
import argparse
import hashlib
import json
import re
import subprocess
import threading
import time
from pathlib import Path

from deploy_voice_mcp import ADB, APP, Run
from probe_native_handoff import ask
from probe_once_voice import APK, PROBE, epoch_ms, history_sample
from verify_one_song import exact, parsed, relay, song


TAGS = ["=vc=en=aifw==xaf=aew=", "=vc=en=en=proxy=", "=conversation=",
        "=vc=cp=conversation=", "=vc=mm=mod=", "=vc=mmp=", "=vc=xp=", "=byte=tts=", "=vc=fd=ds="]
SONG = "用网易云播放林俊杰的《不为谁而作的歌》"
TRACKS = {"song": SONG, "open-song": "打开网易云音乐播放林俊杰的《不为谁而作的歌》",
          "normal": "一加一等于几", "timer": "你能读取星仪助手内部的本机休眠定时吗"}
RID = r"[0-9a-fA-F]{32}"


def speech_receipt(run, query):
    digest = hashlib.sha256(query.encode()).hexdigest()
    return parsed(run.shell("am broadcast -p "+APP+" -a "+APP+".DEBUG --es op speech_receipt --es target_hash "+digest))


def spoken_result(lines, receipt, case):
    """Keep content in RAM; relate the exact emitted text to one completed native TtsStr instance."""
    out = {"semantic_match":False, "service_received":False, "player_started":False, "player_finished":False}
    digest = receipt.get("spoken_hash")
    if not receipt.get("spoken_submitted") or not digest:
        return out
    calls = []
    for i, row in enumerate(lines):
        text = re.search(r"=vc=mmp=\s*:\s*.*?playTts: <(.+)>\s*$",row)
        if text and hashlib.sha256(text[1].encode()).hexdigest() == digest:
            calls.append((i,text[1]))
    if len(calls) != 1: return out
    idx, answer = calls[0]
    # Require the immediately preceding vendor service invocation and stop at any competing speech.
    previous = [r for r in lines[:idx] if "handlePlayTts" in r or "playTts: <" in r]
    out["service_received"] = bool(previous and "=vc=fd=ds=" in previous[-1] and "handlePlayTts" in previous[-1])
    if case in ("song","open-song"):
        out["semantic_match"] = (receipt.get("spoken_stage")=="music" and all(v in answer for v in
                                ("网易云","播放","林俊杰","不为谁而作的歌")) and "失败" not in answer and "无法" not in answer)
    elif case == "timer":
        out["semantic_match"] = (receipt.get("spoken_stage")=="answer" and re.search(r"(定时|休眠|倒计时)",answer) is not None
                                 and re.search(r"(没有|未设置|未设|无定时|未开启|没有开启|未启用|剩余|还剩|分钟|未安排)",answer) is not None)
    for row in lines[idx+1:]:
        if "handlePlayTts" in row or "playTts: <" in row: break
        if "=vc=xp=" in row and "playSync:" in row and row.rstrip().endswith("TtsStr"):
            out["tts_string_player"] = True
        if out.get("tts_string_player") and (("=vc=xp=" in row and "onAudioTrack notifyPlayStart" in row)
                or ("=vc=mm=mod=" in row and "onTtsStartPlay," in row)):
            out["player_started"] = True
        if "=vc=mm=mod=" in row and "voice_tts_finish:" in row:
            same = re.search(r"(?:\{|, )content="+re.escape(answer)+r"(?:, [a-z_]+=|\})", row)
            if same and out["player_started"] and "status=success" in row and "is_interrupted=false" in row:
                out["player_finished"] = True
                break
    return out


def inspect(lines, query):
    full = set()
    for line in lines:
        for pattern in (r"updateQuery "+re.escape(query)+r" ("+RID+r")\b",
                        r"query="+re.escape(query)+r", requestId='("+RID+r")'"):
            found = re.search(pattern, line)
            if found:
                full.add(found[1])
    if len(full) != 1:
        return {"matched_dialogs": len(full), "events": {}}
    rid = full.pop()
    events = {}
    replies = []
    for line in lines:
        t = re.match(r"\s*(\d+\.\d+)", line)
        if not t:
            continue
        when = float(t[1])
        def note(name):
            events.setdefault(name, when)
        if rid in line:
            if ("voice_tts_finish: " in line and "dialog_id="+rid in line and
                    query == TRACKS["normal"] and re.search(r"content=(?:一加一等于|等于|答案是)?[2二][。！!]?(?:, |})",line)):
                note("spoken_math_result")
            if ("updateQuery "+query+" "+rid) in line:
                note("query")
            if ("NlpEvent(rid='"+rid+"', push=false, stream=false, SpeechSynthesizer.Speak)") in line:
                note("nlp_speak")
            if "onTtsStartPlay," in line: note("player_start")
            if "onTtsPlayFinish, byManual: true," in line: note("player_stopped")
            if "onTtsPlayFinish, byManual: false," in line: note("player_finished")
        if "speak_text" in events and "player_start" not in events and when - events["speak_text"] < 2:
            if "msg: play by audio track " in line:
                session = re.search(r"msg: play by audio track ([0-9a-fA-F]{4})\s*$",line)
                if session and session[1] == rid[:4]: note("pcm_track_marker")
            if "msg: onPcmData: 0 " in line: note("first_pcm")
        if ("-"+rid[:4]+"-") in line and "recv inst tra: " in line:
            speak = re.search(r"-SpeechSynthesizer\.Speak\[(.*), (?:false|true)\]\s*$", line)
            if speak:
                note("speak_text")
                reply = speak[1]
                replies.append("native_netease_refusal" if "暂不支持使用网易云音乐源播放" in reply else
                               "generic_refusal" if reply.rstrip("。！!") in {"抱歉，目前暂不支持该功能","抱歉，当前暂不支持该功能"} else
                               "other_refusal" if any(w in reply for w in ("抱歉", "无法", "不能")) else "ordinary")
    return {"matched_dialogs": 1, "events": events, "reply_classes": replies}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("case", choices=TRACKS)
    parser.add_argument("mode", choices=["baseline", "quiet", "normal"])
    parser.add_argument("out", type=Path)
    args = parser.parse_args()
    if args.out.exists(): parser.error("Use a fresh evidence path")
    r = Run("192.168.31.162:5555")
    r.deadline = time.monotonic()+85
    pid = r.shell("pidof com.xiaomi.voicecontrol")
    assert pid.isdigit() and r.shell("getprop ro.product.model") == "MiTV-ASTP0"
    base = relay(r)
    assert base.get("enabled")
    query = TRACKS[args.case]
    is_song = args.case in {"song", "open-song"}
    requires_math = args.case=="normal" and args.mode=="normal"
    prepared = False
    if requires_math:
        code, probe_hash = r.local_info(APK, PROBE)
        previous = r.version(PROBE)
        if previous is None:
            r.adb("install",str(APK),timeout=12)
            prepared = True
        elif previous!=code or r.installed_hash(PROBE)!=probe_hash:
            raise RuntimeError("Different math observer installed; preserving it")
    player_before = song(r) if is_song else None
    before_speech = speech_receipt(r,query)
    math_two = False
    lines = []
    p = subprocess.Popen([str(ADB), "-s", r.serial, "shell", "logcat", "-T", "1", "--pid="+pid, "-b", "main",
                          "-v", "threadtime", "-v", "monotonic", *TAGS, "*:S"], stdout=subprocess.PIPE)
    def collect():
        for row in p.stdout:
            if len(row) < 4096 and len(lines) < 12000:
                lines.append(row.decode("utf8", "replace"))
    t = threading.Thread(target=collect, daemon=True)
    t.start()
    try:
        time.sleep(.45)
        lines.clear()
        since = epoch_ms(r)
        ask(r, query)
        if requires_math:
            time.sleep(5)
            observed = history_sample(r,query,since)
            math_two = observed.get("status")=="match" and any("math_two" in item.get("markers",[])
                    for item in observed.get("text_features",[]))
        end = time.monotonic() + (39 if args.mode=="quiet" else 12)
        while time.monotonic()<end:
            if args.mode=="quiet":
                rec=speech_receipt(r,query)
                if spoken_result(list(lines),rec,args.case)["player_finished"]: break
            time.sleep(.75)
    finally:
        p.terminate()
        p.wait(timeout=4)
        t.join(2)
        if prepared and r.installed_hash(PROBE)==probe_hash:
            r.adb("uninstall",PROBE)
    result = inspect(lines, query)
    after_speech=speech_receipt(r,query)
    spoken=spoken_result(lines,after_speech,args.case)
    lines.clear()
    events = result["events"]
    after = relay(r)
    delta = after["accepted_count"] - base["accepted_count"]
    stops = after["quiet_stop_sent"] - base["quiet_stop_sent"]
    player = song(r) if is_song else None
    classes = result.get("reply_classes", [])
    ordinary = classes == ["ordinary"]
    refusal = len(classes) == 1 and classes[0] != "ordinary"
    spoken_new=after_speech.get("matched") and after_speech.get("spoken_seq",0)>before_speech.get("spoken_seq",0)
    if args.mode == "quiet":
        verified = (refusal and stops == 1 and "player_stopped" in events and "player_finished" not in events and delta == 1 and
                    after.get("last_cause")=="confirmed_refusal" and spoken_new and
                    all(spoken[k] for k in ("semantic_match","service_received","player_started","player_finished")) and
                    (not is_song or exact(player)))
    elif args.mode == "normal":
        verified = ((math_two or "spoken_math_result" in events) and stops == 0 and "player_start" in events
                    and "player_finished" in events and "player_stopped" not in events and delta == 0)
    else:
        verified = bool("player_start" in events and "player_finished" in events)
    result.update(verified=bool(verified), native_requests=1, origin="Xiaomi post-ASR", case=args.case, mode=args.mode,
                  handoff_count_delta=delta, quiet_stop_delta=stops, last_cause=after.get("last_cause"),
                  spoken_new=bool(spoken_new),spoken_stage=after_speech.get("spoken_stage"),harness_voice=spoken,
                  native_math_answer_two=bool(math_two or "spoken_math_result" in events),
                  player_before=player_before, player_after=player)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("x", encoding="utf8") as out: json.dump(result, out, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=True))
    return 0 if verified else 1


if __name__ == "__main__":
    raise SystemExit(main())
