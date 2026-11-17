package xyz.erapi0neer.xingyi.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * 「放星辰亻旅行者最新的视频」「影视飓风播放量最高的是哪个」 (2026-11-07): B站's own search, read without the app. Clicked
 * through the app these took 16-21 tool calls (evidence/bili-latest-2026-11-04, bili-mostplayed-2026-11-05: the search
 * page, the UP card, scrolling the uploads); with each video's own link it is one search and an open_link.
 * An UP主 is found by the user search, whose card carries their three newest uploads; more of theirs, or their most
 * played, come from the video search on their name, kept to their own uploads: other people's videos mention a big
 * name (for 影视飓风 one of the twenty newest was theirs). The search wants B站's visitor id (Feeds).
 */
public final class BiliVideos {
    private BiliVideos() { }

    private static final String SEARCH = "https://api.bilibili.com/x/web-interface/search/type?page=1&search_type=";

    /** order: latest, most_played or relevance (the search's pubdate, click and its default ranking). */
    public static String videoUrl(String keyword, String order) {
        String o = "most_played".equals(order) ? "&order=click" : "latest".equals(order) ? "&order=pubdate" : "";
        return SEARCH + "video&keyword=" + encode(keyword) + o;
    }

    public static String userUrl(String name) { return SEARCH + "bili_user&keyword=" + encode(name); }

    private static String encode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }

    public static final class Video {
        public final String title, up, bvid, duration, tags;
        public final long aid, mid, views, pubdate;

        Video(String title, String up, long mid, long aid, String bvid, long views, long pubdate, String duration, String tags) {
            this.title = title; this.up = up; this.mid = mid; this.aid = aid; this.bvid = bvid; this.views = views;
            this.pubdate = pubdate; this.duration = duration; this.tags = tags;
        }

        /** The app's own link, as HotList's: bilibili://video/<aid> opens the video's page and plays it. */
        public String link() { return "bilibili://video/" + (aid > 0 ? String.valueOf(aid) : bvid); }
    }

    public static final class User {
        public final String name;
        public final long mid, fans, room;
        public final int count;
        /** Their newest uploads, newest first (the card's three). */
        public final List<Video> recent;
        /** Live now (「影视飓风在直播吗」), in their room. */
        public final boolean live;

        User(String name, long mid, long fans, int count, List<Video> recent) { this(name, mid, fans, count, recent, false, 0); }

        User(String name, long mid, long fans, int count, List<Video> recent, boolean live, long room) {
            this.name = name; this.mid = mid; this.fans = fans; this.count = count; this.recent = recent; this.live = live; this.room = room;
        }

        /** Their page in the app (「看看我的频道」): bilibili://space/<mid>. */
        public String space() { return "bilibili://space/" + mid; }

        /** Their live room in the app, or null without one. */
        public String liveLink() { return room > 0 ? "bilibili://live/" + room : null; }
    }

    private static JSONArray result(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (o.optInt("code", -1) != 0) throw new JSONException("B站搜索没有返回结果：" + o.optString("message"));
        JSONObject data = o.optJSONObject("data");
        JSONArray r = data == null ? null : data.optJSONArray("result");
        return r == null ? new JSONArray() : r;
    }

    static List<Video> videos(String json) throws JSONException {
        JSONArray r = result(json);
        List<Video> out = new ArrayList<>();
        for (int i = 0; i < r.length(); i++) {
            JSONObject v = r.optJSONObject(i);
            if (v == null || !"video".equals(v.optString("type", "video"))) continue;
            out.add(new Video(News.text(News.str(v, "title")), News.str(v, "author"), v.optLong("mid"), v.optLong("aid"), News.str(v, "bvid"),
                    v.optLong("play"), v.optLong("pubdate"), clock(News.str(v, "duration")), News.str(v, "tag")));
        }
        return out;
    }

    static List<User> users(String json) throws JSONException {
        JSONArray r = result(json);
        List<User> out = new ArrayList<>();
        for (int i = 0; i < r.length(); i++) {
            JSONObject u = r.optJSONObject(i);
            if (u == null) continue;
            String name = u.optString("uname");
            List<Video> recent = new ArrayList<>();
            JSONArray res = u.optJSONArray("res");
            for (int k = 0; res != null && k < res.length(); k++) {
                JSONObject v = res.optJSONObject(k);
                if (v == null) continue;
                recent.add(new Video(News.text(v.optString("title")), name, u.optLong("mid"), v.optLong("aid"), v.optString("bvid"),
                        v.optLong("play"), v.optLong("pubdate"), clock(v.optString("duration")), ""));
            }
            sort(recent, "latest");
            out.add(new User(name, u.optLong("mid"), u.optLong("fans"), u.optInt("videos"), recent, u.optInt("is_live") == 1, u.optLong("room_id")));
        }
        return out;
    }

    /** "3:5" (the search's minutes:seconds) → "3:05". */
    private static String clock(String d) {
        int colon = d.lastIndexOf(':');
        return colon >= 0 && d.length() - colon == 2 ? d.substring(0, colon + 1) + "0" + d.substring(colon + 1) : d;
    }

    /** Letters and digits, lower case: 「示例小号」 is 「示例小号」. */
    static String norm(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    /** The UP主 a name means: the same name; else one whose name holds it (何同学 is 老师好我叫何同学, the search's first);
     * else one a character or so off (a misheard word). Null when none is: better said than a stranger's videos played. */
    static User pickUser(List<User> users, String name) {
        String want = norm(name);
        if (want.isEmpty()) return null;
        for (User u : users) if (norm(u.name).equals(want)) return u;
        if (want.length() >= 2) for (User u : users) if (norm(u.name).contains(want)) return u;
        int limit = Math.max(1, want.length() / 4);
        for (User u : users) if (Grounding.distance(norm(u.name), want, limit) <= limit) return u;
        return null;
    }

    /** What was found: the UP主 (null for a topic, or when no UP主 had that name), their videos or the topic's, in order. */
    public static final class Found {
        public final User user;
        public final List<Video> videos;
        public final String asked, keyword, order;

        Found(User user, List<Video> videos, String asked, String keyword, String order) {
            this.user = user; this.videos = videos; this.asked = asked; this.keyword = keyword; this.order = order;
        }
    }

    /**
     * up: an UP主's name (their own uploads); keyword: words of the videos (with up: among theirs, by title or tag, as
     * 「他的MAD」); order: latest (default with up), most_played, relevance (default without). n: how many are wanted.
     */
    public static Found find(Feeds.Fetch f, String up, String keyword, String order, int n) throws IOException, JSONException {
        String who = up == null ? "" : up.trim(), kw = keyword == null ? "" : keyword.trim();
        String o = order(who, order);
        if (who.isEmpty()) {
            List<Video> found = videos(f.get(videoUrl(kw, o), "UTF-8", 8000));
            // the keyword is an UP主's own name (「影视飓风最新的视频」 with the name as a keyword): their uploads, not others'
            // videos about them
            // the order asked for, not the topic's default: an UP's own default is their newest (2026-11-07 review)
            for (Video v : found) if (!norm(kw).isEmpty() && norm(v.up).equals(norm(kw))) return find(f, kw, "", order, n);
            return new Found(null, found, "", kw, o);
        }
        User u = pickUser(users(f.get(userUrl(who), "UTF-8", 8000)), who);
        if (u == null) return new Found(null, Collections.<Video>emptyList(), who, kw, o);
        List<Video> recent = new ArrayList<>(), mine = new ArrayList<>();
        for (Video v : u.recent) if (kw.isEmpty() || has(v, kw)) recent.add(v);
        // the card's newest answer 「最新的」 by themselves, without a second request
        if (!"latest".equals(o) || !kw.isEmpty() || recent.size() < Math.min(n, u.count)) {
            for (Video v : videos(f.get(videoUrl(u.name, o), "UTF-8", 8000))) if (v.mid == u.mid && (kw.isEmpty() || has(v, kw))) add(mine, v);
            // a big UP's twenty on their name may miss the word: their name and the word together
            if (mine.isEmpty() && recent.isEmpty() && !kw.isEmpty())
                for (Video v : videos(f.get(videoUrl(u.name + " " + kw, o), "UTF-8", 8000))) if (v.mid == u.mid) add(mine, v);
        }
        for (Video v : recent) add(mine, v);
        sort(mine, o);
        return new Found(u, mine, who, kw, o);
    }

    private static boolean has(Video v, String keyword) {
        String k = norm(keyword);
        return !k.isEmpty() && (norm(v.title).contains(k) || norm(v.tags).contains(k));
    }

    private static void add(List<Video> list, Video v) {
        for (Video x : list) if (x.aid == v.aid && (v.aid > 0 || x.bvid.equals(v.bvid))) return;
        list.add(v);
    }

    /** relevance keeps the search's own order. */
    private static void sort(List<Video> list, String order) {
        if ("latest".equals(order)) Collections.sort(list, (a, b) -> Long.compare(b.pubdate, a.pubdate));
        else if ("most_played".equals(order)) Collections.sort(list, (a, b) -> Long.compare(b.views, a.views));
    }

    private static final String[] ORDINAL = {"第一", "第二", "第三", "第四", "第五", "第六", "第七", "第八", "第九", "第十"};

    /** 「影视飓风最新的 3 个视频」, 「星辰亻旅行者播放最多的视频」, 「B站上「黑神话」相关的 3 个视频」. */
    static String what(Found f, int k) {
        String count = k > 1 ? " " + k + " 个" : "";
        if (f.user != null) {
            String how = "most_played".equals(f.order) ? "播放最多的" : "latest".equals(f.order) ? "最新的" : "的";
            return f.user.name + how + count + f.keyword + "视频";
        }
        String how = "most_played".equals(f.order) ? "播放最多的" : "latest".equals(f.order) ? "最新的" : "相关的";
        return "B站上「" + f.keyword + "」" + how + count + "视频";
    }

    /**
     * 「影视飓风最新的 3 个视频：第一，《动态视频｜2026，它来了…》，昨天，13 万播放；第二，…」; one: 「星辰亻旅行者播放最多的视频是
     * 《…》，125 万播放，2019 年 12 月 4 日发的」. A topic's videos say whose they are.
     */
    public static String speak(Found f, int n, long now) {
        String live = f.user != null && f.user.live ? f.user.name + "现在正在直播。" : "";
        return live + list(f, n, now);
    }

    /** 「影视飓风在直播吗」 asks about one thing (2026-11-07 calibration: the list read out did not say). */
    public static final java.util.regex.Pattern LIVE_ASKED = java.util.regex.Pattern.compile("在直播|直播吗|直播没|开播|在播吗|播了吗|直播了吗");

    /**
     * The name to say for an UP主: the one the user said when it is part of the channel's name and shorter (2026-11-10
     * sweep: 「老师好我叫何同学现在没在直播」 reads as 「老师好，我叫何同学…」). Otherwise the channel's own name.
     */
    static String saidName(Found f) {
        if (f.user == null) return "";
        String asked = f.asked == null ? "" : f.asked.trim(), a = norm(asked), n = norm(f.user.name);
        return !a.isEmpty() && a.length() < n.length() && n.contains(a) ? asked : f.user.name;
    }

    /** 「何同学现在没在直播；最新的视频是昨天发的《…》」, 「…正在直播」. */
    public static String speakLive(Found f, long now) {
        if (f.user == null) return speak(f, 1, now);
        String who = saidName(f);
        if (f.user.live) return who + "正在直播";
        if (f.videos.isEmpty()) return who + "现在没在直播";
        Video v = f.videos.get(0);
        String t = cut(v.title);
        return who + "现在没在直播；最新的视频是" + when(v.pubdate, now) + "发的" + (t.contains("《") || t.contains("》") ? t : "《" + t + "》");
    }

    private static String list(Found f, int n, long now) {
        if (f.videos.isEmpty()) {
            if (f.user == null && !f.asked.isEmpty()) return "B站上没找到叫「" + f.asked + "」的 UP 主";
            if (f.user != null) return f.keyword.isEmpty() ? "UP 主「" + f.user.name + "」还没有投稿视频" : f.user.name + "的视频里没找到「" + f.keyword + "」";
            return "B站上没搜到「" + f.keyword + "」的视频";
        }
        int k = Math.max(1, Math.min(n, Math.min(f.videos.size(), ORDINAL.length)));
        if (k == 1) return what(f, 1) + "是" + item(f, f.videos.get(0), now, true);
        StringBuilder s = new StringBuilder(what(f, k)).append("：");
        for (int i = 0; i < k; i++) s.append(i == 0 ? "" : "；").append(ORDINAL[i]).append("，").append(item(f, f.videos.get(i), now, false));
        return s.toString();
    }

    /** A long title cut for speech at 36 characters, not inside an English word. */
    static String cut(String t) {
        if (t.length() <= 36) return t;
        int end = 35;
        if (latin(t.charAt(end - 1)) && latin(t.charAt(end))) {
            int space = t.lastIndexOf(' ', end);
            if (space > 20) end = space;
        }
        return t.substring(0, end).trim() + "…";
    }

    private static boolean latin(char c) { return c < 128 && Character.isLetterOrDigit(c); }

    private static String item(Found f, Video v, long now, boolean one) {
        String t = cut(v.title);
        StringBuilder s = new StringBuilder(t.contains("《") || t.contains("》") ? t : "《" + t + "》");
        if (f.user == null && !v.up.isEmpty()) s.append("，").append(v.up);
        String date = when(v.pubdate, now) + (one ? "发的" : ""), views = HotList.count(v.views) + "播放";
        if ("most_played".equals(f.order)) s.append("，").append(views).append("，").append(date);
        else s.append("，").append(date).append("，").append(views);
        return s.toString();
    }

    private static long day(long ms) { return Math.floorDiv(ms + 8 * 3_600_000L, 86_400_000L); }

    /** A publishing time (seconds) in Beijing's words: 今天, 昨天, 前天, 「11 月 5 日」, an earlier year with its year. */
    static String when(long pubdate, long now) {
        long days = day(now) - day(pubdate * 1000);
        if (days == 0) return "今天";
        if (days == 1) return "昨天";
        if (days == 2) return "前天";
        Calendar c = Calendar.getInstance(Sports.BEIJING), t = Calendar.getInstance(Sports.BEIJING);
        c.setTimeInMillis(pubdate * 1000);
        t.setTimeInMillis(now);
        String md = (c.get(Calendar.MONTH) + 1) + " 月 " + c.get(Calendar.DAY_OF_MONTH) + " 日";
        return c.get(Calendar.YEAR) == t.get(Calendar.YEAR) ? md : c.get(Calendar.YEAR) + " 年 " + md;
    }

    /** The videos for the model, each with its link: up to ten. */
    public static JSONArray rows(Found f, long now) throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(10, f.videos.size()); i++) {
            Video v = f.videos.get(i);
            out.put(new JSONObject().put("n", i + 1).put("title", v.title).put("up", v.up).put("date", when(v.pubdate, now))
                    .put("views", v.views).put("duration", v.duration).put("link", v.link()));
        }
        return out;
    }

    /** What was read out, numbered as it was read, for environment.last_list: 「放第二个」 is its second. */
    public static JSONObject readOut(Found f, int n, long now) throws JSONException {
        JSONArray items = new JSONArray();
        int k = Math.min(Math.min(n, ORDINAL.length), f.videos.size());
        for (int i = 0; i < k; i++) {
            Video v = f.videos.get(i);
            items.put(new JSONObject().put("n", i + 1).put("title", v.title).put("up", v.up).put("date", when(v.pubdate, now)).put("link", v.link()));
        }
        return new JSONObject().put("what", what(f, 0)).put("items", items)
                .put("note", "刚读给用户的B站视频：用户说“放第二个”“看第一个”就是这里的编号，用 open_link 打开它的 link");
    }

    /** For the HUD and the plan: 「查B站上影视飓风最新的视频」. */
    public static String describe(String up, String keyword, String order) {
        String who = up == null ? "" : up.trim(), kw = keyword == null ? "" : keyword.trim(), o = order(who, order);
        String how = "most_played".equals(o) ? "播放最多的" : "latest".equals(o) ? "最新的" : "的";
        return who.isEmpty() ? "查B站上「" + kw + "」" + how + "视频" : "查B站上" + who + how + kw + "视频";
    }

    /** The order asked for; none: an UP主's newest, a topic's best match. */
    static String order(String up, String order) {
        if ("latest".equals(order) || "most_played".equals(order) || "relevance".equals(order)) return order;
        return up == null || up.trim().isEmpty() ? "relevance" : "latest";
    }
}
