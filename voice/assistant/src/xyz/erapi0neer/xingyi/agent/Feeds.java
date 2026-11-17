package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public data feeds without a key (2026-11-06, the user: 「继续增加工具调用」): stock quotes, the RMB's central parity,
 * exchange rates, hot lists. One way to fetch them (with the feed's own charset: Tencent's quotes are GBK), and a short
 * cache, so a question asked twice in a minute does not go out twice. These took a web search, 7-9 s, before.
 */
public final class Feeds {
    private Feeds() { }

    public interface Fetch { String get(String url, String charset, int timeoutMs) throws IOException; }

    /** A POST of one JSON body, answered as a string (腾讯视频的搜索，2026-11-10). */
    public interface Post { String post(String url, String json, int timeoutMs) throws IOException; }

    /** B站's search, which wants the visitor id its own site gets (BiliVideos). */
    private static final String BILI_SEARCH = "https://api.bilibili.com/x/web-interface/search/";
    static final String BILI_VISITOR = "https://api.bilibili.com/x/frontend/finger/spi";

    public static Fetch https() {
        return (url, charset, timeoutMs) -> {
            if (!url.startsWith(BILI_SEARCH)) return fetch(url, charset, timeoutMs, null);
            // B站's search answers a client without a visitor id once or twice, then turns it away with 412 (2026-11-07,
            // on the PC); with the id it kept answering. An id it turns away is replaced once.
            try { return fetch(url, charset, timeoutMs, biliVisitor(timeoutMs, false)); }
            catch (Refused e) { return fetch(url, charset, timeoutMs, biliVisitor(timeoutMs, true)); }
        };
    }

    /** HTTP 412: the service turned this client away (B站's risk control). */
    private static final class Refused extends IOException { Refused() { super("HTTP 412"); } }

    private static String fetch(String url, String charset, int timeoutMs, String biliCookie) throws IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        boolean read = false;
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(Math.min(6000, timeoutMs));
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9; MiTV) AppleWebKit/537.36");
            // Sina's quotes answer only with its own page as the referrer; the sports lottery's site turns bare,
            // quick requests away with a 567 challenge page
            if (url.contains("sinajs.cn")) c.setRequestProperty("Referer", "https://finance.sina.com.cn");
            // Eastmoney's fund values answer {"ErrCode":-999} without their own pages as the referrer (2026-11-07:
            // on the PC with curl's headers it answered; on the TV it did not)
            if (url.contains("api.fund.eastmoney.com")) c.setRequestProperty("Referer", "https://fundf10.eastmoney.com/");
            // Douban's mobile search answers its own pages (Movies)
            if (url.contains("m.douban.com")) c.setRequestProperty("Referer", "https://m.douban.com/movie/");
            // NetEase's web API answers its own site (as NeteaseCatalog has it)
            if (url.contains("music.163.com")) c.setRequestProperty("Referer", "https://music.163.com/");
            if (url.contains("sporttery.cn")) {
                c.setRequestProperty("Referer", "https://static.sporttery.cn/");
                c.setRequestProperty("Origin", "https://static.sporttery.cn");
            }
            if (biliCookie != null) {
                c.setRequestProperty("Cookie", biliCookie);
                c.setRequestProperty("Referer", "https://search.bilibili.com/");
            }
            int status = c.getResponseCode();
            if (status == 412) throw new Refused();
            if (status != 200) throw new IOException("HTTP " + status);
            String body = readBody(c, charset);
            read = true;
            return body;
        } finally {
            // a fully read answer leaves the socket to the pool; else close it
            if (!read) c.disconnect();
        }
    }

    /** 一个回复读成字符串；超过 4 MB 的不是我们要的（腾讯视频的一次搜索就有 0.1–1.1 MB，抗日剧那份 1 119 209 字节）。 */
    private static String readBody(java.net.HttpURLConnection c, String charset) throws IOException {
        try (java.io.InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) != -1; ) {
                out.write(buf, 0, n);
                if (out.size() > 4 * 1024 * 1024) throw new IOException("回复太大");
            }
            return new String(out.toByteArray(), charset == null ? "UTF-8" : charset);
        }
    }

    /**
     * A POST of a JSON body. 腾讯视频的搜索（{@link TencentVideo#SEARCH}）要 v.qq.com 的 Referer 和 Origin 才回答，
     * 和它网站自己的页面发的一样；别的地址照常。超时和 User-Agent 与 {@link #fetch} 一样。
     */
    public static Post httpsPost() {
        return (url, json, timeoutMs) -> {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            boolean read = false;
            try {
                c.setInstanceFollowRedirects(false);
                c.setRequestMethod("POST");
                c.setConnectTimeout(Math.min(6000, timeoutMs));
                c.setReadTimeout(timeoutMs);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9; MiTV) AppleWebKit/537.36");
                c.setRequestProperty("Content-Type", "application/json");
                if (url.contains("pbaccess.video.qq.com")) {
                    c.setRequestProperty("Referer", "https://v.qq.com/");
                    c.setRequestProperty("Origin", "https://v.qq.com");
                }
                byte[] body = (json == null ? "" : json).getBytes("UTF-8");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                java.io.OutputStream out = c.getOutputStream();
                try { out.write(body); } finally { out.close(); }
                int status = c.getResponseCode();
                if (status != 200) throw new IOException("HTTP " + status);
                String text = readBody(c, "UTF-8");
                read = true;
                return text;
            } finally {
                if (!read) c.disconnect();
            }
        };
    }

    private static String biliId;
    private static long biliIdAt;

    /** B站's visitor id (buvid3 and buvid4, as its site gets them), kept 12 hours or until the search turns it away. */
    private static synchronized String biliVisitor(int timeoutMs, boolean fresh) throws IOException {
        long now = System.currentTimeMillis();
        if (!fresh && biliId != null && now - biliIdAt < 12 * 3_600_000L) return biliId;
        try {
            org.json.JSONObject d = new org.json.JSONObject(fetch(BILI_VISITOR, "UTF-8", timeoutMs, null)).getJSONObject("data");
            biliId = "buvid3=" + d.getString("b_3") + "; buvid4=" + d.getString("b_4");
        } catch (org.json.JSONException e) {
            throw new IOException("B站的访客标识没拿到");
        }
        biliIdAt = now;
        return biliId;
    }

    /** Answers kept ttlMs by address, at most 32. */
    public static Fetch cached(Fetch f, long ttlMs) { return cached(f, ttlMs, System::currentTimeMillis); }

    static Fetch cached(Fetch f, long ttlMs, java.util.function.LongSupplier clock) {
        Map<String, Object[]> memo = new LinkedHashMap<String, Object[]>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Object[]> e) { return size() > 32; }
        };
        return (url, charset, timeoutMs) -> {
            long now = clock.getAsLong();
            synchronized (memo) {
                Object[] hit = memo.get(url);
                if (hit != null && now - (Long) hit[0] < ttlMs) return (String) hit[1];
            }
            String body = f.get(url, charset, timeoutMs);
            synchronized (memo) { memo.put(url, new Object[]{now, body}); }
            return body;
        };
    }

    /** POST 的回复按 url + 请求体缓存 ttlMs（同样的问题十分钟内不再问一次），最多 32 条。 */
    public static Post cached(Post p, long ttlMs) { return cachedPost(p, ttlMs, System::currentTimeMillis); }

    /**
     * 带时钟的那一个不叫 cached：{@code cached(Fetch, long, LongSupplier)} 也在，一个直接写在那里的 lambda 两个都像，
     * 编译不过（FeedChecks 就是这么调的）。这里按名字区分。
     */
    static Post cachedPost(Post p, long ttlMs, java.util.function.LongSupplier clock) {
        Map<String, Object[]> memo = new LinkedHashMap<String, Object[]>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Object[]> e) { return size() > 32; }
        };
        return (url, json, timeoutMs) -> {
            String key = url + "\n" + json;
            long now = clock.getAsLong();
            synchronized (memo) {
                Object[] hit = memo.get(key);
                if (hit != null && now - (Long) hit[0] < ttlMs) return (String) hit[1];
            }
            String body = p.post(url, json, timeoutMs);
            synchronized (memo) { memo.put(key, new Object[]{now, body}); }
            return body;
        };
    }
}
