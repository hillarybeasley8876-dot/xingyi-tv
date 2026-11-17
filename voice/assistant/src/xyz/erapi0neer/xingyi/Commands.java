package xyz.erapi0neer.xingyi;

import org.json.*;
import java.util.*;
import java.util.regex.*;

/** Closed, strictly typed action vocabulary. No input is ever shell code. */
public final class Commands {
    private Commands() {}
    public static JSONObject action(String tool, JSONObject args) throws JSONException {
        return new JSONObject().put("tool", tool).put("args", args);
    }
    public static JSONArray parse(String input) throws JSONException {
        String s = input == null ? "" : input.trim().replaceAll("[。！!]$", "");
        JSONArray a = new JSONArray();
        if (s.matches("(返回|回到|打开)?(桌面|主页)")) a.put(action("home",new JSONObject()));
        else if (s.matches("(打开|进入)?(电视)?设置")) a.put(action("settings",new JSONObject()));
        else if (s.matches("(取消静音|恢复声音|静音)")) a.put(action("adjust_volume",new JSONObject().put("direction",s.equals("静音")?"mute":"unmute")));
        else if (s.matches("(音量大一点|调大音量|声音大一点|音量加|提高音量)")) a.put(action("adjust_volume",new JSONObject().put("direction","up")));
        else if (s.matches("(音量小一点|调小音量|声音小一点|音量减|降低音量)")) a.put(action("adjust_volume",new JSONObject().put("direction","down")));
        else {
            Matcher m = Pattern.compile("(?:请)?(?:把)?(?:媒体)?(?:音量|声音)(?:设置|调|调整)?(?:成|到|为)?\\s*(?:百分之)?([0-9零〇一二两三四五六七八九十百]{1,5})\\s*[%％]?").matcher(s);
            Matcher h = Pattern.compile("(?:切换到|切到|打开|切换)?\\s*HDMI\\s*([123])",Pattern.CASE_INSENSITIVE).matcher(s);
            if(m.matches()) {
                Integer level = number(m.group(1));
                if(level != null) a.put(action("set_volume",new JSONObject().put("level",level)));
            } else if(h.matches()) a.put(action("switch_input",new JSONObject().put("name","HDMI"+h.group(1))));
            else if(s.startsWith("打开") && s.length()>2) a.put(action("open_app",new JSONObject().put("name",s.substring(2).trim())));
        }
        validate(a); return a;
    }
    private static Integer number(String s) {
        if(s.matches("[0-9]{1,5}"))return Integer.valueOf(s);
        if(s.equals("一百"))return 100;
        String digits="零一二三四五六七八九";
        s=s.replace('〇','零').replace('两','二');
        if(s.length()==1&&digits.indexOf(s.charAt(0))>=0)return digits.indexOf(s.charAt(0));
        if(s.matches("[一二三四五六七八九]?十[一二三四五六七八九]?")){
            int at=s.indexOf('十');return (at==0?1:digits.indexOf(s.charAt(0)))*10+(at==s.length()-1?0:digits.indexOf(s.charAt(at+1)));
        }
        return null;
    }
    public static void validate(JSONArray list) throws JSONException {
        if(list==null)throw new IllegalArgumentException("操作列表不能为空");
        if(list.length()>4) throw new IllegalArgumentException("一次最多执行 4 个操作");
        for(int i=0;i<list.length();i++) {
            JSONObject a=list.getJSONObject(i);
            if(!(a.opt("args") instanceof JSONObject))throw new IllegalArgumentException("操作参数必须是对象");
            JSONObject args=a.getJSONObject("args"); String t=string(a,"tool",40);
            exactKeys(a,new HashSet<>(Arrays.asList("tool","args")));
            Set<String> keys=new HashSet<>();
            switch(t) {
                case "set_volume":
                    keys.add("level"); Object raw=args.opt("level");
                    if(!(raw instanceof Number)||!Double.isFinite(((Number)raw).doubleValue())||((Number)raw).doubleValue()!=((Number)raw).intValue()||((Number)raw).intValue()<0||((Number)raw).intValue()>100) throw new IllegalArgumentException("音量必须是 0–100 的整数百分比"); break;
                case "adjust_volume": keys.add("direction"); if(!Arrays.asList("up","down","mute","unmute").contains(string(args,"direction",10))) throw new IllegalArgumentException("不支持的音量操作"); break;
                case "switch_input": keys.add("name"); if(!string(args,"name",8).matches("HDMI[123]")) throw new IllegalArgumentException("信号源仅支持 HDMI1–3"); break;
                case "open_app": keys.add("name"); string(args,"name",200); break;
                case "search_video": keys.add("app"); keys.add("query"); string(args,"app",200); string(args,"query",200); break;
                case "say": keys.add("text"); string(args,"text",1800); break;
                case "home": case "settings": break;
                default: throw new IllegalArgumentException("尚不支持这个操作");
            }
            exactKeys(args,keys);
        }
    }
    private static String string(JSONObject object,String key,int limit) {
        Object raw=object.opt(key);
        if(!(raw instanceof String))throw new IllegalArgumentException("参数 "+key+" 必须是文字");
        String s=(String)raw;
        if(s.trim().isEmpty()||s.length()>limit||s.indexOf('\0')>=0)throw new IllegalArgumentException("参数为空或过长");
        return s;
    }
    private static void exactKeys(JSONObject object,Set<String> allowed) {
        Iterator<String> keys=object.keys();while(keys.hasNext())if(!allowed.contains(keys.next()))throw new IllegalArgumentException("操作包含未定义参数");
    }
    public static String describe(JSONArray a) throws JSONException {
        validate(a); StringBuilder b=new StringBuilder();
        for(int i=0;i<a.length();i++) {
            JSONObject x=a.getJSONObject(i),p=x.getJSONObject("args"); String t=x.getString("tool"),s;
            switch(t) {
                case "set_volume": s="媒体音量设为 "+p.getInt("level")+"%"; break;
                case "adjust_volume": String d=p.getString("direction");s=d.equals("up")?"调大音量":d.equals("down")?"调小音量":d.equals("mute")?"静音":"取消静音"; break;
                case "switch_input": s="切换至 "+p.getString("name"); break;
                case "open_app": s="打开 "+p.getString("name"); break;
                case "search_video": s="在 "+p.getString("app")+" 搜索「"+p.getString("query")+"」"; break;
                case "home": s="返回桌面"; break;
                case "settings": s="打开电视设置"; break;
                default: s="播报："+p.getString("text");
            }
            b.append(i+1).append(". ").append(s).append('\n');
        } return b.toString();
    }
}
