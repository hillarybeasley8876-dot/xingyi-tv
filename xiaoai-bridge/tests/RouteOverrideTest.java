package xyz.erapi0neer.xiaoaibridge;

/**
 * The route override (2026-11-14): change the model in the private config.json, leave the routes and keys alone.
 * The documents here are the real shapes — the gateway outage that afternoon is why the feature exists (the gateway
 * said 429 for MiniMax-M3.1-Flash for hours; the user asked for MiniMax-M78 meanwhile).
 */
public final class RouteOverrideTest {
    private static int pass, fail;
    private static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("FAIL " + what); } }

    private static final String V2 = "{\"version\":2,"
            + "\"official\":{\"baseUrl\":\"https://api.minimax.io\",\"model\":\"MiniMax-M3.1-Flash\",\"apiKey\":\"sk-old\"},"
            + "\"gateway\":{\"baseUrl\":\"https://gateway.example.com\",\"model\":\"MiniMax-M3.1-Flash\",\"apiKey\":\"sk-gw\"}}";

    public static void main(String[] a) throws Exception {
        String out = RouteOverride.patch(V2, "MiniMax-M78");
        org.json.JSONObject j = new org.json.JSONObject(out);
        check("MiniMax-M78".equals(j.getJSONObject("official").getString("model")), "official model replaced");
        check("MiniMax-M78".equals(j.getJSONObject("gateway").getString("model")), "gateway model replaced");
        check("sk-old".equals(j.getJSONObject("official").getString("apiKey")), "official key untouched");
        check("sk-gw".equals(j.getJSONObject("gateway").getString("apiKey")), "gateway key untouched");
        check("https://gateway.example.com".equals(j.getJSONObject("gateway").getString("baseUrl")), "base url untouched");
        check(j.getInt("version") == 2, "version kept");
        check("MiniMax-M78".equals(new org.json.JSONObject(RouteOverride.patch(V2, "  MiniMax-M78  ")).getJSONObject("gateway").getString("model")),
                "surrounding spaces are trimmed, not kept");

        // only one route present: the other stays absent, never invented
        String half = "{\"version\":2,\"gateway\":{\"baseUrl\":\"https://gateway.example.com\",\"model\":\"x\",\"apiKey\":\"k\"}}";
        org.json.JSONObject h = new org.json.JSONObject(RouteOverride.patch(half, "MiniMax-M78"));
        check(h.getJSONObject("gateway").getString("model").equals("MiniMax-M78") && !h.has("official"), "a missing route is not invented");

        // the 0.4 single route
        String v04 = "{\"baseUrl\":\"https://gateway.example.com\",\"model\":\"gemini-3.8-flash\",\"apiKey\":\"k\"}";
        org.json.JSONObject o4 = new org.json.JSONObject(RouteOverride.patch(v04, "MiniMax-M78"));
        check("MiniMax-M78".equals(o4.getString("model")) && "k".equals(o4.getString("apiKey")), "the single-route file works");

        // unknown fields survive: a newer provisioner may write more than this code knows
        String extra = "{\"version\":3,\"note\":\"keep me\",\"gateway\":{\"baseUrl\":\"https://gateway.example.com\",\"model\":\"x\",\"apiKey\":\"k\",\"region\":\"cn\"}}";
        org.json.JSONObject e = new org.json.JSONObject(RouteOverride.patch(extra, "MiniMax-M78"));
        check("keep me".equals(e.getString("note")) && "cn".equals(e.getJSONObject("gateway").getString("region")),
                "fields this code does not know are kept");

        // what it refuses
        String[] bad = {null, "", "   ", "MiniMax/M78", "a b", "x".repeat(65)};
        // 广播的 extra 常带首尾空白，去掉以后再判："M78\n" 是合法的 M78，不是拒绝
        check(RouteOverride.patch(V2, "M78\n").contains("\"model\":\"M78\""), "surrounding whitespace is trimmed");
        for (String b : bad) {
            boolean threw = false;
            try { RouteOverride.patch(V2, b); } catch (IllegalArgumentException expected) { threw = true; }
            check(threw, "refused: [" + (b == null ? "null" : b) + "]");
        }
        for (String body : new String[]{"", "not json", "{}", "{\"version\":2}", "[]"}) {
            boolean threw = false;
            try { RouteOverride.patch(body, "MiniMax-M78"); } catch (IllegalArgumentException expected) { threw = true; }
            check(threw, "refused a document with no routes: [" + body + "]");
        }
        // 同一把模型名应用两次，结果一样（不会越改越乱）
        check(RouteOverride.patch(RouteOverride.patch(V2, "MiniMax-M78"), "MiniMax-M78")
                .equals(RouteOverride.patch(V2, "MiniMax-M78")), "patching twice is stable");

        // what a real model id looks like, and the shapes the ids are allowed to have
        for (String ok : new String[]{"MiniMax-M78", "MiniMax-M3.1-Flash", "gpt-5.4", "ab", "A1_b-2.c"})
            check(RouteOverride.plausible(ok), "plausible: " + ok);
        for (String no : new String[]{"", "a/b", "a b", "a\nb", "\"x\"", "模型"})
            check(!RouteOverride.plausible(no), "implausible: " + no);

        // 只改一条线路（2026-11-14 的实际情况）：网关换 M78，官方的 M3.1-Flash 必须原样留着——
        // M78 在官方账号上根本不存在，充上值以后官方那条会 404
        org.json.JSONObject gw = new org.json.JSONObject(RouteOverride.patch(V2, "MiniMax-M78", "gateway"));
        check("MiniMax-M78".equals(gw.getJSONObject("gateway").getString("model")), "gateway-only: gateway changed");
        check("MiniMax-M3.1-Flash".equals(gw.getJSONObject("official").getString("model")), "gateway-only: official untouched");
        org.json.JSONObject off = new org.json.JSONObject(RouteOverride.patch(V2, "MiniMax-M3.1-Flash", "official"));
        check("MiniMax-M3.1-Flash".equals(off.getJSONObject("official").getString("model")), "official-only: official changed");
        check("MiniMax-M3.1-Flash".equals(off.getJSONObject("gateway").getString("model")), "official-only: gateway untouched");
        check(new org.json.JSONObject(RouteOverride.patch(V2, "MiniMax-M78", "")).getJSONObject("gateway")
                .getString("model").equals("MiniMax-M78"), "an empty route means the gateway");

        System.out.println(fail == 0 ? "PASS " + pass + " passed, 0 failed (route override)"
                : "FAIL " + fail + " of " + (pass + fail) + " (route override)");
        if (fail != 0) System.exit(1);
    }
}
