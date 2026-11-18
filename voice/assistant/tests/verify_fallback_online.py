"""Verify native success and an explicitly simulated refusal via the Windows controller."""
import json
import runpy
import subprocess
import sys
import time
from deploy_voice_mcp import Run, VOICE
from probe_native_handoff import focused
from verify_native_receipt import receipt


def main():
    retest_timer = "--timer-fix" in sys.argv[1:]
    device = Run("192.0.2.10:5555")
    device.deadline = time.monotonic() + 130
    assert device.shell("getprop ro.product.model") == "MiTV-ASTP0"
    if retest_timer:
        from deploy_voice_mcp import APP
        assert device.shell("getprop ro.product.device") == "mulan"
        release = VOICE/"xingyi-0.6.1.apk"
        code, digest = device.local_info(release, APP)
        assert device.version(APP) in (8, code)
        device.adb("install", "-r", str(release), timeout=15)
        assert device.version(APP) == code and device.installed_hash(APP) == digest
    device.home_package = device.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").splitlines()[-1].split('/')[0]
    remote = runpy.run_path(str(VOICE/"assistant/voice_remote.pyw"))
    report = {}
    try:
        device.home()
        initial = receipt(device)
        remote["send_to_xiaoai"]("打开电视设置")
        until = time.monotonic() + 9
        while time.monotonic() < until:
            focus = focused(device)
            if "settings_window" in focus or "com.xiaomi.mitv.settings/" in focus:
                report["windows_native_opened_settings"] = True
                break
            time.sleep(.3)
        report["native_volume_mcp_receipt_unchanged"] = receipt(device) == initial
        device.home()
        # No Xiaomi failure event is available. Exercise the same explicit user decision
        # as the desktop button with a read-only different command, never retry a write.
        query = "请查询星仪本机保存的休眠定时状态"
        gate = remote["RefusalHandoff"]()
        gate.start(query)
        gate.submitted()
        rid = remote["submit_via_mcp"](gate.claim(query))
        end = time.monotonic() + 40
        while time.monotonic() < end:
            answer = remote["review_request"](rid, "status")
            if answer.state == "done":
                if not any(term in answer.text for term in ("定时","休眠","睡眠","分钟","待机")):
                    raise RuntimeError("unexpected timer answer")
                if retest_timer and any(term in answer.text for term in ("无法","失败","报错","授权")):
                    raise RuntimeError("current_schedule_still_ambiguous")
                report["explicit_manual_refusal_fixture"] = {"real_mcp_submission":True, "tv_answer":answer.text[:320]}
                break
            if answer.state not in ("working",):
                raise RuntimeError("unexpected receipt state " + answer.state)
            time.sleep(.4)
        assert "explicit_manual_refusal_fixture" in report
        device.home()
        if not retest_timer:
            bridge = VOICE/"assistant/mcp_bridge"
            p = subprocess.run([str(bridge/".venv/Scripts/python.exe"), str(bridge/"xiaoai_skill/verify_online_preview.py")],
                               cwd=bridge,timeout=68)
            report["song_preview_passed"] = p.returncode == 0
        else:
            report["installed_version_code"] = code
            report["installed_sha256"] = digest
    finally:
        device.deadline = time.monotonic() + 8
        device.home()
        name = "windows-fallback-0.6.1.json" if retest_timer else "windows-fallback.json"
        (VOICE/"evidence-0.6"/name).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf8")
        print(json.dumps(report,ensure_ascii=True))
    return 0 if report.get("windows_native_opened_settings") and (retest_timer or report.get("song_preview_passed")) else 1


if __name__ == "__main__":
    raise SystemExit(main())
