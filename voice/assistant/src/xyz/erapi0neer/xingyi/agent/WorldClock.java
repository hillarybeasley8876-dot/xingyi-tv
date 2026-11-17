package xyz.erapi0neer.xingyi.agent;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * 「纽约现在几点」「伦敦和北京时差几个小时」 (2026-11-06): the time elsewhere from the TV's own time zone data, daylight
 * saving included — a model works the offset out by heart and gets the summer wrong. No network.
 */
public final class WorldClock {
    private WorldClock() { }

    /** {what people say, IANA zone, how it is said back}. A country is its capital or its best-known city. */
    private static final String[][] PLACES = {
            {"纽约", "America/New_York", "纽约"}, {"华盛顿", "America/New_York", "华盛顿"}, {"波士顿", "America/New_York", "波士顿"},
            {"费城", "America/New_York", "费城"}, {"迈阿密", "America/New_York", "迈阿密"}, {"亚特兰大", "America/New_York", "亚特兰大"},
            {"多伦多", "America/Toronto", "多伦多"}, {"蒙特利尔", "America/Toronto", "蒙特利尔"}, {"芝加哥", "America/Chicago", "芝加哥"},
            {"休斯顿", "America/Chicago", "休斯顿"}, {"达拉斯", "America/Chicago", "达拉斯"}, {"丹佛", "America/Denver", "丹佛"},
            {"凤凰城", "America/Phoenix", "凤凰城"}, {"洛杉矶", "America/Los_Angeles", "洛杉矶"}, {"旧金山", "America/Los_Angeles", "旧金山"},
            {"硅谷", "America/Los_Angeles", "硅谷"}, {"西雅图", "America/Los_Angeles", "西雅图"}, {"拉斯维加斯", "America/Los_Angeles", "拉斯维加斯"},
            {"温哥华", "America/Vancouver", "温哥华"}, {"夏威夷", "Pacific/Honolulu", "夏威夷"}, {"檀香山", "Pacific/Honolulu", "檀香山"},
            {"墨西哥城", "America/Mexico_City", "墨西哥城"}, {"圣保罗", "America/Sao_Paulo", "圣保罗"}, {"里约", "America/Sao_Paulo", "里约热内卢"},
            {"里约热内卢", "America/Sao_Paulo", "里约热内卢"}, {"布宜诺斯艾利斯", "America/Argentina/Buenos_Aires", "布宜诺斯艾利斯"},
            {"伦敦", "Europe/London", "伦敦"}, {"曼彻斯特", "Europe/London", "曼彻斯特"}, {"都柏林", "Europe/Dublin", "都柏林"},
            {"巴黎", "Europe/Paris", "巴黎"}, {"柏林", "Europe/Berlin", "柏林"}, {"慕尼黑", "Europe/Berlin", "慕尼黑"},
            {"法兰克福", "Europe/Berlin", "法兰克福"}, {"罗马", "Europe/Rome", "罗马"}, {"米兰", "Europe/Rome", "米兰"},
            {"马德里", "Europe/Madrid", "马德里"}, {"巴塞罗那", "Europe/Madrid", "巴塞罗那"}, {"里斯本", "Europe/Lisbon", "里斯本"},
            {"阿姆斯特丹", "Europe/Amsterdam", "阿姆斯特丹"}, {"布鲁塞尔", "Europe/Brussels", "布鲁塞尔"}, {"维也纳", "Europe/Vienna", "维也纳"},
            {"苏黎世", "Europe/Zurich", "苏黎世"}, {"日内瓦", "Europe/Zurich", "日内瓦"}, {"布拉格", "Europe/Prague", "布拉格"},
            {"华沙", "Europe/Warsaw", "华沙"}, {"斯德哥尔摩", "Europe/Stockholm", "斯德哥尔摩"}, {"哥本哈根", "Europe/Copenhagen", "哥本哈根"},
            {"奥斯陆", "Europe/Oslo", "奥斯陆"}, {"赫尔辛基", "Europe/Helsinki", "赫尔辛基"}, {"雅典", "Europe/Athens", "雅典"},
            {"基辅", "Europe/Kiev", "基辅"}, {"莫斯科", "Europe/Moscow", "莫斯科"}, {"伊斯坦布尔", "Europe/Istanbul", "伊斯坦布尔"},
            {"开罗", "Africa/Cairo", "开罗"}, {"约翰内斯堡", "Africa/Johannesburg", "约翰内斯堡"}, {"内罗毕", "Africa/Nairobi", "内罗毕"},
            {"拉各斯", "Africa/Lagos", "拉各斯"}, {"迪拜", "Asia/Dubai", "迪拜"}, {"阿布扎比", "Asia/Dubai", "阿布扎比"},
            {"多哈", "Asia/Qatar", "多哈"}, {"利雅得", "Asia/Riyadh", "利雅得"}, {"德黑兰", "Asia/Tehran", "德黑兰"},
            {"耶路撒冷", "Asia/Jerusalem", "耶路撒冷"}, {"特拉维夫", "Asia/Jerusalem", "特拉维夫"}, {"新德里", "Asia/Kolkata", "新德里"},
            {"孟买", "Asia/Kolkata", "孟买"}, {"班加罗尔", "Asia/Kolkata", "班加罗尔"}, {"卡拉奇", "Asia/Karachi", "卡拉奇"},
            {"加德满都", "Asia/Kathmandu", "加德满都"}, {"达卡", "Asia/Dhaka", "达卡"}, {"曼谷", "Asia/Bangkok", "曼谷"},
            {"清迈", "Asia/Bangkok", "清迈"}, {"普吉岛", "Asia/Bangkok", "普吉岛"}, {"河内", "Asia/Ho_Chi_Minh", "河内"},
            {"胡志明市", "Asia/Ho_Chi_Minh", "胡志明市"}, {"金边", "Asia/Phnom_Penh", "金边"}, {"新加坡", "Asia/Singapore", "新加坡"},
            {"吉隆坡", "Asia/Kuala_Lumpur", "吉隆坡"}, {"雅加达", "Asia/Jakarta", "雅加达"}, {"巴厘岛", "Asia/Makassar", "巴厘岛"},
            {"马尼拉", "Asia/Manila", "马尼拉"}, {"首尔", "Asia/Seoul", "首尔"}, {"釜山", "Asia/Seoul", "釜山"}, {"东京", "Asia/Tokyo", "东京"},
            {"大阪", "Asia/Tokyo", "大阪"}, {"京都", "Asia/Tokyo", "京都"}, {"北海道", "Asia/Tokyo", "北海道"}, {"冲绳", "Asia/Tokyo", "冲绳"},
            {"台北", "Asia/Taipei", "台北"}, {"香港", "Asia/Hong_Kong", "香港"}, {"澳门", "Asia/Macau", "澳门"},
            {"乌鲁木齐", "Asia/Shanghai", "乌鲁木齐"}, {"悉尼", "Australia/Sydney", "悉尼"}, {"墨尔本", "Australia/Melbourne", "墨尔本"},
            {"布里斯班", "Australia/Brisbane", "布里斯班"}, {"珀斯", "Australia/Perth", "珀斯"}, {"阿德莱德", "Australia/Adelaide", "阿德莱德"},
            {"奥克兰", "Pacific/Auckland", "奥克兰"}, {"惠灵顿", "Pacific/Auckland", "惠灵顿"},
            {"美国", "America/New_York", "美国东部（纽约）"}, {"美东", "America/New_York", "美国东部（纽约）"},
            {"美西", "America/Los_Angeles", "美国西部（洛杉矶）"}, {"加拿大", "America/Toronto", "加拿大东部（多伦多）"},
            {"英国", "Europe/London", "英国"}, {"法国", "Europe/Paris", "法国"}, {"德国", "Europe/Berlin", "德国"}, {"意大利", "Europe/Rome", "意大利"},
            {"西班牙", "Europe/Madrid", "西班牙"}, {"荷兰", "Europe/Amsterdam", "荷兰"}, {"瑞士", "Europe/Zurich", "瑞士"},
            {"俄罗斯", "Europe/Moscow", "俄罗斯（莫斯科）"}, {"日本", "Asia/Tokyo", "日本"}, {"韩国", "Asia/Seoul", "韩国"},
            {"泰国", "Asia/Bangkok", "泰国"}, {"越南", "Asia/Ho_Chi_Minh", "越南"}, {"马来西亚", "Asia/Kuala_Lumpur", "马来西亚"},
            {"印尼", "Asia/Jakarta", "印尼（雅加达）"}, {"印度", "Asia/Kolkata", "印度"}, {"迪拜时间", "Asia/Dubai", "迪拜"},
            {"阿联酋", "Asia/Dubai", "阿联酋"}, {"埃及", "Africa/Cairo", "埃及"}, {"土耳其", "Europe/Istanbul", "土耳其"},
            {"澳大利亚", "Australia/Sydney", "澳大利亚东部（悉尼）"}, {"澳洲", "Australia/Sydney", "澳大利亚东部（悉尼）"},
            {"新西兰", "Pacific/Auckland", "新西兰"}, {"巴西", "America/Sao_Paulo", "巴西（圣保罗）"}, {"阿根廷", "America/Argentina/Buenos_Aires", "阿根廷"},
            {"墨西哥", "America/Mexico_City", "墨西哥城"}, {"以色列", "Asia/Jerusalem", "以色列"}, {"伊朗", "Asia/Tehran", "伊朗"},
            {"沙特", "Asia/Riyadh", "沙特"}, {"卡塔尔", "Asia/Qatar", "卡塔尔"}, {"南非", "Africa/Johannesburg", "南非"},
            {"新加坡时间", "Asia/Singapore", "新加坡"}, {"北京", "Asia/Shanghai", "北京"}};

    /** {zone, how it is said} for what was said, or null. */
    public static String[] place(String said) {
        String s = said == null ? "" : said.trim().replaceAll("[\\s　]", "");
        if (s.endsWith("市") && s.length() > 2) s = s.substring(0, s.length() - 1);
        for (String[] p : PLACES) if (p[0].equals(s)) return new String[]{p[1], p[2]};
        return null;
    }

    /** The names as a regex alternation, the longer first (布宜诺斯艾利斯 before 艾利斯…), for the quick commands. */
    public static String names() {
        List<String> all = new ArrayList<>();
        for (String[] p : PLACES) if (!"北京".equals(p[0]) && !all.contains(p[0])) all.add(p[0]);
        Collections.sort(all, (a, b) -> b.length() - a.length());
        return String.join("|", all);
    }

    /**
     * 「纽约现在是 11 月 5 日星期四中午 12 点 13 分，比北京晚 13 小时（现在是夏令时）」: the day is said when it is not the
     * same as here, the offset as it is now (summer time where there is one).
     */
    public static String now(String zoneId, String name, long now, TimeZone home) {
        TimeZone z = TimeZone.getTimeZone(zoneId);
        Calendar there = Calendar.getInstance(z), here = Calendar.getInstance(home);
        there.setTimeInMillis(now);
        here.setTimeInMillis(now);
        boolean sameDay = there.get(Calendar.YEAR) == here.get(Calendar.YEAR) && there.get(Calendar.DAY_OF_YEAR) == here.get(Calendar.DAY_OF_YEAR);
        String day = sameDay ? "" : " " + (there.get(Calendar.MONTH) + 1) + " 月 " + there.get(Calendar.DAY_OF_MONTH) + " 日星期"
                + "日一二三四五六".charAt(there.get(Calendar.DAY_OF_WEEK) - 1);
        int h = there.get(Calendar.HOUR_OF_DAY), m = there.get(Calendar.MINUTE);
        String part = h < 5 ? "凌晨" : h < 8 ? "早上" : h < 11 ? "上午" : h < 13 ? "中午" : h < 18 ? "下午" : "晚上";
        String clock = part + " " + (h > 12 ? h - 12 : h) + " 点" + (m == 0 ? "整" : " " + m + " 分");
        String diff = difference(z, home, now);
        boolean summer = z.inDaylightTime(new java.util.Date(now));
        return name + "现在是" + day + clock + "，" + diff + (summer ? "（现在是夏令时）" : "");
    }

    /** 「比北京晚 13 小时」「比北京早 1 小时」「比北京晚 2 小时 30 分」「和北京时间一样」. */
    static String difference(TimeZone there, TimeZone home, long now) {
        int minutes = (there.getOffset(now) - home.getOffset(now)) / 60_000;
        if (minutes == 0) return "和北京时间一样";
        int a = Math.abs(minutes), h = a / 60, m = a % 60;
        return "比北京" + (minutes < 0 ? "晚 " : "早 ") + (h > 0 ? h + " 小时" : "") + (m > 0 ? (h > 0 ? " " : "") + m + " 分" : "");
    }
}
