package xyz.erapi0neer.xingyi;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Local foreground UI. Only fixed task labels and bounded progress numbers enter saved state. */
final class TaskProgressView extends LinearLayout {
    private static final int MUTED=0xffa4abb4, PLAIN=0xffedf0f2, ACTIVE=0xff93c8ef,
            PENDING=0xffffd595, GOOD=0xffa9d9af, BAD=0xffffaba2;
    final ScrollView scroll;
    private final TextView badge, summary, entries;
    private final int unit;
    private final ArrayList<String> lines=new ArrayList<>();
    private final SimpleDateFormat time=new SimpleDateFormat("HH:mm:ss",Locale.ROOT);
    private int current=-1;
    private boolean failed, supplied, ended;
    private String last="";

    TaskProgressView(Activity activity, float scale) {
        super(activity);
        unit=Math.max(1,Math.round(scale*100));
        setOrientation(VERTICAL);
        LinearLayout heading=new LinearLayout(activity);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        addView(heading,new LayoutParams(-1,-2));
        TextView title=text("执行过程",28,PLAIN);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        heading.addView(title,new LayoutParams(0,-2,1));
        badge=text("待命",23,MUTED);
        heading.addView(badge,new LayoutParams(-2,-2));
        summary=text("等待任务",24,MUTED);
        summary.setSingleLine(true); summary.setEllipsize(TextUtils.TruncateAt.END);
        summary.setPadding(0,px(9),0,px(13));
        addView(summary,new LayoutParams(-1,-2));
        scroll=new ScrollView(activity);
        scroll.setId(View.generateViewId()); scroll.setFocusable(true);
        scroll.setContentDescription("执行记录，按上下方向键查看步骤");
        scroll.setScrollbarFadingEnabled(false); scroll.setVerticalScrollBarEnabled(true);
        scroll.setOnFocusChangeListener((v,focus)->{
            GradientDrawable bg=new GradientDrawable(); bg.setColor(0xff10151b);
            bg.setCornerRadius(px(8)); bg.setStroke(px(focus?2:1),focus?PLAIN:0xff29323d);
            v.setBackground(bg);
        });
        scroll.setBackgroundColor(0xff10151b);
        scroll.setPadding(px(14),px(9),px(12),px(9));
        addView(scroll,new LayoutParams(-1,0,1));
        entries=text("执行时会在这里逐步更新。",24,MUTED);
        entries.setLineSpacing(px(7),1.0f);
        scroll.addView(entries,new ScrollView.LayoutParams(-1,-2));
        scroll.setOnKeyListener((v,key,e)->{
            if(e.getAction()!=KeyEvent.ACTION_DOWN) return false;
            int dir=key==KeyEvent.KEYCODE_DPAD_UP?-1:key==KeyEvent.KEYCODE_DPAD_DOWN?1:0;
            if(dir==0 || !scroll.canScrollVertically(dir)) return false;
            scroll.smoothScrollBy(0,dir*px(92)); return true;
        });
    }

    private int px(int n){return Math.max(1,Math.round(n*unit/100f));}
    private TextView text(String value,int size,int color){
        TextView row=new TextView(getContext()); row.setText(value);row.setTextColor(color);
        row.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(size));row.setIncludeFontPadding(false);return row;
    }
    private void state(String label,int color){badge.setText(label);badge.setTextColor(color);}
    private void add(String label){
        if(label.equals(last))return;
        last=label;
        if(lines.size()==48) lines.remove(0);
        lines.add(time.format(new Date())+"   "+label);
        boolean follow=!scroll.isFocused() || !scroll.canScrollVertically(1);
        entries.setTextColor(PLAIN);entries.setText(TextUtils.join("\n",lines));
        if(follow) scroll.post(()->scroll.scrollTo(0,entries.getHeight()));
    }
    /** For the agent engine (it runs in the bridge app): the panel is rewritten from its task record. */
    void agent(String goal,String body,String badgeText,boolean working,boolean ok){
        current=-1;ended=!working;last="";lines.clear();
        summary.setText(goal);
        state(badgeText,working?ACTIVE:ok?GOOD:BAD);
        entries.setTextColor(PLAIN);entries.setText(body);
        if(!scroll.isFocused()) scroll.post(()->scroll.scrollTo(0,entries.getHeight()));
    }
    void begin(int token,GenericRefusalText.Scope scope,boolean automatic,boolean image){
        current=token;failed=false;supplied=false;ended=false;last="";lines.clear();
        String task=image?"查看电视画面":"处理请求";
        if(scope!=null){
            switch(scope.kind){
                case INSTALL:task="安装哔哩哔哩官方客户端";break;
                case SEARCH:task="在哔哩哔哩客户端搜索";break;
                case APP:task=GenericRefusalText.isBiliClient(scope.name)?"打开哔哩哔哩":"打开应用";break;
                case SONG:task="网易云点播指定歌手与曲目";break;
                case TITLE:task="网易云按歌名点播";break;
                case ARTIST:task="网易云按歌手点播";break;
                case READ:task=scope.readTool==null?"查询电视状态":tool(scope.readTool);break;
            }
        }
        summary.setText((automatic?"小爱转交 · ":"星仪 · ")+task);
        state("进行中",ACTIVE);add(automatic?"收到小爱转交的原话":"收到任务");
    }
    void event(int token,String event,String name,String status){
        if(token!=current || ended)return;
        String action=tool(name);
        switch(event){
            case "turn_start":add("开始执行");break;
            case "before_model":state("思考中",ACTIVE);add("请求模型"+(status!=null&&status.matches("round=[1-9]")?" · 第 "+status.substring(6)+" 轮":""));break;
            case "after_model":add("模型已返回");break;
            case "proposal_repair":add("请求模型补齐工具调用");break;
            case "before_tool":add("检查操作 · "+action);break;
            case "confirmation_requested":state("等你确认",PENDING);add("等待你确认操作预览");break;
            case "confirmation_granted":state("进行中",ACTIVE);add("已收到确认");break;
            case "voice_confirmation":state("进行中",ACTIVE);add("收到本轮语音确认，继续当前步骤");break;
            case "tool_start":state("执行中",ACTIVE);add("调用工具 · "+action);break;
            case "tool_denied":failed=true;state("已阻止",BAD);add("未获准执行 · "+action);break;
            case "install_progress":installStage(status);break;
            case "music_progress":
                if("checking_playback".equals(status))add("已选定曲目，核对当前播放");
                if("intent_submitted".equals(status))add("已向网易云发送点播请求");
                break;
            case "after_tool":
                if("verified".equals(status)||"observed".equals(status)||"already_playing".equals(status)
                        ||"artist_already_playing".equals(status)||"title_already_playing".equals(status)
                        ||"installed".equals(status)||"already_installed".equals(status)){
                    supplied=true;add("已核实 · "+action);
                }else if("requested".equals(status)||"submitted".equals(status)) add("已提交 · "+action);
                else if(status!=null&&status.matches(".*(?:fail|error|denied|unavailable|unknown|unconfirmed|not_found|reject|uncertain|skipped).*")){
                    failed=true;state("未确认",BAD);add("未能确认完成 · "+action);
                }else add("工具已返回 · "+action);
                break;
            case "turn_error":finish(token,false,"任务出错，请看结果");break;
            case "turn_cancel":finish(token,false,"任务已取消");break;
            case "turn_end":add("执行流程结束");break;
        }
    }
    private void installStage(String status){
        if(status==null)return;
        // Only fixed labels from the installer. Never display untrusted errors, paths or URLs here.
        if(status.matches("正在从哔哩哔哩官方下载，共 [0-9]{1,4} MiB|已下载 [0-9]{1,3}%（[0-9]{1,4} / [0-9]{1,4} MiB）"
                +"|已验证官方签名，版本 [0-9.]{1,20}|核对通过，已安装 tv\\.danmaku\\.bili [0-9.]{1,20}")) { add(status);return; }
        int failure=status.indexOf("未完成：");
        if(failure>0) {
            String phase=status.substring(0,failure), detail=status.substring(failure+4);
            if(!phase.matches("官方下载|包名和签名校验|Android 系统安装|安装结果核对"))phase="安装步骤";
            java.util.regex.Matcher code=java.util.regex.Pattern.compile("\\bINSTALL_FAILED_[A-Z_]{1,64}\\b").matcher(detail);
            String reason;
            if(code.find())reason=code.group();
            else if(detail.matches("电视可用空间不足|官方下载超时或数据过长|官方安装包下载不完整|安装包名称或签名未通过哔哩哔哩官方身份校验|已取消安装|官方服务器没有返回完整的 Android 安装包（HTTP [0-9]{3}）"))reason=detail;
            else if(detail.contains("复制"))reason="安装包复制失败";
            else if(detail.contains("摘要")||detail.contains("散列")||detail.contains("SHA256"))reason="安装包摘要校验失败";
            else reason="请看左侧结果";
            add(phase+"未完成 · "+reason);
            failed=true;state("未完成",BAD);return;
        }
        switch(status){
            case "核对电视上已安装的包名和签名":
            case "下载完成，正在校验官方包名和签名":
            case "正在把已校验的安装包交给 Android 安装器":
            case "正在核对电视实际安装的包名、版本和签名":add(status);break;
            default:break;
        }
    }
    void finish(int token,boolean ok,String label){
        if(token!=current || ended)return;
        ended=true;
        state(!ok||failed?"未完成":supplied?"已核实":"已结束",!ok||failed?BAD:GOOD);
        add(label);
    }
    private static String tool(String id){
        if(id==null)return "电视工具";
        switch(id){
            case "get_status":return "读取电视状态";
            case "get_sleep_timer":return "读取休眠定时";
            case "list_apps":return "列出应用";
            case "open_app":return "打开应用";
            case "install_app":return "安装并核验应用";
            case "search_video":return "搜索视频";
            case "play_netease_artist":case "play_netease_music":case "play_netease_title":return "网易云点歌";
            case "set_volume":case "adjust_volume":return "调整音量";
            case "set_sleep_timer":case "cancel_sleep_timer":return "调整休眠定时";
            case "say":return "小爱播报";
            case "ask_xiaoai":return "交给小爱";
            case "switch_input":return "切换信号源";
            case "settings":return "打开设置";
            case "home":return "回到桌面";
            default:return "电视工具";
        }
    }
    void save(Bundle out){
        if(lines.isEmpty())return;
        out.putStringArrayList("task-lines",new ArrayList<>(lines));
        out.putString("task-summary",summary.getText().toString());out.putString("task-status",badge.getText().toString());
        out.putBoolean("task-ended",ended);out.putInt("task-color",badge.getCurrentTextColor());
    }
    void restore(Bundle saved){
        if(saved==null)return;
        ArrayList<String> previous=saved.getStringArrayList("task-lines");
        if(previous==null || previous.isEmpty())return;
        lines.addAll(previous); summary.setText(saved.getString("task-summary"));
        ended=true;entries.setTextColor(PLAIN);entries.setText(TextUtils.join("\n",lines));
        if(saved.getBoolean("task-ended"))state(saved.getString("task-status"),saved.getInt("task-color",MUTED));
        else{state("上次记录",MUTED);add("界面已重建，后续状态未确认");}
    }
}
