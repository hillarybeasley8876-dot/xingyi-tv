"""Push-to-talk Windows companion. Only a reviewed UTF-8 transcript leaves this PC."""
import base64
import json
from concurrent.futures import ThreadPoolExecutor
from collections import namedtuple
from pathlib import Path
import os
import queue
import re
import secrets
import shutil
import shlex
import subprocess
import time
import tkinter as tk
from tkinter import ttk
from tkinter.scrolledtext import ScrolledText

APP = "xyz.erapi0neer.xingyi"
TV = os.environ.get("XINGYI_TV", "192.0.2.10:5555")
ADB = Path(__file__).resolve().parents[3] / "platform-tools" / "adb.exe"
CACHE = Path("H:/hf-cache/hub/models--mobiuslabsgmbh--faster-whisper-large-v3-turbo/snapshots")
HIDDEN = getattr(subprocess, "CREATE_NO_WINDOW", 0)
FFMPEG = shutil.which("ffmpeg")


def microphones():
    if not FFMPEG:
        raise RuntimeError("找不到 FFmpeg，无法读取麦克风。")
    result = subprocess.run([FFMPEG, "-list_devices", "true", "-f", "dshow", "-i", "dummy"],
                            capture_output=True, timeout=7, creationflags=HIDDEN)
    names = re.findall(r'"([^"\r\n]+)"\s*\(audio\)', result.stderr.decode("utf-8", "replace"))
    return list(dict.fromkeys(names))


class LocalRecognizer:
    def __init__(self):
        self.model = None

    def transcribe(self, samples):
        import numpy as np
        samples = np.asarray(samples, dtype=np.float32)
        if samples.size < 4000 or float(np.abs(samples).max()) < .004:
            raise RuntimeError("没有听到人声，请检查所选麦克风再试。")
        if self.model is None:
            import ctranslate2
            from faster_whisper import WhisperModel
            configured = os.environ.get("XINGYI_WHISPER_MODEL")
            folder = Path(configured) if configured else next(
                (p.parent for p in sorted(CACHE.glob("*/model.bin"))), None)
            if not folder or not (folder / "model.bin").is_file():
                raise RuntimeError("没有找到本地中文识别模型，可用 XINGYI_WHISPER_MODEL 指定模型目录。")
            device = "cuda" if ctranslate2.get_cuda_device_count() else "cpu"
            dtype = "float16" if device == "cuda" else "int8"
            self.model = WhisperModel(str(folder), device=device, compute_type=dtype, local_files_only=True)
        parts, _ = self.model.transcribe(samples, language="zh", beam_size=3,
                                         condition_on_previous_text=False, vad_filter=True,
                                         initial_prompt="以下是简体中文电视指令。")
        text = "".join(p.text for p in parts).strip()
        if not text:
            raise RuntimeError("没有识别到指令，请再说一次。")
        return text


def adb(*args, timeout=10):
    if not ADB.is_file():
        raise RuntimeError("找不到本机已配对电视用的 ADB。")
    p = subprocess.run([str(ADB), *args], capture_output=True, timeout=timeout, creationflags=HIDDEN)
    return p.returncode, p.stdout.decode("utf-8", "replace").strip()


def checked_transcript(text):
    text = text.strip()
    if not 1 <= len(text.encode("utf-16-le")) // 2 <= 320 or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in text):
        raise RuntimeError("请填写一条 1 到 320 字的指令。")
    return text


def paired_target():
    target = ["-s", TV]
    if adb(*target, "get-state") != (0, "device"):
        adb("connect", TV)
        if adb(*target, "get-state") != (0, "device"):
            raise RuntimeError("电视未连接，请确认电脑与电视在同一网络，电视已允许调试。")
    return target


def send_transcript(text):
    text = checked_transcript(text)
    target = paired_target()
    code, start = adb(*target, "shell", "am", "start", "-W", "-n", APP + "/.MainActivity", timeout=15)
    if code or "Error" in start or "Exception" in start:
        raise RuntimeError("无法打开电视上的星仪助手。")
    # A single base64 argument keeps all reviewed text out of the Android shell grammar.
    encoded = base64.b64encode(text.encode("utf-8")).decode("ascii")
    request_id = secrets.token_hex(16)
    for attempt in range(3):
        code, reply = adb(*target, "shell", "am", "broadcast", "-a", APP + ".VOICE_QUERY",
                          "-p", APP, "--es", "request_id", request_id, "--es", "text_b64", encoded)
        result = re.search(r"Broadcast completed: result=(\d+)", reply)
        value = int(result[1]) if not code and result else 0
        if value == 1:
            return request_id
        if value == 2:
            raise RuntimeError("星仪有待处理的指令，请先在电视上完成或取消。")
        if value != 3:
            raise RuntimeError("电视没有接收这条指令，请检查星仪版本是否为 0.4 或以上。")
        if attempt < 2:
            time.sleep(.45)
    raise RuntimeError("请在电视上把星仪切到前台后重试。")


def send_to_xiaoai(text):
    text = checked_transcript(text)
    target = paired_target()
    service = "am startservice -n com.xiaomi.voicecontrol/.VoiceControlService -a com.xiaomi.mitv.voice.EXTERNAL_TEXT_"
    for command in (service + "START", service + "STOP --es query " + shlex.quote(text)):
        code, reply = adb(*target, "shell", command)
        if code or "Starting service:" not in reply or "Error" in reply or "Exception" in reply:
            raise RuntimeError("未收到小爱文字入口的正常回执，请先核对电视上的执行情况。")
        if command == service + "START":
            time.sleep(.35)


def submit_via_mcp(text):
    bridge = Path(__file__).resolve().parent / "mcp_bridge"
    interpreter = bridge / ".venv" / "Scripts" / "python.exe"
    if not interpreter.is_file():
        raise RuntimeError("缺少星仪 MCP 客户端的运行环境。")
    request = json.dumps({"op": "submit", "text": checked_transcript(text)}, ensure_ascii=False).encode("utf-8")
    p = subprocess.run([str(interpreter), str(bridge / "relay_client.py")], input=request,
                       capture_output=True, timeout=60, creationflags=HIDDEN)
    try:
        result = json.loads(p.stdout)
    except (UnicodeError, ValueError):
        result = {}
    if p.returncode or result.get("state") != "accepted" or not re.fullmatch("[0-9a-f]{32}", str(result.get("request_id", ""))):
        raise RuntimeError("MCP 未返回有效的提交回执，请先核对电视上的执行情况。")
    return result["request_id"]


class RefusalHandoff:
    """A confirmed decline is a one-time decision bound to this exact submitted draft."""
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.reset()

    def reset(self):
        self.text = None
        self.stage = "empty"
        self.until = 0

    def start(self, draft):
        if self.stage != "empty":
            raise RuntimeError("这条指令已提交过。请先核对电视，另起一条新指令。")
        self.text = checked_transcript(draft)
        self.stage = "sending"
        return self.text

    def submitted(self):
        if self.stage == "sending":
            self.stage = "awaiting_user"
            self.until = self.clock() + 120

    def can_handoff(self):
        if self.stage == "awaiting_user" and self.clock() >= self.until:
            self.stage = "expired"
        return self.stage == "awaiting_user"

    def claim(self, draft):
        if not self.can_handoff() or draft.strip() != self.text:
            raise RuntimeError("这条指令已修改、已过期或已经接力。")
        self.stage = "consumed"
        return self.text


VoiceReview = namedtuple("VoiceReview", "state text preview_b64")


def review_request(request_id, op="status", preview_b64=None):
    if not re.fullmatch("[0-9a-f]{32}", request_id) or op not in ("status", "confirm", "cancel"):
        raise ValueError("无效的远程指令。")
    args = ["-s", TV, "shell", "am", "broadcast", "-a", APP + ".VOICE_REVIEW", "-p", APP,
            "--es", "request_id", request_id, "--es", "op", op]
    if op == "confirm":
        if not isinstance(preview_b64, str) or not preview_b64:
            raise ValueError("确认时缺少电视返回的操作预览。")
        base64.b64decode(preview_b64, validate=True).decode("utf-8")
        args.extend(["--es", "preview_b64", preview_b64])
    code, reply = adb(*args)
    parsed = re.search(r"(?m)^Broadcast completed: result=(\d+)(?:, data=\"([A-Za-z0-9+/=]*)\")?", reply)
    if code or not parsed:
        raise RuntimeError("未收到电视回复，请检查网络后刷新状态。")
    states = {1: "working", 2: "pending", 3: "done", 4: "stale"}
    state = states.get(int(parsed[1]))
    if state is None:
        raise RuntimeError("电视尚未提供电脑审批，请检查星仪版本。")
    encoded = parsed[2] or ""
    if state in ("pending", "done"):
        if not encoded:
            raise RuntimeError("电视没有返回完整文字，请刷新状态。")
        try:
            text = base64.b64decode(encoded, validate=True).decode("utf-8")
        except (ValueError, UnicodeError) as exc:
            raise RuntimeError("电视返回的文字编码有误，请刷新状态。") from exc
        return VoiceReview(state, text, encoded if state == "pending" else "")
    return VoiceReview(state, "", "")


def main():
    window = tk.Tk()
    window.title("星仪电视语音遥控器")
    window.columnconfigure(0, weight=1)
    frame = ttk.Frame(window, padding=20)
    frame.grid(sticky="nsew")
    frame.columnconfigure(0, weight=1)
    ttk.Label(frame, text="星仪电视语音遥控器", font=("Microsoft YaHei UI", 17)).grid(sticky="w")
    ttk.Label(frame, text="点「开始说话」录一条指令。电脑本地识别后，你可以改字再发送。").grid(sticky="w", pady=(6, 14))
    ttk.Label(frame, text="电脑麦克风").grid(sticky="w")
    mic = ttk.Combobox(frame, state="readonly", width=54)
    mic.grid(sticky="ew", pady=(3, 8))
    status = tk.StringVar(value="正在读取可用麦克风…")
    transcript = tk.StringVar()
    verdict = ttk.Label(frame, textvariable=status, wraplength=510)
    entry = ttk.Entry(frame, textvariable=transcript, font=("Microsoft YaHei UI", 11))
    jobs, events = ThreadPoolExecutor(max_workers=1), queue.Queue()
    recognizer = LocalRecognizer()
    active = {"recording": None, "closed": False, "request": None, "epoch": 0, "preview": "", "busy": True}
    handoff = RefusalHandoff()

    def set_busy(busy):
        active["busy"] = busy
        speak.configure(state="disabled" if busy or not mic.get() else "normal")
        fresh = not busy and handoff.stage == "empty"
        send.configure(state="normal" if fresh else "disabled")
        primary.configure(state="normal" if fresh else "disabled")
        fallback.configure(state="normal" if not busy and handoff.can_handoff() else "disabled")
        new_draft.configure(state="normal" if not busy and handoff.text is not None else "disabled")
        mic.configure(state="disabled" if busy else "readonly")
        entry.configure(state="disabled" if busy else "normal")

    def run(kind, fn):
        def work():
            try:
                events.put((kind, fn()))
            except Exception as exc:
                events.put((kind + "_error", str(exc)))
        jobs.submit(work)

    def speech():
        device = mic.get()
        if not device:
            return
        set_busy(True)
        status.set("正在听，请现在说话。8 秒后自动结束。")
        def capture():
            import numpy as np
            command = [FFMPEG, "-nostdin", "-hide_banner", "-loglevel", "error", "-f", "dshow",
                       "-i", "audio=" + device, "-t", "8", "-ac", "1", "-ar", "16000", "-f", "s16le", "pipe:1"]
            with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=HIDDEN) as proc:
                active["recording"] = proc
                try:
                    if active["closed"]:
                        proc.kill()
                        return ""
                    try:
                        raw, _ = proc.communicate(timeout=14)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                        proc.communicate()
                        raise RuntimeError("录音设备没有按时响应，请更换麦克风。")
                finally:
                    active["recording"] = None
            if proc.returncode:
                raise RuntimeError("无法从这个麦克风录音，请检查 Windows 麦克风权限，或更换设备。")
            events.put(("status", "在电脑上识别中…"))
            return recognizer.transcribe(np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768)
        run("transcript", capture)

    def transmit():
        text = transcript.get()
        if not text.strip():
            status.set("先说一句，或在输入框里填写指令。")
            return
        set_busy(True)
        show_review("")
        status.set("正在把这条文字指令送到电视…")
        run("sent", lambda: send_transcript(text))

    def send_primary():
        if active["busy"]:
            return
        try:
            original = handoff.start(transcript.get())
        except RuntimeError as exc:
            status.set(str(exc))
            return
        set_busy(True)
        show_review("")
        status.set("正在把原句交给电视小爱…")
        run("primary_sent", lambda: send_to_xiaoai(original))

    def fallback_after_refusal():
        if active["busy"]:
            return
        try:
            original = handoff.claim(transcript.get())
        except RuntimeError as exc:
            status.set(str(exc))
            set_busy(False)
            return
        set_busy(True)
        status.set("正在通过 MCP 把同一句话交给星仪…")
        run("relay_sent", lambda: submit_via_mcp(original))

    def replace_draft(*_):
        if handoff.text is not None:
            handoff.reset()
            status.set("已换成新指令。")
            set_busy(active["busy"])

    def expire_handoff():
        if handoff.stage == "awaiting_user" and not handoff.can_handoff():
            status.set("上一条接力预览已过期。请先核对电视，另起新指令。")
            set_busy(active["busy"])
        window.after(1000, expire_handoff)

    def show_review(text):
        if review_text.get("1.0", "end-1c") == text:
            return
        review_text.configure(state="normal")
        review_text.delete("1.0", "end")
        review_text.insert("1.0", text)
        review_text.configure(state="disabled")

    def review_buttons(confirm=False, cancel=False, refresh=False):
        for button, state in ((approve, confirm), (reject, cancel), (reload_button, refresh)):
            button.configure(state="normal" if state else "disabled")

    def issue_review(op):
        rid, preview = active["request"], active["preview"]
        if not rid or (op == "confirm" and not preview):
            return
        # Invalidate any in-flight passive status reply or scheduled poll before a new decision.
        active["epoch"] += 1
        epoch = active["epoch"]
        if op != "status":
            active["preview"] = ""
            review_buttons()
            status.set("已提交决定，正在等待电视回复…")
        def query():
            try:
                result = review_request(rid, op, preview if op == "confirm" else None)
                if result.state == "stale" and op != "status":
                    # The TV may already be offering a different preview. Fetch it for a fresh manual decision.
                    result = review_request(rid, "status")
                events.put(("review", (epoch, result)))
            except Exception as exc:
                events.put(("review_failure", (epoch, str(exc))))
        jobs.submit(query)

    def render_result(result):
        active["preview"] = result.preview_b64
        if result.state == "pending":
            show_review(result.text)
            status.set("电视等待你决定。请核对下面的全部操作后点「确认执行」。")
            review_buttons(confirm=True, cancel=True, refresh=True)
        elif result.state == "working":
            show_review("")
            status.set("星仪正在处理指令…")
            review_buttons(cancel=True, refresh=True)
        else:
            active["request"] = None
            review_buttons()
            set_busy(False)
            show_review(result.text)
            status.set("电视已返回结果。" if result.state == "done" else "这次指令或预览已失效。可重新发送指令。")
            return
        epoch = active["epoch"]
        def poll():
            if epoch == active["epoch"] and active["request"]:
                issue_review("status")
        window.after(2000 if result.state == "pending" else 800, poll)

    def handle():
        while not events.empty():
            kind, data = events.get_nowait()
            if kind == "devices":
                mic.configure(values=data)
                if data:
                    mic.current(0)
                status.set("准备就绪" if data else "未找到电脑麦克风。可以直接填写指令。")
            elif kind == "transcript":
                transcript.set(data)
                status.set("请核对文字，点「先交给小爱」。也可点「发送到电视」直接使用星仪。")
            elif kind == "primary_sent":
                handoff.submitted()
                status.set("原句已交给小爱。若小爱明确说做不了，点下方按钮接力。没听清或没反应时请先核对电视。")
            elif kind in ("primary_sent_error", "relay_sent_error"):
                handoff.stage = "uncertain"
                status.set("未确认这次提交的结果，请先核对电视；本条不会再次发送。" + data)
            elif kind in ("sent", "relay_sent"):
                active["request"] = data
                status.set("电视已收到，正在读取处理状态…")
                issue_review("status")
                continue
            elif kind in ("review", "review_failure"):
                epoch, result = data
                if epoch != active["epoch"]:
                    continue
                if kind == "review":
                    render_result(result)
                else:
                    active["preview"] = ""
                    review_buttons(refresh=True)
                    status.set(result)
                continue
            else:
                status.set(data)
            if kind != "status":
                set_busy(False)
        window.after(100, handle)

    def close():
        active["closed"] = True
        proc = active["recording"]
        if proc is not None and proc.poll() is None:
            proc.terminate()
        jobs.shutdown(wait=False, cancel_futures=True)
        window.destroy()

    speak = ttk.Button(frame, text="开始说话（8 秒）", command=speech)
    speak.grid(sticky="w", pady=(0, 14))
    ttk.Label(frame, text="要交给电视的文字").grid(sticky="w")
    entry.grid(sticky="ew", pady=(4, 10))
    primary = ttk.Button(frame, text="先交给小爱", command=send_primary)
    primary.grid(sticky="w")
    fallback = ttk.Button(frame, text="小爱说做不了，交给 MCP", command=fallback_after_refusal)
    fallback.grid(sticky="w", pady=(4, 0))
    send = ttk.Button(frame, text="发送到电视", command=transmit)
    send.grid(sticky="w", pady=(4, 0))
    new_draft = ttk.Button(frame, text="新指令", command=lambda: transcript.set(""))
    new_draft.grid(sticky="w", pady=(4, 0))
    transcript.trace_add("write", replace_draft)
    verdict.grid(sticky="w", pady=(12, 0))
    ttk.Label(frame, text="电视上的操作预览与结果").grid(sticky="w", pady=(10, 3))
    review_text = ScrolledText(frame, width=54, height=8, wrap="word", state="disabled")
    review_text.grid(sticky="ew")
    controls = ttk.Frame(frame)
    controls.grid(sticky="w", pady=(8, 0))
    approve = ttk.Button(controls, text="确认执行", command=lambda: issue_review("confirm"))
    reject = ttk.Button(controls, text="取消这条指令", command=lambda: issue_review("cancel"))
    reload_button = ttk.Button(controls, text="刷新状态", command=lambda: issue_review("status"))
    for col, button in enumerate((approve, reject, reload_button)):
        button.grid(row=0, column=col, padx=(0, 8))
    review_buttons()
    set_busy(True)
    run("devices", microphones)
    window.protocol("WM_DELETE_WINDOW", close)
    window.after(100, handle)
    window.after(1000, expire_handoff)
    window.mainloop()


if __name__ == "__main__":
    main()
