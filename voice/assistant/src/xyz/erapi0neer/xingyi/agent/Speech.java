package xyz.erapi0neer.xingyi.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Turns model text into something XiaoAi can read aloud. */
public final class Speech {
    private static final Pattern THINK = Pattern.compile("(?is)<think(?:ing)?(?:\\s[^>]*)?>.*?(?:</think(?:ing)?>|\\z)");
    private static final Pattern FAILED = Pattern.compile("没能|未能|失败|无法|找不到|没有找到|没找到|不支持|做不到|出错|没成功|没有成功");
    private Speech() { }

    /** A tool's name the model wrote at the end of its text instead of calling it (2026-11-06: 「…连休9天。finish」). */
    private static final Pattern TOOL_TAIL = Pattern.compile("(?<=[。！？!?」）)])\\s*(?:finish|plan_update|ask_user|plan)\\s*(?:\\(\\))?\\s*$");

    /** Visible answer text: reasoning blocks removed, and a tool's name left at its end. */
    public static String visible(Object content) {
        if (!(content instanceof String)) return "";
        return TOOL_TAIL.matcher(THINK.matcher((String) content).replaceAll("").trim()).replaceAll("").trim();
    }

    /** A web address is never read out letter by letter: a markdown link keeps its words, a bare address goes, and so do
     * the brackets it leaves empty (「（来源：）」). 2026-11-06 overnight pass. */
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]\\n]{1,80})\\]\\((?:https?://|www\\.)[^)\\s]*\\)");
    private static final Pattern URL = Pattern.compile("(?:https?://|www\\.)[A-Za-z0-9._~:/?#\\[\\]@!$&'*+,;=%-]+");
    private static final Pattern EMPTY_BRACKETS = Pattern.compile("[（(]\\s*(?:来源|链接|网址|详见|见)?[：:]?\\s*[）)]");
    /** Emoji and pictographs: XiaoAi reads them as words or stumbles; they say nothing on a voice answer. */
    private static final Pattern EMOJI = Pattern.compile("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]");

    /** A clock time: hours to 23, two-digit minutes, not inside a longer number (scores 「3:2」 and ratios 「16:9」 have
     * one digit after the colon and are left alone). */
    private static final Pattern CLOCK = Pattern.compile("(?<![0-9:.])([01]?[0-9]|2[0-3]):([0-5][0-9])(?![0-9:])");
    private static final Pattern CLOCK_RANGE = Pattern.compile("([点分])\\s*[-–—~～]\\s*(?=[0-9])");

    /**
     * 「20:58」 as 「20 点 58 分」 and 「20:58–23:10」 as 「20 点 58 分到 23 点 10 分」 (2026-11-07: the contract says so, and a
     * model still wrote 「20:58 到 23:10」 when the tool had said 「晚上 8 点 58 分」; XiaoAi may read the colon 「比」).
     */
    static String clocks(String s) {
        java.util.regex.Matcher m = CLOCK.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            int h = Integer.parseInt(m.group(1)), min = Integer.parseInt(m.group(2));
            char before = m.start() > 0 ? s.charAt(m.start() - 1) : ' ';
            String lead = Character.isIdeographic(before) ? " " : "";
            m.appendReplacement(out, lead + h + " 点" + (min == 0 ? "" : " " + min + " 分"));
        }
        m.appendTail(out);
        // 「7 点 播放」: the space the time had before a word goes
        return CLOCK_RANGE.matcher(out.toString()).replaceAll("$1到 ").replaceAll("([0-9] [点分]) (?=[\\u4e00-\\u9fa5])", "$1");
    }

    /** Markdown and list layout removed, lines joined into sentences. */
    public static String clean(String text) {
        if (text == null) return "";
        text = EMPTY_BRACKETS.matcher(URL.matcher(MD_LINK.matcher(text).replaceAll("$1")).replaceAll("")).replaceAll("");
        text = EMOJI.matcher(text).replaceAll("");
        text = clocks(text);
        StringBuilder out = new StringBuilder();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim()
                    .replaceAll("^#{1,6}\\s*", "")
                    .replaceAll("^(?:[-*•·]|\\d{1,2}[.、)])\\s*", "")
                    .replace("**", "").replace("__", "").replace("`", "").replace("*", "").replace("#", "")
                    .trim();
            if (line.isEmpty()) continue;
            if (out.length() > 0) {
                char last = out.charAt(out.length() - 1);
                if ("。！？!?；;，,：:".indexOf(last) < 0) out.append(line.endsWith("：") || line.endsWith(":") ? "" : "；");
            }
            out.append(line);
        }
        return out.toString().replaceAll("\\s{2,}", " ").trim();
    }

    /** At most max characters, cut at the last sentence end that fits; without one, at the last clause, never inside a
     * word (2026-11-06: 「…第三 Gemini 4 Carb」 was read out, and the reading stopped there). */
    public static String spoken(String text, int max) {
        String s = clean(text);
        if (s.length() <= max) return s;
        int cut = -1;
        for (int i = 0; i < max; i++) if ("。！？!?；;".indexOf(s.charAt(i)) >= 0) cut = i;
        if (cut >= max / 3) return s.substring(0, cut + 1);
        int clause = -1;
        for (int i = 0; i < max; i++) if ("，,、：:".indexOf(s.charAt(i)) >= 0) clause = i;
        if (clause >= max / 3) return s.substring(0, clause) + "。";
        return s.substring(0, max - 1) + "…";
    }

    /** An earlier answer as recent_turns carries it: whole up to max, else cut like spoken() and marked with 「…」, so a
     * long answer from before does not ride along in full with every request for three hours (2026-11-06 overnight
     * pass: two 「详细讲讲」 answers were 1100 of the first message's characters). */
    public static String brief(String text, int max) {
        String all = clean(text), cut = spoken(text, max);
        return cut.length() >= all.length() ? all : cut.endsWith("…") ? cut : cut + "…";
    }

    /** The most a final answer reads aloud: about a minute of XiaoAi's voice. 90 characters cut a five-place ranking in the
     * middle of its third name (2026-11-06 「查一下目前大模型的榜单」). */
    public static final int ANSWER_MAX = 300;

    /** One page of a long answer: what the panel shows while XiaoAi reads it (about half a minute of voice). */
    public static final int PAGE_MAX = 130;

    /**
     * A long answer as pages for the panel and the voice, read one after the other (2026-11-06: an 865-character answer
     * was read to 300 characters and the panel never turned): whole sentences, at most max characters a page; a longer
     * sentence is cut at its last comma that fits, else at max.
     */
    public static java.util.List<String> pages(String text, int max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String s = clean(text);
        StringBuilder page = new StringBuilder();
        int from = 0;
        while (from < s.length()) {
            int stop = -1;
            for (int i = from; i < s.length(); i++) if ("。！？!?；;".indexOf(s.charAt(i)) >= 0) { stop = i; break; }
            String sentence = stop < 0 ? s.substring(from) : s.substring(from, stop + 1);
            from = stop < 0 ? s.length() : stop + 1;
            while (sentence.length() > max) {
                int cut = -1;
                for (int i = 0; i < max; i++) if ("，,、：:".indexOf(sentence.charAt(i)) >= 0) cut = i;
                int at = cut >= max / 3 ? cut + 1 : max;
                if (page.length() > 0) { out.add(page.toString().trim()); page.setLength(0); }
                out.add(sentence.substring(0, at).trim());
                sentence = sentence.substring(at);
            }
            if (page.length() + sentence.length() > max && page.length() > 0) { out.add(page.toString().trim()); page.setLength(0); }
            page.append(sentence);
        }
        if (page.toString().trim().length() > 0) out.add(page.toString().trim());
        return out;
    }

    /** The shortest first sentence worth starting to read on its own: 「好的。」 is not an answer, it is a cough. */
    private static final int EARLY_MIN_LETTERS = 8;

    /**
     * What may be read before the answer is finished, or null (2026-11-09: on the TV a 60–150 character answer was read
     * only once it was whole — first speech median 5.4 s, while the model had its first sentence in about a second):
     * two pages or more, the first page, as before; else the first whole sentence of clean(), when it really is a
     * sentence (at least 8 letters or digits) and the model has already begun the next one — a finished one-sentence
     * answer is read at finish as before. What comes back is a prefix of clean() of the finished answer, which is what
     * readOn and AgentLoop.end compare with startsWith.
     */
    public static String early(String soFar, int pageMax) {
        if (soFar == null) return null;
        java.util.List<String> pages = pages(soFar, pageMax);
        if (pages.size() >= 2) return pages.get(0);
        String s = clean(soFar);
        int stop = -1;
        for (int i = 0; i < s.length(); i++) if ("。！？!?".indexOf(s.charAt(i)) >= 0) { stop = i; break; }
        if (stop < 0) return null;
        String first = s.substring(0, stop + 1);
        if (first.length() > pageMax) return null; // longer than a page: pages() above takes it
        int letters = 0;
        for (int i = 0; i < first.length(); i++) if (Character.isLetterOrDigit(first.charAt(i))) letters++;
        if (letters < EARLY_MIN_LETTERS) return null;
        return s.substring(stop + 1).trim().isEmpty() ? null : first; // nothing after it yet: it may be the whole answer
    }

    /** A final answer as it is read aloud: whole, or up to ANSWER_MAX and then pointing at the screen for the rest. */
    public static String answer(String text) {
        String all = clean(text), said = spoken(text, ANSWER_MAX);
        return said.length() >= all.length() ? said : said + "后面的在屏幕上。";
    }

    /** How long each side of a cut must be before the later one may count as the same thing said again. */
    private static final int RESTATE_MIN = 20;
    /** How much of the request the later part has to open with for it to be that request answered a second time. */
    private static final int ASK_MIN = 4;
    /** How much of the later part's distinct bigrams the earlier one has to carry (0.55: 「病情持续或高烧不退及时就医」
     * against 「…病情持续加重或高烧不退，及时去医院。」 is not enough on its own, the opening question is). */
    private static final double SAME = 0.55;
    /** A later part that opens with the request still has to share this much with the earlier one: a subject said again
     * at the head of the next sentence (「林俊杰最近发了…。林俊杰最近还…」) is not the answer told twice. */
    private static final double SAME_AFTER_ASK = 0.3;

    /** A later part of an answer that says the earlier part again (2026-11-10 「感冒了吃什么好」 came back as two answers
     * in one, the second even opening with the question): the answer keeps the earlier part only. */
    public static String deduped(String reply, String said) {
        if (reply == null) return "";
        String s = reply.trim();
        String ask = letters(said);
        String head = ask.length() >= ASK_MIN ? ask.substring(0, ASK_MIN) : "";
        for (int i = 0; i < s.length(); i++) {
            if (STOPS.indexOf(s.charAt(i)) < 0) continue;
            String a = s.substring(0, i + 1), b = s.substring(i + 1);
            if (clean(a).length() < RESTATE_MIN || clean(b).length() < RESTATE_MIN) continue;
            if (echoes(a, b)) continue;
            if (!head.isEmpty() && letters(b).startsWith(head) && restates(letters(a), letters(b), SAME_AFTER_ASK)) return a.trim();
            if (restates(letters(a), letters(b))) return a.trim();
        }
        return s;
    }

    /** Whether the line after the cut says the line before it again: that is a list of like lines or a refrain (the
     * checks' 60-sentence essay, 「第一…第二…」), not the answer told a second time — cutting there would leave the
     * list half-said. */
    private static boolean echoes(String a, String b) {
        return restates(letters(lastLine(a)), letters(firstLine(b)));
    }

    /** The last sentence of s, without its stop. */
    private static String lastLine(String s) {
        int end = s.length();
        while (end > 0 && STOPS.indexOf(s.charAt(end - 1)) >= 0) end--;
        int start = end;
        while (start > 0 && STOPS.indexOf(s.charAt(start - 1)) < 0) start--;
        return s.substring(start, end);
    }

    /** The first sentence of s, without its stop. */
    private static String firstLine(String s) {
        for (int i = 0; i < s.length(); i++) if (STOPS.indexOf(s.charAt(i)) >= 0) return s.substring(0, i);
        return s;
    }

    /** Whether most of b's distinct character bigrams are already in a. */
    private static boolean restates(String a, String b) { return restates(a, b, SAME); }

    /** Whether at least the share {@code share} of b's distinct character bigrams are already in a. */
    private static boolean restates(String a, String b, double share) {
        if (b.length() < 2) return false;
        java.util.Set<String> bigrams = new java.util.LinkedHashSet<>();
        for (int i = 0; i + 1 < b.length(); i++) bigrams.add(b.substring(i, i + 2));
        int same = 0;
        for (String g : bigrams) if (a.contains(g)) same++;
        return same >= bigrams.size() * share;
    }

    /** Only what is said: punctuation, spaces and quotes go, so a repeat is seen through them. */
    private static String letters(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString();
    }

    /** A sentence that only goes on from the one before it (「…要不要我把音量调大？或者换一首？」). */
    private static final Pattern GOES_ON = Pattern.compile("^(?:还是|或者|或是|要么|不然)");
    /** A clause that only sets up the next one (「如果明天还下雨，要不要我提醒你？」「你要是想听，…」「想听原唱的话，…」: cutting
     * the offer after it would leave it hanging, 2026-11-09 review). */
    private static final Pattern IF = Pattern.compile("^(?:(?:你|您)?(?:如果|要是|若是?|假如|倘若|万一)|如有|如需|需要的话|有需要|想的话|感兴趣的话)|的话$");
    /** A question that offers more of 星仪 (「要不要我…」「需要我…吗」「要不要再来一首」「还想听别的吗」) ... — advice to the user
     * stays (「要不要去医院看看？」「要不要带件外套？」, 2026-11-09 review) */
    private static final Pattern MORE = Pattern.compile("要不要我|需不需要我|是否需要我|需要我|要我|想让我|让我|我(?:再|来|帮|给|为|继续)|帮你|给你|为你"
            + "|要不要(?:再|换|听|看|放|来|继续|接着|查)|还(?:要|想|需要)(?:再|我|听|看|放|查|来|别的|其他|什么|点|大|小|高|低|调)"
            + "|还有(?:什么|其他|别的)|再来|(?:接着|继续)(?:放|看|听|查|讲|说)");
    /** ... not one that asks what was meant (「你说的是不是《晴天》？」): that one stays, it is the answer's point. */
    private static final Pattern WHICH = Pattern.compile("哪|谁|是不是|是指|说的是|问的是|找的是|指的是|要的是|还是");
    /** The other reading the contract asks for, however it is phrased (「要是你想问的是…，再说一声」). */
    private static final Pattern OTHER_READING = Pattern.compile("说的是|问的是|找的是|指的是|要的是");
    /** A statement that offers more: 「如果需要，我可以…」「我可以再…」「随时叫我」「有什么想听的告诉我」. 「要是你问的是…，再说一声」
     * is the other reading the contract asks for, and stays. */
    private static final Pattern OFFER = Pattern.compile("^(?:(?:如果|要是|若是?|假如|倘若)(?:你|您)?(?:还|也)?(?:需要|想|愿意|有兴趣|感兴趣|喜欢)"
            + "|如有需要|如需|需要的话|有需要|想的话|感兴趣的话|(?:我|也)(?:还|也)?(?:可以|能)(?:再|继续|顺便|接着|另外)|需要我|要我|随时"
            + "|还?有(?:什么|其他|别的|任何))");
    /** What the offer would have me do, or the user tell me (「告诉我一声」「说一声」, not 「告诉我名字」). */
    private static final Pattern OFFER_DOES = Pattern.compile("我(?:还|也)?(?:可以|能|来|再|帮|随时)|让我|帮你|给你|为你"
            + "|(?:告诉|叫|喊|找|问|跟|和)我(?:说)?(?:一声|一下|就行|就好|就可以|吧)?$|说一声$");
    /** An offer said in one breath, subject 我, ending on its question mark (2026-11-10 「我今天好累」 ended
     * 「…要不我放点林俊杰给你听着，边听边缓一缓？」). 「要不你…」「要不要去医院看看？」 are advice to the user and stay. */
    private static final Pattern OFFER_ASKED = Pattern.compile("^(?:要不我|要不[^。！？]{0,6}我(?:给|帮)你|我(?:再|来)?给你|我帮你)");
    private static final Pattern ASKED = Pattern.compile("(?:[？?]|吗[。！!]?)$");
    /** An offer somebody else's words (「他刚才说“要不我给你放首歌？”」): not ours to drop. */
    private static final Pattern QUOTED = Pattern.compile("[“「『]");
    /** What opens an offer a clause before the words that do it (「要不这样，我给你放首歌？」). */
    private static final Pattern LEAD_IN = Pattern.compile("^(?:要不|不如)");
    private static final String STOPS = "。！？!?；;";
    private static final String CLOSERS = "”」』》）)\"'";

    /**
     * A final answer without the offer or the question it ends on (「…已经在放了，要不要再来一首？」「如果需要，我可以再查明天的。」):
     * to answer it the user has to wake XiaoAi again, and when they want more they say so. The contract asks for this;
     * this is the floor under it (on the TV, 11-05/06: 「需要我按具体人或单位再帮你核实吗？」「要我去看他的主页动态吗？」).
     * Only a tail with a real answer before it goes; an answer that is nothing but the question stays as it is.
     */
    public static String settled(String text) {
        String s = text == null ? "" : text.trim();
        for (int pass = 0; pass < 3; pass++) {
            int at = offerAt(s);
            if (at < 0) break;
            String rest = s.substring(0, at).replaceAll("[，,、：:；;\\s]+$", "");
            int said = 0;
            for (int i = 0; i < rest.length(); i++) if (Character.isLetterOrDigit(rest.charAt(i))) said++;
            if (said < 4) break;
            char end = rest.charAt(rest.length() - 1);
            boolean closed = "。！？!?".indexOf(end) >= 0
                    || CLOSERS.indexOf(end) >= 0 && rest.length() > 1 && "。！？!?".indexOf(rest.charAt(rest.length() - 2)) >= 0;
            s = closed ? rest : rest + "。";
        }
        return s;
    }

    /** Where s's offering tail begins, or -1. */
    private static int offerAt(String s) {
        List<int[]> sentences = spans(s, STOPS);
        if (sentences.isEmpty()) return -1;
        int first = sentences.size() - 1;
        while (first > 0 && GOES_ON.matcher(body(s, sentences.get(first))).find()) first--;
        int from = sentences.get(first)[0];
        String tail = s.substring(from);
        List<int[]> clauses = spans(tail, "，," + STOPS);
        int last = clauses.size() - 1;
        if (ASKED.matcher(tail.trim()).find()) {
            int k = last;
            while (k > 0 && GOES_ON.matcher(body(tail, clauses.get(k))).find()) k--;
            while (k > 0 && (IF.matcher(body(tail, clauses.get(k - 1))).find() || LEAD_IN.matcher(body(tail, clauses.get(k - 1))).find())) k--;
            String asked = tail.substring(clauses.get(k)[0]);
            if (MORE.matcher(asked).find() && !WHICH.matcher(asked).find()) return from + clauses.get(k)[0];
            return offers(tail, clauses, last, from);
        }
        for (int k = last; k >= 0; k--) {
            String b = body(tail, clauses.get(k));
            if (!OFFER.matcher(b).find()) continue;
            boolean does = OFFER_DOES.matcher(b).find()
                    || k < last && IF.matcher(b).find() && OFFER_DOES.matcher(body(tail, clauses.get(k + 1))).find();
            if (!does) continue;
            while (k > 0 && IF.matcher(body(tail, clauses.get(k - 1))).find()) k--;
            if (OTHER_READING.matcher(tail.substring(clauses.get(k)[0])).find()) return -1;
            return from + clauses.get(k)[0];
        }
        return -1;
    }

    /** Where the tail's offer said in one breath begins (「要不我…？」「我给你…吧？」), or -1: it has to end on its question
     * mark, and a question about which one, the other reading of the request and words inside quotes are left alone. */
    private static int offers(String tail, List<int[]> clauses, int last, int from) {
        for (int k = last; k >= 0; k--) {
            if (!OFFER_ASKED.matcher(tail.substring(clauses.get(k)[0]).trim()).find()) continue;
            // the offer may open a clause earlier (「要不这样，我给你放首歌？」): cutting inside it leaves it hanging
            while (k > 0 && (IF.matcher(body(tail, clauses.get(k - 1))).find() || LEAD_IN.matcher(body(tail, clauses.get(k - 1))).find())) k--;
            String rest = tail.substring(clauses.get(k)[0]).trim();
            if (!rest.endsWith("？") && !rest.endsWith("?")) continue;
            if (WHICH.matcher(rest).find() || OTHER_READING.matcher(rest).find() || QUOTED.matcher(rest).find()) continue;
            return from + clauses.get(k)[0];
        }
        return -1;
    }

    /** s cut after each run of stops, a closing quote and spaces going with the stop before them. */
    private static List<int[]> spans(String s, String stops) {
        List<int[]> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            int start = i;
            while (i < n && stops.indexOf(s.charAt(i)) < 0) i++;
            while (i < n && stops.indexOf(s.charAt(i)) >= 0) i++;
            while (i < n && (CLOSERS.indexOf(s.charAt(i)) >= 0 || Character.isWhitespace(s.charAt(i)))) i++;
            out.add(new int[] {start, i});
        }
        return out;
    }

    /** A span's words, without the stops and spaces around them. */
    private static String body(String s, int[] span) {
        int a = span[0], b = span[1];
        while (a < b && Character.isWhitespace(s.charAt(a))) a++;
        while (b > a && (STOPS.indexOf(s.charAt(b - 1)) >= 0 || "，,".indexOf(s.charAt(b - 1)) >= 0
                || CLOSERS.indexOf(s.charAt(b - 1)) >= 0 || Character.isWhitespace(s.charAt(b - 1)))) b--;
        return s.substring(a, b);
    }

    static boolean soundsFailed(String text) { return text != null && FAILED.matcher(text).find(); }
}
