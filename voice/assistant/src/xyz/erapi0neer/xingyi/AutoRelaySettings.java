package xyz.erapi0neer.xingyi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.text.InputType;
import android.util.AtomicFile;
import android.widget.EditText;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Local-only owner switch. No audio, utterance history or account credentials in these files. */
public final class AutoRelaySettings {
    private static AtomicFile file(Context c, String name) {
        return new AtomicFile(new File(c.getNoBackupFilesDir(), "xiaoai-relay-" + name));
    }
    private static String read(Context c, String key) {
        try { return new String(file(c,key).readFully(), StandardCharsets.US_ASCII); }
        catch (IOException e) { return ""; }
    }
    private static void write(Context c, String key, String value) throws IOException {
        AtomicFile f = file(c,key);
        FileOutputStream out = f.startWrite();
        try { out.write(value.getBytes(StandardCharsets.US_ASCII)); f.finishWrite(out); }
        catch (IOException e) { f.failWrite(out); throw e; }
    }
    static String owner(Context c) {
        String id = read(c,"owner");
        return id.matches("[0-9]{1,24}") ? id : "";
    }
    static boolean trialActive(Context c) {
        try {
            String[] values = read(c,"trial").split("/");
            if (values.length != 2) return false;
            long age = SystemClock.elapsedRealtime() - Long.parseLong(values[0]);
            long wall = System.currentTimeMillis() - Long.parseLong(values[1]);
            return age >= 0 && wall >= 0 && age < 80000 && wall < 80000;
        } catch (NumberFormatException ignored) { return false; }
    }
    public static boolean isArmed(Context c) { return !owner(c).isEmpty() || trialActive(c); }
    static void launch(Context c, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }
    static void enable(Context c, String id) throws IOException {
        if (id == null || !id.matches("[0-9]{1,24}")) throw new IOException("需要电视上登录的小米账号数字 ID");
        write(c,"owner",id);
        try { launch(c, new Intent(c,AutoRelayService.class)); }
        catch (RuntimeException e) { disable(c); throw new IOException("系统未能启动接手服务"); }
    }
    static void armTrial(Context c, String id, String expected) throws IOException {
        if (!owner(c).isEmpty()) throw new IOException("请先关闭日常接手，再开始隔离测试");
        if (id == null || !id.matches("[0-9]{1,24}") || SharedTextDraft.text(expected) == null)
            throw new IOException("无效的测试参数");
        write(c,"trial",SystemClock.elapsedRealtime()+"/"+System.currentTimeMillis());
        launch(c,new Intent(c,AutoRelayService.class).setAction("trial").putExtra("owner",id).putExtra("expected",expected));
    }
    static void endTrial(Context c) { file(c,"trial").delete(); }
    static boolean quietRefusals(Context c) { return "1".equals(read(c,"quiet-refusals")); }
    static void setQuietRefusals(Context c, boolean enabled) throws IOException {
        if (owner(c).isEmpty()) throw new IOException("请先开启小爱自动接手");
        if (enabled) write(c,"quiet-refusals","1"); else file(c,"quiet-refusals").delete();
        launch(c,new Intent(c,AutoRelayService.class).setAction("refresh_quiet"));
    }
    static void disable(Context c) {
        file(c,"owner").delete();
        file(c,"quiet-refusals").delete();
        endTrial(c);
        c.stopService(new Intent(c,AutoRelayService.class));
    }
    static void show(Activity a) {
        if (!owner(a).isEmpty()) {
            boolean quiet = quietRefusals(a);
            new AlertDialog.Builder(a).setTitle("小爱自动接手已开启")
                .setMessage("小爱先处理，明确拒绝后由 MCP 接手。识别到已知的拒绝播报时，星仪可以只停止这一句。当前" + (quiet ? "已开启。" : "已关闭。"))
                .setNeutralButton(quiet ? "恢复拒绝播报" : "停止拒绝播报",(d,w)->{
                    try { setQuietRefusals(a,!quiet); }
                    catch (Exception e) { Toast.makeText(a,"无法更改拒绝播报设置",Toast.LENGTH_LONG).show(); }
                })
                .setNegativeButton("返回",null).setPositiveButton("关闭接手",(d,w)->disable(a)).show();
            return;
        }
        EditText id = new EditText(a);
        id.setSingleLine(true);
        id.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        id.setHint("电视上登录的小米账号数字 ID");
        new AlertDialog.Builder(a).setTitle("允许小爱自动接手")
            .setMessage("开启后，星仪在电视本地检查这个账号此后新产生的小爱会话，匹配同一轮的原话和拒绝结果。命中规则时，原话会自动发送给 MCP 和当前配置的模型。其他会话不会发送。设备操作仍按原有规则预览。可随时在此关闭。")
            .setView(id).setNegativeButton("暂不开启",null).setPositiveButton("允许并开启",(d,w)->{
                try { enable(a,id.getText().toString().trim()); Toast.makeText(a,"小爱自动接手已开启",Toast.LENGTH_SHORT).show(); }
                catch (Exception e) { Toast.makeText(a,e.getMessage(),Toast.LENGTH_LONG).show(); }
            }).show();
    }
    private AutoRelaySettings() {}
}
