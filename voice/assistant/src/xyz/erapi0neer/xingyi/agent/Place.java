package xyz.erapi0neer.xingyi.agent;

import org.json.JSONObject;

/**
 * Where the TV is, for questions that leave the place out (「明天天气怎么样」「附近有什么好吃的」): Xiaomi's own setting,
 * Settings.System mitv_location / mitv_user_location — {"province":"上海","city":"上海","district":"徐汇",…} — which the
 * TV's weather uses. Before 2026-11-06 星仪 did not know the city at all and the search guessed.
 */
public final class Place {
    private Place() { }

    /** 「上海徐汇」, 「浙江杭州西湖」, or null when the setting says nothing usable. The user's own choice wins. */
    public static String of(String userSet, String located) {
        String u = name(userSet, true);
        return u != null ? u : name(located, false);
    }

    /** The city alone (「杭州」), for looking the place up by name: the user's own choice first; null when none. */
    public static String city(String userSet, String located) {
        for (String json : new String[]{userSet(userSet) ? userSet : null, located}) {
            if (json == null || json.trim().isEmpty()) continue;
            try {
                String c = clean(new JSONObject(json).optString("city"));
                if (!c.isEmpty()) return c;
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** Whether the user chose the place themselves (mitv_user_location's isUserSet). */
    public static boolean userSet(String json) {
        if (json == null || json.trim().isEmpty()) return false;
        try { return new JSONObject(json).optBoolean("isUserSet", false); } catch (Exception e) { return false; }
    }

    static String name(String json, boolean mustBeUserSet) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            if (mustBeUserSet && !o.optBoolean("isUserSet", false)) return null;
            String province = clean(o.optString("province")), city = clean(o.optString("city")), district = clean(o.optString("district"));
            if (city.isEmpty()) return null;
            StringBuilder s = new StringBuilder();
            // 上海 is both the province and the city: once is enough
            if (!province.isEmpty() && !province.equals(city)) s.append(province);
            s.append(city);
            if (!district.isEmpty() && !district.equals(city)) s.append(district);
            return s.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String clean(String s) {
        String t = s == null ? "" : s.trim();
        return t.equals("null") ? "" : t.replaceAll("[\\s　]", "");
    }
}
