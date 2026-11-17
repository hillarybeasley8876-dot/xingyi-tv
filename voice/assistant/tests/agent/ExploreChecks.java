package xyz.erapi0neer.xingyi.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** 星仪 finds out what an app can do by itself (AppIntents, AppNotes, ExploreTools): an app's own deep links read out
 * of the TV's package dump, what 星仪 learned about an app by trying, and the two tools over them. */
final class ExploreChecks {
    private ExploreChecks() { }

    static void run() throws Exception {
        bili();
        otherApps();
        oddDumps();
        summaries();
        noteBook();
        noteSkills();
        exploreTool();
        noteTool();
        withoutAPlan();
    }

    /** A real `dumpsys package <pkg>` from the TV. */
    static String dump(String pkg) throws Exception {
        return new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/apps/" + pkg + ".txt")), StandardCharsets.UTF_8);
    }

    static JSONObject parsed(String pkg) throws Exception { return AppIntents.parse(dump(pkg), pkg); }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    static List<String> entries(JSONObject parsed, String scheme) {
        List<String> out = new ArrayList<>();
        JSONArray a = parsed.optJSONObject("links") == null ? null : parsed.optJSONObject("links").optJSONArray(scheme);
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    static List<String> schemes(JSONObject parsed) {
        JSONObject links = parsed.optJSONObject("links");
        return links == null ? new ArrayList<String>() : AppIntents.order(links);
    }

    /** The pages in an entry list: an entry with a slash is a path variant of the page before it. */
    static int pages(List<String> entries) {
        int n = 0;
        for (String e : entries) if (e.indexOf('/') < 0) n++;
        return n;
    }

    static boolean has(List<String> entries, String entry) { return entries.contains(entry); }

    static boolean anyStartsWith(List<String> entries, String prefix) {
        for (String e : entries) if (e.equals(prefix) || e.startsWith(prefix + "/")) return true;
        return false;
    }

    // ---- B站 -------------------------------------------------------------------------------------

    static void bili() throws Exception {
        JSONObject p = parsed("tv.danmaku.bili");
        List<String> own = entries(p, "bilibili"), web = entries(p, "https");
        check("8.91.1".equals(p.optString("version")), "B站's version comes out of the dump: " + p.optString("version"));
        check("tv.danmaku.bili".equals(p.optString("package")), "the package is echoed back");
        check(schemes(p).equals(Arrays.asList("bilibili", "abiliav", "https")),
                "its own schemes first, https last: " + schemes(p));
        check(has(own, "search"), "bilibili://search");
        check(has(own, "space"), "bilibili://space");
        check(has(own, "history"), "bilibili://history");
        check(has(own, "main/favorite"), "bilibili://main/favorite (a page's path)");
        check(has(own, "following"), "bilibili://following");
        check(has(own, "following/detail/…") && has(own, "article/readlist"), "paths are written with … and kept as they are");
        check(!anyStartsWith(own, "bilipay"), "the payment pages are gone");
        check(!anyStartsWith(own, "auth") && !anyStartsWith(own, "author"), "the login pages are gone");
        check(!anyStartsWith(own, "ad") && !anyStartsWith(own, "advertise"), "the ad pages are gone");
        check(!anyStartsWith(own, "debugger") && !anyStartsWith(own, "com.bilibili.push"), "debug and push pages are gone");
        for (String junk : new String[] {"fbconnect", "fb1158523682358502", "tencent100951776", "qwallet100951776",
                "aacbaaafec", "bilibili.oauth", "bili_alipay_result_act"}) {
            check(!schemes(p).contains(junk), "the " + junk + " scheme is dropped");
        }
        check(entries(p, "abiliav").equals(Arrays.asList("")), "a scheme without a host opens the app itself");
        check(has(web, "www.bilibili.com") && has(web, "www.bilibili.com/video/…"), "https keeps the web pages");
        check(!schemes(p).contains("http"), "http is merged into https");
        boolean star = false;
        for (String e : own) if (e.contains("*")) star = true;
        check(!star, "no raw pattern reaches the model");
        check(new HashSet<>(own).size() == own.size() && new HashSet<>(web).size() == web.size(), "entries are deduplicated");
        int paths = 0, worst = 0;
        for (String e : own) {
            paths = e.indexOf('/') < 0 ? 0 : paths + 1;
            worst = Math.max(worst, paths);
        }
        check(worst <= AppIntents.MAX_PATHS_PER_PAGE, "at most three paths per page: " + worst);
        check(pages(own) <= AppIntents.MAX_PAGES_PER_SCHEME && pages(web) <= AppIntents.MAX_PAGES_PER_SCHEME,
                "at most 60 pages per scheme: " + pages(own) + "/" + pages(web));
        int all = 0;
        for (String s : schemes(p)) all += pages(entries(p, s));
        check(all <= AppIntents.MAX_PAGES, "at most 80 pages in all: " + all);
        int count = 0;
        for (String s : schemes(p)) count += entries(p, s).size();
        check(p.optInt("count") == count && count > 80, "count is every entry: " + p.optInt("count"));
    }

    // ---- the other three apps on the TV ---------------------------------------------------------

    static void otherApps() throws Exception {
        JSONObject music = parsed("com.netease.cloudmusic.tv");
        check("1.2.10".equals(music.optString("version")), "NetEase's version: " + music.optString("version"));
        check(schemes(music).equals(Arrays.asList("nmcloudmusictv", "voice")), "NetEase's own schemes: " + schemes(music));
        check(entries(music, "voice").equals(Arrays.asList("netease.audioplayer")), "voice://netease.audioplayer");
        check(entries(music, "nmcloudmusictv").equals(Arrays.asList("")), "nmcloudmusictv:// opens the app");
        check(music.optInt("count") == 2, "and nothing else: " + music.optInt("count"));

        JSONObject video = parsed("com.ktcp.video");
        check("20.1.0.2015".equals(video.optString("version")), "云视听极光's version: " + video.optString("version"));
        check(schemes(video).equals(Arrays.asList("tenvideo2")), "only its own scheme is left: " + schemes(video));
        check(entries(video, "tenvideo2").equals(Arrays.asList("tvrecommendation", "tvrecommendation/video")),
                "tenvideo2://tvrecommendation and its /video page");
        check(video.optInt("count") == 2, "two entries: " + video.optInt("count"));

        JSONObject store = parsed("com.xiaomi.mitv.appstore");
        check("2.24.0".equals(store.optString("version")), "the app store's version: " + store.optString("version"));
        check(schemes(store).equals(Arrays.asList("market", "miwebcontent")), "the store's schemes: " + schemes(store));
        check(entries(store, "market").equals(Arrays.asList("appstore.mitv.xiaomi.com", "appstore.mitv.xiaomi.com/home",
                "appstore.mitv.xiaomi.com/details", "appstore.mitv.xiaomi.com/skill")), "market:// pages, three paths kept");
        check(entries(store, "miwebcontent").equals(Arrays.asList("game")), "miwebcontent://game");
        check(store.optInt("count") == 5, "five entries: " + store.optInt("count"));
    }

    // ---- dumps that are not what we hope for -----------------------------------------------------

    /** One scheme group of a hand-written dump. */
    static String group(String scheme, String component, String action, String host, String path) {
        StringBuilder b = new StringBuilder("      " + scheme + ":\n");
        b.append("        1a2b3c ").append(component).append(" filter 9z8y\n");
        b.append("          Action: \"").append(action).append("\"\n");
        b.append("          Scheme: \"").append(scheme).append("\"\n");
        if (host != null) b.append("          Authority: \"").append(host).append("\": -1\n");
        if (path != null) b.append("          Path: \"PatternMatcher{GLOB: ").append(path).append("}\"\n");
        return b.toString();
    }

    static final String VIEW = "android.intent.action.VIEW", MINE = "com.demo/.MainActivity", OTHER = "com.other/.MainActivity";

    static String handmade() {
        return "Activity Resolver Table:\n"
                + "  Schemes:\n"
                + group("demo", MINE, VIEW, "page", "/a/.*")
                + group("demo", MINE, "com.demo.OPEN", "notview", null)
                + group("demo", OTHER, VIEW, "otherpackage", null)
                + group("fb1", MINE, VIEW, "junk", null)
                + group("fbconnect", MINE, VIEW, "junk", null)
                + group("wx12345678", MINE, VIEW, "junk", null)
                + group("tencent1", MINE, VIEW, "junk", null)
                + group("qwallet9", MINE, VIEW, "junk", null)
                + group("alipay", MINE, VIEW, "junk", null)
                + group("myoauth", MINE, VIEW, "junk", null)
                + group("aacbaaafec", MINE, VIEW, "junk", null)
                + group("txdt0and0ymkjb42rak7", MINE, VIEW, "junk", null)
                + group("demo", MINE, VIEW, "paywall", null)
                + group("demo", MINE, VIEW, "debugger", null)
                + group("demo", MINE, VIEW, "push", null)
                + group("demo", MINE, VIEW, "ad", null)
                + "  Non-Data Actions:\n"
                + "      android.intent.action.VIEW:\n"
                + group("sneaky", MINE, VIEW, "nope", null)
                + "Receiver Resolver Table:\n"
                + "  Schemes:\n"
                + group("receiveronly", MINE, VIEW, "nope", null)
                + "Packages:\n"
                + "  Package [com.demo] (1a2b3c):\n"
                + "    versionName=9.9\n";
    }

    static void oddDumps() throws Exception {
        JSONObject p = AppIntents.parse(handmade(), "com.demo");
        check(schemes(p).equals(Arrays.asList("demo")), "only demo survives: " + schemes(p));
        check(entries(p, "demo").equals(Arrays.asList("page", "page/a/…")), "one page with one path: " + entries(p, "demo"));
        check("9.9".equals(p.optString("version")), "the version of a hand-written dump");
        check(p.optInt("count") == 2, "two entries: " + p.optInt("count"));

        JSONObject theirs = AppIntents.parse(dump("tv.danmaku.bili"), "com.other.app");
        check(theirs.optJSONObject("links").length() == 0 && theirs.optInt("count") == 0,
                "another package's filters give nothing");
        for (String empty : new String[] {null, "", "   \n\n", "Activity Resolver Table:\n", "Packages:\n  Package [x]:\n"}) {
            JSONObject none = AppIntents.parse(empty, "com.demo");
            check(none.optJSONObject("links").length() == 0 && none.optInt("count") == 0
                    && none.optString("version").isEmpty(), "a dump without the section gives nothing, no exception");
        }
        check(AppIntents.parse(handmade(), null).optJSONObject("links").length() == 0, "no package, no links");
    }

    // ---- the one line the model and the user see --------------------------------------------------

    static void summaries() throws Exception {
        String empty = "没有找到对外公开的链接";
        check(empty.equals(AppIntents.summary(new JSONObject(), 900)), "nothing parsed, nothing to say");
        check(empty.equals(AppIntents.summary(null, 900)), "not even a result");
        check(empty.equals(AppIntents.summary(AppIntents.parse("", "com.demo"), 900)), "a dump with no links");
        JSONObject bili = parsed("tv.danmaku.bili");
        String full = AppIntents.summary(bili, 4000);
        check(full.startsWith("bilibili://cheese、") && full.contains("；https://live.bilibili.com"),
                "schemes are separated by ；: " + full.substring(0, 40));
        check(!full.contains("等") && full.contains("main/favorite"), "the whole list fits in 4000 characters");
        for (int max : new int[] {0, 1, 20, 60, 120, 900}) {
            String s = AppIntents.summary(bili, max);
            check(s.length() <= max, "summary(" + max + ") is at most " + max + " characters: " + s.length());
        }
        String cut = AppIntents.summary(bili, 120);
        check(cut.endsWith("个") && cut.contains("等"), "what did not fit is counted: " + cut);
        check(AppIntents.summary(bili, 900).contains("等"), "900 characters is not enough for B站 either");
        check("nmcloudmusictv://；voice://netease.audioplayer".equals(AppIntents.summary(parsed("com.netease.cloudmusic.tv"), 900)),
                "NetEase in one line");
        check(("market://appstore.mitv.xiaomi.com、appstore.mitv.xiaomi.com/home、appstore.mitv.xiaomi.com/details、"
                + "appstore.mitv.xiaomi.com/skill；miwebcontent://game").equals(AppIntents.summary(parsed("com.xiaomi.mitv.appstore"), 900)),
                "the app store in one line");
    }

    // ---- what 星仪 learned by trying -------------------------------------------------------------

    static final String PKG = "tv.danmaku.bili", LABEL = "哔哩哔哩";

    static String refuses(JSONObject book, String note) {
        try {
            AppNotes.add(book, PKG, LABEL, note, 3000);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (JSONException e) {
            return "json";
        }
    }

    static JSONObject app(JSONObject book, String pkg) {
        return book.getJSONObject("apps").getJSONObject(pkg);
    }

    static void noteBook() throws Exception {
        JSONObject book = new JSONObject();
        String said = AppNotes.add(book, PKG, LABEL, "  bilibili://history   直接打开观看历史。 ", 1000);
        check("记下了：在哔哩哔哩里，bilibili://history 直接打开观看历史".equals(said), "add says what it stored: " + said);
        check(AppNotes.notes(book, PKG).equals(Arrays.asList("bilibili://history 直接打开观看历史")), "and it is there");
        check(app(book, PKG).optLong("at") == 1000 && app(book, PKG).optString("label").equals(LABEL),
                "with the app's name and the time it was noted");
        check(app(book, PKG).getJSONArray("notes").getJSONObject(0).optLong("at") == 1000, "the note has its own time");
        String again = AppNotes.add(book, PKG, LABEL, "bilibili://history直接打开观看历史", 2000);
        check("已经记着了：bilibili://history 直接打开观看历史".equals(again), "the same note again: " + again);
        check(AppNotes.notes(book, PKG).size() == 1, "and it is stored once");
        check("要记的经验是空的".equals(refuses(book, "")), "an empty note is refused");
        check("要记的经验是空的".equals(refuses(book, "   ")), "a blank note is refused");
        check("太长了，一句话说清".equals(refuses(book, longNote(121))), "121 characters is too long");
        check(refuses(book, longNote(120)) == null && AppNotes.notes(book, PKG).size() == 2, "120 characters is not");
        check("这类信息不记".equals(refuses(book, "我的密码是abcd")), "a password is not an experience");
        check("这类信息不记".equals(refuses(book, "验证码 123456")), "nor is a code");
        check("这类信息不记".equals(refuses(book, "会员卡号 622202123456789")), "nor is a card number");
        check(AppNotes.notes(book, "com.not.here").isEmpty(), "an app nobody noted has no notes");
        check(AppNotes.notes(null, PKG).isEmpty() && AppNotes.notes(new JSONObject(), PKG).isEmpty(), "nor has an empty book");

        JSONObject many = new JSONObject();
        for (int i = 1; i <= 9; i++) AppNotes.add(many, PKG, LABEL, "第" + i + "条经验", i);
        List<String> kept = AppNotes.notes(many, PKG);
        check(kept.size() == AppNotes.MAX_NOTES && kept.get(0).equals("第2条经验") && kept.get(7).equals("第9条经验"),
                "eight notes per app, the oldest goes: " + kept);

        JSONObject apps = new JSONObject();
        for (int i = 1; i <= 41; i++) AppNotes.add(apps, "com.app" + i, "应用" + i, "经验" + i, 1000 + i);
        JSONObject left = apps.getJSONObject("apps");
        check(left.length() == AppNotes.MAX_APPS && !left.has("com.app1") && left.has("com.app41"),
                "forty apps, the one noted longest ago goes: " + left.length());

        JSONObject order = new JSONObject();
        AppNotes.add(order, PKG, LABEL, "第一条", 1);
        AppNotes.add(order, PKG, LABEL, "第二条", 2);
        AppNotes.add(order, PKG, LABEL, "第三条", 3);
        check(AppNotes.notes(order, PKG).equals(Arrays.asList("第一条", "第二条", "第三条")), "notes come back oldest first");

        JSONObject gone = new JSONObject();
        AppNotes.add(gone, PKG, LABEL, "打开历史页", 1);
        AppNotes.add(gone, PKG, LABEL, "搜索页要打字", 2);
        AppNotes.add(gone, PKG, LABEL, "收藏在左边", 3);
        check(AppNotes.remove(gone, PKG, "2").equals(Arrays.asList("搜索页要打字")), "remove by number");
        check(AppNotes.notes(gone, PKG).equals(Arrays.asList("打开历史页", "收藏在左边")), "the other two stay");
        check(AppNotes.remove(gone, PKG, "历史").equals(Arrays.asList("打开历史页")), "remove by a word");
        check(AppNotes.remove(gone, PKG, "没有这个词").isEmpty() && AppNotes.notes(gone, PKG).size() == 1, "an unknown word removes nothing");
        check(AppNotes.remove(gone, "com.nothing", "all").isEmpty(), "an app nobody noted removes nothing");
        check(AppNotes.remove(gone, PKG, "all").equals(Arrays.asList("收藏在左边")), "all removes the rest");
        check(AppNotes.notes(gone, PKG).isEmpty() && !gone.getJSONObject("apps").has(PKG), "and the app itself is gone");
        AppNotes.add(gone, PKG, LABEL, "又记一条", 9);
        check(AppNotes.remove(gone, PKG, "全部").size() == 1, "全部 is all too");
    }

    static String longNote(int chars) {
        StringBuilder b = new StringBuilder();
        while (b.length() < chars) b.append("经");
        return b.toString();
    }

    // ---- the 应用经验 the notes become -----------------------------------------------------------

    static void noteSkills() throws Exception {
        JSONObject book = new JSONObject();
        AppNotes.add(book, PKG, LABEL, "bilibili://history 打开观看历史", 1);
        AppNotes.add(book, PKG, LABEL, "搜索页在首页第二行", 2);
        AppNotes.add(book, "com.netease.cloudmusic.tv", "网易云音乐", "nmcloudmusictv:// 直接进播放页", 3);
        List<Skills.Skill> skills = AppNotes.skills(book);
        check(skills.size() == 2, "one 应用经验 per app that has notes: " + skills.size());
        Skills.Skill bili = skills.get(0);
        // lead change: a name of its own, so it never collides with the contract's guide for the same app
        check((LABEL + "·自己摸索的经验").equals(bili.name), "named after the app, as its own guide: " + bili.name);
        check((LABEL + "（星仪自己摸索出来的经验，可能不准）：bilibili://history 打开观看历史；搜索页在首页第二行").equals(bili.text),
                "the notes become one guide: " + bili.text);
        check(bili.match.equals(Arrays.asList(LABEL)) && bili.packages.equals(Arrays.asList(PKG)) && bili.tools.isEmpty(),
                "it matches the app's name and package, and drives no tool");
        check(Skills.select(skills, "在哔哩哔哩里看看历史", "", "").size() == 1, "a request about the app picks it up");
        // lead change: the app in front alone no longer brings a guide; 「继续」 after a request about the app does
        check(Skills.select(skills, "继续", "哔哩哔哩（tv.danmaku.bili）", "").isEmpty(), "the app in front alone brings none");
        check(Skills.select(skills, "继续", "", "打开哔哩哔哩").size() == 1, "继续 after a request about the app does");
        check(Skills.select(skills, "现在几点", "", "").isEmpty(), "an unrelated request does not");
        check(AppNotes.skills(new JSONObject()).isEmpty() && AppNotes.skills(null).isEmpty(), "an empty book gives none");
        JSONObject noLabel = new JSONObject();
        AppNotes.add(noLabel, "com.x.y", "", "点两下就进去了", 1);
        check("com.x.y·自己摸索的经验".equals(AppNotes.skills(noLabel).get(0).name), "without a name the package is the name");
        JSONObject gone = new JSONObject();
        AppNotes.add(gone, "com.a", "甲", "经验", 1);
        AppNotes.add(gone, "com.b", "乙", "经验", 2);
        AppNotes.remove(gone, "com.a", "all");
        check(AppNotes.skills(gone).size() == 1, "an app whose notes are gone is skipped");
    }

    // ---- the two tools ---------------------------------------------------------------------------

    static final class FakeApps implements ExploreTools.Apps {
        final Map<String, String> byName = new LinkedHashMap<>(), labels = new LinkedHashMap<>();
        FakeApps with(String said, String pkg, String label) { byName.put(said, pkg); labels.put(pkg, label); return this; }
        @Override public String resolve(String app) { return byName.get(app); }
        @Override public String label(String pkg) { return labels.get(pkg); }
    }

    static final class FakeDump implements ExploreTools.Dumper {
        String text, fail;
        @Override public String dump(String pkg) throws Exception {
            if (fail != null) throw new IOException(fail);
            return text;
        }
    }

    static final class FakeStore implements MemoryTools.Store {
        JSONObject book = new JSONObject();
        boolean failLoad, failSave;
        int saves;
        @Override public JSONObject load() throws Exception {
            if (failLoad) throw new IOException("读不了");
            return book;
        }
        @Override public void save(JSONObject b) throws Exception {
            if (failSave) throw new IOException("写不了");
            book = b;
            saves++;
        }
    }

    static FakeApps tv() { return new FakeApps().with("哔哩哔哩", PKG, LABEL).with("B站", PKG, LABEL); }

    static JSONObject o(Object... kv) throws JSONException { return AgentCoreTest.o(kv); }

    static void exploreTool() throws Exception {
        FakeApps apps = tv();
        FakeDump dumper = new FakeDump();
        dumper.text = dump(PKG);
        FakeStore store = new FakeStore();
        List<Tool> tools = ExploreTools.create(dumper, apps, store);
        check(tools.size() == 2 && "explore_app".equals(tools.get(0).name()) && "app_note".equals(tools.get(1).name()),
                "explore_app and app_note");
        Tool explore = tools.get(0);
        check(explore.gate(new JSONObject(), null).risk == Tool.Gate.Risk.READ, "explore_app runs without a plan");
        check("看看哔哩哔哩能直接打开哪些页面".equals(explore.describe(o("app", "哔哩哔哩"), null)), "what it says it will do");
        JSONObject missing = explore.run(o("app", "相册"), null);
        check(!missing.optBoolean("ok") && "not_found".equals(missing.optString("status"))
                && "电视上没装「相册」：要用的话先用 install_app 装上".equals(missing.optString("message")),
                "an app that is not installed: " + missing);
        JSONObject r = explore.run(o("app", "B站"), null);
        check(r.optBoolean("ok") && "observed".equals(r.optString("status")), "the app is there: " + r.optString("status"));
        check(r.optString("message").startsWith("哔哩哔哩 8.91.1 能直接打开：bilibili://"), "name, version and links: " + r.optString("message"));
        check(PKG.equals(r.optString("package")) && r.optJSONObject("links").has("bilibili"), "the package and the links come back");
        check(r.optJSONArray("notes").length() == 0, "nothing learned about it yet");
        AppNotes.add(store.book, PKG, LABEL, "history 直接打开观看历史", 1);
        JSONObject known = explore.run(o("app", "哔哩哔哩"), null);
        check(known.optString("message").contains("。之前摸索出来的经验：history 直接打开观看历史"),
                "what was learned before rides along: " + known.optString("message"));
        check(known.optJSONArray("notes").length() == 1, "and comes back in the result too");
        JSONObject bad = ExploreTools.create(failing(), apps, store).get(0).run(o("app", "哔哩哔哩"), null);
        check(!bad.optBoolean("ok") && "error".equals(bad.optString("status")) && bad.optString("message").contains("adb 断了"),
                "a dump that fails: " + bad);
        JSONObject none = ExploreTools.create(new FakeDump(), apps, store).get(0).run(o("app", "哔哩哔哩"), null);
        check(none.optBoolean("ok") && none.optString("message").contains("没有找到对外公开的链接"),
                "a dump with nothing in it: " + none.optString("message"));
        FakeStore unreadable = new FakeStore();
        unreadable.failLoad = true;
        JSONObject ok = ExploreTools.create(dumper, apps, unreadable).get(0).run(o("app", "哔哩哔哩"), null);
        check(ok.optBoolean("ok") && ok.optJSONArray("notes").length() == 0, "a note file that cannot be read costs nothing else");
    }

    static FakeDump failing() {
        FakeDump d = new FakeDump();
        d.fail = "adb 断了";
        return d;
    }

    static void noteTool() throws Exception {
        FakeApps apps = tv();
        FakeStore store = new FakeStore();
        Tool note = ExploreTools.create(new FakeDump(), apps, store).get(1);
        check(note.gate(new JSONObject(), null).risk == Tool.Gate.Risk.READ, "app_note runs without a plan");
        check("记下哔哩哔哩的经验：history 能用".equals(note.describe(o("app", "哔哩哔哩", "note", "history 能用"), null)),
                "what it says it will do");
        JSONObject r = note.run(o("app", "哔哩哔哩", "note", "bilibili://history 打开观看历史"), null);
        check(r.optBoolean("ok") && "recorded".equals(r.optString("status"))
                && "记下了：在哔哩哔哩里，bilibili://history 打开观看历史".equals(r.optString("message")), "recorded: " + r);
        check(store.saves == 1 && AppNotes.notes(store.book, PKG).size() == 1, "and written to the file");
        JSONObject twice = note.run(o("app", "哔哩哔哩", "note", "bilibili://history 打开观看历史"), null);
        check(twice.optBoolean("ok") && "exists".equals(twice.optString("status"))
                && twice.optString("message").startsWith("已经记着了："), "the same experience again: " + twice);
        check(store.saves == 1, "and the file is not written again");
        JSONObject blank = note.run(o("app", "哔哩哔哩", "note", " "), null);
        check(!blank.optBoolean("ok") && "rejected".equals(blank.optString("status"))
                && "要记的经验是空的".equals(blank.optString("message")), "an empty note: " + blank);
        JSONObject secret = note.run(o("app", "哔哩哔哩", "note", "登录密码是 123456"), null);
        check(!secret.optBoolean("ok") && "rejected".equals(secret.optString("status"))
                && "这类信息不记".equals(secret.optString("message")), "a secret: " + secret);
        JSONObject elsewhere = note.run(o("app", "奇异果", "note", "首页第二行是搜索"), null);
        check(elsewhere.optBoolean("ok") && elsewhere.optString("message").contains("在奇异果里"),
                "an app that is not installed is filed under the user's own words: " + elsewhere);
        check(AppNotes.notes(store.book, "?奇异果").size() == 1, "under 「?<app>」");
        JSONObject readFail = ExploreTools.create(new FakeDump(), apps, broken(true, false)).get(1)
                .run(o("app", "哔哩哔哩", "note", "经验"), null);
        check(!readFail.optBoolean("ok") && "error".equals(readFail.optString("status"))
                && "经验没存上：读不了".equals(readFail.optString("message")), "a file that cannot be read: " + readFail);
        JSONObject saveFail = ExploreTools.create(new FakeDump(), apps, broken(false, true)).get(1)
                .run(o("app", "哔哩哔哩", "note", "经验"), null);
        check(!saveFail.optBoolean("ok") && "error".equals(saveFail.optString("status"))
                && saveFail.optString("message").contains("写不了"), "a file that cannot be written: " + saveFail);
    }

    static FakeStore broken(boolean load, boolean save) {
        FakeStore s = new FakeStore();
        s.failLoad = load;
        s.failSave = save;
        return s;
    }

    // ---- explore_app in a task without a plan ----------------------------------------------------

    /** Only these two tools: assets/agent/contract.json is not this job's to change, and the loop refuses a tool the
     * contract does not carry. */
    static Contract contract() throws JSONException {
        return new Contract("{\"system\":\"测试\",\"tools\":["
                + "{\"type\":\"function\",\"function\":{\"name\":\"explore_app\",\"description\":\"d\",\"parameters\":"
                + "{\"type\":\"object\",\"properties\":{\"app\":{\"type\":\"string\"}},\"required\":[\"app\"]}}},"
                + "{\"type\":\"function\",\"function\":{\"name\":\"app_note\",\"description\":\"d\",\"parameters\":"
                + "{\"type\":\"object\",\"properties\":{\"app\":{\"type\":\"string\"},\"note\":{\"type\":\"string\"}},"
                + "\"required\":[\"app\",\"note\"]}}}]}");
    }

    static AgentLoop loop(AgentCoreTest.Script model, Tool... tools) throws Exception {
        long[] t = {1_000_000};
        return new AgentLoop(contract(), model, Arrays.asList(tools), new AgentCoreTest.Events(), (w, l) -> true,
                () -> t[0] += 10, null);
    }

    static void withoutAPlan() throws Exception {
        FakeDump dumper = new FakeDump();
        dumper.text = dump(PKG);
        Tool explore = ExploreTools.create(dumper, tv(), new FakeStore()).get(0);
        AgentCoreTest.Script m = new AgentCoreTest.Script()
                .calls("explore_app", o("app", "哔哩哔哩"))
                .text("哔哩哔哩能直接打开搜索页、观看历史这些页面");
        Ledger done = loop(m, explore).run(new AgentLoop.Request("看看哔哩哔哩能直接打开哪些页面", "test"));
        check(m.seen.size() == 2, "the read ran without a plan, then the model answered: " + m.seen.size());
        JSONObject result = m.toolResult("c0");
        check(result != null && result.optBoolean("ok") && result.optJSONObject("links").has("bilibili"),
                "the model is given the links: " + result);
        check(result.optString("message").contains("能直接打开：bilibili://"), "and one line it can say");
        check(done.reply.startsWith("哔哩哔哩能直接打开"), "the model's own words are the answer: " + done.reply);
    }
}
