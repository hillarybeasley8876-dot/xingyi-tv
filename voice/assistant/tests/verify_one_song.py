"""One utterance to Xiaomi only. Never confirm, start the player, or issue another request."""
import hashlib
import json
import re
import time
from deploy_voice_mcp import Run, APP, VOICE
from probe_native_handoff import ask

QUERY = "打开网易云音乐播放林俊杰的《不为谁而作的歌》"
ARTIST, TITLE = "林俊杰", "不为谁而作的歌"
QUALIFIED = "不为谁而作的歌 (Twilight)"
KEY = hashlib.sha256((ARTIST+"\n"+TITLE).encode()).hexdigest()
OUT = VOICE / "evidence-song" / "one-request-live.json"

def parsed(line):
    m = re.search(r'result=1, data="?(\{.*\})', line)
    return json.loads(m[1]) if m else {}

def relay(r):
    return parsed(r.shell("am broadcast -n "+APP+"/.AutoRelayDiagnostic -a "+APP+".RELAY_STATUS"))

def song_receipt(r):
    return parsed(r.shell("am broadcast -p "+APP+" -a "+APP+".DEBUG --es op song_receipt --es target_hash "+KEY))

def song(r):
    dump = r.shell("dumpsys media_session | grep -E '^ *(package=|active=|state=|metadata:)'")
    for section in re.split(r'(?m)^\s*package=',dump)[1:]:
        if section.splitlines()[0].strip() != "com.netease.cloudmusic.tv":
            continue
        m = re.search(r'(?m)^\s*metadata:.*?description=([^\r\n]+)',section)
        s = re.search(r'state=PlaybackState \{state=(\d+), position=(-?\d+).*?speed=([.\d-]+), updated=(\d+)',section)
        if not s:
            continue
        fields = m[1].strip().split(", ") if m else []
        relevant = len(fields)==3 and TITLE in fields[0]
        return {"active": bool(re.search(r'(?m)^\s*active=true\s*$',section)), "state": int(s[1]), "position":int(s[2]),
                "speed":float(s[3]),"updated":int(s[4]),"target_title":fields[0] if relevant else None,
                "artist":fields[1] if relevant else None,"album":fields[2] if relevant else None}
    return {}

def exact(p):
    return p.get("active") and p.get("state")==3 and p.get("speed")==1.0 and p.get("artist")==ARTIST and \
        p.get("target_title") in (TITLE,QUALIFIED) and p.get("album") in ("和自己对话","不为谁而作的歌","上一首情歌 (国语精选)")

def main():
    r=Run("192.168.31.162:5555")
    r.deadline=time.monotonic()+115
    assert r.shell("getprop ro.product.model")=="MiTV-ASTP0" and r.shell("getprop ro.product.device")=="mulan"
    apk=VOICE/"xingyi-0.8.apk"
    version,digest=r.local_info(apk,APP)
    assert r.version(APP) in (11,version)
    report={"request":QUERY,"origin":"one Xiaomi EXTERNAL_TEXT (post-ASR)","automatic_confirm_keys":0,
            "direct_player_starts_from_driver":0,"mcp_direct_submissions_from_driver":0}
    try:
        if r.installed_hash(APP)!=digest:
            r.adb("install","-r",str(apk),timeout=15)
        assert r.version(APP)==version and r.installed_hash(APP)==digest
        report["installed_sha256"]=digest
        end=time.monotonic()+12
        while time.monotonic()<end:
            base=relay(r)
            if base.get("enabled") and base.get("phase") in ("armed","accepted"): break
            time.sleep(.45)
        assert base.get("enabled") and base["phase"] in ("armed","accepted"),base
        report["before"]=song(r)
        if exact(report["before"]):
            raise RuntimeError("target_already_playing_before_test")
        report["relay_before"]=base
        report["native_request_count"]=1
        report["since_epoch_ms"]=int(r.shell("date +%s"))*1000
        ask(r,QUERY)
        observations=[]
        end=time.monotonic()+50
        matching_since=None
        while time.monotonic()<end:
            p=song(r); phase=relay(r); played=song_receipt(r)
            record={"music":p,"phase":phase.get("phase"),"cause":phase.get("last_cause"),
                    "count":phase.get("accepted_count"),"mcp":played}
            if not observations or record!=observations[-1]:observations.append(record)
            report["observations"]=observations
            if exact(p):
                if matching_since is None: matching_since=time.monotonic()
                if time.monotonic()-matching_since>=1.2 and (phase.get("accepted_count")==base["accepted_count"] or
                        played.get("status") in ("observed","already_playing")): break
            else: matching_since=None
            time.sleep(.6)
        report.update(final=song(r),relay_after=relay(r),song_receipt=song_receipt(r))
        report["verified_playing"]=bool(matching_since is not None and time.monotonic()-matching_since>=1.2 and exact(report["final"]))
        delta=report["relay_after"]["accepted_count"]-base["accepted_count"]
        report["route"]="mcp" if delta==1 and report["song_receipt"].get("intent_submitted") and report["song_receipt"].get("status")=="observed" else "native" if delta==0 and report["verified_playing"] else "unverified"
        return 0 if report["verified_playing"] and report["route"]!="unverified" else 1
    finally:
        OUT.parent.mkdir(exist_ok=True)
        OUT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf8")
        print(json.dumps({k:v for k,v in report.items() if k in ("verified_playing","route","final","song_receipt")},ensure_ascii=True))

if __name__=="__main__":
    raise SystemExit(main())
