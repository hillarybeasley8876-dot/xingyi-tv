package xyz.erapi0neer.xingyi;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A spoken/typed music request, parsed without any network access. */
public final class MusicIntent {
    public enum Kind { SONG, ARTIST, FAVORITES, PRIVATE_FM, DAILY, OPEN_APP, PAUSE, RESUME, NEXT, PREVIOUS }

    public final Kind kind;
    public final boolean netease;
    public final boolean explicitPlay;
    public final String content;
    public final String artist;
    public final List<String[]> splits;
    public final boolean wantsVersion;

    private static final List<String> FILLERS = constantList(
            "小爱同学", "小爱", "星仪", "你好", "请你", "麻烦你", "麻烦", "帮我", "给我", "帮忙", "我想要", "我要", "我想",
            "想要", "我们", "能不能", "可不可以", "可以", "请", "嘿", "喂", "哎", "能");

    private static final Pattern NETEASE = Pattern.compile(
            "(用|在|打开|去|到|上|从)?(网易云音乐|网易云|网抑云)(app|tv)?(里面|里边|里|上面|上|中|的)?");

    private static final List<String> CONNECTORS = constantList(
            "然后", "再", "并且", "并", "接着", "就", "帮我", "给我");

    private static final List<String> CONNECTORS_AND_FILLERS = concatenate(CONNECTORS, FILLERS);

    private static final List<String> VERBS = sortedVerbs();

    private static final List<String> QUANT = constantList(
            "一首", "一个", "一下", "一些", "首", "个", "些", "点");

    private static final List<String> PARTICLES = constantList(
            "好不好", "好吗", "可以吗", "谢谢", "吧", "啊", "呀", "哦", "呢", "嘛");

    private static final Set<String> PAUSE = constantSet(
            "暂停", "暂停播放", "暂停一下", "停止", "停止播放", "停", "停一下", "别放了", "不听了", "关掉音乐", "关闭音乐");

    private static final Set<String> RESUME = constantSet(
            "继续", "继续播放", "接着放", "接着播", "接着听", "恢复播放", "播放");

    private static final Set<String> NEXT = constantSet(
            "下一首", "下一曲", "下首", "切歌", "换一首", "换首歌", "换一首歌", "换歌");

    private static final Set<String> PREV = constantSet(
            "上一首", "上一曲", "上首");

    private static final Pattern FAV = Pattern.compile(
            "^(我|我的)?(喜欢|收藏|红心|最爱)的?(音乐|歌曲|歌单|歌|列表)?$");

    private static final Set<String> FM = constantSet(
            "私人fm", "私人电台", "私人漫游", "私人雷达", "心动模式", "随便听听", "随便放放", "随便", "随机", "随机播放",
            "音乐", "歌", "歌曲", "好听的歌", "好听的", "好听的音乐", "随便放点歌", "随便来点歌", "随便来点", "随便放点");

    private static final Set<String> DAILY = constantSet(
            "每日推荐", "日推", "今日推荐", "今天的推荐", "每日推荐歌单", "每日歌曲推荐", "推荐歌曲", "推荐的歌");

    private static final Pattern EXCLUDE = Pattern.compile(
            "音量|声音|亮度|屏幕|频道|电视剧|电影|视频|综艺|动画片|动漫|纪录片|节目|直播|mv|hdmi|信号源|设置|桌面|应用"
                    + "|第[0-9一二三四五六七八九十百]+集");

    private static final Pattern ARTIST = Pattern.compile(
            "^(.{1,20}?)唱?的(歌|歌曲|音乐|热门歌曲|热门歌|热歌|新歌|好歌|老歌|经典歌曲|经典老歌|代表作|成名曲|所有歌|全部歌曲|专辑)$");

    private static final Pattern VERSION = Pattern.compile(
            "live|现场|演唱会|remix|混音|伴奏|纯音乐|钢琴|吉他|cover|翻唱|翻自|原唱|dj|女声|男声|童声|加速|慢速|降调|升调"
                    + "|\\d(\\.\\d+)?x|片段|demo|小样|acoustic|不插电|instrumental|karaoke|串烧|改编",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** The app named with something to do to it, not a song (2026-11-08: 「回到桌面退出网易云」 played 《布拉格广场》). */
    private static final Pattern APP_ACTION = Pattern.compile(
            "退出|关闭|关掉|关了|关上|回到|返回|回桌面|桌面|主页|首页|离开|卸载|更新|升级|清理|清除|缓存|登录|登出|账号|设置|会员|充值|扫码|下载|安装");

    /** Left after 网易云 with no play verb, and no song's name (2026-11-09 review: 「关网易云」「回到网易云」「网易云小声一点」
     * 「网易云切到伴奏」 searched NetEase for 关, 回, 小声一点, 切到伴奏 and played what came up). */
    private static final Pattern NOT_A_TITLE = Pattern.compile(
            "切到|切成|切回|切换|调大|调小|调高|调低|大声|小声|静音|没声|歌词|伴奏|原唱|随心唱|k歌|卡拉ok|怎么|为什么|为啥|咋"
                    + "|太大|太小|太吵|卡了|卡住|关");

    private static final Set<String> STRONG = constantSet(
            "播放", "播放一首", "播一首", "播首", "放一首", "放首", "来一首", "来首", "来点", "来些", "听一首", "听首",
            "点一首", "点首", "点播", "唱一首", "唱首");

    private static final Set<String> PRONOUNS = constantSet(
            "我们", "你们", "他们", "她们", "自己", "大家", "咱们");

    private static final Pattern TITLE_HINT = Pattern.compile("《([^》]+)》");
    private static final Pattern QUOTE_MARKS = Pattern.compile("[《》「」『』“”\"‘’']");
    private static final Pattern PUNCTUATION = Pattern.compile("[，。！？、；：,!?;:…~～]+");
    // Python's \s, spelled out: Android rejects Pattern.UNICODE_CHARACTER_CLASS ("Unsupported flags: 256").
    private static final Pattern WHITESPACE = Pattern.compile(
            "[\\s\\x1c-\\x1f\\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]+");
    private static final Pattern FAV_TRIGGER = Pattern.compile("喜欢|收藏|红心|最爱");
    private static final Pattern TRAILING_CHANG_DE = Pattern.compile("唱?的$");
    private static final Pattern BY = Pattern.compile("^(.+?) by (.+)$");

    private static final Set<String> OPEN_APP_WORDS = constantSet(
            "", "打开", "启动", "进入", "开");

    private static final String SIZE_PREFIXES = "大小高低";

    private MusicIntent(Kind kind, boolean netease, boolean explicitPlay, String content, String artist,
                        List<String[]> splits, boolean wantsVersion) {
        this.kind = kind;
        this.netease = netease;
        this.explicitPlay = explicitPlay;
        this.content = content;
        this.artist = artist;
        this.splits = splits;
        this.wantsVersion = wantsVersion;
    }

    public static MusicIntent parse(String utterance) {
        if (utterance == null) {
            return null;
        }

        String s = strip(Normalizer.normalize(utterance, Normalizer.Form.NFKC));

        Matcher titleMatcher = TITLE_HINT.matcher(s);
        String titleHint = titleMatcher.find()
                ? strip(titleMatcher.group(1)).toLowerCase(Locale.ROOT)
                : null;

        s = QUOTE_MARKS.matcher(s).replaceAll("");
        s = PUNCTUATION.matcher(s).replaceAll(" ");
        s = WHITESPACE.matcher(s).replaceAll(" ");
        s = strip(s).toLowerCase(Locale.ROOT);

        String before = s;
        s = stripPrefix(s, FILLERS);
        int strippedLength = codePointLength(before) - codePointLength(s);
        String stripped = substringByCodePoints(before, 0, strippedLength);
        boolean wanted = stripped.contains("我想")
                || stripped.contains("我要")
                || stripped.contains("想要")
                || stripped.contains("帮我")
                || stripped.contains("给我");

        boolean netease = false;
        Matcher neteaseMatcher = NETEASE.matcher(s);
        if (neteaseMatcher.find()) {
            netease = true;
            int sLength = codePointLength(s);
            int matchStart = codePointIndexAt(s, neteaseMatcher.start());
            int matchEnd = codePointIndexAt(s, neteaseMatcher.end());
            String joined = substringByCodePoints(s, 0, matchStart)
                    + " "
                    + substringByCodePoints(s, matchEnd, sLength);
            s = WHITESPACE.matcher(joined).replaceAll(" ");
            s = strip(s);
            s = stripPrefix(s, CONNECTORS_AND_FILLERS);
        }

        s = strip(stripSuffix(s, PARTICLES));
        String whole = s.replace(" ", "");

        if (netease && OPEN_APP_WORDS.contains(whole)) {
            return intent(Kind.OPEN_APP, true);
        }

        if (PAUSE.contains(whole)) {
            return intent(Kind.PAUSE, netease);
        }
        if (RESUME.contains(whole)) {
            return intent(Kind.RESUME, netease);
        }
        if (NEXT.contains(whole)) {
            return intent(Kind.NEXT, netease);
        }
        if (PREV.contains(whole)) {
            return intent(Kind.PREVIOUS, netease);
        }

        String verb = null;
        for (String candidate : VERBS) {
            if (s.startsWith(candidate)) {
                verb = candidate;
                break;
            }
        }

        if (verb == null && !netease) {
            return null;
        }

        boolean explicitPlay = netease
                || STRONG.contains(verb)
                || (verb != null && wanted);

        String c;
        if (verb != null) {
            c = strip(substringByCodePoints(
                    s, codePointLength(verb), codePointLength(s)));
        } else {
            c = s;
        }

        for (String q : QUANT) {
            if (c.startsWith(q) && codePointLength(c) > codePointLength(q)) {
                c = strip(substringByCodePoints(
                        c, codePointLength(q), codePointLength(c)));
                break;
            }
        }

        c = strip(stripSuffix(c, PARTICLES));

        if (codePointLength(c) == 0) {
            return netease ? intent(Kind.RESUME, true) : null;
        }

        if (netease && verb == null && APP_ACTION.matcher(c).find()) {
            return null;
        }

        if (!netease
                && (SIZE_PREFIXES.indexOf(c.codePointAt(0)) >= 0
                || EXCLUDE.matcher(c).find())) {
            return null;
        }

        String cw = c.replace(" ", "");

        if (FAV.matcher(cw).matches() && FAV_TRIGGER.matcher(cw).find()) {
            return withExplicitPlay(intent(Kind.FAVORITES, netease), explicitPlay);
        }
        if (FM.contains(cw)) {
            return withExplicitPlay(intent(Kind.PRIVATE_FM, netease), explicitPlay);
        }
        if (DAILY.contains(cw)) {
            return withExplicitPlay(intent(Kind.DAILY, netease), explicitPlay);
        }

        // 网易云 named with no play verb: what is left has to look like a song's name, the rest is 星仪's
        if (netease && verb == null && (codePointLength(cw) < 2 || SIZE_PREFIXES.indexOf(cw.codePointAt(0)) >= 0
                || EXCLUDE.matcher(c).find() || NOT_A_TITLE.matcher(c).find())) {
            return null;
        }

        Matcher artistMatcher = ARTIST.matcher(c);
        String artist = artistMatcher.matches()
                ? strip(artistMatcher.group(1))
                : "";

        if (artist.contains("的")
                || PRONOUNS.contains(artist)
                || "我".equals(artist)
                || "你".equals(artist)
                || "他".equals(artist)
                || "她".equals(artist)) {
            artist = "";
        }

        List<String[]> splits = new ArrayList<String[]>();

        if (titleHint != null && codePointLength(titleHint) > 0 && c.contains(titleHint)) {
            int titleCharIndex = c.indexOf(titleHint);
            int titleCodePointIndex = codePointIndexAt(c, titleCharIndex);
            String prefix = substringByCodePoints(c, 0, titleCodePointIndex);
            String a = strip(TRAILING_CHANG_DE.matcher(prefix).replaceAll(""));
            if (codePointLength(a) < 2
                    || codePointLength(a) > 15
                    || PRONOUNS.contains(a)) {
                a = "";
            }
            splits.add(new String[]{a, titleHint});
        } else {
            int[] codePoints = c.codePoints().toArray();
            for (int i = 0; i < codePoints.length; i++) {
                if (codePoints[i] == '的' && splits.size() < 3) {
                    String a = strip(new String(codePoints, 0, i));
                    String t = strip(new String(
                            codePoints, i + 1, codePoints.length - i - 1));

                    if (a.endsWith("唱")) {
                        int aLength = codePointLength(a);
                        a = strip(substringByCodePoints(a, 0, aLength - 1));
                    }

                    if (codePointLength(a) >= 2
                            && codePointLength(a) <= 15
                            && codePointLength(t) > 0
                            && !PRONOUNS.contains(a)) {
                        splits.add(new String[]{a, t});
                    }
                }
            }

            Matcher byMatcher = BY.matcher(c);
            if (byMatcher.matches()) {
                splits.add(new String[]{
                        strip(byMatcher.group(2)),
                        strip(byMatcher.group(1))
                });
            }
        }

        if (splits.isEmpty() || !isFallbackSplit(splits.get(splits.size() - 1), c)) {
            splits.add(new String[]{"", c});
        }

        MusicIntent out = intent(
                artist.isEmpty() ? Kind.SONG : Kind.ARTIST,
                netease,
                c,
                splits,
                VERSION.matcher(c).find());

        return new MusicIntent(
                out.kind,
                out.netease,
                explicitPlay,
                out.content,
                artist,
                out.splits,
                out.wantsVersion);
    }

    private static MusicIntent intent(Kind kind, boolean netease) {
        return intent(kind, netease, "", null, false);
    }

    private static MusicIntent intent(Kind kind, boolean netease, String content,
                                      List<String[]> splits, boolean wantsVersion) {
        List<String[]> copiedSplits = splits == null
                ? new ArrayList<String[]>()
                : new ArrayList<String[]>(splits);
        return new MusicIntent(
                kind,
                netease,
                netease,
                content,
                "",
                Collections.unmodifiableList(copiedSplits),
                wantsVersion);
    }

    private static MusicIntent withExplicitPlay(MusicIntent source, boolean explicitPlay) {
        return new MusicIntent(
                source.kind,
                source.netease,
                explicitPlay,
                source.content,
                source.artist,
                source.splits,
                source.wantsVersion);
    }

    private static String stripPrefix(String s, List<String> words) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String word : words) {
                if (s.startsWith(word) && codePointLength(s) > codePointLength(word)) {
                    s = lstrip(substringByCodePoints(
                            s, codePointLength(word), codePointLength(s)));
                    changed = true;
                    break;
                }
            }
        }
        return s;
    }

    private static String stripSuffix(String s, List<String> words) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String word : words) {
                if (s.endsWith(word) && codePointLength(s) > codePointLength(word)) {
                    s = rstrip(substringByCodePoints(
                            s, 0, codePointLength(s) - codePointLength(word)));
                    changed = true;
                    break;
                }
            }
        }
        return s;
    }

    private static String strip(String value) {
        return strip(value, true, true);
    }

    private static String lstrip(String value) {
        return strip(value, true, false);
    }

    private static String rstrip(String value) {
        return strip(value, false, true);
    }

    private static String strip(String value, boolean fromLeft, boolean fromRight) {
        int[] codePoints = value.codePoints().toArray();
        int start = 0;
        int end = codePoints.length;

        if (fromLeft) {
            while (start < end && isSpace(codePoints[start])) {
                start++;
            }
        }
        if (fromRight) {
            while (end > start && isSpace(codePoints[end - 1])) {
                end--;
            }
        }

        return new String(codePoints, start, end - start);
    }

    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static int codePointIndexAt(String value, int charIndex) {
        return value.codePointCount(0, charIndex);
    }

    private static String substringByCodePoints(String value, int begin, int end) {
        return new String(value.codePoints().toArray(), begin, end - begin);
    }

    private static boolean isFallbackSplit(String[] split, String content) {
        return split != null
                && split.length == 2
                && "".equals(split[0])
                && content.equals(split[1]);
    }

    private static List<String> sortedVerbs() {
        List<String> verbs = new ArrayList<String>(Arrays.asList(
                "播放一首", "播放", "播一首", "播首", "播", "放一首", "放首", "放一个", "放个", "放点", "放些", "放",
                "来一首", "来首", "来一个", "来个", "来点", "来些", "听一首", "听首", "听听", "听一下", "听",
                "点一首", "点首", "点播", "唱一首", "唱首", "唱", "搜一下", "搜索", "搜", "找一下", "找一首", "找"));

        Collections.sort(verbs, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                int leftLength = codePointLength(left);
                int rightLength = codePointLength(right);
                if (leftLength == rightLength) {
                    return 0;
                }
                return leftLength > rightLength ? -1 : 1;
            }
        });

        return Collections.unmodifiableList(verbs);
    }

    private static List<String> concatenate(List<String> first, List<String> second) {
        List<String> result = new ArrayList<String>(first);
        result.addAll(second);
        return Collections.unmodifiableList(result);
    }

    private static List<String> constantList(String... values) {
        return Collections.unmodifiableList(
                new ArrayList<String>(Arrays.asList(values)));
    }

    private static Set<String> constantSet(String... values) {
        return new HashSet<String>(Arrays.asList(values));
    }
}
