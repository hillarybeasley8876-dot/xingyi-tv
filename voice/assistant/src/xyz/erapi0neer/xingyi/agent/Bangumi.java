package xyz.erapi0neer.xingyi.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * 「今天有什么番更新」「凡人修仙传什么时候更新」「放凡人修仙传最新一集」 (2026-11-07): B站's timeline of anime (番剧) and
 * 国创 episodes, from a week back to six days on (a weekly show's last episode and its next), without a login. B站's
 * bangumi search carries every episode of a series (918 KB for 名侦探柯南), too heavy for this TV; the two timelines
 * are about 50 KB.
 */
public final class Bangumi {
    private Bangumi() { }

    public static final String ANIME = "https://api.bilibili.com/pgc/web/timeline?types=1&before=7&after=6";
    public static final String GUOCHUANG = "https://api.bilibili.com/pgc/web/timeline?types=4&before=7&after=6";

    public static final class Episode {
        public final String title, index, kind, delayReason;
        /** When it comes out, ms. */
        public final long at, season, id;
        public final boolean out, delayed;

        Episode(String title, String index, String kind, long at, long season, long id, boolean out, boolean delayed, String delayReason) {
            this.title = title; this.index = index; this.kind = kind; this.at = at; this.season = season; this.id = id; this.out = out;
            this.delayed = delayed; this.delayReason = delayReason;
        }

        /** The episode in the app: B站's own web address for it, which the app takes (IntentHandlerActivity). */
        public String link() { return "https://www.bilibili.com/bangumi/play/ep" + id; }

        public String seasonLink() { return "bilibili://bangumi/season/" + season; }
    }

    /** kind: 番剧 or 国创. */
    public static List<Episode> episodes(String json, String kind) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (o.optInt("code", -1) != 0) throw new JSONException("B站的更新表没有返回结果：" + o.optString("message"));
        JSONArray days = o.optJSONArray("result");
        List<Episode> out = new ArrayList<>();
        for (int i = 0; days != null && i < days.length(); i++) {
            JSONArray eps = days.getJSONObject(i).optJSONArray("episodes");
            for (int k = 0; eps != null && k < eps.length(); k++) {
                JSONObject e = eps.getJSONObject(k);
                out.add(new Episode(News.text(News.str(e, "title")).replace("（ ", "（").replace(" ）", "）"), News.str(e, "pub_index"), kind, e.optLong("pub_ts") * 1000, e.optLong("season_id"),
                        e.optLong("episode_id"), e.optInt("published") == 1, e.optInt("delay") == 1, News.str(e, "delay_reason")));
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.at, b.at));
        return out;
    }

    private static final java.util.regex.Pattern DUB = java.util.regex.Pattern.compile("\\s*[（(]?(?:中配|中文配音|粤配|粤语配音)[）)]?\\s*$");

    /** A dubbed copy (中配, 中文配音) of a show that is on the list itself is left out. */
    public static List<Episode> withoutDubs(List<Episode> eps) {
        java.util.Set<String> shows = new java.util.HashSet<>();
        for (Episode e : eps) if (!DUB.matcher(e.title).find()) shows.add(e.title);
        List<Episode> out = new ArrayList<>();
        for (Episode e : eps) if (!DUB.matcher(e.title).find() || !shows.contains(DUB.matcher(e.title).replaceFirst("").trim())) out.add(e);
        return out;
    }

    private static long day(long ms) { return Math.floorDiv(ms + 8 * 3_600_000L, 86_400_000L); }

    /** The episodes of the Beijing day `offset` days from now's. */
    public static List<Episode> on(List<Episode> all, long now, int offset) {
        List<Episode> out = new ArrayList<>();
        for (Episode e : all) if (day(e.at) == day(now) + offset) out.add(e);
        return out;
    }

    /** 「今天」, 「明天」, 「周日」, 「11-08」 → days from today (0 when not understood); the week runs Monday to Sunday. */
    public static int offset(String said, long now) {
        String s = said == null ? "" : said.trim();
        if (s.isEmpty() || s.contains("今天") || s.contains("今晚")) return 0;
        if (s.contains("大后天")) return 3;
        if (s.contains("后天")) return 2;
        if (s.contains("明天") || s.contains("明晚")) return 1;
        if (s.contains("大前天")) return -3;
        if (s.contains("前天")) return -2;
        if (s.contains("昨天") || s.contains("昨晚")) return -1;
        java.util.regex.Matcher w = java.util.regex.Pattern.compile("(上|下|这)?(?:周|星期|礼拜)([一二三四五六日天])").matcher(s);
        Calendar c = Calendar.getInstance(Sports.BEIJING);
        c.setTimeInMillis(now);
        int today = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Monday 0 … Sunday 6
        if (w.find()) {
            int want = "一二三四五六日天".indexOf(w.group(2));
            want = Math.min(want, 6);
            int d = want - today;
            if ("下".equals(w.group(1))) d += 7;
            else if ("上".equals(w.group(1))) d -= 7;
            else if (w.group(1) == null && d < 0) d += 7; // 「周一有什么番」 on a Saturday: the coming Monday
            return d;
        }
        // 「2026-11-08」 (the year read as a year, not 26 as a month), 「11-08」「1月2日」 (in late December, next year's)
        java.util.regex.Matcher md = java.util.regex.Pattern.compile("(?:([0-9]{4})[-年./])?([0-9]{1,2})[-月./]([0-9]{1,2})").matcher(s);
        if (md.find()) {
            int month = Integer.parseInt(md.group(2)), dom = Integer.parseInt(md.group(3));
            if (month < 1 || month > 12 || dom < 1 || dom > 31) return 0;
            Calendar t = Calendar.getInstance(Sports.BEIJING);
            t.setTimeInMillis(now);
            int year = md.group(1) != null ? Integer.parseInt(md.group(1)) : t.get(Calendar.YEAR);
            long d = daysTo(year, month, dom, now);
            if (md.group(1) == null && d < -180) d = daysTo(year + 1, month, dom, now);
            else if (md.group(1) == null && d > 180) d = daysTo(year - 1, month, dom, now);
            return (int) d;
        }
        return 0;
    }

    private static long daysTo(int year, int month, int dom, long now) {
        Calendar t = Calendar.getInstance(Sports.BEIJING);
        t.clear();
        t.set(year, month - 1, dom, 12, 0);
        return day(t.getTimeInMillis()) - day(now);
    }

    /** The timetable's days: a week back, six days on. */
    public static final int BACK = 7, AHEAD = 6;

    /** 今天, 明天, 昨天, 前天, 后天, else 「11 月 13 日」. */
    static String dayWord(long at, long now) {
        long d = day(at) - day(now);
        if (d == 0) return "今天";
        if (d == 1) return "明天";
        if (d == 2) return "后天";
        if (d == -1) return "昨天";
        if (d == -2) return "前天";
        Calendar c = Calendar.getInstance(Sports.BEIJING);
        c.setTimeInMillis(at);
        return (c.get(Calendar.MONTH) + 1) + " 月 " + c.get(Calendar.DAY_OF_MONTH) + " 日";
    }

    /** 「上午 11 点」「晚上 7 点 30 分」. */
    static String clock(long at) {
        Calendar c = Calendar.getInstance(Sports.BEIJING);
        c.setTimeInMillis(at);
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        String part = h < 5 ? "凌晨" : h < 8 ? "早上" : h < 12 ? "上午" : h < 13 ? "中午" : h < 18 ? "下午" : "晚上";
        return part + " " + (h > 12 ? h - 12 : h) + " 点" + (m == 0 ? "" : " " + m + " 分");
    }

    /** 「第199话」 → 「第 199 话」, 「第1话~第10话」 → 「第 1 话到第 10 话」. */
    static String index(String i) {
        return i.replace("~", "到").replaceAll("(\\d+)", " $1 ").replace("  ", " ").trim();
    }

    private static String quoted(String t) { return t.contains("《") || t.contains("》") ? t : "《" + t + "》"; }

    /**
     * 「今天B站更新的番剧和国创有 6 部：上午 9 点《万古至尊：李云霄传》第 34 话；…」; out already: 「（已更新）」. Dubbed copies are
     * left to the caller (withoutDubs).
     */
    public static String speakDay(List<Episode> day, int offset, long now) { return speakDay(day, offset, now, ""); }

    /** kind: 番剧 or 国创 for one of them only, else both. */
    public static String speakDay(List<Episode> day, int offset, long now, String kind) {
        String when = offset == 0 ? "今天" : offset == 1 ? "明天" : offset == -1 ? "昨天" : dayWord(now + offset * 86_400_000L, now);
        String what = kind == null || kind.isEmpty() ? "番剧和国创" : kind;
        // beyond the timetable is not 「没有」 (2026-11-07 review: 下周六 on a Monday was said to have none)
        String last = dayWord(now + AHEAD * 86_400_000L, now);
        if (offset > AHEAD) return "B站的更新表只排到" + (Character.isDigit(last.charAt(0)) ? " " : "") + last + "，" + when + "的还没排出来";
        if (offset < -BACK) return "B站的更新表只留了最近一周的，" + when + "的查不到了";
        if (day.isEmpty()) return when + "B站的更新表上没有" + what;
        StringBuilder s = new StringBuilder(when + "B站更新的" + what + "有 " + day.size() + " 部：");
        for (int i = 0; i < day.size(); i++) {
            Episode e = day.get(i);
            s.append(i == 0 ? "" : "；").append(clock(e.at)).append(quoted(e.title)).append(index(e.index));
            if (e.delayed) s.append("（停更").append(e.delayReason.isEmpty() ? "" : "：" + e.delayReason).append("）");
            else if (e.out && offset == 0) s.append("（已更新）");
        }
        return s.toString();
    }

    /** 「这周有什么新番」 (2026-11-10 sweep: three model rounds, one tool call a day, 7.1 s). */
    private static final java.util.regex.Pattern WEEK_ASKED = java.util.regex.Pattern.compile("这周|本周|这一周|这几天|最近|近几天");

    /** Whether a day is the whole week ahead: 「这周」「这几天」「最近」. */
    public static boolean weekAsked(String said) {
        return said != null && WEEK_ASKED.matcher(said).find();
    }

    /** The days the week answer reads: today and the next six (the timetable's whole reach forward). */
    public static final int WEEK = AHEAD + 1;

    /** How long the week answer may be: whole days are cut off the end, never a day in half. */
    static final int WEEK_MAX = 300;

    /** Today, tomorrow, then the weekday: 「今天」「明天」「周三」. */
    static String weekDay(int offset, long now) {
        if (offset == 0) return "今天";
        if (offset == 1) return "明天";
        Calendar c = Calendar.getInstance(Sports.BEIJING);
        c.setTimeInMillis(now + offset * 86_400_000L);
        return WEEKDAY[(c.get(Calendar.DAY_OF_WEEK) + 5) % 7];
    }

    /** A show's name for the week answer, cut so one long title cannot fill it (not inside an English word). */
    private static String name(String t) {
        if (t.length() <= 24) return t;
        int end = 23;
        while (end > 8 && latin(t.charAt(end)) && latin(t.charAt(end - 1))) end--;
        return t.substring(0, end).trim() + "…";
    }

    private static boolean latin(char c) { return c < 128 && Character.isLetterOrDigit(c); }

    /**
     * 「这周B站更新的番剧和国创：今天 6 部：万古至尊：李云霄传、功夫熊猫：神龙骑士 第二季、凡人修仙传；明天 5 部：…」 —
     * day by day, at most three names each, days with nothing left out, whole days cut at the end with 「还有 3 天没念」.
     * `all` is the whole timetable (dubbed copies and the other kind already left out by the caller).
     */
    public static String speakWeek(List<Episode> all, long now, String kind) {
        String what = kind == null || kind.isEmpty() ? "番剧和国创" : kind;
        StringBuilder s = new StringBuilder("这周B站更新的" + what + "：");
        List<String> days = new ArrayList<>();
        for (int d = 0; d < WEEK; d++) {
            List<Episode> on = on(all, now, d);
            if (on.isEmpty()) continue;
            StringBuilder day = new StringBuilder(weekDay(d, now)).append(" ").append(on.size()).append(" 部：");
            for (int i = 0; i < Math.min(3, on.size()); i++) day.append(i == 0 ? "" : "、").append(name(on.get(i).title));
            days.add(day.toString());
        }
        if (days.isEmpty()) return "这周B站的更新表上没有" + what;
        int said = 0, len = s.length();
        for (String day : days) {
            if (said > 0 && len + day.length() + 1 > WEEK_MAX) break;
            s.append(said == 0 ? "" : "；").append(day);
            len += day.length() + 1;
            said++;
        }
        if (said < days.size()) s.append("；还有 ").append(days.size() - said).append(" 天没念");
        return s.toString();
    }

    /** 「明天有什么国创」 asks for one kind: 国创 for 国创, 国漫, 国产动画; 番剧 for 日漫, 日本动画, 日本番; else "". */
    public static String kindAsked(String said) {
        String s = said == null ? "" : said;
        if (s.contains("国创") || s.contains("国漫") || s.contains("国产动画") || s.contains("国产动漫")) return "国创";
        if (s.contains("日漫") || s.contains("日本动画") || s.contains("日本番") || s.contains("日番") || s.contains("日本动漫")) return "番剧";
        return "";
    }

    public static List<Episode> ofKind(List<Episode> all, String kind) {
        if (kind == null || kind.isEmpty()) return all;
        List<Episode> out = new ArrayList<>();
        for (Episode e : all) if (kind.equals(e.kind)) out.add(e);
        return out;
    }

    /** The show's episodes on the list: its name in theirs or theirs in its; a dubbed copy only when asked for. */
    public static List<Episode> find(List<Episode> all, String asked) {
        String want = BiliVideos.norm(asked);
        boolean dub = DUB.matcher(asked).find();
        List<Episode> out = new ArrayList<>();
        if (want.isEmpty()) return out;
        for (Episode e : all) {
            String t = BiliVideos.norm(e.title);
            if ((t.contains(want) || want.contains(t) && t.length() >= 2) && (dub || !DUB.matcher(e.title).find())) out.add(e);
        }
        // one show: the name that matched first (another season or a spin-off is another show)
        if (!out.isEmpty()) {
            String first = out.get(0).title;
            for (Episode e : out) if (BiliVideos.norm(e.title).equals(want)) { first = e.title; break; }
            List<Episode> one = new ArrayList<>();
            for (Episode e : out) if (e.title.equals(first)) one.add(e);
            return one;
        }
        return out;
    }

    public static Episode latest(List<Episode> show) {
        Episode best = null;
        for (Episode e : show) if (e.out && (best == null || e.at > best.at)) best = e;
        return best;
    }

    public static Episode next(List<Episode> show) {
        for (Episode e : show) if (!e.out) return e;
        return null;
    }

    private static final String[] WEEKDAY = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /** 「每周六上午 11 点」「每天上午 10 点」「每周一到周五中午 12 点」「每周一、周三、周六」 (times that differ are left out). */
    static String schedule(List<Episode> show) {
        TreeSet<Integer> days = new TreeSet<>();
        java.util.Set<String> times = new java.util.HashSet<>();
        for (Episode e : show) {
            Calendar c = Calendar.getInstance(Sports.BEIJING);
            c.setTimeInMillis(e.at);
            days.add((c.get(Calendar.DAY_OF_WEEK) + 5) % 7);
            times.add(clock(e.at));
        }
        if (show.size() < 2 && days.size() < 2) return "";
        String when;
        if (days.size() == 7) when = "每天";
        else if (days.equals(new TreeSet<>(java.util.Arrays.asList(0, 1, 2, 3, 4)))) when = "每周一到周五";
        else {
            List<String> w = new ArrayList<>();
            for (int d : days) w.add(WEEKDAY[d]);
            when = "每" + String.join("、", w);
        }
        return when + (times.size() == 1 ? times.iterator().next() : "");
    }

    /**
     * 「《凡人修仙传》每周六上午 11 点更新：最新一集是第 198 话（10 月 31 日）；下一集第 199 话今天上午 11 点更新」; not out yet:
     * 「…还没开播，第 1 话到第 10 话 11 月 9 日中午 12 点上线」.
     */
    public static String speakShow(List<Episode> show, String asked, long now) {
        if (show.isEmpty()) return "B站这两周的更新表上没有「" + asked + "」：可能已经完结，或者不在B站";
        Episode last = latest(show), next = next(show);
        String when = schedule(show);
        StringBuilder s = new StringBuilder(quoted(show.get(0).title)).append(when.isEmpty() ? "" : when + "更新");
        if (last == null && next != null) {
            return s.append(when.isEmpty() ? "" : "，").append("还没开播，").append(index(next.index)).append("在").append(dayWord(next.at, now))
                    .append(clock(next.at)).append("上线").toString();
        }
        s.append(when.isEmpty() ? "" : "：");
        if (last != null) s.append("最新一集是").append(index(last.index)).append("（").append(dayWord(last.at, now)).append("）");
        if (next != null) {
            s.append(last != null ? "；" : "").append("下一集").append(index(next.index));
            if (next.delayed) s.append("停更").append(next.delayReason.isEmpty() ? "" : "：" + next.delayReason);
            else s.append(dayWord(next.at, now)).append(clock(next.at)).append("更新");
        }
        return s.toString();
    }

    /** What was read out, for environment.last_list: 「放第三个」 opens its episode. */
    public static JSONObject readOut(List<Episode> day, int offset, long now) throws JSONException {
        JSONArray items = new JSONArray();
        for (int i = 0; i < day.size(); i++) {
            Episode e = day.get(i);
            items.put(new JSONObject().put("n", i + 1).put("title", e.title).put("episode", e.index).put("out", e.out).put("link", e.link()));
        }
        String when = offset == 0 ? "今天" : offset == 1 ? "明天" : offset == -1 ? "昨天" : dayWord(now + offset * 86_400_000L, now);
        return new JSONObject().put("what", when + "B站更新的番剧").put("items", items)
                .put("note", "刚读给用户的番：用户说“放第三个”就用 open_link 打开它的 link（还没更新的打不开，照实说）");
    }

    public static JSONArray rows(List<Episode> eps, long now) throws JSONException {
        JSONArray out = new JSONArray();
        for (Episode e : eps) out.put(new JSONObject().put("title", e.title).put("episode", e.index).put("kind", e.kind)
                .put("when", dayWord(e.at, now) + clock(e.at)).put("out", e.out).put("link", e.link()));
        return out;
    }
}
