package xyz.erapi0neer.xingyi;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Complete native capability refusals paired with one recognizable TV intent. */
final class GenericRefusalText {
    private static final String POLITE = "(?:(?:小爱同学[, ]*)?(?:请问|请你|请|麻烦你|帮我|给我)?)";
    private static final String LOOK = "(?:请问|查询|查看|告诉我|看看|查一下|看一下|帮我查一下|读一下|读取)?";
    private static final String TV = "(?:(?:这台)?电视(?:机)?(?:上|的|上的)?)?";
    private static final String TIME = "(?:现在|目前|当前)?";
    private static final String MEDIA = "(?:媒体|播放)?";
    private static final String END = "[。.!！?？]?";
    private static final Pattern LEVEL = Pattern.compile(POLITE + LOOK + TIME + TV + TIME
            + "(?:" + MEDIA + "音量(?:现在|目前|当前)?(?:是多少|有多大|多少|多大|大小|状态)?"
            + "|(?:是否|有没有|是不是)?静音(?:了吗|了|吗|状态)?)" + END);
    private static final Pattern STATUS = Pattern.compile(POLITE + LOOK + TIME
            + "(?:(?:这台)?电视(?:机)?(?:的)?" + TIME + "(?:的)?(?:系统)?状态|"
            + TV + TIME + "(?:音量和静音状态|媒体音量和静音状态))" + END);
    private static final Pattern APPS = Pattern.compile(POLITE + LOOK + TV
            + "(?:(?:都)?(?:安装|装)(?:了|着)(?:哪些|什么)(?:应用|软件)|"
            + "(?:已安装的|安装的|本机)?(?:应用|软件)(?:清单|列表)|有哪些(?:已安装的|可以打开的)?(?:应用|软件)(?:可以打开)?)" + END);
    private static final Pattern TIMER = Pattern.compile(POLITE + LOOK + TIME + TV + TIME
            + "(?:(?:有没有|是否设置了|还有没有)?(?:定时休眠|休眠定时)(?:设置|状态|时间|还有多久|还剩多久|还有多少分钟|还剩多少分钟|了吗|吗)?"
            + "|(?:还要|还有|还剩)(?:多久|多少分钟)(?:才|会|后)?(?:自动)?休眠)" + END);
    private static final String LABEL = "[\\p{L}\\p{N}· &'’+()_-]";
    private static final Pattern OPEN = Pattern.compile(POLITE + "(?:打开|启动)(?:这个|一个)?"
            + "(?:(?:名为|名称为)?(?:应用程序|应用|软件)[ ]*)?(" + LABEL + "{1,32}?)(?:这个应用|这个软件)?[。.!！]?");
    private static final Pattern KNOWN_APP = Pattern.compile("网易云音乐|云视听极光|哔哩哔哩|无线投屏|相册|应用商店|电视管家|"
            + "腾讯视频|爱奇艺|优酷|芒果TV|全民K歌|酷喵|QQ音乐|酷狗音乐|央视频|电视家", Pattern.CASE_INSENSITIVE);
    static final String BILI_PACKAGE = "tv.danmaku.bili";
    private static final String BILI_NAME = "(?:哔哩哔哩|哔哩哔哩动画|bilibili|B[ ]*站)(?:官方)?(?:客户端|手机版|安卓版|[ ]*Android[ ]*客户端)?";
    private static final Pattern BILI_LABEL = Pattern.compile(BILI_NAME,Pattern.CASE_INSENSITIVE);
    private static final Pattern BILI_SEARCH = Pattern.compile(
            "(?:小爱同学[ ,，]*)?(?:(?:请你|请|麻烦你|帮我|给我)[ ]*)?(?:(?:打开|启动|进入|去|在|用)[ ]*)?"
            + BILI_NAME + "(?:[ ]*(?:上|里|中))?[ ,，、]*(?:(?:然后|接着|并且|并)[ ,，、]*)?"
            + "(?:帮我|给我)?(?:搜索(?:一下)?|搜一搜|搜一下|搜|查找(?:一下)?)[ ]*(.+)",Pattern.CASE_INSENSITIVE);
    private static final Pattern SEARCH_EXTRA_ACTION = Pattern.compile(
            "之后再|完了再|(?:再|并|且|[，,。][ ]*)(?:帮我|给我|请)?(?:关机|关灯|开灯|退出|返回|打开|安装|执行|点赞|投币|收藏|关注|播放|搜索|关闭|调高|调低)|"
            + "(?:^|\\s)(?:and|then|also)(?:\\s|$)",Pattern.CASE_INSENSITIVE);
    private static final Pattern INSTALL_BILI = Pattern.compile(POLITE + "(?:(?:在|给)(?:这台)?电视(?:上)?)?"
            + "(?:下载安装|安装)(?:一下)?[ ]*(?:应用|软件)?[ ]*" + BILI_NAME + "(?:这个应用|这个软件)?"
            + "(?:[,。 ]*(?:不是|不要|别装)(?:云电视|云视听小电视|云视听极光)(?:版)?)?[。.!！]?",Pattern.CASE_INSENSITIVE);
    // The TV's far-field recognizer also produces provider-first commands, e.g. 网易云音乐打开林俊杰的歌.
    private static final String PLAYER = "网易云(?:音乐)?[, ]*";
    private static final String PLAY = "(?:播放|点播|放一首|听一首|放首)";
    private static final String MUSIC_PREFIX = POLITE + "(?:(?:(?:用|使用|在|打开)|我想(?:在|用))" + PLAYER
            + "(?:帮我|给我)?" + PLAY + "|" + PLAYER + "(?:打开[, ]*(?:" + PLAY + ")?|" + PLAY + ")|"
            + "(?:我想|我要)?" + PLAY + "|(?:我)?想听)[ ]*";
    private static final Pattern MUSIC_COMMAND = Pattern.compile(MUSIC_PREFIX + "(.{1,150}?)[。.!！]?");
    private static final String ARTIST_NAME = "((?:(?!的)" + LABEL + "){1,50}?)(?:演唱|唱)?[ ]*的[ ]*";
    private static final String TITLE = "(?:(?:歌曲|单曲|歌)?《([^《》\\r\\n]{1,90})》|(" + LABEL + "{1,90}))";
    private static final Pattern ARTIST = Pattern.compile(ARTIST_NAME + "(?:歌|歌曲|音乐)");
    private static final Pattern SONG = Pattern.compile(ARTIST_NAME + TITLE);
    private static final Pattern TITLE_ONLY = Pattern.compile(TITLE);
    private static final Pattern OTHER_MEDIA = Pattern.compile("QQ音乐|酷狗|酷我|汽水音乐|苹果音乐|咪咕|腾讯|优酷|爱奇艺|"
            + "喜马拉雅|哔哩哔哩|抖音|B站|Spotify|Apple Music|YouTube|MV|电影|电视剧|连续剧|动画片|纪录片|"
            + "综艺|电视台|频道|视频|有声书|广播|播客|直播|第[一二三四五六七八九十百零0-9]+[集季期]",Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_TRACK = Pattern.compile("^(?:一首)?(?:歌|歌曲|音乐|下一首|上一首|这首|这首歌|"
            + "这首歌曲|每日推荐|私人FM|随便|随机|继续)$|^(?:的|切换到|(?:用|通过|在).{1,16}(?:播放器|客户端|音乐软件))|歌单|播放列表|收藏夹",Pattern.CASE_INSENSITIVE);
    private static final Pattern OTHER_SPEECH = Pattern.compile("^(?:请|介绍|查询|告诉|说说|查一下|搜一下|你知道|你觉得|我知道|这是|解释)|[吗呢？?]$");
    // These are intent cues only. They never supply a title, an artist, or tool arguments.
    private static final Pattern MUSIC_SUBJECT = Pattern.compile("网易云|歌曲|音乐|唱的|的歌|歌单|专辑|歌手|单曲|[一两几]首|[点来放听]首|《[^《》]{1,90}》");
    private static final Pattern MUSIC_REQUEST = Pattern.compile("播放|点播|循环播|随机播|[点来放听找](?:一|两|几)?首|来点|"
            + "放给我听|给我放|帮我放|想听|我要听|让我听|给我听|听一听|听听|听一下|放一下");
    private static final Pattern MUSIC_COMMENT = Pattern.compile("[“”\"‘’]|怎么|为什么|为何|如何|什么意思|是什么意思|"
            + "介绍|解释|复述|转述|引用|举例|讨论|说说|你知道|我不知道|听说|有人说|据说|原话|这句话|是否|能不能播放|支持吗|支持哪些|能播放哪些|播放功能|"
            + "谁写的|谁唱的|作词是谁|歌手是谁|不是要|不想|不希望|不需要|不用|别放|别用|别给|不许|禁止|暂停|停止|取消|如果|假如");
    private static final Pattern UNBOUND_TARGET = Pattern.compile("^(?:一首|几首|随便|适合|适用于|类似于|像.{1,14}那样|"
            + "请推荐|给我|帮我|你觉得|能不能|可不可以)|(?:给我|帮我)(?:选|挑|找|推荐)|(?:适合|适用于).{1,18}的(?:歌|音乐)$");
    private static final Pattern NON_ARTIST = Pattern.compile("^(?:我|我们|你|你们|您|他|她|它|自己|大家)$|"
            + "的|歌单|收藏|喜欢|最爱|推荐|最近听|常听|历史|本地|下载|播放|打开|关闭|搜索|不要|取消|"
            + "(?<=\\p{L})[和与跟或](?=\\p{L})|还有|以及|[&]|\\b(?:and|or)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIBRARY = Pattern.compile(".*的[ ]*(?:歌单|专辑|收藏|播放列表|全部歌曲|所有歌曲|热门歌曲)[。.!！]?");
    private static final Pattern SECOND_OR_UNSAFE = Pattern.compile("然后|接着|随后|同时|顺便|并且|以及|再帮我|再给我|再打开|再播放|再关闭|"
            + "并(?:打开|关闭|播放|设置|把|将)|[;；\\n\\r]|密码|摄像头|摄像机|录音|监控|门锁|账号|账户|支付|付款|购买|"
            + "授权|隐私|敏感|登录|删除|卸载|解锁|验证码|钱包|银行|账单|交易|和(?:打开|启动|关闭|播放|设置|查询)|小爱同学[, ]*小爱同学");
    private static final Pattern FIXED = Pattern.compile("(?:(?:很抱歉|抱歉|对不起|不好意思)[, ]*)?(?:"
            + "(?:目前|当前)暂不支持(?:该|此)功能"
            + "|(?:我|小爱(?:同学)?)(?:现在|目前|当前|暂时|还|尚|暂)?(?:还|暂时)?"
            + "(?:不支持(?:执行|处理)?(?:这个|这项|该|此)(?:操作|功能|任务|请求|指令)|没有(?:这个|这项)(?:功能|能力))"
            + "|(?:当前|这个|该|这台|[你您]的)(?:应用|设备|电视)(?:目前|当前|暂时|暂|还|尚)?"
            + "不支持(?:这个|这项|此|该)(?:操作|功能|任务))"
            + "(?:哦|呢)?[。.!！]?");

    // Xiaomi may decline the selected provider while offering its own music player.
    // That is an explicit failure of the provider constraint, not a claim that NetEase started.
    private static final Pattern MUSIC_PROVIDER_REFUSAL = Pattern.compile(
            "(?:(?:很抱歉|抱歉|对不起|不好意思)[, ]*)?(?:小爱|当前|目前|本机|本设备|该设备|这台电视|电视端|本平台|我){0,2}"
            + "(?:暂不支持|还不支持|暂时不支持|目前不支持|不支持|无法|暂时无法)(?:直接)?(?:使用|通过|用|调用|在|从)"
            + "网易云(?:音乐)?(?:源|APP|客户端|应用程序|应用|软件|平台|服务|中|上|里|来|进行|直接|的|为您|为你|歌曲|点歌|来为您){0,4}"
            + "(?:播放|点播)(?:歌曲|音乐)?"
            + "(?:[,。 ]+(?:将|将会|我会|小爱会|会|改为|改用)(?:继续|默认|优先|自动|尝试|先|仍然|仍|直接|为您|为你|暂时|转而|改为){0,3}"
            + "(?:使用|用)?(?:当前|默认|系统|自带|内置|电视)(?:的)?音乐(?:播放器|服务商|来源|服务|应用|软件|平台|源|APP|客户端){0,2}"
            + "(?:来)?(?:为您|为你|帮您|帮你)?(?:播放|点播)(?:歌曲|音乐)?)?[。.!！]?", Pattern.CASE_INSENSITIVE);

    private static final Pattern LEARNING_REFUSAL = Pattern.compile("(?:对不起|抱歉)[, ]*我还在学习(?:中)?[。.!！]?");
    enum Kind { READ, APP, SONG, ARTIST, TITLE, INSTALL, SEARCH }
    static final class Scope {
        final Kind kind;
        final String readTool, name, artist, title;
        private Scope(Kind kind, String readTool, String name, String artist, String title) {
            this.kind=kind; this.readTool=readTool; this.name=name; this.artist=artist; this.title=title;
        }
        boolean allows(String tool, JSONObject args) {
            if (kind == Kind.READ) return readTool == null ? isRead(tool) : readTool.equals(tool);
            if (kind == Kind.APP) {
                if ("list_apps".equals(tool)) return true;
                Object target = args.opt("name");
                return "open_app".equals(tool) && (exact(name,target) || (target instanceof String
                        && isBiliClient(name) && isBiliClient((String)target)));
            }
            if (kind == Kind.INSTALL) return "install_app".equals(tool) && args.length()==1 && BILI_PACKAGE.equals(args.opt("package"));
            if (kind == Kind.SEARCH) return "search_video".equals(tool) && args.length()==2 && args.opt("app") instanceof String
                    && isBiliClient((String)args.opt("app")) && title.equals(args.opt("query"));
            if (kind == Kind.TITLE) return "play_netease_title".equals(tool) && args.length()==1 && exact(title,args.opt("title"));
            return kind == Kind.SONG && "play_netease_music".equals(tool)
                    && exact(artist,args.opt("artist")) && exact(title,args.opt("title"));
        }
    }
    static Scope scope(String original) {
        if (original == null || original.length() > 200) return null;
        // Match the original string so the keyword retains script, punctuation and internal spacing.
        Matcher search = BILI_SEARCH.matcher(original.trim());
        if (search.matches()) {
            String keyword=search.group(1).trim();
            if (keyword.isEmpty() || keyword.length()>120 || SECOND_OR_UNSAFE.matcher(keyword).find()
                    || SEARCH_EXTRA_ACTION.matcher(keyword).find() || keyword.matches("(?s).*\\p{Cc}.*")) return null;
            return new Scope(Kind.SEARCH,null,BILI_PACKAGE,null,keyword);
        }
        String q = normalize(original);
        if (SECOND_OR_UNSAFE.matcher(q).find()) return null;
        if (ReadOnlyRefusalText.isSupportedQuery(original)) return new Scope(Kind.READ,null,null,null,null);
        if (LEVEL.matcher(q).matches() || STATUS.matcher(q).matches()) return new Scope(Kind.READ,"get_status",null,null,null);
        if (APPS.matcher(q).matches()) return new Scope(Kind.READ,"list_apps",null,null,null);
        if (TIMER.matcher(q).matches()) return new Scope(Kind.READ,"get_sleep_timer",null,null,null);
        if (INSTALL_BILI.matcher(q).matches()) return new Scope(Kind.INSTALL,null,BILI_PACKAGE,null,null);
        Matcher command = MUSIC_COMMAND.matcher(q);
        if (command.matches()) return musicTarget(command.group(1).trim(), false);
        Matcher open = OPEN.matcher(q);
        if (open.matches()) {
            String name = open.group(1).trim();
            String lower = name.toLowerCase(Locale.ROOT);
            if (name.isEmpty() || name.contains("小爱") || name.contains("星仪") || lower.contains("xiaoai")
                    || lower.contains("xingyi")) return null;
            if (!isBiliClient(name) && !KNOWN_APP.matcher(name).matches() && !q.matches(".*(?:打开|启动)(?:这个|一个)?(?:名为|名称为)?(?:应用程序|应用|软件)\\s*.+")
                    && !q.matches(".*(?:这个应用|这个软件)[。.!！]?")) return null;
            return new Scope(Kind.APP,null,name,null,null);
        }
        return null;
    }
    /** A bare artist/title can refine a recent music request. The caller must establish that context. */
    static Scope musicSelection(String original) {
        if (original == null || original.length()>150) return null;
        String q = normalize(original).replaceFirst("[。.!！]$","").trim();
        if (SECOND_OR_UNSAFE.matcher(q).find() || MUSIC_COMMAND.matcher(q).matches() || OTHER_SPEECH.matcher(q).find()
                || MUSIC_COMMENT.matcher(q.replaceAll("《[^《》]{1,90}》","曲名")).find()) return null;
        int split=q.indexOf('的');
        if (split<1 || q.substring(0,split).matches(".*(?:网易云|帮我|给我|让我|想听|来首|来一首|推荐|听听|听一下|点首|能不能|能否|可不可以|说的|复述).*")) return null;
        return musicTarget(q, true);
    }
    private static Scope musicTarget(String target, boolean selectionOnly) {
        int quote = target.indexOf('《');
        String requestTerms = quote < 0 ? target : target.substring(0,quote);
        if (target.isEmpty() || OTHER_MEDIA.matcher(requestTerms).find() || UNBOUND_TARGET.matcher(requestTerms).find()
                || NO_TRACK.matcher(target).find() || LIBRARY.matcher(target).matches())
            return null;
        Matcher byArtist = ARTIST.matcher(target);
        if (byArtist.matches()) {
            String artist = byArtist.group(1).trim();
            if (selectionOnly || artist.isEmpty() || NON_ARTIST.matcher(artist).find()) return null;
            return new Scope(Kind.ARTIST, null, null, artist, null);
        }
        Matcher song = SONG.matcher(target);
        if (song.matches()) {
            String artist = song.group(1).trim(), title = capturedTitle(song,2);
            if (artist.isEmpty() || NON_ARTIST.matcher(artist).find() || title.isEmpty()) return null;
            return new Scope(Kind.SONG,null,null,artist,title);
        }
        Matcher byTitle = TITLE_ONLY.matcher(target);
        if (selectionOnly || !byTitle.matches()) return null;
        String title = capturedTitle(byTitle,1);
        return title.isEmpty() ? null : new Scope(Kind.TITLE,null,null,null,title);
    }
    private static String capturedTitle(Matcher m,int first) {
        String title = (m.group(first)!=null ? m.group(first) : m.group(first+1)).trim();
        return title.contains("“") || title.contains("”") ? "" : title;
    }
    static boolean nativeMusicReply(Scope selection,String reply) {
        if (selection==null || selection.kind!=Kind.SONG || reply==null || reply.length()>180) return false;
        if ("native_music_provider_unavailable".equals(classify(selection,reply))) return true;
        String title=Pattern.quote(selection.title), artist=Pattern.quote(selection.artist);
        return normalize(reply).matches("(?:好的[, ]*)?(?:一起来听|为你播放|为您播放|正在播放)[ ]*(?:"
                +artist+"的)?(?:《"+title+"》|"+title+")[。.!！]?");
    }
    static String classify(Scope scope, String reply) {
        if (scope == null || reply == null || reply.length() > 120) return null;
        String value = normalize(reply);
        if ((scope.kind == Kind.SONG || scope.kind == Kind.ARTIST || scope.kind == Kind.TITLE) && MUSIC_PROVIDER_REFUSAL.matcher(value).matches())
            return "native_music_provider_unavailable";
        return FIXED.matcher(value).matches() || LEARNING_REFUSAL.matcher(value).matches()
                ? "native_capability_" + scope.kind.name().toLowerCase(Locale.ROOT) : null;
    }
    /** Unscoped requests can reach the harness on a stock, complete capability refusal.
     * This is a routing decision; scope() intentionally still returns null for them. */
    static boolean isUnscopedRefusal(String original, String reply) {
        if (reply == null || reply.length() > 120 || original == null || original.isEmpty() || original.length()>1000) return false;
        if (looksLikeBiliSearch(original)) return false; // an unbound compound search must not widen to arbitrary tools
        String q = normalize(original);
        if (SECOND_OR_UNSAFE.matcher(q).find()
                || q.matches(".*(?:不要|别再|别帮我|(?:^|请)别|取消刚才|停止刚才|权限|儿童锁|家长控制|验证码).*")) return false;
        String answer=normalize(reply);
        if (FIXED.matcher(answer).matches() || LEARNING_REFUSAL.matcher(answer).matches()) return true;
        if (!MUSIC_PROVIDER_REFUSAL.matcher(answer).matches() || !MUSIC_SUBJECT.matcher(q).find()) return false;
        String request=q.replaceAll("《[^《》]{1,90}》","曲名");
        return !OTHER_MEDIA.matcher(request).find() && !MUSIC_COMMENT.matcher(request).find()
                && MUSIC_REQUEST.matcher(request).find();
    }
    static boolean isRead(String tool) {
        return "get_status".equals(tool) || "get_sleep_timer".equals(tool) || "list_apps".equals(tool);
    }
    static boolean isBiliClient(String name) {
        if (name == null) return false;
        String label = normalize(name);
        return BILI_PACKAGE.equals(label) || BILI_LABEL.matcher(label).matches();
    }
    static boolean looksLikeBiliSearch(String original) {
        return original!=null && BILI_SEARCH.matcher(original.trim()).matches();
    }
    private static boolean exact(String expected, Object actual) {
        return actual instanceof String && normalize(expected).equalsIgnoreCase(normalize((String)actual));
    }
    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("[ \\t]+"," ");
    }
    private GenericRefusalText() {}
}
