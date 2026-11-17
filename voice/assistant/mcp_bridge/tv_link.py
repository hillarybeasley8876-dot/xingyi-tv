"""Tell apart why this PC cannot reach the TV's agent.

Over the network adb reports the same few words whether the TV is switched off, has moved to another address,
has debugging turned off, or has not authorised this PC. Two plain TCP probes separate them: a closed port answers
with a reset (so something is alive there), a silent one means nobody is home. The Xiaomi assistant service on
6095 is the TV's own remote-control endpoint; it keeps answering when only adb has been switched off, and its
reply names the device, so it also tells "our TV" from "some other device that took the address".
"""
import errno
import http.client
import json
import socket
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ADB_PORT = 5555
ASSISTANT_PORT = 6095
# Windows needs about two seconds to give up on SYN retries and report a refusal; a shorter timeout would turn
# "closed" into "silent".
PROBE_TIMEOUT = 3.0
REFUSED = {errno.ECONNREFUSED, 10061}


class TvLinkError(RuntimeError):
    """The PC cannot talk to the TV; `data` carries the machine-readable cause next to the message."""

    def __init__(self, message, **data):
        super().__init__(message)
        self.data = data


def tcp_probe(host, port, timeout=PROBE_TIMEOUT):
    """'open', 'refused' (something answered, the port is closed) or 'silent' (no answer at all)."""
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return "open"
    except ConnectionRefusedError:
        return "refused"
    except OSError as error:
        return "refused" if error.errno in REFUSED or getattr(error, "winerror", None) in REFUSED else "silent"


def assistant_probe(host, port=ASSISTANT_PORT, timeout=PROBE_TIMEOUT):
    """{'name': device name} when the TV's own remote-control service answers, else None."""
    try:
        with urllib.request.urlopen(f"http://{host}:{port}/request?action=isalive", timeout=timeout) as reply:
            data = json.loads(reply.read().decode("utf-8", "replace"))
    except (OSError, ValueError, http.client.HTTPException):
        return None
    if not isinstance(data, dict) or data.get("status") != 0 or not isinstance(data.get("data"), dict):
        return None
    return {"name": str(data["data"].get("devicename") or "")}


def classify(adb_port, assistant):
    """ok | adb_closed | host_unclear | offline, from the two probe results."""
    if adb_port == "open":
        return "ok"
    if assistant is not None:
        return "adb_closed"
    return "host_unclear" if adb_port == "refused" else "offline"


def probe(host, adb_port=ADB_PORT, assistant_port=ASSISTANT_PORT, timeout=PROBE_TIMEOUT):
    with ThreadPoolExecutor(2) as pool:
        adb_future = pool.submit(tcp_probe, host, adb_port, timeout)
        assistant_future = pool.submit(assistant_probe, host, assistant_port, timeout)
        adb_state, assistant = adb_future.result(), assistant_future.result()
    return {"link": classify(adb_state, assistant), "adb_port": adb_state,
            "tv_name": assistant["name"] if assistant else None}


def advice(found, host, adb_state=None):
    """One plain-words sentence for the user: what is wrong and what to do about it."""
    link = found["link"]
    if link == "adb_closed":
        name = f"（{found['tv_name']}）" if found.get("tv_name") else ""
        return (f"电视在线{name}，但 adb 调试口（{ADB_PORT}）是关着的，小米电视待机时会自己把它关掉。请用遥控器到 "
                "设置 → 账号与安全，打开「ADB 调试」，打开后直接再试，这边会自动重连。")
    if link == "host_unclear":
        return (f"{host} 上有设备应答，但不是电视的遥控服务，电视的 IP 很可能变了。到路由器的设备列表里找到电视的新地址，"
                "再把环境变量 XINGYI_TV 设成 新地址:5555。")
    if link == "offline":
        return f"连不上电视（{host} 没有任何应答）。确认电视没有关机，电脑和电视在同一个 Wi-Fi（YOUR_WIFI_SSID）里。"
    if adb_state == "unauthorized":
        return "电视还没有授权这台电脑调试。电视屏幕上会弹出「允许 USB 调试吗」，选「始终允许」。"
    if adb_state:
        return f"电视的调试口开着，但 adb 看到它的状态是 {adb_state}，不是 device。等几秒再试，仍不行就在电视上关掉再打开「ADB 调试」。"
    return "电视的调试口开着，但 adb 连不上它。等几秒再试，仍不行就在电视上关掉再打开「ADB 调试」。"


def listed_state(adb, target):
    """The state `adb devices` shows for the target (device/offline/unauthorized), or None when it is not listed."""
    _, text = adb("devices", timeout=8)
    for line in text.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[0] == target:
            return parts[1]
    return None


def ensure_link(adb, target, probe=probe):
    """Make sure adb lists the TV as a device (one reconnect is tried), otherwise raise TvLinkError saying why."""
    if adb("-s", target, "get-state", timeout=8) == (0, "device"):
        return
    adb("connect", target, timeout=12)
    if adb("-s", target, "get-state", timeout=8) == (0, "device"):
        return
    host, _, port = target.partition(":")
    found = probe(host, int(port) if port.isdigit() else ADB_PORT)
    adb_state = listed_state(adb, target) if found["link"] == "ok" else None
    raise TvLinkError(advice(found, host, adb_state), **found, adb_state=adb_state)
