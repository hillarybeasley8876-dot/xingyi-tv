package xyz.erapi0neer.xiaoaibridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONException;
import org.json.JSONObject;
import xyz.erapi0neer.xingyi.MusicResolver;

/** NetEase's public web search (the same endpoint the browser uses). The response is returned as is, code included. */
final class NeteaseSearch implements MusicResolver.Search {
    private static final String URL_BASE = "https://music.163.com/api/search/get/web?type=1&limit=20&offset=0&s=";
    private static final int MAX_BYTES = 256 * 1024;

    @Override public JSONObject songs(String keywords) throws IOException {
        HttpsURLConnection c = (HttpsURLConnection) new URL(URL_BASE + URLEncoder.encode(keywords, "UTF-8")).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        c.setConnectTimeout(4000);
        c.setReadTimeout(5000);
        c.setRequestProperty("Referer", "https://music.163.com/");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) XiaoaiBridge/0.1");
        try {
            if (c.getResponseCode() != 200) throw new IOException("search_failed");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (bytes.size() + n > MAX_BYTES) throw new IOException("search_failed");
                    bytes.write(buf, 0, n);
                }
                return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (JSONException e) {
            throw new IOException("search_failed", e);
        } finally {
            c.disconnect();
        }
    }
}
