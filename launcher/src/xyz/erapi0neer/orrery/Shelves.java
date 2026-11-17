package xyz.erapi0neer.orrery;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The home's entries sorted onto shelves (2026-11-06: 「ui还是太乱了 没有分类 收纳 太简约了有点」「安装的应用也不知道在哪找」):
 * 常用, 影音, 游戏 (only when one is installed), 信号源, 工具, 全部. UP/DOWN pick a shelf, LEFT/RIGHT walk it; the other
 * shelves stay folded. Plain Java, so tools/ShelvesCheck.java runs it on the PC.
 */
final class Shelves {
    private Shelves() { }

    /** One thing the home can open. pkg is null for the HDMI inputs and for the shelf-openers. */
    static final class Item {
        final String key, label, pkg;
        /** lead | other | hdmi | tool | store | media | settings | shelf */
        final String group;
        /** ApplicationInfo.category (-1: none). */
        final int appCategory;
        final long installedAt;
        /** A shelf-opener's own state line (HDMI 1　·　2　·　3, 15 个　·　1 个新装); "" for everything else. */
        final String line;
        Item(String key, String label, String pkg, String group, int appCategory, long installedAt) {
            this(key, label, pkg, group, appCategory, installedAt, "");
        }
        Item(String key, String label, String pkg, String group, int appCategory, long installedAt, String line) {
            this.key = key; this.label = label; this.pkg = pkg; this.group = group;
            this.appCategory = appCategory; this.installedAt = installedAt; this.line = line;
        }
    }

    static final class Shelf {
        final String name;
        final List<Item> items;
        Shelf(String name, List<Item> items) { this.name = name; this.items = items; }
    }

    /** Installed in the last three days: marked 新 and first on 全部. */
    static final long NEW_MS = 3L * 24 * 3600 * 1000;
    /** 常用 holds at most this many apps; what is not an app fills the rest of COMMON_FILL. */
    static final int COMMON_MAX = 6, COMMON_FILL = 8;
    /** Opened from the home this often, an app joins 常用. */
    static final int COMMON_LAUNCHES = 3;
    /** An entry that opens another shelf: its key is this and the shelf's name (2026-11-08, 常用 fills the band). */
    static final String SHELF_KEY = "shelf:";

    static boolean isShelf(Item i) { return "shelf".equals(i.group); }
    /** The shelf a shelf-opener leads to, or null. */
    static String shelfOf(Item i) { return isShelf(i) ? i.key.substring(SHELF_KEY.length()) : null; }

    static Item shelfItem(String shelf, String label, String line) {
        return new Item(SHELF_KEY + shelf, label, null, "shelf", -1, 0, line);
    }

    /** What 常用 starts with before anything was counted: the daily ones. */
    static final List<String> COMMON = Arrays.asList("xyz.erapi0neer.xingyi", "com.netease.cloudmusic.tv", "com.ktcp.video",
            "tv.danmaku.bili");
    /** The TV builds people watch and listen with (the store's licence names hide what they are). */
    static final Set<String> MEDIA = new HashSet<>(Arrays.asList("tv.danmaku.bili", "com.ktcp.video", "com.netease.cloudmusic.tv",
            "com.gitvdemo.video", "com.xiaodianshi.tv.yst", "com.hunantv.license", "com.kwai.tv.yst", "com.sohuott.tv.vod",
            "cn.miguvideo.migutv", "com.newtv.cboxtv", "com.tencent.qqmusictv", "com.kugou.android.tv", "com.tencent.karaoketv",
            "com.fmxos.app.smarttv", "com.letv.tv", "cn.com.wasu.main", "com.vcinema.client.tv", "com.bestv.ott.baseservices",
            "com.dangbei.dbmusic", "com.changba.sd", "com.mitv.gallery", "com.youku.taitan.tv", "com.cibn.tv"));
    /** Casting is how a phone becomes an input: it sits with HDMI. */
    static final Set<String> CAST = new HashSet<>(Arrays.asList("com.xiaomi.mitv.smartshare", "com.hpplay.happyplay.aw"));
    static final Pattern MEDIA_WORDS = Pattern.compile("视频|影视|电影|音乐|K歌|直播|动漫|追剧|影院|听书|电台");
    static final Pattern GAME_WORDS = Pattern.compile("游戏|斗地主|麻将|象棋|围棋");
    /** ApplicationInfo.CATEGORY_GAME / AUDIO / VIDEO / IMAGE. */
    static final int GAME = 0, AUDIO = 1, VIDEO = 2, IMAGE = 3;

    /** What an entry is, in a word, under its name. */
    static final Map<String, String> KIND = new HashMap<>();
    static {
        KIND.put("xyz.erapi0neer.xingyi", "语音助手");
        KIND.put("com.netease.cloudmusic.tv", "音乐");
        KIND.put("com.ktcp.video", "影视");
        KIND.put("tv.danmaku.bili", "视频");
        KIND.put("com.mitv.gallery", "照片");
        KIND.put("com.xiaomi.mitv.smartshare", "手机投屏");
        KIND.put("com.xiaomi.mitv.tvmanager", "清理与加速");
        KIND.put("com.xiaomi.mitv.appstore", "下载应用");
        KIND.put("com.xiaomi.mitv.mediaexplorer", "U盘与文件");
    }

    static boolean isNew(Item i, long now) { return i.installedAt > 0 && now - i.installedAt >= 0 && now - i.installedAt < NEW_MS; }

    static boolean media(Item i) {
        if (i.pkg == null) return false;
        if (MEDIA.contains(i.pkg)) return true;
        if (i.appCategory == AUDIO || i.appCategory == VIDEO || i.appCategory == IMAGE) return true;
        return "other".equals(i.group) && MEDIA_WORDS.matcher(i.label).find();
    }

    static boolean game(Item i) {
        return i.pkg != null && (i.appCategory == GAME || ("other".equals(i.group) && GAME_WORDS.matcher(i.label).find()));
    }

    static boolean input(Item i) { return "hdmi".equals(i.group) || (i.pkg != null && CAST.contains(i.pkg)); }

    /** The word under an entry's name: what it is, and 新安装 for three days. */
    static String kind(Item i, long now) {
        if (isShelf(i)) return i.line;
        String k = i.pkg != null ? KIND.get(i.pkg) : null;
        if (k == null) {
            if ("hdmi".equals(i.group)) k = "外接信号源";
            else if ("settings".equals(i.group)) k = "系统设置";
            else if (game(i)) k = "游戏";
            else if (media(i)) k = "影音";
            else k = "应用";
        }
        return isNew(i, now) ? k + "　·　新安装" : k;
    }

    /**
     * @param all the home's entries in today's order (lead, others by name, HDMI, the TV's tools, store, U盘, settings)
     * @param launches how often each key was opened from the home
     */
    static List<Shelf> build(List<Item> all, Map<String, Integer> launches, long now) {
        List<Item> common = new ArrayList<>(), media = new ArrayList<>(), games = new ArrayList<>(), inputs = new ArrayList<>(),
                tools = new ArrayList<>();
        for (Item i : all) {
            if (input(i)) inputs.add(i);
            else if (game(i)) games.add(i);
            else if (media(i)) media.add(i);
            else if (!"lead".equals(i.group)) tools.add(i);
        }
        // 常用: the daily ones that are installed, then what is opened most, at most COMMON_MAX, then the not-apps
        for (String pkg : COMMON) for (Item i : all) if (pkg.equals(i.pkg)) common.add(i);
        List<Item> often = new ArrayList<>();
        for (Item i : all) {
            Integer n = launches == null ? null : launches.get(i.key);
            if (n != null && n >= COMMON_LAUNCHES && !common.contains(i) && !"settings".equals(i.group)) often.add(i);
        }
        Collections.sort(often, (a, b) -> launches.get(b.key) - launches.get(a.key));
        for (Item i : often) if (common.size() < COMMON_MAX) common.add(i);
        // lead apps that are not media (星仪) stay on 常用 only; 全部 keeps everything, the newest installs first
        List<Item> everything = new ArrayList<>(all);
        final long t = now;
        Collections.sort(everything, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                boolean na = isNew(a, t), nb = isNew(b, t);
                if (na != nb) return na ? -1 : 1;
                if (na) return Long.compare(b.installedAt, a.installedAt);
                return all.indexOf(a) - all.indexOf(b);
            }
        });
        fill(common, inputs, everything, now);
        List<Shelf> out = new ArrayList<>();
        add(out, "常用", common);
        add(out, "影音", media);
        add(out, "游戏", games);
        add(out, "信号源", inputs);
        add(out, "工具", tools);
        add(out, "全部", everything);
        return out;
    }

    /**
     * What is not an app, up to COMMON_FILL: 信号源 (the shelf), 无线投屏 (the cast entry), 全部应用 (the shelf), 设置.
     * Only what is there to open: a TV without an input has no 信号源, one without anything installed no 全部.
     */
    static void fill(List<Item> common, List<Item> inputs, List<Item> everything, long now) {
        if (common.size() >= COMMON_FILL) return;
        if (!inputs.isEmpty()) add(common, shelfItem("信号源", "信号源", signals(inputs)));
        for (Item i : inputs) if (i.pkg != null && CAST.contains(i.pkg)) add(common, i);
        if (!everything.isEmpty()) add(common, shelfItem("全部", "全部应用", allApps(everything, now)));
        for (Item i : everything) if ("settings".equals(i.group)) add(common, i);
    }

    /** Adds an entry once, while there is room: the cast entry can already be there from the launches. */
    static void add(List<Item> common, Item i) {
        if (common.size() < COMMON_FILL && !common.contains(i)) common.add(i);
    }

    /** The 信号源 opener's line: the HDMI inputs, the first one whole (HDMI 1　·　2　·　3), else the inputs' own names. */
    static String signals(List<Item> inputs) {
        List<String> parts = new ArrayList<>();
        for (Item i : inputs) if ("hdmi".equals(i.group)) parts.add(i.label.trim());
        for (int k = 1; k < parts.size(); k++) parts.set(k, parts.get(k).replaceAll("^HDMI\\s*", ""));
        if (parts.isEmpty()) for (Item i : inputs) parts.add(i.label.trim());
        return join(parts);
    }

    /** The 全部应用 opener's line: how many entries, and how many of them are new (15 个　·　1 个新装). */
    static String allApps(List<Item> everything, long now) {
        int fresh = 0;
        for (Item i : everything) if (isNew(i, now)) fresh++;
        String s = everything.size() + " 个";
        return fresh == 0 ? s : s + SEP + fresh + " 个新装";
    }

    /** The parts of a line, with the separator between them (Shelves compiles alone, so its own SEP). */
    static final String SEP = "　·　";

    static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (b.length() > 0) b.append(SEP);
            b.append(p);
        }
        return b.toString();
    }

    private static void add(List<Shelf> out, String name, List<Item> items) {
        if (!items.isEmpty()) out.add(new Shelf(name, items));
    }

    /** Others by name, as the home sorts them: Chinese collation. */
    static Comparator<Item> byName() {
        final Collator zh = Collator.getInstance(Locale.CHINA);
        return (a, b) -> zh.compare(a.label, b.label);
    }
}
