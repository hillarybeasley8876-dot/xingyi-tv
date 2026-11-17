package xyz.erapi0neer.xingyi.agent;

import java.util.Locale;

/**
 * The TV's own health in words, for tv_status (2026-11-06 overnight pass: 「电视怎么这么卡」 sent the model through five
 * rounds of settings pages before it gave general advice; with these it answers from facts in two).
 */
public final class DeviceFacts {
    private DeviceFacts() { }

    /** 「可用 1.3 GB / 共 2.0 GB」, with 「（内存紧张）」 when the system says so. */
    public static String memory(long available, long total, boolean low) {
        if (total <= 0) return "读不到";
        return "可用 " + size(available) + " / 共 " + size(total) + (low ? "（内存紧张）" : "");
    }

    /** 「剩余 5.2 GB / 共 16.0 GB」, with 「（快满了）」 under a tenth or under 1 GB. */
    public static String storage(long free, long total) {
        if (total <= 0) return "读不到";
        boolean full = free < total / 10 || free < 1024L * 1024 * 1024;
        return "剩余 " + size(free) + " / 共 " + size(total) + (full ? "（快满了）" : "");
    }

    /** 「2 天 3 小时」 since the TV last started (standby is not a restart on this TV). */
    public static String uptime(long ms) {
        long minutes = Math.max(0, ms) / 60_000L, days = minutes / 1440, hours = minutes % 1440 / 60;
        if (days > 0) return days + " 天 " + hours + " 小时";
        if (hours > 0) return hours + " 小时 " + minutes % 60 + " 分";
        return minutes % 60 + " 分钟";
    }

    /** 「信号好（-52 dBm），速率 433 Mbps」. */
    public static String wifiSignal(int rssi, int linkMbps) {
        if (rssi <= -127 || rssi >= 0) return "";
        String level = rssi >= -55 ? "好" : rssi >= -67 ? "一般" : rssi >= -75 ? "偏弱" : "很弱";
        return "信号" + level + "（" + rssi + " dBm）" + (linkMbps > 0 ? "，速率 " + linkMbps + " Mbps" : "");
    }

    public static String size(long bytes) {
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 1 ? String.format(Locale.ROOT, "%.1f GB", gb) : Math.round(bytes / (1024.0 * 1024)) + " MB";
    }
}
