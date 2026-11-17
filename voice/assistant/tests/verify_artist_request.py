"""Verify an artist-only handoff on a running TV. Use --query only for a post-ASR test."""
import argparse
import hashlib
import json
import re
import time
from pathlib import Path

from deploy_voice_mcp import APP, Run
from probe_native_handoff import ask
from verify_one_song import parsed, relay


def media(run, artist):
    dump = run.shell("dumpsys media_session | grep -E '^ *(package=|active=|state=|metadata:)'")
    for section in re.split(r"(?m)^\s*package=", dump)[1:]:
        if section.splitlines()[0].strip() != "com.netease.cloudmusic.tv":
            continue
        state = re.search(r"state=PlaybackState \{state=(\d+),.*?speed=([.\d-]+)", section)
        meta = re.search(r"(?m)^\s*metadata:.*?description=([^\r\n]+)", section)
        fields = meta[1].strip().split(", ") if meta else []
        matches = len(fields) == 3 and fields[1].split("/",1)[0].strip().casefold() == artist.casefold()
        playing = bool(state and state[1] == "3" and float(state[2]) > 0 and
                       re.search(r"(?m)^\s*active=true\s*$", section))
        return {"playing": playing, "target_artist": matches,
                "title": fields[0] if matches else None, "credits": fields[1] if matches else None,
                "album": fields[2] if matches else None}
    return {"playing": False, "target_artist": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artist", required=True)
    parser.add_argument("--query", help="Submit exactly one command at the Xiaomi text entry. Default: passive observation.")
    parser.add_argument("--output", type=Path, required=True, help="New evidence file; existing files are preserved")
    parser.add_argument("--seconds", type=int, default=55)
    args = parser.parse_args()
    if args.output.exists() or not 10 <= args.seconds <= 300:
        parser.error("Choose a new output path and 10–300 seconds.")
    if args.query is not None and (args.artist not in args.query or "网易云" not in args.query):
        parser.error("The query must name NetEase and the expected artist.")
    r = Run("192.168.31.162:5555")
    r.deadline = time.monotonic() + args.seconds + 25
    if r.shell("getprop ro.product.model") != "MiTV-ASTP0":
        raise RuntimeError("Unexpected TV")
    key = hashlib.sha256((args.artist+"\n").encode()).hexdigest()

    def receipt():
        return parsed(r.shell("am broadcast -p "+APP+" -a "+APP+".DEBUG --es op song_receipt --es target_hash "+key))

    base = relay(r)
    if not base.get("enabled") or base.get("phase") not in {"armed", "accepted"}:
        raise RuntimeError("Relay is not ready")
    previous_seq = receipt().get("seq", 0)
    report = {"origin": "Xiaomi post-ASR" if args.query else "passively observed TV",
              "driver_requests": 1 if args.query else 0, "query_submitted": args.query, "artist": args.artist,
              "relay_before": base, "media_before": media(r, args.artist), "observations": [], "verified": False}
    try:
        if args.query:
            ask(r, args.query)
        end = time.monotonic() + args.seconds
        stable_since, stable_track = None, None
        while time.monotonic() < end:
            phase, song, player = relay(r), receipt(), media(r, args.artist)
            observation = {"accepted": phase.get("accepted_count"), "cause": phase.get("last_cause"),
                           "receipt": song, "player": player}
            observations = report["observations"]
            if not observations or observation != observations[-1]:
                observations.append(observation)
            delta = phase.get("accepted_count", 0) - base["accepted_count"]
            resolved = (song.get("status") == "observed" and song.get("intent_submitted") is True and
                        song.get("track_id", 0) > 0) or (
                            song.get("status") in {"already_playing", "artist_already_playing"} and
                            song.get("intent_submitted") is False)
            valid = (delta == 1 and song.get("matched") and song.get("seq", 0) > previous_seq and resolved and
                     player.get("playing") and player.get("target_artist") and player.get("title"))
            track = (player.get("title"), player.get("album"))
            if valid and track == stable_track and stable_since is not None:
                if time.monotonic() - stable_since >= 1.2:
                    report["verified"] = True
                    break
            else:
                stable_since, stable_track = (time.monotonic(), track) if valid else (None, None)
            time.sleep(.5)
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf8") as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2)
    print(json.dumps({"verified": report["verified"], "latest": report["observations"][-1:]}, ensure_ascii=True))
    return 0 if report["verified"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
