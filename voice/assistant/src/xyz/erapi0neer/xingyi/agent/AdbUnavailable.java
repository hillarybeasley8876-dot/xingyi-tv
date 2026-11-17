package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.net.ConnectException;
import java.util.function.BooleanSupplier;

/** The TV's own adb listener (loopback port 5555) refused the connection: "ADB debugging" has been switched off (Xiaomi's
 * firmware does that at standby) or the listener is gone. Key presses, screenshots and playback-state reads all go
 * through it; reading and clicking through the accessibility service still work. */
public final class AdbUnavailable extends IOException {
    public static final String MESSAGE = "电视的 ADB 调试关着，按键、截图、读播放状态这些要用调试口的操作现在做不了；读屏和点选还能用。";
    public static final String NOTE = "不要重试要用调试口的操作。能只靠读屏和点选做完就继续；否则调用 finish（status=partial），"
            + "告诉用户到 设置 → 账号与安全 里打开“ADB 调试”，打开后说“星仪继续”。";

    /** Set by the host app: tries to bring the listener back, true when it answers again. Null where nothing can. */
    public static volatile BooleanSupplier healer;

    public AdbUnavailable(Throwable cause) { super(MESSAGE, cause); }

    /** A refused connection to the local listener means it is gone; timeouts and protocol errors are something else. */
    public static IOException classify(IOException e) { return e instanceof ConnectException ? new AdbUnavailable(e) : e; }

    /** Asks the host to bring the listener back; never throws. */
    public static boolean heal() {
        BooleanSupplier h = healer;
        if (h == null) return false;
        try { return h.getAsBoolean(); } catch (RuntimeException e) { return false; }
    }

    /** What 星仪 says when it gives up because of this (spoken, so no arrows). */
    public static String reply(boolean resumable) {
        return "电视的 ADB 调试被关掉了，我现在按不了键、截不了图。请到设置里的账号与安全，把 ADB 调试打开"
                + (resumable ? "，打开后说“星仪继续”。" : "，打开后再说一遍。");
    }
}
