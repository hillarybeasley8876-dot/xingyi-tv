package xyz.erapi0neer.xingyi.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网易云「随心唱」现在是开是关，从播放页的屏幕文字里读出来（2026-11-09 17:25 在电视上看到的样子）。
 *
 * <p>播放页（PlayerActivityV2）上，进度条上方右边有一个麦克风图标；焦点落在它上面时，旁边有一行字：
 * 「开启随心唱」是把它打开的那个按钮上的字，也就是<b>现在是关着的</b>；开着的时候那行字变成「按 上 键调节人声」。
 * 把「开启随心唱」读成「已经开了」，就是那天它答「已经是伴奏模式了」的原因。
 */
public final class Karaoke {
    /** 麦克风图标在右边（@94,73），控制栏那一排在 @…,89：焦点不在这一块就不是它。 */
    private static final int RIGHT_FROM = 60, ICON_ROW_MAX = 85;
    /** 一行元素：{@code [3] 图标 焦点 @94,73}。 */
    private static final Pattern LINE = Pattern.compile("^\\[\\d+\\] ([^「\\s]+)(?:「([^」]*)」)?(.*)$");
    private static final Pattern AT = Pattern.compile("@(\\d+),(\\d+)");
    /** 开着时旁边写的字，和关着时旁边写的字。 */
    public static final String ON_LABEL = "调节人声", OFF_LABEL = "开启随心唱";

    private Karaoke() { }

    /**
     * 随心唱现在是开着（伴奏）还是关着（原唱）：TRUE / FALSE；读不出来（不是播放页、焦点不在麦克风上、
     * 旁边是别的字）时 null —— 宁可说不知道，也不要按错一次。
     */
    public static Boolean state(String screenDigestText) {
        if (screenDigestText == null || screenDigestText.isEmpty()) return null;
        String[] lines = screenDigestText.split("\n");
        if (!playerPage(lines[0])) return null;
        List<Row> rows = new ArrayList<>();
        for (String line : lines) {
            Row r = row(line);
            if (r != null) rows.add(r);
        }
        Row icon = micIcon(rows);
        if (icon == null) return null;
        Boolean own = reading(icon.label);
        if (own != null) return own;
        for (Row r : rows) {
            if (r == icon || !r.placed || r.x > icon.x || Math.abs(r.y - icon.y) > 4) continue;
            Boolean beside = reading(r.label);
            if (beside != null) return beside;
        }
        return null;
    }

    /** 这一行是不是网易云的播放页；括号里没写页面时按调用方已经核对过处理（Eyes 偶尔读不到类名）。 */
    static boolean playerPage(String head) {
        if (head == null || !head.startsWith("前台：") || !head.contains(PlayerPages.NETEASE)) return false;
        int open = head.indexOf('（'), close = head.lastIndexOf('）');
        if (open < 0 || close < open) return true;
        return PlayerPages.isPlayer(PlayerPages.NETEASE, head.substring(open + 1, close));
    }

    /**
     * 「播放页不在前台」那句话，说清楚怎么回到播放页。2026-11-09 20:11 的「切换到伴奏模式」走了 105 秒 11 轮：
     * 模型按原来那句提示去 open_app 网易云音乐，结果打开的是网易云<b>首页</b>，退回去又从头放了一遍歌。
     * 所以这里只让它用 netease_play 重开这一首，并且明说 open_app 打开的是首页。
     *
     * <p>2026-11-10 起这句话是最后一招：网易云在放或暂停着的时候，{@code netease_karaoke} 先用
     * {@code nmcloudmusictv://ng/player} 把这一首的播放页叫回来（0.15–0.2 秒，位置不丢），叫不回来才说这句。
     *
     * @param where 现在前台是什么（应用名，必要时带页面名）
     * @param title 网易云在放的那首歌；空串表示网易云没有在放歌
     * @param artist 歌手；空串就不写 artist=
     */
    public static String notInFront(String where, String title, String artist) {
        String at = where == null || where.isEmpty() ? "别的应用" : where;
        if (title == null || title.isEmpty())
            return "网易云没有在放歌（现在是" + at + "）：先用 netease_play 放一首歌，再切伴奏或原唱";
        String who = artist == null || artist.isEmpty() ? "" : "，artist=" + artist;
        return "网易云的播放页没能回到前台（现在是" + at + "）。网易云在放《" + title + "》：用 netease_play（title="
                + title + who + "）重新打开这首歌的播放页（会从头放），再切伴奏或原唱；不要用 open_app，它打开的是网易云首页";
    }

    /** 屏幕上焦点那一行（角色和文字），给「没读出状态」那句话用；没有焦点就返回空串。 */
    public static String focus(String screenDigestText) {
        if (screenDigestText == null) return "";
        for (String line : screenDigestText.split("\n")) {
            Row r = row(line);
            if (r != null && r.focused()) return r.label.isEmpty() ? r.role : "「" + r.label + "」";
        }
        return "";
    }

    /** 焦点在右边、进度条上方的那个图标；焦点在控制栏（@…,89）上或没有焦点时 null。 */
    private static Row micIcon(List<Row> rows) {
        for (Row r : rows)
            if (r.placed && r.focused() && "图标".equals(r.role) && r.x >= RIGHT_FROM && r.y <= ICON_ROW_MAX) return r;
        return null;
    }

    /** 这行字说的状态：调节人声 = 开着，开启随心唱 = 关着，别的字 null。 */
    private static Boolean reading(String label) {
        if (label == null || label.isEmpty()) return null;
        if (label.contains(ON_LABEL)) return Boolean.TRUE;
        if (label.contains(OFF_LABEL)) return Boolean.FALSE;
        return null;
    }

    private static Row row(String line) {
        Matcher m = LINE.matcher(line);
        if (!m.matches()) return null;
        String flags = m.group(3) == null ? "" : m.group(3);
        Matcher at = AT.matcher(flags);
        boolean placed = at.find();
        return new Row(m.group(1), m.group(2) == null ? "" : m.group(2), flags,
                placed ? Integer.parseInt(at.group(1)) : -1, placed ? Integer.parseInt(at.group(2)) : -1, placed);
    }

    private static final class Row {
        final String role, label, flags;
        final int x, y;
        final boolean placed;
        Row(String role, String label, String flags, int x, int y, boolean placed) {
            this.role = role; this.label = label; this.flags = flags; this.x = x; this.y = y; this.placed = placed;
        }
        boolean focused() { return flags.contains("焦点"); }
    }
}
