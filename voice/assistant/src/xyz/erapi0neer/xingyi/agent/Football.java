package xyz.erapi0neer.xingyi.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Football scores and fixtures from ESPN's public API (2026-11-06): the Premier League, La Liga, the Bundesliga, Serie
 * A, Ligue 1, the Champions League and the Chinese Super League — a team's last result and next game, or a league's
 * games on a day, by Beijing time and in Chinese club names. A team's results and fixtures are two lists at ESPN
 * (schedule, schedule?fixture=true).
 */
public final class Football {
    private Football() { }

    private static final String BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer/";

    /** {what people say, ESPN's league, its Chinese name}. */
    private static final String[][] LEAGUES = {
            {"英超", "eng.1", "英超"}, {"西甲", "esp.1", "西甲"}, {"德甲", "ger.1", "德甲"}, {"意甲", "ita.1", "意甲"}, {"法甲", "fra.1", "法甲"},
            {"欧冠", "uefa.champions", "欧冠"}, {"中超", "chn.1", "中超"}};

    /** {league, ESPN id, ESPN's name, Chinese name, other names}. */
    private static final String[][] CLUBS = {
            {"eng.1", "349", "AFC Bournemouth", "伯恩茅斯"}, {"eng.1", "359", "Arsenal", "阿森纳", "枪手"}, {"eng.1", "362", "Aston Villa", "阿斯顿维拉", "维拉"},
            {"eng.1", "337", "Brentford", "布伦特福德"}, {"eng.1", "331", "Brighton & Hove Albion", "布莱顿"}, {"eng.1", "363", "Chelsea", "切尔西", "蓝军"},
            {"eng.1", "388", "Coventry City", "考文垂"}, {"eng.1", "384", "Crystal Palace", "水晶宫"}, {"eng.1", "368", "Everton", "埃弗顿"},
            {"eng.1", "370", "Fulham", "富勒姆"}, {"eng.1", "306", "Hull City", "赫尔城"}, {"eng.1", "373", "Ipswich Town", "伊普斯维奇"},
            {"eng.1", "357", "Leeds United", "利兹联"}, {"eng.1", "364", "Liverpool", "利物浦", "红军"}, {"eng.1", "382", "Manchester City", "曼城"},
            {"eng.1", "360", "Manchester United", "曼联"}, {"eng.1", "361", "Newcastle United", "纽卡斯尔", "纽卡"},
            {"eng.1", "393", "Nottingham Forest", "诺丁汉森林"}, {"eng.1", "366", "Sunderland", "桑德兰"}, {"eng.1", "367", "Tottenham Hotspur", "热刺"},
            {"esp.1", "96", "Alavés", "阿拉维斯"}, {"esp.1", "93", "Athletic Club", "毕尔巴鄂竞技", "毕尔巴鄂"}, {"esp.1", "1068", "Atlético Madrid", "马德里竞技", "马竞"},
            {"esp.1", "83", "Barcelona", "巴塞罗那", "巴萨"}, {"esp.1", "85", "Celta Vigo", "塞尔塔"}, {"esp.1", "90", "Deportivo", "拉科鲁尼亚"},
            {"esp.1", "3751", "Elche", "埃尔切"}, {"esp.1", "88", "Espanyol", "西班牙人"}, {"esp.1", "2922", "Getafe", "赫塔菲"},
            {"esp.1", "1538", "Levante", "莱万特"}, {"esp.1", "99", "Málaga", "马拉加"}, {"esp.1", "97", "Osasuna", "奥萨苏纳"},
            {"esp.1", "87", "Racing Santander", "桑坦德竞技"}, {"esp.1", "101", "Rayo Vallecano", "巴列卡诺"}, {"esp.1", "244", "Real Betis", "皇家贝蒂斯", "贝蒂斯"},
            {"esp.1", "86", "Real Madrid", "皇家马德里", "皇马"}, {"esp.1", "89", "Real Sociedad", "皇家社会"}, {"esp.1", "243", "Sevilla", "塞维利亚"},
            {"esp.1", "94", "Valencia", "瓦伦西亚"}, {"esp.1", "102", "Villarreal", "比利亚雷亚尔", "黄潜"},
            {"ger.1", "598", "1. FC Union Berlin", "柏林联合"}, {"ger.1", "131", "Bayer Leverkusen", "勒沃库森", "药厂"},
            {"ger.1", "132", "Bayern Munich", "拜仁慕尼黑", "拜仁"}, {"ger.1", "124", "Borussia Dortmund", "多特蒙德", "多特"},
            {"ger.1", "268", "Borussia Mönchengladbach", "门兴格拉德巴赫", "门兴"}, {"ger.1", "125", "Eintracht Frankfurt", "法兰克福"},
            {"ger.1", "3841", "FC Augsburg", "奥格斯堡"}, {"ger.1", "122", "FC Cologne", "科隆"}, {"ger.1", "127", "Hamburg SV", "汉堡"},
            {"ger.1", "2950", "Mainz", "美因茨"}, {"ger.1", "11420", "RB Leipzig", "莱比锡红牛", "莱比锡"}, {"ger.1", "126", "SC Freiburg", "弗赖堡"},
            {"ger.1", "3307", "SC Paderborn 07", "帕德博恩"}, {"ger.1", "10388", "SV Elversberg", "埃尔弗斯贝格"}, {"ger.1", "133", "Schalke 04", "沙尔克04", "沙尔克"},
            {"ger.1", "7911", "TSG Hoffenheim", "霍芬海姆"}, {"ger.1", "134", "VfB Stuttgart", "斯图加特"}, {"ger.1", "137", "Werder Bremen", "云达不来梅", "不来梅"},
            {"ita.1", "103", "AC Milan", "AC米兰", "米兰"}, {"ita.1", "104", "AS Roma", "罗马"}, {"ita.1", "105", "Atalanta", "亚特兰大"},
            {"ita.1", "107", "Bologna", "博洛尼亚"}, {"ita.1", "2925", "Cagliari", "卡利亚里"}, {"ita.1", "2572", "Como", "科莫"},
            {"ita.1", "109", "Fiorentina", "佛罗伦萨", "紫百合"}, {"ita.1", "4057", "Frosinone", "弗罗西诺内"}, {"ita.1", "3263", "Genoa", "热那亚"},
            {"ita.1", "110", "Internazionale", "国际米兰", "国米"}, {"ita.1", "111", "Juventus", "尤文图斯", "尤文"}, {"ita.1", "112", "Lazio", "拉齐奥"},
            {"ita.1", "113", "Lecce", "莱切"}, {"ita.1", "4007", "Monza", "蒙扎"}, {"ita.1", "114", "Napoli", "那不勒斯"}, {"ita.1", "115", "Parma", "帕尔马"},
            {"ita.1", "3997", "Sassuolo", "萨索洛"}, {"ita.1", "239", "Torino", "都灵"}, {"ita.1", "118", "Udinese", "乌迪内斯"}, {"ita.1", "17530", "Venezia", "威尼斯"},
            {"fra.1", "172", "AJ Auxerre", "欧塞尔"}, {"fra.1", "174", "AS Monaco", "摩纳哥"}, {"fra.1", "7868", "Angers", "昂热"},
            {"fra.1", "6997", "Brest", "布雷斯特"}, {"fra.1", "3236", "Le Havre AC", "勒阿弗尔"}, {"fra.1", "2697", "Le Mans", "勒芒"},
            {"fra.1", "175", "Lens", "朗斯"}, {"fra.1", "166", "Lille", "里尔"}, {"fra.1", "273", "Lorient", "洛里昂"}, {"fra.1", "167", "Lyon", "里昂"},
            {"fra.1", "176", "Marseille", "马赛"}, {"fra.1", "2502", "Nice", "尼斯"}, {"fra.1", "6851", "Paris FC", "巴黎FC"},
            {"fra.1", "160", "Paris Saint-Germain", "巴黎圣日耳曼", "大巴黎", "巴黎"}, {"fra.1", "169", "Stade Rennais", "雷恩"},
            {"fra.1", "180", "Strasbourg", "斯特拉斯堡"}, {"fra.1", "179", "Toulouse", "图卢兹"}, {"fra.1", "170", "Troyes", "特鲁瓦"},
            {"chn.1", "2052", "Beijing Guoan", "北京国安", "国安"}, {"chn.1", "21355", "Chengdu Rongcheng", "成都蓉城"},
            {"chn.1", "131704", "Chongqing Tonglianglong", "重庆铜梁龙"}, {"chn.1", "22537", "Dalian Yingbo", "大连英博"}, {"chn.1", "8240", "Henan", "河南队"},
            {"chn.1", "131705", "Liaoning Tieren", "辽宁铁人"}, {"chn.1", "21910", "Qingdao Hainiu", "青岛海牛"}, {"chn.1", "22198", "Qingdao West Coast", "青岛西海岸"},
            {"chn.1", "7521", "Shandong Taishan", "山东泰山", "泰山"}, {"chn.1", "15515", "Shanghai Port FC", "上海海港", "海港"},
            {"chn.1", "977", "Shanghai Shenhua FC", "上海申花", "申花"}, {"chn.1", "22199", "Shenzhen Xinpengcheng", "深圳新鹏城"},
            {"chn.1", "8239", "Tianjin Jinmen Tiger", "天津津门虎", "津门虎"}, {"chn.1", "21506", "Wuhan Three Towns", "武汉三镇"},
            {"chn.1", "22536", "Yunnan Yukun", "云南玉昆"}, {"chn.1", "18203", "Zhejiang Professional FC", "浙江队"}};

    /** Clubs from elsewhere that the Champions League brings, by ESPN's name, for saying them in Chinese. */
    private static final String[][] GUESTS = {
            {"Benfica", "本菲卡"}, {"FC Porto", "波尔图"}, {"Sporting CP", "葡萄牙体育"}, {"Ajax Amsterdam", "阿贾克斯"}, {"PSV Eindhoven", "埃因霍温"},
            {"Feyenoord Rotterdam", "费耶诺德"}, {"Celtic", "凯尔特人"}, {"Club Brugge", "布鲁日"}, {"Galatasaray", "加拉塔萨雷"},
            {"Fenerbahce", "费内巴切"}, {"Red Bull Salzburg", "萨尔茨堡红牛"}, {"Shakhtar Donetsk", "顿涅茨克矿工"}, {"Olympiacos", "奥林匹亚科斯"},
            {"Slavia Prague", "布拉格斯拉维亚"}, {"Sparta Prague", "布拉格斯巴达"}, {"FC Copenhagen", "哥本哈根"}, {"Young Boys", "年轻人"},
            {"Dinamo Zagreb", "萨格勒布迪纳摩"}, {"Crvena Zvezda", "贝尔格莱德红星"}, {"Union St.-Gilloise", "圣吉罗斯联合"}};

    /** {ESPN league, Chinese name} for 「英超」…, or null. */
    public static String[] league(String said) {
        String s = said == null ? "" : said.trim();
        for (String[] l : LEAGUES) if (s.contains(l[0])) return new String[]{l[1], l[2]};
        return null;
    }

    /** {league, id, Chinese name} for 「曼联」「上海申花」, exact names before the ones a longer name ends with. */
    public static String[] club(String said) {
        String s = said == null ? "" : said.trim().replaceAll("[\\s　]", "");
        if (s.isEmpty()) return null;
        for (String[] c : CLUBS) for (int i = 3; i < c.length; i++) if (s.equals(c[i])) return new String[]{c[0], c[1], c[3]};
        for (String[] c : CLUBS) for (int i = 3; i < c.length; i++) if (s.endsWith(c[i]) && c[i].length() >= 2) return new String[]{c[0], c[1], c[3]};
        return null;
    }

    /** The Chinese names as a regex alternation (the longer first), for the quick commands. */
    public static String names() {
        List<String> all = new ArrayList<>();
        for (String[] c : CLUBS) for (int i = 3; i < c.length; i++) if (!all.contains(c[i])) all.add(c[i]);
        Collections.sort(all, (a, b) -> b.length() - a.length());
        return String.join("|", all);
    }

    static String cn(String id, String english) {
        for (String[] c : CLUBS) if (c[1].equals(id)) return c[3];
        for (String[] g : GUESTS) if (g[0].equalsIgnoreCase(english)) return g[1];
        return english;
    }

    public static String resultsUrl(String league, String id) { return BASE + league + "/teams/" + id + "/schedule"; }

    public static String fixturesUrl(String league, String id) { return resultsUrl(league, id) + "?fixture=true"; }

    public static String boardUrl(String league, Calendar day) {
        return BASE + league + "/scoreboard?dates=" + String.format(Locale.ROOT, "%04d%02d%02d", day.get(Calendar.YEAR),
                day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH));
    }

    static final class Match {
        final long at;
        final String state, clock, homeId, awayId, home, away, homeScore, awayScore;

        Match(long at, String state, String clock, String homeId, String awayId, String home, String away, String hs, String as) {
            this.at = at; this.state = state; this.clock = clock; this.homeId = homeId; this.awayId = awayId; this.home = home;
            this.away = away; this.homeScore = hs; this.awayScore = as;
        }
    }

    static List<Match> matches(String json) throws JSONException {
        JSONArray events = new JSONObject(json).optJSONArray("events");
        List<Match> out = new ArrayList<>();
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i), c = e.getJSONArray("competitions").getJSONObject(0);
            JSONObject st = c.optJSONObject("status"), type = st == null ? null : st.optJSONObject("type");
            String hId = "", aId = "", h = "", a = "", hs = "", as = "";
            JSONArray cs = c.getJSONArray("competitors");
            for (int k = 0; k < cs.length(); k++) {
                JSONObject t = cs.getJSONObject(k), team = t.getJSONObject("team");
                Object score = t.opt("score");
                String sc = score instanceof JSONObject ? ((JSONObject) score).optString("displayValue") : score == null ? "" : String.valueOf(score);
                String id = News.str(team, "id"), name = cn(id, News.str(team, "displayName"));
                if ("home".equals(News.str(t, "homeAway"))) { hId = id; h = name; hs = sc; } else { aId = id; a = name; as = sc; }
            }
            out.add(new Match(Sports.parse(News.str(e, "date")), type == null ? "" : News.str(type, "state"),
                    st == null ? "" : News.str(st, "displayClock"), hId, aId, h, a, hs, as));
        }
        Collections.sort(out, (x, y) -> Long.compare(x.at, y.at));
        return out;
    }

    private static int num(String s) {
        try { return (int) Math.round(Double.parseDouble(s)); } catch (NumberFormatException e) { return 0; }
    }

    /** 「阿森纳 1 比 0 胜利物浦」「阿斯顿维拉 1 比 1 战平富勒姆」「第 67 分钟，曼联 1 比 0 领先热刺」「曼联主场对阿斯顿维拉，…开始」. */
    static String line(Match m, long now) {
        int h = num(m.homeScore), a = num(m.awayScore);
        if ("post".equals(m.state)) {
            if (h == a) return m.home + " " + h + " 比 " + a + " 战平" + m.away;
            return h > a ? m.home + " " + h + " 比 " + a + " 胜" + m.away : m.away + " " + a + " 比 " + h + " 胜" + m.home;
        }
        if ("in".equals(m.state)) {
            String minute = m.clock.replace("'", "").trim();
            String at = minute.isEmpty() ? "" : minute.matches("\\d+\\+\\d+") ? "第 " + minute.substring(0, minute.indexOf('+')) + " 分钟补时阶段，"
                    : minute.matches("\\d+") ? "第 " + minute + " 分钟，" : "";
            if ("HT".equalsIgnoreCase(m.clock.trim())) at = "中场休息，";
            return at + (h == a ? m.home + "和" + m.away + " " + h + " 比 " + a + " 战平中" : h > a ? m.home + " " + h + " 比 " + a + " 领先" + m.away
                    : m.away + " " + a + " 比 " + h + " 领先" + m.home);
        }
        return m.home + "主场对" + m.away + "，" + Sports.when(m.at, now) + "开始";
    }

    /** A league's games on a Beijing day, from the two ESPN days it spans: 「北京时间今天的英超有 3 场：…」. */
    public static String speakBoard(List<String> jsons, String leagueName, Calendar beijingDay, long now) throws JSONException {
        List<Match> all = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String j : jsons) for (Match m : matches(j)) {
            Calendar c = Calendar.getInstance(Sports.BEIJING);
            c.setTimeInMillis(m.at);
            String key = m.at + m.homeId + m.awayId;
            if (c.get(Calendar.YEAR) == beijingDay.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == beijingDay.get(Calendar.DAY_OF_YEAR)
                    && !seen.contains(key)) { all.add(m); seen.add(key); }
        }
        Collections.sort(all, (x, y) -> Long.compare(x.at, y.at));
        String day = dayWord(beijingDay, now);
        if (all.isEmpty()) return "北京时间" + day + "没有" + leagueName + "比赛";
        StringBuilder s = new StringBuilder("北京时间" + day + "的" + leagueName + "有 " + all.size() + " 场：");
        int shown = Math.min(all.size(), 8);
        for (int i = 0; i < shown; i++) s.append(i == 0 ? "" : "；").append(line(all.get(i), now));
        if (all.size() > shown) s.append("；还有 ").append(all.size() - shown).append(" 场");
        return s.toString();
    }

    static String dayWord(Calendar day, long now) {
        Calendar n = Calendar.getInstance(Sports.BEIJING);
        n.setTimeInMillis(now);
        long d = (day.getTimeInMillis() + day.get(Calendar.ZONE_OFFSET)) / 86_400_000L - (n.getTimeInMillis() + n.get(Calendar.ZONE_OFFSET)) / 86_400_000L;
        return d == 0 ? "今天" : d == -1 ? "昨天" : d == 1 ? "明天" : d == 2 ? "后天" : " " + (day.get(Calendar.MONTH) + 1) + " 月 " + day.get(Calendar.DAY_OF_MONTH) + " 日";
    }

    /** A club: the game on now, else its last result; and its next game. 「曼联上一场是…，客场 0 比 2 输给了切尔西；下一场是…」. */
    public static String speakClub(String resultsJson, String fixturesJson, String id, String name, long now) throws JSONException {
        Match last = null, live = null, next = null;
        List<Match> all = new ArrayList<>(matches(resultsJson));
        if (fixturesJson != null) all.addAll(matches(fixturesJson));
        for (Match m : all) {
            if (!id.equals(m.homeId) && !id.equals(m.awayId)) continue;
            if ("in".equals(m.state)) live = m;
            else if ("post".equals(m.state)) { if (last == null || m.at > last.at) last = m; }
            else if (m.at > now - 3 * 3_600_000L && (next == null || m.at < next.at)) next = m;
        }
        StringBuilder s = new StringBuilder();
        if (live != null) s.append(name).append("正在比赛：").append(line(live, now));
        else if (last != null) {
            boolean home = id.equals(last.homeId);
            int mine = num(home ? last.homeScore : last.awayScore), theirs = num(home ? last.awayScore : last.homeScore);
            s.append(name).append("上一场是").append(Sports.after(last.at, now)).append("，").append(home ? "主场" : "客场").append(" ").append(mine)
                    .append(" 比 ").append(theirs).append(mine > theirs ? " 赢了" : mine < theirs ? " 输给了" : " 战平").append(home ? last.away : last.home);
        }
        if (next != null) {
            boolean home = id.equals(next.homeId);
            s.append(s.length() > 0 ? "；下一场是" : name + "下一场是").append(Sports.after(next.at, now)).append("，").append(home ? "主场打" : "客场打")
                    .append(home ? next.away : next.home);
        }
        return s.length() == 0 ? name + "最近没有查到比赛" : s.toString();
    }
}
