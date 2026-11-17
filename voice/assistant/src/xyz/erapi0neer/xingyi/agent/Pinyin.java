package xyz.erapi0neer.xingyi.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How Chinese characters sound (pypinyin's readings, generated into PinyinData): XiaoAi's speech recognition often
 * writes a name with other characters of the same sound, so a stock name that is not found is searched for again by its
 * pinyin and only a name that really sounds the same is accepted (「质朴」 → zhipu → 智谱, 2026-11-07).
 */
public final class Pinyin {
    private Pinyin() { }

    private static Map<Character, String[]> table;

    /** PinyinData's 「<character><reading>[,<reading>…];」 run, parsed once. */
    private static synchronized Map<Character, String[]> table() {
        if (table == null) {
            StringBuilder all = new StringBuilder();
            for (String chunk : PinyinData.CHUNKS) all.append(chunk);
            String s = all.toString();
            Map<Character, String[]> m = new HashMap<>();
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i++);
                int end = s.indexOf(';', i);
                if (end < 0) break;
                m.put(c, s.substring(i, end).split(","));
                i = end + 1;
            }
            table = m;
        }
        return table;
    }

    /** Every reading of a Chinese character, toneless, lower case, most common first (「朴」→ pu, piao, po); empty for anything else. */
    public static List<String> readings(char c) {
        String[] r = table().get(c);
        return r == null ? Collections.<String>emptyList() : Collections.unmodifiableList(Arrays.asList(r));
    }

    /** Whether the text has a character that has readings. */
    public static boolean hasChinese(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) if (table().containsKey(s.charAt(i))) return true;
        return false;
    }

    /** What a pinyin search is given: each Chinese character's first reading, ASCII letters and digits in lower case,
     * everything else (spaces, punctuation, other scripts, characters without readings) left out.
     * 「质朴」→ "zhipu", 「MINI MAX」→ "minimax", 「B站」→ "bzhan". null → "". */
    public static String spell(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < length(s); i++) {
            char c = s.charAt(i);
            String[] r = table().get(c);
            if (r != null) b.append(r[0]);
            else if (ascii(c)) b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    /** The first letters of the same: 「质朴」→ "zp", 「贵州茅台」→ "gzmt", 「B站」→ "bz". null → "". */
    public static String initials(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < length(s); i++) {
            char c = s.charAt(i);
            String[] r = table().get(c);
            if (r != null) { if (!r[0].isEmpty()) b.append(r[0].charAt(0)); }
            else if (ascii(c)) b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    /** Same sound: keep the characters kept() keeps; the same number of them; at each position two Chinese characters
     * share at least one reading, anything else is equal ignoring case. 「质朴」/「智谱」 yes, 「支付」/「智谱」 no,
     * 「银行」/「银杭」 yes (行 has hang), 「毛台」/「茅台」 yes, 「质朴」/「智谱AI」 no (lengths differ). */
    public static boolean sameSound(String a, String b) {
        String x = kept(a), y = kept(b);
        if (x.isEmpty() || x.length() != y.length()) return false;
        for (int i = 0; i < x.length(); i++) if (!alike(x.charAt(i), y.charAt(i))) return false;
        return true;
    }

    /** Where in b a stretch sounds like a (same rule, a stretch of b's kept characters as long as a's): the index of its
     * first kept character, -1 when there is none. 「毛台」 in 「贵州茅台」 → 2; 「质朴」 in 「智谱」 → 0. */
    public static int soundIndex(String a, String b) {
        String x = kept(a), y = kept(b);
        if (x.isEmpty() || x.length() > y.length()) return -1;
        for (int i = 0; i + x.length() <= y.length(); i++) {
            boolean hit = true;
            for (int j = 0; j < x.length(); j++) if (!alike(x.charAt(j), y.charAt(i + j))) { hit = false; break; }
            if (hit) return i;
        }
        return -1;
    }

    /** sameSound on each character's first (most common) reading only: 「质朴」/「智谱」 yes (zhi pu); 「说的」/「月的」 no
     * (说 is shuo; yue is a rare reading); 「银行」/「银杭」 no (xing / hang). */
    public static boolean sameMainSound(String a, String b) {
        String x = kept(a), y = kept(b);
        if (x.isEmpty() || x.length() != y.length()) return false;
        for (int i = 0; i < x.length(); i++) if (!mainAlike(x.charAt(i), y.charAt(i))) return false;
        return true;
    }

    /** soundIndex on the first readings only. */
    public static int mainSoundIndex(String a, String b) {
        String x = kept(a), y = kept(b);
        if (x.isEmpty() || x.length() > y.length()) return -1;
        for (int i = 0; i + x.length() <= y.length(); i++) {
            boolean hit = true;
            for (int j = 0; j < x.length(); j++) if (!mainAlike(x.charAt(j), y.charAt(i + j))) { hit = false; break; }
            if (hit) return i;
        }
        return -1;
    }

    /** The characters that are compared: every Chinese character (a reading is not needed — one the table has none for
     * is compared as itself, so 玥 is 玥 and never 珺) and every ASCII letter or digit, in lower case, in order. */
    private static String kept(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < length(s); i++) {
            char c = s.charAt(i);
            // a name's rare character must not vanish: 王珺 compared as 王 alone sounds like every other 王
            if (table().containsKey(c) || (c >= 0x4E00 && c <= 0x9FFF)) b.append(c);
            else if (ascii(c)) b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    /** How many characters {@link #kept} keeps of a text (null → 0): the length the sound comparisons need. */
    static int keptLength(String s) {
        return kept(s).length();
    }

    /** Two kept characters sound the same: Chinese ones share a reading, the rest are equal ignoring case. */
    private static boolean alike(char a, char b) {
        List<String> ra = readings(a), rb = readings(b);
        if (ra.isEmpty() || rb.isEmpty()) return ra.isEmpty() && rb.isEmpty() && Character.toLowerCase(a) == Character.toLowerCase(b);
        for (String r : ra) if (rb.contains(r)) return true;
        return false;
    }

    /** alike on the first reading only: a rare reading (说's yue, 行's hang) never makes two words sound the same. */
    private static boolean mainAlike(char a, char b) {
        List<String> ra = readings(a), rb = readings(b);
        if (ra.isEmpty() || rb.isEmpty()) return ra.isEmpty() && rb.isEmpty() && Character.toLowerCase(a) == Character.toLowerCase(b);
        return !ra.get(0).isEmpty() && ra.get(0).equals(rb.get(0));
    }

    private static boolean ascii(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }
}
