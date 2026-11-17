package xyz.erapi0neer.xingyi;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.*;
import java.io.IOException;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit tool registry; model strings never become executable code. Call execute on a worker. */
public final class ToolRegistry {
    private final Activity activity;
    private final TvControl tv; private final java.util.function.BooleanSupplier cancelled;
    private final NeteaseMusic.Progress musicProgress;
    private final OfficialAppInstaller.Progress installProgress;
    private final Handler main=new Handler(Looper.getMainLooper());
    private static final Set<String> READS=new HashSet<>(Arrays.asList("get_status","list_apps","get_sleep_timer"));
    public ToolRegistry(Activity a, java.util.function.BooleanSupplier stop){this(a,stop,null,stage -> {});}
    ToolRegistry(Activity a, java.util.function.BooleanSupplier stop, NeteaseMusic.Progress progress, OfficialAppInstaller.Progress installProgress){
        activity=a;tv=new TvControl(a);cancelled=stop;musicProgress=progress;this.installProgress=installProgress;
    }
    public static boolean readOnly(String name){return READS.contains(name);}
    public static JSONArray definitions() throws JSONException {
        JSONArray a=new JSONArray();
        add(a,"get_status","读取真实媒体音量、静音和助手能力。无参数。",new JSONObject());
        add(a,"list_apps","列出可打开应用的准确名称。不得猜应用名。",new JSONObject());
        add(a,"get_sleep_timer","读取已设置的定时休眠状态。",new JSONObject());
        add(a,"set_volume","设置媒体音量百分比，0到100整数，宿主收到调用后会预览，请直接发起函数调用。",props("level","integer"));
        add(a,"adjust_volume","音量操作direction只能up/down/mute/unmute，需确认。",props("direction","string"));
        add(a,"switch_input","切换输入，name只能HDMI1/HDMI2/HDMI3，需确认。",props("name","string"));
        add(a,"open_app","打开应用，name必须为list_apps返回的准确标签。",props("name","string"));
        add(a,"install_app","安装已知官方应用，需要对安装包名和签名进行校验并确认。当前支持哔哩哔哩官方 Android 客户端。",props("package","string"));
        add(a,"play_netease_music","在网易云音乐精确点歌，自动查询曲库、打开播放器并核对歌名和歌手。title只填歌名，artist填完整歌手署名。打开网易云然后播放某歌，只调用这个工具一次，无需先打开应用。",props("title","string","artist","string"));
        add(a,"play_netease_artist","用户只要求在网易云听某位歌手的歌时，由曲库选一首本人演唱的歌曲并核对播放。仅填实际说出的歌手姓名。",props("artist","string"));
        add(a,"play_netease_title","只指定歌名时在网易云按完整歌名选曲，返回实际播放的歌手和版本。",props("title","string"));
        add(a,"home","返回桌面。",new JSONObject());add(a,"settings","打开电视设置。",new JSONObject());
        add(a,"search_video","通过应用公开搜索入口搜索。不能保证自动播放。",props("app","string","query","string"));
        add(a,"say","把text通过电视语音播报，需确认。",props("text","string"));
        add(a,"set_sleep_timer","设置1到120分钟后的休眠，需确认。",props("minutes","integer"));
        add(a,"cancel_sleep_timer","取消已有休眠定时，需确认。",new JSONObject());
        add(a,"ask_xiaoai","将用户要求转交小爱的文字提交给宿主预览，可含米家设备指令。需用户确认，只返回提交状态，不能宣称设备已完成。",props("query","string"));
        return a;
    }
    private static JSONObject props(String... kv)throws JSONException{JSONObject p=new JSONObject();for(int i=0;i<kv.length;i+=2)p.put(kv[i],new JSONObject().put("type",kv[i+1]));return p;}
    private static void add(JSONArray a,String name,String description,JSONObject props)throws JSONException{JSONArray required=new JSONArray();Iterator<String> it=props.keys();while(it.hasNext())required.put(it.next());a.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description).put("parameters",new JSONObject().put("type","object").put("properties",props).put("required",required).put("additionalProperties",false))));}
    public static void validate(String name,JSONObject args)throws Exception{
        if(name.equals("install_app")){
            if(args.length()!=1 || !OfficialAppInstaller.BILIBILI.equals(args.opt("package")))throw new IllegalArgumentException("此软件包尚未配置官方安装来源");
            return;
        }
        if (name.equals("play_netease_artist") || name.equals("play_netease_title")) {
            String field = name.equals("play_netease_artist") ? "artist" : "title";
            if (args.length() != 1 || !musicTerm(args.opt(field)))
                throw new IllegalArgumentException("请提供有效的点歌目标");
            return;
        }
        if (name.equals("play_netease_music")) {
            if (args.length() != 2 || !musicTerm(args.opt("title")) || !musicTerm(args.opt("artist")))
                throw new IllegalArgumentException("点歌需要有效的歌名和歌手");
            return;
        }
        if(READS.contains(name)||name.equals("cancel_sleep_timer")){if(args.length()!=0)throw new IllegalArgumentException("该工具不接受参数");return;}
        if(name.equals("set_sleep_timer")){Object n=args.opt("minutes");if(args.length()!=1||!(n instanceof Number)||((Number)n).doubleValue()!=((Number)n).intValue()||((Number)n).intValue()<1||((Number)n).intValue()>120)throw new IllegalArgumentException("休眠时间必须为1–120分钟整数");return;}
        if(name.equals("ask_xiaoai")){Object q=args.opt("query");if(args.length()!=1||!(q instanceof String)||((String)q).trim().isEmpty()||((String)q).length()>1000)throw new IllegalArgumentException("小爱指令为空或过长");return;}
        Commands.validate(new JSONArray().put(Commands.action(name,args)));
    }
    public static String describe(String name,JSONObject args)throws Exception{
        validate(name,args);
        if(name.equals("install_app"))return OfficialAppInstaller.PREVIEW;
        if(name.equals("play_netease_artist"))return "在网易云音乐播放一首"+args.getString("artist")+"演唱的歌";
        if(name.equals("play_netease_title"))return "在网易云音乐播放《"+args.getString("title")+"》";
        if(name.equals("play_netease_music"))return "在网易云音乐播放"+args.getString("artist")+"的《"+args.getString("title")+"》";
        if(name.equals("say"))return "朗读："+args.getString("text");if(name.equals("get_status"))return "读取电视音量和状态";
        if(name.equals("list_apps"))return "读取应用列表";
        if(name.equals("get_sleep_timer"))return "读取休眠定时";
        if(name.equals("set_sleep_timer"))return args.getInt("minutes")+"分钟后休眠";
        if(name.equals("cancel_sleep_timer"))return "取消休眠定时";
        if(name.equals("ask_xiaoai"))return "交给小爱："+args.getString("query");
        return Commands.describe(new JSONArray().put(Commands.action(name,args))).replaceFirst("1. ","").trim();
    }
    public JSONObject execute(String name,JSONObject args)throws Exception{
        validate(name,args);if(cancelled.getAsBoolean())throw new IllegalStateException("已取消");
        if(name.equals("install_app"))return new OfficialAppInstaller(activity,cancelled,installProgress).install();
        if(name.equals("play_netease_artist") || name.equals("play_netease_music") || name.equals("play_netease_title")) {
            String artist=args.optString("artist",null), title=args.optString("title",null);
            return musicProgress==null ? NeteaseMusic.play(activity,title,artist,cancelled)
                    : NeteaseMusic.playAutomatic(activity,title,artist,cancelled,musicProgress);
        }
        if(name.equals("ask_xiaoai")){
            CountDownLatch ready=new CountDownLatch(1);String[] status={"未提交"};
            VendorQuery.dispatch(activity,args.getString("query"),message->{status[0]=message;ready.countDown();});
            if(!ready.await(4,TimeUnit.SECONDS))return new JSONObject().put("ok",false).put("status","unknown").put("message","小爱提交状态未知，不要自动重试");
            return new JSONObject().put("ok",status[0].startsWith("已转交")).put("status","submitted").put("execution_confirmed",false).put("message",status[0]);
        }
        if(name.equals("set_sleep_timer")){
            LocalAdb connection=new LocalAdb(activity);try{connection.connect();}finally{connection.cancel();}
            if(cancelled.getAsBoolean())throw new IllegalStateException("已取消");SleepTimer.schedule(activity,args.getInt("minutes"));return new JSONObject().put("ok",true).put("scheduled_minutes",args.getInt("minutes"));
        }
        FutureTask<JSONObject> work=new FutureTask<>(()->{
            if(cancelled.getAsBoolean()||activity.isDestroyed())throw new IllegalStateException("助手已关闭");
            if(name.equals("get_status")){AudioManager am=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);return new JSONObject().put("ok",true).put("volume",am.getStreamVolume(3)).put("volume_max",am.getStreamMaxVolume(3)).put("muted",am.isStreamMute(3)).put("voice_input","可打开电视键盘尝试遥控语音，也可通过手机听写。远场唤醒由小爱处理。").put("model",ModelRouter.activeModelName()).put("model_route",ModelRouter.status().label);}
            if(name.equals("list_apps"))return new JSONObject().put("ok",true).put("apps",new JSONArray(tv.apps().keySet()));
            if(name.equals("get_sleep_timer"))return new JSONObject().put("ok",true).put("status","observed").put("current_timer",SleepTimer.currentStatus(activity));
            if(name.equals("cancel_sleep_timer")){SleepTimer.cancel(activity);return new JSONObject().put("ok",true).put("message","休眠定时已取消");}
            return tv.executeTool(name,args);
        });
        main.post(work);
        JSONObject sent;
        try{sent=work.get(5,TimeUnit.SECONDS);}catch(TimeoutException e){work.cancel(false);return new JSONObject().put("ok",false).put("status","unknown").put("message","工具执行状态未及时返回，禁止自动重试");}
        if(!"search_video".equals(name) || !sent.optBoolean("ok") || !GenericRefusalText.isBiliClient(args.optString("app")))return sent;
        LocalAdb local=new LocalAdb(activity);
        long end=SystemClock.elapsedRealtime()+16000;
        try {
            for(int i=0;i<4 && SystemClock.elapsedRealtime()<end && !cancelled.getAsBoolean();i++){
                Thread.sleep(650);
                try {
                    JSONObject page=BiliSearchResult.inspect(local.biliSearchSnapshot(),args.getString("query"));
                    if(page!=null)return page;
                } catch(IOException unavailable) {
                    local.cancel(); local=new LocalAdb(activity);
                }
            }
        } finally { local.cancel(); }
        return BiliSearchResult.result(false,"unconfirmed","已发出哔哩哔哩搜索请求，还没能核对到“"+args.getString("query")+"”的结果页。");
    }
    private static boolean musicTerm(Object value) {
        if (!(value instanceof String)) return false;
        String term = (String)value;
        return !term.trim().isEmpty() && term.length() <= 200 && !java.util.regex.Pattern.compile("\\p{Cc}").matcher(term).find();
    }
}


