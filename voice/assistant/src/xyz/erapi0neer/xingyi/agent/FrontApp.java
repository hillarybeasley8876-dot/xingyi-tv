package xyz.erapi0neer.xingyi.agent;

/**
 * 哪个包算「用户真正在的应用」。小爱同学自己的两个悬浮窗（2026-11-08 用 appops 藏起来了，{@code mAppOpVisibility=false}，
 * 但照样发无障碍事件）在用户说每一句话时都会发 {@code TYPE_WINDOW_STATE_CHANGED}，而它后面的应用不会再发一次，
 * 于是模型每次都被告知「你在小爱同学里」（2026-11-09 20:41 的记录：用户明明在网易云）。
 *
 * <p>判据只有两条：这个包是不是悬浮窗（{@link MemoryCare#OVERLAYS}，小爱、唤醒服务、系统 UI、"android"），
 * 以及这次事件是不是它真正的全屏 activity（比如小爱的视频推荐页——那是要用 BACK 关掉的，必须继续看得见）。
 */
public final class FrontApp {
    private FrontApp() { }

    /** 这个包这次算不算前台：悬浮窗包只有真的是 activity 时才算；我们自己的包永远不算。 */
    public static boolean counts(String pkg, boolean activity, String own) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(own)) return false;
        return !MemoryCare.OVERLAYS.contains(pkg) || activity;
    }

    /** {@code dumpsys window} 的 {@code mFocusedApp} 里的包名；读不出来或不是 {@code 包/类} 时空串。 */
    public static String pkg(String focused) {
        int slash = slash(focused);
        return slash < 0 ? "" : focused.substring(0, slash);
    }

    /** {@code mFocusedApp} 里的 activity 类名；同上。 */
    public static String cls(String focused) {
        int slash = slash(focused);
        return slash < 0 ? "" : focused.substring(slash + 1);
    }

    /** 中间那个斜杠的位置；两边都得有字，否则 -1。 */
    private static int slash(String focused) {
        if (focused == null || focused.isEmpty()) return -1;
        int i = focused.indexOf('/');
        return i <= 0 || i >= focused.length() - 1 ? -1 : i;
    }
}
