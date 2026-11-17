package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** The model-facing contract: system prompt plus tool schemas. assets/agent/contract.json is the only copy;
 * tests/agent/calibrate.mjs replays the same file against the real model. */
public final class Contract {
    public final String system;
    /** 应用经验 loaded on demand ("skills"; none in an older contract). */
    public final List<Skills.Skill> skills;
    private final String toolsJson;
    private final Map<String, JSONObject> params = new LinkedHashMap<>();
    /** Each tool's definition (its function object) and description, for find_tools. */
    private final Map<String, String> functions = new LinkedHashMap<>(), descriptions = new HashMap<>();

    /**
     * Tools sent only when a request may need them (2026-11-07): the public data tools (quotes, rates, scores, news…),
     * each with the words that call for it. Every model round carries the whole tool list; the 14 data tools added on
     * 11-06 made a round about 0.22 s slower (49 → 63 tools, A/B on the same questions). A tool left out still runs if
     * the model calls it (check() and the host know every tool), and the strong model gets them all.
     */
    private final Map<String, java.util.regex.Pattern> gates = new LinkedHashMap<>();

    public Contract(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        system = root.getString("system");
        skills = Collections.unmodifiableList(Skills.parse(root.optJSONArray("skills")));
        JSONArray tools = root.getJSONArray("tools");
        for (int i = 0; i < tools.length(); i++) {
            JSONObject f = tools.getJSONObject(i).getJSONObject("function");
            params.put(f.getString("name"), f.getJSONObject("parameters"));
            functions.put(f.getString("name"), f.toString());
            descriptions.put(f.getString("name"), f.optString("description"));
        }
        toolsJson = tools.toString();
        JSONObject g = root.optJSONObject("gates");
        if (g != null) for (Iterator<String> it = g.keys(); it.hasNext(); ) {
            String name = it.next();
            if (params.containsKey(name)) gates.put(name, java.util.regex.Pattern.compile(g.getString(name)));
        }
    }

    /** A fresh copy each call: the HTTP layer may hold on to what it sends. */
    public JSONArray tools() {
        try { return new JSONArray(toolsJson); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    /** The gated tools whose words are in the text (the request and what it follows up). */
    public Set<String> gatedIn(String text) {
        Set<String> in = new HashSet<>();
        String t = text == null ? "" : text;
        for (Map.Entry<String, java.util.regex.Pattern> g : gates.entrySet()) if (g.getValue().matcher(t).find()) in.add(g.getKey());
        return in;
    }

    public Set<String> gated() { return Collections.unmodifiableSet(gates.keySet()); }

    /** The tools for a request: every tool without a gate, and the gated ones in `in`. A fresh copy each call. */
    public JSONArray toolsFor(Set<String> in) {
        JSONArray all = tools(), out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject t = all.optJSONObject(i);
            String name = t == null ? "" : t.optJSONObject("function") == null ? "" : t.optJSONObject("function").optString("name");
            if (!gates.containsKey(name) || in.contains(name)) out.put(t);
        }
        return out;
    }

    /** The system prompt for a request: a rule line that names gated tools, none of them sent, is left out. */
    public String systemFor(Set<String> in) {
        if (gates.isEmpty()) return system;
        StringBuilder out = new StringBuilder();
        for (String line : system.split("\n", -1)) {
            boolean names = false, sent = false;
            for (String g : gates.keySet()) {
                if (!mentions(line, g)) continue;
                names = true;
                if (in.contains(g)) sent = true;
            }
            if (names && !sent) continue;
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString();
    }

    /**
     * The gated tools for what the model says it needs (find_tools, 2026-11-07, after Claude's tool search: a data tool
     * the gates left out is found by the model, instead of the request falling back to a web search). A tool whose gate
     * words are in the query comes first, then the ones sharing the most two-character words with its description; at
     * most max, none when nothing matches.
     */
    public List<String> search(String query, int max) {
        String q = query == null ? "" : query.trim();
        List<Object[]> scored = new ArrayList<>();
        for (Map.Entry<String, java.util.regex.Pattern> g : gates.entrySet()) {
            int score = q.isEmpty() ? 0 : g.getValue().matcher(q).find() ? 100 : 0;
            String d = descriptions.containsKey(g.getKey()) ? descriptions.get(g.getKey()) : "";
            for (int i = 0; i + 2 <= q.length(); i++) {
                String w = q.substring(i, i + 2);
                if (Character.isWhitespace(w.charAt(0)) || Character.isWhitespace(w.charAt(1))) continue;
                if (d.contains(w)) score++;
            }
            if (score > 0) scored.add(new Object[]{g.getKey(), score});
        }
        Collections.sort(scored, (a, b) -> (Integer) b[1] - (Integer) a[1]);
        List<String> out = new ArrayList<>();
        for (Object[] s : scored) if (out.size() < max) out.add((String) s[0]);
        return out;
    }

    /** A tool's definition (its function object, a fresh copy), or null. */
    public JSONObject function(String name) {
        try { return functions.containsKey(name) ? new JSONObject(functions.get(name)) : null; }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    /** A tool's description up to its first full stop: what find_tools lists when nothing matched. */
    public String summary(String name) {
        String d = descriptions.containsKey(name) ? descriptions.get(name) : "";
        int stop = d.indexOf('：') > 0 && d.indexOf('：') < 40 ? d.indexOf('：') : d.indexOf('。');
        return stop > 0 ? d.substring(0, stop) : d;
    }

    /** The rule lines of the system prompt that name these tools (a request that did not send them lacks the lines). */
    public List<String> linesFor(Collection<String> names) {
        List<String> out = new ArrayList<>();
        for (String line : system.split("\n")) for (String n : names) if (mentions(line, n)) { out.add(line.trim()); break; }
        return out;
    }

    /** name as a word of its own: 「news」 in 「news」 but not in 「newsletter」. */
    static boolean mentions(String line, String name) {
        for (int at = line.indexOf(name); at >= 0; at = line.indexOf(name, at + 1)) {
            boolean before = at == 0 || !word(line.charAt(at - 1)), after = at + name.length() >= line.length() || !word(line.charAt(at + name.length()));
            if (before && after) return true;
        }
        return false;
    }

    private static boolean word(char c) { return Character.isLetterOrDigit(c) && c < 128 || c == '_'; }

    public Set<String> names() { return Collections.unmodifiableSet(params.keySet()); }

    /** Checks model arguments against the schema and returns a clean copy: known keys only,
     * integers as int, enums enforced. The message of the exception is shown to the model. */
    public JSONObject check(String name, JSONObject args) {
        JSONObject schema = params.get(name);
        if (schema == null) throw new IllegalArgumentException("没有这个工具：" + name);
        JSONObject props = schema.optJSONObject("properties");
        JSONObject out = new JSONObject();
        try {
            JSONArray required = schema.optJSONArray("required");
            Set<String> needed = new LinkedHashSet<>();
            if (required != null) for (int i = 0; i < required.length(); i++) needed.add(required.getString(i));
            if (props != null) {
                for (Iterator<String> it = props.keys(); it.hasNext(); ) {
                    String key = it.next();
                    if (!args.has(key) || args.isNull(key)) continue;
                    // an optional field sent empty is a field not sent (2026-11-07: netease_play {"artist":"林俊杰","title":""}
                    // came back 「title 不能为空」 and the model gave up on the singer's songs)
                    Object raw = args.get(key);
                    if (raw instanceof String && ((String) raw).trim().isEmpty() && !needed.contains(key)) continue;
                    out.put(key, value(key, props.getJSONObject(key), raw));
                }
            }
            for (String key : needed) if (!out.has(key)) throw new IllegalArgumentException("缺少参数 " + key);
        } catch (JSONException e) {
            throw new IllegalArgumentException("参数格式不对");
        }
        return out;
    }

    private static Object value(String key, JSONObject spec, Object raw) throws JSONException {
        String type = spec.optString("type");
        Object v;
        switch (type) {
            case "string":
                if (raw instanceof Number || raw instanceof Boolean) raw = String.valueOf(raw);
                // {"text": "…"} where a string belongs: the text (2026-11-07: plan's steps came as [{"text": …}] three
                // times, each refused, 4 rounds lost before the model sent plain strings)
                if (raw instanceof JSONObject && ((JSONObject) raw).length() == 1) {
                    Object only = ((JSONObject) raw).opt(((JSONObject) raw).keys().next());
                    if (only instanceof String) raw = only;
                }
                if (!(raw instanceof String)) throw new IllegalArgumentException(key + " 应为文字");
                String s = ((String) raw).trim();
                if (s.isEmpty()) throw new IllegalArgumentException(key + " 不能为空");
                if (s.length() > 500) throw new IllegalArgumentException(key + " 太长");
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c < 0x20 && c != '\n') throw new IllegalArgumentException(key + " 含有控制字符");
                }
                v = s;
                break;
            case "integer":
                if (raw instanceof String) {
                    try { raw = Double.parseDouble(((String) raw).trim()); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException(key + " 应为整数"); }
                }
                if (!(raw instanceof Number)) throw new IllegalArgumentException(key + " 应为整数");
                double d = ((Number) raw).doubleValue();
                if (d != Math.rint(d) || Math.abs(d) > 1_000_000) throw new IllegalArgumentException(key + " 应为整数");
                v = (int) d;
                break;
            case "boolean":
                if ("true".equals(raw) || "false".equals(raw)) raw = Boolean.valueOf((String) raw);
                if (!(raw instanceof Boolean)) throw new IllegalArgumentException(key + " 应为 true 或 false");
                v = raw;
                break;
            case "array":
                // a list sent as one string: a JSON list in a string, or items between commas (「打开 Orrery，找退出入口，确认」)
                if (raw instanceof String) {
                    String text = ((String) raw).trim();
                    JSONArray parsed = null;
                    if (text.startsWith("[")) {
                        try { parsed = new JSONArray(text); } catch (JSONException notJson) { parsed = null; }
                    }
                    if (parsed == null) {
                        parsed = new JSONArray();
                        for (String part : text.split("[，,；;、\n]+")) if (!part.trim().isEmpty()) parsed.put(part.trim());
                    }
                    raw = parsed;
                }
                if (!(raw instanceof JSONArray)) throw new IllegalArgumentException(key + " 应为列表");
                JSONArray in = (JSONArray) raw, list = new JSONArray();
                if (in.length() > 20) throw new IllegalArgumentException(key + " 最多 20 项");
                JSONObject item = spec.optJSONObject("items");
                for (int i = 0; i < in.length(); i++) list.put(item == null ? in.get(i) : value(key, item, in.get(i)));
                v = list;
                break;
            default:
                v = raw;
        }
        JSONArray allowed = spec.optJSONArray("enum");
        if (allowed != null) {
            boolean ok = false;
            for (int i = 0; i < allowed.length(); i++) if (allowed.get(i).equals(v)) ok = true;
            if (!ok) throw new IllegalArgumentException(key + " 只能是 " + allowed.join("/").replace("\"", ""));
        }
        return v;
    }
}
