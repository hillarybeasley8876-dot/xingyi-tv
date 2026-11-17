"""Run deliberately on the home network. Never presses OK or calls a confirmation tool."""
import asyncio
import base64
import hashlib
import json
import os
import re
import secrets
import subprocess
import sys
import tempfile
import time
from email.utils import formatdate
from pathlib import Path
from xml.etree import ElementTree

import httpx
from gateway import ENDPOINT, Settings, application, sign


ADB = r"H:\tools\platform-tools\adb.exe"
TV = "192.168.31.162:5555"
APP = "xyz.erapi0neer.xingyi/"
HOME = "xyz.erapi0neer.orrery/"
PREVIEW = "在网易云音乐播放林俊杰的《不为谁而作的歌》"
OUT = Path(r"H:\tools\mitv\voice\evidence-0.6\song-preview.json")


def check(value, code):
    if not value:
        raise RuntimeError(code)


def adb(*args, timeout=5):
    p = subprocess.run([ADB, "-s", TV, *args], capture_output=True, timeout=timeout)
    check(p.returncode == 0, "adb_command_failed")
    return p.stdout.decode("utf-8", "replace").strip()


def shell(s, timeout=5):
    return adb("shell", s, timeout=timeout)


def front():
    return shell("dumpsys activity activities | grep mResumedActivity")


def music():
    # Compare only in memory; the report contains no prior song titles or device identifiers.
    snap = shell("dumpsys media_session")
    for block in re.split(r"(?m)^\s*package=", snap)[1:]:
        if block.splitlines()[0].strip() == "com.netease.cloudmusic.tv":
            state = re.search(r"state=PlaybackState \{state=(\d+)", block)
            label = re.search(r"(?m)^\s*metadata:size=\d+, description=(.*)$", block)
            return (state[1] if state else None, label[1].strip() if label else None)
    return None


async def run(report):
    os.environ["XINGYI_TV"] = TV
    check(adb("get-state") == "device", "paired_tv_unavailable")
    check(HOME in front(), "initial_screen_not_orrery")
    before = music()
    check(not before or before[0] not in ("3", "6"), "music_in_use")
    xml = "/data/local/tmp/xingyi-preview-" + secrets.token_hex(6) + ".xml"
    returned_home = False
    try:
        with tempfile.TemporaryDirectory() as td:
            config = Settings("test.example.invalid", "test", secrets.token_bytes(32), "fixture-app",
                              "fixture-owner", Path(td) / "seen.sqlite3")
            app = application(config)
            calls = []
            receipt = None
            async with app.router.lifespan_context(app), httpx.AsyncClient(
                    transport=httpx.ASGITransport(app=app), base_url="http://127.0.0.1") as http:
                call = app.state.call
                async def observed(name, args):
                    nonlocal receipt
                    calls.append(name)
                    result = await call(name, args)
                    if name == "xingyi_submit" and not result.isError:
                        receipt = (result.structuredContent or {}).get("request_id")
                    return result
                app.state.call = observed

                async def intent(name, slots):
                    event = {"version":"1.0","session":{"application":{"app_id":config.app_id},
                             "user":{"user_id":config.owner_id}}, "request":{"type":1,
                             "request_id":secrets.token_hex(12),"slot_info":{"intent_name":name,"slots":slots}}}
                    body = json.dumps(event, ensure_ascii=False).encode()
                    md5 = base64.b64encode(hashlib.md5(body).digest()).decode()
                    stamp = formatdate(usegmt=True)
                    signature = sign(config.secret, "POST", ENDPOINT, "", stamp, config.host, "application/json", md5)
                    headers = {"Host":config.host, "X-Xiaomi-Date":stamp, "Content-Type":"application/json",
                               "Content-MD5":md5, "Authorization":"MIAI-HmacSHA256-V1 test::"+signature}
                    response = await http.post(ENDPOINT, content=body, headers=headers)
                    check(response.status_code == 200, "callback_rejected_synthetic_signed_request")
                    return response.json()

                unsupported = await intent("not_a_skill_action", [])
                check(unsupported["is_session_end"] and unsupported["response"]["not_understand"]
                      and not unsupported["response"]["open_mic"] and not calls, "unsupported_intent_routed")
                report["unsupported_ended_without_mcp"] = True

                song = await intent("play_song", [{"name":"artist","value":"林俊杰"},
                                                  {"name":"title","value":"不为谁而作的歌"}])
                check(song["is_session_end"] and "核对" in song["response"]["to_speak"]["text"]
                      and receipt and calls == ["xingyi_submit"], "song_submit_not_acknowledged")
                report["real_mcp_submission"] = True
                try:
                    deadline = time.monotonic() + 60
                    while time.monotonic() < deadline:
                        result = await asyncio.wait_for(call("xingyi_result", {"request_id":receipt}), 8)
                        state = (result.structuredContent or {})
                        if state.get("state") == "pending":
                            lines = [line.strip() for line in state.get("text","").splitlines() if line.strip()]
                            check(lines[1:] == ["1. " + PREVIEW], "unexpected_action_preview")
                            break
                        check(not result.isError and state.get("state") == "working", "model_ended_before_preview")
                        await asyncio.sleep(.35)
                    else:
                        raise RuntimeError("preview_timeout")
                    check(APP in front(), "assistant_not_foreground")
                    status = shell("uiautomator dump " + xml, timeout=12)
                    check("dumped to" in status.lower(), "ui_capture_failed")
                    ui = ElementTree.fromstring(shell("cat " + xml))
                    texts = [n.get("text","") for n in ui.iter("node") if n.get("package") == APP[:-1]]
                    check(any("1. " + PREVIEW in t and "确认执行" in t for t in texts), "matching_preview_not_visible")
                    check(music() == before, "playback_changed_before_approval")
                    report.update(preview=PREVIEW, preview_visible=True, playback_unchanged_before_cancel=True)
                finally:
                    # Only cancel this process's accepted turn. Requests made on the TV by somebody else are untouched.
                    result = await asyncio.wait_for(call("xingyi_result", {"request_id":receipt}), 8)
                    if (result.structuredContent or {}).get("state") in ("pending","working") and APP in front():
                        shell("input keyevent KEYCODE_BACK")
                        await asyncio.sleep(.35)
                check(music() == before, "playback_changed_after_cancel")
                result = await asyncio.wait_for(call("xingyi_result", {"request_id":receipt}), 8)
                check((result.structuredContent or {}).get("state") in ("stale","done"), "cancellation_unverified")
                report.update(cancelled_with_back=True, playback_unchanged_after_cancel=True)
    finally:
        try:
            shell("rm -f " + xml)
        finally:
            shell("input keyevent KEYCODE_HOME")
        for _ in range(10):
            if HOME in front():
                returned_home = True
                break
            await asyncio.sleep(.25)
        report["restored_orrery"] = returned_home
        check(returned_home, "home_restore_failed")


if __name__ == "__main__":
    report = {"source":"临时签名的模拟小爱技能事件，无真实小米云端或语音唤醒", "result":"FAIL"}
    try:
        asyncio.run(run(report))
        report["result"] = "PASS"
    except Exception as exc:
        report["failure"] = str(exc) if type(exc) is RuntimeError and re.fullmatch(r"[a-z_]+",str(exc)) else type(exc).__name__
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(report["result"], report.get("failure",""))
    sys.exit(0 if report["result"] == "PASS" else 1)
