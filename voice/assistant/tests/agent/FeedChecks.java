package xyz.erapi0neer.xingyi.agent;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/** The public data feeds (2026-11-06, 「继续增加工具调用」): hot lists, quotes, exchange rates, against fixtures saved from
 * the real services that morning. */
final class FeedChecks {
    private FeedChecks() { }

    private static String fixture(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/" + name)), StandardCharsets.UTF_8);
    }

    // 2026-11-16: the four searches the name checks need, kept here instead of in fixtures/ so no new file has to be
    // added for them — the shape is the smartbox v_hint the saved pages have (market~code~name~initials~type^…).
    private static final String APPLE_EN = "v_hint=\"us~aapl.oq~Apple~apple~GP^us~aple.n~apple hospitality reit, inc~*~GP\"";
    private static final String MINIMAX = "v_hint=\"hk~00100~MiniMax~minimax~GP^hk~13100~MiniMax信证七七购A~minimaxxzqqga~QZ\"";
    private static final String MAX = "v_hint=\"hk~01037~MAX中国~maxzg~GP^us~max.n~Max~max~GP\"";

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    static void run() {
        try {
            hot();
            hotOne();
            quotes();
            search();
            rates();
            cache();
            gold();
            lottery();
            nba();
            nbaLast();
            lastSaid();
            football();
            worldClock();
            tvGuide();
            news();
            newsDay();
            charts();
            quake();
            prices();
            history();
        } catch (Exception e) {
            check(false, "feed checks threw " + e);
        }
    }

    static void hot() throws Exception {
        List<String> words = HotList.baidu(fixture("hot-baidu.json"));
        check(words.size() >= 20, "baidu: the board's topics: " + words.size());
        check("美股暴跌 遭遇历史性抛售".equals(words.get(0)), "the pinned editors' pick is left out: " + words.get(0));
        String said = HotList.speakBaidu(words, 3);
        check(said.equals("百度热搜前 3 条：第一，美股暴跌 遭遇历史性抛售；第二，东风-61已列装火箭军；第三，6G有啥不一样"), "baidu spoken: " + said);
        List<HotList.Video> videos = HotList.bili(fixture("hot-bili.json"));
        check(videos.size() == 10 && "BV1nm4g6NE71".equals(videos.get(0).bvid), "bili: the list with its BV numbers");
        String bili = HotList.speakBili(videos, 2);
        check(bili.startsWith("B站热门前 2 个：第一，《崩坏：因缘精灵》幸福PV：缘汽一下，崩坏因缘精灵，424 万播放；第二，《"), "bili spoken: " + bili);
        check(videos.get(0).toJson().optString("link").matches("bilibili://video/[0-9]+"), "each video carries its link (by aid): "
                + videos.get(0).toJson().optString("link"));
        org.json.JSONObject read = HotList.readOutVideos(videos, 3);
        check(read.getJSONArray("items").length() == 3 && read.getJSONArray("items").getJSONObject(1).getInt("n") == 2
                && read.getJSONArray("items").getJSONObject(1).getString("link").equals("bilibili://video/117563807766583"), "the list as read, numbered from 1");
        check(HotList.readOutTopics(words, 5).getJSONArray("items").getJSONObject(0).getString("title").equals(words.get(0)), "topics too");
        check("68 万".equals(HotList.count(681298)) && "9800 次".equals(HotList.count(9800)) && "1.2 亿".equals(HotList.count(120_000_000)), "counts");
        boolean threw = false;
        try { HotList.baidu("{\"success\":false}"); } catch (Exception e) { threw = true; }
        check(threw, "an unsuccessful board is an error, not an empty list");
    }

    /** 「百度热搜第一是什么」: one item is one sentence, not a list of one (2026-11-10 on the TV: 「百度热搜前 1 条：第一，
     * 全国陨石猎人扎堆赶赴石家庄」). */
    static void hotOne() throws Exception {
        List<String> words = HotList.baidu(fixture("hot-baidu.json"));
        String one = HotList.speakBaidu(words, 1);
        check(one.equals("百度热搜第一是「美股暴跌 遭遇历史性抛售」"), "百度热搜第一: " + one);
        check(!one.contains("前 1 条") && !one.contains("第一，"), "no list of one: " + one);
        check(HotList.speakBaidu(words, 3).equals("百度热搜前 3 条：第一，美股暴跌 遭遇历史性抛售；第二，东风-61已列装火箭军；第三，6G有啥不一样"),
                "more than one is still the numbered list: " + HotList.speakBaidu(words, 3));
        check(HotList.speakBaidu(new java.util.ArrayList<String>(), 1).equals("百度热搜这会儿是空的"), "an empty board is still empty");
        List<HotList.Video> videos = HotList.bili(fixture("hot-bili.json"));
        String bili = HotList.speakBili(videos, 1);
        check(bili.equals("B站热门第一是《崩坏：因缘精灵》幸福PV：缘汽一下，崩坏因缘精灵，424 万播放"), "B站热门第一: " + bili);
        check(HotList.speakBili(videos, 2).startsWith("B站热门前 2 个：第一，"), "two are the list as before: " + HotList.speakBili(videos, 2));
        List<HotList.Song> songs = HotList.chartSongs(fixture("chart-netease-hot.json"));
        String song = HotList.speakChart("网易云热歌榜", songs, 1);
        check(song.equals("网易云热歌榜第一是《Hong Kong (feat. 王菲季候风)》，The Weeknd"), "网易云热歌榜第一: " + song);
        check(HotList.speakChart("网易云热歌榜", songs, 3).startsWith("网易云热歌榜前 3 首：第一，"), "three are the list as before");
    }

    static void quotes() throws Exception {
        List<Quotes.Quote> qs = Quotes.parse(fixture("quotes-qt.txt"));
        check(qs.size() == 6, "six codes, six quotes: " + qs.size());
        Quotes.Quote sh = qs.get(0), maotai = qs.get(3), apple = qs.get(4), hsi = qs.get(5);
        check(sh.index() && "上证指数".equals(sh.name) && "3636.80".equals(sh.price), "the Shanghai index");
        check(!maotai.index() && "1453.08".equals(maotai.price) && "0.07".equals(maotai.pct), "a stock");
        check(apple.index() == false && "AAPL.OQ".equals(apple.code), "a US stock");
        check(hsi.index() && "24476.87".equals(hsi.price), "the Hang Seng index, three decimals cut to two: " + hsi.price);
        Calendar fri = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        fri.clear();
        fri.set(2026, Calendar.NOVEMBER, 6, 9, 50);
        String three = Quotes.speak(qs.subList(0, 3), fri);
        check(three.equals("上证指数 3636.80 点，跌 1.44%，跌了 53.11 点；深证成指 11221.47 点，跌 2.18%，跌了 249.62 点；"
                + "创业板指 2478.42 点，跌 3.44%，跌了 88.38 点（9 点 49 分）"), "the A-share indices, the time once: " + three);
        String mixed = Quotes.speak(qs.subList(3, 5), fri);
        check(mixed.equals("贵州茅台 1453.08 元，涨 0.07%（9 点 49 分）；苹果 344.17 美元，跌 1.53%（美东时间 11 月 5 日收盘）"),
                "a stock here and in New York, each with its time: " + mixed);
        Calendar sat = (Calendar) fri.clone();
        sat.set(2026, Calendar.NOVEMBER, 7, 10, 0);
        check(Quotes.speak(qs.subList(3, 4), sat).endsWith("（11 月 6 日收盘）"), "on the weekend: the day it closed");
        check(Quotes.parse("v_pv_none_match=\"1\";").isEmpty(), "an unknown code is left out");
        check("五粮液".equals(Quotes.spokenName("五 粮 液")) && "阿里巴巴".equals(Quotes.spokenName("阿里巴巴-W"))
                && "蔚来".equals(Quotes.spokenName("蔚来-SW")) && "谷歌".equals(Quotes.spokenName("谷歌-C"))
                && "Meta Platforms".equals(Quotes.spokenName("Meta Platforms")), "names as they are said");
        check("423.00".equals(Quotes.trim("423.000")) && "86.30".equals(Quotes.trim("86.3")) && "-1.44".equals(Quotes.trim("-1.440")), "trim");
    }

    static void search() throws Exception {
        check(Quotes.known("大盘").size() == 3 && Quotes.known("").size() == 3, "大盘 and nothing said: the three A-share indices");
        check("[sh000001]".equals(String.valueOf(Quotes.known("上证指数"))) && "[usIXIC]".equals(String.valueOf(Quotes.known("纳斯达克指数"))), "index names");
        check("[sh600519]".equals(String.valueOf(Quotes.known("600519"))) && "[sz002594]".equals(String.valueOf(Quotes.known("002594")))
                && "[sz300750]".equals(String.valueOf(Quotes.known("300750"))), "six-digit codes by their market");
        check("[hk00700]".equals(String.valueOf(Quotes.known("hk00700"))) && "[usAAPL]".equals(String.valueOf(Quotes.known("usaapl"))), "market codes");
        check(Quotes.known("茅台") == null, "a name needs the search");
        check("sh600519".equals(Quotes.pick(fixture("quotes-search-maotai.txt"), "茅台")), "茅台");
        check("hk00700".equals(Quotes.pick(fixture("quotes-search-tencent.txt"), "腾讯")), "腾讯: the stock, not 腾讯济安 the index");
        check("usAAPL".equals(Quotes.pick(fixture("quotes-search-apple.txt"), "苹果")), "苹果: AAPL, not the ETFs");
        check("sz002594".equals(Quotes.pick(fixture("quotes-search-byd.txt"), "比亚迪")), "比亚迪: the A share first");
        check("hk01810".equals(Quotes.pick(fixture("quotes-search-xiaomi.txt"), "小米")), "小米: Hong Kong, not the RMB counter");
        check("sh000001".equals(Quotes.pick(fixture("quotes-search-szzs.txt"), "上证指数")), "an index asked for: the index, not its ETFs");
        check(Quotes.pick("v_hint=\"N\";", "不存在的公司") == null, "nothing found");
        check("usNVDA".equals(Quotes.pick(fixture("quotes-search-nvidia.txt"), "英伟达")), "英伟达: NVIDIA itself, not a leveraged short in Hong Kong");
        check("hk01810".equals(Quotes.pick(fixture("quotes-search-xiaomi.txt"), "小米集团")), "a fuller name still finds it");
        // 2026-11-16: the whole name in either case, but only the whole name — the prefix tier stays case-blind (AMD would
        // take a WEEK ETF, ARM armada)
        check("usAAPL".equals(Quotes.pick(APPLE_EN, "apple")), "apple in lower case: AAPL, not APLE");
        check("hk00100".equals(Quotes.pick(MINIMAX, "MINIMAX")), "MINIMAX: the stock, not its warrant");
        check(!"hk01037".equals(Quotes.pick(MAX, "MAX")), "MAX: not the RMB counter");
        check(Quotes.parts("MINI MAX").size() == 1, "parts: 「MINI MAX」 is one name: " + Quotes.parts("MINI MAX"));
        check(Quotes.parts("苹果 AAPL").equals(java.util.Arrays.asList("苹果", "AAPL")), "parts: a Chinese name and a code still split");
        resolve();
    }

    /** What a request's names come to (2026-11-16): the whole sentence first, codes deduplicated, a name the search does
     * not find left out instead of failing the request, and the names past the sixth said rather than dropped. */
    static void resolve() throws Exception {
        Map<String, String> pages = new java.util.HashMap<>();
        pages.put(Quotes.SEARCH + java.net.URLEncoder.encode("MINI", "UTF-8"), MINIMAX);
        pages.put(Quotes.SEARCH + java.net.URLEncoder.encode("MINI、QQZZ", "UTF-8"), "v_hint=\"N\";");
        pages.put(Quotes.SEARCH + java.net.URLEncoder.encode("QQZZ", "UTF-8"), "v_hint=\"N\";");
        pages.put(Quotes.SEARCH + java.net.URLEncoder.encode("苹果", "UTF-8"), APPLE_EN);
        // the whole sentence 「苹果AAPL」: the search has no such name, so the request falls back to its parts
        pages.put(Quotes.SEARCH + java.net.URLEncoder.encode("苹果AAPL", "UTF-8"), "v_hint=\"N\";");
        pages.put(Quotes.SEARCH + java.net.URLEncoder.encode("AAPL", "UTF-8"), APPLE_EN);
        pages.put(Quotes.SEARCH + java.net.URLEncoder.encode("茅台", "UTF-8"), fixture("quotes-search-maotai.txt"));
        pages.put(Quotes.SEARCH + java.net.URLEncoder.encode("腾讯", "UTF-8"), fixture("quotes-search-tencent.txt"));
        pages.put(Quotes.SEARCH + java.net.URLEncoder.encode("小米", "UTF-8"), fixture("quotes-search-xiaomi.txt"));
        Feeds.Fetch feed = (url, cs, t) -> {
            String p = pages.get(url);
            // a search finds nothing it has no fixture for, as the real one does for a sentence no name matches
            return p == null ? "v_hint=\"N\";" : p;
        };
        Quotes.Found mixed = Quotes.resolve("苹果 AAPL", feed);
        check(mixed.codes.size() == 1 && mixed.codes.contains("usAAPL"), "苹果 AAPL: one code, read out once: " + mixed.codes);
        Quotes.Found partial = Quotes.resolve("MINI、QQZZ", feed);
        check(partial.codes.contains("hk00100") && partial.notFound.equals(java.util.Arrays.asList("QQZZ")),
                "a name no search finds is left out, the other is still looked up: " + partial.codes + " " + partial.notFound);
        Quotes.Found listed = Quotes.resolve("茅台 腾讯", feed);
        check(listed.codes.contains("sh600519") && listed.codes.contains("hk00700") && listed.notFound.isEmpty(),
                "茅台 腾讯: both, in one request: " + listed.codes + " " + listed.notFound);
        Quotes.Found un = Quotes.resolve("华为 小米", feed);
        check(un.notListed.size() == 1 && un.codes.contains("hk01810"), "华为 is not listed, 小米 is: " + un.notListed + " " + un.codes);
        Quotes.Found many = Quotes.resolve("大盘、美股、港股", feed);
        check(many.codes.size() == 6 && many.skipped.equals(java.util.Arrays.asList("港股")),
                "past six names the rest are skipped, not dropped: " + many.codes + " " + many.skipped);
    }

    static void rates() throws Exception {
        Rates.Parity p = Rates.parity(fixture("fx-ccpr.json"));
        Map<String, Double> m = Rates.market(fixture("fx-erapi.json"));
        check(Math.abs(p.cnyPer.get("USD") - 6.7168) < 1e-9 && Math.abs(p.cnyPer.get("JPY") - 0.042534) < 1e-9
                && Math.abs(p.cnyPer.get("THB") - 1 / 5.0038) < 1e-9, "CNY per unit, 100 yen and the inverse pairs");
        check("USD".equals(Rates.code("美元")) && "USD".equals(Rates.code("美金")) && "HKD".equals(Rates.code("港币"))
                && "CNY".equals(Rates.code("人民币")) && "EUR".equals(Rates.code("eur")) && Rates.code("比特币") == null, "names");
        String usd = Rates.answer("USD", "CNY", 1, p, m);
        check(usd.equals("今天人民币汇率中间价：1 美元兑 6.7168 元人民币，人民币比上个交易日调贬 12 个基点（11 月 6 日 9 点 15 分公布）"), "1 美元: " + usd);
        String hundred = Rates.answer("USD", "CNY", 100, p, m);
        check(hundred.contains("；100 美元约合 671.68 元人民币（"), "100 美元: " + hundred);
        String yen = Rates.answer("JPY", "CNY", 1, p, m);
        check(yen.startsWith("今天人民币汇率中间价：100 日元兑 4.2534 元人民币"), "yen per 100: " + yen);
        String thb = Rates.answer("CNY", "THB", 100, p, m);
        check(thb.startsWith("今天人民币汇率中间价：1 元人民币兑 5.0038 泰铢") && thb.contains("100 元人民币约合 500.38 泰铢"), "RMB to baht: " + thb);
        String cross = Rates.answer("USD", "JPY", 1, p, m);
        check(cross.equals("1 美元约合 157.84 日元（国际市场参考价，每天更新一次）"), "a pair without the RMB: the market rate: " + cross);
        String twd = Rates.answer("TWD", "CNY", 100, p, m);
        check(twd.startsWith("100 新台币约合 20.22 元人民币"), "a currency the parity leaves out: " + twd);
        check(Rates.answer("USD", "USD", 1, p, m).equals("这是同一种货币"), "the same currency");
        check(Rates.answer("XXX", "CNY", 1, p, m) == null, "unknown: null (the tool says so)");
        check(Rates.needsMarket("USD", "JPY", p) && !Rates.needsMarket("USD", "CNY", p) && Rates.needsMarket("TWD", "CNY", p), "needsMarket");
    }

    static void gold() throws Exception {
        Calendar fri = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        fri.clear();
        fri.set(2026, Calendar.NOVEMBER, 6, 10, 10);
        String g = Metals.speakGold(fixture("gold-sina.txt"), fri);
        check(g.equals("国内金价（上海黄金交易所 Au99.99）871.90 元一克，比上个交易日跌 1.38%；国际金价（伦敦金）4062.56 美元一盎司，跌 0.64%"
                + "（10 点 8 分）。金店的首饰金价比这个高，以各家牌价为准"), "gold: " + g);
        check(Metals.speakGold("var hq_str_gds_AU9999=\"\";", fri) == null, "nothing came: null");
        Calendar sat = (Calendar) fri.clone();
        sat.set(2026, Calendar.NOVEMBER, 7, 10, 0);
        check(Metals.speakGold(fixture("gold-sina.txt"), sat).contains("（11 月 6 日）"), "another day: the date");
    }

    static void lottery() throws Exception {
        String ssq = Lottery.speak("双色球", "cwl:ssq", fixture("lottery-ssq.json"));
        check(ssq.equals("双色球第 2026127 期（11 月 5 日 周四）：红球 13、14、19、25、29、32，蓝球 12；一等奖 3 注，每注 1000 万元"), "双色球: " + ssq);
        String d3 = Lottery.speak("福彩 3D", "cwl:3d", fixture("lottery-3d.json"));
        check(d3.equals("福彩 3D第 2026295 期（11 月 5 日 周四）：7、4、1"), "3D: " + d3);
        String qlc = Lottery.speak("七乐彩", "cwl:qlc", fixture("lottery-qlc.json"));
        check(qlc.startsWith("七乐彩第 2026127 期（11 月 4 日 周三）：基本号 08、11、15、24、26、27、30，特别号 28；一等奖 3 注，每注 43.5 万元"), "七乐彩: " + qlc);
        String dlt = Lottery.speak("大乐透", "tc:85", fixture("lottery-tc85.json"));
        check(dlt.equals("大乐透第 26126 期（11 月 4 日 周三）：前区 05、11、17、25、34，后区 04、06；一等奖 9 注，每注 624.7 万元"), "大乐透: " + dlt);
        String p3 = Lottery.speak("排列三", "tc:35", fixture("lottery-tc35.json"));
        check(p3.equals("排列三第 26295 期（11 月 5 日 周四）：2、9、1"), "排列三: " + p3);
        String qxc = Lottery.speak("七星彩", "tc:04", fixture("lottery-tc04.json"));
        check(qxc.startsWith("七星彩第 26126 期（11 月 3 日 周二）：3、3、3、4、1、7、8；一等奖 1 注，每注 417.9 万元"), "七星彩: " + qxc);
        check("[双色球, cwl:ssq]".equals(java.util.Arrays.toString(Lottery.game("双色球"))) && "tc:85".equals(Lottery.game("超级大乐透")[1])
                && "cwl:3d".equals(Lottery.game("福彩3D")[1]) && Lottery.game("刮刮乐") == null, "games");
        check("1000 万元".equals(Lottery.money("10000000")) && "43.5 万元".equals(Lottery.money("434572")) && "1040 元".equals(Lottery.money("1040")), "money");
        boolean threw = false;
        try { Lottery.speak("大乐透", "tc:85", "{\"success\":false,\"errorMessage\":\"x\"}"); } catch (Exception e) { threw = true; }
        check(threw, "a refused answer is an error");
    }

    static void nba() throws Exception {
        // 10:20 on Friday 6 November 2026, Beijing time
        long now = Sports.parse("2026-11-06T02:20Z");
        check("[18, NY, 尼克斯]".equals(java.util.Arrays.toString(Sports.team("尼克斯"))) && "NY".equals(Sports.team("纽约尼克斯")[1])
                && "DAL".equals(Sports.team("小牛")[1]) && "PHI".equals(Sports.team("76人")[1]) && Sports.team("曼联") == null, "team names");
        String team = Sports.speakTeam(fixture("nba-schedule-nyk.json"), "NY", now);
        check(team.equals("尼克斯上一场是昨天上午 8 点 30 分，主场 135 比 105 赢了公牛；下一场是 11 月 8 日上午 9 点，客场打骑士"), "a team: " + team);
        String live = Sports.speakBoard(fixture("nba-board-live.json"), "今天", now);
        check(live.equals("北京时间今天的 NBA 有 2 场：魔术 16 比 13 领先太阳，第 1 节还剩 6 分 47 秒；热火对勇士（勇士主场），今天上午 11 点开始"),
                "a live board: " + live);
        String done = Sports.speakBoard(fixture("nba-board-final.json"), "昨天", now);
        check(done.startsWith("北京时间昨天的 NBA 有 14 场：76人 112 比 106 胜活塞；老鹰 120 比 105 胜奇才；凯尔特人 109 比 102 胜雄鹿")
                && done.endsWith("；还有 6 场"), "a finished board: " + done);
        java.util.Calendar fri = java.util.Calendar.getInstance(Sports.BEIJING);
        fri.clear();
        fri.set(2026, java.util.Calendar.NOVEMBER, 6);
        check(Sports.boardUrl(fri).endsWith("scoreboard?dates=20261105"), "Beijing's Friday is the US Thursday: " + Sports.boardUrl(fri));
        check("6 分 47 秒".equals(Sports.clockWords("6:47")) && "45 秒".equals(Sports.clockWords("45.3")) && Sports.clockWords("0.0").isEmpty()
                && "12 分".equals(Sports.clockWords("12:00")), "the game clock in words");
    }

    /** The schedule's first n games only (a copy, in memory): the fixture's ninth is the Knicks' next one. */
    private static String firstGames(String json, int n) throws Exception {
        org.json.JSONObject o = new org.json.JSONObject(json);
        org.json.JSONArray events = o.getJSONArray("events"), keep = new org.json.JSONArray();
        for (int i = 0; i < n && i < events.length(); i++) keep.put(events.get(i));
        return o.put("events", keep).toString();
    }

    /** 「湖人昨天赢了吗」: the last game that is over, said first (2026-11-10 sweep: the answer only had the live game
     * and the next one, never how the last one ended). */
    static void nbaLast() throws Exception {
        long now = Sports.parse("2026-11-06T02:20Z"); // 10:20 on Friday 6 November, Beijing
        String json = fixture("nba-schedule-nyk.json");
        String won = Sports.speakTeam(json, "NY", now, true);
        check(won.equals("尼克斯上一场（昨天上午）135 比 105 赢了公牛；下一场是 11 月 8 日上午 9 点，客场打骑士"), "last: the finished game first, then the next: " + won);
        check(!won.contains("客场 135") && !won.contains("上一场是"), "last: the result, not the tip-off time and the arena: " + won);
        check(Sports.speakTeam(json, "NY", now).equals("尼克斯上一场是昨天上午 8 点 30 分，主场 135 比 105 赢了公牛；下一场是 11 月 8 日上午 9 点，客场打骑士"),
                "without last: today's sentence, unchanged");
        check(Sports.dayPart(Sports.parse("2026-11-05T00:30Z"), now).equals("昨天上午")
                        && Sports.dayPart(Sports.parse("2026-11-03T00:30Z"), now).equals("11 月 3 日")
                        && Sports.dayPart(Sports.parse("2026-11-06T02:00Z"), now).equals("今天上午"),
                "the game's Beijing day and part of it: " + Sports.dayPart(Sports.parse("2026-11-03T00:30Z"), now));

        // a lost one: on 10 月 26 日 in Beijing the Knicks' last finished game was the 102-111 home loss to 魔术
        long oct26 = Sports.parse("2026-10-26T02:20Z");
        String lost = Sports.speakTeam(firstGames(json, 3), "NY", oct26, true);
        check(lost.equals("尼克斯上一场（今天早上）102 比 111 输给了魔术"), "last: a loss says 输给了: " + lost);

        // the same schedule with its last finished game still on: the finished one before it, then 「现在正在打」
        String done = "\"name\":\"STATUS_FINAL\"";
        int lastFinal = json.lastIndexOf(done);
        String inProgress = json.substring(0, lastFinal) + "\"name\":\"STATUS_IN_PROGRESS\"" + json.substring(lastFinal + done.length());
        String live = Sports.speakTeam(inProgress, "NY", now, true);
        check(live.equals("尼克斯上一场（11 月 3 日）103 比 90 赢了篮网；现在正在打：尼克斯 135 比 105 领先公牛，第 4 节结束；下一场是 11 月 8 日上午 9 点，客场打骑士"),
                "last: the finished game, then the one on now: " + live);
        check(Sports.speakTeam(inProgress, "NY", now).equals("尼克斯正在比赛：尼克斯 135 比 105 领先公牛，第 4 节结束；下一场是 11 月 8 日上午 9 点，客场打骑士"),
                "without last: the live game still comes first");

        // nothing finished yet in the schedule: said in a few words, then as today
        String sched = Sports.speakTeam(json.replace("STATUS_FINAL", "STATUS_SCHEDULED"), "NY", now, true);
        check(sched.equals("尼克斯这个赛季还没打过比赛；下一场是 11 月 8 日上午 9 点，客场打骑士"), "none finished: said so, then the next: " + sched);
        // nothing finished and one on now: said as it is said without last
        String opening = Sports.speakTeam(firstGames(json, 3).replace("STATUS_FINAL", "STATUS_IN_PROGRESS"), "NY", oct26, true);
        check(opening.equals("尼克斯正在比赛：魔术 111 比 102 领先尼克斯，第 4 节结束"), "none finished, one on now: today's wording: " + opening);
        check(Sports.speakTeam("{\"events\":[]}", "NY", now, true).equals("尼克斯这个赛季还没打过比赛"), "no games at all, with last");
        check(Sports.speakTeam("{\"events\":[]}", "NY", now).equals("尼克斯这个赛季的赛程里没有查到比赛"), "without last: today's wording for an empty schedule");
    }

    /** 「湖人昨天赢了吗」 said as a sentence (2026-11-10 on the TV: 「湖人上一场 今天 124 比 114 赢了老鹰」 — the day was
     * loose between the words and never said when in the day the game was): 「上一场（今天上午）124 比 114 赢了老鹰」. */
    static void lastSaid() throws Exception {
        long now = Sports.parse("2026-11-06T02:20Z"); // 10:20 on Friday 6 November, Beijing
        String json = fixture("nba-schedule-nyk.json");
        // this morning: the game started at 8:30 Beijing on the day it is asked about
        String morning = Sports.speakTeam(json, "NY", Sports.parse("2026-11-05T02:20Z"), true);
        check(morning.startsWith("尼克斯上一场（今天上午）135 比 105 赢了公牛"), "today morning, in brackets: " + morning);
        check(!morning.contains("上一场 ") && !morning.contains("） 135") && !morning.contains("  135"),
                "no stray space around the day: " + morning);
        // yesterday evening: the same game, its tip-off moved to 19:30 Beijing the day before
        String evening = Sports.speakTeam(json.replace("2026-11-05T00:30Z", "2026-11-05T11:30Z"), "NY", now, true);
        check(evening.startsWith("尼克斯上一场（昨天晚上）135 比 105 赢了公牛"), "yesterday evening: " + evening);
        // an older game: the date, still in brackets
        String older = Sports.speakTeam(firstGames(json, 7), "NY", now, true);
        check(older.equals("尼克斯上一场（11 月 3 日）103 比 90 赢了篮网"), "an older game: the date: " + older);
        // a loss: 输给了, said the same way
        String lost = Sports.speakTeam(firstGames(json, 2), "NY", Sports.parse("2026-10-24T02:20Z"), true);
        check(lost.equals("尼克斯上一场（今天早上）103 比 107 输给了凯尔特人"), "a loss: " + lost);
        // the part of the day, from the game's own start (Beijing)
        check("今天上午".equals(Sports.dayPart(Sports.parse("2026-11-06T02:00Z"), now))
                && "今天下午".equals(Sports.dayPart(Sports.parse("2026-11-06T08:00Z"), now))
                && "昨天晚上".equals(Sports.dayPart(Sports.parse("2026-11-05T15:00Z"), now))
                && "今天凌晨".equals(Sports.dayPart(Sports.parse("2026-11-05T18:00Z"), now)),
                "上午 / 下午 / 晚上 / 凌晨 from the game's start: " + Sports.dayPart(Sports.parse("2026-11-05T18:00Z"), now));
    }

    static void football() throws Exception {
        long now = Sports.parse("2026-11-06T02:20Z");
        check("[eng.1, 360, 曼联]".equals(java.util.Arrays.toString(Football.club("曼联"))) && "977".equals(Football.club("上海申花")[1])
                && "977".equals(Football.club("申花")[1]) && "110".equals(Football.club("国际米兰")[1]) && "103".equals(Football.club("米兰")[1])
                && "160".equals(Football.club("巴黎")[1]) && Football.club("凯尔特人") == null && Football.club("湖人") == null, "club names");
        check("eng.1".equals(Football.league("今天有什么英超比赛")[0]) && "chn.1".equals(Football.league("中超")[0]) && Football.league("NBA") == null, "leagues");
        String mu = Football.speakClub(fixture("football-results-mu.json"), fixture("football-fixtures-mu.json"), "360", "曼联", now);
        check(mu.startsWith("曼联上一场是 10 月 31 日晚上 8 点 30 分，客场 0 比 2 输给了切尔西；下一场是") && mu.endsWith("主场打阿斯顿维拉"), "a club: " + mu);
        java.util.Calendar oct31 = java.util.Calendar.getInstance(Sports.BEIJING);
        oct31.clear();
        oct31.set(2026, java.util.Calendar.OCTOBER, 31, 12, 0);
        String board = Football.speakBoard(java.util.Collections.singletonList(fixture("football-board-epl-1031.json")), "英超", oct31, now);
        check(board.equals("北京时间 10 月 31 日的英超有 6 场：切尔西 2 比 0 胜曼联；伯恩茅斯 2 比 0 胜利兹联；布伦特福德 2 比 0 胜诺丁汉森林；"
                + "考文垂 3 比 1 胜桑德兰；赫尔城 2 比 2 战平伊普斯维奇；布莱顿 1 比 0 胜曼城"), "a league's Beijing day (two late games are the next day's): " + board);
        check(Football.names().startsWith("") && Football.names().contains("曼联") && Football.names().indexOf("巴黎圣日耳曼") < Football.names().indexOf("|巴黎|"),
                "names, the longer first");
    }

    static void worldClock() throws Exception {
        java.util.TimeZone home = java.util.TimeZone.getTimeZone("Asia/Shanghai");
        long winter = Sports.parse("2026-11-06T05:30Z"), summer = Sports.parse("2026-07-01T05:30Z");
        check(java.util.Arrays.equals(WorldClock.place("纽约"), new String[]{"America/New_York", "纽约"}) && WorldClock.place("纽约市") != null
                && WorldClock.place("火星") == null, "places");
        String ny = WorldClock.now("America/New_York", "纽约", winter, home);
        check(ny.equals("纽约现在是凌晨 0 点 30 分，比北京晚 13 小时"), "New York in November (no summer time): " + ny);
        String nySummer = WorldClock.now("America/New_York", "纽约", summer, home);
        check(nySummer.equals("纽约现在是凌晨 1 点 30 分，比北京晚 12 小时（现在是夏令时）"), "New York in July: " + nySummer);
        String sydney = WorldClock.now("Australia/Sydney", "悉尼", Sports.parse("2026-11-06T15:30Z"), home);
        check(sydney.startsWith("悉尼现在是 11 月 7 日星期六凌晨 2 点 30 分，比北京早 3 小时"), "another day is said: " + sydney);
        check(WorldClock.now("Asia/Tokyo", "东京", winter, home).endsWith("比北京早 1 小时"), "Tokyo");
        check(WorldClock.now("Asia/Kolkata", "新德里", winter, home).endsWith("比北京晚 2 小时 30 分"), "India's half hour");
        check(WorldClock.now("Asia/Hong_Kong", "香港", winter, home).endsWith("和北京时间一样"), "Hong Kong");
        String london = WorldClock.now("Europe/London", "伦敦", winter, home);
        check(london.equals("伦敦现在是早上 5 点 30 分，比北京晚 8 小时"), "London: " + london);
        check(WorldClock.names().indexOf("布宜诺斯艾利斯") < WorldClock.names().indexOf("|美国|"), "names, the longer first");
    }

    static void tvGuide() throws Exception {
        check(java.util.Arrays.equals(TvGuide.channel("CCTV5"), new String[]{"cctv5", "CCTV-5 体育"}), "CCTV5");
        check("cctv5".equals(TvGuide.channel("央视五套")[0]) && "cctv5".equals(TvGuide.channel("中央5台")[0]) && "cctv5plus".equals(TvGuide.channel("CCTV-5+")[0])
                && "cctv13".equals(TvGuide.channel("cctv 13")[0]) && "cctv13".equals(TvGuide.channel("央视十三套")[0])
                && "cctvjilu".equals(TvGuide.channel("CCTV9")[0]) && "cctvchild".equals(TvGuide.channel("少儿频道")[0])
                && "hunan".equals(TvGuide.channel("湖南卫视")[0]) && "dongfang".equals(TvGuide.channel("上海卫视")[0])
                && "cctv5".equals(TvGuide.channel("体育频道")[0]) && TvGuide.channel("B站") == null && TvGuide.channel("CCTV99") == null, "channels");
        long now = Sports.parse("2026-11-06T05:15Z"); // 13:15
        String on = TvGuide.speakNow("CCTV-5 体育", fixture("tvguide-cctv5.json"), "cctv5", now);
        check(on.startsWith("CCTV-5 体育现在在播《实况录像-2026年世界职业拳王争霸赛 12》（12 点 55 分开始，13 点 50 分结束）；接下来 13 点 50 分《"),
                "now on CCTV-5 (a full-width space made plain): " + on);
        String tonight = TvGuide.speakPart("CCTV-1 综合", fixture("tvguide-cctv1.json"), "cctv1", 18, 24, "今晚");
        check(tonight.startsWith("今晚CCTV-1 综合：18 点 20 分《自然中国-探秘长白山-山林共生》；18 点 50 分《秘境之眼》；19 点《新闻联播》；19 点 39 分《焦点访谈》")
                && !tonight.contains("前情提要"), "tonight on CCTV-1 (no recaps, no episode codes): " + tonight);
        String found = TvGuide.find("CCTV-1 综合", fixture("tvguide-cctv1.json"), "cctv1", "新闻联播", "今天", now);
        check("《新闻联播》今天在CCTV-1 综合播出：19 点到 19 点 39 分".equals(found), "a programme by name, the morning rerun left out: " + found);
        String late = TvGuide.find("CCTV-1 综合", fixture("tvguide-cctv1.json"), "cctv1", "新闻联播", "今天", Sports.parse("2026-11-06T15:00Z"));
        check(late.startsWith("《新闻联播》今天在CCTV-1 综合已经播完了：") && late.endsWith("19 点到 19 点 39 分"), "all over: " + late);
        check(TvGuide.find("CCTV-1 综合", fixture("tvguide-cctv1.json"), "cctv1", "跑男", "今天", now) == null, "not on that channel: null");
        check("今日说法".equals(TvGuide.clean("今日说法-2026-202")) && "伟大的长征第48集".equals(TvGuide.clean("伟大的长征第48集")), "titles");
        check(TvGuide.number("十七") == 17 && TvGuide.number("十") == 10 && TvGuide.number("五") == 5 && TvGuide.number("13") == 13, "numbers");
        // 这台电视上没有能看直播的应用：放不了就是放不了，节目单读得到就接着说现在在播什么
        check("这台电视上没有能看电视直播的应用，放不了CCTV-5 体育。".equals(TvGuide.cannotWatch("CCTV-5 体育", null)),
                "cannotWatch, no programme list: " + TvGuide.cannotWatch("CCTV-5 体育", null));
        String onNow = "CCTV-5 体育现在在播《体育新闻》（12 点开始，12 点 30 分结束）";
        // the channel is named once: what is on follows as 「它现在在播…」
        check(onNow.startsWith("CCTV-5 体育现在在播") && TvGuide.cannotWatch("CCTV-5 体育", onNow)
                        .equals("这台电视上没有能看电视直播的应用，放不了CCTV-5 体育。它" + onNow.substring("CCTV-5 体育".length())),
                "cannotWatch with what is on now: " + TvGuide.cannotWatch("CCTV-5 体育", onNow));
        check(TvGuide.cannotWatch("CCTV-5 体育", "节目单上现在是空的").endsWith("放不了CCTV-5 体育。节目单上现在是空的"),
                "a sentence that does not start with the channel is added as it is");
        // one programme after the one that is on, for a channel that cannot be watched here; three as before otherwise
        String one = TvGuide.speakNow("CCTV-5 体育", fixture("tvguide-cctv5.json"), "cctv5", now, 1);
        check(on.startsWith(one) && one.contains("；接下来 ") && !one.substring(one.indexOf("；接下来 ")).contains("，"),
                "speakNow with one upcoming programme: " + one);
        check(TvGuide.speakNow("CCTV-5 体育", fixture("tvguide-cctv5.json"), "cctv5", now, 0).equals(on.substring(0, on.indexOf("；接下来 "))),
                "…and with none");
        check(TvGuide.cannotWatch("湖南卫视", "").equals(TvGuide.cannotWatch("湖南卫视", null)), "an empty list is no list");
    }

    static void news() throws Exception {
        long now = Sports.parse("2026-11-06T05:15Z");
        java.util.List<News.Item> world = News.fresh(News.items("cn:world", fixture("news-cn-world.xml")), now);
        check(world.size() >= 20 && world.get(0).title.equals("（第九届进博会）进博会聚焦全球经济再平衡：开放合作是关键"), "world news, newest first: " + world.get(0).title);
        check(world.get(0).summary.startsWith("正在上海举行的第九届中国国际进口博览会期间"), "the dateline is not read: " + world.get(0).summary);
        String said = News.speak("国际新闻", "cn:world", world, 3, now);
        check(said.startsWith("中新网国际新闻（最新一条 11 点 49 分）：第一，（第九届进博会）进博会聚焦全球经济再平衡：开放合作是关键；第二，"), "spoken: " + said);
        java.util.List<News.Item> tech = News.fresh(News.items("sina:2515", fixture("news-sina-tech.json")), now);
        check(!tech.isEmpty() && tech.get(0).title.startsWith("直击进博会"), "Sina tech: " + (tech.isEmpty() ? "" : tech.get(0).title));
        check(News.fresh(News.items("cn:world", fixture("news-cn-world.xml")), now + 5 * 86_400_000L).isEmpty(), "older than two days: nothing");
        check("cn:world".equals(News.category("国际")[0]) && "sina:2515".equals(News.category("科技")[0]) && "cn:scroll-news".equals(News.category("")[0])
                && News.known("体育") && !News.known("娱乐") && News.known(""), "categories");
        // AI news from 量子位, gadgets from IT之家 (2026-11-07)
        long later = Sports.parse("2026-11-06T18:00Z");
        java.util.List<News.Item> ai = News.fresh(News.items("rss:qbitai", fixture("news-qbitai.xml")), later);
        check(ai.size() == 10 && ai.get(0).title.startsWith("DeepSeek带火稀疏计算"), "量子位's AI news: " + (ai.isEmpty() ? "" : ai.get(0).title));
        String aiSaid = News.speak("AI 新闻", "rss:qbitai", ai, 2, later);
        // 2026-11-10 sweep: the newest one is from the day before (asked at two in the morning), and 「最新一条 15 点」
        // sounded like today's
        check(aiSaid.startsWith("量子位 AI 新闻（最新一条 昨天 15 点）：第一，DeepSeek带火稀疏计算"), "spoken, with the day: " + aiSaid);
        java.util.List<News.Item> it = News.fresh(News.items("rss:ithome", fixture("news-ithome.xml")), later);
        check(it.size() == 12 && it.get(0).title.startsWith("今日立冬") && it.get(0).summary.length() <= 121, "IT之家, summaries cut: " + (it.isEmpty() ? "" : it.get(0).title));
        check("rss:qbitai".equals(News.category("最近有什么AI新闻")[0]) && "rss:qbitai".equals(News.category("大模型")[0]) && "rss:ithome".equals(News.category("数码")[0])
                && "sina:2515".equals(News.category("科技")[0]) && News.publisher("rss:ithome").equals("IT之家"), "AI, gadgets, tech");
        org.json.JSONObject read = News.readOut("国际新闻", world, 3);
        check(read.getJSONArray("items").length() == 3 && read.getJSONArray("items").getJSONObject(0).has("summary"), "the read-out list has summaries");
    }

    /** 「最近AI圈有什么新闻」 at half past eight: 「最新一条 16 点 57 分」 was yesterday's, and said like that it sounded
     * like today's (2026-11-10 sweep). */
    static void newsDay() throws Exception {
        long item = Sports.parse("2026-11-09T08:57Z"); // 16:57 on 9 November, Beijing
        check(News.dayWord(item, Sports.parse("2026-11-09T00:30Z")).isEmpty(), "today: no day word");
        check(News.dayWord(item, Sports.parse("2026-11-10T00:30Z")).equals("昨天 "), "yesterday: 昨天");
        check(News.dayWord(item, Sports.parse("2026-11-11T00:30Z")).equals("前天 "), "the day before: 前天");
        check(News.dayWord(item, Sports.parse("2026-11-13T00:30Z")).equals("11 月 9 日 "), "older: the date");
        check(News.dayWord(item, Sports.parse("2026-11-08T20:00Z")).equals(""), "this morning: the day starts at midnight, Beijing");

        java.util.List<News.Item> one = new java.util.ArrayList<>();
        one.add(new News.Item("标题", "", "", item));
        check(News.speak("AI 新闻", "rss:qbitai", one, 1, Sports.parse("2026-11-10T00:30Z")).equals("量子位 AI 新闻（最新一条 昨天 16 点 57 分）：第一，标题"),
                "yesterday's, said so: " + News.speak("AI 新闻", "rss:qbitai", one, 1, Sports.parse("2026-11-10T00:30Z")));
        check(News.speak("AI 新闻", "rss:qbitai", one, 1, Sports.parse("2026-11-11T00:30Z")).contains("（最新一条 前天 16 点 57 分）"), "the day before");
        check(News.speak("AI 新闻", "rss:qbitai", one, 1, Sports.parse("2026-11-13T00:30Z")).contains("（最新一条 11 月 9 日 16 点 57 分）"), "an older day: the date");
        check(News.speak("最新新闻", "cn:scroll-news", one, 1, Sports.parse("2026-11-09T10:00Z")).equals("中新网最新新闻（最新一条 16 点 57 分）：第一，标题"),
                "today's stays as it was");
        // the real feed: the world's newest headline is from the same morning
        long noon = Sports.parse("2026-11-06T05:15Z");
        java.util.List<News.Item> world = News.fresh(News.items("cn:world", fixture("news-cn-world.xml")), noon);
        check(News.speak("国际新闻", "cn:world", world, 3, noon).startsWith("中新网国际新闻（最新一条 11 点 49 分）：第一，"), "the same day: unchanged: "
                + News.speak("国际新闻", "cn:world", world, 3, noon));
        java.util.List<News.Item> nextDay = News.fresh(News.items("cn:world", fixture("news-cn-world.xml")), noon + 86_400_000L);
        check(News.speak("国际新闻", "cn:world", nextDay, 3, noon + 86_400_000L).startsWith("中新网国际新闻（最新一条 昨天 11 点 49 分）：第一，"), "read the next day: 昨天");
    }

    static void charts() throws Exception {
        java.util.List<HotList.Song> hot = HotList.chartSongs(fixture("chart-netease-hot.json"));
        check(hot.size() == 10 && "我不难过".equals(hot.get(1).title) && "孙燕姿".equals(hot.get(1).artist), "the hot chart's songs");
        String said = HotList.speakChart("网易云热歌榜", hot, 3);
        check(said.equals("网易云热歌榜前 3 首：第一，《Hong Kong (feat. 王菲季候风)》，The Weeknd；第二，《我不难过》，孙燕姿；第三，《明知故犯》，Max李玄"), "spoken: " + said);
        check("3779629".equals(HotList.chart("网易云新歌榜")[0]) && "3778678".equals(HotList.chart("网易云")[0]) && HotList.chart("百度") == null
                && HotList.chart("B站") == null, "charts");
        check(HotList.readOutSongs("网易云热歌榜", hot, 5).getJSONArray("items").getJSONObject(1).getString("artist").equals("孙燕姿"), "the read-out songs");
        check(HotList.chartSongs(fixture("chart-netease-new.json")).size() == 10, "the new songs chart");
    }

    static void quake() throws Exception {
        java.util.List<Quake.Event> ev = Quake.events(fixture("quake-cenc.json"));
        check(ev.size() == 50 && ev.get(0).place.equals("西藏那曲市巴青县") && ev.get(0).magnitude == 3.4 && ev.get(0).reviewed, "the list, newest first");
        long now = Sports.parse("2026-11-06T02:30Z"); // 10:30, twenty minutes after the 巴青 one
        String far = Quake.nearby(ev, now, 31.23, 121.47, 2);
        check(far.startsWith("中国地震台网最近 2 小时在这附近没有测到地震。全国最近一次是今天 10 点 11 分西藏那曲市巴青县 3.4 级，震源深度 10 公里，离这里约 ")
                && far.endsWith(" 公里"), "Shanghai, nothing near: " + far);
        String near = Quake.nearby(ev, now, 32.0, 93.5, 2);
        check(near.startsWith("是的，中国地震台网测定：今天 10 点 11 分西藏那曲市巴青县 3.4 级"), "next to it: " + near);
        String list = Quake.latest(ev, now, 31.23, 121.47, null, 3);
        check(list.startsWith("中国地震台网最近 3 次：今天 10 点 11 分西藏那曲市巴青县 3.4 级") && list.contains("；今天 1 点 21 分斐济群岛地区 6.1 级"), "the latest: " + list);
        String yesterday = Quake.nearby(ev, Sports.parse("2026-11-07T02:30Z"), 31.23, 121.47, 2);
        check(yesterday.contains("全国最近一次是 11 月 6 日 10 点 11 分"), "another day's date after 是, with its space: " + yesterday);
        check(Quake.latest(ev, now, Double.NaN, Double.NaN, "新疆", 1).startsWith("中国地震台网记录的新疆最近 1 次：今天 0 点 56 分新疆和田地区和康县 4.8 级，震源深度 60 公里")
                && !Quake.latest(ev, now, Double.NaN, Double.NaN, "新疆", 1).contains("离这里"), "by place, without the TV's place");
        check(Math.abs(Quake.km(31.23, 121.47, 39.90, 116.40) - 1067) < 15, "Shanghai to Beijing is about 1070 km");
    }

    static void prices() throws Exception {
        check("上海".equals(Prices.province("上海徐汇")) && "浙江".equals(Prices.province("杭州")) && "广东".equals(Prices.province("广东省"))
                && "内蒙古".equals(Prices.province("内蒙古自治区")) && Prices.province("东京") == null, "provinces");
        String fuel = Prices.speakFuel(fixture("fuel-shanghai.json"));
        check(fuel.equals("上海油价（10 月 30 日调价后）：92 号汽油 8.85 元一升，95 号 9.41 元，0 号柴油 8.57 元；这次每升涨了 0.28 元（92 号）"), "fuel: " + fuel);
        String[] fund = Prices.pickFund(fixture("fund-search.json"));
        check(fund != null && "005827".equals(fund[0]) && "易方达蓝筹精选混合".equals(fund[1]), "the fund found");
        String nav = Prices.speakFund(fund[1], fixture("fund-nav.json"));
        check(nav.equals("易方达蓝筹精选混合 11 月 6 日净值 1.4023，当天跌 1.00%"), "fund: " + nav);
        check(Prices.fuelUrl("上海").contains("CITYNAME%3D%22%E4%B8%8A%E6%B5%B7%22"), "the province in the filter");
    }

    static void history() throws Exception {
        java.util.List<HistoryToday.Entry> day = HistoryToday.entries(fixture("history-11.json"), 11, 7);
        check(day.size() == 12 && day.get(0).year.equals("1867") && !day.get(0).title.contains("<"), "the day's entries, links stripped");
        java.util.List<HistoryToday.Entry> picked = HistoryToday.pick(day, 4);
        String said = HistoryToday.speak(picked, 11, 7);
        check(said.startsWith("历史上的今天（11 月 7 日）：1867 年，被称为居里夫人的玛丽亚·斯克沃多夫斯卡·居里出生；1879 年，") && picked.size() == 4,
                "notable first, in year order: " + said);
        check(HistoryToday.entries(fixture("history-11.json"), 11, 20).isEmpty(), "a day not in the file: nothing");
        check(HistoryToday.url(5).endsWith("/05.json") && HistoryToday.url(11).endsWith("/11.json"), "the month's file");
        check(HistoryToday.readOut(picked).getJSONArray("items").getJSONObject(0).has("summary"), "the read-out list");
    }

    static void cache() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        long[] now = {1000};
        Feeds.Fetch f = Feeds.cached((url, cs, t) -> "body" + calls.incrementAndGet(), 30_000, () -> now[0]);
        String a = f.get("u", "UTF-8", 1000), b = f.get("u", "UTF-8", 1000);
        now[0] += 31_000;
        String c = f.get("u", "UTF-8", 1000);
        check("body1".equals(a) && "body1".equals(b) && "body2".equals(c) && calls.get() == 2, "kept for its time, then fetched again");
        check(new JSONObject("{}").length() == 0, "json present");
    }
}
