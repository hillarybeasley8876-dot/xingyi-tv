"""Prove native-only and explicit handoff using a fresh, private tools/call receipt."""
import json
import re
import time
from deploy_voice_mcp import Run, APP, CHECK, VOICE
from probe_native_handoff import ask, focused


def receipt(device):
    message = device.shell("am broadcast -n " + CHECK + "/.ReceiptReceiver -a " + CHECK + ".READ_RECEIPT")
    match = re.search(r'data="?(\{[^\r\n]*?\})', message)
    if not match:
        raise RuntimeError("receipt unavailable")
    return json.loads(match[1])


def main():
    device = Run("192.168.31.162:5555")
    device.deadline = time.monotonic() + 95
    report = {"origin": "Xiaomi native text/NLP path, no microphone audio"}
    assert device.shell("getprop ro.product.model") == "MiTV-ASTP0"
    assert device.shell("getprop ro.product.device") == "mulan"
    assert device.installed_hash(APP) == device.local_info(VOICE/"xingyi-0.6.apk", APP)[1]
    apk = VOICE/"xingyi-voicecheck-1.1.apk"
    code, digest = device.local_info(apk, CHECK)
    assert (device.version(CHECK) or 0) <= code
    device.adb("install", "-r", str(apk), timeout=15)
    assert device.installed_hash(CHECK) == digest
    device.home_package = device.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").splitlines()[-1].split('/')[0]
    try:
        device.home()
        previous = receipt(device)
        assert previous == receipt(device)
        ask(device, "打开电视设置")
        until = time.monotonic() + 8
        native = False
        while time.monotonic() < until:
            window = focused(device)
            if "settings_window" in window or "com.xiaomi.mitv.settings/" in window:
                native = True
                break
            time.sleep(.3)
        report["native_settings_opened"] = native
        report["mcp_seq_unchanged_for_settings"] = previous == receipt(device)
        for phrase in ("启动应用星仪查音量", "打开星仪查音量这个应用"):
            device.home()
            assert device.front() != CHECK
            before = receipt(device).get("seq", 0)
            ask(device, phrase)
            until = time.monotonic() + 13
            after = {}
            while time.monotonic() < until:
                after = receipt(device)
                if after.get("seq", 0) > before:
                    break
                time.sleep(.6)
            current = list(device.volume())
            match = after.get("seq", 0) > before and current == [after.get("volume"), after.get("volume_max")]
            report.setdefault("handoff", []).append({"phrase": phrase, "seq_before": before,
                "receipt": after, "independent_volume": current, "fresh_match": match})
            if match:
                break
        report["verified"] = bool(native and report["mcp_seq_unchanged_for_settings"] and match)
        if not match:
            device.home()
            device.shell("am start -W -n " + CHECK + "/.VolumeActivity")
            time.sleep(1.2)
            report["direct_control"] = receipt(device)
    finally:
        device.deadline = time.monotonic()+8
        device.home()
        (VOICE/"evidence-0.6"/"native-receipt.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf8")
        print(json.dumps(report,ensure_ascii=True))
    return 0 if report.get("verified") else 1


if __name__ == "__main__":
    raise SystemExit(main())
