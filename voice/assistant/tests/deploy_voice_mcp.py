"""Deploy to the paired MiTV and isolate native XiaoAI routing from direct MCP entry."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

VOICE = Path(__file__).resolve().parents[2]
TOOLS = VOICE.parents[1]
ADB = TOOLS / "platform-tools/adb.exe"
AAPT = TOOLS / "android-sdk/android-15/aapt2.exe"
APP = "xyz.erapi0neer.xingyi"
CHECK = APP + ".voicecheck"
SERVICE = "com.xiaomi.voicecontrol/.VoiceControlService"
ACTION = "com.xiaomi.mitv.voice.EXTERNAL_TEXT_"
PHRASE = "打开星仪查音量"
MODEL = "MiTV-ASTP0"


class Failure(Exception):
    pass


class Run:
    def __init__(self, serial):
        self.serial = serial
        self.deadline = time.monotonic() + 75  # reserve five more seconds for HOME/cleanup

    def command(self, args, timeout=6, ok=True):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise Failure("time_budget_exhausted")
        env = os.environ.copy()
        env.pop("ANDROID_SERIAL", None)
        try:
            result = subprocess.run([str(x) for x in args], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    timeout=min(timeout, remaining), env=env, check=False)
        except subprocess.TimeoutExpired:
            raise Failure("command_timed_out") from None
        if result.returncode and ok:
            raise Failure("command_failed")
        return result.stdout.decode("utf8", "replace").strip()

    def adb(self, *args, **options):
        return self.command([ADB, "-s", self.serial, *args], **options)

    def shell(self, *args, **options):
        return self.adb("shell", *args, **options)

    def front(self):
        line = self.shell("dumpsys activity activities | grep -m 1 mResumedActivity", ok=False)
        m = re.search(r"\bu\d+\s+([a-zA-Z0-9_.]+)/", line)
        return m[1] if m else ""

    def window_is_checker(self):
        line = self.shell("dumpsys window windows | grep -m 1 mCurrentFocus", ok=False)
        return bool(re.search(r"\bu\d+\s+" + re.escape(CHECK) + r"/", line))

    def home(self):
        self.shell("input keyevent KEYCODE_HOME")
        end = time.monotonic() + 4
        while time.monotonic() < end:
            if self.front() == self.home_package:
                return
            time.sleep(.18)
        raise Failure("home_not_observed")

    def volume(self):
        result = self.shell("media volume --stream 3 --get")
        m = re.search(r"volume is (\d+) in range \[(\d+)\.\.(\d+)\]", result)
        if not m or not int(m[2]) <= int(m[1]) <= int(m[3]):
            raise Failure("volume_readback_unavailable")
        return int(m[1]), int(m[3])

    def version(self, pkg):
        data = self.shell("dumpsys package " + pkg + " | grep -m 1 versionCode=", ok=False)
        m = re.search(r"\bversionCode=(\d+)\b", data)
        return int(m[1]) if m else None

    def local_info(self, path, pkg):
        if not path.is_file():
            raise Failure("release_apk_missing:" + pkg)
        data = self.command([AAPT, "dump", "badging", path])
        m = re.search(r"(?m)^package: name='([^']+)' versionCode='(\d+)'", data)
        if not m or m[1] != pkg:
            raise Failure("release_package_mismatch:" + pkg)
        return int(m[2]), hashlib.sha256(path.read_bytes()).hexdigest()

    def installed_hash(self, pkg):
        paths = self.shell("pm path " + pkg, ok=False).splitlines()
        path = next((ln.strip()[8:] for ln in paths if ln.startswith("package:") and ln.strip().endswith("/base.apk")), "")
        if not re.fullmatch(r"/data/app/[A-Za-z0-9=/_.+-]+/base\.apk", path):
            return ""
        m = re.match(r"[0-9a-fA-F]{64}\b", self.shell("sha256sum " + path, ok=False))
        return m[0].lower() if m else ""

    def observe(self, seconds):
        end = time.monotonic() + seconds
        launched = False
        while time.monotonic() < end:
            if self.front() != CHECK or not self.window_is_checker():
                time.sleep(.25)
                continue
            launched = True
            # Keep XML in process memory. Never inspect a different app or persist UI content.
            try:
                xml = self.adb("exec-out", "uiautomator", "dump", "/dev/tty",
                               timeout=min(6, max(.1, end - time.monotonic())), ok=False)
            except Failure:
                continue
            if self.front() != CHECK or not self.window_is_checker():
                continue
            start, tail = xml.find("<?xml"), xml.find("</hierarchy>")
            if start < 0 or tail < start:
                continue
            try:
                root = ET.fromstring(xml[start:tail+len("</hierarchy>")])
            except ET.ParseError:
                continue
            texts = {node.get("text", "") for node in root.iter("node") if node.get("package") == CHECK}
            meter = next((re.fullmatch(r"(\d+)\s*/\s*(\d+)", t) for t in texts if re.fullmatch(r"\d+\s*/\s*\d+",t)),None)
            if meter and "星仪查音量" in texts:
                shown = (int(meter[1]),int(meter[2]))
                system = self.volume()
                return dict(launched=True, matches=shown == system, displayed=shown, independent=system)
            if "未取得读数" in texts:
                return dict(launched=True,matches=False,reason="mcp_read_unavailable")
        return dict(launched=launched,matches=False,reason="no_verified_readout" if launched else "not_launched")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify-only", action="store_true", help="Require both installed APKs to match; skip install.")
    parser.add_argument("--checker", type=Path, default=VOICE/"spoken-mcp/build/xingyi-voicecheck.apk")
    parser.add_argument("--assistant", type=Path, default=VOICE/"xingyi-0.6.apk")
    parser.add_argument("--evidence", type=Path, default=VOICE/"evidence-0.6"/"native-route.json")
    args = parser.parse_args()
    serial = os.environ.get("XINGYI_TV", "192.0.2.10:5555")
    if not re.fullmatch(r"[a-zA-Z0-9.:-]{1,128}", serial) or serial.startswith("-"):
        raise SystemExit("Invalid XINGYI_TV")
    run = Run(serial)
    report = dict(input="xiaomi_external_text_not_acoustic",phrase=PHRASE,passed=False)
    return_home = False
    try:
        if run.adb("get-state",ok=False) != "device":
            raise Failure("paired_tv_unreachable")
        if run.shell("getprop ro.product.model") != MODEL or run.shell("getprop ro.product.device") != "mulan":
            raise Failure("device_identity_mismatch")
        report["model"] = MODEL
        if run.version(APP) is None:
            raise Failure("existing_assistant_not_found")
        releases = [(APP, args.assistant), (CHECK, args.checker)]
        planned = []
        for pkg, path in releases:
            target, digest = run.local_info(path,pkg)
            old = run.version(pkg)
            if old is not None and old > target:
                raise Failure("newer_version_installed:" + pkg)
            installed = run.installed_hash(pkg)
            if args.verify_only and installed != digest:
                raise Failure("installed_hash_mismatch:" + pkg)
            planned.append((pkg,path,target,digest,installed))
        report["packages"] = {}
        for pkg,path,version,digest,installed in planned:
            if digest != installed:
                answer = run.adb("install","-r",str(path),timeout=18)
                if "Success" not in answer.splitlines():
                    raise Failure("apk_install_failed:" + pkg)
            if run.version(pkg) != version or run.installed_hash(pkg) != digest:
                raise Failure("apk_verification_failed:" + pkg)
            report["packages"][pkg] = dict(version_code=version,sha256=digest)
        resolved = run.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
        names = re.findall(r"(?m)^([a-zA-Z0-9_.]+)/[a-zA-Z0-9_.]+$", resolved.replace("\r",""))
        if not names:
            raise Failure("home_component_unavailable")
        run.home_package = names[-1]
        report["initial_volume"] = run.volume()
        return_home = True
        run.home()
        run.shell("am startservice -n " + SERVICE + " -a " + ACTION + "START")
        time.sleep(.35)
        run.shell("am startservice -n " + SERVICE + " -a " + ACTION + "STOP --es query " + PHRASE)
        report["native_route"] = run.observe(13)
        run.home()
        # End the checker with BACK if it was opened, instead of marking an app stopped
        # before asking Xiaomi to discover it by its launcher name.
        if run.front() == CHECK:
            run.shell("input keyevent 4")
        run.shell("am start -n " + CHECK + "/.VolumeActivity")
        report["direct_companion"] = run.observe(13)
        report["passed"] = report["native_route"]["matches"] and report["direct_companion"]["matches"]
    except (Failure,OSError) as exc:
        report["error"] = str(exc) if isinstance(exc,Failure) else "host_or_device_io_error"
    finally:
        if return_home:
            run.deadline += 5
            try:
                run.home()
                report["home_restored"] = True
            except (Failure,OSError):
                report["home_restored"] = False
                report["passed"] = False
        args.evidence.parent.mkdir(parents=True,exist_ok=True)
        temp = args.evidence.with_name(args.evidence.name + "." + secrets.token_hex(4) + ".tmp")
        temp.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf8")
        temp.replace(args.evidence)
        print(json.dumps(report,ensure_ascii=True))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
