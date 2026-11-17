package xyz.erapi0neer.xingyi.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 「现在有什么热搜」「B站有什么热门视频」 (2026-11-06): Baidu's realtime board and B站's popular list, public and keyless
 * (Weibo's needs a login). */
public final class HotList {
    private HotList() { }

    public static final String BAIDU = "https://top.baidu.com/api/board?platform=wise&tab=realtime";
    public static final String BILI = "https://api.bilibili.com/x/web-interface/popular?ps=20&pn=1";

    /** The board's topics in order, without the pinned one (an editors' pick, not a search). */
    public static List<String> baidu(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (!o.optBoolean("success")) throw new JSONException("百度热搜没有返回结果");
        List<String> out = new ArrayList<>();
        JSONArray cards = o.getJSONObject("data").optJSONArray("cards");
        for (int i = 0; cards != null && i < cards.length(); i++) {
            JSONArray content = cards.getJSONObject(i).optJSONArray("content");
            for (int j = 0; content != null && j < content.length(); j++) {
                JSONObject block = content.optJSONObject(j);
                if (block == null) continue;
                JSONArray items = block.optJSONArray("content");
                if (items == null) items = new JSONArray().put(block);
                for (int k = 0; k < items.length(); k++) {
                    JSONObject it = items.optJSONObject(k);
                    if (it == null || it.optBoolean("isTop")) continue;
                    String w = (!News.str(it, "word").isEmpty() ? News.str(it, "word") : News.str(it, "query")).trim();
                    if (!w.isEmpty() && !out.contains(w)) out.add(w);
                }
            }
        }
        return out;
    }

    public static final class Video {
        public final String title, up, bvid;
        public final long views, aid;

        Video(String title, String up, String bvid, long views, long aid) {
            this.title = title; this.up = up; this.bvid = bvid; this.views = views; this.aid = aid;
        }

        /** The app's own link: bilibili://video/<aid>, the classic form (the BV form when there is no aid). */
        public String link() { return "bilibili://video/" + (aid > 0 ? String.valueOf(aid) : bvid); }

        public JSONObject toJson() throws JSONException {
            return new JSONObject().put("title", title).put("up", up).put("bvid", bvid).put("views", views).put("link", link());
        }
    }

    public static List<Video> bili(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (o.optInt("code", -1) != 0) throw new JSONException("B站热门没有返回结果：" + News.str(o, "message"));
        JSONArray list = o.getJSONObject("data").getJSONArray("list");
        List<Video> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject v = list.getJSONObject(i);
            JSONObject owner = v.optJSONObject("owner"), stat = v.optJSONObject("stat");
            out.add(new Video(News.str(v, "title").trim(), owner == null ? "" : News.str(owner, "name"), News.str(v, "bvid"),
                    stat == null ? 0 : stat.optLong("view"), v.optLong("aid")));
        }
        return out;
    }

    /** What was read out, numbered as it was read, for environment.last_list: 「放第二个」 is its second item. */
    public static JSONObject readOutVideos(List<Video> videos, int n) throws JSONException {
        JSONArray items = new JSONArray();
        for (int i = 0; i < Math.min(Math.min(n, ORDINAL.length), videos.size()); i++) {
            Video v = videos.get(i);
            items.put(new JSONObject().put("n", i + 1).put("title", v.title).put("up", v.up).put("link", v.link()));
        }
        return new JSONObject().put("what", "B站热门").put("items", items)
                .put("note", "刚读给用户的列表：用户说“第二个”“放第三个”就是这里的编号，用 open_link 打开它的 link");
    }

    public static JSONObject readOutTopics(List<String> words, int n) throws JSONException {
        JSONArray items = new JSONArray();
        for (int i = 0; i < Math.min(Math.min(n, ORDINAL.length), words.size()); i++) items.put(new JSONObject().put("n", i + 1).put("title", words.get(i)));
        return new JSONObject().put("what", "百度热搜").put("items", items)
                .put("note", "刚读给用户的列表：用户问“第二条是怎么回事”就是这里的编号，用 web_search 查那一条");
    }

    /** NetEase Cloud Music's charts: {what people say, playlist id, name}; 网易云 alone is the hot songs. */
    private static final String[][] CHARTS = {{"新歌", "3779629", "网易云新歌榜"}, {"飙升", "19723756", "网易云飙升榜"},
            {"原创", "2884035", "网易云原创榜"}, {"热歌", "3778678", "网易云热歌榜"}};

    /** {playlist id, name} when the source is a NetEase chart (网易云, 热歌榜, 新歌…), else null. */
    public static String[] chart(String source) {
        String s = source == null ? "" : source;
        for (String[] c : CHARTS) if (s.contains(c[0])) return new String[]{c[1], c[2]};
        return s.contains("网易") || s.contains("歌") || s.contains("音乐") ? new String[]{"3778678", "网易云热歌榜"} : null;
    }

    /** The chart's first n songs only (58 KB for ten, against 600 KB for the whole list). */
    public static String chartUrl(String id, int n) { return "https://music.163.com/api/v6/playlist/detail?id=" + id + "&n=" + n; }

    public static final class Song {
        public final String title, artist;

        Song(String title, String artist) { this.title = title; this.artist = artist; }
    }

    public static List<Song> chartSongs(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (o.optInt("code") != 200) throw new JSONException("网易云榜单没有返回结果");
        JSONArray tracks = o.getJSONObject("playlist").optJSONArray("tracks");
        List<Song> out = new ArrayList<>();
        for (int i = 0; tracks != null && i < tracks.length(); i++) {
            JSONObject t = tracks.getJSONObject(i);
            List<String> artists = new ArrayList<>();
            JSONArray ar = t.optJSONArray("ar");
            for (int k = 0; ar != null && k < ar.length(); k++) artists.add(ar.getJSONObject(k).optString("name"));
            out.add(new Song(News.str(t, "name").trim(), String.join("、", artists)));
        }
        return out;
    }

    /** 「网易云热歌榜前 5 首：第一，《我不难过》，孙燕姿；…」; one song is one sentence: 「网易云热歌榜第一是《…》，孙燕姿」
     * (2026-11-10 on the TV: 「前 1 首：第一，…」 read like a list of one). */
    public static String speakChart(String name, List<Song> songs, int n) {
        if (songs.isEmpty()) return name + "这会儿是空的";
        Song first = songs.get(0);
        if (n == 1) return name + "第一是《" + first.title + "》" + (first.artist.isEmpty() ? "" : "，" + first.artist);
        int k = Math.min(n, Math.min(songs.size(), ORDINAL.length));
        StringBuilder s = new StringBuilder(name + "前 " + k + " 首：");
        for (int i = 0; i < k; i++) {
            Song x = songs.get(i);
            s.append(i == 0 ? "" : "；").append(ORDINAL[i]).append("，《").append(x.title).append("》").append(x.artist.isEmpty() ? "" : "，" + x.artist);
        }
        return s.toString();
    }

    public static JSONObject readOutSongs(String name, List<Song> songs, int n) throws JSONException {
        JSONArray items = new JSONArray();
        for (int i = 0; i < Math.min(Math.min(n, ORDINAL.length), songs.size()); i++)
            items.put(new JSONObject().put("n", i + 1).put("title", songs.get(i).title).put("artist", songs.get(i).artist));
        return new JSONObject().put("what", name).put("items", items)
                .put("note", "刚读给用户的歌：用户说“放第一首”“放第三首”就用 netease_play 播那一首（title 填歌名，artist 填歌手）");
    }

    private static final String[] ORDINAL = {"第一", "第二", "第三", "第四", "第五", "第六", "第七", "第八", "第九", "第十"};

    /** 「百度热搜前 5 条：第一，美股暴跌 遭遇历史性抛售；第二，…」: ordinals, not 「1.」, which is read 「一点」. One topic is one
     * sentence: 「百度热搜第一是「…」」 (2026-11-10 on the TV: 「前 1 条：第一，…」 read like a list of one). */
    public static String speakBaidu(List<String> words, int n) {
        if (words.isEmpty()) return "百度热搜这会儿是空的";
        if (n == 1) return "百度热搜第一是「" + words.get(0) + "」";
        int k = Math.min(n, Math.min(words.size(), ORDINAL.length));
        StringBuilder s = new StringBuilder("百度热搜前 " + k + " 条：");
        for (int i = 0; i < k; i++) s.append(i == 0 ? "" : "；").append(ORDINAL[i]).append("，").append(words.get(i));
        return s.toString();
    }

    /** 「B站热门前 3 个：第一，《…》，影视飓风，68 万播放；…」; one video is one sentence: 「B站热门第一是《…》，影视飓风，68 万播放」. */
    public static String speakBili(List<Video> videos, int n) {
        if (videos.isEmpty()) return "B站热门这会儿是空的";
        if (n == 1) {
            Video v = videos.get(0);
            boolean titled = v.title.contains("《") || v.title.contains("》");
            return "B站热门第一是" + (titled ? v.title : "《" + v.title + "》") + (v.up.isEmpty() ? "" : "，" + v.up)
                    + "，" + count(v.views) + "播放";
        }
        int k = Math.min(n, Math.min(videos.size(), ORDINAL.length));
        StringBuilder s = new StringBuilder("B站热门前 " + k + " 个：");
        for (int i = 0; i < k; i++) {
            Video v = videos.get(i);
            boolean titled = v.title.contains("《") || v.title.contains("》");
            s.append(i == 0 ? "" : "；").append(ORDINAL[i]).append("，").append(titled ? v.title : "《" + v.title + "》");
            if (!v.up.isEmpty()) s.append("，").append(v.up);
            s.append("，").append(count(v.views)).append("播放");
        }
        return s.toString();
    }

    /** 4247433 → 「424 万」, 9800 → 「9800 次」, 1.2e8 → 「1.2 亿」. */
    static String count(long n) {
        return n >= 100_000_000 ? String.format(Locale.ROOT, "%.1f 亿", n / 1e8) : n >= 10_000 ? (n / 10_000) + " 万" : n + " 次";
    }
}
