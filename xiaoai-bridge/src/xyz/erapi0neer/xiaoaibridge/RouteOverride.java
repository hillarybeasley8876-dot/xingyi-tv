package xyz.erapi0neer.xiaoaibridge;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Changes which model the agent runs on, without copying keys around: the private config.json that 星仪's runtime reads
 * ({@code GatewayConfig.loadRoutes}) keeps its routes and keys, and only the model names are replaced.
 *
 * <p>Why this exists (2026-11-14): the bridge is {@code debuggable="false"}, so {@code run-as} cannot write its private
 * files, and {@code provision_minimax.py} writes 星仪's own copy, which the bridge only reads when it has none
 * ({@code Identity.ensure}). Until now the only way to change the model was to reinstall and re-provision. The
 * gateway went out of capacity for MiniMax-M3.1-Flash that afternoon (429 for hours) and the user asked for
 * MiniMax-M78 meanwhile.
 *
 * <p>What it refuses: a name that is not a model id, and a config that has no routes at all. Anything else in the
 * file — keys, base urls, unknown fields — comes back untouched, so a bad edit cannot lose the credentials.
 */
final class RouteOverride {

    /** Model ids are short and ASCII; this is a shape check, not a list of what the gateway serves today. */
    private static final Set<Character> EXTRA = new HashSet<>(Arrays.asList('-', '_', '.'));

    private RouteOverride() { }

    /**
     * @param configJson the private config.json ({"version":2,"official":{…},"gateway":{…}}, or the 0.4 single route)
     * @param model      the model the route should use
     * @param route      "gateway", "official" or "all". 2026-11-14: the gateway was out of capacity for
     *                   MiniMax-M3.1-Flash while MiniMax-M78 answered there — but M78 does not exist on 星仪's official
     *                   MiniMax account, so switching "all" would leave a route that can only answer 404 after a
     *                   recharge. Only the route that is actually being changed is touched.
     * @return the same document with only that route's model replaced
     * @throws IllegalArgumentException when the model name, the route or the document cannot be used — nothing is guessed
     */
    static String patch(String configJson, String model, String route) {
        String name = model == null ? "" : model.trim();
        if (!plausible(name)) throw new IllegalArgumentException("不是有效的模型名：" + (name.isEmpty() ? "（空）" : name));
        String which = route == null || route.trim().isEmpty() ? "gateway" : route.trim();
        if (!"gateway".equals(which) && !"official".equals(which) && !"all".equals(which))
            throw new IllegalArgumentException("线路只能是 gateway / official / all：" + which);
        JSONObject root;
        try {
            root = new JSONObject(configJson);
        } catch (JSONException e) {
            throw new IllegalArgumentException("config.json 不是有效的 JSON");
        }
        try {
            if (root.has("official") || root.has("gateway")) {
                boolean any = false;
                for (String r : new String[]{"official", "gateway"}) {
                    if (!"all".equals(which) && !r.equals(which)) continue;
                    Object raw = root.opt(r);
                    if (raw instanceof JSONObject) {
                        ((JSONObject) raw).put("model", name);
                        any = true;
                    }
                }
                if (!any) throw new IllegalArgumentException("config.json 里没有「" + which + "」这条线路");
            } else if (root.has("baseUrl") || root.has("apiKey")) {
                root.put("model", name);                 // the 0.4 single route: {"baseUrl","model","apiKey"}
            } else {
                throw new IllegalArgumentException("config.json 里没有可用的线路");
            }
            return root.toString();
        } catch (JSONException e) {
            throw new IllegalArgumentException("改写模型名失败：" + e.getClass().getSimpleName());
        }
    }

    /** Every route gets the model (see {@link #patch(String, String, String)} for why that is usually the wrong call). */
    static String patch(String configJson, String model) { return patch(configJson, model, "all"); }

    /** Letters, digits and {@code - _ .}: what the gateway's model ids look like, and nothing that could be a path. */
    static boolean plausible(String model) {
        if (model == null || model.isEmpty() || model.length() > 64) return false;
        for (int i = 0; i < model.length(); i++) {
            char c = model.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || EXTRA.contains(c))) return false;
        }
        return true;
    }
}
