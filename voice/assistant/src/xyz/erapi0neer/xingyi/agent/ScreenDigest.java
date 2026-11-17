package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns a UI tree (from the accessibility service or a uiautomator dump) into the numbered element list the
 * model reads. Interactive nodes absorb the text of their non-interactive descendants, so a card reads as one
 * element; free-standing text is listed separately. Numbers are valid until the next digest. */
public final class ScreenDigest {
    public static final class Node {
        public String cls = "", text = "", desc = "", viewId = "", pkg = "";
        public int left, top, right, bottom;
        public boolean clickable, focusable, focused, selected, checkable, checked, editable, scrollable,
                longClickable, password, enabled = true, visible = true;
        public final List<Node> children = new ArrayList<>();
        /** Platform node used to act on this element; null for dumps. */
        public Object handle;

        boolean interactive() { return clickable || focusable || editable || checkable || scrollable || longClickable; }
        int width() { return right - left; }
        int height() { return bottom - top; }
        public int centerX() { return (left + right) / 2; }
        public int centerY() { return (top + bottom) / 2; }
    }

    public static final class Element {
        public final int id;
        public final Node node;
        public final String role, label;
        public final List<String> flags;
        Element(int id, Node node, String role, String label, List<String> flags) {
            this.id = id; this.node = node; this.role = role; this.label = label; this.flags = flags;
        }
        public String line(int width, int height) {
            StringBuilder s = new StringBuilder("[").append(id).append("] ").append(role);
            if (!label.isEmpty()) s.append("「").append(label).append("」");
            for (String f : flags) s.append(' ').append(f);
            if (width > 0 && height > 0) {
                s.append(" @").append(Math.min(99, Math.max(0, node.centerX() * 100 / width)))
                        .append(',').append(Math.min(99, Math.max(0, node.centerY() * 100 / height)));
            }
            return s.toString();
        }
    }

    public final String app, activity;
    public final List<Element> elements = new ArrayList<>();
    public final int width, height, hidden;
    /** Descriptions shared by many text-less nodes; dropped from labels. */
    private final Set<String> noise = new HashSet<>();

    public static final int MAX_ELEMENTS = 60;
    private static final int MAX_LABEL = 56;

    public static ScreenDigest of(Node root, String app, String activity) {
        return new ScreenDigest(root, app, activity);
    }

    private ScreenDigest(Node root, String app, String activity) {
        this.app = app == null ? "" : app;
        this.activity = activity == null ? "" : activity;
        width = root == null ? 0 : Math.max(1, root.right);
        height = root == null ? 0 : Math.max(1, root.bottom);
        if (root != null) {
            // A description that many text-less nodes share says nothing (MiTV settings: every menu icon is
            // described "电视设置", which made each item read "电视设置 · 网络设置").
            Map<String, Integer> counts = new HashMap<>();
            countDescriptions(root, counts);
            for (Map.Entry<String, Integer> e : counts.entrySet()) if (e.getValue() >= 3) noise.add(e.getKey());
        }
        List<Element> all = new ArrayList<>();
        if (root != null) collect(root, all, new IdentityHashMap<Node, Boolean>());
        dedupe(all);
        Collections.sort(all, (a, b) -> {
            int ra = a.node.top / 24, rb = b.node.top / 24;
            return ra != rb ? Integer.compare(ra, rb) : Integer.compare(a.node.left, b.node.left);
        });
        List<Element> chosen = all;
        int dropped = 0;
        if (all.size() > MAX_ELEMENTS) {
            // Keep the focused element and every interactive one first, then text, in reading order.
            List<Element> keep = new ArrayList<>();
            for (Element e : all) if (e.flags.contains("焦点")) keep.add(e);
            for (Element e : all) if (keep.size() < MAX_ELEMENTS && !keep.contains(e) && !"文本".equals(e.role)) keep.add(e);
            for (Element e : all) if (keep.size() < MAX_ELEMENTS && !keep.contains(e)) keep.add(e);
            dropped = all.size() - keep.size();
            Set<Element> set = Collections.newSetFromMap(new IdentityHashMap<Element, Boolean>());
            set.addAll(keep);
            chosen = new ArrayList<>();
            for (Element e : all) if (set.contains(e)) chosen.add(e);
        }
        int id = 1;
        for (Element e : chosen) elements.add(new Element(id++, e.node, e.role, e.label, e.flags));
        hidden = dropped;
    }

    public Element element(int id) {
        return id >= 1 && id <= elements.size() ? elements.get(id - 1) : null;
    }

    public Element focused() {
        for (Element e : elements) if (e.flags.contains("焦点")) return e;
        return null;
    }

    /** The text block given to the model. */
    public String text() {
        StringBuilder s = new StringBuilder("前台：").append(app.isEmpty() ? "未知应用" : app);
        if (!activity.isEmpty()) s.append("（").append(activity).append("）");
        if (elements.isEmpty()) s.append("\n（没有读到界面元素：可能是视频画面、自绘界面或受保护画面，可以用 screen_look 看图）");
        for (Element e : elements) s.append('\n').append(e.line(width, height));
        if (hidden > 0) s.append("\n（另有 ").append(hidden).append(" 个元素未列出，可滚动或用 screen_look 查看）");
        return s.toString();
    }

    /** True when the screen contains the text (labels joined), ignoring spaces. */
    public boolean contains(String needle) {
        if (needle == null || needle.isEmpty()) return false;
        String n = needle.replace(" ", "");
        for (Element e : elements) if (e.label.replace(" ", "").contains(n)) return true;
        return false;
    }

    // ---- building ------------------------------------------------------------------------------

    private void collect(Node n, List<Element> out, Map<Node, Boolean> consumed) {
        if (!shown(n)) return;
        if (n.interactive()) {
            StringBuilder label = new StringBuilder(own(n));
            if (label.length() == 0) gather(n, label, consumed, true);
            List<String> flags = flags(n);
            String text = fit(label.toString());
            String role = role(n, text, label.toString().contains(" · "));
            if (text.isEmpty() && "图标".equals(role) && !n.viewId.isEmpty()) {
                String id = n.viewId.substring(n.viewId.indexOf('/') + 1);
                if (!id.matches("[a-z]{1,3}\\d*")) text = id;
            }
            if (!text.isEmpty() || n.focused || n.editable || n.scrollable || n.clickable) out.add(new Element(0, n, role, text, flags));
        } else if (!consumed.containsKey(n)) {
            String own = own(n);
            if (!own.isEmpty()) out.add(new Element(0, n, n.cls.endsWith("ImageView") ? "图片" : "文本", trim(own), flags(n)));
            else if (isVideo(n)) out.add(new Element(0, n, "视频画面", "", flags(n)));
        }
        for (Node c : n.children) collect(c, out, consumed);
    }

    /** Appends descendant text that belongs to this element; interactive descendants speak for themselves. */
    private void gather(Node n, StringBuilder label, Map<Node, Boolean> consumed, boolean top) {
        for (Node c : n.children) {
            if (!shown(c) || c.interactive()) continue;
            String t = own(c);
            if (!t.isEmpty() && label.length() < MAX_LABEL * 2) {
                if (label.length() > 0) label.append(" · ");
                label.append(t);
            }
            consumed.put(c, Boolean.TRUE);
            gather(c, label, consumed, false);
        }
    }

    private boolean shown(Node n) {
        if (!n.visible || n.width() <= 0 || n.height() <= 0) return false;
        if (width > 0 && height > 0 && (n.right <= 0 || n.bottom <= 0 || n.left >= width || n.top >= height)) return false;
        return true;
    }

    private String own(Node n) {
        String t = clean(n.text), d = clean(n.desc);
        if (noise.contains(d)) d = "";
        if (t.isEmpty()) return d;
        if (d.isEmpty() || t.contains(d) || d.contains(t)) return t;
        return t + " · " + d;
    }

    private static void countDescriptions(Node n, Map<String, Integer> counts) {
        String d = clean(n.desc);
        if (clean(n.text).isEmpty() && !d.isEmpty()) counts.put(d, counts.containsKey(d) ? counts.get(d) + 1 : 1);
        for (Node c : n.children) countDescriptions(c, counts);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\u00a0\\u3000]+", " ").replace("「", "“").replace("」", "”").trim();
    }

    private static String trim(String s) {
        return s.length() <= MAX_LABEL ? s : s.substring(0, MAX_LABEL - 1) + "…";
    }

    /** Shortens a card label by cutting its longest part, so short facts (play count, date, duration) survive:
     * "1958 · 【示例小镇 ｜示例】一段很长的示例视频标题，用来测试卡片截断，长度和原来的标题差不多 · 10月18日" rather than losing the date. */
    static String fit(String label) {
        if (label.length() <= MAX_LABEL) return label;
        String[] parts = label.split(" · ");
        if (parts.length < 2) return trim(label);
        int total = label.length();
        while (total > MAX_LABEL) {
            int longest = 0;
            for (int i = 1; i < parts.length; i++) if (parts[i].length() > parts[longest].length()) longest = i;
            String p = parts[longest];
            if (p.length() <= 13) break;
            int cut = Math.min(total - MAX_LABEL, p.length() - 12);
            String shorter = p.substring(0, p.length() - cut - 1) + "…";
            total -= p.length() - shorter.length();
            parts[longest] = shorter;
        }
        String out = String.join(" · ", parts);
        return out.length() <= MAX_LABEL + 8 ? out : trim(out);
    }

    private static List<String> flags(Node n) {
        List<String> f = new ArrayList<>(3);
        if (n.focused && (n.focusable || n.editable)) f.add("焦点");
        if (n.selected) f.add("已选");
        if (n.checkable) f.add(n.checked ? "已开" : "未开");
        if (n.editable) f.add("可输入");
        if (n.password) f.add("密码");
        if (n.scrollable) f.add("可滚动");
        if (!n.enabled) f.add("不可用");
        return f;
    }

    private static String role(Node n, String label, boolean aggregated) {
        String c = n.cls;
        if (n.editable || c.endsWith("EditText") || c.endsWith("SearchView")) return "文本框";
        if (n.checkable) return "开关";
        if (c.endsWith("Button") || c.endsWith("ImageButton")) return label.isEmpty() ? "图标" : "按钮";
        if (n.scrollable || c.endsWith("RecyclerView") || c.endsWith("ListView") || c.endsWith("GridView")
                || c.endsWith("ScrollView") || c.endsWith("ViewPager")) return "列表";
        if (c.endsWith("WebView")) return "网页";
        if (label.isEmpty()) return c.endsWith("ImageView") ? "图标" : isVideo(n) ? "视频画面" : "图标";
        if (c.endsWith("TabView") || c.contains("Tab")) return "标签";
        if (aggregated) return "卡片";
        return "选项";
    }

    private static boolean isVideo(Node n) {
        return (n.cls.endsWith("SurfaceView") || n.cls.endsWith("TextureView")) && n.width() >= 320 && n.height() >= 180;
    }

    /** Keeps one of several nested wrappers that render as the same thing in the same place:
     * the focused one, else the one with the more specific role. */
    private static void dedupe(List<Element> all) {
        for (int i = all.size() - 1; i >= 0; i--) {
            Element e = all.get(i);
            for (int j = 0; j < i; j++) {
                Element o = all.get(j);
                if (o.label.equals(e.label) && Math.abs(o.node.centerX() - e.node.centerX()) < 12
                        && Math.abs(o.node.centerY() - e.node.centerY()) < 12) {
                    boolean eFocus = e.flags.contains("焦点"), oFocus = o.flags.contains("焦点");
                    if ((eFocus && !oFocus) || (eFocus == oFocus && generic(o.role) && !generic(e.role))) all.set(j, e);
                    all.remove(i);
                    break;
                }
            }
        }
    }

    private static boolean generic(String role) { return "选项".equals(role) || "卡片".equals(role) || "文本".equals(role); }

    // ---- uiautomator dump ----------------------------------------------------------------------

    private static final Pattern TAG = Pattern.compile("<node\\b([^>]*?)(/?)>|</node>");
    private static final Pattern ATTR = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    private static final Pattern BOUNDS = Pattern.compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");

    /** Parses `uiautomator dump` XML. Returns a synthetic root spanning the first node's bounds. */
    public static Node parseDump(String xml) {
        Node root = null;
        Deque<Node> stack = new ArrayDeque<>();
        Matcher m = TAG.matcher(xml == null ? "" : xml);
        while (m.find()) {
            if (m.group(1) == null) { if (!stack.isEmpty()) stack.pop(); continue; }
            Node n = new Node();
            Matcher a = ATTR.matcher(m.group(1));
            while (a.find()) {
                String k = a.group(1), v = unescape(a.group(2));
                switch (k) {
                    case "text": n.text = v; break;
                    case "content-desc": n.desc = v; break;
                    case "class": n.cls = v; break;
                    case "resource-id": n.viewId = v; break;
                    case "package": n.pkg = v; break;
                    case "clickable": n.clickable = "true".equals(v); break;
                    case "focusable": n.focusable = "true".equals(v); break;
                    case "focused": n.focused = "true".equals(v); break;
                    case "selected": n.selected = "true".equals(v); break;
                    case "checkable": n.checkable = "true".equals(v); break;
                    case "checked": n.checked = "true".equals(v); break;
                    case "scrollable": n.scrollable = "true".equals(v); break;
                    case "long-clickable": n.longClickable = "true".equals(v); break;
                    case "password": n.password = "true".equals(v); break;
                    case "enabled": n.enabled = !"false".equals(v); break;
                    case "bounds": {
                        Matcher b = BOUNDS.matcher(v);
                        if (b.find()) {
                            n.left = Integer.parseInt(b.group(1)); n.top = Integer.parseInt(b.group(2));
                            n.right = Integer.parseInt(b.group(3)); n.bottom = Integer.parseInt(b.group(4));
                        }
                        break;
                    }
                    default:
                }
            }
            n.editable = n.cls.endsWith("EditText");
            if (stack.isEmpty()) {
                if (root == null) root = n; else root.children.add(n);
            } else stack.peek().children.add(n);
            if (m.group(2).isEmpty()) stack.push(n);
        }
        return root;
    }

    private static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos);").matcher(s);
        int last = 0;
        while (m.find()) {
            out.append(s, last, m.start());
            String e = m.group(1);
            if (e.startsWith("#x")) out.appendCodePoint(Integer.parseInt(e.substring(2), 16));
            else if (e.startsWith("#")) out.appendCodePoint(Integer.parseInt(e.substring(1)));
            else out.append("amp".equals(e) ? "&" : "lt".equals(e) ? "<" : "gt".equals(e) ? ">" : "quot".equals(e) ? "\"" : "'");
            last = m.end();
        }
        return out.append(s.substring(last)).toString();
    }
}
