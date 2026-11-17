package xyz.erapi0neer.xingyi.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 「林俊杰最近发新歌了吗」「周杰伦最新的歌是哪首」 (2026-11-07): a singer's newest releases from NetEase Cloud Music (the
 * singer by its search, their albums newest first, without a key; the TV's NetEase app plays them). A web search took
 * 10 s in the TV's sweep for it, and the calibration's 「播放林俊杰最新发表的一个单曲」 searched before it played.
 */
public final class MusicNew {
    private MusicNew() { }

    public static String artistUrl(String name) {
        try { return "https://music.163.com/api/search/get/web?type=100&limit=5&s=" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String albumsUrl(long id) { return "https://music.163.com/api/artist/albums/" + id + "?offset=0&limit=8"; }

    /** {id, name}: the same name or alias first, else one holding it, else the search's first; null when none. */
    public static Object[] artist(String json, String asked) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (o.optInt("code") != 200) throw new JSONException("网易云的歌手搜索没有返回结果");
        JSONObject r = o.optJSONObject("result");
        JSONArray list = r == null ? null : r.optJSONArray("artists");
        if (list == null || list.length() == 0) return null;
        String want = BiliVideos.norm(asked);
        for (int i = 0; i < list.length(); i++) {
            JSONObject a = list.getJSONObject(i);
            if (BiliVideos.norm(a.optString("name")).equals(want)) return new Object[]{a.getLong("id"), a.optString("name")};
            JSONArray alias = a.optJSONArray("alias");
            for (int k = 0; alias != null && k < alias.length(); k++)
                if (BiliVideos.norm(alias.optString(k)).equals(want)) return new Object[]{a.getLong("id"), a.optString("name")};
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject a = list.getJSONObject(i);
            if (want.length() >= 2 && BiliVideos.norm(a.optString("name")).contains(want)) return new Object[]{a.getLong("id"), a.optString("name")};
        }
        JSONObject first = list.getJSONObject(0);
        return new Object[]{first.getLong("id"), first.optString("name")};
    }

    public static final class Release {
        public final String name, kind;
        public final int size;
        public final long at;

        Release(String name, String kind, int size, long at) { this.name = name; this.kind = kind; this.size = size; this.at = at; }
    }

    /** Newest first; compilations (合集) and live recordings are not new songs and are left out. */
    public static List<Release> releases(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (o.optInt("code") != 200) throw new JSONException("网易云的专辑列表没有返回结果");
        JSONArray list = o.optJSONArray("hotAlbums");
        List<Release> out = new ArrayList<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject a = list.getJSONObject(i);
            String type = News.str(a, "type"), sub = News.str(a, "subType");
            if (type.contains("合集") || sub.contains("现场")) continue;
            String kind = type.startsWith("Single") ? "单曲" : type.startsWith("EP") ? "EP" : "专辑";
            out.add(new Release(News.str(a, "name").trim(), kind, a.optInt("size"), a.optLong("publishTime")));
        }
        out.sort((x, y) -> Long.compare(y.at, x.at));
        return out;
    }

    private static long day(long ms) { return Math.floorDiv(ms + 8 * 3_600_000L, 86_400_000L); }

    static String when(long at, long now) {
        long d = day(now) - day(at);
        if (d == 0) return "今天";
        if (d == 1) return "昨天";
        if (d == 2) return "前天";
        Calendar c = Calendar.getInstance(Sports.BEIJING), t = Calendar.getInstance(Sports.BEIJING);
        c.setTimeInMillis(at);
        t.setTimeInMillis(now);
        String md = (c.get(Calendar.MONTH) + 1) + " 月 " + c.get(Calendar.DAY_OF_MONTH) + " 日";
        return c.get(Calendar.YEAR) == t.get(Calendar.YEAR) ? md : c.get(Calendar.YEAR) + " 年 " + md;
    }

    /** A date after a word: 「是 3 月 26 日」, with the space before the number the speech wants. */
    private static String after(long at, long now) {
        String w = when(at, now);
        return Character.isDigit(w.charAt(0)) ? " " + w : w;
    }

    private static String what(Release r) { return "专辑".equals(r.kind) || "EP".equals(r.kind) ? r.kind + "《" + r.name + "》（" + r.size + " 首）" : "单曲《" + r.name + "》"; }

    /**
     * 「林俊杰最新的是 10 月 10 日上线的单曲《Rise Without A Sound》，之前是 2 月 27 日的单曲《我对缘分小心翼翼》、…」; the newest within a
     * month is 「最近发了新歌」, older 「最近一次发歌是…，差不多 N 个月前」.
     */
    public static String speak(String artist, List<Release> rs, int n, long now) {
        if (rs.isEmpty()) return "网易云上没查到" + artist + "发过的歌";
        Release first = rs.get(0);
        long days = day(now) - day(first.at);
        StringBuilder s = new StringBuilder(artist);
        if (days <= 31) s.append("最近发了新歌：").append(when(first.at, now)).append("上线的").append(what(first));
        else s.append("最近一次发歌是").append(after(first.at, now)).append("的").append(what(first)).append("，差不多 ").append(Math.max(1, (int) (days / 30.4))).append(" 个月前");
        int k = Math.min(Math.max(1, n), rs.size());
        if (k > 1) {
            s.append("；之前是");
            for (int i = 1; i < k; i++) s.append(i == 1 ? "" : "、").append(i == 1 ? after(rs.get(i).at, now) : when(rs.get(i).at, now)).append("的").append(what(rs.get(i)));
        }
        return s.toString();
    }

    public static JSONArray rows(List<Release> rs, long now) throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(8, rs.size()); i++)
            out.put(new JSONObject().put("title", rs.get(i).name).put("kind", rs.get(i).kind).put("songs", rs.get(i).size).put("date", when(rs.get(i).at, now)));
        return out;
    }
}
