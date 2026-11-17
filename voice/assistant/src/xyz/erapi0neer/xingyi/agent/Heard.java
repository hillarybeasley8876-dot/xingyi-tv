package xyz.erapi0neer.xingyi.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names XiaoAi's speech recognition wrote another way (2026-11-07): the words of a request that look like a name
 * an earlier turn said or answered — 「MAX」 for MINIMAX (11:56 asked for MINI MAX, 12:20 for MAX and got MediaAlpha),
 * 「TIBA」 for Tibo, 「质朴」 for 智谱. The recognition itself cannot be touched, so the model is told afterwards what
 * the request may have meant.
 */
public final class Heard {
    /** At most this many hints: the line is read by the model with every request. */
    static final int MAX_HINTS = 4;
    /** Everyday words that are never a name (「the app」, a web address). */
    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "the", "and", "for", "you", "app", "ok", "tv", "ai", "http", "https", "www", "com"));
    /** A Latin word: letters and digits, starting with a letter (a price or a code is not a name). */
    private static final Pattern LATIN = Pattern.compile("[A-Za-z][A-Za-z0-9]*");
    /** A name 星仪's answer spells right: 《…》, 「…」, “…”, "…", 2 to 12 characters. */
    private static final Pattern QUOTED = Pattern.compile(
            "《([^》\\s]{2,12})》|「([^」\\s]{2,12})」|“([^”\\s]{2,12})”|\"([^\"\\s]{2,12})\"");
    /** A quote's name is exactly what Quotes.speak writes: 2–8 Chinese characters at the start of the text or right after
     * one of 「：；，。、（」, then one space, a number, one space, the unit (「智谱 426.20 港元」, 「上证指数 3636.80 点」).
     * 「的历史峰值是1330.00港元」 is a price inside a sentence, not a name. */
    private static final Pattern QUOTE_NAME = Pattern.compile(
            "(?:^|[：；，。、（「])([一-鿿]{2,8}) [0-9][0-9.,]* (?:港元|美元|元|点)");

    private Heard() { }

    /** One word of the request and the earlier name it may be: 「MAX」, 「MINIMAX」, 「刚才」 or 「11月6日」. */
    public static final class Hint {
        public final String said, meant, when;

        public Hint(String said, String meant, String when) { this.said = said; this.meant = meant; this.when = when; }

        @Override public String toString() { return "「" + said + "」→「" + meant + "」(" + when + ")"; }
    }

    /**
     * What earlier turns said and answered, newest first, each {@code {said, replied, when}}: the words of
     * {@code utterance} that are not an earlier name as they are but look like one. At most {@link #MAX_HINTS}, the
     * newest turn's names first, one per word of the request.
     */
    public static List<Hint> hints(String utterance, List<String[]> earlier) {
        List<Hint> out = new ArrayList<>();
        if (utterance == null || utterance.isEmpty() || earlier == null) return out;
        List<Word> words = words(utterance);
        if (words.isEmpty()) return out;
        Set<String> said = new HashSet<>();
        for (Word w : words) said.add(w.flat);
        List<Turn> turns = turns(earlier);
        Set<String> echoed = new HashSet<>();
        for (Turn t : turns) echoed.addAll(t.echoed);
        List<Found> found = new ArrayList<>();
        for (Word w : words) {
            // the user repeated two or more whole words of an answer (「OPEN AI」 out of 「OPPO Open AI」): said right
            if (echoed.contains(w.flat)) continue;
            // a two-character slice of two words of the request (到香 in 「明天到香港」) is no name heard another way
            if (!w.alone) continue;
            for (int t = 0; t < turns.size(); t++) {
                String hit = turns.get(t).match(w);
                if (hit == null) continue;
                // the name is in the request too: the user said it right this time, so nothing to hint at
                if (!said.contains(flat(hit))) found.add(new Found(w, hit, t, turns.get(t).when));
                break;
            }
        }
        return keep(found);
    }

    /** 「「MAX」可能是刚才说过的「MINIMAX」；「TIBA」可能是11月6日说过的「Tibo」」, "" when there are none. */
    public static String line(List<Hint> hints) {
        if (hints == null || hints.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (Hint h : hints) {
            if (h == null) continue;
            if (b.length() > 0) b.append("；");
            b.append("「").append(h.said).append("」可能是").append(when(h.when)).append("说过的「").append(h.meant).append("」");
        }
        return b.toString();
    }

    private static String when(String w) {
        return w == null || w.trim().isEmpty() ? "刚才" : w.trim();
    }

    // ---- what the request says --------------------------------------------------------------------

    /** One candidate word of the request, and where it sits in it (words that overlap give one hint between them).
     * {@code alone} is whether a two-character Chinese word stands on its own in the request (see
     * {@link #standsAlone}); it is true for every other kind of word. */
    private static final class Word {
        final String text, flat;
        final int start, end;
        final boolean latin, alone;

        Word(String text, int start, int end, boolean latin, boolean alone) {
            this.text = text; this.start = start; this.end = end; this.latin = latin; this.alone = alone;
            this.flat = flat(text);
        }
    }

    /** A name an earlier turn used, with what the match needs worked out once (200 turns are read per request). */
    private static final class Name {
        final String text, flat;
        final boolean latin;
        final int kept;

        Name(String text) {
            this.text = text; this.flat = flat(text); this.latin = latinName(text); this.kept = Pinyin.keptLength(text);
        }
    }

    /** A word and the earlier name it may be, with the turn that name came from. */
    private static final class Found {
        final Word word;
        final String name, when;
        final int turn;

        Found(Word word, String name, int turn, String when) {
            this.word = word; this.name = name; this.turn = turn; this.when = when;
        }
    }

    /** The Latin runs (a run joined across single spaces is one word: 「MINI MAX」 → MINIMAX) and every run of 2 to 4
     * Chinese characters (「查询质朴和」 → 查询, 询质, 质朴, 朴和, 查询质, …). A two-character Chinese slice is only marked
     * {@code alone} when {@link #standsAlone} says it is a word of the request and not a piece of two of them: every
     * two-character name an answer spells (《稻香》, 「蔚来 5.12 美元」) would otherwise be found in any request that
     * happens to cross it (2026-11-09 review). */
    private static List<Word> words(String s) {
        List<Word> out = new ArrayList<>();
        runs(s, out);
        int i = 0;
        while (i < s.length()) {
            if (!han(s.charAt(i))) { i++; continue; }
            int j = i;
            while (j < s.length() && han(s.charAt(j))) j++;
            for (int a = i; a < j; a++)
                // longest first: 「贵州毛台」 is the whole name heard wrong, 「贵州毛」 only a part of it
                for (int n = Math.min(4, j - a); n >= 2; n--)
                    out.add(new Word(s.substring(a, a + n), a, a + n, false, n != 2 || standsAlone(s, a, a + n)));
            i = j;
        }
        return out;
    }

    /**
     * Whether the Chinese word {@code s[start, end)} stands on its own in the request, true only when both its sides
     * are open: nothing Chinese is glued to it, or what is glued to it joins words (的 in 「质朴的股价」), leads one
     * (「查一下毛台」) or follows one (「质朴股价」). A slice that crosses two words of the request (到香 in
     * 「明天到香港…」) is not a word, so it is never a name heard another way. Null or out of range gives false.
     */
    static boolean standsAlone(String s, int start, int end) {
        if (s == null || start < 0 || end > s.length() || start >= end) return false;
        return openLeft(s, start) && openRight(s, end);
    }

    /** Characters that join two words rather than being part of one (了 is not one: 「说了什么」 is one stretch). */
    private static final String JOINING = "的和跟与及或是吗呢吧啊呀嘛";
    /** What a request puts right before a name: 「查一下毛台」, 「查询质朴」, 「播放周杰伦」, 「那毛台呢」,
     * 「买点毛台」, 「放一首稻香」. */
    private static final String[] LEAD = {"一下", "查询", "查", "搜索", "搜", "放", "打开", "听", "看", "问",
            "关于", "还有", "对比", "比较", "那", "买点", "一首", "个"};
    /** What a request puts right after a name: 「质朴股价」, 「质朴最近为什么跌」, 「质朴港股」, 「质朴还是毛台」,
     * 「未来汽车股价」. Not a bare 港 (「到香港…」 would make 到香 a word) nor a bare 的. */
    private static final String[] FOLLOW = {"股", "怎么", "怎样", "今天", "最近", "最新", "现在", "多少",
            "为什么", "涨", "跌", "港股", "美股", "市值", "上市", "这只", "还是", "汽车"};

    private static boolean openLeft(String s, int start) {
        if (start == 0) return true;
        char before = s.charAt(start - 1);
        if (!han(before) || JOINING.indexOf(before) >= 0) return true;
        // checked where it sits: the head of the request is never copied (a request is read in every slice)
        for (String w : LEAD) if (start >= w.length() && s.startsWith(w, start - w.length())) return true;
        return false;
    }

    private static boolean openRight(String s, int end) {
        if (end == s.length()) return true;
        char after = s.charAt(end);
        if (!han(after) || JOINING.indexOf(after) >= 0) return true;
        for (String w : FOLLOW) if (s.startsWith(w, end)) return true;
        return false;
    }

    /** The single Latin words of a text (3+ characters, without the stop list), each with where it sits. */
    private static void latin(String s, List<Word> out) {
        if (s == null) return;
        Matcher m = LATIN.matcher(s);
        while (m.find()) add(out, s.substring(m.start(), m.end()), m.start(), m.end());
    }

    /**
     * The Latin runs of a text: every maximal stretch of Latin words one single space apart, as one word without the
     * spaces. A request is read like this — 「MINI MAX」 is MINIMAX said in two words, so MINI and MAX on their own are
     * not looked at — and an earlier answer gives both this run and the single words it is made of.
     */
    private static void runs(String s, List<Word> out) {
        if (s == null) return;
        Matcher m = LATIN.matcher(s);
        List<int[]> found = new ArrayList<>();
        while (m.find()) found.add(new int[]{m.start(), m.end()});
        int i = 0;
        while (i < found.size()) {
            int j = i, end = found.get(i)[1];
            // anything but a single space between two words is not one name
            while (j + 1 < found.size() && end + 1 == found.get(j + 1)[0] && s.charAt(end) == ' ') {
                j++;
                end = found.get(j)[1];
            }
            add(out, s.substring(found.get(i)[0], end).replace(" ", ""), found.get(i)[0], end);
            i = j + 1;
        }
    }

    /**
     * Every stretch of two or more whole Latin words an answer wrote, flat: 「OPPO Open AI」 gives OPPOOPEN, OPENAI and
     * OPPOOPENAI. A request word that is one of them is that phrase said back, so it is never a name heard another way
     * (「我想听的是OPEN AI最新的动向」 is OpenAI, not OPPOOpenAI); one word on its own (MAX out of 「MINI MAX」) still is.
     */
    private static void echoes(String s, Set<String> out) {
        if (s == null) return;
        Matcher m = LATIN.matcher(s);
        List<int[]> found = new ArrayList<>();
        while (m.find()) found.add(new int[]{m.start(), m.end()});
        for (int i = 0; i < found.size(); i++) {
            int end = found.get(i)[1];
            StringBuilder b = new StringBuilder(s.substring(found.get(i)[0], end));
            for (int j = i + 1; j < found.size() && end + 1 == found.get(j)[0] && s.charAt(end) == ' '; j++) {
                b.append(s, found.get(j)[0], found.get(j)[1]);
                end = found.get(j)[1];
                out.add(flat(b.toString()));
            }
        }
    }

    private static void add(List<Word> out, String w, int start, int end) {
        if (w.length() >= 3 && !STOP.contains(w.toLowerCase())) out.add(new Word(w, start, end, true, true));
    }

    // ---- what earlier turns named -----------------------------------------------------------------

    /** One earlier turn's names, 星仪's answer first: the answer spells them right, the user's words are the
     * recognition's (「TIBA」 gets 「Tibo」 from the answer, not 「TIBO」 from what was said). {@link #echoed} is every
     * stretch of two or more whole words the answer wrote, flat: a request word that is one of them is the answer's
     * own phrase repeated, not a name written another way. */
    private static final class Turn {
        final List<Name> names = new ArrayList<>();
        final Set<String> echoed = new HashSet<>();
        final String when;

        Turn(String said, String replied, String when) {
            this.when = when;
            List<Word> latin = new ArrayList<>();
            // 星仪's answer spells a name right, so a run it joins is a name as well (「MINI MAX」 → MINIMAX)
            Heard.runs(replied, latin);
            Heard.latin(replied, latin);
            for (Word w : latin) names.add(new Name(w.text));
            quoted(replied);
            quoteName(replied);
            Heard.echoes(replied, echoed);
            latin.clear();
            // what the user said is the recognition's own writing: only the single words are names there, and not one
            // the answer wrote another way (「TIBA这个人」 answered about Tibo: TIBA is the mishearing, 2026-11-09 review)
            Heard.latin(said, latin);
            for (Word w : latin) {
                boolean corrected = false;
                for (Name n : names) if (alike(w, n)) { corrected = true; break; }
                if (!corrected) names.add(new Name(w.text));
            }
        }

        /** The first name of this turn the word may be, null when none is. */
        String match(Word w) {
            // said exactly as a name of this turn: the user has it right, whatever else of the turn sounds like it
            // (智谱 said again after 「质朴」 was quoted and answered as 智谱, 2026-11-09 review)
            for (Name n : names) if (n.latin == w.latin && n.flat.equals(w.flat) && !insideLonger(n)) return null;
            for (Name n : names) if (alike(w, n)) return n.text;
            return null;
        }

        /** n is a word of a longer name of this turn (MAX of the run MINI MAX): that longer name is still a hint. */
        private boolean insideLonger(Name n) {
            for (Name m : names) if (m != n && m.latin == n.latin && m.flat.length() > n.flat.length() && m.flat.contains(n.flat)) return true;
            return false;
        }

        private void quoted(String s) {
            if (s == null) return;
            Matcher m = QUOTED.matcher(s);
            while (m.find()) for (int g = 1; g <= 4; g++) if (m.group(g) != null) names.add(new Name(m.group(g)));
        }

        /** 「智谱 426.20 港元」 → 智谱: only 星仪's answers quote a stock, and only there is the name written right. */
        private void quoteName(String s) {
            if (s == null) return;
            Matcher m = QUOTE_NAME.matcher(s);
            while (m.find()) names.add(new Name(m.group(1)));
        }
    }

    private static List<Turn> turns(List<String[]> earlier) {
        List<Turn> out = new ArrayList<>();
        for (String[] e : earlier) {
            if (e == null) continue;
            out.add(new Turn(e.length > 0 ? e[0] : null, e.length > 1 ? e[1] : null, e.length > 2 ? e[2] : null));
        }
        return out;
    }

    // ---- when a word is a name heard another way ---------------------------------------------------

    /** Whether the word may be that name: never when it is the name as it stands, nor when the name is literally in
     * the request (the user said it right this time), nor a Chinese word inside a Chinese name (茅台 in 贵州茅台). */
    private static boolean alike(Word w, Name n) {
        if (w.latin != n.latin || w.flat.equals(n.flat)) return false;
        if (w.latin) return latinLike(w.flat, n.flat);
        if (n.text.contains(w.text)) return false;
        if (n.kept == w.text.length()) return Pinyin.sameMainSound(w.text, n.text);
        // 毛台 is too short to be looked for inside a longer name; 贵州毛台 matches 贵州茅台 by sound anyway
        return w.text.length() >= 3 && n.kept > w.text.length() && Pinyin.mainSoundIndex(w.text, n.text) >= 0;
    }

    /** A part of a longer name (MAX in MINIMAX, MINI in MINIMAX) or one letter wrong (TIBA / TIBO). Both are already
     * lower case and without spaces (flat), so this only compares letters. */
    private static boolean latinLike(String a, String b) {
        if (b.length() >= 5 && a.length() >= 3 && (b.startsWith(a) || b.endsWith(a))) {
            // not a number cut short: CCTV1 is not the start of CCTV13 (2026-11-09 review)
            boolean start = b.startsWith(a);
            char edge = start ? a.charAt(a.length() - 1) : a.charAt(0), next = start ? b.charAt(a.length()) : b.charAt(b.length() - a.length() - 1);
            return !(Character.isDigit(edge) && Character.isDigit(next));
        }
        if (a.length() < 4 || a.length() != b.length()) return false;
        int wrong = 0;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) == b.charAt(i)) continue;
            // a digit is said on purpose: HDMI1 is not HDMI2, iPhone17 not iPhone16 (2026-11-09 review)
            if (Character.isDigit(a.charAt(i)) || Character.isDigit(b.charAt(i))) return false;
            wrong++;
        }
        return wrong == 1;
    }

    // ---- which hints are kept ---------------------------------------------------------------------

    /** One hint per word, words that overlap in the request give one between them (质朴 and 询质), the newest turn's
     * names first, at most {@link #MAX_HINTS}. */
    private static List<Hint> keep(List<Found> found) {
        List<Found> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Found f : found) {
            if (!seen.add(f.word.flat)) continue;
            boolean over = false;
            for (Found k : kept) if (f.word.start < k.word.end && k.word.start < f.word.end) { over = true; break; }
            if (!over) kept.add(f);
        }
        Collections.sort(kept, new Comparator<Found>() {
            @Override public int compare(Found a, Found b) { return a.turn - b.turn; }
        });
        List<Hint> out = new ArrayList<>();
        for (int i = 0; i < kept.size() && out.size() < MAX_HINTS; i++) {
            Found f = kept.get(i);
            out.add(new Hint(f.word.text, f.name, when(f.when)));
        }
        return out;
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** A name as it is compared: lower case, without the spaces the recognition put in it. */
    private static String flat(String s) {
        return s == null ? "" : s.replace(" ", "").toLowerCase();
    }

    private static boolean han(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private static boolean latinName(String name) {
        if (name.isEmpty()) return false;
        char c = name.charAt(0);
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
