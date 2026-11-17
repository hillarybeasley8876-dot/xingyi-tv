package xyz.erapi0neer.xiaoaibridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import xyz.erapi0neer.xingyi.agent.tv.ScreenWake;

/** NetEase Cloud Music TV entry points. The voice:// routes are the ones NetEase registered for voice assistants. */
final class NeteasePlayer {
    static final String PKG = "com.netease.cloudmusic.tv";
    private static final ComponentName REDIRECT = new ComponentName(PKG, "com.netease.cloudmusic.tv.activity.TvRedirectActivity");

    /** One exact recording by catalog id. */
    static void playSong(Context c, long id) {
        Uri uri = new Uri.Builder().scheme("nmcloudmusictv").authority("ng").path("/player")
                .appendQueryParameter("RESOURCE_ID", Long.toString(id)).build();
        start(c, new Intent(Intent.ACTION_VIEW, uri).setPackage(PKG).putExtra("RESOURCE_ID", Long.toString(id)));
    }

    /** NetEase searches with the signed-in account and plays the playable results in order. */
    static void playKeywords(Context c, String keywords) { voice(c, "/search", "keywords", keywords); }
    static void playFavorites(Context c) { voice(c, "/favoritePlaylist", "command", "play"); }
    static void playPrivateFm(Context c) { voice(c, "/playPrivateFm", null, null); }

    static void openDaily(Context c) {
        start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("nmcloudmusictv://ng/daily/recommend")).setPackage(PKG));
    }

    static boolean installed(Context c) { return c.getPackageManager().getLaunchIntentForPackage(PKG) != null; }

    private static void voice(Context c, String path, String key, String value) {
        Uri.Builder b = new Uri.Builder().scheme("voice").authority("netease.audioplayer").path(path);
        if (key != null) b.appendQueryParameter(key, value);
        start(c, new Intent(Intent.ACTION_VIEW).setDataAndType(b.build(), "voice/audio").setComponent(REDIRECT));
    }

    private static void start(Context c, Intent intent) {
        // NetEase's launch page does not move on while the screen is off (2026-11-09 20:11: the song waited 17 s until
        // the user said 「屏幕打开」), and the user wants a song to stop when the screen goes off (2026-11-10): a song asked
        // for on a sleeping TV lights the screen first, as 星仪's own song tools do. Never on the main thread (≤4 s).
        if (Looper.myLooper() != Looper.getMainLooper()) {
            try { ScreenWake.ensureOn(c, null); } catch (Exception ignored) { /* the song is asked for anyway */ }
        }
        c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    static void mediaKey(Context c, int keyCode) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    /** NetEase's media session as reported by `dumpsys media_session` (needs DUMP); null when unreadable. */
    static NowPlaying nowPlaying() {
        String dump;
        try {
            Process p = new ProcessBuilder("dumpsys", "media_session").redirectErrorStream(true).start();
            try (InputStream in = p.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1 && out.size() < 512 * 1024) out.write(buf, 0, n);
                dump = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            p.waitFor(3, TimeUnit.SECONDS);
            p.destroy();
        } catch (IOException | InterruptedException e) {
            return null;
        }
        return NowPlaying.parse(dump, PKG);
    }

    private NeteasePlayer() { }
}
