"""Drive the xingyi-tv MCP server through the official MCP client over real stdio.

.venv/Scripts/python.exe check_v2.py          handshake, list tools, tv_state, tv_screen (read only)
.venv/Scripts/python.exe check_v2.py "现在音量是多少"   also runs one tv_do request
"""
import asyncio
import json
import sys
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


async def main(request):
    root = Path(__file__).resolve().parent
    params = StdioServerParameters(command=sys.executable, args=[str(root / "server.py")], cwd=root, env={"PYTHONUTF8": "1"})
    async with stdio_client(params) as streams:
        async with ClientSession(*streams) as client:
            hello = await client.initialize()
            print("server:", hello.serverInfo.name, hello.serverInfo.version)
            print("tools:", [t.name for t in (await client.list_tools()).tools])
            for name in ("tv_status", "tv_state", "tv_screen"):
                r = await client.call_tool(name, {})
                text = r.content[0].text
                print(f"{name}: error={r.isError} {text[:400]}")
            if request:
                r = await client.call_tool("tv_do", {"request": request, "wait_seconds": 60})
                print("tv_do:", r.isError, json.dumps(json.loads(r.content[0].text), ensure_ascii=False, indent=1))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else None))
