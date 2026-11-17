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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「最近有什么新闻」「今天有什么国际新闻」「科技新闻」 (2026-11-06): the latest headlines from China News Service's RSS
 * feeds (中新网: 即时, 国内, 国际, 社会, 财经, 体育) and Sina Tech's roll; two days old at most. The headlines are read; a
 * story's detail is a web search on it. A web search for 「最近有什么科技新闻」 took 9-10 s.
 */
public final class News {
    private News() { }

    /** {what people say, the source, how it is said}. */
    private static final String[][] CATEGORIES = {
            // 2026-11-07: AI news is what the household asks most (the 30-day record: OpenAI, models, leaderboards); 量子位
            // writes about little else; IT之家 is gadgets and phones
            {"AI", "rss:qbitai", "AI 新闻"}, {"ai", "rss:qbitai", "AI 新闻"}, {"人工智能", "rss:qbitai", "AI 新闻"}, {"大模型", "rss:qbitai", "AI 新闻"},
            {"数码", "rss:ithome", "数码新闻"}, {"手机", "rss:ithome", "数码新闻"}, {"IT", "rss:ithome", "数码新闻"},
            {"国内", "cn:china", "国内新闻"}, {"国际", "cn:world", "国际新闻"}, {"社会", "cn:society", "社会新闻"},
            {"财经", "cn:finance", "财经新闻"}, {"经济", "cn:finance", "财经新闻"}, {"体育", "cn:sports", "体育新闻"},
            {"科技", "sina:2515", "科技新闻"}, {"互联网", "sina:2515", "科技新闻"},
            {"最新", "cn:scroll-news", "最新新闻"}, {"即时", "cn:scroll-news", "最新新闻"}};

    /** {source, how it is said} for a category (empty or unknown: the latest news). */
    public static String[] category(String said) {
        String s = said == null ? "" : said.trim();
        for (String[] c : CATEGORIES) if (s.contains(c[0])) return new String[]{c[1], c[2]};
        return new String[]{"cn:scroll-news", "最新新闻"};
    }

    /** Whether a category is one this tool has (娱乐, 军事… are a web search). */
    public static boolean known(String said) {
        String s = said == null ? "" : said.trim();
        if (s.isEmpty()) return true;
        for (String[] c : CATEGORIES) if (s.contains(c[0])) return true;
        return false;
    }

    public static String url(String source) {
        if (source.startsWith("cn:")) return "https://www.chinanews.com.cn/rss/" + source.substring(3) + ".xml";
        if ("rss:qbitai".equals(source)) return "https://www.qbitai.com/feed";
        if ("rss:ithome".equals(source)) return "https://www.ithome.com/rss/";
        return "https://feed.mix.sina.com.cn/api/roll/get?pageid=153&lid=" + source.substring(5) + "&k=&num=20&page=1";
    }

    public static final class Item {
        public final String title, summary, link;
        public final long at;

        Item(String title, String summary, String link, long at) { this.title = title; this.summary = summary; this.link = link; this.at = at; }
    }

    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    private static String tag(String item, String name) {
        Matcher m = Pattern.compile("<" + name + ">(?:\\s*<!\\[CDATA\\[)?(.*?)(?:\\]\\]>\\s*)?</" + name + ">", Pattern.DOTALL).matcher(item);
        return m.find() ? m.group(1) : "";
    }

    /** A string field, "" for a missing or null one: Android's org.json gives "null" for a JSON null (Douban's
     * release_date on its series lists), the desktop library the tests use gives "" (2026-11-07 review). */
    static String str(JSONObject o, String key) { return o == null || o.isNull(key) ? "" : o.optString(key); }

    static String text(String html) {
        String t = TAGS.matcher(html == null ? "" : html).replaceAll("");
        t = t.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'");
        return t.replaceAll("[\\s　]+", " ").trim();
    }

    /** 「中新社上海11月6日电(记者 肖玉笛)正在上海举行的…」→「正在上海举行的…」: the dateline is not read. */
    private static final Pattern DATELINE = Pattern.compile("^[^。]{0,30}?(?:\\d{1,2}月\\d{1,2}日)?电[（(][^）)]{0,20}[）)]\\s*");

    public static List<Item> items(String source, String body) throws JSONException {
        List<Item> out = new ArrayList<>();
        if (source.startsWith("cn:") || source.startsWith("rss:")) {
            Matcher m = ITEM.matcher(body == null ? "" : body);
            SimpleDateFormat f = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US);
            while (m.find()) {
                String it = m.group(1);
                long at;
                try { at = f.parse(text(tag(it, "pubDate"))).getTime(); } catch (ParseException e) { at = 0; }
                String summary = DATELINE.matcher(text(tag(it, "description"))).replaceFirst("");
                out.add(new Item(text(tag(it, "title")), cut(summary, 120), text(tag(it, "link")), at));
            }
            return out;
        }
        JSONObject r = new JSONObject(body).getJSONObject("result");
        JSONArray data = r.optJSONArray("data");
        for (int i = 0; data != null && i < data.length(); i++) {
            JSONObject x = data.getJSONObject(i);
            out.add(new Item(text(x.optString("title")), cut(text(x.optString("intro")), 120), x.optString("url"), x.optLong("ctime") * 1000));
        }
        return out;
    }

    private static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }

    /** The items from the last two days, newest first, without repeats (the same story under two titles is kept). */
    public static List<Item> fresh(List<Item> all, long now) {
        List<Item> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Item it : all) {
            if (it.at <= 0 || now - it.at > 48 * 3_600_000L || it.title.isEmpty() || seen.contains(it.title)) continue;
            seen.add(it.title);
            out.add(it);
        }
        out.sort((a, b) -> Long.compare(b.at, a.at));
        return out;
    }

    private static final String[] ORDINAL = {"第一", "第二", "第三", "第四", "第五", "第六", "第七", "第八", "第九", "第十"};

    /** 「中新网国际新闻（最新一条 11 点 49 分）：第一，…；第二，…」 */
    public static String speak(String label, String source, List<Item> items, int n) {
        return speak(label, source, items, n, System.currentTimeMillis());
    }

    /**
     * The newest headline's time with its day when it is not today's (2026-11-10 sweep: 「最新一条 16 点 57 分」 at eight in
     * the morning was yesterday's, and said like that it sounds like today's): 「（最新一条 昨天 16 点 57 分）」.
     */
    public static String speak(String label, String source, List<Item> items, int n, long now) {
        int k = Math.min(Math.min(n, ORDINAL.length), items.size());
        // 「量子位 AI 新闻」: a space before a Latin word, as everywhere in what is read out
        boolean latin = !label.isEmpty() && label.charAt(0) < 128;
        StringBuilder s = new StringBuilder(publisher(source)).append(latin ? " " : "").append(label)
                .append("（最新一条 ").append(dayWord(items.get(0).at, now)).append(TvGuide.hm(items.get(0).at)).append("）：");
        for (int i = 0; i < k; i++) s.append(i == 0 ? "" : "；").append(ORDINAL[i]).append("，").append(items.get(i).title);
        return s.toString();
    }

    /** The Beijing day before a time, with the space that keeps it off the number: "" today, 「昨天 」「前天 」「11 月 8 日 」. */
    static String dayWord(long at, long now) {
        long days = Math.floorDiv(at + 8 * 3_600_000L, 86_400_000L) - Math.floorDiv(now + 8 * 3_600_000L, 86_400_000L);
        if (days == 0) return "";
        if (days == -1) return "昨天 ";
        if (days == -2) return "前天 ";
        Calendar c = Calendar.getInstance(Sports.BEIJING);
        c.setTimeInMillis(at);
        return (c.get(Calendar.MONTH) + 1) + " 月 " + c.get(Calendar.DAY_OF_MONTH) + " 日 ";
    }

    /** Whose headlines these are: 中新网, 新浪, 量子位, IT之家. */
    public static String publisher(String source) {
        if (source.startsWith("cn:")) return "中新网";
        if ("rss:qbitai".equals(source)) return "量子位";
        if ("rss:ithome".equals(source)) return "IT之家";
        return "新浪";
    }

    /** What was read, for environment.last_list: 「第二条详细说说」 is its second item, with its summary. */
    public static JSONObject readOut(String label, List<Item> items, int n) throws JSONException {
        JSONArray list = new JSONArray();
        for (int i = 0; i < Math.min(Math.min(n, ORDINAL.length), items.size()); i++) {
            Item it = items.get(i);
            list.put(new JSONObject().put("n", i + 1).put("title", it.title).put("summary", it.summary));
        }
        return new JSONObject().put("what", label).put("items", list)
                .put("note", "刚读给用户的新闻标题：用户问“第二条怎么回事”先用它的 summary 说，要更多细节再用 web_search 查那一条");
    }
}
