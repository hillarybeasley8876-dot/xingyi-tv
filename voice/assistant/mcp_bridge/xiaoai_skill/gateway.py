"""Authenticated Xiaomi skill callback, hosted beside the owner's paired-TV MCP server."""
import argparse
import asyncio
import base64
import binascii
import hashlib
import hmac
import json
import os
import re
import sqlite3
import sys
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass
from email.utils import parsedate_to_datetime
from pathlib import Path

import uvicorn
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.routing import Route


MCP_ROOT = Path(__file__).resolve().parents[1]
ENDPOINT = "/xiaoai/skill"
MAX_BODY = 32768
MAX_AGE = 300
MUSIC_TERM = re.compile(r"[\w\u3400-\u9fff ·・'’&+./()（）-]{1,64}")


@dataclass(frozen=True)
class Settings:
    host: str
    key_id: str
    secret: bytes
    app_id: str
    owner_id: str
    replay_file: Path

    @classmethod
    def load(cls, path):
        fields = json.loads(path.read_text(encoding="utf-8-sig"))
        for name in ("host", "key_id", "secret_base64", "app_id", "owner_id"):
            if not isinstance(fields.get(name), str) or not fields[name].strip():
                raise ValueError("缺少技能设置 " + name)
        if not re.fullmatch(r"[a-zA-Z0-9.-]+(?::[0-9]+)?", fields["host"]):
            raise ValueError("host 必须与小米签名中的主机名相同")
        secret = base64.b64decode(fields["secret_base64"], validate=True)
        if len(secret) < 16:
            raise ValueError("请使用平台发放的签名密钥")
        return cls(fields["host"], fields["key_id"], secret, fields["app_id"],
                   fields["owner_id"], path.parent / "xiaoai-receipts.sqlite3")


def sign(secret, method, path, query, date, host, content_type, md5):
    # Xiaomi's published Scala and Java examples end after the MD5 newline.
    canonical = "\n".join((method, path, query, date, host, content_type, md5, ""))
    return hmac.new(secret, canonical.encode("utf-8"), hashlib.sha256).hexdigest()


def authentic(request, body, settings):
    for key in ("authorization", "host", "x-original-host", "x-xiaomi-date", "date", "content-type", "content-md5"):
        if len(request.headers.getlist(key)) > 1:
            return False
    h = request.headers
    host = h.get("x-original-host", h.get("host", ""))
    date = h.get("x-xiaomi-date", h.get("date", ""))
    content_type = h.get("content-type", "")
    if host != settings.host or content_type.split(";")[0].strip().lower() != "application/json":
        return False
    try:
        parsed = parsedate_to_datetime(date)
        if parsed.tzinfo is None or parsed.utcoffset().total_seconds() != 0 or abs(time.time() - parsed.timestamp()) > MAX_AGE:
            return False
    except (ValueError, TypeError, OverflowError):
        return False
    digest = base64.b64encode(hashlib.md5(body).digest()).decode("ascii")
    if h.get("content-md5") != digest:
        return False
    expected = "MIAI-HmacSHA256-V1 " + settings.key_id + "::" + sign(
        settings.secret, "POST", ENDPOINT, "", date, host, content_type, digest)
    return hmac.compare_digest(h.get("authorization", "").encode(), expected.encode())


def remember(db, request_id):
    # A crash or retry after dispatch must never launch the same request again.
    key = hashlib.sha256(request_id.encode("utf-8")).digest()
    now = int(time.time())
    with sqlite3.connect(db, timeout=2) as connection:
        connection.execute("CREATE TABLE IF NOT EXISTS seen (id BLOB PRIMARY KEY, expiry INTEGER NOT NULL)")
        connection.execute("DELETE FROM seen WHERE expiry < ?", (now,))
        inserted = connection.execute("INSERT OR IGNORE INTO seen VALUES (?,?)", (key, now + 2 * MAX_AGE + 5))
        return inserted.rowcount == 1


def speak(text, prompt=False, understood=True):
    return JSONResponse({"version": "1.0", "is_session_end": not prompt,
                         "response": {"open_mic": prompt, "not_understand": not understood,
                                      "to_speak": {"type": 0, "text": text},
                                      "to_display": {"type": 0, "text": text}}})


def resolve(event):
    req = event["request"]
    kind = req["type"]
    if type(kind) is not int or kind not in (0, 1, 2):
        raise ValueError("request type")
    if kind == 2:
        return None, None
    if req.get("no_response") is True:
        return "silence", None
    info = req.get("slot_info")
    if not info:
        raw = (req.get("intent") or {}).get("slots")
        info = json.loads(raw) if isinstance(raw, str) and raw else {}
    if not isinstance(info, dict):
        raise ValueError("slot info")
    intent = info.get("intent_name")
    if not intent and kind == 0:
        return "welcome", None
    if intent == "query_volume":
        return "tv_status", {}
    if intent == "play_song":
        slots = info.get("slots")
        if not isinstance(slots, list) or len(slots) != 2:
            return "missing_song", None
        terms = {}
        for slot in slots:
            if not isinstance(slot, dict) or slot.get("name") not in ("artist", "title"):
                return "missing_song", None
            value = slot.get("value")
            if not isinstance(value, str) or not MUSIC_TERM.fullmatch(value) or not value.strip():
                return "missing_song", None
            terms[slot["name"]] = value.strip()
        if set(terms) != {"artist", "title"}:
            return "missing_song", None
        return "xingyi_submit", {"text": "打开网易云音乐，播放歌手「%s」的歌曲《%s》。" % (terms["artist"], terms["title"])}
    return "unsupported", None


@asynccontextmanager
async def mcp_connection(app):
    environment = {"PYTHONUTF8": "1"}
    if "XINGYI_TV" in os.environ:
        environment["XINGYI_TV"] = os.environ["XINGYI_TV"]
    params = StdioServerParameters(command=sys.executable, args=[str(MCP_ROOT / "server.py")],
                                   cwd=MCP_ROOT, env=environment)
    async with stdio_client(params) as (reader, writer), ClientSession(reader, writer) as client:
        await client.initialize()
        names = {tool.name for tool in (await client.list_tools()).tools}
        if not {"tv_status", "xingyi_submit"} <= names:
            raise RuntimeError("星仪 MCP 未提供所需工具")
        app.state.call = client.call_tool
        yield


def application(settings):
    async def callback(request: Request):
        if request.scope.get("query_string") or request.scope.get("raw_path", b"") != ENDPOINT.encode():
            return Response(status_code=400)
        body = bytearray()
        async for block in request.stream():
            if len(body) + len(block) > MAX_BODY:
                return Response(status_code=413)
            body.extend(block)
        if not authentic(request, body, settings):
            return Response(status_code=401)
        try:
            event = json.loads(body)
            session = event["session"]
            if (event["version"] != "1.0" or session["application"]["app_id"] != settings.app_id
                    or session["user"]["user_id"] != settings.owner_id):
                return Response(status_code=403)
            request_id = event["request"]["request_id"]
            if not isinstance(request_id, str) or not (1 <= len(request_id) <= 256):
                return Response(status_code=400)
            tool, args = resolve(event)
        except (KeyError, ValueError, TypeError, AttributeError, UnicodeError, RecursionError):
            return Response(status_code=400)
        if not remember(settings.replay_file, request_id):
            return Response(status_code=409)
        if tool is None:
            return speak("好的。")
        if tool == "welcome":
            return speak("你可以问电视音量是多少，或让我在网易云播放指定歌手的一首歌。", prompt=True)
        if tool == "unsupported" or tool == "silence":
            return speak("本次星仪会话已结束。", understood=False)
        if tool == "missing_song":
            return speak("请一起说出歌手和歌名。", prompt=True)
        try:
            response = await asyncio.wait_for(request.app.state.call(tool, args), timeout=6)
        except Exception:
            return speak("暂时无法取得电视的回复。" if tool == "tv_status" else "未能确认电视是否收到了这次点歌。")
        data = response.structuredContent or {}
        if tool == "tv_status" and not response.isError and data.get("connected") is True:
            volume, maximum = data.get("volume"), data.get("maximum")
            if type(volume) is int and type(maximum) is int and 0 <= volume <= maximum and maximum > 0:
                return speak("电视当前音量是%d，最大是%d。" % (volume, maximum))
        if tool == "xingyi_submit" and not response.isError and data.get("state") == "accepted":
            return speak("星仪已收到点歌请求，请核对电视上的操作预览并确认。")
        return speak("暂时无法读取电视音量，请检查家庭电脑与电视的连接。" if tool == "tv_status"
                     else "没有取得点歌回执，请先检查电视上的星仪。")

    return Starlette(routes=[Route(ENDPOINT, callback, methods=["POST"])], lifespan=mcp_connection)


if __name__ == "__main__":
    cli = argparse.ArgumentParser()
    cli.add_argument("--config", type=Path, required=True)
    cli.add_argument("--port", type=int, default=8765)
    args = cli.parse_args()
    try:
        config = Settings.load(args.config)
    except (OSError, ValueError, KeyError, binascii.Error):
        cli.error("无法读取技能配置，请检查配置文件中的平台参数。")
    uvicorn.run(application(config), host="127.0.0.1", port=args.port, access_log=False,
                proxy_headers=False, timeout_keep_alive=4, limit_concurrency=16)
