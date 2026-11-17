package xyz.erapi0neer.xingyi.agent;

import java.util.Calendar;
import java.util.Collections;

/** Checks from the 2026-11-06 overnight pass: where the TV is, answers from the clock, 「再说一遍」, the hand-back to
 * XiaoAi when no model route answers. */
final class ContextChecks {
    private ContextChecks() { }

    static void run() {
        place();
        clock();
        replay();
        searchKnowsThePlace();
        offline();
        dailyCap();
        brief();
        aboutScreen();
        testTraces();
        dailyBrief();
    }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    static void place() {
        String located = "{\"cityid\":\"1422\",\"province\":\"上海\",\"city\":\"上海\",\"district\":\"徐汇\",\"latitude\":\"31.4\",\"fromXMLocation\":true}";
        String notSet = "{\"cityid\":\"1422\",\"province\":\"上海\",\"city\":\"上海\",\"district\":\"徐汇\",\"isUserSet\":false}";
        String userSet = "{\"province\":\"浙江\",\"city\":\"杭州\",\"district\":\"西湖\",\"isUserSet\":true}";
        check("上海徐汇".equals(Place.of(notSet, located)), "the TV's own location: " + Place.of(notSet, located));
        check("浙江杭州西湖".equals(Place.of(userSet, located)), "the user's choice wins: " + Place.of(userSet, located));
        check("上海".equals(Place.of(null, "{\"province\":\"上海\",\"city\":\"上海\"}")), "no district");
        check(Place.of(null, null) == null && Place.of("", "not json") == null, "nothing usable, nothing said");
        check(Place.of(null, "{\"province\":\"上海\",\"city\":\"\"}") == null, "no city, no place");
    }

    private static QuickCommand q(String s) { return QuickCommand.match(s, Collections.<String>emptySet()); }

    /** Exactly that tool with exactly those arguments: 「weather place=北京 day=明天 final=true」, nothing else. */
    private static boolean is(QuickCommand q, String tool, String... kv) {
        if (q == null || !tool.equals(q.tool) || q.args.length() != kv.length / 2) return false;
        for (int i = 0; i < kv.length; i += 2) {
            Object v = q.args.opt(kv[i]);
            if (v == null || !String.valueOf(v).equals(kv[i + 1])) return false;
        }
        return true;
    }

    static void clock() {
        for (String s : new String[]{"现在几点", "现在几点了", "几点了", "小爱同学，现在几点钟", "现在什么时间"})
            check(q(s) != null && "clock".equals(q(s).tool) && "time".equals(q(s).args.optString("kind")), s + " → the clock");
        for (String s : new String[]{"今天几号", "今天几月几号", "今天是几号啊"})
            check(q(s) != null && "date".equals(q(s).args.optString("kind")), s + " → the date");
        for (String s : new String[]{"今天星期几", "今天周几", "星期几了"})
            check(q(s) != null && "weekday".equals(q(s).args.optString("kind")), s + " → the weekday");
        // another city's time: the TV's own time zone data since 2026-11-06 (world_clock), an unknown place the model
        check(q("纽约现在几点") != null && "world_clock".equals(q("纽约现在几点").tool), "纽约现在几点 → world_clock");
        check(q("火星现在几点") == null, "a place the clock does not know goes to the model");
        check(q("明天几号") != null && q("明天几号").args.optInt("in") == 1, "明天几号 is the clock a day on (it went to the model before the overnight pass)");
        check(q("明天几号开会") == null, "明天几号开会 goes to the model");
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.NOVEMBER, 6, 3, 25, 0);
        check("现在是凌晨 3 点 25 分".equals(QuickCommand.clockText("time", c)), "凌晨: " + QuickCommand.clockText("time", c));
        c.set(Calendar.HOUR_OF_DAY, 14); c.set(Calendar.MINUTE, 0);
        check("现在是下午 2 点整".equals(QuickCommand.clockText("time", c)), "下午整点: " + QuickCommand.clockText("time", c));
        c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 5);
        check("现在是中午 12 点 5 分".equals(QuickCommand.clockText("time", c)), "中午: " + QuickCommand.clockText("time", c));
        c.set(Calendar.HOUR_OF_DAY, 21);
        check("现在是晚上 9 点 5 分".equals(QuickCommand.clockText("time", c)), "晚上: " + QuickCommand.clockText("time", c));
        check("今天是 11 月 6 日，星期五，农历九月廿八".equals(QuickCommand.clockText("date", c)), "date: " + QuickCommand.clockText("date", c));
        check("今天星期五".equals(QuickCommand.clockText("weekday", c)), "weekday: " + QuickCommand.clockText("weekday", c));
    }

    static void replay() {
        for (String s : new String[]{"再说一遍", "重复一下", "刚才说什么", "我没听清", "小爱同学，再说一次吧"})
            check(q(s) != null && "replay".equals(q(s).tool), s + " → the last answer again");
        check(q("再说一遍刚才那首歌的名字") == null, "a longer request goes to the model");
        for (String s : new String[]{"音量现在多少", "现在音量是多少", "小爱同学，声音多大"})
            check(q(s) != null && "volume".equals(q(s).args.optString("kind")), s + " → the volume, read off the TV");
        check(q("音量调到多少合适") == null, "a question about the right volume goes to the model");
        // the weather and what is playing, straight to their tools
        String[][] weather = {{"天气怎么样", "今天"}, {"今天天气怎么样", "今天"}, {"明天天气怎么样", "明天"}, {"小爱同学，后天的天气", "后天"},
                {"这周末天气如何", "这周末"}, {"这几天天气", "这几天"}, {"现在几度", "今天"}, {"外面冷不冷", "今天"}};
        for (String[] w : weather) {
            QuickCommand c = q(w[0]);
            check(c != null && "weather".equals(c.tool) && w[1].equals(c.args.optString("day")) && c.args.optBoolean("final"),
                    w[0] + " → weather(" + w[1] + ")");
        }
        QuickCommand air = q("今天空气质量怎么样");
        check(air != null && "weather".equals(air.tool) && air.args.optBoolean("air"), "the air → weather(air)");
        // another city's weather is a quick command on any day since 2026-11-10 (「广州明天天气怎么样」 took 4.4 s through
        // the model); a question about the weather is still the model's
        check(is(q("北京明天天气怎么样"), "weather", "place", "北京", "day", "明天", "final", "true"),
                "北京明天天气怎么样 → weather 北京 明天: " + q("北京明天天气怎么样"));
        check(q("明天天气适合出去玩吗") == null && q("明天适合晒被子吗") == null,
                "a question about the weather goes to the model");
        // the TV's own place said: still quick
        QuickCommand.here(java.util.Arrays.asList("上海徐汇", "上海", "徐汇"));
        String[][] local = {{"明天上海天气怎么样", "明天"}, {"上海明天天气怎么样", "明天"}, {"徐汇今天几度", "今天"}, {"上海市天气怎么样", "今天"},
                {"今天上海空气质量怎么样", "今天"}, {"上海徐汇后天的天气", "后天"}};
        for (String[] w : local) {
            QuickCommand c = q(w[0]);
            check(c != null && "weather".equals(c.tool) && w[1].equals(c.args.optString("day")), w[0] + " → weather here (" + w[1] + ")");
        }
        check(q("今天上海空气质量怎么样").args.optBoolean("air"), "the air with the city said");
        String[][] rain = {{"明天要带伞吗", "明天"}, {"今晚会下雨吗", "今晚"}, {"外面在下雨吗", "外面"}, {"在下雨吗", "现在"},
                {"要不要带伞", "今天"}, {"明天上海会下雨吗", "明天"}, {"小爱同学，后天下雨吗", "后天"}};
        for (String[] w : rain) {
            QuickCommand c = q(w[0]);
            check(c != null && "weather".equals(c.tool) && c.args.optBoolean("rain") && w[1].equals(c.args.optString("day")),
                    w[0] + " → the rain verdict (" + w[1] + ")" + (c == null ? "" : " got " + c.args));
        }
        check(is(q("北京明天会下雨吗"), "weather", "place", "北京", "day", "明天", "rain", "true", "final", "true"),
                "another city's rain is quick too: " + q("北京明天会下雨吗"));
        check(is(q("明天北京天气怎么样"), "weather", "place", "北京", "day", "明天", "final", "true"),
                "the day said first: " + q("明天北京天气怎么样"));
        check(is(q("北京今天几度"), "weather", "place", "北京", "day", "今天", "final", "true"),
                "another city's temperature, with no rain flag: " + q("北京今天几度"));
        QuickCommand.here(null);
        check(q("现在放的是什么歌") != null && "media_now_playing".equals(q("现在放的是什么歌").tool), "what is playing → the media session");
        try {
            check("上海徐汇明天阴。".equals(q("明天天气怎么样").reply(new org.json.JSONObject().put("ok", true).put("answer", "上海徐汇明天阴。"))),
                    "the weather's answer is said as it is");
            check("现在没有在放东西".equals(q("现在放的是什么歌").reply(new org.json.JSONObject().put("ok", true).put("session", org.json.JSONObject.NULL))),
                    "nothing playing");
        } catch (org.json.JSONException e) { check(false, "replies threw " + e); }
    }

    static void searchKnowsThePlace() {
        String i = WebSearch.instructions("2026-11-06 03:40 星期五", "上海徐汇");
        check(i.contains("这户人家在上海徐汇") && i.contains("问题没说地点时就按这里"), "the search is told where the household is");
        check(!WebSearch.instructions("2026-11-06 03:40 星期五", null).contains("这户人家在"), "unknown place, nothing said");
    }

    static void offline() {
        // the words ModelRouter / MiniMaxClient put in a failure once no route answered
        check(AgentLoop.offline("官方 MiniMax 网络请求失败，请检查连接后重试；备用网关也未能应答：备用网关响应超时"), "both routes down → offline");
        check(AgentLoop.offline("官方 MiniMax 余额不足（HTTP 402 · 1008）"), "the only route out of balance → offline");
        check(AgentLoop.offline("备用网关安全连接失败，请检查电视时间和网络"), "TLS failure → offline");
        check(!AgentLoop.offline("请求已取消"), "cancelled is not offline");
        check(!AgentLoop.offline("官方 MiniMax 返回格式无效，本次没有执行新的操作"), "a bad answer is not offline");
        check(!AgentLoop.offline(null) && !AgentLoop.offline(""), "no error, not offline");
        Ledger t = new Ledger("x", "明天天气怎么样", "xiaoai", 1L);
        t.state = Ledger.State.FAILED;
        t.error = "官方 MiniMax 余额不足（HTTP 402 · 1008）；备用网关也未能应答：备用网关响应超时";
        check(AgentLoop.handsBack(t), "a spoken request that found no model goes back to XiaoAi");
        Ledger typed = new Ledger("y", "明天天气怎么样", "ui", 1L);
        typed.state = Ledger.State.FAILED;
        typed.error = t.error;
        check(!AgentLoop.handsBack(typed), "a typed one has no XiaoAi to answer it");
        Ledger acted = new Ledger("z", "打开B站", "xiaoai", 1L);
        acted.state = Ledger.State.INTERRUPTED;
        acted.error = t.error;
        try { acted.actions.add(new org.json.JSONObject().put("tool", "open_app")); } catch (org.json.JSONException e) { throw new RuntimeException(e); }
        check(!AgentLoop.handsBack(acted), "a task that already did something is not handed back");
        Ledger answered = new Ledger("w", "你好", "xiaoai", 1L);
        answered.state = Ledger.State.ANSWERED;
        check(!AgentLoop.handsBack(answered), "an answered task stays");
    }

    static void dailyCap() {
        java.io.File f;
        try { f = java.io.File.createTempFile("cap", ".json"); } catch (java.io.IOException e) { check(false, "temp file"); return; }
        f.delete();
        DailyCap cap = new DailyCap(f, 2, "在 X 上");
        check(cap.take("2026-11-06") == null && cap.take("2026-11-06") == null, "two searches a day pass");
        String third = cap.take("2026-11-06");
        check(third != null && third.contains("今天在 X 上已经查了 2 次"), "the third is refused: " + third);
        check(new DailyCap(f, 2, "在 X 上").used("2026-11-06") == 2, "the count survives a new process");
        check(cap.take("2026-11-07") == null && cap.used("2026-11-07") == 1, "a new day starts over");
        f.delete();
    }

    static void brief() {
        String shortOne = "天空是蓝的，因为蓝光最容易被散射。";
        check(shortOne.equals(Speech.brief(shortOne, 150)), "a short answer stays whole");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 20; i++) b.append("第").append(i + 1).append("点是一句用来凑长度的解释。");
        String cut = Speech.brief(b.toString(), 150);
        check(cut.length() <= 151 && cut.endsWith("。…") && b.toString().startsWith(cut.substring(0, cut.length() - 1)), "a long one is cut at a sentence: " + cut.length());
    }

    static void aboutScreen() {
        for (String q : new String[]{"这个视频讲的什么", "这是谁", "屏幕上写的什么", "现在放的这部电影叫什么名字", "这首歌是谁唱的"})
            check(Dispatch.aboutScreen(q), "about the screen: " + q);
        for (String q : new String[]{"明天天气怎么样", "打开这个视频", "把这个暂停", "放首歌"})
            check(!Dispatch.aboutScreen(q), "not a question about the screen: " + q);
    }

    static void testTraces() {
        try {
            long since = 1_000_000L;
            java.util.List<org.json.JSONObject> perf = new java.util.ArrayList<>();
            perf.add(new org.json.JSONObject().put("at", since + 10_000).put("utterance", "明天上海天气怎么样").put("source", "mcp"));
            perf.add(new org.json.JSONObject().put("at", since + 50_000).put("utterance", "你记得我什么").put("source", "xiaoai"));
            perf.add(new org.json.JSONObject().put("at", since - 99_000).put("utterance", "放首歌").put("source", "mcp"));
            perf.add(new org.json.JSONObject().put("at", since + 90_000).put("utterance", "详细讲讲人类历史上的四次工业革命，越详细越好（这里很长很长很长很长）").put("source", "debug"));
            org.json.JSONArray entries = new org.json.JSONArray()
                    .put(new org.json.JSONObject().put("at", since + 10_003).put("said", "明天上海天气怎么样"))
                    .put(new org.json.JSONObject().put("at", since + 50_002).put("said", "你记得我什么"))
                    .put(new org.json.JSONObject().put("at", since - 99_001).put("said", "放首歌"))
                    .put(new org.json.JSONObject().put("at", since + 89_990).put("said", "详细讲讲人类历史上的四次工业革命，越详细越好（这里很长很长很长很长）"))
                    .put(new org.json.JSONObject().put("at", since + 400_000).put("said", "明天上海天气怎么样"));
            int[] removed = {0};
            org.json.JSONArray kept = TestTraces.without(entries, perf, since, new java.util.HashSet<>(java.util.Arrays.asList("mcp", "debug", "test")), removed);
            check(removed[0] == 2 && kept.length() == 3, "two checks out: " + removed[0] + " " + kept);
            check("你记得我什么".equals(kept.getJSONObject(0).getString("said")), "the user's own (xiaoai) stays");
            check("放首歌".equals(kept.getJSONObject(1).getString("said")), "a check before since stays");
            check(kept.getJSONObject(2).getLong("at") == since + 400_000, "the same words at another time stay");
        } catch (org.json.JSONException e) {
            check(false, "test traces threw " + e);
        }
    }

    static void dailyBrief() {
        try {
            java.util.TimeZone tz = java.util.TimeZone.getTimeZone("Asia/Shanghai");
            Calendar c = Calendar.getInstance(tz);
            c.clear();
            c.set(2026, Calendar.NOVEMBER, 6, 7, 30, 0);
            long now = c.getTimeInMillis();
            org.json.JSONObject book = new org.json.JSONObject();
            Schedules.add(book, new org.json.JSONObject().put("kind", "remind").put("what", "该吃药了").put("time", "20:00"), now, tz);
            Schedules.add(book, new org.json.JSONObject().put("kind", "task").put("what", "播放新闻联播").put("time", "19:00").put("date", "2026-11-07"), now, tz);
            org.json.JSONObject notes = new org.json.JSONObject();
            Notes.add(notes, "买牛奶", now - 3_600_000L);
            Notes.add(notes, "交电费", now - 7_200_000L);
            String b = Brief.today(now, tz, "今天阴，14 到 19 度", book, notes);
            check(b.equals("早上好。今天是 11 月 6 日，星期五，农历九月廿八。今天阴，14 到 19 度。明天立冬。今天的预约：今天 20:00 提醒你：该吃药了；随手记里有 2 件没划掉。"),
                    "the day in one answer: " + b);
            String quiet = Brief.today(now, tz, null, new org.json.JSONObject(), new org.json.JSONObject());
            check(quiet.equals("早上好。今天是 11 月 6 日，星期五，农历九月廿八。明天立冬。今天没有预约。"), "no weather, nothing booked: " + quiet);
            for (String s : new String[]{"星仪自检", "自检一下", "你是不是坏了"})
                check(q(s) != null && "self_check".equals(q(s).tool), s + " → self_check");
            for (String s : new String[]{"早上好", "小爱同学，早安", "今天有什么安排"})
                check(q(s) != null && "brief".equals(q(s).args.optString("kind")), s + " → the brief");
        } catch (org.json.JSONException e) {
            check(false, "brief threw " + e);
        }
    }
}
