package xyz.erapi0neer.xingyi.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * NBA scores and fixtures from ESPN's public API (2026-11-06): a team's last game and its next, or a day's games, by
 * Beijing time and in Chinese names (「尼克斯上一场是今天上午 8 点 30 分，主场 135 比 105 赢了公牛」). Football and the
 * rest go to web_search.
 */
public final class Sports {
    private Sports() { }

    private static final String NBA = "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/";
    public static final TimeZone BEIJING = TimeZone.getTimeZone("Asia/Shanghai");

    /** {ESPN id, abbreviation, Chinese name, other names}. */
    private static final String[][] TEAMS = {
            {"1", "ATL", "老鹰"}, {"2", "BOS", "凯尔特人", "绿军"}, {"17", "BKN", "篮网"}, {"30", "CHA", "黄蜂"}, {"4", "CHI", "公牛"},
            {"5", "CLE", "骑士"}, {"6", "DAL", "独行侠", "小牛"}, {"7", "DEN", "掘金"}, {"8", "DET", "活塞"}, {"9", "GS", "勇士"},
            {"10", "HOU", "火箭"}, {"11", "IND", "步行者"}, {"12", "LAC", "快船"}, {"13", "LAL", "湖人"}, {"29", "MEM", "灰熊"},
            {"14", "MIA", "热火"}, {"15", "MIL", "雄鹿"}, {"16", "MIN", "森林狼"}, {"3", "NO", "鹈鹕"}, {"18", "NY", "尼克斯"},
            {"25", "OKC", "雷霆"}, {"19", "ORL", "魔术"}, {"20", "PHI", "76人", "七六人"}, {"21", "PHX", "太阳"}, {"22", "POR", "开拓者"},
            {"23", "SAC", "国王"}, {"24", "SA", "马刺"}, {"28", "TOR", "猛龙"}, {"26", "UTAH", "爵士"}, {"27", "WSH", "奇才"}};

    /** The Chinese names as a regex alternation, for the quick commands. */
    public static String names() {
        StringBuilder s = new StringBuilder();
        for (String[] t : TEAMS) for (int i = 2; i < t.length; i++) s.append(s.length() == 0 ? "" : "|").append(t[i]);
        return s.toString();
    }

    /** {id, abbreviation, Chinese name} for 「尼克斯」「纽约尼克斯」「NY」, or null. */
    public static String[] team(String said) {
        String s = said == null ? "" : said.trim().replaceAll("[\\s　]", "");
        if (s.isEmpty()) return null;
        for (String[] t : TEAMS) {
            if (t[1].equalsIgnoreCase(s)) return new String[]{t[0], t[1], t[2]};
            for (int i = 2; i < t.length; i++) if (s.equals(t[i]) || s.endsWith(t[i])) return new String[]{t[0], t[1], t[2]};
        }
        return null;
    }

    static String cn(String abbr) {
        for (String[] t : TEAMS) if (t[1].equalsIgnoreCase(abbr)) return t[2];
        return abbr;
    }

    public static String scheduleUrl(String id) { return NBA + "teams/" + id + "/schedule"; }

    /** The games of a Beijing date are those of the day before on ESPN's (US) calendar: NBA nights are Beijing mornings. */
    public static String boardUrl(Calendar beijingDay) {
        Calendar us = (Calendar) beijingDay.clone();
        us.add(Calendar.DAY_OF_MONTH, -1);
        return NBA + "scoreboard?dates=" + String.format(Locale.ROOT, "%04d%02d%02d", us.get(Calendar.YEAR), us.get(Calendar.MONTH) + 1,
                us.get(Calendar.DAY_OF_MONTH));
    }

    /** One game, from either a scoreboard (the score a string) or a schedule (the score an object). */
    static final class Game {
        final long at;
        final String state, home, away, homeScore, awayScore, clock;
        final int period;

        Game(long at, String state, String home, String away, String homeScore, String awayScore, int period, String clock) {
            this.at = at; this.state = state; this.home = home; this.away = away; this.homeScore = homeScore; this.awayScore = awayScore;
            this.period = period; this.clock = clock;
        }

        boolean done() { return "STATUS_FINAL".equals(state); }
        boolean live() { return state.contains("IN_PROGRESS") || state.contains("HALFTIME") || state.contains("END_PERIOD"); }
    }

    static List<Game> games(String json) throws JSONException {
        JSONArray events = new JSONObject(json).optJSONArray("events");
        List<Game> out = new ArrayList<>();
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            JSONObject c = e.getJSONArray("competitions").getJSONObject(0), st = c.optJSONObject("status");
            JSONObject type = st == null ? null : st.optJSONObject("type");
            String home = "", away = "", hs = "", as = "";
            JSONArray cs = c.getJSONArray("competitors");
            for (int k = 0; k < cs.length(); k++) {
                JSONObject t = cs.getJSONObject(k);
                String abbr = t.getJSONObject("team").optString("abbreviation");
                Object score = t.opt("score");
                String sc = score instanceof JSONObject ? ((JSONObject) score).optString("displayValue") : score == null ? "" : String.valueOf(score);
                if ("home".equals(News.str(t, "homeAway"))) { home = abbr; hs = sc; } else { away = abbr; as = sc; }
            }
            out.add(new Game(parse(News.str(e, "date")), type == null ? "" : News.str(type, "name"), home, away, hs, as,
                    st == null ? 0 : st.optInt("period"), st == null ? "" : News.str(st, "displayClock")));
        }
        return out;
    }

    static long parse(String iso) {
        for (String f : new String[]{"yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            SimpleDateFormat p = new SimpleDateFormat(f, Locale.ROOT);
            p.setTimeZone(TimeZone.getTimeZone("UTC"));
            try { return p.parse(iso).getTime(); } catch (ParseException ignored) { }
        }
        return 0;
    }

    /** when() after a word: 「是 11 月 8 日…」 keeps its space before the number, 「是今天…」 has none. */
    static String after(long at, long now) {
        String w = when(at, now);
        return !w.isEmpty() && Character.isDigit(w.charAt(0)) ? " " + w : w;
    }

    /** 「今天上午 8 点 30 分」「11 月 8 日上午 9 点」, Beijing time. */
    static String when(long at, long now) {
        Calendar c = Calendar.getInstance(BEIJING), n = Calendar.getInstance(BEIJING);
        c.setTimeInMillis(at);
        n.setTimeInMillis(now);
        long days = dayOf(c) - dayOf(n);
        String day = days == 0 ? "今天" : days == 1 ? "明天" : days == -1 ? "昨天" : (c.get(Calendar.MONTH) + 1) + " 月 " + c.get(Calendar.DAY_OF_MONTH) + " 日";
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        return day + partOfDay(h) + " " + (h > 12 ? h - 12 : h) + " 点" + (m == 0 ? "" : " " + m + " 分");
    }

    /** The part of the day an hour falls in, Beijing time: 凌晨 / 早上 / 上午 / 中午 / 下午 / 晚上. */
    private static String partOfDay(int h) {
        return h < 5 ? "凌晨" : h < 8 ? "早上" : h < 12 ? "上午" : h < 13 ? "中午" : h < 18 ? "下午" : "晚上";
    }

    /** The game's Beijing day and the part of it, from its start: 「今天上午」「昨天晚上」; a date for anything older
     * (2026-11-10: 「湖人上一场 今天 124 比 114 赢了老鹰」 said neither when the game was nor read well). */
    static String dayPart(long at, long now) {
        Calendar c = Calendar.getInstance(BEIJING), n = Calendar.getInstance(BEIJING);
        c.setTimeInMillis(at);
        n.setTimeInMillis(now);
        long days = dayOf(c) - dayOf(n);
        if (days != 0 && days != -1) return (c.get(Calendar.MONTH) + 1) + " 月 " + c.get(Calendar.DAY_OF_MONTH) + " 日";
        return (days == 0 ? "今天" : "昨天") + partOfDay(c.get(Calendar.HOUR_OF_DAY));
    }

    private static long dayOf(Calendar c) {
        return (c.getTimeInMillis() + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)) / 86_400_000L;
    }

    /** 「尼克斯上一场是昨天上午 8 点 30 分，主场 135 比 105 赢了公牛」 (the score read as a game on today); with dateOnly
     * 「尼克斯上一场（昨天上午）135 比 105 赢了公牛」 — what 「湖人昨天赢了吗」 asks: the result, with the day and the part of
     * it the game was played in, and no stray space between the words (2026-11-10). */
    private static String lastGame(Game g, String abbr, String me, long now, boolean dateOnly) {
        boolean home = abbr.equalsIgnoreCase(g.home);
        int mine = num(home ? g.homeScore : g.awayScore), theirs = num(home ? g.awayScore : g.homeScore);
        String won = mine > theirs ? " 赢了" : " 输给了", other = cn(home ? g.away : g.home);
        if (dateOnly) return me + "上一场（" + dayPart(g.at, now) + "）" + mine + " 比 " + theirs + won + other;
        return me + "上一场是" + after(g.at, now) + "，" + (home ? "主场" : "客场") + " " + mine + " 比 " + theirs + won + other;
    }

    /** 「魔术 16 比 13 领先太阳，第 1 节还剩 6:47」「热火对勇士，上午 11 点开始」「76人 112 比 106 胜活塞」. */
    static String line(Game g, long now) {
        String h = cn(g.home), a = cn(g.away);
        if (g.done()) {
            boolean homeWon = num(g.homeScore) > num(g.awayScore);
            return homeWon ? h + " " + g.homeScore + " 比 " + g.awayScore + " 胜" + a : a + " " + g.awayScore + " 比 " + g.homeScore + " 胜" + h;
        }
        if (g.live()) {
            String left = clockWords(g.clock);
            String stage = g.state.contains("HALFTIME") ? "中场休息" : (g.period > 4 ? "加时" : "第 " + g.period + " 节") + (left.isEmpty() ? "结束" : "还剩 " + left);
            int d = num(g.homeScore) - num(g.awayScore);
            String score = d == 0 ? h + "和" + a + " " + g.homeScore + " 平" : d > 0 ? h + " " + g.homeScore + " 比 " + g.awayScore + " 领先" + a
                    : a + " " + g.awayScore + " 比 " + g.homeScore + " 领先" + h;
            return score + "，" + stage;
        }
        return a + "对" + h + "（" + h + "主场），" + when(g.at, now) + "开始";
    }

    /** The game clock in words: 「6:47」→「6 分 47 秒」, 「45.3」→「45 秒」, 「0.0」→ nothing (the period is over). */
    static String clockWords(String clock) {
        String c = clock == null ? "" : clock.trim();
        if (c.isEmpty() || c.matches("0+(?:[.:]0+)?")) return "";
        int colon = c.indexOf(':');
        try {
            if (colon < 0) return (int) Math.floor(Double.parseDouble(c)) + " 秒";
            int m = Integer.parseInt(c.substring(0, colon)), sec = (int) Math.floor(Double.parseDouble(c.substring(colon + 1)));
            return m + " 分" + (sec == 0 ? "" : " " + sec + " 秒");
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static int num(String s) {
        try { return (int) Math.round(Double.parseDouble(s)); } catch (NumberFormatException e) { return 0; }
    }

    /** A day's games: 「北京时间今天的 NBA 有 2 场：…」. */
    public static String speakBoard(String json, String dayWord, long now) throws JSONException {
        List<Game> gs = games(json);
        if (gs.isEmpty()) return "北京时间" + dayWord + "没有 NBA 比赛";
        StringBuilder s = new StringBuilder("北京时间" + dayWord + "的 NBA 有 " + gs.size() + " 场：");
        int shown = Math.min(gs.size(), 8);
        for (int i = 0; i < shown; i++) s.append(i == 0 ? "" : "；").append(line(gs.get(i), now));
        if (gs.size() > shown) s.append("；还有 ").append(gs.size() - shown).append(" 场");
        return s.toString();
    }

    /** A team: the game on now, else its last finished one, and its next. */
    public static String speakTeam(String json, String abbr, long now) throws JSONException {
        return speakTeam(json, abbr, now, false);
    }

    /**
     * last (「湖人昨天赢了吗」, 2026-11-10 sweep: the answer never said how the game ended): the last finished game first —
     * its Beijing day and the part of it in brackets, the score and 赢了/输给了 — then the one on now as 「现在正在打：…」,
     * then the next as usual.
     */
    public static String speakTeam(String json, String abbr, long now, boolean last) throws JSONException {
        List<Game> gs = games(json);
        String me = cn(abbr);
        Game finished = null, live = null, next = null;
        for (Game g : gs) {
            if (g.live()) live = g;
            else if (g.done()) { if (finished == null || g.at > finished.at) finished = g; }
            else if (g.at > now - 3 * 3_600_000L && (next == null || g.at < next.at)) next = g;
        }
        StringBuilder s = new StringBuilder();
        // nothing finished yet: said in a few words, and the game on now (if any) as it is said without `last`
        if (last && finished != null) {
            s.append(lastGame(finished, abbr, me, now, true));
            if (live != null) s.append("；现在正在打：").append(line(live, now));
        } else if (live != null) s.append(me).append("正在比赛：").append(line(live, now));
        else if (last) s.append(me).append("这个赛季还没打过比赛");
        else if (finished != null) s.append(lastGame(finished, abbr, me, now, false));
        if (next != null) {
            boolean home = abbr.equalsIgnoreCase(next.home);
            s.append(s.length() > 0 ? "；下一场是" : me + "下一场是").append(after(next.at, now)).append("，").append(home ? "主场打" : "客场打")
                    .append(cn(home ? next.away : next.home));
        }
        return s.length() == 0 ? me + "这个赛季的赛程里没有查到比赛" : s.toString();
    }
}
