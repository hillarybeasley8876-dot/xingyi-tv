"""Bounded probe of our synthetic XiaoAI requests. No account ID, unrelated text, or XML leaves the TV."""
import argparse
import json
import os
import re
import shlex
import time
from pathlib import Path

from deploy_voice_mcp import ACTION, APP, MODEL, SERVICE, VOICE, Failure, Run

PROBE = "xyz.erapi0neer.xingyi.refusalprobe"
XIAO = "com.xiaomi.voicecontrol"
APK = VOICE / "refusal-probe/build/refusal-probe.apk"
QUERIES = ("一加一等于几", "查询星仪本机保存的休眠定时状态", "查询星仪接手服务上一次成功回执的序号")
EXTRA_QUERIES = ("你能读取星仪助手内部的本机休眠定时吗", "请查出星仪私有数据库里名为青柠校验的状态",
                 "查询星仪助手的本机休眠定时；只在确实能读取时报告实际结果")
# Negative preflight capability messages in the installed 5.12.0.7 APK resources.
# Detection here only classifies this synthetic test, never dispatches any action.
DECLINES = ("抱歉，当前应用暂不支持该操作。", "抱歉，您的设备暂不支持此功能。",
            "抱歉，该设备暂不支持此功能", "抱歉，您的电视暂不支持此功能。")


def account_selection():
    # Only Android's account-name header is matched. Skip ambiguous/absent identities.
    # Do not return any account value to the caller or write it to a file.
    return r"""account=$(dumpsys account 2>/dev/null |
    sed -nE 's/^[[:space:]]*Account \{name=([0-9]{1,32}), type=com\.xiaomi\}[[:space:]]*$/\1/p' | sort -u)
case "$account" in ''|*[!0-9]*) account=;; esac
"""


def history_sample(run, text, since):
    action = ("am broadcast --include-stopped-packages -n " + PROBE + "/.Probe -a " +
              PROBE + ".CHECK --es query " + shlex.quote(text) + " --el since " + str(since))
    script = account_selection() + 'if [ -n "$account" ]; then\n'
    script += 'printf "account=unique\\n"\nraw=$(' + action + ' --es account "$account" 2>/dev/null)\n'
    script += 'else\nprintf "account=ambiguous_or_unavailable\\n"\nraw=$(' + action + ' 2>/dev/null)\nfi\n'
    statuses = "no_account unavailable no_rows not_fresh query_missing query_mismatch match invalid dialog_mismatch size_limit permission_denied".split()
    # The host must never see an unfiltered broadcast result, even if the provider changes.
    for state in statuses:
        script += "case \"$raw\" in *'\"status\":\"" + state + "\"'*) echo status=" + state + ";; esac\n"
    script += "case \"$raw\" in *'\"status\":\"match\"'*)\n"
    for name in ("Template.Query", "Template.Toast", "Template.ToastStream", "SpeechSynthesizer.Speak",
                 "Nlp.LargeLanguageModelContent", "Template.Translation"):
        script += "case \"$raw\" in *'\"" + name + "\"'*) echo name=" + name + ";; esac\n"
    for category in ("SUCC", "SAME_STATE", "NEED_CONFIRM", "LIMIT", "UNSUPPORT", "EXCEPTION", "CANCEL", "MISSING", "UNKNOWN"):
        script += "case \"$raw\" in *'\"" + category + "\"'*) echo category=" + category + ";; esac\n"
    for k in ("multi_intent", "query_exact", "tts_type_present"):
        for v in ("true", "false"):
            script += "case \"$raw\" in *'\"" + k + "\":" + v + "'*) echo " + k + "=" + v + ";; esac\n"
    script += "printf '%s\\n' \"$raw\" | tr '{' '\\n' | while IFS= read -r part; do\n"
    script += """case "$part" in '"source":'*) :;; *) continue;; esac\n"""
    for source in ("speak", "toast", "toast_part", "toast_stream"):
        script += "case \"$part\" in '\"source\":\"" + source + "\"'*) echo feature=" + source + ";; esac\n"
    script += """printf '%s' "$part" | sed -nE 's/.*"len":([0-9]{1,5}),.*/feature_len=\\1/p'; echo\n"""
    if text == EXTRA_QUERIES[0]:
        script += """printf '%s' "$part" | sed -nE 's/.*"skeleton":"([0-9]{1,2}(-[0-9]{1,2}){0,95})".*/skeleton=\\1/p'; echo\n"""
    for marker in "self_limit unknown not_supported no_access await_retry privacy advice clarify reports_state polite math_two".split():
        script += "case \"$part\" in *'\"" + marker + "\"'*) echo marker=" + marker + ";; esac\n"
    for template in ("cannot_read", "firmware_unsupported", "unsupported_query"):
        script += "case \"$part\" in *'\"strong_template\":\"" + template + "\"'*) echo strong_template=" + template + ";; esac\n"
    script += "done\n"
    script += ";; esac"
    fields = {"status": "probe_unavailable", "names": [], "tts_category": [], "text_features": []}
    for line in run.shell(script, timeout=5, ok=False).splitlines():
        key, _, value = line.strip().partition("=")
        if key == "name":
            fields["names"].append(value)
        elif key == "category":
            fields["tts_category"].append(value)
        elif key == "feature":
            fields["text_features"].append({"source": value, "markers": [], "strong_template": None})
        elif key in ("marker", "strong_template", "feature_len", "skeleton"):
            if fields["text_features"]:
                feature = fields["text_features"][-1]
                if key == "marker":
                    feature["markers"].append(value)
                elif key == "feature_len" and value.isdecimal():
                    feature["len"] = int(value)
                elif key == "strong_template":
                    feature[key] = value
                elif key == "skeleton" and text == EXTRA_QUERIES[0] and feature["source"] == "toast_stream":
                    if re.fullmatch(r"[0-9]{1,2}(?:-[0-9]{1,2}){0,95}", value):
                        feature[key] = [int(token) for token in value.split("-")]
        elif key and value:
            fields[key] = value
    return fields


def ui_sample(run, query):
    # Drop raw XML on the device. Only fixed tokens generated by our script are returned.
    # Never use node time proximity to associate a refusal with a different query.
    script = "q=" + shlex.quote(query) + "\n"
    # Address the child's piped stdout specifically. /dev/tty could bypass the
    # filter if adb ever allocated a terminal; no window contents may reach it.
    script += r"""timeout 5 uiautomator dump --compressed /proc/self/fd/1 2>/dev/null |
tr '<' '\n' |
while IFS= read -r n; do
 case "$n" in 'hierarchy '*|'/hierarchy>'*) echo hierarchy;; esac
 case "$n" in *'package="com.xiaomi.voicecontrol"'*) ;; *) continue;; esac
 case "$n" in
 *'resource-id="com.xiaomi.voicecontrol:id/query_text"'*|*'resource-id="com.xiaomi.voicecontrol:id/asr"'*)
   case "$n" in *"text=\"$q\""*) echo same_query;; *'text=""'*) :;; *) echo different_query;; esac;;
 *'resource-id="com.xiaomi.voicecontrol:id/toast_result_nlp"'*|*'resource-id="com.xiaomi.voicecontrol:id/template_text"'*)
   case "$n" in
"""
    for idx, message in enumerate(DECLINES):
        script += "   *" + shlex.quote('text="' + message + '"') + "*) echo decline_" + str(idx) + ";;\n"
    script += r"""   *'text="2"'*|*'text="2。"'*|*'text="二"'*) echo math_answer;;
   *'text=""'*) :;; *) echo other_response;; esac;;
 esac
done
"""
    found = set(run.shell(script, timeout=7, ok=False).splitlines())
    expected = {"hierarchy", "same_query", "different_query", "math_answer", "other_response"}
    expected.update("decline_" + str(i) for i in range(len(DECLINES)))
    found.intersection_update(expected)
    declined = any(x.startswith("decline_") for x in found)
    return {"flags": sorted(found),
            "controlled_decline_pair": declined and "same_query" in found and "different_query" not in found}


def epoch_ms(run):
    value = run.shell("date +%s%3N")
    if re.fullmatch(r"\d{13}", value):
        return int(value)
    sec = run.shell("date +%s")
    if not re.fullmatch(r"\d{10}", sec):
        raise Failure("device_clock_unavailable")
    return int(sec) * 1000


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=VOICE / "evidence-once/controlled-probe.json")
    parser.add_argument("--ui-only", action="store_true")
    parser.add_argument("--extended", action="store_true", help="Test the alternate three read-only prompts.")
    parser.add_argument("--case", type=int, choices=(0, 1, 2, 3), help="Run one numbered prompt; 0 and 1 both select the first prompt.")
    args = parser.parse_args()
    run = Run(os.getenv("XINGYI_TV", "192.168.31.162:5555"))
    report = {"source": "owned synthetic phrases through Xiaomi EXTERNAL_TEXT without microphone",
              "cases": [], "scope": "temporary test probes only; no fallback command was executed"}
    installed = False
    home = False
    try:
        if run.adb("get-state", ok=False) != "device" or run.shell("getprop ro.product.model") != MODEL or run.shell("getprop ro.product.device") != "mulan" or run.version(APP) is None:
            raise Failure("not_the_paired_tv")
        run.home_package = run.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").splitlines()[-1].split("/")[0]
        if run.home_package != "xyz.erapi0neer.orrery":
            raise Failure("unexpected_home")
        if not args.ui_only:
            code, digest = run.local_info(APK, PROBE)
            old = run.version(PROBE)
            if old is None or old <= code:
                # Our own short-lived diagnostic may remain installed if the preceding
                # Wi-Fi session dropped during cleanup. Android enforces signing continuity.
                run.adb("install", "-r", str(APK), timeout=12)
                installed = True
            if run.version(PROBE) != code or run.installed_hash(PROBE) != digest:
                raise Failure("probe_build_mismatch")
        home = True
        run.home()
        report["baseline_ui"] = ui_sample(run, QUERIES[0])
        queries = EXTRA_QUERIES if args.extended else QUERIES
        if args.case is not None:
            queries = (queries[max(0,args.case - 1)],)
        for index, query in enumerate(queries):
            if index and time.monotonic() + 13 >= run.deadline:
                break
            run.home()
            since = epoch_ms(run)
            run.shell("am startservice -n " + SERVICE + " -a " + ACTION + "START")
            time.sleep(.35)
            run.shell("am startservice -n " + SERVICE + " -a " + ACTION + "STOP --es query " + shlex.quote(query))
            row = {"test_input": query, "ui": [], "history": []}
            report["cases"].append(row)
            time.sleep(.45)
            for _ in range(2):
                if time.monotonic() + 8 >= run.deadline:
                    break
                row["ui"].append(ui_sample(run, query))
                if not args.ui_only:
                    row["history"].append(history_sample(run, query, since))
                if row["ui"][-1]["controlled_decline_pair"]:
                    break
        report["ran_all"] = len(report["cases"]) == len(queries)
    except Failure as error:
        report["failure"] = str(error)
    finally:
        run.deadline = time.monotonic() + 5
        try:
            if installed:
                report["temporary_probe_removed"] = "Success" in run.adb("uninstall", PROBE, ok=False, timeout=3)
        finally:
            if home:
                run.shell("input keyevent KEYCODE_HOME", ok=False, timeout=2)
        args.out.parent.mkdir(exist_ok=True, parents=True)
        args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf8")
        print(json.dumps(report, ensure_ascii=True))
    return 0 if report.get("ran_all") else 1


if __name__ == "__main__":
    raise SystemExit(main())
