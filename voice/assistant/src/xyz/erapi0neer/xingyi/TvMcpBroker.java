package xyz.erapi0neer.xingyi;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Only boots an authenticated loopback HTTP session. Tool traffic goes over MCP. */
public final class TvMcpBroker extends ContentProvider {
    private static final String CLIENT = "xyz.erapi0neer.xingyi.voicecheck";
    private static final String PERMISSION = "xyz.erapi0neer.xingyi.permission.USE_LOCAL_MCP";
    private TvMcpHttp server;
    private long started;

    @Override public boolean onCreate() { return true; }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        Context app = getContext();
        app.enforceCallingPermission(PERMISSION, "需要星仪签名权限");
        if (!CLIENT.equals(getCallingPackage()) ||
                app.getPackageManager().checkSignatures(Binder.getCallingUid(), Process.myUid())
                        != PackageManager.SIGNATURE_MATCH)
            throw new SecurityException("此应用没有配对");
        if (extras != null && !extras.isEmpty()) throw new IllegalArgumentException("不接受额外参数");
        if ("close_read_session".equals(method)) {
            if (server != null && arg != null && arg.length() <= 128 && MessageDigest.isEqual(
                    server.bearer().getBytes(StandardCharsets.UTF_8), arg.getBytes(StandardCharsets.UTF_8))) {
                server.close();
                server = null;
            }
            return Bundle.EMPTY;
        }
        if (!"open_read_session".equals(method) || arg != null) throw new IllegalArgumentException("未知会话请求");
        long now = SystemClock.elapsedRealtime();
        if (server == null || now - started >= 35000) {
            if (server != null) server.close();
            server = null;
            try { server = new TvMcpHttp(name -> read(app, name)); }
            catch (IOException e) { throw new IllegalStateException("无法打开电视本机 MCP 连接", e); }
            started = now;
        }
        Bundle answer = new Bundle();
        answer.putString("endpoint", server.url());
        answer.putString("token", server.bearer());
        return answer;
    }

    static JSONObject read(Context app, String name) throws Exception {
        if ("tv_status".equals(name)) {
            AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) throw new IllegalStateException("音频服务不可用");
            int stream = AudioManager.STREAM_MUSIC;
            int max = audio.getStreamMaxVolume(stream), volume = audio.getStreamVolume(stream);
            if (max <= 0 || volume < 0 || volume > max) throw new IllegalStateException("音量读数无效");
            return new JSONObject().put("ok", true).put("volume", volume).put("volume_max", max)
                    .put("muted", audio.isStreamMute(stream));
        }
        if ("sleep_timer".equals(name)) return new JSONObject().put("ok", true).put("status", SleepTimer.currentStatus(app));
        throw new IllegalArgumentException("未知只读工具");
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
