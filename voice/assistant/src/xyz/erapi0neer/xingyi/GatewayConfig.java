package xyz.erapi0neer.xingyi;

import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** One model endpoint (HTTPS root, key, model) from the app-private files/config.json.
 * Never put this file in shared/external storage, logs or exception messages.
 *
 * config.json version 2 holds both routes; provision_minimax.py writes it:
 * {"version":2,"official":{"baseUrl":"https://api.minimax.io","model":"MiniMax-M3.1-Flash","apiKey":"…"},
 *  "gateway":{"baseUrl":"https://gateway.example.com","model":"MiniMax-M3.1-Flash","apiKey":"…"}}
 * The 0.4 single-gateway file ({"baseUrl","model","apiKey"}) still loads, as a gateway-only setup.
 */
public final class GatewayConfig {
    static final String DEFAULT_GATEWAY = "https://gateway.example.com";
    /** Only MiniMax's own API hosts may be labelled "official" in the UI. */
    static final Set<String> OFFICIAL_HOSTS = new HashSet<>(Arrays.asList("api.minimax.io", "api.minimaxi.com"));
    public final String baseUrl;
    public final String apiKey;
    public final String model;

    public GatewayConfig(String baseUrl, String apiKey, String model) throws IOException {
        if(baseUrl==null||baseUrl.length()>2048)throw new IOException("模型线路地址格式错误");
        try {
            URI uri = new URI(baseUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw new IOException("模型线路地址必须是 HTTPS 域名根地址");
            }
            this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        } catch (java.net.URISyntaxException e) {
            throw new IOException("模型线路地址格式错误");
        }
        if (apiKey == null || apiKey.isEmpty() || apiKey.length() > 4096) throw new IOException("模型线路密钥未配置或格式错误");
        for (int i = 0; i < apiKey.length(); i++) {
            // Reject invalid header octets here, rather than exposing an exception containing the header value.
            char ch = apiKey.charAt(i);
            if (ch < 33 || ch > 126) throw new IOException("模型线路密钥格式错误");
        }
        if (model == null || !model.matches("[A-Za-z0-9._-]{1,100}")) {
            throw new IOException("模型名称格式错误");
        }
        this.apiKey = apiKey;
        this.model = model;
    }

    /** Both routes. Either may be null, never both. */
    public static final class Routes {
        public final GatewayConfig official, gateway;
        Routes(GatewayConfig official, GatewayConfig gateway) { this.official = official; this.gateway = gateway; }
    }

    public static Routes loadRoutes(Context context) throws IOException {
        return parseRoutes(read(context));
    }

    static Routes parseRoutes(JSONObject json) throws IOException {
        if (json.has("official") || json.has("gateway")) {
            GatewayConfig official = route(json, "official"), gateway = route(json, "gateway");
            if (official == null && gateway == null) throw new IOException("尚未配置模型线路，请先导入助手配置");
            if (official != null && !OFFICIAL_HOSTS.contains(host(official.baseUrl)))
                throw new IOException("官方线路必须使用 MiniMax 官方域名");
            return new Routes(official, gateway);
        }
        // 0.4 and earlier: a single gateway. Its "model" named the retired Gemini client, so chat uses MODEL.
        return new Routes(null, new GatewayConfig(field(json, "baseUrl", DEFAULT_GATEWAY),
                field(json, "apiKey", ""), MiniMaxClient.MODEL));
    }

    /** Legacy single-endpoint loader kept for {@link GatewayClient}. */
    public static GatewayConfig load(Context context) throws IOException {
        JSONObject json = read(context);
        return new GatewayConfig(field(json,"baseUrl",DEFAULT_GATEWAY),
                field(json,"apiKey",""),field(json,"model","gemini-3.8-flash"));
    }

    private static JSONObject read(Context context) throws IOException {
        File file = new File(context.getFilesDir(), "config.json");
        if (!file.isFile()) throw new IOException("尚未配置模型线路，请先导入助手配置");
        try (FileInputStream input = new FileInputStream(file);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[1024];
            int n;
            while ((n = input.read(chunk)) != -1) {
                if (buffer.size() + n > 16384) throw new IOException("模型线路配置文件过大");
                buffer.write(chunk, 0, n);
            }
            return new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException("模型线路配置不是有效的 JSON");
        }
    }

    private static GatewayConfig route(JSONObject json, String name) throws IOException {
        Object raw = json.opt(name);
        if (raw == null || raw == JSONObject.NULL) return null;
        if (!(raw instanceof JSONObject)) throw new IOException("模型线路配置格式不正确");
        JSONObject entry = (JSONObject) raw;
        return new GatewayConfig(field(entry, "baseUrl", ""), field(entry, "apiKey", ""),
                field(entry, "model", MiniMaxClient.MODEL));
    }

    private static String host(String url) {
        try { String host = new URI(url).getHost(); return host == null ? "" : host.toLowerCase(Locale.ROOT); }
        catch (java.net.URISyntaxException e) { return ""; }
    }

    private static String field(JSONObject json,String key,String fallback)throws IOException {
        if(!json.has(key))return fallback;
        Object v=json.opt(key);if(!(v instanceof String))throw new IOException("模型线路配置字段类型不正确");
        return (String)v;
    }
}
