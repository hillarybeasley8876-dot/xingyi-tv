"""Exercise the Android server class with the official MCP SDK against host JVM fixture readings.

Run with the Python in ../mcp_bridge/.venv. --expiry additionally waits for absolute closure.
"""
import argparse
import asyncio
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

BASE = Path(__file__).resolve().parents[1]
FIXTURE = """
import org.json.JSONObject;
import xyz.erapi0neer.xingyi.TvMcpHttp;

public final class McpReadFixture {
    public static void main(String[] args) throws Exception {
        try (TvMcpHttp http = new TvMcpHttp(name ->
                "tv_status".equals(name) ? new JSONObject().put("volume", 18).put("muted", false)
                                         : new JSONObject().put("active", false))) {
            System.out.println(http.url() + " " + http.bearer());
            System.out.flush();
            System.in.read();
        }
    }
}
"""


async def verify(url, token, expiry):
    # Even an unauthenticated client sending a blank HTTP head must not terminate the listener.
    port = httpx.URL(url).port
    raw, writer = await asyncio.open_connection("127.0.0.1", port)
    writer.write(b"\r\n\r\n")
    await writer.drain()
    assert (await raw.readline()).startswith(b"HTTP/1.1 400 ")
    writer.close()
    await writer.wait_closed()
    headers = {"Authorization": "Bearer " + token}
    async with httpx.AsyncClient(headers=headers, trust_env=False, timeout=3) as http:
        async with streamable_http_client(url, http_client=http) as (r, w, _):
            async with ClientSession(r, w) as session:
                init = await session.initialize()
                assert init.protocolVersion == "2025-11-25"
                assert init.serverInfo.name == "xingyi-tv-local"
                await session.send_ping()
                listed = await session.list_tools()
                assert {t.name for t in listed.tools} == {"tv_status", "sleep_timer"}
                status = await session.call_tool("tv_status", {})
                timer = await session.call_tool("sleep_timer", {})
                assert status.structuredContent == {"volume":18,"muted":False} and not status.isError
                assert timer.structuredContent == {"active":False} and not timer.isError
        get = await http.get(url)
        assert get.status_code == 405 and get.headers["Allow"] == "POST"
        req = {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"tv_status","arguments":{}}}
        for h, expected in [({"Origin":"https://foreign.example"},403),
                            ({"Origin":"null"},403),({"Host":"unrelated.local"},403),
                            ({"Authorization":"Bearer incorrect"},401),
                            ({"MCP-Protocol-Version":"0000-00-00"},400)]:
            assert (await http.post(url, json=req, headers=h)).status_code == expected
        note = {"jsonrpc":"2.0","method":"notifications/initialized"}
        ack = await http.post(url,json=note)
        assert ack.status_code == 202 and ack.content == b""
        assert (await http.post(url,json=[])).json()["error"]["code"] == -32600
        assert (await http.post(url,content=b"{",headers={"Content-Type":"application/json"})).json()["error"]["code"] == -32700
        deep = b"["*900 + b"0" + b"]"*900
        assert (await http.post(url,content=deep,headers={"Content-Type":"application/json"})).json()["error"]["code"] == -32700
        for data in ({"name":"unknown"}, {"name":"tv_status","arguments":{"volume":50}}):
            req["params"] = data
            assert (await http.post(url,json=req)).json()["error"]["code"] == -32602
        assert (await http.post(url,content=b"a"*9000,headers={"Content-Type":"application/json"})).status_code == 413
    async with httpx.AsyncClient(trust_env=False,timeout=3) as plain:
        assert (await plain.post(url,json=note)).status_code == 401
        if expiry:
            await asyncio.sleep(45.1)
            try:
                await plain.post(url,json=note,headers=headers)
            except httpx.ConnectError:
                pass
            else:
                raise AssertionError("MCP server still accessible after 45 seconds")
    print(json.dumps({"transport":"Streamable HTTP", "protocol":init.protocolVersion,
                      "backend":"host JVM fixture; no physical TV",
                      "tools":["tv_status","sleep_timer"],"authorization_and_origin_checks":True,
                      "invalid_calls_rejected":True,"expiry_verified":expiry}))


def main():
    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    parser = argparse.ArgumentParser()
    parser.add_argument("--expiry",action="store_true")
    expiry = parser.parse_args().expiry
    jar = BASE / "tests/lib/json-20240303.jar"
    source = BASE / "src/xyz/erapi0neer/xingyi/TvMcpHttp.java"
    with tempfile.TemporaryDirectory() as folder:
        mainfile = Path(folder) / "McpReadFixture.java"
        mainfile.write_text(FIXTURE,encoding="utf8")
        subprocess.run(["javac","--release","8","-encoding","UTF-8","-cp",str(jar),"-d",folder,
                        str(source),str(mainfile)],check=True)
        with subprocess.Popen(["java","-cp",str(jar)+os.pathsep+folder,"McpReadFixture"],
                              stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True) as worker:
            try:
                url,token = worker.stdout.readline().strip().split()
                asyncio.run(verify(url,token,expiry))
            finally:
                worker.stdin.close()


if __name__ == "__main__":
    main()
