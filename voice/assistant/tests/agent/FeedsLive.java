package xyz.erapi0neer.xingyi.agent;

import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * Every public data feed the tools read, fetched the way the TV fetches it (Feeds.https(): its headers, its charset) and
 * read by the real parsers, on the PC. Not part of run_core (it needs the network): sh tests/agent/feeds_live.sh.
 * 2026-11-07: the fund values answered on the PC with curl's own headers and failed on the TV without a referrer;
 * the fixtures had been saved with curl, so the offline checks passed.
 */
public final class FeedsLive {
    private FeedsLive() { }

    private interface Probe { String run(Feeds.Fetch f, long now) throws Exception; }

    private static int failed = 0;

    private static void probe(String name, Feeds.Fetch f, long now, Probe p) {
        long t0 = System.currentTimeMillis();
        try {
            String said = p.run(f, now);
            if (said == null || said.trim().isEmpty()) throw new IllegalStateException("nothing to say");
            System.out.printf("OK   %-14s %5d ms  %s%n", name, System.currentTimeMillis() - t0, said.length() > 90 ? said.substring(0, 90) + "…" : said);
        } catch (Exception e) {
            failed++;
            System.out.printf("FAIL %-14s %5d ms  %s%n", name, System.currentTimeMillis() - t0, e);
        }
    }

    public static void main(String[] args) {
        Feeds.Fetch f = Feeds.https();
        long now = System.currentTimeMillis();
        Calendar today = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        probe("quotes", f, now, (x, n) -> Quotes.speak(Quotes.parse(x.get(Quotes.QT + "sh000001,sh600519,usAAPL,hk00700", "GBK", 8000)), today));
        probe("quote-search", f, now, (x, n) -> Quotes.pick(x.get(Quotes.SEARCH + java.net.URLEncoder.encode("英伟达", "UTF-8"), "UTF-8", 8000), "英伟达"));
        probe("rates", f, now, (x, n) -> Rates.answer("USD", "CNY", 100, Rates.parity(x.get(Rates.PARITY, "UTF-8", 8000)),
                Rates.market(x.get(Rates.MARKET, "UTF-8", 8000))));
        probe("rates-market", f, now, (x, n) -> Rates.answer("USD", "JPY", 1, null, Rates.market(x.get(Rates.MARKET, "UTF-8", 8000))));
        probe("gold", f, now, (x, n) -> Metals.speakGold(x.get(Metals.GOLD, "GBK", 8000), today));
        probe("baidu-hot", f, now, (x, n) -> HotList.speakBaidu(HotList.baidu(x.get(HotList.BAIDU, "UTF-8", 8000)), 3));
        probe("bili-hot", f, now, (x, n) -> HotList.speakBili(HotList.bili(x.get(HotList.BILI, "UTF-8", 8000)), 3));
        probe("netease-chart", f, now, (x, n) -> HotList.speakChart("网易云热歌榜", HotList.chartSongs(x.get(HotList.chartUrl("3778678", 10), "UTF-8", 8000)), 3));
        probe("lottery-ssq", f, now, (x, n) -> Lottery.speak("双色球", "cwl:ssq", x.get(Lottery.url("cwl:ssq"), "UTF-8", 8000)));
        probe("lottery-dlt", f, now, (x, n) -> Lottery.speak("大乐透", "tc:85", x.get(Lottery.url("tc:85"), "UTF-8", 8000)));
        probe("nba-board", f, now, (x, n) -> Sports.speakBoard(x.get(Sports.boardUrl(today), "UTF-8", 10000), "今天", n));
        probe("nba-team", f, now, (x, n) -> Sports.speakTeam(x.get(Sports.scheduleUrl("13"), "UTF-8", 10000), "LAL", n));
        probe("football-club", f, now, (x, n) -> Football.speakClub(x.get(Football.resultsUrl("eng.1", "360"), "UTF-8", 10000),
                x.get(Football.fixturesUrl("eng.1", "360"), "UTF-8", 10000), "360", "曼联", n));
        probe("tv-guide", f, now, (x, n) -> TvGuide.speakNow("CCTV-1 综合", x.get(TvGuide.url("cctv1", today), "UTF-8", 8000), "cctv1", n));
        probe("news-cn", f, now, (x, n) -> News.speak("最新新闻", "cn:scroll-news", News.fresh(News.items("cn:scroll-news",
                x.get(News.url("cn:scroll-news"), "UTF-8", 8000)), n), 3));
        probe("news-sina-tech", f, now, (x, n) -> News.speak("科技新闻", "sina:2515", News.fresh(News.items("sina:2515",
                x.get(News.url("sina:2515"), "UTF-8", 8000)), n), 3));
        probe("earthquake", f, now, (x, n) -> Quake.latest(Quake.events(x.get(Quake.CENC, "UTF-8", 8000)), n, 31.23, 121.47, null, 2));
        probe("fuel", f, now, (x, n) -> Prices.speakFuel(x.get(Prices.fuelUrl("上海"), "UTF-8", 8000)));
        probe("fund", f, now, (x, n) -> {
            String[] fund = Prices.pickFund(x.get(Prices.fundSearchUrl("易方达蓝筹"), "UTF-8", 8000));
            return Prices.speakFund(fund[1], x.get(Prices.fundNavUrl(fund[0]), "UTF-8", 8000));
        });
        probe("history", f, now, (x, n) -> HistoryToday.speak(HistoryToday.pick(HistoryToday.entries(x.get(HistoryToday.url(today.get(Calendar.MONTH) + 1),
                "UTF-8", 10000), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH)), 3), today.get(Calendar.MONTH) + 1,
                today.get(Calendar.DAY_OF_MONTH)));
        // B站's search wants its visitor id (Feeds): five searches in a row, as the PC was turned away after one without it
        probe("bili-up-latest", f, now, (x, n) -> BiliVideos.speak(BiliVideos.find(x, "影视飓风", "", "latest", 5), 5, n));
        probe("bili-up-top", f, now, (x, n) -> BiliVideos.speak(BiliVideos.find(x, "星辰亻旅行者", "", "most_played", 3), 3, n));
        probe("bili-up-kind", f, now, (x, n) -> BiliVideos.speak(BiliVideos.find(x, "星辰亻旅行者", "MAD", "latest", 3), 3, n));
        probe("bili-topic", f, now, (x, n) -> BiliVideos.speak(BiliVideos.find(x, "", "黑神话", "most_played", 3), 3, n));
        probe("bangumi-today", f, now, (x, n) -> {
            List<Bangumi.Episode> all = new java.util.ArrayList<>(Bangumi.episodes(x.get(Bangumi.ANIME, "UTF-8", 8000), "番剧"));
            all.addAll(Bangumi.episodes(x.get(Bangumi.GUOCHUANG, "UTF-8", 8000), "国创"));
            all.sort((p, q) -> Long.compare(p.at, q.at));
            return Bangumi.speakDay(Bangumi.on(Bangumi.withoutDubs(all), n, 0), 0, n) + " / " + Bangumi.speakShow(Bangumi.find(all, "凡人修仙传"), "凡人修仙传", n);
        });
        probe("almanac", f, now, (x, n) -> {
            int[] d = Huangli.dayOf("", n);
            return Huangli.speak(Huangli.on(Huangli.days(x.get(Huangli.url(d[0], d[1]), "GBK", 8000)), d[0], d[1], d[2]), "今天", "搬家");
        });
        probe("alarms", f, now, (x, n) -> Alerts.speakAlarms(Alerts.alarms(x.get(Alerts.alarmUrl("上海"), "UTF-8", 8000), "上海"), "上海", "徐汇", n)
                + " / " + Alerts.speakAlarms(Alerts.alarms(x.get(Alerts.alarmUrl("云南"), "UTF-8", 8000), "云南"), "云南", "", n));
        probe("typhoons", f, now, (x, n) -> {
            List<Alerts.Storm> storms = Alerts.storms(x.get(Alerts.TYPHOONS, "UTF-8", 8000));
            Alerts.Storm s = storms.get(0);
            for (Alerts.Storm t : storms) if (t.active) { s = t; break; }
            return (s.active ? "active: " : "none active; the latest: ") + Alerts.speakStorm(s, Alerts.last(x.get(Alerts.trackUrl(s.id), "UTF-8", 8000)), 31.23, 121.47, n);
        });
        probe("news-ai", f, now, (x, n) -> News.speak("AI 新闻", "rss:qbitai", News.fresh(News.items("rss:qbitai", x.get(News.url("rss:qbitai"), "UTF-8", 8000)), n), 3));
        probe("news-ithome", f, now, (x, n) -> News.speak("数码新闻", "rss:ithome", News.fresh(News.items("rss:ithome", x.get(News.url("rss:ithome"), "UTF-8", 8000)), n), 3));
        probe("music-new", f, now, (x, n) -> {
            Object[] who = MusicNew.artist(x.get(MusicNew.artistUrl("林俊杰"), "UTF-8", 8000), "林俊杰");
            return MusicNew.speak((String) who[1], MusicNew.releases(x.get(MusicNew.albumsUrl((Long) who[0]), "UTF-8", 8000)), 3, n);
        });
        probe("douban-lists", f, now, (x, n) -> Movies.speakList("movie_showing", "正在上映的电影", Movies.items(x.get(Movies.listUrl("movie_showing", 40), "UTF-8", 8000)), 4, n)
                + " / " + Movies.speakList("tv_hot", "近期热门剧集", Movies.items(x.get(Movies.listUrl("tv_hot", 3), "UTF-8", 8000)), 3, n));
        probe("douban", f, now, (x, n) -> Movies.speak(Movies.titles(x.get(Movies.url("流浪地球2"), "UTF-8", 8000)), "流浪地球2", 0));
        List<String> none = Collections.emptyList();
        System.out.println(failed == 0 ? "FEEDS LIVE OK" : "FEEDS LIVE: " + failed + " failed" + none);
        System.exit(failed == 0 ? 0 : 1);
    }
}
