"""Only a harmless JVM backend is used; no actual TV or household query is read."""
import asyncio
import os
from pathlib import Path
import subprocess
import tempfile

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client
from mcp.shared.exceptions import McpError

root=Path(__file__).resolve().parents[1]
src=root/'src/xyz/erapi0neer/xingyi'
jar=Path(os.environ.get('ANDROID_JSON_JAR',str(Path(tempfile.gettempdir())/'xingyi-android-json-0.0.20131108.vaadin1.jar')))

async def sdk(endpoint,token,nonce,request_id):
    async with httpx.AsyncClient(headers={'Authorization':'Bearer '+token},trust_env=False,timeout=3) as http:
        async with streamable_http_client(endpoint,http_client=http) as (reader,writer,_), ClientSession(reader,writer) as client:
            await client.initialize()
            assert [t.name for t in (await client.list_tools()).tools]==['xingyi_submit']
            args={'request_id':request_id,'text':'只读合成检查：A+B','nonce':nonce}
            good=await client.call_tool('xingyi_submit',args)
            assert good.structuredContent=={'accepted':True,'request_id':request_id} and not good.isError
            assert (await client.call_tool('xingyi_submit',args)).isError
            try: await client.call_tool('tv_status',{})
            except McpError as e: assert e.error.code == -32602
            else: raise AssertionError('privately scoped endpoint exposed other tools')

if __name__=='__main__':
    with tempfile.TemporaryDirectory(prefix='xingyi-auto-mcp-') as folder:
        sources=[src/(name+'.java') for name in ('SharedTextDraft','TvMcpHttp','HandoffBinding','HandoffMcpClient')]
        subprocess.run(['javac','-encoding','UTF-8','-cp',str(jar),'-d',folder,*map(str,sources),str(root/'tests/HandoffMcpClientTest.java')],check=True)
        java=['java','-cp',folder+os.pathsep+str(jar),'xyz.erapi0neer.xingyi.HandoffMcpClientTest']
        subprocess.run(java,check=True,timeout=12)
        with subprocess.Popen(java+['serve'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True) as server:
            try: asyncio.run(sdk(*server.stdout.readline().strip().split()))
            finally: server.communicate('\n',timeout=5)
        print('PASS independent official MCP SDK discovered submit-only capability, validated the receipt and declined replay.')
