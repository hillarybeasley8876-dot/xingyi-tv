package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.*;

/** What Bilibili's search results page means. On the 综合 tab the UP card for a searched name is followed by one
 * row of that UP's newest uploads, newest first (seen 2026-11-04 with 星辰亻旅行者: 10月18日, 10月18日, 10月12日).
 * The model kept doubting that row and wandered into the full upload list; the connector now states it. */
public final class BiliPage {
    private static final Pattern DATE = Pattern.compile(
            "\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}月\\d{1,2}日|\\d{1,2}-\\d{1,2}|\\d+(?:分钟|小时|天)前|昨天|前天|刚刚");
    private static final Pattern NUMBER = Pattern.compile("[\\d.]+万?|\\d{1,2}:\\d{2}(?::\\d{2})?");

    private BiliPage() { }

    /** {up_card_id, name, recent_uploads:[{id,title,date}], note} or null when the page has no such row. */
    public static JSONObject upUploads(ScreenDigest d, String keyword) throws JSONException {
        if (d == null || keyword == null || keyword.isEmpty()) return null;
        ScreenDigest.Element up = null;
        for (ScreenDigest.Element e : d.elements) {
            if (e.label.contains("粉丝") && (e.label.contains(keyword) || keyword.contains(first(e.label)))) { up = e; break; }
        }
        if (up == null) return null;
        List<ScreenDigest.Element> row = new ArrayList<>();
        int rowY = -1;
        boolean after = false;
        for (ScreenDigest.Element e : d.elements) {
            if (e == up) { after = true; continue; }
            if (!after || !"卡片".equals(e.role) || !DATE.matcher(e.label).find()) continue;
            int y = e.node.centerY();
            if (rowY < 0) rowY = y;
            else if (Math.abs(y - rowY) > Math.max(40, d.height / 12)) break;
            row.add(e);
            if (row.size() == 3) break;
        }
        if (row.isEmpty()) return null;
        Collections.sort(row, (a, b) -> Integer.compare(a.node.centerX(), b.node.centerX()));
        JSONArray list = new JSONArray();
        for (ScreenDigest.Element e : row) {
            Matcher m = DATE.matcher(e.label);
            list.put(new JSONObject().put("id", e.id).put("title", title(e.label)).put("date", m.find() ? m.group() : ""));
        }
        return new JSONObject().put("up_card_id", up.id).put("name", first(up.label)).put("recent_uploads", list)
                .put("note", "recent_uploads 是这个UP主最近的投稿，从新到旧排列，第一个最新；screen_click 它的 id 就会进入播放页并开始播放");
    }

    private static String first(String label) {
        int i = label.indexOf(" · ");
        return i < 0 ? label : label.substring(0, i);
    }

    /** The longest part that is not a count, duration or date. */
    private static String title(String label) {
        String best = "";
        for (String p : label.split(" · ")) {
            String s = p.trim();
            if (NUMBER.matcher(s).matches() || DATE.matcher(s).matches()) continue;
            if (s.length() > best.length()) best = s;
        }
        return best.isEmpty() ? label : best;
    }
}
