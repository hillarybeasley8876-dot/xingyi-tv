"""Windows companion approval regression. Uses a hidden Tk window and a simulated television."""
import base64
from pathlib import Path
import runpy
import tkinter as tk
from tkinter import ttk
import unittest
from unittest.mock import patch

source = Path(__file__).resolve().parents[1] / "voice_remote.pyw"
module = runpy.run_path(str(source))["review_request"].__globals__


def encode(text):
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


class ReviewTests(unittest.TestCase):
    def test_exact_preview_and_scope_on_wire(self):
        rid = "12" * 16
        preview = "1. 播放林俊杰的《不为谁而作的歌》\n2. 设定休眠"
        encoded = encode(preview)
        with patch.dict(module, adb=lambda *args: (0, 'Broadcast completed: result=2, data="' + encoded + '"')):
            state = module["review_request"](rid)
        self.assertEqual((state.state, state.text, state.preview_b64), ("pending", preview, encoded))
        with self.assertRaises(ValueError):
            module["review_request"](rid, "confirm", "")
        calls = []
        def complete(*args):
            calls.append(args)
            return 0, 'Broadcast completed: result=3, data="' + encode("播放中。") + '"'
        with patch.dict(module, adb=complete):
            response = module["review_request"](rid, "confirm", state.preview_b64)
        self.assertEqual(calls[0][-9:], ("--es", "request_id", rid, "--es", "op", "confirm",
                                         "--es", "preview_b64", encoded))
        self.assertEqual((response.state, response.text, response.preview_b64), ("done", "播放中。", ""))
        self.assertIn(module["APP"] + ".VOICE_REVIEW", calls[0])

    def test_window_requires_separate_click_for_each_step(self):
        rid = "ab" * 16
        request_count, approved = [], []
        texts = ["打开网易云音乐并播放指定版本", "设置休眠定时"]
        Root = tk.Tk
        verdict = []
        def mock_review(current_id, op="status", preview_b64=None):
            self.assertEqual(current_id, rid)
            if op == "confirm":
                self.assertEqual(preview_b64, encode(texts[len(approved)]))
                approved.append(preview_b64)
            else:
                self.assertEqual(op, "status")
            i = len(approved)
            kind = module["VoiceReview"]
            return kind("pending", texts[i], encode(texts[i])) if i < 2 else kind("done", "已完成", "")
        def fake_root():
            window = Root()
            window.withdraw()
            def drive():
                try:
                    frame = window.winfo_children()[0]
                    widgets = list(frame.winfo_children())
                    buttons = {w.cget("text"): w for w in widgets if isinstance(w, ttk.Button)}
                    controls = next(w for w in widgets if isinstance(w, ttk.Frame) and
                                    any(isinstance(b, ttk.Button) for b in w.winfo_children()))
                    confirm = next(b for b in controls.winfo_children() if b.cget("text") == "确认执行")
                    if not request_count and buttons["发送到电视"].instate(["!disabled"]):
                        self.assertTrue(confirm.instate(["disabled"]))
                        next(w for w in widgets if isinstance(w, ttk.Entry) and not isinstance(w, ttk.Combobox)).insert(0, "播放音乐")
                        buttons["发送到电视"].invoke()
                        request_count.append(True)
                    elif confirm.instate(["!disabled"]):
                        text_widget = next(child for widget in widgets for child in widget.winfo_children()
                                           if isinstance(child, tk.Text))
                        shown = text_widget.get("1.0", "end-1c")
                        self.assertEqual(shown, texts[len(approved)])
                        confirm.invoke()
                        self.assertTrue(confirm.instate(["disabled"]))
                    elif len(approved) == 2 and buttons["发送到电视"].instate(["!disabled"]):
                        verdict.append("done")
                        window.tk.call(window.protocol("WM_DELETE_WINDOW"))
                        return
                    window.after(25, drive)
                except Exception as exc:
                    verdict.append(repr(exc))
                    window.tk.call(window.protocol("WM_DELETE_WINDOW"))
            window.after(50, drive)
            window.after(6000, lambda: window.tk.call(window.protocol("WM_DELETE_WINDOW")))
            return window
        with patch.object(tk, "Tk", fake_root), patch.dict(module, microphones=lambda: ["测试麦克风"],
                        send_transcript=lambda _: rid, review_request=mock_review):
            module["main"]()
        self.assertEqual(verdict, ["done"])
        self.assertEqual(approved, [encode(t) for t in texts])


if __name__ == "__main__":
    unittest.main()
