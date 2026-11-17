package xyz.erapi0neer.xingyi.agent;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 电视自带的遥控服务（小米手机遥控用的那个，6095 端口）的按键名和它的回答怎么算成功。
 *
 * <p>为什么走它：星仪按的每个键以前都是本机 ADB 的 {@code input keyevent}，每次都要在这台 CPU 上起一个 Java 进程，
 * 约 0.85 秒才落地（2026-11-09 实测；「切换到伴奏模式」的七个键用了 9.5 秒）。这个服务
 * {@code GET /controller?action=keyevent&keycode=<名字>} 31–58 毫秒就回，键像遥控器按下去一样注入（窗口管理器记
 * {@code Virtual KeyEvent} DOWN，约 185 毫秒后 UP）。整条随心唱的路（down、right ×4、up）2.2 秒。
 */
public final class KeyNames {
    private KeyNames() { }

    /**
     * 这个键码在遥控服务里的名字；没有名字的键码返回 null（26 POWER 仍然走 {@code input keyevent}：它的调用方先看过
     * 电源状态，按错就是关机）。23 DPAD_CENTER 是服务里的 {@code enter}，就是确认键（2026-11-10 01:45 实测它能切换
     * 网易云随心唱的麦克风按钮，两次都在 1.2 秒内）。
     */
    public static String of(int keyCode) {
        switch (keyCode) {
            case 19: return "up";
            case 20: return "down";
            case 21: return "left";
            case 22: return "right";
            case 23: return "enter"; // DPAD_CENTER: 遥控服务的 enter 就是确认键
            case 66: return "enter";
            case 4: return "back";
            case 3: return "home";
            case 82: return "menu";
            case 24: return "volumeup";
            case 25: return "volumedown";
            default: return null;
        }
    }

    /**
     * 遥控服务的一次回答算不算按键成功：状态行是 200，而且正文里的 {@code status} 是 0。别的都算失败——空、null、
     * 没有正文、坏掉的 JSON、404 配一个 status 0 的正文（那不是这次按键的回执）。正文可以像实测的那样分几行。
     */
    public static boolean ok(String httpResponse) {
        if (httpResponse == null || httpResponse.isEmpty()) return false;
        int line = httpResponse.indexOf('\n');
        if (line < 0) return false;
        String[] status = httpResponse.substring(0, line).trim().split("\\s+");
        if (status.length < 2 || !status[0].startsWith("HTTP/") || !"200".equals(status[1])) return false;
        int body = bodyAt(httpResponse);
        if (body < 0) return false;
        String json = httpResponse.substring(body).trim();
        if (json.isEmpty()) return false;
        try {
            return new JSONObject(json).optInt("status", -1) == 0;
        } catch (JSONException e) {
            return false;
        }
    }

    /** 正文从哪开始（HTTP 头后面的空行之后）；没有这个空行就是 -1。 */
    private static int bodyAt(String reply) {
        int crlf = reply.indexOf("\r\n\r\n");
        if (crlf >= 0) return crlf + 4;
        int lf = reply.indexOf("\n\n");
        return lf >= 0 ? lf + 2 : -1;
    }
}
