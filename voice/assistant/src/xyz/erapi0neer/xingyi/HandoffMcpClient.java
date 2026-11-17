package xyz.erapi0neer.xingyi;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.json.*;

/** JSON-only branch of Streamable HTTP over an authenticated, fixed loopback socket. No reconnect/retry. */
final class HandoffMcpClient {
    private final int port;
    private final String bearer;
    private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
    private int sequence;

    HandoffMcpClient(String url,String bearer) throws IOException {
        try {
            URI u=new URI(url);
            int p=u.getPort();
            if (p<1024 || p>65535 || !("http://127.0.0.1:"+p+"/mcp").equals(url) || !HandoffBinding.hex64(bearer))
                throw new IOException("接力端点无效");
            this.port=p;
            this.bearer=bearer;
        } catch (URISyntaxException | NullPointerException e) { throw new IOException("接力端点无效"); }
    }
    boolean submit(String id,String text,String nonce) throws IOException {
        try {
            JSONObject init=call("initialize",new JSONObject().put("protocolVersion",TvMcpHttp.VERSION)
                    .put("capabilities",new JSONObject()).put("clientInfo",new JSONObject().put("name","xingyi-autorelay").put("version","1")));
            if (!TvMcpHttp.VERSION.equals(init.getString("protocolVersion")) ||
                    !"xingyi-tv-local".equals(init.getJSONObject("serverInfo").getString("name")) ||
                    init.getJSONObject("capabilities").optJSONObject("tools")==null) throw new IOException("接力握手不匹配");
            post(new JSONObject().put("jsonrpc","2.0").put("method","notifications/initialized"),true);
            JSONArray tools=call("tools/list",new JSONObject()).getJSONArray("tools");
            if (tools.length()!=1 || !"xingyi_submit".equals(tools.getJSONObject(0).getString("name"))) throw new IOException("接力工具不可用");
            JSONObject result=call("tools/call",new JSONObject().put("name","xingyi_submit").put("arguments",
                    new JSONObject().put("request_id",id).put("text",text).put("nonce",nonce)));
            JSONObject data=result.optJSONObject("structuredContent");
            return Boolean.FALSE.equals(result.opt("isError")) && data!=null && Boolean.TRUE.equals(data.opt("accepted"))
                    && id.equals(data.opt("request_id"));
        } catch(JSONException e){ throw new IOException("MCP 回复格式无效"); }
    }
    private JSONObject call(String method,JSONObject args) throws IOException,JSONException {
        int id=++sequence;
        JSONObject r=post(new JSONObject().put("jsonrpc","2.0").put("id",id).put("method",method).put("params",args),false);
        if (!"2.0".equals(r.opt("jsonrpc")) || !Integer.valueOf(id).equals(r.opt("id")) ||
                r.has("error") || r.optJSONObject("result")==null) throw new IOException("MCP 没有返回对应结果");
        return r.getJSONObject("result");
    }
    private int remaining() throws IOException {
        long ms=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
        if (ms<=0) throw new IOException("接力会话超时");
        return (int)Math.min(2500,ms);
    }
    private JSONObject post(JSONObject message,boolean notification) throws IOException,JSONException {
        byte[] body=message.toString().getBytes(StandardCharsets.UTF_8);
        try (Socket s=new Socket(Proxy.NO_PROXY)) {
            s.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),port),remaining());
            String head="POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nAuthorization: Bearer "+bearer+
                "\r\nAccept: application/json, text/event-stream\r\nContent-Type: application/json\r\nConnection: close\r\n"+
                ("initialize".equals(message.opt("method")) ? "" : "MCP-Protocol-Version: "+TvMcpHttp.VERSION+"\r\n")+
                "Content-Length: "+body.length+"\r\n\r\n";
            OutputStream out=s.getOutputStream(); out.write(head.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
            InputStream in=new BufferedInputStream(s.getInputStream());
            ByteArrayOutputStream headers=new ByteArrayOutputStream();
            int tail=0;
            while(tail!=0x0d0a0d0a) {
                s.setSoTimeout(remaining());
                int b=in.read();
                if (b<0 || headers.size()>=4096) throw new IOException("接力响应头无效");
                headers.write(b); tail=(tail<<8)|b;
            }
            String[] lines=new String(headers.toByteArray(),StandardCharsets.US_ASCII).split("\r\n");
            int length=-1;
            boolean json=false;
            for(int i=1;i<lines.length;i++) {
                String line=lines[i].toLowerCase(Locale.ROOT);
                if (line.startsWith("content-length:")) {
                    String value=line.substring(15).trim();
                    if (length!=-1 || !value.matches("[0-9]{1,5}")) throw new IOException("响应长度无效");
                    length=Integer.parseInt(value);
                } else if (line.startsWith("content-type:")) json=line.substring(13).trim().equals("application/json");
            }
            if (length<0 || length>16384) throw new IOException("响应过大或没有确定长度");
            if (notification) {
                if (!lines[0].startsWith("HTTP/1.1 202 ") || length!=0) throw new IOException("MCP 未接受握手通知");
                return null;
            }
            if (!lines[0].startsWith("HTTP/1.1 200 ") || !json) throw new IOException("MCP HTTP 请求未成功");
            byte[] bytes=new byte[length];
            for(int offset=0;offset<length;) {
                s.setSoTimeout(remaining());
                int n=in.read(bytes,offset,length-offset);
                if (n<0) throw new IOException("响应不完整");
                offset+=n;
            }
            return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        }
    }
}
