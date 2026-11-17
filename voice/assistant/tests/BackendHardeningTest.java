package xyz.erapi0neer.xingyi;

import org.json.*;
import java.net.*;
import java.io.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/** Host-only regression test; injects a fake HTTPS handler, never contacts the network or TV. */
public class BackendHardeningTest {
    static int checks;
    static volatile String mode;
    static CountDownLatch reading;
    static final String TEST_KEY="FAKE-TEST-KEY-DO-NOT-LEAK";
    static void check(boolean yes,String what){checks++;if(!yes)throw new AssertionError(what);}
    static void reject(String json)throws Exception {
        boolean denied=false;try{Commands.validate(new JSONArray(json));}catch(IllegalArgumentException|JSONException expected){denied=true;}
        check(denied,"accepted invalid tool: "+json);
    }
    static GatewayClient client(int ms)throws Exception{return new GatewayClient(new GatewayConfig("https://gateway.invalid",TEST_KEY,"fake"),ms);}
    static class FakeHttps extends HttpsURLConnection {
        final CountDownLatch stopped=new CountDownLatch(1);
        FakeHttps(URL url){super(url);}
        public void connect(){} public void disconnect(){stopped.countDown();}
        public boolean usingProxy(){return false;} public String getCipherSuite(){return "test";}
        public Certificate[] getLocalCertificates(){return null;} public Certificate[] getServerCertificates(){return null;}
        public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        public int getResponseCode()throws IOException{if("transport".equals(mode))throw new IOException("internal credential "+TEST_KEY);return 200;}
        public InputStream getInputStream() {
            final String thisMode=mode;
            return new InputStream(){public int read(){throw new AssertionError();}public int read(byte[] b,int off,int len)throws IOException{
                reading.countDown();
                if(thisMode.equals("trickle")){try{Thread.sleep(12);}catch(InterruptedException e){Thread.currentThread().interrupt();}b[off]='x';return 1;}
                try{if(!stopped.await(3,TimeUnit.SECONDS))throw new IOException("fake read not cancelled");}
                catch(InterruptedException e){Thread.currentThread().interrupt();}
                throw new IOException("private connection details "+TEST_KEY);
            }};
        }
    }
    public static void main(String[] argv)throws Exception {
        URL.setURLStreamHandlerFactory(protocol->!protocol.equals("https")?null:new URLStreamHandler(){protected URLConnection openConnection(URL u){return new FakeHttps(u);}});
        check(Commands.parse("请把媒体音量调成百分之十二").getJSONObject(0).getJSONObject("args").getInt("level")==12,"spoken percentage");
        check(Commands.parse("声音调到一百").getJSONObject(0).getJSONObject("args").getInt("level")==100,"hundred");
        check(Commands.parse("声音调到九十九").getJSONObject(0).getJSONObject("args").getInt("level")==99,"tens+ones");
        check(Commands.parse("音量调到二十是什么意思？").length()==0,"question must remain a question");
        check(Commands.parse("声音调到三十二，关机").length()==0,"mixed clauses must not partially execute");
        reject("[{tool:'open_app',args:{name:null}}]");reject("[{tool:'say',args:{text:123}}]");
        reject("[{tool:'home',args:{},unknown:'ignored?'}]");reject("[{tool:'set_volume',args:{level:'12'}}]");
        reject("[{tool:'search_video',args:{app:'x',query:['camera']}}]");reject("[{tool:'home',args:{x:1}}]");
        Map<String,String> applications=new HashMap<>();applications.put("com.a","Player");applications.put("com.b","Player");applications.put("com.c","Player [com.a]");
        Map<String,String> labels=TvControl.appLabels(applications);
        check(labels.size()==3&&!labels.containsKey("Player"),"ambiguous bare name");
        check("com.a".equals(labels.get("Player [com.a]"))&&"com.b".equals(labels.get("Player [com.b]")),"stable targets");
        check("com.c".equals(labels.get("Player 〔com.a〕")),"spoofed package suffix");
        Map<String,String> installed=new HashMap<>();
        installed.put("云视听极光","com.ktcp.video");installed.put("哔哩哔哩","com.xiaodianshi.tv.yst");
        check(TvControl.resolveApp("B站",installed,pkg->pkg)==null,"cloud variants cannot satisfy Bilibili aliases");
        check(TvControl.resolveApp("哔哩哔哩",installed,pkg->pkg)==null,"wrong package with exact Bilibili label");
        installed.put("哔哩哔哩动画","tv.danmaku.bili");
        check("tv.danmaku.bili".equals(TvControl.resolveApp("BiLiBiLi",installed,pkg->pkg)),"resolve exact official client");
        check("com.ktcp.video".equals(TvControl.resolveApp("云视听极光",installed,pkg->pkg)),"keep separate cloud launcher");
        mode="transport";
        try{client(5000).ask("hello");throw new AssertionError("transport failure accepted");}catch(IOException e){check(!e.getMessage().contains(TEST_KEY),"transport leaked key");}
        mode="trickle";reading=new CountDownLatch(1);long start=System.nanoTime();
        try{client(200).ask("hello");throw new AssertionError("trickle ignored deadline");}catch(IOException e){check(e.getMessage().contains("超时"),"wrong deadline status: "+e);}
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1500,"deadline returned too late");
        mode="block";reading=new CountDownLatch(1);GatewayClient request=client(5000);
        FutureTask<String> response=new FutureTask<>(()->{try{request.ask("hello");return "success";}catch(IOException e){return e.getMessage();}});new Thread(response).start();
        check(reading.await(2,TimeUnit.SECONDS),"never reached blocked read");request.cancel();
        check(response.get(1,TimeUnit.SECONDS).equals("请求已取消"),"blocked read was not cancelled");
        try{new GatewayConfig("https://gateway.invalid",TEST_KEY+"\u0007","fake");throw new AssertionError("control character accepted");}catch(IOException e){check(!e.getMessage().contains(TEST_KEY),"invalid header leaked key");}
        System.out.println("PASS "+checks+" offline backend regressions");
    }
}
