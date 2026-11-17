package xyz.erapi0neer.xingyi.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 腾讯视频自己的搜索（2026-11-10）：一次 POST 就知道一部片子有没有、在哪能看、封面编号是多少。
 *
 * <p>为什么要有：云视听极光（腾讯视频的电视版）的页面都是自己画的，Eyes 读不出元素，只能看截图按方向键——
 * 10:45 的「我想看狂飙」在里面逛了两分钟什么也没找到（而狂飙根本不在腾讯视频，是爱奇艺的）。腾讯视频网站自己的搜索
 * 直接说：这个名字有哪些片子、封面编号、类型、年份、集数，以及每一部在哪个平台放（qq / qiyi / bilibili / youku…）。
 * 有了封面编号就能用 {@code tenvideo2://?action=1|7&cover_id=} 一步进详情页或全屏播放（实测冷启 2.5 秒、热 0.6 秒）。
 *
 * <p>这个类是纯的：只解析一段已经取回来的 JSON，不联网（取回复在 {@link Feeds#httpsPost()}）。
 */
public final class TencentVideo {
    private TencentVideo() { }

    /** 腾讯视频网站的搜索接口（v.qq.com 自己的页面用的就是这个）。 */
    public static final String SEARCH =
            "https://pbaccess.video.qq.com/trpc.videosearch.mobile_search.MultiTerminalSearch/MbSearch?vplatform=2";

    public static final String FOUND = "FOUND", ELSEWHERE = "ELSEWHERE", NONE = "NONE";

    /** 封面编号：只有能拼成链接的才算一部片子（{@link #detailLink} 同样只收这个形状）。 */
    private static final Pattern CID = Pattern.compile("[A-Za-z0-9]{6,32}");

    /** 剩下的是第几季/第几部：第十季、第2季、第9季、2、·大湾区篇；后面还跟着别的字（第一季特别版）不算。 */
    private static final Pattern SEASON = Pattern.compile("^(?:第[0-9一二三四五六七八九十百千零两]+[季部]|[0-9]+|[·・].*)$");

    /** 名字后面跟的一个版本说明：（美版）、[卫视版]、（TV版）、【导演剪辑版】。 */
    private static final Pattern EDITION = Pattern.compile(
            "^(?:\\([^()]*\\)|\\[[^\\[\\]]*\\]|（[^（）]*）|［[^［］]*］|【[^【】]*】)$");

    /** 简称只在这些类型里找：其余（纪录片、短剧…）多半是蹭名字的解读视频。 */
    private static final Set<String> ALIAS_TYPES = new HashSet<>(java.util.Arrays.asList("电视剧", "电影", "综艺", "动漫", "少儿"));

    /** 这些类型除非用户点名要，否则不当成一部能看的片子（搜索结果里全是蹭名字的短剧和原创剪辑）。 */
    private static final Set<String> UNLESS_ASKED = new HashSet<>(java.util.Arrays.asList("短剧", "原创"));

    /** 一部片子。 */
    public static final class Show {
        public final String cid;
        /** 片名，&lt;em&gt; 标记已经去掉。 */
        public final String title;
        /** typeName：电视剧 / 电影 / 综艺 / 动漫 / 纪录片… */
        public final String type;
        /** 上映年份，0 是没有写。 */
        public final int year;
        /** 集数：在腾讯视频放的取腾讯那一栏，只在外站放的取外站第一栏；0 是没有写。 */
        public final int episodes;
        /** 在不在腾讯视频（playSites 里有 qq）。 */
        public final boolean tencent;
        /** 还没上映（videoInfo.status == 1）：页面上只有预告，可以预约。 */
        public final boolean upcoming;
        /** 不在腾讯视频时，它在的平台（中文名，按 playSites 的顺序）。 */
        public final List<String> platforms;
        /** 同名不同类型（长安的荔枝：一部电影一部电视剧）：说「还有」时要把类型带上才分得清。 */
        boolean kinded;

        Show(String cid, String title, String type, int year, int episodes, boolean tencent, List<String> platforms) {
            this(cid, title, type, year, episodes, tencent, platforms, false);
        }

        Show(String cid, String title, String type, int year, int episodes, boolean tencent, List<String> platforms,
                boolean upcoming) {
            this.cid = cid; this.title = title; this.type = type; this.year = year;
            this.episodes = episodes; this.tencent = tencent; this.platforms = platforms; this.upcoming = upcoming;
        }

        @Override public String toString() { return title + "/" + type + "/" + year + "/" + cid; }
    }

    /** 一次搜索的结论。 */
    public static final class Result {
        public final String status;
        /** 选中的那一部（NONE 时是 null）。 */
        public final Show show;
        /** FOUND：还有哪些值得提一句；NONE：最接近的几个名字。 */
        public final List<Show> others;
        /** ELSEWHERE：它在哪些平台（中文名）。 */
        public final List<String> platforms;
        /** 放下了 kind/year 的过滤时才不是空串。 */
        public final String note;
        /** 选中的那部是怎么对上的：exact（同名）/ season（某一季）/ heard（同一个音）/ alias（简称）/ 空串（NONE）。 */
        public final String how;

        Result(String status, Show show, List<Show> others, List<String> platforms, String note) {
            this(status, show, others, platforms, note, "");
        }

        Result(String status, Show show, List<Show> others, List<String> platforms, String note, String how) {
            this.status = status; this.show = show; this.others = others;
            this.platforms = platforms; this.note = note; this.how = how;
        }

        public boolean is(String s) { return s.equals(status); }
    }

    /** 详情页（焦点在左边的小播放窗上，按确认键全屏）。 */
    public static String detailLink(String cid) { return link(1, cid); }

    /** 全屏播放（不是会员的话正片前有一两分钟广告）。 */
    public static String playLink(String cid) { return link(7, cid); }

    private static String link(int action, String cid) {
        if (cid == null || !CID.matcher(cid).matches())
            throw new IllegalArgumentException("封面编号不对：" + cid);
        return "tenvideo2://?action=" + action + "&cover_id=" + cid;
    }

    /** 请求体（org.json 拼的，顺序无关；一个字的 query 都不改）。 */
    public static String body(String query) throws JSONException {
        JSONObject extra = new JSONObject().put("isNewMarkLabel", "1").put("multi_terminal_pc", "1")
                .put("themeType", "1").put("sugRelatedIds", "{}").put("appVersion", "");
        return new JSONObject().put("version", "25042201").put("clientType", 1).put("filterValue", "")
                .put("uuid", "DE7F930F-307B-4405-B91F-7A0D95402A62").put("retry", 0)
                .put("query", query == null ? "" : query).put("pagenum", 0).put("isPrefetch", true)
                .put("pagesize", 30).put("queryFrom", 0).put("searchDatakey", "").put("transInfo", "")
                .put("isneedQc", true).put("preQid", "").put("adClientInfo", "")
                .put("extraInfo", extra).toString();
    }

    /**
     * 一次搜索的回复切小：0.1–1.1 MB 里 {@link #pick} 只读得动 15 KB，剩下的（封面地址、简介、预告、相关推荐…）
     * 一律丢掉，缓存里放的就是切小以后的那份（{@code Feeds.cached} 留 32 份十分钟，一份一兆的话一个进程装不下）。
     *
     * <p>留下的：每个盒子（空盒子丢掉）和 normalList，只留 {@code doc.dataType == 2} 的那些 item，每条只留
     * {@code doc.dataType/id} 和 {@code videoInfo} 的 title / typeName / year / status / playSites（每个站点只留
     * enName 和 totalEpisode）。{@code pick(slim(x))} 和 {@code pick(x)} 的结论一个字都不差。
     * 不是这样的回复（没有 data 的报错、根本不是 JSON 的东西）原样返回。
     */
    public static String slim(String json) {
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            return json;
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) return json;
        try {
            JSONArray boxes = new JSONArray();
            JSONArray in = data.optJSONArray("areaBoxList");
            for (int i = 0; in != null && i < in.length(); i++) {
                JSONObject b = in.optJSONObject(i);
                if (b == null) continue;
                JSONArray items = slimItems(b.optJSONArray("itemList"));
                if (items.length() == 0) continue;
                boxes.put(new JSONObject().put("boxId", b.optString("boxId", "")).put("itemList", items));
            }
            JSONObject normal = data.optJSONObject("normalList");
            JSONArray items = slimItems(normal == null ? null : normal.optJSONArray("itemList"));
            JSONObject out = new JSONObject().put("errcode", data.optInt("errcode", 0))
                    .put("errmsg", data.optString("errmsg", ""))
                    .put("areaBoxList", boxes)
                    .put("normalList", new JSONObject().put("itemList", items));
            return new JSONObject().put("data", out).toString();
        } catch (JSONException e) {
            return json;
        }
    }

    /** {@link #slim} 里的一个列表：一条 item 只留 pick 会读的字段。 */
    private static JSONArray slimItems(JSONArray items) throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i), doc = item == null ? null : item.optJSONObject("doc");
            if (doc == null || doc.optInt("dataType") != 2) continue;
            JSONObject v = item.optJSONObject("videoInfo");
            JSONArray sites = new JSONArray();
            JSONArray in = v == null ? null : v.optJSONArray("playSites");
            for (int j = 0; in != null && j < in.length(); j++) {
                JSONObject p = in.optJSONObject(j);
                if (p == null) continue;
                sites.put(new JSONObject().put("enName", News.str(p, "enName"))
                        .put("totalEpisode", p.optInt("totalEpisode", 0)));
            }
            JSONObject info = new JSONObject()
                    .put("title", v == null ? "" : News.str(v, "title"))
                    .put("typeName", v == null ? "" : News.str(v, "typeName"))
                    .put("year", v == null ? 0 : v.optInt("year", 0))
                    .put("status", v == null ? 0 : v.optInt("status", 0))
                    .put("playSites", sites);
            out.put(new JSONObject()
                    .put("doc", new JSONObject().put("dataType", 2).put("id", News.str(doc, "id")))
                    .put("videoInfo", info));
        }
        return out;
    }

    /**
     * 这段搜索结果里，用户说的那部片子在哪。
     *
     * <p>候选的顺序：MainNeed 那个盒子、normalList、其它盒子（腾讯视频自己的页面就是这个顺序）；同一个封面编号只算一次。
     * 名字去掉《》「」“”和空格、全角转半角、ASCII 转小写以后比：一样的是同名，开头是用户说的名字而剩下的是第几季/第几部的是
     * 它的某一季。这两样一部都没有时，才先看末尾的数字是不是写在另一边（{@link #numbered}），再看是不是同一个音
     * （{@link #heard}），最后才找简称（{@link #aliases}）。kind（说了是电视剧还是动漫）和 year（说了哪一年）
     * 先过滤，过滤完一部都不剩就把过滤放下（note 里说明）。
     */
    public static Result pick(String json, String title, String kind, int year) throws JSONException {
        JSONObject data = new JSONObject(json).optJSONObject("data");
        if (data == null) throw new JSONException("腾讯视频的搜索没有返回 data");
        String asked = norm(title), want = kind == null ? "" : kind.trim();
        List<Show> main = new ArrayList<>();
        List<Show> all = candidates(data, want, main);
        List<Show> pool = filtered(all, want, year), mainPool = filtered(main, want, year);
        String note = "";
        if (pool.isEmpty() && !all.isEmpty() && (!want.isEmpty() || year > 0)) {
            pool = all;
            mainPool = main;
            note = "没搜到" + want + (year > 0 ? year + "年的" : "") + "《" + (title == null ? "" : title.trim())
                    + "》，下面是按片名找的";
        }
        if (asked.isEmpty()) return new Result(NONE, null, new ArrayList<Show>(), new ArrayList<String>(), note);

        String askedPlain = plain(title);
        List<Show> exact = new ArrayList<>(), season = new ArrayList<>();
        for (Show s : pool) {
            String n = norm(s.title);
            if (!askedPlain.isEmpty() && plain(s.title).equals(askedPlain)) exact.add(s);
            else if (n.startsWith(asked) && SEASON.matcher(n.substring(asked.length())).matches()) season.add(s);
        }
        Show chosen = null;
        String how = "";
        for (Show s : exact) if (s.tencent && !s.upcoming) { chosen = s; how = "exact"; break; }
        Show latest = newest(season);
        if (chosen == null && latest != null) { chosen = latest; how = "season"; }
        // 一档还在播的综艺：用户说「奔跑吧」指的是今年那一季，不是 2017 年那季
        if (chosen != null && "综艺".equals(chosen.type) && latest != null && latest.year > chosen.year) {
            chosen = latest;
            how = "season";
        }
        // 一部已经上映的都没选上，才是那部还没上映的（页面上只有预告）
        if (chosen == null) {
            for (Show s : exact) if (s.tencent && s.upcoming) { chosen = s; how = "exact"; break; }
            if (chosen == null) {
                Show soon = newest(season, true);
                if (soon != null) { chosen = soon; how = "season"; }
            }
        }
        // 同名的电影和好几季剧（「海绵宝宝」）：用户想看的是剧，电影排到「还有」里
        Show film = null;
        if (chosen != null && "exact".equals(how) && "电影".equals(chosen.type)) {
            List<Show> series = new ArrayList<>();
            for (Show s : season) if (s.tencent && !s.upcoming && !"电影".equals(s.type)) series.add(s);
            if (series.size() >= 2) { film = chosen; chosen = newest(series); how = "season"; }
        }
        if (chosen != null) {
            // 「还有…」：先同名的那几部，再各季（奔跑吧：先 2017 年那季，再大湾区篇、第9季），最多三部
            List<Show> others = new ArrayList<>();
            if (film != null) others.add(film);
            for (Show s : exact) addOther(others, s, chosen);
            for (Show s : season) addOther(others, s, chosen);
            return new Result(FOUND, chosen, markKinds(others, chosen), chosen.platforms, note, how);
        }
        for (Show s : exact) if (!s.platforms.isEmpty())
            return new Result(ELSEWHERE, s, new ArrayList<Show>(), s.platforms, note, "exact");
        // 简称：同名的一部和它的某一季都一部都没有，才先看数字是不是写在另一边（流浪地球二 → 流浪地球2），
        // 再看是不是同一个音（狂彪 → 狂飙），最后才找名字里带着用户说的那个名字的（甄嬛传 → 后宫甄嬛传）
        if (exact.isEmpty() && season.isEmpty()) {
            Result other = choose(numbered(mainPool, askedPlain), note);
            if (other == null) other = choose(heard(mainPool, askedPlain), note);
            if (other != null) return other;
            List<Show> alias = aliases(mainPool, asked, want);
            for (Show s : alias) if (s.tencent) {
                List<Show> others = new ArrayList<>();
                for (Show o : alias) addOther(others, o, s);
                return new Result(FOUND, s, markKinds(others, s), s.platforms, note, "alias");
            }
            for (Show s : alias) if (!s.platforms.isEmpty())
                return new Result(ELSEWHERE, s, new ArrayList<Show>(), s.platforms, note, "alias");
        }
        return new Result(NONE, null, nearest(pool, asked), new ArrayList<String>(), note);
    }

    /** 一个对上了的名字：哪一部片子、怎么对上的。 */
    private static final class Hit {
        final Show show;
        final String how;
        Hit(Show show, String how) { this.show = show; this.how = how; }
    }

    /** 名字末尾的那个数字（用户说的那一边）：一二三四五六七八九十 或 1–9，前面至少还剩两个字；-1 是没有。 */
    private static int trailingNumber(String askedPlain) {
        if (askedPlain.length() < 3) return -1;
        char c = askedPlain.charAt(askedPlain.length() - 1);
        int cn = "一二三四五六七八九十".indexOf(c);
        if (cn >= 0) return cn + 1;
        return c >= '1' && c <= '9' ? c - '0' : -1;
    }

    /** n 的两种写法：2 和 二（腾讯视频两种都写）。 */
    private static String[] written(int n) {
        return new String[]{String.valueOf(n), "一二三四五六七八九十".substring(n - 1, n)};
    }

    /**
     * 数字写在另一边：用户说「流浪地球二」，腾讯视频写《流浪地球2》；说「庆余年2」，写的是《庆余年第二季》。
     *
     * <p>只在同名的一部和它的某一季都一部都没有时才找，而且只在 MainNeed 和 normalList 里找（其余盒子是「相关」）。
     * 末尾那个数字之外的写法都认：数字或汉字都行，第几季和第几部都行。
     */
    private static List<Hit> numbered(List<Show> main, String askedPlain) {
        List<Hit> out = new ArrayList<>();
        int n = trailingNumber(askedPlain);
        if (n < 0) return out;
        String base = askedPlain.substring(0, askedPlain.length() - 1);
        for (Show s : main) {
            String t = plain(s.title);
            if (!t.startsWith(base)) continue;
            String rest = t.substring(base.length());
            String how = "";
            for (String num : written(n)) {
                if (rest.equals(num)) { how = "exact"; break; }
                if (rest.equals("第" + num + "季") || rest.equals("第" + num + "部")) { how = "season"; break; }
            }
            if (!how.isEmpty()) out.add(new Hit(s, how));
        }
        return out;
    }

    /**
     * 同一个音：小爱把「狂飙」写成「狂彪」，把「琅琊榜」写成「琅琊帮」，把「凡人修仙传」写成「凡人修仙转」。
     *
     * <p>只在同名的一部和它的某一季、以及数字写在另一边的都一部都没有时才找，只在 MainNeed 和 normalList 里找。
     * 字数要一样（只是名字里某几个字同音的不算：「狂彪传」不是《狂飙》），而且一个字都不一样的那个写法才用得上。
     */
    private static List<Hit> heard(List<Show> main, String askedPlain) {
        List<Hit> out = new ArrayList<>();
        if (askedPlain.isEmpty()) return out;
        for (Show s : main) {
            String t = plain(s.title);
            if (t.length() != askedPlain.length() || t.equals(askedPlain)) continue;
            if (Pinyin.sameSound(askedPlain, t)) out.add(new Hit(s, "heard"));
        }
        return out;
    }

    /**
     * 对上的那几部里挑一部：腾讯视频里已经上映的 → 腾讯视频里还没上映的（页面上只有预告）→ 有平台的（在外站）。
     * 一部都没有就交回 null，让下一个办法来找。
     */
    private static Result choose(List<Hit> hits, String note) {
        for (int pass = 0; pass < 3; pass++) {
            for (Hit h : hits) {
                Show s = h.show;
                boolean ok = pass == 0 ? s.tencent && !s.upcoming
                        : pass == 1 ? s.tencent : !s.platforms.isEmpty();
                if (!ok) continue;
                if (pass == 2) return new Result(ELSEWHERE, s, new ArrayList<Show>(), s.platforms, note, h.how);
                List<Show> others = new ArrayList<>();
                for (Hit o : hits) addOther(others, o.show, s);
                return new Result(FOUND, s, markKinds(others, s), s.platforms, note, h.how);
            }
        }
        return null;
    }

    /**
     * 简称：名字以用户说的结尾（后宫甄嬛传），或以它开头而剩下的是一个版本说明（甄嬛传(美版)、漫长的季节[卫视版]）。
     *
     * <p>至少三个字才这么找（「甄嬛」两个字会招来一大堆）；只在 MainNeed 和 normalList 里找（其它盒子是「相关」，
     * 全是蹭名字的解读）；类型限 {@link #ALIAS_TYPES}，用户说了 kind 就照 kind。排序：名字以它结尾的在前，
     * 腾讯视频里放的在前。
     */
    private static List<Show> aliases(List<Show> main, String asked, String want) {
        List<Show> out = new ArrayList<>();
        if (asked.length() < 3) return out;
        for (int rank = 0; rank < 2; rank++)
            for (int here = 0; here < 2; here++)
                for (Show s : main) {
                    if (s.tencent != (here == 0) || rankOf(s, asked) != rank) continue;
                    if (want.isEmpty() ? !ALIAS_TYPES.contains(s.type) : !want.equals(s.type)) continue;
                    out.add(s);
                }
        return out;
    }

    /** 0：名字以用户说的结尾（末尾那几个字听起来一样也算：识别写的「甄环传」就是《后宫甄嬛传》）；1：开头是它而剩下的是一个版本说明；-1：不是简称。 */
    private static int rankOf(Show s, String asked) {
        String n = norm(s.title);
        if (n.length() <= asked.length()) return -1;
        if (n.endsWith(asked)) return 0;
        if (soundsTheSame(asked, n.substring(n.length() - asked.length()))) return 0;
        if (n.startsWith(asked) && EDITION.matcher(n.substring(asked.length())).matches()) return 1;
        return -1;
    }

    /**
     * 两个名字听起来一样：{@link Pinyin#sameSound} 说的那样，只是拼音表里查不到读音的那个汉字（嬛）不算不一样。
     *
     * <p>为什么要有这一条：识别把《后宫甄嬛传》写成「甄环传」，而 嬛 这个字拼音表里没有它的读音，
     * {@code sameSound} 就说这两个名字不一样（一个查不到读音的字只能和它自己一样）。只在简称这一步用：
     * 同名的一部和它的某一季、数字写在另一边、整个名字同音都一部都没有时才轮到它，名字至少三个字，
     * 而且只在 MainNeed 和 normalList 里找。字母和数字不一样就是不一样。
     */
    private static boolean soundsTheSame(String a, String b) {
        if (Pinyin.sameSound(a, b)) return true;
        String x = comparable(a), y = comparable(b);
        if (x.length() < 3 || x.length() != y.length()) return false;
        for (int i = 0; i < x.length(); i++) {
            char p = x.charAt(i), q = y.charAt(i);
            if (p == q) continue;
            List<String> rp = Pinyin.readings(p), rq = Pinyin.readings(q);
            if (rp.isEmpty() || rq.isEmpty()) {
                // 查不到读音的汉字：没人知道它怎么读，不当成不一样
                if (han(p) && han(q)) continue;
                return false;
            }
            boolean share = false;
            for (String r : rp) if (rq.contains(r)) { share = true; break; }
            if (!share) return false;
        }
        return true;
    }

    /** {@link Pinyin} 比声音时留下的那些字：汉字（有没有读音都算）和 ASCII 字母数字。 */
    private static String comparable(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; s != null && i < s.length(); i++) {
            char c = s.charAt(i);
            if (han(c) || !Pinyin.readings(c).isEmpty() || ascii(c)) b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    private static boolean han(char c) { return c >= 0x4E00 && c <= 0x9FFF; }

    private static boolean ascii(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** 同名不同类型的（长安的荔枝：一部电影一部电视剧）：「还有」里那几部要带上类型才分得清。 */
    private static List<Show> markKinds(List<Show> others, Show chosen) {
        for (Show s : others) {
            if (differs(s, chosen)) { s.kinded = true; continue; }
            for (Show o : others) if (differs(s, o)) { s.kinded = true; break; }
        }
        return others;
    }

    private static boolean differs(Show a, Show b) {
        return a != b && !a.type.isEmpty() && !b.type.isEmpty() && !a.type.equals(b.type)
                && norm(a.title).equals(norm(b.title));
    }

    /** 在腾讯视频放的那一季里最新的一季（年份一样取候选顺序里的第一个）；只有已经上映的那些。 */
    private static Show newest(List<Show> season) { return newest(season, false); }

    /** soon=true 时反过来：只看还没上映的那些。 */
    private static Show newest(List<Show> season, boolean soon) {
        Show best = null;
        for (Show s : season) if (s.tencent && s.upcoming == soon && (best == null || s.year > best.year)) best = s;
        return best;
    }

    /** 同一部片子：名字、类型、年份都一样（搜索里同一部会带着不同的封面编号出现两次）。 */
    private static boolean same(Show a, Show b) {
        return a.year == b.year && a.type.equals(b.type) && norm(a.title).equals(norm(b.title));
    }

    /** 「还有…」里的一部：在腾讯视频放的，不是选中的那部，也不是同一部片子的另一个编号，最多三部。 */
    private static void addOther(List<Show> others, Show s, Show chosen) {
        if (others.size() >= 3 || s == chosen || !s.tencent || same(s, chosen) || others.contains(s)) return;
        others.add(s);
    }

    private static List<Show> filtered(List<Show> all, String kind, int year) {
        List<Show> out = new ArrayList<>();
        for (Show s : all) if ((kind.isEmpty() || kind.equals(s.type)) && (year <= 0 || year == s.year)) out.add(s);
        return out;
    }

    /** 候选：MainNeed 盒子 → normalList → 其它盒子，dataType 2 才是一部片子（1 是切片，5000 是盒子自己）。 */
    private static List<Show> candidates(JSONObject data, String kind, List<Show> main) {
        List<Show> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JSONArray boxes = data.optJSONArray("areaBoxList");
        for (int i = 0; boxes != null && i < boxes.length(); i++) {
            JSONObject b = boxes.optJSONObject(i);
            if (b != null && "MainNeed".equals(b.optString("boxId"))) addAll(out, seen, b.optJSONArray("itemList"), kind);
        }
        JSONObject normal = data.optJSONObject("normalList");
        addAll(out, seen, normal == null ? null : normal.optJSONArray("itemList"), kind);
        // 简称只从这两处找：其它盒子是「相关」，里面全是名字里蹭着这几个字的解读视频
        main.addAll(out);
        for (int i = 0; boxes != null && i < boxes.length(); i++) {
            JSONObject b = boxes.optJSONObject(i);
            if (b != null && !"MainNeed".equals(b.optString("boxId"))) addAll(out, seen, b.optJSONArray("itemList"), kind);
        }
        return out;
    }

    private static void addAll(List<Show> out, Set<String> seen, JSONArray items, String kind) {
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i), doc = item == null ? null : item.optJSONObject("doc");
            if (doc == null || doc.optInt("dataType") != 2) continue;
            String cid = News.str(doc, "id");
            if (!CID.matcher(cid).matches() || !seen.add(cid)) continue;
            JSONObject v = item.optJSONObject("videoInfo");
            String title = v == null ? "" : clean(News.str(v, "title"));
            String type = v == null ? "" : News.str(v, "typeName");
            if (title.isEmpty() || UNLESS_ASKED.contains(type) && !type.equals(kind)) continue;
            JSONArray sites = v.optJSONArray("playSites");
            boolean tencent = false;
            int qqEpisodes = 0, otherEpisodes = 0;
            List<String> platforms = new ArrayList<>();
            for (int j = 0; sites != null && j < sites.length(); j++) {
                JSONObject p = sites.optJSONObject(j);
                if (p == null) continue;
                String en = News.str(p, "enName");
                int total = p.optInt("totalEpisode", 0);
                if ("qq".equals(en)) { tencent = true; qqEpisodes = total; continue; }
                String cn = platform(en);
                if (!cn.isEmpty() && !platforms.contains(cn)) platforms.add(cn);
                if (otherEpisodes == 0) otherEpisodes = total;
            }
            out.add(new Show(cid, title, type, v.optInt("year", 0),
                    tencent ? qqEpisodes : otherEpisodes, tencent, platforms, v.optInt("status", 0) == 1));
        }
    }

    /** 平台的中文名；不认识的照 enName 说。 */
    static String platform(String en) {
        String e = en == null ? "" : en.toLowerCase(Locale.ROOT);
        if ("qiyi".equals(e)) return "爱奇艺";
        if ("youku".equals(e)) return "优酷";
        if ("bilibili".equals(e)) return "哔哩哔哩";
        if ("mgtv".equals(e) || "hunantv".equals(e)) return "芒果TV";
        if ("sohu".equals(e)) return "搜狐视频";
        if ("cctv".equals(e) || "cntv".equals(e)) return "央视";
        if ("migu".equals(e)) return "咪咕视频";
        if ("letv".equals(e)) return "乐视视频";
        if ("yangshipin".equals(e)) return "央视频";
        if ("pptv".equals(e)) return "PP视频";
        if ("wasu".equals(e)) return "华数TV";
        if ("m1905".equals(e) || "1905".equals(e)) return "1905电影网";
        // sdp 是腾讯视频给「别的网站」的一个统称，不是哪一家
        if ("sdp".equals(e)) return "别的网站";
        return en == null ? "" : en;
    }

    /** 搜不到时值得提一句的名字：和用户说的名字开头相同的那些，相同的字数多的在前，最多三个。 */
    private static List<Show> nearest(List<Show> pool, String asked) {
        List<Show> out = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (Show s : pool) {
            int n = commonPrefix(norm(s.title), asked);
            if (n <= 0) continue;
            int at = 0;
            while (at < out.size() && scores.get(at) >= n) at++;
            if (at >= 3) continue;
            out.add(at, s);
            scores.add(at, n);
            if (out.size() > 3) { out.remove(3); scores.remove(3); }
        }
        return out;
    }

    private static int commonPrefix(String a, String b) {
        int n = 0;
        while (n < a.length() && n < b.length() && a.charAt(n) == b.charAt(n)) n++;
        return n;
    }

    private static String clean(String title) { return title.replaceAll("<[^>]*>", "").trim(); }

    /** 名字里的标点：用户说「你的名字」，腾讯视频写的是《你的名字。》。 */
    private static final String PUNCTUATION = "。．.·・：:，,、！!？?～~—–-_'’\"…";

    /** 比名字用的写法，再去掉标点：一样的是同一部片子（「三体·黑暗森林」就是「三体黑暗森林」）。 */
    static String plain(String s) {
        String n = norm(s);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (PUNCTUATION.indexOf(c) < 0) b.append(c);
        }
        return b.toString();
    }

    /** 只有这些类型是一部能看的片子；音乐、游戏、汽车这些是搜索里的别的栏目。 */
    private static final Set<String> SURE_TYPES = new HashSet<>(java.util.Arrays.asList(
            "电视剧", "电影", "综艺", "动漫", "少儿", "纪录片"));

    /**
     * 能不能不问模型就打开：腾讯视频里真有一部这个名字的、已经上映的、能看的片子，而且名字说得够清楚。
     *
     * <p>模型猜不出腾讯视频有没有《别无选择》（2026-11-11：16 次里错 7 次），搜索 0.2–0.6 秒就知道。这句话只在
     * 搜索说得死死的（同名、它的某一季，或识别听岔了的同一个音；已经上映；是一部片子；名字至少三个字）时才替模型动手；
     * 差一点就交回模型。
     */
    public static boolean sure(Result r, String asked, boolean kindNamed) {
        if (r == null || !FOUND.equals(r.status) || r.show == null || r.show.upcoming) return false;
        if (!"exact".equals(r.how) && !"season".equals(r.how) && !"heard".equals(r.how)) return false;
        if (!SURE_TYPES.contains(r.show.type)) return false;
        return plain(asked).length() >= (kindNamed ? 2 : 3);
    }

    /** 比名字用的写法：去掉《》「」“”和空格，全角数字字母转半角，ASCII 转小写。 */
    static String norm(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '《' || c == '》' || c == '「' || c == '」' || c == '“' || c == '”') continue;
            if (Character.isWhitespace(c) || c == '　') continue;
            if (c >= 0xFF10 && c <= 0xFF19) c = (char) (c - 0xFF10 + '0');
            else if (c >= 0xFF21 && c <= 0xFF3A) c = (char) (c - 0xFF21 + 'A');
            else if (c >= 0xFF41 && c <= 0xFF5A) c = (char) (c - 0xFF41 + 'a');
            if (c >= 'A' && c <= 'Z') c = (char) (c - 'A' + 'a');
            b.append(c);
        }
        return b.toString();
    }

    /** 「（电视剧，2023，共 36 集）」：没有年份、没有集数就不写那一段。 */
    public static String tag(Show s) { return paren(s, true); }

    /** 「（电视剧，2023）」。 */
    public static String brief(Show s) { return paren(s, false); }

    private static String paren(Show s, boolean episodes) {
        boolean ep = episodes && s.episodes > 0;
        if (s.type.isEmpty() && s.year <= 0 && !ep) return "";
        StringBuilder b = new StringBuilder("（").append(s.type.isEmpty() ? "片子" : s.type);
        if (s.year > 0) b.append('，').append(s.year);
        if (ep) b.append("，共 ").append(s.episodes).append(" 集");
        return b.append('）').toString();
    }

    /** 「。还有《繁花剧场版》（2024）、《三体·黑暗森林》（2026）」，最多 max 部；同名不同类型的带上类型。 */
    public static String also(List<Show> others, int max) {
        if (others == null || others.isEmpty() || max <= 0) return "";
        StringBuilder b = new StringBuilder("。还有");
        for (int i = 0; i < others.size() && i < max; i++) {
            Show s = others.get(i);
            if (i > 0) b.append('、');
            b.append('《').append(s.title).append('》');
            if (s.kinded) b.append(brief(s));
            else if (s.year > 0) b.append('（').append(s.year).append('）');
        }
        return b.toString();
    }

    /** 「（有《A》《B》）」：一部都没搜到时，搜索里最接近的几个名字。 */
    public static String near(List<Show> others, int max) {
        if (others == null || others.isEmpty() || max <= 0) return "";
        StringBuilder b = new StringBuilder("（有");
        for (int i = 0; i < others.size() && i < max; i++) b.append('《').append(others.get(i).title).append('》');
        return b.append('）').toString();
    }

    /**
     * 腾讯视频网站的选集列表（2026-11-11 在电脑上问出来的）：一部片子有哪些集、每一集的编号是多少。
     *
     * <p>为什么要有：{@code tenvideo2://?action=7&cover_id=} 打开的是一部片子，进的是它记住的那一集；要看第 3 集
     * 得知道第 3 集自己的编号。网站自己的详情页就是这么拿的（和搜索同一个 host、同样的 Referer / Origin）。
     * 一页 30 条，更长的片子带 tabs，每个 tab 带着下一页的 page_context（{@link Episodes#contextsFor}）。
     */
    public static final String EPISODES =
            "https://pbaccess.video.qq.com/trpc.universal_backend_service.page_server_rpc.PageServer/GetPageData?video_appid=3000010&vplatform=2";

    /** 一集的编号：只有能拼成链接的才算（比封面编号短）。 */
    private static final Pattern VID = Pattern.compile("[A-Za-z0-9]{6,20}");

    /** 「第n期」后面跟着这些字的不是正片：加更、特别加更、上下期、中集。 */
    private static final Pattern NOT_MAIN = Pattern.compile("^(?:加更|特别|下|中)");

    /** 选集列表的请求体（org.json 拼的，顺序无关）；pageContext 是上一页给的凭据，第一页是空串。 */
    public static String episodesBody(String cid, String pageContext) throws JSONException {
        JSONObject params = new JSONObject().put("req_from", "web_vsite").put("page_id", "vsite_episode_list")
                .put("page_type", "detail_operation").put("id_type", "1").put("page_size", "")
                .put("cid", cid == null ? "" : cid).put("vid", "").put("lid", "").put("page_num", "")
                .put("page_context", pageContext == null ? "" : pageContext).put("detail_page_type", "1");
        return new JSONObject().put("page_params", params).put("has_cache", 1).toString();
    }

    /** 直接放这一集（2026-11-11 在电视上试过：暂停的画面上写的就是「别无选择 第3集」）。 */
    public static String playLink(String cid, String vid) {
        if (cid == null || !CID.matcher(cid).matches())
            throw new IllegalArgumentException("封面编号不对：" + cid);
        if (vid == null || !VID.matcher(vid).matches())
            throw new IllegalArgumentException("这一集的编号不对：" + vid);
        return "tenvideo2://?action=7&cover_id=" + cid + "&video_id=" + vid;
    }

    /** 一集。 */
    public static final class Episode {
        /** 列表上写的名字：「3」是第 3 集，「第1期：…」是一档综艺的一期，别的（彩蛋、周年短片）是花絮。 */
        public final String title;
        public final String vid;
        /** 「繁花 第03集」：播放页上写的那一句。 */
        public final String playTitle;
        /** 这一集的一句话简介；没有写就是空串。 */
        public final String about;
        /** 名字就是一个 1–4 位的数字时是第几集，别的写法（综艺的一期、彩蛋、一部电影）是 0。 */
        public final int number;
        /** 时长，秒；没有写是 0。 */
        public final int seconds;
        /** 要会员才能看全（uni_imgtag 里有 VIP）。 */
        public final boolean vip;
        /** 是预告，不是正片。 */
        public final boolean trailer;

        Episode(String title, String vid, String playTitle, String about, int number, int seconds,
                boolean vip, boolean trailer) {
            this.title = title; this.vid = vid; this.playTitle = playTitle; this.about = about;
            this.number = number; this.seconds = seconds; this.vip = vip; this.trailer = trailer;
        }

        @Override public String toString() { return title + "/" + vid; }
    }

    /** 选集列表上的一页：第 begin 到第 end 集；page_context 是问它要这一页的凭据。 */
    public static final class Tab {
        public final int begin, end;
        /** 就是当前这一页（不用再问）。 */
        public final boolean selected;
        public final String context;

        Tab(int begin, int end, boolean selected, String context) {
            this.begin = begin; this.end = end; this.selected = selected; this.context = context;
        }

        @Override public String toString() { return begin + "-" + end + (selected ? "*" : ""); }
    }

    /** 一页选集列表：这一页上的每一集、还有哪几页（更长的片子）、顶上写的那一句。 */
    public static final class Episodes {
        public final List<Episode> list;
        public final List<Tab> tabs;
        /** 「会员看全集」「全31集」「更新至678集」：这一页顶上写的那一句，没有就是空串。 */
        public final String subTitle;

        Episodes(List<Episode> list, List<Tab> tabs, String subTitle) {
            this.list = list; this.tabs = tabs; this.subTitle = subTitle;
        }

        /**
         * 第 n 集在这一页上是哪一条：先是名字就是那个数字的，再是名字以「第n期」开头的正片（一档综艺的一期：
         * 加更、特别加更、上下期、中集都不算），最后是名字里有「第n集」「第n期」或「第n话」的（【…】第31集）。
         * 预告不算。null 是这一页上没有第 n 集。
         */
        public Episode find(int n) {
            if (n < 1) return null;
            for (Episode e : list) if (!e.trailer && e.number == n) return e;
            Pattern main = mainPeriod(n);
            for (Episode e : list) {
                if (e.trailer) continue;
                Matcher m = main.matcher(e.title);
                if (m.lookingAt() && !NOT_MAIN.matcher(e.title.substring(m.end())).find()) return e;
            }
            Pattern any = anyEpisode(n);
            for (Episode e : list) if (!e.trailer && any.matcher(e.title).find()) return e;
            return null;
        }

        /**
         * 第 n 集还可能在哪几页：先装着它的那一页，再别的没在当前这页上的页，按列表的顺序，最多 4 个。
         * 一个 page_context 都没有（这一页就是全部、或者那一页没有凭据）的 tab 不算。
         */
        public List<String> contextsFor(int n) {
            List<String> out = new ArrayList<>();
            for (int pass = 0; pass < 2; pass++)
                for (Tab t : tabs) {
                    if (t.selected || t.context == null || t.context.isEmpty()) continue;
                    if ((pass == 0) != (t.begin <= n && n <= t.end)) continue;
                    if (out.size() >= 4) return out;
                    out.add(t.context);
                }
            return out;
        }

        /** 这一页上最大的一集是第几集；一集都没编号（一档综艺、一部电影）是 0。 */
        public int last() {
            int n = 0;
            for (Episode e : list) if (e.number > n) n = e.number;
            return n;
        }
    }

    /**
     * 一页选集列表。条目是 {@code item_type == "1"} 而且 {@code item_params.vid} 不是空串的那些
     * （28 是「普通话版」这样的筛选器，不是选集）。没有 data 的回复（报错）是空的一页，不抛。
     */
    public static Episodes episodes(String json) throws JSONException {
        JSONObject data = new JSONObject(json).optJSONObject("data");
        List<Episode> list = new ArrayList<>();
        List<Tab> tabs = new ArrayList<>();
        String subTitle = "";
        JSONArray modules = data == null ? null : data.optJSONArray("module_list_datas");
        for (int i = 0; modules != null && i < modules.length(); i++) {
            JSONObject module = modules.optJSONObject(i);
            JSONArray datas = module == null ? null : module.optJSONArray("module_datas");
            for (int j = 0; datas != null && j < datas.length(); j++) {
                JSONObject md = datas.optJSONObject(j);
                if (md == null) continue;
                JSONObject params = md.optJSONObject("module_params");
                if (params != null) {
                    if (tabs.isEmpty()) readTabs(tabs, params.optString("tabs", ""));
                    if (subTitle.isEmpty()) subTitle = params.optString("sub_title", "");
                }
                JSONObject lists = md.optJSONObject("item_data_lists");
                JSONArray items = lists == null ? null : lists.optJSONArray("item_datas");
                for (int k = 0; items != null && k < items.length(); k++) {
                    Episode e = episode(items.optJSONObject(k));
                    if (e != null) list.add(e);
                }
            }
        }
        return new Episodes(list, tabs, subTitle);
    }

    /** {@code module_params.tabs} 是一段 JSON 字符串（[{begin,end,selected,page_context}]）；空串是没有分页。 */
    private static void readTabs(List<Tab> out, String raw) {
        if (raw == null || raw.isEmpty()) return;
        JSONArray in;
        try {
            in = new JSONArray(raw);
        } catch (JSONException e) {
            return;
        }
        for (int i = 0; i < in.length(); i++) {
            JSONObject t = in.optJSONObject(i);
            if (t == null) continue;
            out.add(new Tab(t.optInt("begin", 0), t.optInt("end", 0), t.optBoolean("selected", false),
                    t.optString("page_context", "")));
        }
    }

    /** 一条条目是不是一集；不是（筛选器、没有编号）就交回 null。 */
    private static Episode episode(JSONObject item) {
        if (item == null || !"1".equals(item.optString("item_type", ""))) return null;
        JSONObject p = item.optJSONObject("item_params");
        if (p == null) return null;
        String vid = p.optString("vid", "");
        if (vid.isEmpty()) return null;
        String title = p.optString("title", "");
        return new Episode(title, vid, p.optString("play_title", ""), p.optString("video_subtitle", ""),
                number(title), seconds(p.optString("duration", "")), vip(p.optString("uni_imgtag", "")),
                "1".equals(p.optString("is_trailer", "")));
    }

    /** 名字就是一个 1–4 位的数字时是第几集；别的写法（综艺的一期、彩蛋、《流浪地球2》）是 0。 */
    private static int number(String title) {
        return title != null && title.matches("[0-9]{1,4}") ? Integer.parseInt(title) : 0;
    }

    /** 时长写的是秒（一个字符串）；不是这样写的算 0。 */
    private static int seconds(String duration) {
        return duration != null && duration.matches("[0-9]{1,6}") ? Integer.parseInt(duration) : 0;
    }

    /** 要会员才能看全：uni_imgtag 是一段 JSON 字符串，里面哪一个 tag 带着 VIP 就是。 */
    private static boolean vip(String imgtag) {
        return imgtag != null && imgtag.contains("VIP");
    }

    /**
     * 一页选集列表切小：只留 {@link #episodes} 读的那些字段（封面地址、点赞数、相关推荐…一律丢掉），缓存里放的就是
     * 切小以后的那份——{@code Feeds.cached} 一份一兆的话一个进程装不下。{@code episodes(slimEpisodes(x))} 和
     * {@code episodes(x)} 的结论一个字都不差。不是这样的回复（没有 data 的报错、根本不是 JSON 的东西）原样返回。
     */
    public static String slimEpisodes(String json) {
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            return json;
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) return json;
        try {
            JSONArray modules = new JSONArray();
            JSONArray in = data.optJSONArray("module_list_datas");
            for (int i = 0; in != null && i < in.length(); i++) {
                JSONObject module = in.optJSONObject(i);
                JSONArray datas = new JSONArray();
                JSONArray md = module == null ? null : module.optJSONArray("module_datas");
                for (int j = 0; md != null && j < md.length(); j++) {
                    JSONObject one = md.optJSONObject(j);
                    if (one == null) continue;
                    JSONObject params = one.optJSONObject("module_params");
                    JSONObject lists = one.optJSONObject("item_data_lists");
                    JSONArray items = lists == null ? null : lists.optJSONArray("item_datas");
                    JSONArray kept = new JSONArray();
                    for (int k = 0; items != null && k < items.length(); k++) {
                        JSONObject item = slimEpisodeItem(items.optJSONObject(k));
                        if (item != null) kept.put(item);
                    }
                    datas.put(new JSONObject()
                            .put("module_params", new JSONObject()
                                    .put("tabs", params == null ? "" : params.optString("tabs", ""))
                                    .put("sub_title", params == null ? "" : params.optString("sub_title", "")))
                            .put("item_data_lists", new JSONObject().put("item_datas", kept)));
                }
                modules.put(new JSONObject().put("module_datas", datas));
            }
            JSONObject out = new JSONObject().put("module_list_datas", modules);
            return new JSONObject().put("ret", root.optInt("ret", 0)).put("msg", root.optString("msg", ""))
                    .put("data", out).toString();
        } catch (JSONException e) {
            return json;
        }
    }

    /** {@link #slimEpisodes} 里的一条：一集只留 {@link #episode} 读的字段，别的条目（筛选器）只留类型和名字。 */
    private static JSONObject slimEpisodeItem(JSONObject item) throws JSONException {
        if (item == null) return null;
        String type = item.optString("item_type", "");
        JSONObject p = item.optJSONObject("item_params");
        if (!"1".equals(type))
            return new JSONObject().put("item_type", type)
                    .put("item_params", new JSONObject().put("title", p == null ? "" : p.optString("title", "")));
        String imgtag = p == null ? "" : p.optString("uni_imgtag", "");
        return new JSONObject().put("item_type", type).put("item_params", new JSONObject()
                .put("title", p == null ? "" : p.optString("title", ""))
                .put("vid", p == null ? "" : p.optString("vid", ""))
                .put("play_title", p == null ? "" : p.optString("play_title", ""))
                .put("duration", p == null ? "" : p.optString("duration", ""))
                .put("is_trailer", p == null ? "" : p.optString("is_trailer", ""))
                .put("video_subtitle", p == null ? "" : p.optString("video_subtitle", ""))
                // 只有「要会员」这一件事要读：没有 VIP 的那一大段标签（日期、时长…）丢掉
                .put("uni_imgtag", vip(imgtag) ? imgtag : ""));
    }

    /** n 的汉字写法（1–99：十、十一、二十、二十一）；别的交回空串。 */
    static String chinese(int n) {
        if (n < 1 || n > 99) return "";
        String digits = "一二三四五六七八九";
        if (n < 10) return digits.substring(n - 1, n);
        int tens = n / 10, ones = n % 10;
        return (tens == 1 ? "十" : digits.substring(tens - 1, tens) + "十") + (ones == 0 ? "" : digits.substring(ones - 1, ones));
    }

    /** 第 n 的两种写法（3 和 三），中间允许有空格。 */
    private static String writtenAs(int n) {
        String cn = chinese(n);
        return "(?:" + n + (cn.isEmpty() ? "" : "|" + cn) + ")";
    }

    /** 名字以「第n期」开头：一档综艺的正片那一期。 */
    private static Pattern mainPeriod(int n) {
        return Pattern.compile("^第\\s*" + writtenAs(n) + "\\s*期");
    }

    /** 名字里有「第n集」「第n期」或「第n话」，后面不跟着另一个数字。 */
    private static Pattern anyEpisode(int n) {
        return Pattern.compile("第\\s*" + writtenAs(n) + "\\s*[集期话](?![0-9])");
    }
}

