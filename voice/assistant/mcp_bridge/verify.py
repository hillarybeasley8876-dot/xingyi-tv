"""Run a real desktop MCP client; simulate TV state only in the explicit contract fixture."""
import asyncio
import json
import sys
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

ROOT = Path(__file__).resolve().parent
RID = "3" * 32
FIXTURE = """
import asyncio, server
from collections import namedtuple
seen = []
def submit(text):
    assert text == '播放指定歌曲'
    return '3' * 32
def review(token, op):
    assert token == '3' * 32 and op == 'status'
    seen.append(op)
    return namedtuple('R', 'state text')('pending', '在电视上确认播放')
server.remote.update(send_transcript=submit, review_request=review)
asyncio.run(server.main())
"""


async def check(fixture):
    args = ["-c", FIXTURE] if fixture else [str(ROOT / "server.py")]
    params = StdioServerParameters(command=sys.executable, args=args, cwd=ROOT,
                                   env={"PYTHONUTF8": "1"})
    async with stdio_client(params) as (reader, writer):
        async with ClientSession(reader, writer) as client:
            init = await client.initialize()
            tools = await client.list_tools()
            names = [tool.name for tool in tools.tools]
            assert init.serverInfo.name == "xingyi-tv" and names == ["tv_status", "xingyi_submit", "xingyi_result"]
            report = {"protocol": init.protocolVersion, "server": init.serverInfo.name, "tools": names}
            if not fixture:
                state = await client.call_tool("tv_status", {})
                assert state.structuredContent and (
                    (state.isError and "message" in state.structuredContent)
                    or (not state.isError and state.structuredContent["connected"] is True)
                )
                report.update(status=state.structuredContent, isError=state.isError)
                return report
            submit = await client.call_tool("xingyi_submit", {"text": "播放指定歌曲"})
            assert submit.structuredContent == {"state": "accepted", "request_id": RID}
            pending = await client.call_tool("xingyi_result", {"request_id": RID})
            assert pending.structuredContent["state"] == "pending"
            changed = await client.call_tool("xingyi_result", {"request_id": RID, "op": "confirm"})
            foreign = await client.call_tool("xingyi_result", {"request_id": "9" * 32})
            assert changed.isError and foreign.isError
            return {"submitted": True, "preview_seen": True,
                    "approval_argument_rejected": True, "foreign_receipt_rejected": True}


async def main():
    report = {"real_paired_device": await check(False), "synthetic_tv_fixture": await check(True)}
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
