package xyz.erapi0neer.xingyi;

import org.json.JSONObject;

/** The callback counts acceptances without opening an Activity or performing any device action. */
public final class HandoffMcpClientTest {
    public static void main(String[] argv) throws Exception {
        String text="只读合成检查：A+B";
        String id=HandoffBinding.digest("synthetic-dialog");
        HandoffBinding b=new HandoffBinding(id,HandoffBinding.digest(text));
        int[] accepts={0};
        try (TvMcpHttp server=TvMcpHttp.handoff((key,original,nonce)->{
            boolean ok=b.claim(key,original,nonce);
            if(ok) accepts[0]++;
            return new JSONObject().put("accepted",ok).put("request_id",key);
        })) {
            if(argv.length>0) {
                System.out.println(server.url()+" "+server.bearer()+" "+b.nonce+" "+id);
                System.out.flush();
                System.in.read();
                return;
            }
            if(new HandoffMcpClient(server.url(),server.bearer()).submit(id,text+"x",b.nonce))throw new AssertionError("substitution");
            if(new HandoffMcpClient(server.url(),server.bearer()).submit(id,text,HandoffBinding.random()))throw new AssertionError("nonce");
            if(new HandoffMcpClient(server.url(),server.bearer()).submit(HandoffBinding.digest("another"),text,b.nonce))throw new AssertionError("wrong dialog");
            if(!new HandoffMcpClient(server.url(),server.bearer()).submit(id,text,b.nonce))throw new AssertionError("declined");
            if(new HandoffMcpClient(server.url(),server.bearer()).submit(id,text,b.nonce))throw new AssertionError("replay");
            if(accepts[0]!=1)throw new AssertionError("incorrect count");
            try { new HandoffMcpClient("http://example.com:1234/mcp",server.bearer()); throw new AssertionError("off-device"); }
            catch(java.io.IOException expected) {}
        }
        try(TvMcpHttp readOnly=new TvMcpHttp(name->new JSONObject().put("ok",true))){
            try {new HandoffMcpClient(readOnly.url(),readOnly.bearer()).submit(id,text,b.nonce);throw new AssertionError("read-only endpoint accepted");}
            catch(java.io.IOException expected){}
        }
        System.out.println("PASS real loopback MCP initialize/list/submit: one bound payload, wrong nonce/query/dialog and replay rejected; read-only endpoint isolated.");
    }
}
