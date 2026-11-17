package xyz.erapi0neer.xingyi.agent.tv;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.util.function.BooleanSupplier;
import xyz.erapi0neer.xingyi.LocalAdb;

/**
 * 屏幕关着时先把屏幕点亮，再做那些在待机的电视上走不动的事。
 *
 * <p>为什么：网易云的开屏页（评论日历）在屏幕关着时不往下走——2026-11-09 20:11 点的一首歌在那页停了 17 秒，直到用户
 * 自己说「屏幕打开」（而且那页上按确认键没用，会放成别的歌）。B站的视频早就这么做了（{@code TvTools.openBiliVideo}）。
 *
 * <p>这里只用本机 ADB 按一次电源键，不碰 {@code TvTools}：接力的点歌直通以后也要用它。
 */
public final class ScreenWake {
    /** 按了电源键以后最多等这么久（实测约 1 秒内就亮）。 */
    private static final long WAIT_MS = 4000;

    private ScreenWake() { }

    /**
     * 屏幕已经亮着就直接 true；关着就按一次电源键，然后等它亮（每 200 毫秒看一次，最多 4 秒，任务被取消就提前停）。
     * 返回屏幕现在是不是亮着。从不按第二次：亮着按电源键是关机。
     */
    public static boolean ensureOn(Context c, BooleanSupplier cancelled) throws Exception {
        PowerManager power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (power == null || power.isInteractive()) return true;
        LocalAdb l = new LocalAdb(c);
        try {
            l.connect();
            l.keyEvents(new int[]{KeyEvent.KEYCODE_POWER});
        } finally {
            l.cancel();
        }
        long end = SystemClock.uptimeMillis() + WAIT_MS;
        while (SystemClock.uptimeMillis() < end && !power.isInteractive()) {
            if (cancelled != null && cancelled.getAsBoolean()) break;
            Thread.sleep(200);
        }
        return power.isInteractive();
    }
}
