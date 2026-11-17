package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/** cards-data (2026-11-07): the cards the data tools hand the panel (Cards, Weather.card) and how they travel on the
 * ledger. Everything is built from the fixtures saved from the real services, the way the tools build them. */
final class CardChecks {
    private CardChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    static String fixture(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/" + name)), StandardCharsets.UTF_8);
    }

    static void run() {
        try {
            checks();
        } catch (Exception e) {
            check(false, "card checks threw " + e);
        }
    }

    static void checks() throws Exception {
        stocks();
        lists();
        weather();
        valid();
        panel();
        ledger();
    }

    /** 2026-11-06 09:50 in Shanghai: the morning the quotes fixture was saved. */
    private static Calendar friday() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        c.clear();
        c.set(2026, Calendar.NOVEMBER, 6, 9, 50);
        return c;
    }

    // ---- the quotes --------------------------------------------------------------------------------

    static void stocks() throws Exception {
        List<Quotes.Quote> qs = Quotes.parse(fixture("quotes-qt.txt"));
        Calendar fri = friday();
        JSONObject maotai = Cards.stocks(qs.subList(3, 4), fri);
        JSONObject row = maotai.getJSONArray("rows").getJSONObject(0);
        check("stocks".equals(maotai.getString("type")) && maotai.getJSONArray("rows").length() == 1, "one quote, one row");
        check("贵州茅台".equals(row.getString("name")) && "1453.08".equals(row.getString("price")) && "元".equals(row.getString("unit")),
                "an A share during the day, in yuan: " + row);
        check("0.07".equals(row.getString("pct")) && "9 点 49 分".equals(row.getString("when")),
                "its percentage signed as the quote gives it, its own time: " + row);
        check(Cards.valid(maotai), "the panel can draw it");

        JSONObject index = Cards.stocks(qs.subList(0, 1), fri).getJSONArray("rows").getJSONObject(0);
        check("上证指数".equals(index.getString("name")) && "点".equals(index.getString("unit")) && "-1.44".equals(index.getString("pct")),
                "an index is counted in 点 and may fall: " + index);

        JSONObject apple = Cards.stocks(qs.subList(4, 5), fri).getJSONArray("rows").getJSONObject(0);
        check("苹果".equals(apple.getString("name")) && "美元".equals(apple.getString("unit"))
                && "美东时间 11 月 5 日收盘".equals(apple.getString("when")), "a US close, in dollars and New York time: " + apple);

        // the Hang Seng at 16:10 is a close; the same quote seen on the Saturday closed the day before
        JSONObject hk = Cards.stocks(Quotes.parse(fixture("quotes-qt.txt").replace("2026/11/06 09:34:51", "2026/11/06 16:10:00"))
                .subList(5, 6), fri).getJSONArray("rows").getJSONObject(0);
        check("恒生指数".equals(hk.getString("name")) && "点".equals(hk.getString("unit")) && "今天收盘".equals(hk.getString("when")),
                "a Hong Kong close, counted in 点: " + hk);
        Calendar sat = friday();
        sat.set(2026, Calendar.NOVEMBER, 7, 10, 0);
        JSONObject closed = Cards.stocks(qs.subList(5, 6), sat).getJSONArray("rows").getJSONObject(0);
        check("11 月 6 日收盘".equals(closed.getString("when")), "asked on the Saturday, the day it closed: " + closed);

        JSONObject four = Cards.stocks(qs, fri);
        check(four.getJSONArray("rows").length() == 4 && "上证指数".equals(four.getJSONArray("rows").getJSONObject(0).getString("name"))
                && "贵州茅台".equals(four.getJSONArray("rows").getJSONObject(3).getString("name")), "six quotes: the first four, in order");

        check(Cards.stocks(Collections.<Quotes.Quote>emptyList(), fri) == null && Cards.stocks(null, fri) == null,
                "no quotes, no card");
        check(Cards.stocks(Quotes.parse("v_pv_none_match=\"1\";"), fri) == null, "a quote the service does not know is no card");

        // a row the panel could not draw (no percentage) is left out instead of sinking the whole card
        Quotes.Quote blank = new Quotes.Quote("sh", "600519", "没涨没跌", "20261106094951", "10.00", "0.00", "");
        JSONObject kept = Cards.stocks(Arrays.asList(blank, qs.get(3)), fri);
        check(kept.getJSONArray("rows").length() == 1 && "贵州茅台".equals(kept.getJSONArray("rows").getJSONObject(0).getString("name")),
                "a quote with no percentage is dropped, the rest is drawn");
        check(Cards.stocks(Arrays.asList(blank, new Quotes.Quote("sh", "1", "", "20261106094951", "1.00", "0", "1.0")), fri) == null,
                "nothing drawable at all: no card");
    }

    // ---- the lists ---------------------------------------------------------------------------------

    /** 2026-11-07 02:00 in Beijing, a Saturday: the hour the B站 fixtures were saved. */
    private static final long NOW = 1793988000000L;

    static void lists() throws Exception {
        news();
        videos();
        songs();
        topics();
        bangumi();
        history();
        movies();
        limits();
    }

    static void news() throws Exception {
        long now = Sports.parse("2026-11-06T05:15Z");
        List<News.Item> world = News.fresh(News.items("cn:world", fixture("news-cn-world.xml")), now);
        JSONObject read = News.readOut("国际新闻", world, 5);
        JSONObject card = Cards.list(read.optString("what"), read.optJSONArray("items"));
        check("list".equals(card.getString("type")) && "国际新闻".equals(card.getString("title")), "the news list, under its own title");
        JSONArray items = card.getJSONArray("items");
        check(items.length() == 5 && world.get(0).title.equals(items.getJSONObject(0).getString("title")),
                "five headlines, as they are: " + items.getJSONObject(0).getString("title"));
        String summary = read.getJSONArray("items").getJSONObject(0).getString("summary");
        String meta = items.getJSONObject(0).getString("meta");
        check(summary.startsWith(meta.substring(0, meta.length() - 1)) && meta.length() == 29 && meta.endsWith("…"),
                "a long summary is its first 28 characters, cut with 「…」: " + meta);
        check(!items.getJSONObject(1).getString("meta").isEmpty() && Cards.valid(card), "every item has its meta and the panel can draw it");
    }

    /** The B站 search as the fixtures saw it. */
    private static Feeds.Fetch bili() throws IOException {
        Map<String, String> pages = new HashMap<>();
        pages.put(BiliVideos.userUrl("影视飓风"), fixture("bili-user-ysjf.json"));
        pages.put(BiliVideos.videoUrl("影视飓风", "latest"), fixture("bili-video-ysjf-latest.json"));
        return (url, charset, timeoutMs) -> {
            String p = pages.get(url);
            if (p == null) throw new IOException("no fixture for " + url);
            return p;
        };
    }

    static void videos() throws Exception {
        JSONObject read = BiliVideos.readOut(BiliVideos.find(bili(), "影视飓风", "", "latest", 3), 3, NOW);
        JSONObject card = Cards.list(read.optString("what"), read.optJSONArray("items"));
        JSONArray items = card.getJSONArray("items");
        check(items.length() == 3 && read.getString("what").equals(card.getString("title")), "an UP's newest, under the list's own title: "
                + card.getString("title"));
        JSONObject first = read.getJSONArray("items").getJSONObject(0);
        check((first.getString("up") + " · " + first.getString("date")).equals(items.getJSONObject(0).getString("meta")),
                "a B站 video's meta is its UP and the day: " + items.getJSONObject(0).getString("meta"));
        check("影视飓风".equals(first.getString("up")) && items.getJSONObject(0).getString("meta").startsWith("影视飓风 · "),
                "the UP is the one asked for: " + items.getJSONObject(0).getString("meta"));
        // the hot list's videos carry no day: the meta is the UP alone
        List<HotList.Video> hot = HotList.bili(fixture("hot-bili.json"));
        JSONObject hotRead = HotList.readOutVideos(hot, 3);
        JSONObject hotCard = Cards.list(hotRead.optString("what"), hotRead.optJSONArray("items"));
        check("B站热门".equals(hotCard.getString("title")) && hotCard.getJSONArray("items").getJSONObject(0).getString("meta")
                .equals(hotRead.getJSONArray("items").getJSONObject(0).getString("up")), "no day, no separator: the UP alone: "
                + hotCard.getJSONArray("items").getJSONObject(0).getString("meta"));
    }

    static void songs() throws Exception {
        List<HotList.Song> songs = HotList.chartSongs(fixture("chart-netease-hot.json"));
        JSONObject read = HotList.readOutSongs("网易云热歌榜", songs, 5);
        JSONObject card = Cards.list(read.optString("what"), read.optJSONArray("items"));
        check("网易云热歌榜".equals(card.getString("title")) && card.getJSONArray("items").length() == 5, "the chart, five songs");
        check("孙燕姿".equals(card.getJSONArray("items").getJSONObject(1).getString("meta"))
                && "我不难过".equals(card.getJSONArray("items").getJSONObject(1).getString("title")), "a song's meta is its artist: "
                + card.getJSONArray("items").getJSONObject(1));
    }

    static void topics() throws Exception {
        List<String> words = HotList.baidu(fixture("hot-baidu.json"));
        JSONObject read = HotList.readOutTopics(words, 8);
        JSONObject card = Cards.list(read.optString("what"), read.optJSONArray("items"));
        JSONArray items = card.getJSONArray("items");
        check("百度热搜".equals(card.getString("title")) && items.length() == 5, "the hot search, five of its eight");
        check(words.get(0).equals(items.getJSONObject(0).getString("title")) && !items.getJSONObject(0).has("meta"),
                "a topic has nothing under it: " + items.getJSONObject(0));
        check(Cards.valid(card), "a list without meta lines is still drawn");
    }

    static void bangumi() throws Exception {
        List<Bangumi.Episode> all = new ArrayList<>(Bangumi.episodes(fixture("bangumi-anime.json"), "番剧"));
        all.addAll(Bangumi.episodes(fixture("bangumi-guochuang.json"), "国创"));
        Collections.sort(all, (x, y) -> Long.compare(x.at, y.at));
        JSONObject read = Bangumi.readOut(Bangumi.on(Bangumi.withoutDubs(all), NOW, 0), 0, NOW);
        JSONObject card = Cards.list(read.optString("what"), read.optJSONArray("items"));
        check("今天B站更新的番剧".equals(card.getString("title")) && card.getJSONArray("items").length() == 5,
                "today's anime, five of the six: " + card.getString("title"));
        JSONObject first = read.getJSONArray("items").getJSONObject(0);
        check(Bangumi.index(first.getString("episode")).equals(card.getJSONArray("items").getJSONObject(0).getString("meta")),
                "an episode that has not come out yet says only which episode it is: "
                        + card.getJSONArray("items").getJSONObject(0).getString("meta"));
        JSONObject yesterday = Bangumi.readOut(Bangumi.on(Bangumi.withoutDubs(all), NOW, -1), -1, NOW);
        JSONObject out = Cards.list(yesterday.optString("what"), yesterday.optJSONArray("items"));
        check(yesterday.getJSONArray("items").getJSONObject(0).getBoolean("out")
                && out.getJSONArray("items").getJSONObject(0).getString("meta").endsWith("（已更新）"),
                "one already out says so: " + out.getJSONArray("items").getJSONObject(0).getString("meta"));
    }

    static void history() throws Exception {
        List<HistoryToday.Entry> picked = HistoryToday.pick(HistoryToday.entries(fixture("history-11.json"), 11, 7), 4);
        JSONObject read = HistoryToday.readOut(picked);
        JSONObject card = Cards.list(read.optString("what"), read.optJSONArray("items"));
        JSONArray items = card.getJSONArray("items");
        check("历史上的今天".equals(card.getString("title")) && items.length() == 4, "the day's four entries");
        check(items.getJSONObject(0).getString("title").startsWith("1867 年，"), "an entry's title is its year and what happened");
        String summary = read.getJSONArray("items").getJSONObject(0).getString("summary");
        String meta = items.getJSONObject(0).getString("meta");
        check(summary.length() > 28 ? meta.equals(summary.substring(0, 28) + "…") : meta.equals(summary),
                "an entry's meta is the start of what happened: " + meta);
        check(meta.length() <= 29, "a long entry is cut to what fits");
    }

    static void movies() throws Exception {
        List<Movies.Item> films = Movies.items(fixture("douban-list-tv.json"));
        JSONArray rows = new JSONArray();
        for (int i = 0; i < Math.min(10, films.size()); i++)
            rows.put(new JSONObject().put("title", films.get(i).name).put("rating", films.get(i).rating).put("year", films.get(i).year)
                    .put("release", films.get(i).release).put("cast", films.get(i).cast));
        JSONObject card = Cards.list("近期热门剧集", rows);
        JSONArray items = card.getJSONArray("items");
        check(items.length() == 5 && "近期热门剧集".equals(card.getString("title")), "five of the list");
        check(films.get(0).name.equals(items.getJSONObject(0).getString("title")), "the film's name as the row has it");
        String meta = items.getJSONObject(0).getString("meta");
        check(meta.startsWith("豆瓣 ") && (films.get(0).cast.isEmpty() || meta.equals("豆瓣 "
                + String.format(java.util.Locale.ROOT, "%.1f", films.get(0).rating) + " · " + films.get(0).cast)),
                "a film's meta is its Douban score and its leads: " + meta);
        check(meta.contains(" · ") && meta.contains("、"), "the leads are the two the row carries: " + meta);
        // a film nobody has rated yet: no score, only who is in it
        JSONArray unrated = new JSONArray().put(new JSONObject().put("title", "还没上映").put("rating", 0).put("cast", "某人"));
        JSONObject plain = Cards.list("片单", unrated);
        check("某人".equals(plain.getJSONArray("items").getJSONObject(0).getString("meta")), "no score, no 「豆瓣」: " + plain);
    }

    static void limits() throws Exception {
        JSONArray many = new JSONArray();
        for (int i = 0; i < 9; i++) many.put(new JSONObject().put("n", i + 1).put("title", "第 " + (i + 1) + " 条"));
        check(Cards.list("九条", many).getJSONArray("items").length() == 5, "nine items: the panel draws five");
        check(Cards.list("", many) == null && Cards.list(null, many) == null, "no title, no card");
        check(Cards.list("空的", new JSONArray()) == null && Cards.list("空的", null) == null, "no items, no card");
        check(Cards.list("没标题的", new JSONArray().put(new JSONObject().put("n", 1).put("summary", "只有摘要"))) == null,
                "an item with no title, no card");
        JSONArray mixed = new JSONArray().put(new JSONObject().put("n", 1)).put(new JSONObject().put("n", 2).put("title", "有标题"));
        check(Cards.list("混着的", mixed).getJSONArray("items").length() == 1, "an item with no title is passed over: "
                + Cards.list("混着的", mixed));
    }

    // ---- the weather card --------------------------------------------------------------------------

    static void weather() throws Exception {
        JSONObject f = new JSONObject(fixture("weather-hourly.json"));
        long morning = Sports.parse("2026-11-06T00:20Z");   // 08:20 in Shanghai
        JSONObject today = Weather.card("上海徐汇", f, "", morning);
        check("weather".equals(today.getString("type")) && "上海徐汇".equals(today.getString("place")), "the place it was asked for");
        check("今天 11月6日 周五".equals(today.getString("day")), "today's own line: " + today.getString("day"));
        check("阴".equals(today.getString("sky")) && today.getInt("lo") == 14 && today.getInt("hi") == 19,
                "the sky and the day's low and high, rounded: " + today.getInt("lo") + "–" + today.getInt("hi"));
        check(today.getInt("rain") == 0, "the day's chance of rain: " + today.getInt("rain"));
        JSONArray hours = today.getJSONArray("hours");
        check(hours.length() == 5 && hours.getJSONObject(0).getInt("h") == 9 && hours.getJSONObject(4).getInt("h") == 21,
                "from the next whole hour, three apart, to the end of the day: " + hours.length());
        check(hours.getJSONObject(0).getDouble("t") == 15.5 && hours.getJSONObject(1).getDouble("t") == 18.0
                && hours.getJSONObject(3).getDouble("t") == 17.3, "the hour's temperature to one decimal: " + hours);
        check(hours.getJSONObject(0).getInt("p") == 0 && Cards.valid(today), "its chance of rain, and the panel can draw it");

        JSONObject tomorrow = Weather.card("上海徐汇", f, "明天", morning);
        check("明天 11月7日 周六".equals(tomorrow.getString("day")) && tomorrow.getInt("lo") == 16 && tomorrow.getInt("hi") == 18
                && tomorrow.getInt("rain") == 20, "tomorrow, its own line: " + tomorrow.getString("day"));
        check(tomorrow.getJSONArray("hours").length() == 8
                && tomorrow.getJSONArray("hours").getJSONObject(0).getInt("h") == 0
                && tomorrow.getJSONArray("hours").getJSONObject(7).getInt("h") == 21, "another day: the whole day, 0 to 21");
        check(tomorrow.getJSONArray("hours").getJSONObject(4).getDouble("t") == 18.3, "the afternoon's temperature: "
                + tomorrow.getJSONArray("hours").getJSONObject(4));

        check("明天 11月7日 周六".equals(Weather.card("上海徐汇", f, "周六", morning).getString("day")),
                "「周六」 from a Friday is tomorrow");
        JSONObject few = Weather.card("上海徐汇", f, "这几天", morning);
        check("今天 11月6日 周五".equals(few.getString("day")), "several days asked: the first one is drawn");
        check("后天 11月8日 周日".equals(Weather.card("上海徐汇", f, "后天", morning).getString("day")), "the day after tomorrow");
        check("11月9日 周一".equals(Weather.card("上海徐汇", f, "2026-11-09", morning).getString("day")),
                "a day further off has no 今天 before it: " + Weather.card("上海徐汇", f, "2026-11-09", morning).getString("day"));
        check("今天 11月6日 周五".equals(Weather.card("上海徐汇", f, "今晚", morning).getString("day")),
                "a part of a day is that day");

        // the hourly forecast runs out: only the two hours of the 8th it still has
        JSONObject after = Weather.card("上海徐汇", f, "后天", morning);
        check(after.getJSONArray("hours").length() == 2 && after.getJSONArray("hours").getJSONObject(0).getInt("h") == 0
                && after.getJSONArray("hours").getJSONObject(1).getInt("h") == 3, "hours the forecast does not have are left out: "
                + after.getJSONArray("hours"));
        check(after.getJSONArray("hours").getJSONObject(1).getInt("p") == 93, "the rain chance of the hour it has: "
                + after.getJSONArray("hours"));

        check(!Weather.card("上海徐汇", f, "", Sports.parse("2026-11-06T14:10Z")).has("hours"),
                "22:10: one hour of the day left is not a line");
        check(!Weather.card("上海徐汇", f, "", Sports.parse("2026-11-06T15:30Z")).has("hours"),
                "23:30: the day has no whole hour left");
        check(Weather.card("上海徐汇", f, "", Sports.parse("2026-11-06T01:00Z")).getJSONArray("hours").getJSONObject(0).getInt("h") == 9,
                "on the hour, that hour is the next one");
        check(!Weather.card("上海徐汇", new JSONObject(fixture("weather-forecast.json")), "", morning).has("hours"),
                "a forecast without hours: no strip, the rest of the card as usual");
        check(Cards.valid(Weather.card("上海徐汇", new JSONObject(fixture("weather-forecast.json")), "今天", morning)),
                "the panel can draw it without the hours");
        boolean threw = false;
        try { Weather.card("上海徐汇", f, "下周六", morning); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "a day the forecast does not cover is refused, as the answer is");
    }

    // ---- what the panel can draw -------------------------------------------------------------------

    static void valid() throws Exception {
        check(!Cards.valid(null), "no card at all");
        check(!Cards.valid(new JSONObject().put("type", "quote")) && !Cards.valid(new JSONObject()), "an unknown type, an empty card");

        JSONObject w = Weather.card("上海徐汇", new JSONObject(fixture("weather-hourly.json")), "今天", Sports.parse("2026-11-06T00:20Z"));
        check(Cards.valid(w), "a whole weather card");
        for (String field : new String[]{"place", "day", "sky", "lo", "hi"}) {
            JSONObject missing = new JSONObject(w.toString());
            missing.remove(field);
            check(!Cards.valid(missing), "a weather card without " + field + " is not drawn");
        }
        check(!Cards.valid(new JSONObject(w.toString()).put("lo", "14")), "a low that is not a number is not drawn");
        JSONObject bare = new JSONObject(w.toString());
        bare.remove("rain");
        bare.remove("hours");
        check(Cards.valid(bare), "rain and hours are the card's own extras, not required");

        JSONObject s = Cards.stocks(Quotes.parse(fixture("quotes-qt.txt")), friday());
        check(Cards.valid(s), "a whole quote card");
        check(!Cards.valid(new JSONObject(s.toString()).put("rows", new JSONArray())), "no rows, no card");
        JSONObject noRows = new JSONObject(s.toString());
        noRows.remove("rows");
        check(!Cards.valid(noRows), "a quote card without rows is not drawn");
        for (String field : new String[]{"name", "price", "pct"}) {
            JSONArray rows = new JSONArray(new JSONObject(s.toString()).getJSONArray("rows").toString());
            rows.getJSONObject(0).remove(field);
            check(!Cards.valid(new JSONObject(s.toString()).put("rows", rows)), "a row without " + field + " sinks the card");
        }
        check(!Cards.valid(new JSONObject(s.toString()).put("rows", new JSONArray().put(new JSONObject().put("name", "茅台")))),
                "a row with nothing but a name sinks the card");

        JSONObject l = Cards.list("榜单", new JSONArray().put(new JSONObject().put("title", "第一条").put("meta", "甲")));
        check(Cards.valid(l), "a whole list card");
        JSONObject noTitle = new JSONObject(l.toString());
        noTitle.remove("title");
        check(!Cards.valid(noTitle), "a list without a title is not drawn");
        check(!Cards.valid(new JSONObject(l.toString()).put("items", new JSONArray())), "a list with no items is not drawn");
        JSONObject noItems = new JSONObject(l.toString());
        noItems.remove("items");
        check(!Cards.valid(noItems), "a list without items at all is not drawn");
        check(!Cards.valid(new JSONObject(l.toString()).put("items", new JSONArray().put(new JSONObject().put("meta", "甲")))),
                "an item without a title is not drawn");
        JSONObject noMeta = new JSONObject(l.toString());
        noMeta.getJSONArray("items").getJSONObject(0).remove("meta");
        check(Cards.valid(noMeta), "an item's meta line is its own extra, not required");
    }

    // ---- what the panel draws of it -----------------------------------------------------------------

    /** 2026-11-09 review: a panel that had stopped still drew the step that was in progress with the running
     * marker, and a reply that had nothing to do with the card an earlier step left on the ledger was cut to the
     * card's two caption lines. Both decisions live in PanelRules, so they are checked here. */
    static void panel() throws Exception {
        steps();
        captions();
    }

    /** A step is drawn as the one running only while the panel still moves. */
    private static void steps() {
        check(PanelRules.stepDrawnRunning(true, "in_progress"), "while the work runs, the step in progress is the running one");
        check(!PanelRules.stepDrawnRunning(false, "in_progress"), "a stopped panel draws no step as running");
        check(!PanelRules.stepDrawnRunning(false, "pending") && !PanelRules.stepDrawnRunning(false, "done"),
                "nor the step still to come, nor one already done");
        check(PanelRules.stepDrawnRunning(true, "pending"), "while it runs, the first step still to come is the current one");
        check(!PanelRules.stepDrawnRunning(true, "done") && !PanelRules.stepDrawnRunning(true, "failed")
                        && !PanelRules.stepDrawnRunning(true, "skipped"),
                "a step that is over is never drawn as the running one");
        check(!PanelRules.stepDrawnRunning(true, null) && !PanelRules.stepDrawnRunning(true, ""),
                "a step with no state at all is not running");
    }

    /** The reply is the card's caption only when it fits under it or says what the card shows. */
    private static void captions() throws Exception {
        long morning = Sports.parse("2026-11-06T00:20Z");
        JSONObject hourly = new JSONObject(fixture("weather-hourly.json"));
        JSONObject beijing = Weather.card("北京", hourly, "今天", morning);
        JSONObject shanghai = Weather.card("上海徐汇", hourly, "今天", morning);
        String said = "北京今天晴，最高 15 度";
        check(PanelRules.replyIsCaption(said, beijing), "「" + said + "」 under 北京's card is its caption");

        // the real answer to something else, longer than the two caption lines and naming nothing on the card
        String answer = "客厅的灯已经关了，空调调到 26 度，窗帘拉上一半，电视音量调到 12，"
                + "晚上八点会提醒你吃药，明天早上七点半叫你起床。";
        check(answer.length() > PanelRules.CAPTION_CHARS, "the answer used here is longer than a caption: " + answer.length());
        check(!PanelRules.replyIsCaption(answer, beijing), "a long answer about something else is not the weather card's caption");
        check(!PanelRules.replyIsCaption(answer, shanghai), "nor under another place's card");
        check(!PanelRules.replyIsCaption(answer, null), "with no card at all there is nothing to be a caption of");

        check(PanelRules.replyIsCaption(answer + "北京那边还是晴天。", beijing),
                "a long answer that names what the card shows stays its caption");
        check(!PanelRules.replyIsCaption(answer + "北京那边还是晴天。", shanghai),
                "the same words under a card for somewhere else are not its caption");

        // the budget itself: the caption is two lines, and the second character past them is one too many
        check(PanelRules.replyIsCaption(fill(48), shanghai) && !PanelRules.replyIsCaption(fill(49), shanghai),
                "48 characters fit the two caption lines, 49 do not");
        check(!PanelRules.replyIsCaption("一行\n两行\n三行", shanghai), "three lines of its own do not fit two caption lines");
        check(PanelRules.replyIsCaption("", shanghai) && PanelRules.replyIsCaption(null, shanghai),
                "nothing to draw is a caption either way");

        JSONObject quote = Cards.stocks(Quotes.parse(fixture("quotes-qt.txt")).subList(3, 4), friday());
        check(PanelRules.replyIsCaption("贵州茅台现在 1453.08 元", quote), "a quote's own name makes the reply its caption");
        check(!PanelRules.replyIsCaption(answer, quote), "a long answer about the room is not a quote card's caption");

        JSONObject chart = Cards.list("网易云热歌榜", new JSONArray()
                .put(new JSONObject().put("title", "我不难过").put("meta", "孙燕姿"))
                .put(new JSONObject().put("title", "晴天").put("meta", "周杰伦")));
        check(PanelRules.replyIsCaption("网易云热歌榜前五首都在这儿了", chart), "a list's own title makes the reply its caption");
        check(PanelRules.replyIsCaption(answer + "，顺便放了「我不难过」。", chart), "so does one of the items it lists");
        check(!PanelRules.replyIsCaption(answer, chart), "a long answer about the room is not a list card's caption");
    }

    /** A string of {@code n} characters that names nothing any card shows. */
    private static String fill(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append('甲');
        return b.toString();
    }

    // ---- how it travels ----------------------------------------------------------------------------

    static void ledger() throws Exception {
        JSONObject weatherCard = Weather.card("上海徐汇", new JSONObject(fixture("weather-hourly.json")), "今天",
                Sports.parse("2026-11-06T00:20Z"));
        JSONObject stockCard = Cards.stocks(Quotes.parse(fixture("quotes-qt.txt")), friday());

        AgentCoreTest.Fake w = new AgentCoreTest.Fake("weather", Tool.Gate.read());
        w.reply = AgentCoreTest.o("ok", true, "status", "observed", "answer", "今天阴", "card", weatherCard);
        Ledger t = run(w);
        check(t.card != null && "weather".equals(t.card.optString("type")) && "上海徐汇".equals(t.card.optString("place")),
                "a data tool's card travels to the ledger");

        AgentCoreTest.Fake q = new AgentCoreTest.Fake("stock_quote", Tool.Gate.read());
        q.reply = AgentCoreTest.o("ok", true, "status", "observed", "answer", "茅台", "card", stockCard);
        AgentCoreTest.Fake plain = new AgentCoreTest.Fake("news", Tool.Gate.read());
        plain.reply = AgentCoreTest.o("ok", true, "status", "observed", "answer", "新闻", "card", new JSONObject());
        Ledger both = run(w, q);
        check("stocks".equals(both.card.optString("type")), "the last tool's card is the one on screen");
        Ledger first = run(q, w);
        check("weather".equals(first.card.optString("type")), "in the other order, the other one");
        Ledger kept = run(w, plain);
        check("weather".equals(kept.card.optString("type")), "a tool with no card leaves the one that was there");
        Ledger none = run(plain);
        check(none.card == null, "no tool with a card, no card");

        AgentCoreTest.Fake bad = new AgentCoreTest.Fake("hot_list", Tool.Gate.read());
        bad.reply = AgentCoreTest.o("ok", true, "status", "observed", "answer", "热搜", "card",
                new JSONObject().put("type", "list"));
        check(run(bad).card == null, "a card the panel could not draw is not put on the ledger");

        Ledger saved = run(w);
        check(!saved.toJson().toString().contains("card"), "the card is not saved with the task");
        check(Ledger.fromJson(new JSONObject(saved.toJson().toString())).card == null, "and is not read back");
    }

    /** A task whose plan calls the tools given, in that order, and then finishes. */
    private static Ledger run(AgentCoreTest.Fake... tools) throws Exception {
        List<Object> calls = new ArrayList<>();
        calls.add("plan");
        calls.add(AgentCoreTest.plan("查一下", "查"));
        for (AgentCoreTest.Fake f : tools) {
            calls.add(f.name);
            calls.add(new JSONObject());
        }
        AgentCoreTest.Script m = new AgentCoreTest.Script().calls(calls.toArray())
                .calls("finish", AgentCoreTest.o("status", "done", "reply", "好了"));
        return AgentCoreTest.loop(m, new AgentCoreTest.Events(), (what, task) -> true, tools)
                .run(new AgentLoop.Request("查一下", "test"));
    }
}
