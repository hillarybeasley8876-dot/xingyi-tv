package xyz.erapi0neer.orrery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** PC check for Shelves: sh tools/shelves_check.sh. The entries are the TV's on 2026-11-06. */
final class ShelvesCheck {
    static int failed;

    static void check(boolean ok, String what) {
        if (!ok) failed++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static String names(Shelves.Shelf s) {
        List<String> l = new ArrayList<>();
        for (Shelves.Item i : s.items) l.add(i.label);
        return String.join("、", l);
    }

    static Shelves.Shelf shelf(List<Shelves.Shelf> all, String name) {
        for (Shelves.Shelf s : all) if (s.name.equals(name)) return s;
        return null;
    }

    public static void main(String[] a) {
        long now = 1_793_900_000_000L, day = 24L * 3600 * 1000;
        List<Shelves.Item> tv = new ArrayList<>(Arrays.asList(
                new Shelves.Item("xyz.erapi0neer.xingyi", "星仪电视助手", "xyz.erapi0neer.xingyi", "lead", -1, now - 3 * day),
                new Shelves.Item("com.netease.cloudmusic.tv", "网易云音乐", "com.netease.cloudmusic.tv", "lead", -1, now - 80 * day),
                new Shelves.Item("com.ktcp.video", "云视听极光", "com.ktcp.video", "lead", -1, now - 44 * day),
                new Shelves.Item("tv.danmaku.bili", "哔哩哔哩", "tv.danmaku.bili", "other", -1, now - 2 * day),
                new Shelves.Item("hdmi1", "HDMI 1", null, "hdmi", -1, 0),
                new Shelves.Item("hdmi2", "HDMI 2", null, "hdmi", -1, 0),
                new Shelves.Item("hdmi3", "HDMI 3", null, "hdmi", -1, 0),
                new Shelves.Item("com.xiaomi.mitv.smartshare", "无线投屏", "com.xiaomi.mitv.smartshare", "tool", -1, now - 400 * day),
                new Shelves.Item("com.mitv.gallery", "相册", "com.mitv.gallery", "tool", -1, now - 400 * day),
                new Shelves.Item("com.xiaomi.mitv.tvmanager", "电视管家", "com.xiaomi.mitv.tvmanager", "tool", -1, now - 400 * day),
                new Shelves.Item("com.xiaomi.mitv.appstore", "应用商店", "com.xiaomi.mitv.appstore", "store", -1, now - 400 * day),
                new Shelves.Item("com.xiaomi.mitv.mediaexplorer", "U盘 / 媒体", "com.xiaomi.mitv.mediaexplorer", "media", -1, now - 400 * day),
                new Shelves.Item("settings", "设置", null, "settings", -1, 0)));
        List<Shelves.Shelf> s = Shelves.build(tv, new HashMap<String, Integer>(), now);
        List<String> order = new ArrayList<>();
        for (Shelves.Shelf x : s) order.add(x.name);
        check(order.equals(Arrays.asList("常用", "影音", "信号源", "工具", "全部")), "shelves, no 游戏 without a game: " + order);
        check(names(shelf(s, "常用")).equals("星仪电视助手、网易云音乐、云视听极光、哔哩哔哩、信号源、无线投屏、全部应用、设置"),
                "常用: " + names(shelf(s, "常用")));
        check(names(shelf(s, "影音")).equals("网易云音乐、云视听极光、哔哩哔哩、相册"), "影音: " + names(shelf(s, "影音")));
        check(names(shelf(s, "信号源")).equals("HDMI 1、HDMI 2、HDMI 3、无线投屏"), "信号源: " + names(shelf(s, "信号源")));
        check(names(shelf(s, "工具")).equals("电视管家、应用商店、U盘 / 媒体、设置"), "工具: " + names(shelf(s, "工具")));
        // 全部: everything; the two installed in the last three days first, newest first
        Shelves.Shelf all = shelf(s, "全部");
        check(all.items.size() == 13, "全部 has all 13: " + all.items.size());
        check(all.items.get(0).label.equals("哔哩哔哩") && all.items.get(1).label.equals("星仪电视助手"), "new installs first: " + names(all));
        check(Shelves.kind(tv.get(3), now).equals("视频　·　新安装"), "B站 is 视频, new: " + Shelves.kind(tv.get(3), now));
        check(Shelves.kind(tv.get(4), now).equals("外接信号源"), "HDMI is an input");
        check(Shelves.kind(tv.get(12), now).equals("系统设置"), "settings");

        // a new app nobody knows: by its store category, else 工具; a game opens the 游戏 shelf
        List<Shelves.Item> more = new ArrayList<>(tv);
        more.add(new Shelves.Item("com.quark.yun.tv", "夸克网盘", "com.quark.yun.tv", "other", -1, now - day / 2));
        more.add(new Shelves.Item("com.example.kara", "唱歌K歌", "com.example.kara", "other", -1, now - 30 * day));
        more.add(new Shelves.Item("cn.jj.tv", "JJ斗地主", "cn.jj.tv", "other", 0, now - 30 * day));
        more.add(new Shelves.Item("com.example.video", "某某TV", "com.example.video", "other", 2, now - 30 * day));
        List<Shelves.Shelf> m = Shelves.build(more, new HashMap<String, Integer>(), now);
        check(shelf(m, "游戏") != null && names(shelf(m, "游戏")).equals("JJ斗地主"), "a game opens 游戏");
        check(names(shelf(m, "工具")).contains("夸克网盘"), "an unknown app sits on 工具: " + names(shelf(m, "工具")));
        check(names(shelf(m, "影音")).contains("唱歌K歌") && names(shelf(m, "影音")).contains("某某TV"), "by words and by store category: " + names(shelf(m, "影音")));
        check(shelf(m, "全部").items.get(0).label.equals("夸克网盘"), "the newest install leads 全部");
        check(Shelves.kind(more.get(13), now).equals("应用　·　新安装"), "an unknown new app: " + Shelves.kind(more.get(13), now));

        // 常用 learns what is opened from the home, at most six apps; what is not an app fills the band to eight
        Map<String, Integer> launches = new HashMap<>();
        launches.put("hdmi1", 9);
        launches.put("com.mitv.gallery", 3);
        launches.put("com.xiaomi.mitv.tvmanager", 2);
        launches.put("settings", 20);
        Shelves.Shelf common = shelf(Shelves.build(tv, launches, now), "常用");
        check(names(common).equals("星仪电视助手、网易云音乐、云视听极光、哔哩哔哩、HDMI 1、相册、信号源、无线投屏"),
                "常用 learns, settings never: " + names(common));

        common(tv, now);
        openers(shelf(s, "常用"), now);

        System.out.println(failed == 0 ? "shelves: all passed" : "shelves: " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** 常用 is eight whenever there is that much to put there: the daily ones, then the fill. */
    static void common(List<Shelves.Item> tv, long now) {
        Shelves.Shelf four = shelf(Shelves.build(tv, new HashMap<String, Integer>(), now), "常用");
        check(four.items.size() == 8, "four daily apps installed: 常用 has eight: " + four.items.size());

        // six apps (the four daily ones and two that were opened often) still leave room for two of the fill
        Map<String, Integer> launched = new HashMap<>();
        launched.put("hdmi1", 9);
        launched.put("com.mitv.gallery", 4);
        Shelves.Shelf six = shelf(Shelves.build(tv, launched, now), "常用");
        check(six.items.size() == 8, "six apps on 常用: eight with the fill: " + six.items.size());
        check(names(six).endsWith("信号源、无线投屏"), "the fill keeps the mock-up's order: " + names(six));

        // a TV where none of the daily ones is installed: only what is not an app is left
        List<Shelves.Item> bare = new ArrayList<>();
        for (Shelves.Item i : tv) if (!Shelves.COMMON.contains(i.pkg)) bare.add(i);
        Shelves.Shelf none = shelf(Shelves.build(bare, new HashMap<String, Integer>(), now), "常用");
        check(names(none).equals("信号源、无线投屏、全部应用、设置"), "no daily app installed: 常用 is its fill: " + names(none));

        // a TV with no input at all: no 信号源 and no 无线投屏 to open
        List<Shelves.Item> noInput = new ArrayList<>();
        for (Shelves.Item i : tv) if (!Shelves.input(i)) noInput.add(i);
        Shelves.Shelf quiet = shelf(Shelves.build(noInput, new HashMap<String, Integer>(), now), "常用");
        check(names(quiet).equals("星仪电视助手、网易云音乐、云视听极光、哔哩哔哩、全部应用、设置"),
                "a TV without an input has no 信号源 on 常用: " + names(quiet));

        // a TV with nothing but its settings: the two openers are all there is
        List<Shelves.Item> only = new ArrayList<>();
        for (Shelves.Item i : tv) if ("settings".equals(i.group)) only.add(i);
        Shelves.Shelf lone = shelf(Shelves.build(only, new HashMap<String, Integer>(), now), "常用");
        check(names(lone).equals("全部应用、设置"), "nothing installed but 设置: " + names(lone));
        check(Shelves.kind(lone.items.get(0), now).equals("1 个"), "全部应用 counts one: " + Shelves.kind(lone.items.get(0), now));
    }

    /** The shelf-openers: their ids, and the line under their name. */
    static void openers(Shelves.Shelf common, long now) {
        Shelves.Item source = common.items.get(4), all = common.items.get(6);
        check(source.key.equals("shelf:信号源") && all.key.equals("shelf:全部"), "the openers' ids: " + source.key + ", " + all.key);
        check(Shelves.isShelf(source) && Shelves.isShelf(all) && !Shelves.isShelf(common.items.get(0)),
                "only the openers are of the shelf kind");
        check("信号源".equals(Shelves.shelfOf(source)) && "全部".equals(Shelves.shelfOf(all)), "the shelf each opener leads to");
        check(Shelves.shelfOf(common.items.get(0)) == null, "an app leads to no shelf");
        check(source.pkg == null && source.installedAt == 0 && !Shelves.isNew(source, now), "an opener is never an install");
        check(Shelves.kind(source, now).equals("HDMI 1　·　2　·　3"), "信号源's line: " + Shelves.kind(source, now));
        check(Shelves.kind(all, now).equals("13 个　·　1 个新装"), "全部应用's line: " + Shelves.kind(all, now));
        check(Shelves.signals(new ArrayList<Shelves.Item>()).equals(""), "no input, no line");
        List<Shelves.Item> cast = new ArrayList<>();
        for (Shelves.Item i : common.items) if (i.pkg != null && Shelves.CAST.contains(i.pkg)) cast.add(i);
        check(Shelves.signals(cast).equals("无线投屏"), "a TV with no HDMI names its one input: " + Shelves.signals(cast));
    }
}
