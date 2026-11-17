"""One reviewed desktop action over real stdio MCP. No retry or remote approval operations."""
import asyncio
import json
import os
import re
import sys
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


def server_parameters():
    root = Path(__file__).resolve().parent
    env = {"PYTHONUTF8": "1"}
    if os.environ.get("XINGYI_TV"):
        env["XINGYI_TV"] = os.environ["XINGYI_TV"]
    return StdioServerParameters(command=sys.executable, args=[str(root / "server.py")], cwd=root, env=env)


async def request(data, params=None):
    if not isinstance(data, dict):
        raise ValueError("无效请求")
    if data.get("op") == "submit" and set(data) == {"op", "text"}:
        text = data["text"]
        if not isinstance(text, str) or not text.strip() or len(text.encode("utf-16-le")) // 2 > 320:
            raise ValueError("无效文字")
        tool, values = "xingyi_submit", {"text": text}
    elif data == {"op": "status"}:
        tool, values = "tv_status", {}
    else:
        raise ValueError("未知操作")
    async with stdio_client(params or server_parameters()) as streams:
        async with ClientSession(*streams) as client:
            handshake = await client.initialize()
            if handshake.serverInfo.name != "xingyi-tv":
                raise RuntimeError("未连接到星仪电视 MCP 服务")
            names = [t.name for t in (await client.list_tools()).tools]
            if tool not in names:
                raise RuntimeError("星仪 MCP 未提供此工具")
            reply = await client.call_tool(tool, values)
            fields = reply.structuredContent
            if reply.isError or not isinstance(fields, dict):
                raise RuntimeError("电视未返回已确认的工具结果")
            if tool == "xingyi_submit" and (fields.get("state") != "accepted"
                    or not re.fullmatch("[0-9a-f]{32}", str(fields.get("request_id","")))):
                raise RuntimeError("星仪 MCP 没有返回有效回执")
            return fields


if __name__ == "__main__":
    try:
        payload = sys.stdin.buffer.read(8193)
        if not 0 < len(payload) <= 8192:
            raise ValueError("无效输入")
        result = asyncio.run(request(json.loads(payload.decode("utf-8"))))
    except Exception:
        print('{"state":"unknown"}')
        raise SystemExit(1)
    print(json.dumps(result, ensure_ascii=True))
