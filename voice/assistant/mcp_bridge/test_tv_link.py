"""Offline checks for tv_link (standard library only): python -m unittest test_tv_link -v"""
import json
import socket
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest import mock

import tv_link

TARGET = "192.168.31.162:5555"
ISALIVE = {"status": 0, "msg": "success",
           "data": {"devicename": "客厅的REDMI 智能电视 A55 2025 节能版", "ip": "192.168.31.162:6095", "platform": 1472}}


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Assistant:
    """A stand-in for the TV's remote-control service on a local port."""

    def __init__(self, payload=ISALIVE, raw=None):
        body = raw if raw is not None else json.dumps(payload).encode()

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class Listener:
    """A TCP port that accepts connections (adb listening)."""

    def __init__(self):
        self.sock = socket.socket()
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(5)
        self.port = self.sock.getsockname()[1]

    def close(self):
        self.sock.close()


class FakeAdb:
    def __init__(self, connect_works=False, devices=""):
        self.calls, self.connected = [], False
        self.connect_works, self.devices = connect_works, devices

    def __call__(self, *args, timeout=10):
        self.calls.append(args)
        if args == ("-s", TARGET, "get-state"):
            return (0, "device") if self.connected else (1, "")
        if args[0] == "connect":
            self.connected = self.connect_works
            return 0, "connected to " + TARGET if self.connect_works else "cannot connect"
        if args[0] == "devices":
            return 0, "List of devices attached\n" + self.devices
        raise AssertionError(f"unexpected adb call {args}")


class TcpProbe(unittest.TestCase):
    def test_open_port(self):
        listener = Listener()
        try:
            self.assertEqual(tv_link.tcp_probe("127.0.0.1", listener.port, timeout=4), "open")
        finally:
            listener.close()

    def test_closed_port_is_refused_not_silent(self):
        self.assertEqual(tv_link.tcp_probe("127.0.0.1", free_port(), timeout=5), "refused")

    def test_no_answer_is_silent(self):
        with mock.patch("socket.create_connection", side_effect=socket.timeout("timed out")):
            self.assertEqual(tv_link.tcp_probe("10.0.0.9", 5555), "silent")

    def test_network_unreachable_is_silent(self):
        error = OSError(10051, "network is unreachable")
        with mock.patch("socket.create_connection", side_effect=error):
            self.assertEqual(tv_link.tcp_probe("10.0.0.9", 5555), "silent")

    def test_windows_refusal_code_without_the_subclass_is_still_refused(self):
        error = OSError(10061, "refused")
        with mock.patch("socket.create_connection", side_effect=error):
            self.assertEqual(tv_link.tcp_probe("10.0.0.9", 5555), "refused")


class AssistantProbe(unittest.TestCase):
    def test_names_the_tv(self):
        service = Assistant()
        try:
            found = tv_link.assistant_probe("127.0.0.1", service.port, timeout=3)
        finally:
            service.close()
        self.assertEqual(found, {"name": "客厅的REDMI 智能电视 A55 2025 节能版"})

    def test_other_json_is_not_the_tv(self):
        for raw in (b"not json", b"[]", b'{"status": 1, "data": {}}', b'{"status": 0, "data": 5}'):
            service = Assistant(raw=raw)
            try:
                self.assertIsNone(tv_link.assistant_probe("127.0.0.1", service.port, timeout=3), raw)
            finally:
                service.close()

    def test_nothing_listening(self):
        self.assertIsNone(tv_link.assistant_probe("127.0.0.1", free_port(), timeout=5))


class Classify(unittest.TestCase):
    def test_table(self):
        tv = {"name": "x"}
        self.assertEqual(tv_link.classify("open", tv), "ok")
        self.assertEqual(tv_link.classify("open", None), "ok")
        self.assertEqual(tv_link.classify("refused", tv), "adb_closed")
        self.assertEqual(tv_link.classify("silent", tv), "adb_closed")
        self.assertEqual(tv_link.classify("refused", None), "host_unclear")
        self.assertEqual(tv_link.classify("silent", None), "offline")


class Probe(unittest.TestCase):
    def test_adb_closed_while_the_tv_answers(self):
        service = Assistant()
        try:
            found = tv_link.probe("127.0.0.1", free_port(), service.port, timeout=5)
        finally:
            service.close()
        self.assertEqual(found["link"], "adb_closed")
        self.assertEqual(found["adb_port"], "refused")
        self.assertIn("REDMI", found["tv_name"])

    def test_everything_fine(self):
        listener = Listener()
        try:
            found = tv_link.probe("127.0.0.1", listener.port, free_port(), timeout=5)
        finally:
            listener.close()
        self.assertEqual(found["link"], "ok")


class Advice(unittest.TestCase):
    def test_each_cause_says_what_to_do(self):
        closed = tv_link.advice({"link": "adb_closed", "tv_name": "客厅的电视"}, "h")
        self.assertIn("ADB 调试", closed)
        self.assertIn("账号与安全", closed)
        self.assertIn("客厅的电视", closed)
        self.assertIn("XINGYI_TV", tv_link.advice({"link": "host_unclear"}, "h"))
        self.assertIn("Wi-Fi", tv_link.advice({"link": "offline"}, "h"))
        self.assertIn("始终允许", tv_link.advice({"link": "ok"}, "h", "unauthorized"))
        self.assertIn("offline", tv_link.advice({"link": "ok"}, "h", "offline"))


class EnsureLink(unittest.TestCase):
    def test_already_connected_does_not_reconnect(self):
        adb = FakeAdb()
        adb.connected = True
        tv_link.ensure_link(adb, TARGET, probe=mock.Mock(side_effect=AssertionError("must not probe")))
        self.assertEqual([c[0] for c in adb.calls], ["-s"])

    def test_one_reconnect_is_enough(self):
        adb = FakeAdb(connect_works=True)
        tv_link.ensure_link(adb, TARGET, probe=mock.Mock(side_effect=AssertionError("must not probe")))
        self.assertEqual([c[0] for c in adb.calls if c[0] == "connect"], ["connect"])

    def test_adb_switched_off_on_a_live_tv(self):
        adb = FakeAdb()
        found = {"link": "adb_closed", "adb_port": "refused", "tv_name": "客厅的电视"}
        with self.assertRaises(tv_link.TvLinkError) as caught:
            tv_link.ensure_link(adb, TARGET, probe=lambda host, port: found)
        self.assertEqual(caught.exception.data["link"], "adb_closed")
        self.assertIn("ADB 调试", str(caught.exception))

    def test_tv_not_on_the_network(self):
        adb = FakeAdb()
        found = {"link": "offline", "adb_port": "silent", "tv_name": None}
        with self.assertRaises(tv_link.TvLinkError) as caught:
            tv_link.ensure_link(adb, TARGET, probe=lambda host, port: found)
        self.assertEqual(caught.exception.data["link"], "offline")

    def test_port_open_but_this_pc_is_not_authorised(self):
        adb = FakeAdb(devices=f"{TARGET}\tunauthorized\n")
        found = {"link": "ok", "adb_port": "open", "tv_name": None}
        with self.assertRaises(tv_link.TvLinkError) as caught:
            tv_link.ensure_link(adb, TARGET, probe=lambda host, port: found)
        self.assertEqual(caught.exception.data["adb_state"], "unauthorized")
        self.assertIn("始终允许", str(caught.exception))

    def test_probe_gets_host_and_port_from_the_target(self):
        seen = []
        adb = FakeAdb()
        with self.assertRaises(tv_link.TvLinkError):
            tv_link.ensure_link(adb, TARGET, probe=lambda host, port: seen.append((host, port)) or {"link": "offline"})
        self.assertEqual(seen, [("192.168.31.162", 5555)])


if __name__ == "__main__":
    unittest.main()
