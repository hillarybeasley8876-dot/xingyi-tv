"""Exercise the actual relay window with a simulated TV; fixture tools never operate a device."""
import asyncio
import importlib.util
from pathlib import Path
import runpy
import shlex
import sys
import tkinter as tk
from tkinter import ttk
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
remote = runpy.run_path(str(ROOT / "voice_remote.pyw"))["submit_via_mcp"].__globals__


class HandoffTests(unittest.TestCase):
    def test_literal_xiaoai_service_args(self):
        text = """请播放 John's `radio` $(test); "$HOME" 音乐"""
        log = []
        def record(*args):
            log.append(args)
            return 0, "Starting service: Intent { ... }"
        with patch.dict(remote, adb=record, paired_target=lambda: ["-s","fixture"]), patch.object(remote["time"], "sleep"):
            remote["send_to_xiaoai"](text)
        self.assertEqual(len(log),2)
        self.assertTrue(log[0][-1].endswith("EXTERNAL_TEXT_START"))
        self.assertEqual(shlex.split(log[1][-1])[-3:], ["--es", "query", text])

    def test_expiry_and_consumption(self):
        now = [10]
        relay = remote["RefusalHandoff"](lambda: now[0])
        self.assertEqual(relay.start("查音量"), "查音量")
        with self.assertRaises(RuntimeError):
            relay.claim("查音量")
        relay.submitted()
        with self.assertRaises(RuntimeError):
            relay.claim("不同文字")
        now[0] = 130
        with self.assertRaises(RuntimeError):
            relay.claim("查音量")
        relay.reset()
        relay.start("播放音乐"); relay.submitted()
        self.assertEqual(relay.claim("播放音乐"), "播放音乐")
        with self.assertRaises(RuntimeError):
            relay.claim("播放音乐")

    def test_unknown_service_reply_is_never_retried(self):
        attempted = []
        def lost_reply(*args):
            attempted.append(args)
            if len(attempted) == 2:
                raise TimeoutError("response lost")
            return 0, "Starting service: Intent { ... }"
        with patch.dict(remote, adb=lost_reply, paired_target=lambda: ["-s", "fixture"]), patch.object(remote["time"],"sleep"):
            with self.assertRaises(TimeoutError):
                remote["send_to_xiaoai"]("查音量")
        self.assertEqual(len(attempted), 2)

    def test_real_window_negative_ack_is_required(self):
        note = []
        args = []
        Root = tk.Tk
        phase = ["initial"]
        text = "打开网易云音乐播放林俊杰的不为谁而作的歌"
        def fake_root():
            w = Root()
            w.withdraw()
            def drive():
                try:
                    widgets = w.winfo_children()[0].winfo_children()
                    buttons = {b.cget("text"): b for b in widgets if isinstance(b,ttk.Button)}
                    primary, fallback = buttons["先交给小爱"], buttons["小爱说做不了，交给 MCP"]
                    field = next(b for b in widgets if isinstance(b,ttk.Entry) and not isinstance(b,ttk.Combobox))
                    if phase[0] == "initial" and primary.instate(["!disabled"]):
                        field.insert(0,text)
                        self.assertTrue(fallback.instate(["disabled"]))
                        primary.invoke();primary.invoke()
                        phase[0] = "awaiting"
                    elif phase[0] == "awaiting" and fallback.instate(["!disabled"]):
                        self.assertEqual(args,[("xiaoai",text)])
                        self.assertTrue(primary.instate(["disabled"]))
                        fallback.invoke();fallback.invoke()
                        phase[0] = "sent"
                    elif phase[0] == "sent" and len(args) >= 3:
                        self.assertEqual(args[:3],[("xiaoai",text),("mcp",text),("review","status")])
                        self.assertTrue(fallback.instate(["disabled"]))
                        note.append("ok")
                        w.tk.call(w.protocol("WM_DELETE_WINDOW")); return
                    w.after(25,drive)
                except Exception as error:
                    note.append(repr(error)); w.tk.call(w.protocol("WM_DELETE_WINDOW"))
            w.after(30,drive)
            w.after(6500,lambda:w.tk.call(w.protocol("WM_DELETE_WINDOW")))
            return w
        def review(rid,op,preview=None):
            args.append(("review",op))
            return remote["VoiceReview"]("pending","只读预览","YWJj")
        def via_mcp(q):
            args.append(("mcp",q));return "5" * 32
        with patch.object(tk,"Tk",fake_root),patch.dict(remote,microphones=lambda:[], send_to_xiaoai=lambda q: args.append(("xiaoai",q)),
                submit_via_mcp=via_mcp,review_request=review):
            remote["main"]()
        self.assertEqual(note,["ok"])
        self.assertEqual([op for op,_ in args].count("mcp"),1)

    def test_mcp_sdk_real_transport_with_test_backend(self):
        from mcp import StdioServerParameters
        spec = importlib.util.spec_from_file_location("relay_client", ROOT / "mcp_bridge" / "relay_client.py")
        client = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(client)
        code = """import asyncio,server
def submit(text):
 assert text == '原始指令'
 return '1'*32
server.remote['send_transcript']=submit
asyncio.run(server.main())
"""
        params = StdioServerParameters(command=sys.executable,args=["-c",code],cwd=ROOT / "mcp_bridge",env={"PYTHONUTF8":"1"})
        result = asyncio.run(client.request({"op":"submit","text":"原始指令"},params))
        self.assertEqual(result,{"state":"accepted","request_id":"1"*32})
        with self.assertRaises(ValueError):
            asyncio.run(client.request({"op":"confirm","text":"原始指令"},params))


if __name__ == "__main__":
    unittest.main()
