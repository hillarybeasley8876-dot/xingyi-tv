"""Offline XiaoAI request fixtures. One test calls the real MCP stdio server."""
import asyncio
import base64
import hashlib
import json
import secrets
import sys
import tempfile
from email.utils import formatdate
from pathlib import Path

import httpx
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from gateway import ENDPOINT, MCP_ROOT, Settings, application, sign


async def verify():
    # Public, non-production test vectors from Xiaomi's signature appendix.
    sample_key = base64.b64decode("xxxxxxxxxxxxxxxxyyyyyyyyyyyyyyyy")
    assert sign(sample_key, "POST", "/hmac/testPost", "", "Mon, 7 Aug 2017 08:02:03 GMT",
                "localhost:19001", "application/x-www-form-urlencoded", "LvICVZWSXkBmN8URxZ1wlg==") == \
           "7af664ac15b675633bc13a3ab03adc2d0c6e3834999785698e5830ae215a8545"
    assert sign(sample_key, "GET", "/hmac/testGet", "id=xxxxx&name=abc", "Mon, 7 Aug 2017 08:00:50 GMT",
                "localhost:19001", "", "") == "65d85343ab96f67b4f17cb5754c9a44dea32e89b3a8b01fb9a9eac714212ebee"
    with tempfile.TemporaryDirectory() as folder:
        settings = Settings("skill.example.invalid", "dummy-key", secrets.token_bytes(32), "test-app",
                            "test-owner", Path(folder) / "ids.db")
        app = application(settings)

        def event(intent="query_volume", slots=None):
            return {"version": "1.0",
                    "session": {"application": {"app_id": "test-app"}, "user": {"user_id": "test-owner"}},
                    "request": {"type": 0, "request_id": secrets.token_hex(12),
                                "slot_info": {"intent_name": intent, "slots": slots or []}}}

        def pack(value, date=None):
            data = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()
            md5 = base64.b64encode(hashlib.md5(data).digest()).decode()
            stamp = date or formatdate(usegmt=True)
            digest = sign(settings.secret, "POST", ENDPOINT, "", stamp, settings.host, "application/json", md5)
            headers = {"Host": settings.host, "X-Xiaomi-Date": stamp, "Content-Type": "application/json",
                       "Content-MD5": md5, "Authorization": "MIAI-HmacSHA256-V1 dummy-key::" + digest}
            return data, headers

        async with app.router.lifespan_context(app), httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url="http://127.0.0.1") as client:
            async def post(packet):
                body, headers = packet
                return await client.post(ENDPOINT, headers=headers, content=body)

            packet = pack(event())
            answer = await post(packet)
            assert answer.status_code == 200 and answer.json()["version"] == "1.0"
            text = answer.json()["response"]["to_speak"]["text"]
            assert "音量" in text and ("最大" in text or "无法读取" in text), text
            assert (await post(packet)).status_code == 409
            data, headers = pack(event())
            assert (await post((data + b" ", headers))).status_code == 401
            h = dict(headers)
            h.pop("Content-MD5")
            assert (await post((data, h))).status_code == 401
            assert (await post(pack(event(), date="Mon, 7 Aug 2017 08:02:03 GMT"))).status_code == 401
            other = event()
            other["session"]["user"]["user_id"] = "other"
            assert (await post(pack(other))).status_code == 403
            other = event()
            other["session"]["application"]["app_id"] = "other"
            assert (await post(pack(other))).status_code == 403

            # Use the production server dispatcher in a separate process; replace only its TV boundary.
            expected = "打开网易云音乐，播放歌手「林俊杰」的歌曲《不为谁而作的歌》。"
            code = ("import asyncio, server\n"
                    "def receive(text):\n"
                    " assert text == " + ascii(expected) + "\n"
                    " return 'a'*32\n"
                    "server.remote['send_transcript']=receive\n"
                    "asyncio.run(server.main())")
            params = StdioServerParameters(command=sys.executable, args=["-c", code], cwd=MCP_ROOT,
                                           env={"PYTHONUTF8": "1"})
            async with stdio_client(params) as (reader, writer), ClientSession(reader, writer) as mock_tv:
                await mock_tv.initialize()
                calls = []
                async def observed(tool, args):
                    calls.append(tool)
                    return await mock_tv.call_tool(tool, args)
                app.state.call = observed
                slots = [{"name": "artist", "value": "林俊杰"}, {"name": "title", "value": "不为谁而作的歌"}]
                assert (await post(pack(event("play_song", slots[:1])))).json()["response"]["open_mic"]
                assert not calls
                music = pack(event("play_song", slots))
                answer = await post(music)
                assert "确认" in answer.json()["response"]["to_speak"]["text"]
                assert calls == ["xingyi_submit"]
                assert (await post(music)).status_code == 409 and len(calls) == 1
                unknown = (await post(pack(event("unknown")))).json()
                assert unknown["is_session_end"] and not unknown["response"]["open_mic"]
                assert unknown["response"]["not_understand"]
                silence = event("play_song", slots)
                silence["request"]["no_response"] = True
                assert (await post(pack(silence))).json()["is_session_end"]
                assert len(calls) == 1
    return {"origin": "合成小爱技能请求，未连接小米账号或采集语音",
            "official_signature_vectors": "GET 与 POST 均匹配",
            "volume": {"transport": "official MCP SDK over real stdio", "spoken_response": text},
            "song": "签名模拟请求→真正的 stdio MCP xingyi_submit。只模拟电视受理边界，验证歌手、曲名、待确认回复和防重复提交。",
            "routing": "新意图以外的指令和静默均结束技能会话，未调用 MCP；成功和待确认回复均结束技能会话。",
            "rejection_checks": ["错误摘要", "缺少摘要", "过期签名", "其他账号", "其他技能", "重放"]}


if __name__ == "__main__":
    print(json.dumps(asyncio.run(verify()), ensure_ascii=False, indent=2))
