package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONObject;

/** find-installer (2026-11-07 night): the search for an app's official Android installer link (Installers,
 * WebSearch.findInstaller). The worker fills this in; see briefs/find-installer.md. */
final class InstallerChecks {
    private static final String NOW = "2026-11-07 23:40 星期六";

    private InstallerChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static void eq(String got, String want, String what) {
        check(want.equals(got), what + ": got [" + got + "] want [" + want + "]");
    }

    static String fixture(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/" + name)), StandardCharsets.UTF_8);
    }

    /** A minimal Responses reply carrying one output_text part. */
    private static String message(String text) {
        return "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":" + JSONObject.quote(text) + "}]}]}";
    }

    private static JSONObject parsed(String text) { return Installers.parse(text); }

    static void run() {
        try {
            checks();
        } catch (Exception e) {
            check(false, "installer checks threw " + e);
        }
    }

    static void checks() throws Exception {
        realAnswers();
        wrapped();
        dropped();
        garbage();
        request();
        failures();
        cap();
        nulls();
    }

    /** The search model writes null for what it did not find; Android's optString would make that the word "null"
     * (2026-11-09 review). The desktop jar gives "" either way: this pins the reading, the TV is what it is for. */
    private static void nulls() throws Exception {
        JSONObject r = parsed("{\"app\":\"Via\",\"developer\":null,\"page\":\"https://viayoo.com/\",\"url\":null,\"package\":null,\"note\":null}");
        check(r != null && "".equals(r.optString("url")) && "".equals(r.optString("developer")) && "".equals(r.optString("package")),
                "null fields are empty, not the word null: " + r);
    }

    // ---- the three real answers (2026-11-07 23:5x, 9–12 s each) -----------------------------------

    static void realAnswers() throws Exception {
        JSONObject via = parsed(fixture("installer-via.txt"));
        check(via != null, "Via浏览器's answer parses");
        check(via.optBoolean("found"), "Via浏览器 has a link that may be fetched");
        eq(via.optString("url"), "https://res.viayoo.com/v1/via-release-cn.apk", "its own CDN link is kept");
        eq(via.optString("page"), "https://viayoo.com/zh-cn/docs/get-via.html", "the maker's download page is kept");
        eq(via.optString("app"), "Via浏览器", "the app name comes back");
        eq(via.optString("developer"), "上海知为网络科技有限公司", "so does the developer");
        eq(via.optString("package"), "", "a package the model did not know stays empty");
        check(via.optString("note").contains("官网下载页"), "the model's own note is kept");

        JSONObject dangbei = parsed(fixture("installer-dangbei.txt"));
        check(dangbei != null && dangbei.optBoolean("found"), "当贝市场 has a link that may be fetched");
        eq(dangbei.optString("url"), "https://app.qingyingyong.net/down/20260706/dangbeimarket_6.0.7_613_znds.apk",
                "the download host its own page lists is kept (it is not a known download site)");
        eq(dangbei.optString("page"), "https://www.dangbei.com/app/m/", "and its page");
        eq(dangbei.optString("developer"), "杭州当贝网络科技有限公司", "当贝市场's developer");

        JSONObject hongguo = parsed(fixture("installer-hongguo.txt"));
        check(hongguo != null, "红果短剧's answer parses");
        check(!hongguo.optBoolean("found"), "红果短剧 has no direct link");
        eq(hongguo.optString("url"), "", "an empty url stays empty");
        eq(hongguo.optString("page"), "https://applink.novelquickapp.com/", "its page is kept for the model");
        eq(hongguo.optString("developer"), "北京笔墨留香科技有限公司", "红果短剧's developer");
        check(hongguo.optString("note").contains("扫码"), "the reason it has no link is kept");
    }

    // ---- however the model wraps it ----------------------------------------------------------------

    static void wrapped() throws Exception {
        String via = fixture("installer-via.txt").trim();
        JSONObject fenced = parsed("```json\n" + via + "\n```");
        check(fenced != null && fenced.optBoolean("found"), "an answer in ```json fences still parses");
        eq(fenced.optString("url"), "https://res.viayoo.com/v1/via-release-cn.apk", "the fenced link is kept");
        JSONObject talked = parsed("我查到了，结果如下：\n" + via + "\n希望对你有帮助。");
        check(talked != null && talked.optBoolean("found"), "a sentence before and after is dropped");
        eq(talked.optString("url"), "https://res.viayoo.com/v1/via-release-cn.apk", "the link survives the sentence");
        eq(talked.optString("app"), "Via浏览器", "the fields are read, not the sentence");
        JSONObject both = parsed("好的。```json\n" + via + "\n```\n以上。");
        check(both != null && both.optBoolean("found"), "fences inside a sentence still parse");
        eq(both.optString("page"), "https://viayoo.com/zh-cn/docs/get-via.html", "and keep the page");
    }

    // ---- a link that is not the maker's own ---------------------------------------------------------

    private static final String GOOD = "{\"app\":\"红果短剧\",\"developer\":\"北京笔墨留香科技有限公司\","
            + "\"page\":\"https://www.hongguoduanju.com/download/\",\"url\":\"https://www.hongguoduanju.com/download/hg.apk\","
            + "\"package\":\"com.hongguo\",\"note\":\"官网下载页给出的直链\"}";

    static void dropped() {
        JSONObject site = parsed(GOOD.replace("https://www.hongguoduanju.com/download/hg.apk", "https://www.pc6.com/x.apk"));
        check(site != null, "an answer with a download site's link parses");
        eq(site.optString("url"), "", "a download site's link is dropped");
        check(!site.optBoolean("found"), "so nothing is found there");
        check(site.optString("note").contains("「www.pc6.com」是下载站，不算"), "and the drop is said in the note: " + site.optString("note"));
        check(site.optString("note").startsWith("官网下载页给出的直链；"), "the model's own note is kept in front of it");
        JSONObject sub = parsed(GOOD.replace("https://www.hongguoduanju.com/download/hg.apk", "https://down.pc6.com/x.apk"));
        check(!sub.optBoolean("found"), "a subdomain of a download site is dropped too");
        JSONObject plain = parsed(GOOD.replace("https://www.hongguoduanju.com/download/hg.apk", "http://www.hongguoduanju.com/download/hg.apk"));
        check(!plain.optBoolean("found"), "a plain http link is dropped");
        eq(plain.optString("url"), "", "and left out");
        check(plain.optString("note").contains("不是能直接下载的 https 直链，不算"), "the http drop is said in the note: " + plain.optString("note"));
        JSONObject hosting = parsed(GOOD.replace("https://www.hongguoduanju.com/download/hg.apk", "https://hongguo.pages.dev/hg.apk"));
        check(!hosting.optBoolean("found"), "free hosting is nobody's official site");
        JSONObject page = parsed(GOOD.replace("\"page\":\"https://www.hongguoduanju.com/download/\"",
                "\"page\":\"https://www.cr173.com/hongguo/\""));
        check(page.optBoolean("found"), "a download site as the page does not throw away a good link");
        eq(page.optString("page"), "", "but the page is dropped");
        check(page.optString("note").contains("「www.cr173.com」是下载站，不算"), "and said in the note: " + page.optString("note"));
        JSONObject empty = parsed(GOOD.replace("\"note\":\"官网下载页给出的直链\"", "\"note\":\"\"")
                .replace("https://www.hongguoduanju.com/download/hg.apk", "https://www.pc6.com/x.apk"));
        eq(empty.optString("note"), "「www.pc6.com」是下载站，不算", "without a note the drop is the whole note");
    }

    // ---- text that is not an answer -------------------------------------------------------------------

    static void garbage() {
        check(parsed(null) == null, "null is not an answer");
        check(parsed("") == null, "nor an empty string");
        check(parsed("没有找到这个应用的官网。") == null, "a plain sentence is not an answer");
        check(parsed("{这不是 json}") == null, "braces around nonsense are not an answer");
        check(parsed("{\"app\": }") == null, "a half-written object is not an answer");
        check(parsed("[]") == null, "a JSON array is not an answer");
        JSONObject inside = parsed("[{\"app\":\"红果短剧\"}]");
        check(inside != null && "红果短剧".equals(inside.optString("app")), "an object inside an array is still read");
        check(parsed("{\"app\":\"红果短剧\"") == null, "an object that never closes is not an answer");
        JSONObject bare = parsed("{}");
        check(bare != null, "an empty object is an answer with nothing in it");
        check(!bare.optBoolean("found"), "and nothing found");
        for (String f : Installers.FIELDS) eq(bare.optString(f), "", "every missing field is empty: " + f);
        JSONObject partial = parsed("{\"app\":\"当贝市场\",\"url\":\"https://www.dangbei.com/a.apk\"}");
        check(partial.optBoolean("found"), "a url alone is enough to go on");
        eq(partial.optString("developer"), "", "the fields the model left out stay empty");
        eq(partial.optString("app"), "当贝市场", "the ones it gave are read");
        JSONObject nulls = parsed("{\"app\":\"当贝市场\",\"url\":null,\"note\":null}");
        check(nulls != null && !nulls.optBoolean("found"), "a null url is not a link");
        eq(nulls.optString("note"), "", "and a null note is empty");
    }

    // ---- what goes out, and what comes back through it ------------------------------------------------

    static void request() throws Exception {
        String via = fixture("installer-via.txt");
        WebSearchChecks.Recorder rec = new WebSearchChecks.Recorder();
        rec.reply = new WebSearch.Reply(200, message(via));
        WebSearch ws = new WebSearch("https://gw.example.com", "sk-key-1", null, rec);
        Installers.Found f = ws.findInstaller("  Via浏览器  ", NOW, 40000, null);
        eq(rec.url, "https://gw.example.com/v1/responses", "find_installer posts to /v1/responses");
        eq(rec.key, "sk-key-1", "the api key is the bearer");
        check(rec.timeout == 40000, "the 40 s timeout reaches the transport: " + rec.timeout);
        JSONObject sent = new JSONObject(rec.body);
        check(sent.similar(WebSearch.body(WebSearch.DEFAULT_MODEL, WebSearch.installerInstructions(NOW),
                "找「Via浏览器」的官方 Android 安装包")), "the sent body is the web_search one with these instructions: " + sent);
        eq(sent.getString("model"), WebSearch.DEFAULT_MODEL, "the same model as a web search");
        check(sent.getJSONArray("tools").length() == 1
                && "web_search".equals(sent.getJSONArray("tools").getJSONObject(0).getString("type")), "the web_search tool is requested");
        JSONObject turn = sent.getJSONArray("input").getJSONObject(0);
        check("user".equals(turn.getString("role")) && "找「Via浏览器」的官方 Android 安装包".equals(turn.getString("content")),
                "the input names the app: " + turn);
        eq(WebSearch.installerInput("当贝市场"), "找「当贝市场」的官方 Android 安装包", "and does so for any app");

        String rules = WebSearch.installerInstructions(NOW);
        check(rules.startsWith("你在帮一台小米电视（Android 9，32 位 armeabi-v7a）找某个应用的官方 Android 安装包。现在是 " + NOW + "。先联网搜索。"),
                "the model is told what it is looking for and when: " + rules);
        check(rules.contains("只认开发者自己的官网、它官网上写明的下载地址或开发者自己的 CDN，不要第三方下载站、应用市场镜像、网盘、破解版。"),
                "only the maker's own site is accepted");
        check(rules.contains("只回答一个 JSON 对象，不要别的文字：{\"app\":应用名,\"developer\":开发者公司,\"page\":官网上提供下载的页面地址,"
                        + "\"url\":安装包直链（https，能直接下载到 .apk；没有直链就填空字符串）,\"package\":包名（知道的话）,\"note\":一句说明}"),
                "and it is asked for exactly these fields: " + rules);

        check(f.refusal == null, "no refusal without a cap");
        check(f.found(), "the answer is found");
        eq(f.facts.optString("url"), "https://res.viayoo.com/v1/via-release-cn.apk", "the link is there for the model");
        check(f.millis >= 0 && f.millis < 60000, "millis measures the call: " + f.millis);
        // web_search strips every address before the model sees it; find_installer must not
        check(WebSearch.speakable(via).indexOf("http") < 0, "a web_search answer would have lost the link");
        check(ResponsesText.extract(message(via)).indexOf("http") < 0, "so would ResponsesText.extract (it cleans the text)");
        check(Installers.text(message(via)).contains("https://res.viayoo.com/v1/via-release-cn.apk"),
                "the installer reader leaves the links in: " + Installers.text(message(via)));
        eq(Installers.text("{\"output_text\":\"{\\\"app\\\":\\\"当贝市场\\\"}\"}"), "{\"app\":\"当贝市场\"}",
                "a top-level output_text is read as it is");
        eq(Installers.text("not json"), "", "a reply that is not json has no text");
        eq(Installers.text("{\"output\":[{\"type\":\"web_search_call\"}]}"), "", "nor one with no message");
        eq(Installers.text(message("第一句。")), "第一句。", "a one-message reply is its text");
        eq(Installers.text("{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"我查一下。\"}]},"
                        + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"结果。\"}]}]}"), "结果。",
                "only the last message, as a spoken answer takes");
        check(Installers.text(null).isEmpty(), "and no reply at all is no text");
        check(f.facts.toString().contains("https://res.viayoo.com/v1/via-release-cn.apk"), "find_installer keeps it");
    }

    /** Every way the call can fail: a sentence the model can act on, never a raw status line. */
    static void failures() throws Exception {
        int[] cases = {401, 403, 429, 500};
        String[] want = {"联网搜索的线路鉴权失败", "联网搜索的线路鉴权失败", "联网搜索太忙了，稍后再试", "联网搜索失败（HTTP 500）"};
        for (int i = 0; i < cases.length; i++) {
            WebSearchChecks.Recorder rec = new WebSearchChecks.Recorder();
            rec.reply = new WebSearch.Reply(cases[i], message("{}"));
            eq(failure(new WebSearch("https://gw.example.com", "k", null, rec), "Via浏览器", 40000), want[i],
                    "HTTP " + cases[i] + " is spoken as a sentence");
        }
        WebSearchChecks.Recorder empty = new WebSearchChecks.Recorder();
        empty.reply = new WebSearch.Reply(200, "{\"output\":[{\"type\":\"web_search_call\"}]}");
        eq(failure(new WebSearch("https://gw.example.com", "k", null, empty), "Via浏览器", 40000), "联网搜索没有返回结果",
                "a reply with no message is an empty answer");
        WebSearchChecks.Recorder broken = new WebSearchChecks.Recorder();
        broken.failure = new IOException("连接超时");
        try {
            new WebSearch("https://gw.example.com", "k", null, broken).findInstaller("Via浏览器", NOW, 40000, null);
            check(false, "the transport's own failure must come out");
        } catch (IOException e) {
            check(e == broken.failure, "the transport's own IOException comes out unchanged: " + e);
        }
        // the model sometimes answers with prose: that is a search that found nothing, not a crash
        WebSearchChecks.Recorder prose = new WebSearchChecks.Recorder();
        prose.reply = new WebSearch.Reply(200, message("我没有找到这个应用的官网。"));
        Installers.Found said = new WebSearch("https://gw.example.com", "k", null, prose).findInstaller("Via浏览器", NOW, 40000, null);
        check(said.facts == null && said.refusal == null && !said.found(), "an answer that is not an object is not a link: " + said.facts);

        for (String bad : new String[]{"", "   "}) {
            WebSearchChecks.Recorder rec = new WebSearchChecks.Recorder();
            try {
                new WebSearch("https://gw.example.com", "k", null, rec).findInstaller(bad, NOW, 40000, null);
                check(false, "an app name of [" + bad + "] must be refused");
            } catch (IllegalArgumentException e) {
                eq(e.getMessage(), "要找的应用名要在 1 到 300 字之间", "an app name of [" + bad + "] is refused");
            }
            check(rec.calls == 0, "no request for an app name of [" + bad + "]");
        }
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 301; i++) longName.append('当');
        WebSearchChecks.Recorder tooLong = new WebSearchChecks.Recorder();
        try {
            new WebSearch("https://gw.example.com", "k", null, tooLong).findInstaller(longName.toString(), NOW, 40000, null);
            check(false, "an app name of 301 chars must be refused");
        } catch (IllegalArgumentException e) {
            eq(e.getMessage(), "要找的应用名要在 1 到 300 字之间", "an app name of 301 chars is refused");
        }
        check(tooLong.calls == 0, "and nothing is sent for it");
        WebSearchChecks.Recorder quick = new WebSearchChecks.Recorder();
        try {
            new WebSearch("https://gw.example.com", "k", null, quick).findInstaller("Via浏览器", NOW, 999, null);
            check(false, "a timeout under a second must be refused");
        } catch (IllegalArgumentException e) {
            check(e.getMessage() != null && !e.getMessage().isEmpty(), "short timeout refused: " + e.getMessage());
        }
        check(quick.calls == 0, "no request for a timeout of 999 ms");
    }

    /** The message of the IOException findInstaller throws, or "" when it threw none. */
    private static String failure(WebSearch ws, String app, int timeoutMs) {
        try {
            ws.findInstaller(app, NOW, timeoutMs, null);
            return "";
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    /** A search that costs money is refused on a capped day before anything leaves the TV. */
    static void cap() throws Exception {
        WebSearchChecks.Recorder rec = new WebSearchChecks.Recorder();
        rec.reply = new WebSearch.Reply(200, message(fixture("installer-via.txt")));
        final String[] refusal = {null};
        Installers.Cap closed = new Installers.Cap() {
            @Override public String take() { return refusal[0]; }
        };
        WebSearch ws = new WebSearch("https://gw.example.com", "k", null, rec);
        refusal[0] = "今天联网已经查了 150 次，到了每天的上限。";
        Installers.Found stopped = ws.findInstaller("Via浏览器", NOW, 40000, closed);
        eq(stopped.refusal, refusal[0], "a capped day gives the cap's sentence back");
        check(stopped.facts == null && !stopped.found(), "and no facts");
        check(rec.calls == 0, "nothing was sent on a capped day: " + rec.calls);

        refusal[0] = null;
        Installers.Found went = ws.findInstaller("Via浏览器", NOW, 40000, closed);
        check(went.refusal == null && went.found(), "the same search runs when the cap allows it");
        check(rec.calls == 1, "and it is sent once: " + rec.calls);

        java.io.File file = java.io.File.createTempFile("installer-cap", ".json");
        file.delete();
        final DailyCap day = new DailyCap(file, 1, "联网");
        WebSearchChecks.Recorder real = new WebSearchChecks.Recorder();
        real.reply = new WebSearch.Reply(200, message(fixture("installer-via.txt")));
        WebSearch metered = new WebSearch("https://gw.example.com", "k", null, real);
        Installers.Cap counted = new Installers.Cap() {
            @Override public String take() { return day.take("2026-11-07"); }
        };
        check(metered.findInstaller("Via浏览器", NOW, 40000, counted).found(), "the day's first search runs");
        Installers.Found second = metered.findInstaller("当贝市场", NOW, 40000, counted);
        check(second.refusal != null && second.refusal.contains("今天联网已经查了 1 次"), "the second is refused: " + second.refusal);
        check(real.calls == 1, "and costs nothing: " + real.calls);
        check(metered.findInstaller("当贝市场", NOW, 40000, counted).refusal != null, "a third one too");
        check(day.take("2026-11-08") == null, "a new day starts over");
        file.delete();
    }
}
