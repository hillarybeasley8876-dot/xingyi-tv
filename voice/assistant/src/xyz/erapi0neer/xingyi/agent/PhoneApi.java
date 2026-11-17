package xyz.erapi0neer.xingyi.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The phone's console (phone-console, 2026-11-08): a minimal HTTP/1.1 server for the web app in
 * {@code assets/agent/phone.html}. Typing on the phone — or the phone keyboard's own dictation — gets a sentence into
 * 星仪 exactly as it was meant, which XiaoAi's recognition does not (质朴/智谱, TIBA/Tibo, 合集/和谐), and the phone then
 * watches the plan and the thinking chain, confirms a step and stops a task. Pairing: the TV shows a six-digit code
 * for two minutes, the phone trades it for a token kept in {@code files/agent/phones.json} (at most five phones), and
 * every other {@code /api/*} call carries {@code Authorization: Bearer <token>}.
 *
 * <p>All of it is pure (java.util, java.io, org.json) so the checks drive it with fakes; {@code tv/PhoneServer} is
 * the Android side (the socket, the assets, the files, the pair card on the TV).
 */
public final class PhoneApi {
    /** What the phone can ask the TV to do. */
    public interface Engine {
        String submit(String text, boolean speak);
        JSONObject state();
        void consent(boolean yes);
        void stop();
        JSONArray history(int days);
        /** Shows the pair code on the TV. False when it cannot be shown (the screen is off) — then pairing waits. */
        boolean showPairCode(String code);
        /** Whether something is playing now: the 暂停/继续 chip pauses it, or resumes when nothing plays. */
        default boolean playing() { return true; }
    }

    /** The paired phones, kept between restarts ({@code files/agent/phones.json} on the TV). */
    public interface Phones {
        JSONArray load();
        void save(JSONArray phones);
    }

    /** The clock, so the pair code's two minutes can be checked with a fake. */
    public interface Clock { long now(); }

    public static final Clock SYSTEM_CLOCK = new Clock() {
        @Override public long now() { return System.currentTimeMillis(); }
    };

    /** A request head and a body are each at most this; anything larger is a 400 (the phone sends a sentence). */
    static final int MAX = 8192;
    /** How long a pair code lives, how many wrong tries void it, and how many phones are kept (a home's TV). */
    static final long PAIR_MS = 120_000;
    static final int PAIR_TRIES = 5, MAX_PHONES = 5;
    /** Wrong codes counted across fresh codes: more than this many in LOCK_MS and pairing waits out the rest of it.
     * A fresh code used to reset the count, so asking again and again walked through the six digits (2026-11-09
     * review); now that takes years. */
    static final int LOCK_TRIES = 10;
    static final long LOCK_MS = 10 * 60_000;
    /** One sentence, as long as the old phone window allowed; the phone list is written back at most this often. */
    static final int TEXT_MAX = 500;
    private static final long SAVE_EVERY_MS = 60_000;

    private static final String HTML_TYPE = "text/html; charset=utf-8";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Control characters a typed sentence has no business carrying (tab and newline are fine). */
    private static final Pattern CONTROL = Pattern.compile("[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]");

    private final Engine engine;
    private final Phones store;
    private final Clock clock;
    /** The web app itself, read from assets by the TV side. */
    private final String html;
    private JSONArray tokens;
    private long savedAt;
    private String code = "";
    private long codeAt;
    private int tries;
    private final java.util.ArrayDeque<Long> misses = new java.util.ArrayDeque<>();

    public PhoneApi(Engine engine, Phones store, Clock clock, String html) {
        this.engine = engine;
        this.store = store;
        this.clock = clock;
        this.html = html == null ? "" : html;
    }

    /** One connection: reads the request, writes the whole response and returns its status. */
    public int serve(InputStream in, OutputStream out) throws IOException {
        Request request = Request.read(in);
        Response response;
        try { response = request == null ? error(400, "请求看不懂，请刷新页面重试。") : handle(request); }
        catch (JSONException e) { response = error(500, "电视这边出错了，请再试一次。"); }
        response.writeTo(out);
        return response.status;
    }

    // ---- routing --------------------------------------------------------------------------------

    /** An unknown path is a 404; a known one with the other method is a 405, so a stray post says what is wrong. */
    private synchronized Response handle(Request q) throws JSONException {
        String path = path(q.path);
        String allow = method(path);
        if (allow.isEmpty()) return error(404, "没有这个地址。");
        if (!allow.equals(q.method)) return error(405, "这个地址只接受 " + allow + "。");
        if ("/".equals(path)) return html.isEmpty() ? error(500, "手机页面还没装好。") : Response.of(200, HTML_TYPE, html);
        if ("/manifest.json".equals(path)) return Response.of(200, "application/json; charset=utf-8", MANIFEST);
        if ("/icon.svg".equals(path)) return Response.of(200, "image/svg+xml", ICON);
        // pairing is what a phone without a token does; everything else needs one
        if ("/api/pair/start".equals(path)) return pairStart();
        if ("/api/pair".equals(path)) return pair(q);
        if (!auth(q)) return error(401, "请在电视上重新配对。");
        if ("/api/say".equals(path)) return say(q);
        if ("/api/key".equals(path)) return key(q);
        if ("/api/consent".equals(path)) return consent(q);
        if ("/api/stop".equals(path)) { engine.stop(); return ok(); }
        if ("/api/history".equals(path)) return Response.json(200, engine.history(2));
        return Response.json(200, engine.state());
    }

    private static final Set<String> GETS = new HashSet<>(Arrays.asList(
            "/", "/manifest.json", "/icon.svg", "/api/state", "/api/history"));
    private static final Set<String> POSTS = new HashSet<>(Arrays.asList(
            "/api/pair/start", "/api/pair", "/api/say", "/api/key", "/api/consent", "/api/stop"));

    /** The method a path answers to, or "" when there is no such path. */
    static String method(String path) { return GETS.contains(path) ? "GET" : POSTS.contains(path) ? "POST" : ""; }

    /** The path without its query. */
    private static String path(String target) { int cut = target.indexOf('?'); return cut < 0 ? target : target.substring(0, cut); }

    /** The request's JSON body, or null when it is missing or not an object. */
    private static JSONObject body(Request q) {
        if (q.body.isEmpty()) return null;
        try { return new JSONObject(q.body); } catch (JSONException notJson) { return null; }
    }

    private static Response ok() throws JSONException { return Response.json(200, new JSONObject().put("ok", true)); }

    private static Response error(int status, String message) {
        try { return Response.json(status, new JSONObject().put("error", message)); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    // ---- pairing and tokens ---------------------------------------------------------------------

    /** A fresh six-digit code on the TV, good for two minutes — and only on a lit screen: nobody reads a code off a
     * dark TV, and lighting it up for whoever is on the LAN is not this app's business. */
    private synchronized Response pairStart() throws JSONException {
        if (locked()) return error(429, "配对码试错太多次了，过十分钟再配对。");
        code = String.format(Locale.US, "%06d", RANDOM.nextInt(1_000_000));
        codeAt = clock.now();
        tries = 0;
        if (!engine.showPairCode(code)) {
            code = "";
            // a dark screen, or a task's panel up on a lit one (2026-11-09 review: 「先打开电视屏幕」 with the screen on)
            return error(409, "先打开电视屏幕，等电视上的任务结束再配对。");
        }
        return Response.json(200, new JSONObject().put("ok", true).put("expires_in", (int) (PAIR_MS / 1000)));
    }

    /** The code from the TV traded for a token that is kept until the phone is forgotten. */
    private synchronized Response pair(Request q) throws JSONException {
        JSONObject b = body(q);
        if (b == null) return error(400, "请求看不懂，请刷新页面重试。");
        String given = b.optString("code", "").replaceAll("\\s", "");
        String name = b.optString("name", "").trim();
        if (name.isEmpty()) name = "手机";
        if (name.length() > 24) name = name.substring(0, 24);
        if (code.isEmpty() || clock.now() - codeAt > PAIR_MS) {
            code = "";
            return error(400, "配对码已过期，请在电视上重新显示。");
        }
        if (locked()) {
            code = "";
            return error(429, "配对码试错太多次了，过十分钟再配对。");
        }
        if (!given.equals(code)) {
            misses.addLast(clock.now());
            if (++tries >= PAIR_TRIES) {
                code = "";
                return error(403, "配对码错误次数已用完，请在电视上重新显示。");
            }
            return error(403, "配对码不正确。");
        }
        // one code, one phone: the second try with the same code gets nothing
        code = "";
        String token = newToken();
        add(token, name);
        return Response.json(200, new JSONObject().put("token", token).put("name", name));
    }

    /** Too many wrong codes lately, whichever code they were tried against. */
    private boolean locked() {
        long now = clock.now();
        while (!misses.isEmpty() && now - misses.peekFirst() > LOCK_MS) misses.pollFirst();
        return misses.size() >= LOCK_TRIES;
    }

    /** Whether the request carries a token this TV knows; a known one is marked as seen, and the list is written
     * back at most once a minute since a paired phone polls while it watches. */
    private boolean auth(Request q) throws JSONException {
        String header = q.headers.get("authorization");
        if (header == null) return false;
        String value = header.trim();
        if (value.length() <= 7 || !value.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        String token = value.substring(7).trim();
        if (token.isEmpty()) return false;
        JSONArray list = tokens();
        for (int i = 0; i < list.length(); i++) {
            JSONObject p = list.optJSONObject(i);
            if (p == null || !token.equals(p.optString("token"))) continue;
            long now = clock.now();
            p.put("seen", now);
            if (now - savedAt > SAVE_EVERY_MS) { store.save(list); savedAt = now; }
            return true;
        }
        return false;
    }

    private JSONArray tokens() {
        if (tokens == null) {
            JSONArray loaded = store.load();
            tokens = loaded == null ? new JSONArray() : loaded;
            savedAt = clock.now();
        }
        return tokens;
    }

    /** Remembers a phone, dropping the oldest when there are already {@link #MAX_PHONES}. */
    private void add(String token, String name) throws JSONException {
        JSONArray list = tokens();
        for (int i = list.length() - 1; i >= 0; i--) {
            JSONObject p = list.optJSONObject(i);
            // a torn entry or the same phone pairing again: the new one replaces it
            if (p == null || p.optString("token").isEmpty() || token.equals(p.optString("token"))) list.remove(i);
        }
        long now = clock.now();
        list.put(new JSONObject().put("token", token).put("name", name).put("created", now).put("seen", now));
        while (list.length() > MAX_PHONES) list.remove(0);
        store.save(list);
        savedAt = now;
    }

    /** 32 bytes of SecureRandom, hex: nothing about the phone can be guessed from it. */
    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder(64);
        for (byte b : bytes) out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return out.toString();
    }

    // ---- what the phone asks for -----------------------------------------------------------------

    /** One typed sentence into 星仪. {@code speak} false is the 「电视朗读」 switch off: answered on the TV's screen,
     * not read out loud (AgentEngine.spoken treats the quiet source like the 星仪 app's own typing). */
    private Response say(Request q) throws JSONException {
        JSONObject b = body(q);
        if (b == null) return error(400, "请求看不懂，请刷新页面重试。");
        String raw = b.optString("text", "");
        // before trimming: trim() would quietly drop the control characters this refuses
        if (CONTROL.matcher(raw).find()) return error(400, "请输入 1 至 " + TEXT_MAX + " 字的指令。");
        String text = raw.trim();
        if (text.isEmpty() || text.length() > TEXT_MAX) return error(400, "请输入 1 至 " + TEXT_MAX + " 字的指令。");
        String routed = engine.submit(text, b.optBoolean("speak", true));
        return Response.json(200, new JSONObject().put("request", routed == null ? "" : routed));
    }

    /** A quick chip: the words the user would say, so the quick commands answer it without a model round. Always
     * quiet — a chip is a remote control, not a request. */
    private Response key(Request q) throws JSONException {
        JSONObject b = body(q);
        if (b == null) return error(400, "请求看不懂，请刷新页面重试。");
        String key = b.optString("key", "");
        String command = KEYS.get(key);
        if (command == null) return error(400, "不认识的按键。");
        // 暂停/继续: pause what plays, resume when nothing does (it only ever paused, 2026-11-09 review)
        if ("play_pause".equals(key) && !engine.playing()) command = "继续播放";
        String routed = engine.submit(command, false);
        return Response.json(200, new JSONObject().put("request", routed == null ? "" : routed).put("command", command));
    }

    private Response consent(Request q) throws JSONException {
        JSONObject b = body(q);
        if (b == null) return error(400, "请求看不懂，请刷新页面重试。");
        engine.consent(b.optBoolean("approve"));
        return ok();
    }

    /** The chips of the phone's bottom row, each the words the quick commands already know. */
    static final Map<String, String> KEYS = keys();

    private static Map<String, String> keys() {
        String[][] pairs = {{"vol_up", "音量调大一点"}, {"vol_down", "音量调小一点"}, {"mute", "静音"},
                {"play_pause", "暂停"}, {"next", "下一首"}, {"prev", "上一首"}, {"home", "回到桌面"},
                {"back", "返回"}, {"screen_off", "关闭屏幕"}, {"screen_on", "亮屏"}};
        Map<String, String> m = new HashMap<>();
        for (String[] p : pairs) m.put(p[0], p[1]);
        return m;
    }

    // ---- the request ----------------------------------------------------------------------------

    /** One HTTP/1.1 request: the line, the headers (lowercase names) and a body of at most {@link #MAX} bytes. */
    static final class Request {
        final String method, path;
        final Map<String, String> headers;
        final String body;

        private Request(String method, String path, Map<String, String> headers, String body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }

        /** The whole request off the stream, or null when it is malformed, too large or cut off (the caller answers
         * 400 and closes). No chunked bodies: the phone's own fetch() always says how long its JSON is. */
        static Request read(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int tail = 0;
            while (tail != 0x0d0a0d0a) {
                int next = in.read();
                if (next < 0 || head.size() >= MAX) return null;
                head.write(next);
                tail = (tail << 8) | next;
            }
            String[] lines = new String(head.toByteArray(), StandardCharsets.US_ASCII).split("\r\n");
            String[] request = lines[0].split(" ");
            if (request.length != 3 || !request[2].startsWith("HTTP/1.")) return null;
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isEmpty()) continue;
                int split = lines[i].indexOf(':');
                // a header without a name, or the same one twice: not something a browser sends
                if (split < 1) return null;
                String name = lines[i].substring(0, split).toLowerCase(Locale.US);
                if (headers.put(name, lines[i].substring(split + 1).trim()) != null) return null;
            }
            if (headers.containsKey("transfer-encoding")) return null;
            int size = 0;
            String length = headers.get("content-length");
            if (length != null) {
                try { size = Integer.parseInt(length.trim()); }
                catch (NumberFormatException notANumber) { return null; }
                if (size < 0 || size > MAX) return null;
            }
            byte[] body = new byte[size];
            for (int read = 0; read < size; ) {
                int count = in.read(body, read, size - read);
                if (count < 0) return null;
                read += count;
            }
            return new Request(request[0], request[1], headers, new String(body, StandardCharsets.UTF_8));
        }
    }

    // ---- the response ---------------------------------------------------------------------------

    /** A whole response, written and closed: the phone's fetch() never reuses a connection. */
    static final class Response {
        final int status;
        final String type;
        final byte[] body;

        private Response(int status, String type, byte[] body) {
            this.status = status;
            this.type = type;
            this.body = body;
        }

        static Response of(int status, String type, String text) {
            return new Response(status, type, text.getBytes(StandardCharsets.UTF_8));
        }

        static Response json(int status, Object o) {
            return of(status, "application/json; charset=utf-8", o.toString());
        }

        void writeTo(OutputStream out) throws IOException {
            String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                    + "Content-Type: " + type + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n"
                    + "X-Content-Type-Options: nosniff\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        }

        private static String reason(int status) {
            switch (status) {
                case 400: return "Bad Request";
                case 401: return "Unauthorized";
                case 403: return "Forbidden";
                case 404: return "Not Found";
                case 405: return "Method Not Allowed";
                case 409: return "Conflict";
                case 429: return "Too Many Requests";
                case 500: return "Internal Server Error";
                default: return status == 200 ? "OK" : "Error";
            }
        }
    }

    /** What the phone's browser needs to offer 「添加到主屏幕」 as an app of its own. */
    static final String MANIFEST = "{\"name\":\"星仪\",\"short_name\":\"星仪\",\"display\":\"standalone\","
            + "\"background_color\":\"#0b0c0f\",\"theme_color\":\"#0b0c0f\",\"start_url\":\"/\","
            + "\"icons\":[{\"src\":\"/icon.svg\",\"sizes\":\"any\",\"type\":\"image/svg+xml\"}]}";

    /** The panel's own mark: a ring with a dot resting at 45° up-right, white on the app's black. */
    static final String ICON = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\">"
            + "<rect width=\"64\" height=\"64\" fill=\"#0b0c0f\"/>"
            + "<circle cx=\"32\" cy=\"32\" r=\"18\" fill=\"none\" stroke=\"#fff\" stroke-opacity=\".6\" stroke-width=\"3.2\"/>"
            + "<circle cx=\"44.7\" cy=\"19.3\" r=\"7.9\" fill=\"#fff\"/></svg>";
}
