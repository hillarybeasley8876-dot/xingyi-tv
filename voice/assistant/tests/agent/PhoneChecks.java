package xyz.erapi0neer.xingyi.agent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.*;

/** phone-console (2026-11-08): 星仪 from the phone — PhoneApi's parsing, routing, pairing, auth and commands, against a
 * fake engine. The worker fills this in; see briefs/phone-console.md. */
final class PhoneChecks {
    private PhoneChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    /** The TV side of the phone console, driven by the checks: the engine, the paired phones and the clock. */
    private static final class Fake implements PhoneApi.Engine, PhoneApi.Phones, PhoneApi.Clock {
        long now = 1_700_000_000_000L;
        JSONArray phones = new JSONArray();
        int saves;
        boolean screen = true;
        boolean playing = true;
        String shown = "";
        String said;
        boolean spoke = true;
        String routed = "started";
        int approvals;
        boolean approved, stopped;
        int historyDays = -1;
        final JSONObject state = new JSONObject();
        final JSONArray history = new JSONArray();

        Fake() throws JSONException {
            state.put("busy", false).put("awaiting", "").put("request", 7);
            history.put(new JSONObject().put("said", "放首歌").put("replied", "好的"));
        }

        @Override public long now() { return now; }
        @Override public String submit(String text, boolean speak) { said = text; spoke = speak; return routed; }
        @Override public JSONObject state() { return state; }
        @Override public void consent(boolean yes) { approvals++; approved = yes; }
        @Override public void stop() { stopped = true; }
        @Override public JSONArray history(int days) { historyDays = days; return history; }
        @Override public boolean showPairCode(String code) { shown = code; return screen; }
        @Override public boolean playing() { return playing; }
        @Override public JSONArray load() { try { return new JSONArray(phones.toString()); } catch (JSONException e) { return new JSONArray(); } }
        @Override public void save(JSONArray list) {
            saves++;
            try { phones = new JSONArray(list.toString()); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    private static final String HTML = "<!doctype html><title>星仪</title>";

    private static PhoneApi api(Fake fake) { return new PhoneApi(fake, fake, fake, HTML); }

    /** One raw request through the api: {status, body, head}. */
    private static String[] send(PhoneApi api, String raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int status = api.serve(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), out);
        String response = new String(out.toByteArray(), StandardCharsets.UTF_8);
        int split = response.indexOf("\r\n\r\n");
        return new String[]{Integer.toString(status), split < 0 ? "" : response.substring(split + 4),
                split < 0 ? response : response.substring(0, split)};
    }

    private static String get(String path, String token) {
        return "GET " + path + " HTTP/1.1\r\nHost: 192.168.1.9:18780\r\n"
                + (token == null ? "" : "Authorization: Bearer " + token + "\r\n") + "Connection: keep-alive\r\n\r\n";
    }

    private static String post(String path, String token, String body) {
        return "POST " + path + " HTTP/1.1\r\nHost: 192.168.1.9:18780\r\n"
                + (token == null ? "" : "Authorization: Bearer " + token + "\r\n")
                + "Content-Type: application/json\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                + "\r\n\r\n" + body;
    }

    /** A fresh pairing: the code the TV shows, traded for a token. */
    private static String pair(PhoneApi api, Fake fake) throws IOException {
        send(api, post("/api/pair/start", null, "{}"));
        String[] done = send(api, post("/api/pair", null, "{\"code\":\"" + fake.shown + "\",\"name\":\"小明的手机\"}"));
        return new JSONObject(done[1]).optString("token");
    }

    static void run() {
        try {
            checks();
        } catch (Exception e) {
            check(false, "phone checks threw " + e);
        }
    }

    static void checks() throws Exception {
        parsing();
        routing();
        auth();
        pairing();
        say();
        keys();
        wiring();
    }

    // ---- parsing --------------------------------------------------------------------------------

    static void parsing() throws Exception {
        Fake fake = new Fake();
        PhoneApi api = api(fake);
        String token = pair(api, fake);

        String[] state = send(api, get("/api/state", token));
        check("200".equals(state[0]), "a normal GET is parsed: " + state[0]);
        check(new JSONObject(state[1]).optInt("request") == 7, "GET /api/state is the engine's state as it is");
        check(state[2].startsWith("HTTP/1.1 200 OK\r\n"), "the response line carries the status: " + state[2].split("\r\n")[0]);
        check(state[2].contains("Cache-Control: no-store"), "every response says no-store");
        check(state[2].contains("Connection: close"), "every response closes the connection");
        check(state[2].contains("Content-Type: application/json; charset=utf-8"), "JSON answers say what they are");
        check(state[2].contains("Content-Length: " + state[1].getBytes(StandardCharsets.UTF_8).length),
                "Content-Length is the body's real length in bytes");

        String[] said = send(api, post("/api/say", token, "{\"text\":\"打开网易云音乐，放林俊杰\",\"speak\":true}"));
        check("200".equals(said[0]), "a POST with a JSON body is parsed: " + said[0]);
        check("打开网易云音乐，放林俊杰".equals(fake.said), "the Chinese body arrives as it was typed: " + fake.said);

        StringBuilder big = new StringBuilder("{\"text\":\"");
        while (big.length() < 9000) big.append('x');
        String[] tooBig = send(api, post("/api/say", token, big.append("\"}").toString()));
        check("400".equals(tooBig[0]), "a body over 8 KB is refused: " + tooBig[0]);

        check("400".equals(send(api, "GET /api/state HTTP/1.1\r\nHost: tv\r\nAuthorization Bearer x\r\n\r\n")[0]),
                "a header without a colon is malformed");
        check("400".equals(send(api, "POST /api/say HTTP/1.1\r\nContent-Length: 100\r\n\r\n{\"text\":\"hi\"}")[0]),
                "Content-Length that lies about a short body is refused");
        check("400".equals(send(api, "POST /api/say HTTP/1.1\r\nContent-Length: abc\r\n\r\n")[0]),
                "Content-Length that is not a number is refused");
        check("400".equals(send(api, "POST /api/say HTTP/1.1\r\nContent-Length: -1\r\n\r\n")[0]),
                "a negative Content-Length is refused");
        check("400".equals(send(api, "GET /api/state HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n")[0]),
                "the same header twice is malformed");
        check("400".equals(send(api, "GET / HTTP/2.0\r\nHost: tv\r\n\r\n")[0]),
                "a request line that is not HTTP/1.x is malformed");
        check("400".equals(send(api, "GET /api/state HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n")[0]),
                "a chunked body is refused: the phone always says how long its JSON is");
        StringBuilder head = new StringBuilder("GET / HTTP/1.1\r\nX-Pad: ");
        while (head.length() < 9000) head.append('y');
        check("400".equals(send(api, head.append("\r\n\r\n").toString())[0]), "a head over 8 KB is refused");
        check("400".equals(send(api, "nonsense\r\n\r\n")[0]), "a request line without three parts is malformed");
        check("200".equals(send(api, "GET /api/state HTTP/1.1\r\nAuthorization: Bearer " + token
                + "\r\nContent-Length: 2\r\n\r\n{}")[0]), "a GET that carries a body still works");
    }

    // ---- routing --------------------------------------------------------------------------------

    static void routing() throws Exception {
        Fake fake = new Fake();
        PhoneApi api = api(fake);
        String token = pair(api, fake);

        String[] page = send(api, get("/", null));
        check("200".equals(page[0]) && HTML.equals(page[1]), "GET / is the web app itself: " + page[0]);
        check(page[2].contains("Content-Type: text/html; charset=utf-8"), "the page is served as html");

        String[] manifest = send(api, get("/manifest.json", null));
        JSONObject m = new JSONObject(manifest[1]);
        check("200".equals(manifest[0]) && "星仪".equals(m.optString("name")), "the manifest names the app 星仪");
        check("standalone".equals(m.optString("display")), "the manifest asks for a standalone window");
        check("#0b0c0f".equals(m.optString("background_color")), "the manifest's background is the app's black");

        String[] icon = send(api, get("/icon.svg", null));
        check("200".equals(icon[0]) && icon[2].contains("Content-Type: image/svg+xml"), "the icon is served as svg");
        check(icon[1].contains("<circle") && icon[1].contains("#0b0c0f"), "the icon is the ring with a dot on black");

        check("404".equals(send(api, get("/nope", token))[0]), "an unknown path is 404");
        check("404".equals(send(api, get("/api/nope", token))[0]), "an unknown api path is 404");
        check("405".equals(send(api, get("/api/say", token))[0]), "GET on a POST endpoint is 405");
        check("405".equals(send(api, post("/api/state", token, "{}"))[0]), "POST on a GET endpoint is 405");
        check("405".equals(send(api, post("/", null, "{}"))[0]), "POST on the page is 405");
        check("405".equals(send(api, "PUT /api/state HTTP/1.1\r\nAuthorization: Bearer " + token + "\r\n\r\n")[0]),
                "PUT is 405, not 404: the path is there");
        check("404".equals(send(api, "PUT /api/nope HTTP/1.1\r\n\r\n")[0]), "PUT on an unknown path is 404");
        check("200".equals(send(api, get("/api/state?t=17", token))[0]), "a query string is not part of the path");

        String[] history = send(api, get("/api/history", token));
        check("200".equals(history[0]) && fake.historyDays == 2, "GET /api/history asks for today and yesterday: "
                + fake.historyDays);
        check(new JSONArray(history[1]).optJSONObject(0).optString("said").equals("放首歌"),
                "the history is the engine's array as it is");

        check("200".equals(send(api, post("/api/consent", token, "{\"approve\":true}"))[0]) && fake.approved,
                "POST /api/consent approves the waiting step");
        check("200".equals(send(api, post("/api/consent", token, "{\"approve\":false}"))[0]) && !fake.approved,
                "POST /api/consent declines it too");
        check(fake.approvals == 2, "each consent reached the engine once: " + fake.approvals);
        check("200".equals(send(api, post("/api/stop", token, "{}"))[0]) && fake.stopped, "POST /api/stop stops the task");
        check("200".equals(send(api, post("/api/stop", token, ""))[0]), "POST /api/stop needs no body");
    }

    // ---- auth -----------------------------------------------------------------------------------

    static void auth() throws Exception {
        Fake fake = new Fake();
        PhoneApi api = api(fake);
        String token = pair(api, fake);

        check("401".equals(send(api, get("/api/state", null))[0]), "no token is 401");
        check("401".equals(send(api, get("/api/state", "deadbeef"))[0]), "a token this TV never gave is 401");
        check("401".equals(send(api, post("/api/say", null, "{\"text\":\"你好\"}"))[0]), "saying something needs a token");
        check("401".equals(send(api, post("/api/stop", null, "{}"))[0]), "stopping needs a token");
        check("401".equals(send(api, get("/api/history", null))[0]), "the history needs a token");
        check("401".equals(send(api, "GET /api/state HTTP/1.1\r\nAuthorization: " + token + "\r\n\r\n")[0]),
                "a bare token without Bearer is 401");
        check("401".equals(send(api, "GET /api/state HTTP/1.1\r\nAuthorization: Basic " + token + "\r\n\r\n")[0]),
                "another scheme is 401");
        check("401".equals(send(api, "GET /api/state HTTP/1.1\r\nAuthorization: Bearer\r\n\r\n")[0]),
                "Bearer with nothing after it is 401");
        check("200".equals(send(api, "GET /api/state HTTP/1.1\r\nAuthorization: bearer " + token + "\r\n\r\n")[0]),
                "the scheme is read without minding its case");
        check("200".equals(send(api, get("/api/state", token))[0]), "the paired phone's token works");
        check("200".equals(send(api, post("/api/pair/start", null, "{}"))[0]),
                "pairing is what a phone without a token does: no token needed");

        long paired = fake.phones.getJSONObject(0).optLong("seen");
        int saves = fake.saves;
        fake.now += 5_000;
        send(api, get("/api/state", token));
        check(fake.saves == saves && fake.phones.getJSONObject(0).optLong("seen") == paired,
                "polling every 700 ms does not write the phone list every time");
        fake.now += 61_000;
        send(api, get("/api/state", token));
        check(fake.saves == saves + 1 && fake.phones.getJSONObject(0).optLong("seen") == paired + 66_000,
                "a minute later the phone is marked as seen and the list written back");
        fake.now += 1_000;
        send(api, get("/api/state", token));
        check(fake.saves == saves + 1, "and not again a second later: " + fake.saves);
    }

    // ---- pairing --------------------------------------------------------------------------------

    static void pairing() throws Exception {
        Fake fake = new Fake();
        PhoneApi api = api(fake);
        String[] started = send(api, post("/api/pair/start", null, "{}"));
        check("200".equals(started[0]), "the TV shows a pair code: " + started[0]);
        check(new JSONObject(started[1]).optInt("expires_in") == 120, "the code lives two minutes");
        check(fake.shown.matches("[0-9]{6}"), "the code on the TV is six digits: " + fake.shown);

        String[] wrong = send(api, post("/api/pair", null, "{\"code\":\"000000\"}"));
        check("403".equals(wrong[0]), "a wrong code is refused: " + wrong[0]);
        check(new JSONObject(wrong[1]).optString("error").contains("不正确"), "and says the code is wrong");

        String[] done = send(api, post("/api/pair", null, "{\"code\":\"" + fake.shown + "\",\"name\":\"小明的手机\"}"));
        JSONObject paired = new JSONObject(done[1]);
        String token = paired.optString("token");
        check("200".equals(done[0]) && token.matches("[0-9a-f]{64}"), "the code buys a 32-byte hex token: " + done[0]);
        check("小明的手机".equals(paired.optString("name")), "the phone's name comes back with the token");
        check(fake.phones.length() == 1 && fake.phones.getJSONObject(0).optLong("created") == fake.now,
                "the phone is remembered with when it paired");
        check(fake.phones.getJSONObject(0).optString("name").equals("小明的手机")
                && fake.phones.getJSONObject(0).has("seen"), "the saved phone carries its name and when it was seen");
        check(fake.saves == 1, "pairing writes the phone list once: " + fake.saves);

        check("400".equals(send(api, post("/api/pair", null, "{\"code\":\"" + fake.shown + "\"}"))[0]),
                "a code works once: the second try is refused");
        check("200".equals(send(api, get("/api/state", token))[0]), "the token from that pairing works");

        // a fresh code, expired by the clock
        send(api, post("/api/pair/start", null, "{}"));
        String code = fake.shown;
        fake.now += 119_000;
        check("403".equals(send(api, post("/api/pair", null, "{\"code\":\"111111\"}"))[0]),
                "at 119 s the code is still alive (a wrong one is still just wrong)");
        send(api, post("/api/pair/start", null, "{}"));
        code = fake.shown;
        fake.now += 121_000;
        String[] expired = send(api, post("/api/pair", null, "{\"code\":\"" + code + "\"}"));
        check("400".equals(expired[0]), "at 121 s the code is expired: " + expired[0]);
        check(new JSONObject(expired[1]).optString("error").contains("过期"), "and says so in words");

        // five wrong tries void it
        send(api, post("/api/pair/start", null, "{}"));
        code = fake.shown;
        for (int i = 0; i < 4; i++)
            check("403".equals(send(api, post("/api/pair", null, "{\"code\":\"999999\"}"))[0]),
                    "wrong try " + (i + 1) + " is refused");
        String[] fifth = send(api, post("/api/pair", null, "{\"code\":\"999999\"}"));
        check("403".equals(fifth[0]) && new JSONObject(fifth[1]).optString("error").contains("已用完"),
                "the fifth wrong try voids the code");
        check("400".equals(send(api, post("/api/pair", null, "{\"code\":\"" + code + "\"}"))[0]),
                "and the right code is refused after that");

        // wrong codes count across fresh codes: asking for a new one buys no new guesses (2026-11-09 review)
        Fake guesser = new Fake();
        PhoneApi guessed = api(guesser);
        for (int round = 0; round < 2; round++) {
            check("200".equals(send(guessed, post("/api/pair/start", null, "{}"))[0]), "fresh code " + (round + 1) + " is shown");
            String miss = "000000".equals(guesser.shown) ? "000001" : "000000";
            for (int i = 0; i < PhoneApi.PAIR_TRIES; i++) send(guessed, post("/api/pair", null, "{\"code\":\"" + miss + "\"}"));
        }
        String[] locked = send(guessed, post("/api/pair/start", null, "{}"));
        check("429".equals(locked[0]) && new JSONObject(locked[1]).optString("error").contains("十分钟"),
                "after 10 wrong codes in 10 minutes no fresh code is made: " + locked[0]);
        guesser.now += PhoneApi.LOCK_MS + 1;
        check("200".equals(send(guessed, post("/api/pair/start", null, "{}"))[0]), "ten minutes later pairing works again");
        check("200".equals(send(guessed, post("/api/pair", null, "{\"code\":\"" + guesser.shown + "\"}"))[0]),
                "and the right code pairs");

        // the screen is off
        fake.screen = false;
        String[] dark = send(api, post("/api/pair/start", null, "{}"));
        check("409".equals(dark[0]), "a code nobody can read is not made: " + dark[0]);
        check(new JSONObject(dark[1]).optString("error").contains("先打开电视屏幕"), "the phone is told to turn the screen on");
        check("400".equals(send(api, post("/api/pair", null, "{\"code\":\"123456\"}"))[0]),
                "and no code was left behind for a stranger to guess");
        fake.screen = true;

        // the phone list
        Fake many = new Fake();
        PhoneApi api2 = api(many);
        String first = pair(api2, many);
        for (int i = 0; i < 5; i++) { many.now += 1000; pair(api2, many); }
        check(many.phones.length() == 5, "at most five phones are kept: " + many.phones.length());
        check("401".equals(send(api2, get("/api/state", first))[0]), "the oldest phone is dropped when a sixth pairs");
        check("200".equals(send(api2, get("/api/state", pair(api2, many)))[0]), "the newest one works");

        Fake named = new Fake();
        PhoneApi api3 = api(named);
        send(api3, post("/api/pair/start", null, "{}"));
        String[] nameless = send(api3, post("/api/pair", null, "{\"code\":\"" + named.shown + "\"}"));
        check("手机".equals(new JSONObject(nameless[1]).optString("name")), "a phone without a name is 手机");
        send(api3, post("/api/pair/start", null, "{}"));
        check("400".equals(send(api3, post("/api/pair", null, "not json"))[0]), "a pair request that is not JSON is 400");
        StringBuilder longName = new StringBuilder();
        while (longName.length() < 40) longName.append('名');
        String[] cut = send(api3, post("/api/pair", null,
                "{\"code\":\"" + named.shown + "\",\"name\":\"" + longName + "\"}"));
        check(new JSONObject(cut[1]).optString("name").length() == 24, "a long name is cut: "
                + new JSONObject(cut[1]).optString("name").length());
    }

    // ---- what the phone says --------------------------------------------------------------------

    static void say() throws Exception {
        Fake fake = new Fake();
        PhoneApi api = api(fake);
        String token = pair(api, fake);

        String[] said = send(api, post("/api/say", token, "{\"text\":\"播放林俊杰的《不为谁而作的歌》\"}"));
        check("200".equals(said[0]), "a typed sentence goes in: " + said[0]);
        check("播放林俊杰的《不为谁而作的歌》".equals(fake.said), "exactly as it was typed: " + fake.said);
        check("started".equals(new JSONObject(said[1]).optString("request")), "the answer says how it was routed");
        check(fake.spoke, "without a speak flag the TV reads the answer out");

        send(api, post("/api/say", token, "{\"text\":\"  打开哔哩哔哩  \"}"));
        check("打开哔哩哔哩".equals(fake.said), "the sentence is trimmed: " + fake.said);
        send(api, post("/api/say", token, "{\"text\":\"别念出来\",\"speak\":false}"));
        check(!fake.spoke && "别念出来".equals(fake.said), "speak false is the quiet source");
        fake.routed = "queued";
        check("queued".equals(new JSONObject(send(api, post("/api/say", token, "{\"text\":\"等一下\"}"))[1]).optString("request")),
                "a question that waits its turn says queued");

        check("400".equals(send(api, post("/api/say", token, "{\"text\":\"\"}"))[0]), "an empty sentence is refused");
        check("400".equals(send(api, post("/api/say", token, "{\"text\":\"   \"}"))[0]), "a blank sentence is refused");
        check("400".equals(send(api, post("/api/say", token, "{}"))[0]), "a request without a sentence is refused");
        check("400".equals(send(api, post("/api/say", token, ""))[0]), "a request without a body is refused");
        check("400".equals(send(api, post("/api/say", token, "{\"text\":\"\\u0007响\"}"))[0]),
                "a control character in the sentence is refused");
        check("200".equals(send(api, post("/api/say", token, "{\"text\":\"第一行\\n第二行\"}"))[0]),
                "a newline (Shift+Enter) is a sentence like any other");

        StringBuilder text = new StringBuilder();
        while (text.length() < 500) text.append('字');
        check("200".equals(send(api, post("/api/say", token, "{\"text\":\"" + text + "\"}"))[0]),
                "500 characters go in");
        check("400".equals(send(api, post("/api/say", token, "{\"text\":\"" + text.append('字') + "\"}"))[0]),
                "501 characters are refused");
    }

    // ---- the quick chips ------------------------------------------------------------------------

    static void keys() throws Exception {
        Fake fake = new Fake();
        PhoneApi api = api(fake);
        String token = pair(api, fake);

        String[][] expected = {
                {"vol_up", "音量调大一点"}, {"vol_down", "音量调小一点"}, {"mute", "静音"}, {"play_pause", "暂停"},
                {"next", "下一首"}, {"prev", "上一首"}, {"home", "回到桌面"}, {"back", "返回"},
                {"screen_off", "关闭屏幕"}, {"screen_on", "亮屏"}};
        for (String[] key : expected) {
            fake.said = null;
            fake.spoke = true;
            String[] done = send(api, post("/api/key", token, "{\"key\":\"" + key[0] + "\"}"));
            check("200".equals(done[0]) && key[1].equals(fake.said) && !fake.spoke,
                    "the chip " + key[0] + " says 「" + key[1] + "」, quietly: " + fake.said);
            check(key[1].equals(new JSONObject(done[1]).optString("command")), "and the answer names the command it sent");
        }
        check(PhoneApi.KEYS.size() == expected.length, "no other keys are accepted: " + PhoneApi.KEYS.size());
        // 暂停/继续 resumes when nothing plays (it only ever paused, 2026-11-09 review)
        fake.playing = false;
        String[] resumed = send(api, post("/api/key", token, "{\"key\":\"play_pause\"}"));
        check("200".equals(resumed[0]) && "继续播放".equals(fake.said), "with nothing playing the chip resumes: " + fake.said);
        check(QuickCommand.match("继续播放", java.util.Collections.<String>emptySet()) != null, "「继续播放」 is a quick command too");
        fake.playing = true;
        for (String[] key : expected)
            check(QuickCommand.match(key[1], java.util.Collections.<String>emptySet()) != null,
                    "「" + key[1] + "」 is a quick command: no model round for a chip");
        check("screen_off".equals(QuickCommand.match("关闭屏幕", java.util.Collections.<String>emptySet()).tool)
                        && "wake_screen".equals(QuickCommand.match("亮屏", java.util.Collections.<String>emptySet()).tool),
                "关屏 and 亮屏 are the screen tools, not the power ones");
        check("400".equals(send(api, post("/api/key", token, "{\"key\":\"power\"}"))[0]), "an unknown key is refused");
        check("400".equals(send(api, post("/api/key", token, "{}"))[0]), "a request without a key is refused");
        check("400".equals(send(api, post("/api/key", token, ""))[0]), "a request without a body is refused");
    }

    // ---- the TV side and the page ---------------------------------------------------------------

    private static String source(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /** The Android side and the page cannot run here, so these read what they are made of — the wiring the phone
     * needs, checked before a build is ever attempted. */
    static void wiring() throws Exception {
        String server = source("src/xyz/erapi0neer/xingyi/agent/tv/PhoneServer.java");
        String engine = source("src/xyz/erapi0neer/xingyi/agent/tv/AgentEngine.java");
        String page = source("assets/agent/phone.html");

        check(server.contains("18780"), "the console listens on port 18780");
        check(server.contains("implements PhoneApi.Engine"), "the server is the engine the api routes to");
        check(server.contains("speak ? \"phone\" : \"phone-quiet\""), "the 电视朗读 switch picks the source");
        check(server.contains("phones.json"), "the paired phones are kept in the store's own file");
        check(server.contains("手机配对码") && server.contains("两分钟内在手机上输入"), "the TV shows the code as a hint card");
        check(server.contains("isInteractive()"), "a code is not made for a dark screen");
        check(server.contains("setSoTimeout"), "a phone that says nothing is dropped");
        check(engine.contains("PhoneServer.start(app, this)"), "the engine starts the console when it is created");
        check(engine.contains("\"phone-quiet\".equals(source)"), "the quiet phone source is answered on screen, not aloud");

        check(page.contains("/api/say") && page.contains("/api/state") && page.contains("/api/consent")
                && page.contains("/api/stop") && page.contains("/api/key") && page.contains("/api/pair"),
                "the page talks to every endpoint the phone needs");
        check(page.contains("localStorage"), "the token and the switch are kept on the phone");
        check(!page.contains("http://") && !page.contains("https://"), "the page loads nothing from the internet");
        check(page.contains("env(safe-area-inset-bottom)"), "the input bar clears the home indicator");
        check(page.contains("BUSY_MS = 700") && page.contains("IDLE_MS = 4000"),
                "it watches every 700 ms while a task runs, every 4 s when idle");
        check(page.contains("回答星仪的问题"), "the input says what it is answering");
        check(page.contains("好的") && page.contains("算了"), "the confirmation has both answers");
        check(page.contains("电视朗读"), "the switch is the one the brief asked for");
        check(page.contains("在电视上显示配对码"), "the pairing screen asks the TV for the code first");
    }
}
