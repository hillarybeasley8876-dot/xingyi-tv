package xyz.erapi0neer.xingyi;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.media.AudioManager;
import org.json.*;
import java.util.*;

/** Invoked on the main thread. Results distinguish an observed change from an intent submission. */
public final class TvControl {
    final Activity activity;
    public TvControl(Activity a){activity=a;}

    public Map<String,Intent> apps() {
        PackageManager pm=activity.getPackageManager();
        Map<String,Intent> byPackage=new TreeMap<>();Map<String,String> names=new HashMap<>();
        for(String category:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER}) {
            for(ResolveInfo r:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category),0)) {
                if(r.activityInfo==null)continue;
                String pkg=r.activityInfo.packageName;
                if(byPackage.containsKey(pkg)||pkg.equals(activity.getPackageName())||pkg.equals("com.xiaomi.voicecontrol"))continue;
                Intent i=pm.getLeanbackLaunchIntentForPackage(pkg);if(i==null)i=pm.getLaunchIntentForPackage(pkg);
                if(i==null||i.getComponent()==null)continue;
                CharSequence raw=r.loadLabel(pm);String label=raw==null?pkg:raw.toString().trim();if(label.isEmpty())label=pkg;
                byPackage.put(pkg,i);names.put(pkg,label);
            }
        }
        Map<String,Intent> out=new TreeMap<>();
        for(Map.Entry<String,String> e:appLabels(names).entrySet())out.put(e.getKey(),byPackage.get(e.getValue()));
        return out;
    }

    /** Maps human-facing names to one package each. Reserved delimiters in labels cannot spoof our package suffix. */
    static Map<String,String> appLabels(Map<String,String> names) {
        Map<String,String> clean=new TreeMap<>();Map<String,Integer> counts=new HashMap<>();
        for(Map.Entry<String,String> e:names.entrySet()) {
            String label=e.getValue().replace('[','〔').replace(']','〕');
            clean.put(e.getKey(),label);counts.put(label,counts.containsKey(label)?counts.get(label)+1:1);
        }
        Map<String,String> out=new TreeMap<>();
        for(Map.Entry<String,String> e:clean.entrySet()) {
            String label=e.getValue();
            boolean unofficialBiliLabel = GenericRefusalText.isBiliClient(label) && !GenericRefusalText.BILI_PACKAGE.equals(e.getKey());
            out.put(counts.get(label)>1 || unofficialBiliLabel ? label+" ["+e.getKey()+"]" : label,e.getKey());
        }
        return out;
    }
    /** A spoken Bilibili alias names the Android client; launcher labels alone do not identify it. */
    static <T> T resolveApp(String name, Map<String,T> installed, java.util.function.Function<T,String> packageName) {
        if (!GenericRefusalText.isBiliClient(name)) return installed.get(name);
        for (T target : installed.values()) {
            if (GenericRefusalText.BILI_PACKAGE.equals(packageName.apply(target))) return target;
        }
        return null;
    }
    static Intent resolveApp(String name,Map<String,Intent> installed) {
        return resolveApp(name,installed,i -> i.getComponent()==null ? null : i.getComponent().getPackageName());
    }
    private static boolean needsApps(String tool){return "open_app".equals(tool)||"search_video".equals(tool);}

    /** Existing button/batch API. Preflight all operations before the first side effect. */
    public String execute(JSONArray actions) throws Exception {
        Commands.validate(actions);Map<String,Intent> installed=Collections.emptyMap();
        for(int i=0;i<actions.length();i++)if(needsApps(actions.getJSONObject(i).getString("tool"))){installed=apps();break;}
        for(int i=0;i<actions.length();i++)preflight(actions.getJSONObject(i).getString("tool"),actions.getJSONObject(i).getJSONObject("args"),installed);
        StringBuilder result=new StringBuilder();
        for(int i=0;i<actions.length();i++) {
            JSONObject a=actions.getJSONObject(i),out=executeOne(a.getString("tool"),a.getJSONObject("args"),installed);
            result.append(out.getString("message")).append('\n');
            if(!out.getBoolean("ok")){result.append("后续操作已停止");break;}
        }
        return result.toString();
    }

    /** Structured result for a tool-call loop. This method never trusts model-generated shell or intents. */
    public JSONObject executeTool(String tool,JSONObject args) {
        try {
            JSONArray one=new JSONArray().put(Commands.action(tool,args));Commands.validate(one);
            Map<String,Intent> installed=needsApps(tool)?apps():Collections.emptyMap();preflight(tool,args,installed);return executeOne(tool,args,installed);
        }catch(IllegalArgumentException e){return result(false,"rejected",e.getMessage());}
        catch(Exception e){return result(false,"failed","操作参数无效或电视当前无法执行");}
    }

    private void preflight(String tool,JSONObject args,Map<String,Intent> installed)throws Exception {
        if (tool.equals("open_app")) {
            String name=args.getString("name");
            if (resolveApp(name,installed)==null) throw new IllegalArgumentException(GenericRefusalText.isBiliClient(name)
                    ? "电视上没有可启动的哔哩哔哩官方 Android 客户端（tv.danmaku.bili）。"
                    : "没有找到完全匹配的应用，请从应用列表选择");
        }
        if(tool.equals("search_video")) {
            Intent search=searchIntent(args,installed);
            ResolveInfo resolved=activity.getPackageManager().resolveActivity(search,PackageManager.MATCH_DEFAULT_ONLY);
            if(resolved==null || resolved.activityInfo==null || !resolved.activityInfo.exported
                    || !search.getPackage().equals(resolved.activityInfo.packageName))
                throw new IllegalArgumentException("该应用未提供可用的搜索入口，不能保证搜索或播放");
        }
        if(tool.equals("say")&&!VendorSpeech.isAvailable(activity))throw new IllegalArgumentException("小米播报入口不可用");
    }

    private JSONObject executeOne(String tool,JSONObject args,Map<String,Intent> installed) {
        try {
            AudioManager am=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);
            if(tool.equals("set_volume")) {
                int max=am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),expected=Math.round(max*args.getInt("level")/100f);
                am.setStreamVolume(AudioManager.STREAM_MUSIC,expected,0);
                int actual=am.getStreamVolume(AudioManager.STREAM_MUSIC);
                return volumeResult(actual==expected,am,"当前媒体音量 "+actual+"/"+max);
            }
            if(tool.equals("adjust_volume")) {
                int before=am.getStreamVolume(AudioManager.STREAM_MUSIC),max=am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);String d=args.getString("direction");
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,d.equals("up")?AudioManager.ADJUST_RAISE:d.equals("down")?AudioManager.ADJUST_LOWER:d.equals("mute")?AudioManager.ADJUST_MUTE:AudioManager.ADJUST_UNMUTE,0);
                int actual=am.getStreamVolume(AudioManager.STREAM_MUSIC);boolean muted=am.isStreamMute(AudioManager.STREAM_MUSIC);
                boolean ok=d.equals("mute")?muted:d.equals("unmute")?!muted:d.equals("up")?(actual>before||actual==max):(actual<before||actual==0);
                return volumeResult(ok,am,"媒体音量 "+actual+"/"+max+(muted?"，已静音":"，未静音"));
            }
            if(tool.equals("switch_input")) {
                int n=Integer.parseInt(args.getString("name").substring(4));
                activity.startActivity(new Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY").setComponent(new ComponentName("com.xiaomi.mitv.tvplayer","com.xiaomi.mitv.tvplayer.ExternalSourceActivity")).putExtra("input",22+n));
                return result(true,"requested","已请求切换至 HDMI "+n+"，未读回外部信号状态");
            }
            if(tool.equals("home")){activity.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));return result(true,"requested","已请求打开桌面");}
            if(tool.equals("settings")){activity.startActivity(new Intent().setComponent(new ComponentName("com.xiaomi.mitv.settings","com.xiaomi.mitv.settings.entry.MainActivity")));return result(true,"requested","已请求打开电视设置");}
            if(tool.equals("open_app")){
                String name=args.getString("name");
                Intent target=resolveApp(name,installed);
                activity.startActivity(target);
                return result(true,"requested","已请求打开 "+name).put("package",target.getComponent().getPackageName());
            }
            if(tool.equals("search_video")) {
                activity.startActivity(searchIntent(args,installed));
                return result(true,"requested","已发送搜索请求，未验证结果或播放状态");
            }
            if(tool.equals("say")) {
                Intent intent=new Intent("com.xiaomi.voicecontrol.PLAY_TTS").setComponent(new ComponentName("com.xiaomi.voicecontrol","com.xiaomi.voicecontrol.fullduplex.FullDuplexDebugService")).putExtra("text",args.getString("text"));
                ComponentName started=activity.startService(intent);
                return result(started!=null,started!=null?"requested":"failed",started!=null?"已提交小米语音播报，未取得播放完成回调":"未能提交播报请求");
            }
            return result(false,"rejected","不支持这个操作");
        }catch(SecurityException e){return result(false,"failed","系统拒绝了这项操作");}
        catch(ActivityNotFoundException e){return result(false,"failed","电视没有可用的功能入口");}
        catch(Exception e){return result(false,"failed","电视操作失败，后续操作已停止");}
    }

    private static Intent searchIntent(JSONObject args, Map<String,Intent> installed) throws JSONException {
        String name=args.getString("app"), query=args.getString("query");
        Intent target=resolveApp(name,installed);
        if (target==null) throw new IllegalArgumentException("电视上没有安装"+name);
        String pkg=target.getComponent().getPackageName();
        if (GenericRefusalText.BILI_PACKAGE.equals(pkg)) {
            android.net.Uri uri=new android.net.Uri.Builder().scheme("bilibili").authority("search")
                    .appendQueryParameter("keyword",query).build();
            return new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg);
        }
        return new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra("query",query);
    }
    private static JSONObject volumeResult(boolean ok,AudioManager am,String message)throws JSONException {
        JSONObject r=result(ok,ok?"observed":"unconfirmed",message+(ok?"":"；未达到请求值"));
        r.put("volume",am.getStreamVolume(AudioManager.STREAM_MUSIC));r.put("maxVolume",am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));r.put("muted",am.isStreamMute(AudioManager.STREAM_MUSIC));return r;
    }
    private static JSONObject result(boolean ok,String state,String message) {
        JSONObject r=new JSONObject();try{r.put("ok",ok).put("status",state).put("message",message==null?"操作失败":message);}catch(JSONException impossible){throw new AssertionError(impossible);}return r;
    }
}
