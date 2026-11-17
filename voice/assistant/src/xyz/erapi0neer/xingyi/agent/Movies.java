package xyz.erapi0neer.xingyi.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 「狂飙豆瓣多少分」「流浪地球2好看吗」 (2026-11-07): a film's or a show's Douban rating, with its year, kind, region,
 * genres, director and leads, from Douban's mobile search (no key; it wants its own pages as the referrer, Feeds).
 * Picking a TV show to watch is what this TV is for; a web search took 7-9 s for it.
 */
public final class Movies {
    private Movies() { }

    public static String url(String title) {
        try { return "https://m.douban.com/rexxar/api/v2/search/movie?count=8&q=" + java.net.URLEncoder.encode(title, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }

    public static final class Title {
        public final String name, year, kind, id, subtitle, noRating;
        public final double rating;
        public final long votes;
        final boolean movie;

        Title(String name, String year, String kind, boolean movie, String id, double rating, long votes, String subtitle, String noRating) {
            this.name = name; this.year = year; this.kind = kind; this.movie = movie; this.id = id; this.rating = rating;
            this.votes = votes; this.subtitle = subtitle; this.noRating = noRating;
        }

        /** 「中国大陆 / 剧情 犯罪 / 徐纪周 / 张译 张颂文」: region, genres, directors, leads (a year first is dropped). */
        List<String> parts() {
            List<String> p = new ArrayList<>(Arrays.asList(subtitle.split("\\s*/\\s*")));
            if (!p.isEmpty() && p.get(0).matches("\\d{4}")) p.remove(0);
            return p;
        }

        List<String> genres() { List<String> p = parts(); return p.size() >= 2 ? Arrays.asList(p.get(1).trim().split("\\s+")) : new ArrayList<String>(); }

        /** 电影, 电视剧, or what it is to the user: 综艺 for a reality show, 动画, 动画电影, 纪录片. */
        String what() {
            List<String> g = genres();
            if (g.contains("真人秀")) return "综艺";
            if (g.contains("纪录片")) return "纪录片";
            if (g.contains("动画")) return movie ? "动画电影" : "动画";
            return kind.isEmpty() ? (movie ? "电影" : "电视剧") : kind;
        }
    }

    public static List<Title> titles(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (!o.optString("banned").isEmpty()) throw new JSONException("豆瓣暂时不让查：" + o.optString("banned"));
        JSONArray items = o.optJSONArray("items");
        List<Title> out = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i), t = it == null ? null : it.optJSONObject("target");
            if (t == null || !"subject".equals(it.optString("layout", "subject"))) continue;
            JSONObject r = t.optJSONObject("rating");
            out.add(new Title(clean(News.str(t, "title")), News.str(t, "year"), News.str(it, "type_name"), "movie".equals(News.str(it, "target_type")),
                    News.str(t, "id"), r == null ? 0 : r.optDouble("value", 0), r == null ? 0 : r.optLong("count"), News.str(t, "card_subtitle"),
                    News.str(t, "null_rating_reason")));
        }
        return out;
    }

    private static String norm(String s) { return BiliVideos.norm(s); }

    /** Douban writes some titles with a direction mark after them (「神探之痕迹」 + U+200E): not part of the name. */
    static String clean(String title) { return title == null ? "" : title.replaceAll("[\\u200B-\\u200F\\uFEFF]", "").trim(); }

    /** The title asked for: the same name (the year said, else the most rated of them), else the search's first. */
    public static Title pick(List<Title> all, String asked, int year) {
        String want = norm(asked);
        Title best = null;
        for (Title t : all) {
            if (!norm(t.name).equals(want)) continue;
            if (year > 0 && t.year.equals(String.valueOf(year))) return t;
            if (best == null || t.votes > best.votes) best = t;
        }
        if (best != null) return best;
        if (year > 0) for (Title t : all) if (t.year.equals(String.valueOf(year))) return t;
        return all.isEmpty() ? null : all.get(0);
    }

    /** 1074807 → 「107 万」, 4610 → 「4610」. */
    private static String votes(long n) { return n >= 10_000 ? (n / 10_000) + " 万" : String.valueOf(n); }

    private static String rating(Title t) {
        if (t.rating <= 0) return "豆瓣还没有评分" + (t.noRating.isEmpty() || "暂无评分".equals(t.noRating) ? "" : "：" + t.noRating);
        return "豆瓣 " + String.format(java.util.Locale.ROOT, "%.1f", t.rating) + " 分，" + votes(t.votes) + "人评价";
    }

    private static List<String> names(String s, int n) {
        List<String> out = new ArrayList<>();
        for (String x : s.trim().split("\\s+")) if (!x.isEmpty() && out.size() < n) out.add(x);
        return out;
    }

    /**
     * 「《狂飙》（2023 年的电视剧）豆瓣 8.5 分，107 万人评价；中国大陆，剧情、犯罪，导演徐纪周，主演张译、张颂文」. When nothing had
     * the very name, the search's first, and others of the same name (seasons) after it: 「…；还有第一季 8.9 分、第二季 8.9 分」.
     */
    public static String speak(List<Title> all, String asked, int year) {
        Title t = pick(all, asked, year);
        if (t == null) return "豆瓣上没找到「" + asked + "」";
        StringBuilder s = new StringBuilder("《").append(t.name).append("》（").append(t.year.isEmpty() ? "" : t.year + " 年的").append(t.what()).append("）")
                .append(rating(t));
        List<String> p = t.parts();
        List<String> about = new ArrayList<>();
        if (!p.isEmpty() && !p.get(0).trim().isEmpty()) about.add(String.join("、", names(p.get(0), 2)));
        if (p.size() >= 2) about.add(String.join("、", names(p.get(1), 3)));
        if (p.size() >= 4) {
            String what = t.what(), cast = "综艺".equals(what) ? "嘉宾" : what.startsWith("动画") ? "配音" : "纪录片".equals(what) ? "出镜" : "主演";
            about.add("导演" + String.join("、", names(p.get(2), 2)));
            about.add(cast + String.join("、", names(p.get(3), 2)));
        }
        if (!about.isEmpty()) s.append("；").append(String.join("，", about));
        if (!norm(t.name).equals(norm(asked))) {
            List<String> more = new ArrayList<>();
            for (Title o : all) {
                if (o == t || more.size() >= 2 || !norm(o.name).startsWith(norm(asked)) || o.rating <= 0) continue;
                // a season by its number (「第一季 8.9 分」), anything else by its name
                String rest = o.name.startsWith(asked) ? o.name.substring(asked.length()).trim() : "";
                more.add((rest.startsWith("第") ? rest : "《" + o.name + "》") + " " + String.format(java.util.Locale.ROOT, "%.1f", o.rating) + " 分");
            }
            if (!more.isEmpty()) s.append("；还有").append(String.join("、", more));
        }
        return s.toString();
    }

    /** 「狂飙在哪个平台能看」「繁花有多少集」 (2026-11-07): a title's page, with where it streams and how many episodes. */
    public static String detailUrl(Title t) { return "https://m.douban.com/rexxar/api/v2/" + (t.movie ? "movie/" : "tv/") + t.id + "?ck=&for_mobile=1"; }

    public static final class Vendor {
        public final String name, pay, link;

        Vendor(String name, String pay, String link) { this.name = name; this.pay = pay; this.link = link; }
    }

    public static final class Detail {
        public final int episodes;
        public final String duration;
        public final List<Vendor> vendors;

        Detail(int episodes, String duration, List<Vendor> vendors) { this.episodes = episodes; this.duration = duration; this.vendors = vendors; }
    }

    public static Detail detail(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (!o.has("title") && !o.has("vendors")) throw new JSONException("豆瓣的条目没有返回：" + o.optString("msg"));
        List<Vendor> vs = new ArrayList<>();
        JSONArray v = o.optJSONArray("vendors");
        for (int i = 0; v != null && i < v.length(); i++) {
            JSONObject x = v.getJSONObject(i);
            JSONArray pay = x.optJSONArray("payments");
            String how = pay == null || pay.length() == 0 ? "" : pay.getJSONObject(0).optString("method");
            String price = pay == null || pay.length() == 0 ? "" : pay.getJSONObject(0).optString("price");
            // B站's own link opens the season in the app on this TV (its query is a tracking blob)
            String uri = x.optString("uri"), link = uri.startsWith("bilibili://") ? uri.replaceFirst("[?#].*$", "") : "";
            if (!x.optString("title").isEmpty()) vs.add(new Vendor(x.optString("title"), how + (price.isEmpty() ? "" : " " + price), link));
        }
        JSONArray d = o.optJSONArray("durations");
        return new Detail(o.optInt("episodes_count"), d == null || d.length() == 0 ? "" : d.optString(0), vs);
    }

    /** What each platform is on this TV: 云视听极光 for 腾讯视频, B站 itself, 银河奇异果 for 爱奇艺 (from the store). */
    public static String onTv(String vendor) {
        if (vendor.contains("腾讯")) return "电视上用云视听极光（tencent_video_open，搜一下用 tencent_video_search）";
        if (vendor.contains("哔哩") || vendor.contains("B站")) return "电视上用 open_link 打开它的 link";
        if (vendor.contains("爱奇艺")) return "电视上是银河奇异果（小米应用商店里有）";
        if (vendor.contains("优酷")) return "电视上是 CIBN 酷喵（小米应用商店里有）";
        if (vendor.contains("芒果")) return "电视上是芒果TV电视版（小米应用商店里有）";
        return "";
    }

    /**
     * 「《狂飙》（2023 年的电视剧，豆瓣 8.5 分）可以在爱奇艺看（VIP免费观看），共 39 集」; several the same way: 「…可以在腾讯视频、
     * 咪咕视频、爱奇艺、哔哩哔哩、优酷视频看（都是VIP免费观看）」; none listed: 「…豆瓣上没有列出能在哪看」.
     */
    public static String speakWhere(Title t, Detail d) {
        StringBuilder s = new StringBuilder("《").append(t.name).append("》（").append(t.year.isEmpty() ? "" : t.year + " 年的").append(t.what())
                .append(t.rating > 0 ? "，豆瓣 " + String.format(java.util.Locale.ROOT, "%.1f", t.rating) + " 分" : "").append("）");
        if (d.vendors.isEmpty()) s.append("豆瓣上没有列出能在哪看");
        else {
            java.util.Set<String> pays = new java.util.LinkedHashSet<>();
            for (Vendor v : d.vendors) pays.add(v.pay);
            List<String> names = new ArrayList<>();
            for (Vendor v : d.vendors) names.add(pays.size() == 1 || v.pay.isEmpty() ? v.name : v.name + "（" + v.pay + "）");
            s.append("可以在").append(String.join("、", names)).append("看");
            String only = pays.iterator().next();
            if (pays.size() == 1 && !only.isEmpty()) s.append(d.vendors.size() > 1 ? "（都是" + only + "）" : "（" + only + "）");
        }
        if (d.episodes > 0) s.append("，共 ").append(d.episodes).append(" 集");
        else if (t.movie && !d.duration.isEmpty()) s.append("，片长 ").append(d.duration.replaceAll("([0-9]+)分钟", "$1 分钟"));
        return s.toString();
    }

    /** 「在哪个平台能看」「几集」 ask for the page. */
    public static final java.util.regex.Pattern WHERE = java.util.regex.Pattern.compile("哪个平台|什么平台|在哪看|在哪能看|哪里能看|哪里看|在哪里看|哪能看|在哪儿看|哪个软件|哪个app|哪个APP|多少集|几集|一共几集|总共几集|片长|多长时间|多久");

    /** 「最近有什么电影上映」「有什么好看的剧」 (2026-11-07): Douban's lists, by what people call them. */
    private static final String[][] LISTS = {{"电影", "movie_showing", "正在上映的电影"}, {"剧集", "tv_hot", "近期热门剧集"},
            {"国产剧", "tv_domestic", "近期热门国产剧"}, {"美剧", "tv_american", "近期热门美剧"}, {"韩剧", "tv_korean", "近期热门韩剧"},
            {"日剧", "tv_japanese", "近期热门日剧"}, {"动画", "tv_animation", "近期热门动画"}, {"综艺", "show_hot", "近期热门综艺"}};

    /** {collection, name} for a list asked for (电影 when unknown). */
    public static String[] list(String asked) {
        String a = asked == null ? "" : asked;
        for (String[] l : LISTS) if (a.contains(l[0])) return new String[]{l[1], l[2]};
        if (a.contains("电视剧") || a.contains("剧")) return new String[]{"tv_hot", "近期热门剧集"};
        if (a.contains("动漫") || a.contains("番")) return new String[]{"tv_animation", "近期热门动画"};
        return new String[]{"movie_showing", "正在上映的电影"};
    }

    public static String listUrl(String collection, int count) {
        return "https://m.douban.com/rexxar/api/v2/subject_collection/" + collection + "/items?start=0&count=" + count;
    }

    public static final class Item {
        public final String name, year, release, cast;
        public final double rating;
        public final long votes;

        Item(String name, String year, String release, String cast, double rating, long votes) {
            this.name = name; this.year = year; this.release = release; this.cast = cast; this.rating = rating; this.votes = votes;
        }
    }

    public static List<Item> items(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        JSONArray list = o.optJSONArray("subject_collection_items");
        if (list == null) throw new JSONException("豆瓣的榜单没有返回结果");
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject t = list.getJSONObject(i), r = t.optJSONObject("rating");
            List<String> cast = new ArrayList<>();
            JSONArray actors = t.optJSONArray("actors");
            for (int k = 0; actors != null && k < actors.length() && cast.size() < 2; k++) {
                Object x = actors.opt(k);
                String n = x instanceof JSONObject ? ((JSONObject) x).optString("name") : String.valueOf(x);
                if (!n.isEmpty()) cast.add(n);
            }
            if (cast.isEmpty()) {
                // the lists carry no actors: 「2026 / 中国大陆 / 剧情 / 张晓波 / 肖战 倪大红」 ends with the leads (a show's three
                // parts with its hosts)
                List<String> parts = new ArrayList<>(Arrays.asList(News.str(t, "card_subtitle").split("\\s*/\\s*")));
                if (!parts.isEmpty() && parts.get(0).matches("[0-9]{4}")) parts.remove(0);
                if (parts.size() >= 3) cast.addAll(names(parts.get(parts.size() >= 4 ? 3 : 2), 2));
            }
            out.add(new Item(clean(News.str(t, "title")), News.str(t, "year"), News.str(t, "release_date"), String.join("、", cast),
                    r == null ? 0 : r.optDouble("value", 0), r == null ? 0 : r.optLong("count")));
        }
        return out;
    }

    /** 「11.07」 → days from now (Beijing), Integer.MIN_VALUE when not a date. */
    static int daysSince(String release, long now) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([0-9]{1,2})[.-]([0-9]{1,2})$").matcher(release == null ? "" : release.trim());
        if (!m.matches()) return Integer.MIN_VALUE;
        java.util.Calendar c = java.util.Calendar.getInstance(Sports.BEIJING), t = java.util.Calendar.getInstance(Sports.BEIJING);
        t.setTimeInMillis(now);
        c.clear();
        c.set(t.get(java.util.Calendar.YEAR), Integer.parseInt(m.group(1)) - 1, Integer.parseInt(m.group(2)));
        long d = Math.floorDiv(now - c.getTimeInMillis(), 86_400_000L);
        if (d < -180) d += 365; // 12.28 seen in January is last year's
        if (d > 180) d -= 365;  // 01.01 seen on 12.30 is next year's (2026-11-07 review)
        return (int) d;
    }

    private static String score(double r) { return r > 0 ? String.format(java.util.Locale.ROOT, "%.1f", r) + " 分" : "还没有评分"; }

    /**
     * Films on now: those out within three months and made this year or last (not 1994's 肖申克 back on screens), most
     * rated first, then the week's new ones: 「正在上映的热门电影：《欢迎来龙餐馆》8.7 分；…；这几天新上映：《我们仅有一次的人生》（今天）…」.
     * Series and shows: the list's own order (Douban's heat): 「近期热门剧集：第一，《小城良方》8.0 分，肖战、倪大红；…」.
     */
    public static String speakList(String collection, String name, List<Item> items, int n, long now) {
        if (items.isEmpty()) return "豆瓣的" + name + "这会儿是空的";
        String[] ord = {"第一", "第二", "第三", "第四", "第五", "第六", "第七", "第八"};
        int k = Math.max(1, Math.min(n, ord.length));
        if (!"movie_showing".equals(collection)) {
            StringBuilder s = new StringBuilder(name + "：");
            for (int i = 0; i < Math.min(k, items.size()); i++) {
                Item x = items.get(i);
                s.append(i == 0 ? "" : "；").append(ord[i]).append("，《").append(x.name).append("》").append(score(x.rating))
                        .append(x.cast.isEmpty() ? "" : "，" + x.cast);
            }
            return s.toString();
        }
        java.util.Calendar c = java.util.Calendar.getInstance(Sports.BEIJING);
        c.setTimeInMillis(now);
        int year = c.get(java.util.Calendar.YEAR);
        List<Item> hot = new ArrayList<>(), fresh = new ArrayList<>();
        for (Item x : items) {
            int since = daysSince(x.release, now);
            boolean recent = since != Integer.MIN_VALUE && since <= 90 && (x.year.isEmpty() || x.year.compareTo(String.valueOf(year - 1)) >= 0);
            if (recent && since >= -2 && since <= 6) fresh.add(x);
            else if (recent && x.votes > 0) hot.add(x);
        }
        hot.sort((p, q) -> Long.compare(q.votes, p.votes));
        StringBuilder s = new StringBuilder("正在上映的热门电影：");
        for (int i = 0; i < Math.min(k, hot.size()); i++) s.append(i == 0 ? "" : "；").append("《").append(hot.get(i).name).append("》").append(score(hot.get(i).rating));
        if (hot.isEmpty()) s.setLength(0);
        if (!fresh.isEmpty()) {
            s.append(s.length() == 0 ? "" : "；").append("这几天新上映：");
            for (int i = 0; i < Math.min(4, fresh.size()); i++) {
                Item x = fresh.get(i);
                int since = daysSince(x.release, now);
                s.append(i == 0 ? "" : "、").append("《").append(x.name).append("》（").append(since == 0 ? "今天" : since == 1 ? "昨天" : since == -1 ? "明天" : since < 0 ? "后天" : since + " 天前").append("）");
            }
        }
        return s.length() == 0 ? "豆瓣上这会儿没有正在上映的新片" : s.toString();
    }

    /** The candidates for the model: up to five, each with its year, kind and rating. */
    public static JSONArray rows(List<Title> all) throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(5, all.size()); i++) {
            Title t = all.get(i);
            out.put(new JSONObject().put("title", t.name).put("year", t.year).put("kind", t.what()).put("rating", t.rating).put("votes", t.votes)
                    .put("about", t.subtitle));
        }
        return out;
    }
}
