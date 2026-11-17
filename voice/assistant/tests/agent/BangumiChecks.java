package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** bili_bangumi (2026-11-07): B站's anime and 国创 timelines, a week back and six days on, as saved that night. */
final class BangumiChecks {
    private BangumiChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static String fixture(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/" + name)), StandardCharsets.UTF_8);
    }

    /** 2026-11-07 02:00 in Beijing, a Saturday. */
    private static final long NOW = 1793988000000L;

    static void run() {
        try {
            checks();
            week();
        } catch (Exception e) {
            check(false, "bangumi checks threw " + e);
        }
    }

    /** 「这周有什么新番」 (2026-11-10 sweep: three model rounds calling bili_bangumi once a day, 7.1 s — the tool knows
     * one day at a time). One sentence now, day by day. */
    static void week() throws Exception {
        check(Bangumi.weekAsked("这周") && Bangumi.weekAsked("这几天") && Bangumi.weekAsked("最近有什么新番") && Bangumi.weekAsked("本周")
                && !Bangumi.weekAsked("今天") && !Bangumi.weekAsked("周六") && !Bangumi.weekAsked("") && !Bangumi.weekAsked(null), "which days mean the week");
        check(Bangumi.weekDay(0, NOW).equals("今天") && Bangumi.weekDay(1, NOW).equals("明天") && Bangumi.weekDay(2, NOW).equals("周一")
                && Bangumi.weekDay(6, NOW).equals("周五"), "the days read 今天、明天, then the weekday (a Saturday)");

        List<Bangumi.Episode> all = Bangumi.withoutDubs(all());
        String said = Bangumi.speakWeek(all, NOW, "");
        check(said.equals("这周B站更新的番剧和国创：今天 6 部：万古至尊：李云霄传、功夫熊猫：神龙骑士 第二季、凡人修仙传；明天 5 部：财神窦占龙、功夫熊猫：神龙骑士 第二季、肥志百科；"
                        + "周一 1 部：海绵宝宝 第十二季；周二 2 部：掌门低调点、海绵宝宝 第十二季；周三 3 部：李熊猫、苏东坡与杭州的故事、海绵宝宝 第十二季；"
                        + "周四 2 部：雪王来了！、海绵宝宝 第十二季；周五 7 部：苏东坡与杭州的故事、缔灵爱水心缠、雪王来了！"),
                "the week, day by day, three names each: " + said);
        check(said.length() <= Bangumi.WEEK_MAX, "and it stays under the length limit: " + said.length());
        boolean ordered = said.indexOf("今天") < said.indexOf("明天") && said.indexOf("明天") < said.indexOf("周一") && said.indexOf("周一") < said.indexOf("周二")
                && said.indexOf("周二") < said.indexOf("周三") && said.indexOf("周三") < said.indexOf("周四") && said.indexOf("周四") < said.indexOf("周五");
        check(ordered, "the days in order");
        check(said.contains("今天 6 部：") && said.contains("周五 7 部：") && said.indexOf("、", said.indexOf("今天 6 部：")) > 0
                && said.substring(said.indexOf("今天 6 部："), said.indexOf("；明天")).split("、").length == 3, "each day its count, at most three names");

        // 国创 only: Monday has none of them and is left out
        String gc = Bangumi.speakWeek(Bangumi.ofKind(all, "国创"), NOW, "国创");
        check(gc.equals("这周B站更新的国创：今天 3 部：万古至尊：李云霄传、凡人修仙传、我是不白吃；明天 3 部：财神窦占龙、肥志百科、牧神记；周二 1 部：掌门低调点；"
                        + "周三 2 部：李熊猫、苏东坡与杭州的故事；周四 1 部：雪王来了！；周五 5 部：苏东坡与杭州的故事、缔灵爱水心缠、雪王来了！"),
                "国创 only, the day with none of them left out: " + gc);
        check(!gc.contains("周一"), "a day with nothing on it is not read");
        check(Bangumi.speakWeek(new ArrayList<Bangumi.Episode>(), NOW, "").equals("这周B站的更新表上没有番剧和国创"), "nothing all week: "
                + Bangumi.speakWeek(new ArrayList<Bangumi.Episode>(), NOW, ""));
        check(Bangumi.speakWeek(Bangumi.ofKind(all, "番剧"), NOW, "番剧").contains("好奇心俱乐部（Question Club）"), "a title is not cut when it fits: "
                + Bangumi.speakWeek(Bangumi.ofKind(all, "番剧"), NOW, "番剧"));

        // every day full of long titles: whole days are cut off the end, and it says how many were not read
        List<Bangumi.Episode> many = new ArrayList<>();
        for (int d = 0; d < 7; d++) for (int i = 0; i < 5; i++)
            many.add(new Bangumi.Episode("很长很长的番剧名字第" + d + "季第" + i + "部", "第1话", "番剧", NOW + d * 86_400_000L + i * 3_600_000L, 1, d * 10 + i, false, false, ""));
        String cut = Bangumi.speakWeek(many, NOW, "");
        check(cut.endsWith("；还有 2 天没念") && cut.length() <= Bangumi.WEEK_MAX, "too long: whole days cut, and how many were left: " + cut.length() + " " + cut);
        check(cut.contains("周三 5 部：") && !cut.contains("周四"), "the cut is at a day's end, never inside one");
        check(cut.indexOf("今天") == cut.indexOf("今天") && cut.startsWith("这周B站更新的番剧和国创：今天 5 部："), "the first day is always read");
    }

    private static List<Bangumi.Episode> all() throws Exception {
        List<Bangumi.Episode> all = new ArrayList<>(Bangumi.episodes(fixture("bangumi-anime.json"), "番剧"));
        all.addAll(Bangumi.episodes(fixture("bangumi-guochuang.json"), "国创"));
        Collections.sort(all, (x, y) -> Long.compare(x.at, y.at));
        return all;
    }

    static void checks() throws Exception {
        List<Bangumi.Episode> all = all();
        check(all.size() == 80, "both timelines: " + all.size());
        List<Bangumi.Episode> today = Bangumi.on(Bangumi.withoutDubs(all), NOW, 0);
        String said = Bangumi.speakDay(today, 0, NOW);
        check(said.equals("今天B站更新的番剧和国创有 6 部：上午 9 点《万古至尊：李云霄传》第 34 话；上午 10 点《功夫熊猫：神龙骑士 第二季》第 18 话；"
                + "上午 11 点《凡人修仙传》第 199 话；下午 5 点《好奇心俱乐部（Question Club）》第 14 话；晚上 7 点《我是不白吃》第 1102 话；"
                + "晚上 7 点 30 分《名侦探柯南》第 1279 话"), "today, dubbed copies left out: " + said);
        String yesterday = Bangumi.speakDay(Bangumi.on(Bangumi.withoutDubs(all), NOW, -1), -1, NOW);
        check(yesterday.startsWith("昨天B站更新的番剧和国创有 ") && !yesterday.contains("已更新"), "yesterday: " + yesterday);

        check(Bangumi.speakShow(Bangumi.find(all, "凡人修仙传"), "凡人修仙传", NOW)
                .equals("《凡人修仙传》每周六上午 11 点更新：最新一集是第 198 话（10 月 31 日）；下一集第 199 话今天上午 11 点更新"), "a weekly show: "
                + Bangumi.speakShow(Bangumi.find(all, "凡人修仙传"), "凡人修仙传", NOW));
        String panda = Bangumi.speakShow(Bangumi.find(all, "功夫熊猫"), "功夫熊猫", NOW);
        check(panda.equals("《功夫熊猫：神龙骑士 第二季》每天上午 10 点更新：最新一集是第 17 话（昨天）；下一集第 18 话今天上午 10 点更新"), "a daily show by part of its name: " + panda);
        String club = Bangumi.speakShow(Bangumi.find(all, "好奇心俱乐部"), "好奇心俱乐部", NOW);
        check(club.startsWith("《好奇心俱乐部（Question Club）》每周五、周六、周日下午 5 点更新："), "three days a week: " + club);
        String sponge = Bangumi.speakShow(Bangumi.find(all, "海绵宝宝"), "海绵宝宝", NOW);
        check(sponge.equals("《海绵宝宝 第十二季》每周一到周五中午 12 点更新，还没开播，第 1 话到第 10 话在后天中午 12 点上线"), "not started yet: " + sponge);
        String chi = Bangumi.speakShow(Bangumi.find(all, "我是不白吃"), "我是不白吃", NOW);
        check(chi.startsWith("《我是不白吃》每周二、周四、周六更新：最新一集是第 1101 话（前天）"), "times that differ are left out: " + chi);
        String conan = Bangumi.speakShow(Bangumi.find(all, "柯南"), "柯南", NOW);
        check(conan.equals("《名侦探柯南》每周六晚上 7 点 30 分更新：最新一集是第 1278 话（10 月 31 日）；下一集第 1279 话今天晚上 7 点 30 分更新"),
                "柯南 is 名侦探柯南, not its dubbed copy: " + conan);
        check(Bangumi.find(all, "名侦探柯南（中配）").get(0).title.equals("名侦探柯南（中配）"), "the dubbed copy when asked for");
        check(Bangumi.speakShow(Bangumi.find(all, "海贼王"), "海贼王", NOW).equals("B站这两周的更新表上没有「海贼王」：可能已经完结，或者不在B站"), "not on the list");

        Bangumi.Episode last = Bangumi.latest(Bangumi.find(all, "凡人修仙传"));
        check(last.link().equals("https://www.bilibili.com/bangumi/play/ep3854822") && last.seasonLink().equals("bilibili://bangumi/season/28747"),
                "the newest episode's link: " + last.link());
        check(Bangumi.offset("明天", NOW) == 1 && Bangumi.offset("周日", NOW) == 1 && Bangumi.offset("昨天", NOW) == -1 && Bangumi.offset("周一", NOW) == 2
                && Bangumi.offset("这周一", NOW) == -5 && Bangumi.offset("下周六", NOW) == 7 && Bangumi.offset("11-08", NOW) == 1 && Bangumi.offset("", NOW) == 0,
                "days: 周一 on a Saturday is the coming one; 这周一 the one past");
        check(Bangumi.readOut(today, 0, NOW).getJSONArray("items").getJSONObject(2).getString("link").equals("https://www.bilibili.com/bangumi/play/ep3854823")
                && Bangumi.readOut(today, 0, NOW).getString("what").equals("今天B站更新的番剧"), "the list read out, for 「放第三个」");
        String gc = Bangumi.speakDay(Bangumi.on(Bangumi.withoutDubs(Bangumi.ofKind(all, Bangumi.kindAsked("明天有什么国创"))), NOW, 1), 1, NOW, "国创");
        check(gc.equals("明天B站更新的国创有 3 部：上午 9 点《财神窦占龙》第 15 话；上午 10 点《肥志百科》第 176 话；上午 11 点《牧神记》第 108 话"), "国创 only: " + gc);
        check(Bangumi.kindAsked("今天有什么番更新").isEmpty() && "番剧".equals(Bangumi.kindAsked("今晚有什么日漫")), "which kind a request asks for");
        // the review, 2026-11-07
        long dec30 = 1798567200000L; // 2026-12-30 02:00 in Beijing
        check(Bangumi.offset("2026-11-08", NOW) == 1 && Bangumi.offset("1月2日", dec30) == 3 && Bangumi.offset("01-02", dec30) == 3
                && Bangumi.offset("12月28日", 1798567200000L + 5 * 86_400_000L) == -7, "a full date, and dates across the new year: " + Bangumi.offset("2026-11-08", NOW)
                + " " + Bangumi.offset("1月2日", dec30) + " " + Bangumi.offset("12月28日", 1798567200000L + 5 * 86_400_000L));
        String far = Bangumi.speakDay(new ArrayList<Bangumi.Episode>(), 12, NOW, "");
        check(far.equals("B站的更新表只排到 11 月 13 日，11 月 19 日的还没排出来"), "beyond the timetable is not 「没有」: " + far);
        check(Bangumi.speakDay(new ArrayList<Bangumi.Episode>(), -9, NOW, "").startsWith("B站的更新表只留了最近一周的，"), "nor before it");
        check(Bangumi.index("第1话~第10话").equals("第 1 话到第 10 话") && Bangumi.index("SP").equals("SP"), "episode numbers");
    }
}
