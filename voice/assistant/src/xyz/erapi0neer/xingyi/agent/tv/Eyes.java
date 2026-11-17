package xyz.erapi0neer.xingyi.agent.tv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import xyz.erapi0neer.xingyi.agent.FrontApp;
import xyz.erapi0neer.xingyi.agent.ScreenDigest;

/** The agent's eyes and hands inside other apps: reads the focused window, clicks, types, scrolls,
 * and hosts the progress panel as an accessibility overlay (no overlay permission needed). */
public final class Eyes extends AccessibilityService {
    private static volatile Eyes instance;
    private static final AtomicLong changes = new AtomicLong();
    private static volatile long lastChangeAt;
    private static volatile String foregroundPackage = "", foregroundClass = "";
    /** uptime when foregroundClass last changed: a stale class (an overlay took the window since) is older than the event at hand. */
    private static volatile long foregroundClassAt;
    /** "pkg/cls" -> is it a real activity; at most {@link #ACTIVITIES_MAX} entries, cleared when full. */
    private static final Map<String, Boolean> activities = new HashMap<>();
    private static final int ACTIVITIES_MAX = 64;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Hud hud;

    public static Eyes get() { return instance; }

    @Override protected void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_FOCUSED | AccessibilityEvent.TYPE_VIEW_SCROLLED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            info.notificationTimeout = 60;
            setServiceInfo(info);
        }
        hud = new Hud(this);
        instance = this;
        AgentEngine.eyesConnected(this);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg != null && pkg.toString().equals(getPackageName())) return;
        changes.incrementAndGet();
        lastChangeAt = android.os.SystemClock.uptimeMillis();
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null) {
            String p = pkg.toString();
            CharSequence name = event.getClassName();
            String cls = name == null ? "" : name.toString();
            // XiaoAi's own hidden overlays send this event on every spoken sentence while the app behind stays quiet:
            // only a real activity of an overlay package (its video page) may become "the app in front".
            boolean activity = isActivity(p, cls);
            if (FrontApp.counts(p, activity, getPackageName())) {
                foregroundPackage = p;
                if (activity || looksLikeScreen(cls)) {
                    foregroundClass = cls;
                    foregroundClassAt = lastChangeAt;
                }
            }
        }
    }

    /** Is this class a real activity of that package? Asked of the package manager, once per class: these events come
     * several times a second and this runs on the main thread. */
    private boolean isActivity(String pkg, String cls) {
        if (cls.isEmpty()) return false;
        String key = pkg + "/" + cls;
        synchronized (activities) {
            Boolean known = activities.get(key);
            if (known != null) return known.booleanValue();
        }
        boolean found = false;
        try { getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0); found = true; }
        catch (Exception ignored) { }
        synchronized (activities) {
            if (activities.size() >= ACTIVITIES_MAX) activities.clear();
            activities.put(key, found);
        }
        return found;
    }

    private static boolean looksLikeScreen(String cls) {
        return cls.endsWith("Activity") || cls.contains(".activity.") || cls.contains("Activity$");
    }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        if (hud != null) hud.dispose();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        instance = null;
        if (hud != null) hud.dispose();
        super.onDestroy();
    }

    public Hud hud() { return hud; }

    public static long changeCount() { return changes.get(); }
    public static long lastChangeAt() { return lastChangeAt; }
    public static String foregroundPackage() { return foregroundPackage; }
    public static String foregroundClass() { return foregroundClass; }
    public static long foregroundClassAt() { return foregroundClassAt; }

    /** The package of the window that is active right now, read fresh (not the last event): "" when unreadable.
     * The bridge closes XiaoAi's own answer card with BACK only when this says the card is what BACK would close. */
    public String activeWindowPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        CharSequence p = root == null ? null : root.getPackageName();
        return p == null ? "" : p.toString();
    }

    // ---- reading -----------------------------------------------------------------------------

    /** Tree of the active window (never our own overlay), or null when nothing is readable. */
    public ScreenDigest.Node capture() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        int[] budget = {1500};
        ScreenDigest.Node tree = build(root, 0, budget);
        if (root.getPackageName() != null) {
            String pkg = root.getPackageName().toString();
            // an overlay's own window is never taken from a capture either (see FrontApp)
            if (FrontApp.counts(pkg, false, getPackageName()) && !pkg.equals(foregroundPackage)) foregroundPackage = pkg;
        }
        return tree;
    }

    public static String rootPackage(ScreenDigest.Node root) { return root == null ? "" : root.pkg; }

    private static ScreenDigest.Node build(AccessibilityNodeInfo n, int depth, int[] budget) {
        ScreenDigest.Node x = new ScreenDigest.Node();
        budget[0]--;
        x.cls = str(n.getClassName());
        x.text = str(n.getText());
        x.desc = str(n.getContentDescription());
        x.viewId = str(n.getViewIdResourceName());
        x.pkg = str(n.getPackageName());
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        x.left = r.left; x.top = r.top; x.right = r.right; x.bottom = r.bottom;
        x.clickable = n.isClickable(); x.focusable = n.isFocusable(); x.focused = n.isFocused();
        x.selected = n.isSelected(); x.checkable = n.isCheckable(); x.checked = n.isChecked();
        x.editable = n.isEditable(); x.scrollable = n.isScrollable(); x.longClickable = n.isLongClickable();
        x.password = n.isPassword(); x.enabled = n.isEnabled(); x.visible = n.isVisibleToUser();
        if (x.text.isEmpty() && x.editable && android.os.Build.VERSION.SDK_INT >= 26) x.desc = str(n.getHintText());
        x.handle = n;
        if (depth < 40) {
            for (int i = 0; i < n.getChildCount() && budget[0] > 0; i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) x.children.add(build(c, depth + 1, budget));
            }
        }
        return x;
    }

    private static String str(CharSequence s) { return s == null ? "" : s.toString(); }

    // ---- acting ------------------------------------------------------------------------------

    /** Clicks the node or its nearest clickable ancestor. */
    public static boolean click(ScreenDigest.Node node) {
        AccessibilityNodeInfo n = handle(node);
        for (int i = 0; n != null && i < 6; i++) {
            if (n.isClickable() && n.isEnabled()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            n = n.getParent();
        }
        return false;
    }

    public static boolean focus(ScreenDigest.Node node) {
        AccessibilityNodeInfo n = handle(node);
        for (int i = 0; n != null && i < 6; i++) {
            if (n.isFocusable()) return n.isFocused() || n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            n = n.getParent();
        }
        return false;
    }

    /** Replaces the text of an editable node; true only when the node reports the new text. */
    public static boolean setText(ScreenDigest.Node node, String text) {
        AccessibilityNodeInfo n = handle(node);
        if (n == null || !n.isEditable()) return false;
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false;
        n.refresh();
        CharSequence now = n.getText();
        return now != null && now.toString().contains(text);
    }

    public static boolean scroll(ScreenDigest.Node node, boolean forward) {
        AccessibilityNodeInfo n = handle(node);
        for (int i = 0; n != null && i < 6; i++) {
            if (n.isScrollable()) return n.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            n = n.getParent();
        }
        return false;
    }

    public boolean back() { return performGlobalAction(GLOBAL_ACTION_BACK); }
    public boolean home() { return performGlobalAction(GLOBAL_ACTION_HOME); }

    private static AccessibilityNodeInfo handle(ScreenDigest.Node node) {
        return node != null && node.handle instanceof AccessibilityNodeInfo ? (AccessibilityNodeInfo) node.handle : null;
    }

    void onMain(Runnable r) { main.post(r); }
}
