"""Drive a confirmed Xiaomi refusal through the real reader and AgentHarness with a scripted model."""
import os
import subprocess
import tempfile
from pathlib import Path

from test_dialog_reader import ROOT, STUBS, TEST


SCENARIO = '''package xyz.erapi0neer.xingyi;
import android.content.*;
import org.json.*;

public final class ConfirmedHarness {
    static void check(boolean value) { if(!value) throw new AssertionError(); }
    static void musicCase(String original,String artist) throws Exception {
        ReaderBoundary.Source source=new ReaderBoundary.Source();
        Context owner=new Context(){public ContentResolver getContentResolver(){return source;}};
        XiaomiDialogReader reader=new XiaomiDialogReader(owner,"123");
        reader.arm(); Thread.sleep(15);
        String id="000000000000000000000000000000c1";
        source.id=id; source.time=System.currentTimeMillis(); source.category=null;
        source.text=original;
        source.reply="抱歉，当前暂不支持使用网易云音乐源播放，将继续使用当前音乐源为您播放";
        XiaomiDialogReader.Event event=reader.claimConfirmedRefusal(id);
        check(event!=null && original.equals(event.original));
        GenericRefusalText.Scope scope=GenericRefusalText.scope(event.original);
        String tool=artist==null?"play_netease_title":"play_netease_music";
        int[] calls={0};
        java.util.List<String> stages=new java.util.ArrayList<>();
        AgentHarness.Journal journal=new AgentHarness.Journal() {
            public String event(String e,String t,String s,JSONObject a){stages.add(e);return null;}
            public String trace(){return "";}
        };
        AgentHarness h=new AgentHarness(null,(name,params)->{
            calls[0]++;
            check(tool.equals(name) && "空心".equals(params.getString("title")));
            check(artist==null ? params.length()==1 && !params.has("artist") :
                params.length()==2 && artist.equals(params.getString("artist")));
            check(scope.allows(name,params) && !scope.allows("set_volume",new JSONObject().put("level",10)));
            return new JSONObject().put("ok",true).put("status","observed");
        },journal,null,()->0L);
        check("observed".equals(h.startHandedOffMusic(scope).getString("status")) && calls[0]==1);
        check(stages.containsAll(java.util.Arrays.asList("turn_start","before_tool","tool_start","after_tool","turn_end")));
        check(!stages.contains("confirmation_granted") && reader.claimConfirmedRefusal(id)==null);
        try {h.startHandedOffMusic(scope);throw new AssertionError();}catch(IllegalStateException expected){}
        check(calls[0]==1);
    }
    static void installCase() throws Exception {
        GenericRefusalText.Scope wanted=GenericRefusalText.scope("请安装 Bilibili，不要云电视");
        check(wanted!=null && wanted.kind==GenericRefusalText.Kind.INSTALL);
        int[] calls={0};
        AgentHarness.Runtime runtime=(tool,args)->{
            check("install_app".equals(tool) && args.length()==1 && "tv.danmaku.bili".equals(args.getString("package")));
            calls[0]++; return new JSONObject().put("ok",true).put("message","已核对安装目标");
        };
        AgentHarness.Journal journal=new AgentHarness.Journal(){
            public String event(String e,String t,String s,JSONObject a){return null;}
            public String trace(){return "";}
        };
        AgentHarness h=new AgentHarness(null,runtime,journal,null,()->0L);
        AgentHarness.Step preview=h.startInstall(wanted);
        check(preview.needsConfirmation && preview.preview.equals("1. 安装哔哩哔哩官方 Android 客户端（tv.danmaku.bili）") && calls[0]==0);
        h.cancel();
        try { h.confirm(); throw new AssertionError("cancelled install executed"); } catch(java.io.IOException expected) {}
        check(calls[0]==0);
        for (String other : new String[]{"打开B站","安装云视听极光","安装云视听小电视","别安装B站"}) {
            h=new AgentHarness(null,runtime,journal,null,()->0L);
            try { h.startInstall(GenericRefusalText.scope(other)); throw new AssertionError("unbound install accepted"); }
            catch(IllegalStateException expected){}
        }
        try { ToolRegistry.validate("install_app",new JSONObject().put("package","com.ktcp.video"));throw new AssertionError("wrong package accepted"); }
        catch(IllegalArgumentException expected){}
        h=new AgentHarness(null,runtime,journal,null,()->0L);
        check(h.startInstall(wanted).needsConfirmation);
        check(!h.confirm().needsConfirmation && calls[0]==1);
        try { h.confirm(); throw new AssertionError("confirmed install replay"); } catch(IllegalStateException expected){}
        check(calls[0]==1);
    }
    static void searchCase(boolean review) throws Exception {
        String original="打开b站然后搜索星辰亻旅行者", keyword="星辰亻旅行者";
        ReaderBoundary.Source source=new ReaderBoundary.Source();
        XiaomiDialogReader reader=new XiaomiDialogReader(new Context(){public ContentResolver getContentResolver(){return source;}},"123");
        reader.arm();Thread.sleep(15);
        source.id="b0960000000000000000000000000001";source.time=System.currentTimeMillis();source.category=null;
        source.text=original;source.reply="对不起，我还在学习。";
        XiaomiDialogReader.Event event=reader.claimConfirmedRefusal(source.id);
        check(event!=null && original.equals(event.original));
        GenericRefusalText.Scope scope=GenericRefusalText.scope(event.original);
        int[] writes={0};
        AgentHarness.Journal journal=new AgentHarness.Journal(){
            public String event(String e,String t,String s,JSONObject a){return null;}
            public String trace(){return "";}
        };
        AgentHarness h=new AgentHarness(null,(tool,args)->{
            check(tool.equals("search_video") && keyword.equals(args.getString("query")) && "tv.danmaku.bili".equals(args.getString("app")));
            writes[0]++;return new JSONObject().put("ok",true).put("message","已向哔哩哔哩发送搜索");
        },journal,null,()->0L);
        AgentHarness.Step step=h.startBiliSearch(scope,review);
        check(step.needsConfirmation==review && reader.claimConfirmedRefusal(source.id)==null);
        if (review) {check(writes[0]==0 && step.preview.contains(keyword));check(!h.confirm().needsConfirmation);}
        check(writes[0]==1);
        try { h.confirm();throw new AssertionError("search replay"); } catch(IllegalStateException expected){}
    }
    public static void main(String[] args) throws Exception {
        ReaderBoundary.Source source=new ReaderBoundary.Source();
        Context owner=new Context(){public ContentResolver getContentResolver(){return source;}};
        XiaomiDialogReader reader=new XiaomiDialogReader(owner,"123");
        reader.arm();
        Thread.sleep(10);
        String id="000000000000000000000000000000ba";
        source.id=id; source.time=System.currentTimeMillis(); source.category=null;
        source.text="请十五分钟后让电视休眠"; source.reply="抱歉，目前暂不支持该功能";
        check(GenericRefusalText.scope(source.text)==null); // goes through the generic harness branch
        XiaomiDialogReader.Event event=reader.claimConfirmedRefusal(id);
        check(event!=null && source.text.equals(event.original) && "confirmed_refusal".equals(event.cause));
        check(reader.claimConfirmedRefusal(id)==null);
        int[] writes={0}, modelCalls={0};
        AgentHarness.Model model=new AgentHarness.Model() {
            public JSONObject next(JSONArray msgs,JSONArray tools)throws Exception {
                if(modelCalls[0]++ == 0){
                    check(msgs.getJSONObject(1).getString("content").equals(source.text));
                    JSONObject fn=new JSONObject().put("name","set_sleep_timer")
                            .put("arguments",new JSONObject().put("minutes",15).toString());
                    JSONObject call=new JSONObject().put("id","timer1").put("type","function").put("function",fn);
                    return new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(call));
                }
                check("timer1".equals(msgs.getJSONObject(msgs.length()-1).optString("tool_call_id")));
                return new JSONObject().put("role","assistant").put("content","定时已设置");
            }
            public void cancel(){}
        };
        AgentHarness.Journal journal=new AgentHarness.Journal() {
            public String event(String e,String t,String s,JSONObject a){return null;}
            public String trace(){return "";}
        };
        AgentHarness harness=new AgentHarness(model,(tool,action)->{
            check("set_sleep_timer".equals(tool) && action.getInt("minutes")==15);
            writes[0]++; return new JSONObject().put("ok",true);
        },journal,null,()->0L);
        AgentHarness.Step preview=harness.start(event.original,null);
        check(preview.needsConfirmation && preview.preview.contains("15") && writes[0]==0);
        check(reader.poll()==null && reader.claimConfirmedRefusal(id)==null && writes[0]==0);
        PreviewContinuation binding=new PreviewContinuation();
        binding.visible(true,1);
        Thread.sleep(15);
        source.id="100000000000000000000000000000ba";source.text="继续执行";source.time=System.currentTimeMillis();
        check(reader.poll()==null && writes[0]==0);
        XiaomiDialogReader.Event spoken=reader.claimConfirmedRefusal(source.id);
        check(spoken!=null && XiaomiDialogReader.continuation(spoken.original) && reader.claimConfirmedRefusal(source.id)==null);
        // Use the same elapsed time base as the test reader's clock.
        long said=spoken.queryElapsedMs;
        int[] descriptions={0};
        binding.offer(()->{
            try { check(!harness.confirm().needsConfirmation); }
            catch(Exception e) { throw new AssertionError(e); }
        },()->descriptions[0]++,said-1,AgentHarness.CONFIRM_MS);
        PreviewContinuation.Bound live=binding.bind(said,said+5);
        check(live!=null && binding.decide(live,said,said+6,XiaomiDialogReader.explicitConfirmation(spoken.original))==PreviewContinuation.Status.WAITING);
        live.describe.run();
        check(writes[0]==0 && descriptions[0]==1 && binding.bind(said,said+7)==live);
        check(!XiaomiDialogReader.explicitConfirmation("不要确认执行") && !XiaomiDialogReader.explicitConfirmation("继续执行"));
        check(binding.decide(live,said,said+7,XiaomiDialogReader.explicitConfirmation("小爱同学，确认执行"))==PreviewContinuation.Status.CONFIRMED);
        live.confirm.run();
        check(binding.decide(null,said,said+8,false)==PreviewContinuation.Status.RUNNING && writes[0]==1 && modelCalls[0]==2);
        binding.reset(false); check(binding.decide(null,said,said+12,false)==PreviewContinuation.Status.NO_PREVIEW);
        binding.offer(()->{},()->{},said+20,100);
        check(binding.bind(said,said+30)==null); // spoken before replacement was displayed
        PreviewContinuation.Bound replacement=binding.bind(said+30,said+35);
        binding.offer(()->{},()->{},said+40,100);
        check(binding.decide(replacement,said+30,said+45,true)==PreviewContinuation.Status.STALE);
        check(binding.bind(said+50,said+141)==null);
        binding.offer(()->{},()->{},said+145,100);binding.visible(false,said+146);
        check(binding.bind(said+145,said+151)!=null);
        check(binding.bind(said+147,said+150)==null);
        binding.visible(true,said+153);check(binding.bind(said+147,said+160)==null);
        check(!XiaomiDialogReader.continuation("不要继续执行") && !XiaomiDialogReader.continuation("继续执行并关灯"));
        try { harness.confirm(); throw new AssertionError("preview replay"); } catch(IllegalStateException expected){}
        check(writes[0]==1);
        System.out.println("PASS generic native refusal -> one reader claim -> AgentHarness previews; only explicit confirmation writes once.");
        musicCase("网易云音乐播放空心",null);
        musicCase("播放空心",null);
        musicCase("网易云音乐播放刘端端的空心","刘端端");
        musicCase("播放黄霄云的空心","黄霄云");
        System.out.println("PASS four music speech variants enter exact scoped real harness tool, no model or second confirmation, once.");
        installCase();
        System.out.println("PASS Bilibili install exact package and scope, model-free preview, cancel, one confirm and no replay.");
        searchCase(true);searchCase(false);
        System.out.println("PASS native learning refusal → exact Bilibili query retains 亻, a single dispatch for both review and handoff.");
    }
}'''

def run_host_case(name, java_source, with_reader=False):
    android = Path(os.environ.get("ANDROID_PLATFORM_JAR", "H:/tools/android-sdk/android-35/android.jar"))
    runtime_json = Path(os.environ.get("ANDROID_JSON_JAR", Path(tempfile.gettempdir()) / "xingyi-android-json-0.0.20131108.vaadin1.jar"))
    with tempfile.TemporaryDirectory(prefix="xingyi-claim-harness-") as work:
        tmp = Path(work)
        classes = tmp / "classes"
        classes.mkdir()
        package = ROOT / "src/xyz/erapi0neer/xingyi"
        subprocess.run(["javac","-encoding","UTF-8","-cp",str(android),"-sourcepath",str(ROOT/"src"),"-d",str(classes),
                        str(package/"AgentHarness.java"),str(package/"XiaomiDialogReader.java"),str(package/"PreviewContinuation.java")],check=True)
        sources = []
        inputs = dict(STUBS)
        if with_reader:
            inputs["ReaderBoundary.java"] = TEST
        inputs[name + ".java"] = java_source
        for relative, code in inputs.items():
            path = tmp / relative
            path.parent.mkdir(exist_ok=True,parents=True)
            path.write_text(code,encoding="utf8")
            sources.append(str(path))
        classpath = os.pathsep.join(map(str,(classes,runtime_json,android)))
        subprocess.run(["javac","-encoding","UTF-8","-cp",classpath,"-d",str(classes),*sources],check=True)
        subprocess.run(["java","-cp",classpath,"xyz.erapi0neer.xingyi."+name],check=True)


if __name__ == "__main__":
    run_host_case("ConfirmedHarness", SCENARIO, with_reader=True)
