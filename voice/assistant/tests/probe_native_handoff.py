"""Compare native handling and named app handoff, using our known test phrases only."""
import json
import re
import shlex
import subprocess
import time
from pathlib import Path
from deploy_voice_mcp import Run, APP, CHECK, SERVICE, ACTION, VOICE


def focused(run):
    return run.shell('dumpsys window windows | grep -m 1 mCurrentFocus', ok=False)


def ask(run, phrase):
    run.shell("am startservice -n " + SERVICE + " -a " + ACTION + "START")
    time.sleep(.45)
    run.shell("am startservice -n " + SERVICE + " -a " + ACTION + "STOP --es query " + shlex.quote(phrase))


def run_probe():
    run = Run("192.168.31.162:5555")
    run.deadline = time.monotonic() + 100
    assert run.shell("getprop ro.product.model") == "MiTV-ASTP0"
    home = run.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").splitlines()[-1]
    run.home_package = home.split('/')[0]
    report = {"input": "own synthetic text submitted to original Xiaomi NLP", "attempts": []}
    output = VOICE / "evidence-0.6" / "native-first.json"
    try:
        report["power"] = run.shell("dumpsys power | grep -m 1 'mWakefulness='")
        # Warm the upgraded app's launcher entry once. BACK finishes it without
        # setting Android's stopped bit. The voice test must begin on HOME.
        run.shell("am start -W -n " + CHECK + "/.VolumeActivity")
        report["warmup_only"] = run.observe(6)
        run.shell("input keyevent 4")
        for phrase, destination in [
                ("打开电视设置", "settings"), ("打开星仪查音量", CHECK),
                ("启动应用星仪查音量", CHECK), ("打开星仪电视助手", APP)]:
            run.home()
            ask(run, phrase)
            end = time.monotonic() + 8
            opened = False
            while time.monotonic() < end:
                app = run.front()
                if destination == "settings":
                    window = focused(run)
                    opened = app == "com.xiaomi.mitv.settings" or "settings_window" in window or "com.xiaomi.mitv.settings/" in window
                else:
                    opened = app == destination
                if opened:
                    break
                time.sleep(.35)
            row = {"phrase": phrase, "opened": opened}
            if destination == CHECK and opened:
                row["readback"] = run.observe(7)
            report["attempts"].append(row)
            if opened and destination == CHECK and row["readback"].get("matches"):
                break
        # Exercise the desktop stdio bridge with the real online TV, independently of
        # native app launch. No household commands are issued by this read-only probe.
        bridge = VOICE / "assistant" / "mcp_bridge"
        output_bytes = subprocess.check_output([str(bridge / ".venv/Scripts/python.exe"), str(bridge / "verify.py")],
                                              cwd=bridge, timeout=15)
        host = json.loads(output_bytes.decode("utf8"))
        report["desktop_mcp"] = host["real_paired_device"]
        skill_bytes = subprocess.check_output([str(bridge / ".venv/Scripts/python.exe"),
                                              str(bridge / "xiaoai_skill/verify.py")], cwd=bridge, timeout=18)
        report["skill_fixture"] = json.loads(skill_bytes.decode("utf8"))
    finally:
        run.deadline = time.monotonic() + 6
        run.home()
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf8")
        print(json.dumps(report, ensure_ascii=True))


if __name__ == "__main__":
    run_probe()
