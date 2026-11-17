package xyz.erapi0neer.xingyi.agent;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Simple, unambiguous TV commands, matched locally so their tool runs at once. Since 2026-11-05 every sentence XiaoAi
 * hears comes to 星仪, and 「音量调大一点」 used to wait for a model round (2–5 s). Anything that is not exactly one of
 * these commands returns null and is handled by the model exactly as before — when in doubt, null.
 */
public final class QuickCommand {
    public final String tool;
    public final JSONObject args;

    private QuickCommand(String tool, JSONObject args) { this.tool = tool; this.args = args; }

    /** What screen_off answers, and what the tool itself returns as its message: the screen is off, the TV is in
     * standby and XiaoAi still hears you. Shared so the two can never drift apart again. */
    public static final String SCREEN_OFF = "屏幕关了，电视还在待机，叫一声小爱同学就能用";
    public static final String SCREEN_OFF_ALREADY = "屏幕本来就是关着的，电视在待机";

    /** Wake words, politeness and trailing particles, plus the punctuation and spaces around them. The fillers are what
     * the user really said in front of the command (2026-11-08 「嗯那个伴奏模式」, 11-09 「然后关闭显示器」): they carry nothing.
     * Not 再 / 接着 (「再说一遍」「接着放」 are commands themselves), not 现在, and not 是 / 就 alone (「是不是静音」). */
    private static final String PUNCT = "[\\s　。！？!?，,、]";
    private static final Pattern LEAD = Pattern.compile("^(?:小爱同学|小爱|星仪|请|麻烦|帮我|给我|把|我说的是|我是说|然后|那就|那个|"
            + "嗯|呃|额|诶|哎|唉|哦|噢|啊|" + PUNCT + ")+");
    private static final Pattern TAIL = Pattern.compile("(?:一下|吧|呀|啊|呢|了|" + PUNCT + ")+$");

    private static final Pattern SET = Pattern.compile("^(?:音量|声音)(?:设置为|设为|调到|调成|设成|开到|到)(.+)$");
    /** 「音量最大」「声音开到最大」「音量开满」 (2026-11-09): the top of the range is a level, not a step up. */
    private static final Pattern SET_MAX = Pattern.compile("^(?:音量|声音)(?:设置为|设为|调到|调成|设成|开到|到|开)?(?:最大声|最大|最高|满)$");
    private static final Pattern UP = Pattern.compile("^(?:(?:音量|声音)(?:再|稍微)?(?:调|开|放)?[大高响](?:一点点|一点|一些|点|些)?|(?:再|稍微)?[大高]声(?:一点|点|些)?|"
            + "(?:再|稍微)?大点声|调[大高](?:一点|点)?(?:声音|音量)|音量(?:加|加大))$");
    private static final Pattern DOWN = Pattern.compile("^(?:(?:音量|声音)(?:再|稍微)?(?:调|开|放)?[小低轻](?:一点点|一点|一些|点|些)?|(?:再|稍微)?[小低轻]声(?:一点|点|些)?|"
            + "(?:再|稍微)?[小轻]点声|调[小低](?:一点|点)?(?:声音|音量)|音量(?:减|减小))$");
    /** Said as a complaint, as Gemini on Google TV takes 「the screen is too dim」 (2026-11-06 overnight pass): 「太吵了」
     * 「声音太大了」 turn it down, 「声音太小了」「太小声了」 up. Not 「听不清」: that may be the words, not the volume. */
    private static final Pattern TOO_LOUD = Pattern.compile(
            "^(?:(?:(?:电视)?(?:声音|音量)|电视)(?:有点|有些)?太(?:大|吵|响)(?:声)?|(?:有点|有些)?太(?:吵|响|大声)|(?:有点|好)吵|吵死)$");
    private static final Pattern TOO_QUIET = Pattern.compile(
            "^(?:(?:(?:电视)?(?:声音|音量)|电视)(?:有点|有些)?太(?:小|轻)(?:声)?|(?:有点|有些)?太小声|听不见声音|声音听不见)$");
    private static final Set<String> UNMUTE = words("取消静音", "打开声音", "恢复声音", "开声音");
    private static final Set<String> PAUSE = words("暂停", "暂停播放", "先暂停");
    /** A bare 继续 resumes 星仪's last task (Replies.resume), so only the wordings that name playing are quick commands. */
    private static final Set<String> PLAY = words("继续播放", "恢复播放", "接着放", "接着播", "接着看");
    private static final Set<String> NEXT = words("下一首", "下一集", "下一个", "下一曲", "切歌", "换一首", "换首歌");
    private static final Set<String> PREVIOUS = words("上一首", "上一集", "上一个", "上一曲");
    /** 别放了 / 不放了 also without 了: the trailing particle is stripped before matching. */
    private static final Set<String> STOP = words("停止播放", "别放了", "不放了", "别放", "不放");
    private static final Set<String> HOME = words("回到桌面", "返回桌面", "回桌面", "回到主页", "返回主页", "回主页", "回到首页", "返回首页");
    private static final Set<String> BACK = words("返回", "返回上一页", "返回上一级");
    /** 伴奏 / 原唱 是网易云的随心唱：2026-11-08 用户为这句话说了五遍，每遍 47–113 秒、7–16 轮按键看图。一句话就是
     * netease_karaoke，工具自己调出控制栏、找到麦克风图标、读出现在的状态、需要时切换并核对。 */
    private static final String KARAOKE_SUBJECT = "(?:这|那|这个|那个)?(?:一首|首歌|首|个|歌曲|歌|曲)?";
    /** 「切换到伴奏模式」 (2026-11-09 20:11, said once and missed: 105 s and 11 model rounds). The alternatives are
     * ordered longest first — 切换到 must be tried before 切换 and 切. */
    private static final Pattern BANZOU = Pattern.compile("^" + KARAOKE_SUBJECT
            + "(?:切换到|切换成|切换为|切换|切成|切到|切|换成|换到|换|转到|转成|改成|改为|调成|调到|调为|设为|设成|变成|变|"
            + "开成|开启|打开|开|用|来)?伴奏(?:模式)?$");
    private static final Pattern YUANCHANG = Pattern.compile("^" + KARAOKE_SUBJECT
            + "(?:切换回|切换到|切换成|切换为|切换|切回|切成|切到|切|换成|换回|换到|换|转回|转到|改回|改成|调成|调到|变回|变|"
            + "恢复|还原|回到|回)?原唱(?:模式)?$");
    private static final Pattern MIC_ON = Pattern.compile("^" + KARAOKE_SUBJECT + "(?:打开|开启|开)?随心唱(?:模式)?$");
    private static final Pattern MIC_OFF = Pattern.compile("^" + KARAOKE_SUBJECT + "(?:关掉|关闭|关|停掉|退出)?随心唱(?:模式)?$"
            // 「把随心唱关了」: normalise() takes 把 and 了 off, leaving 「随心唱关」
            + "|^" + KARAOKE_SUBJECT + "随心唱(?:模式)?(?:关掉|关闭|关|停掉)$");
    /** 2026-11-05 on the TV: 「亮屏」 took two model rounds (4.9 s) to say the screen was already on. */
    /** Read off the TV's own clock, no model (2026-11-06 overnight pass: 「现在几点」 took a 2–3 s model round). */
    private static final Set<String> TIME = words("几点", "现在几点", "现在几点钟", "几点钟", "现在什么时间", "现在是几点", "现在时间",
            "现在什么时候");
    private static final Set<String> DATE = words("今天几号", "今天几月几号", "今天几月几日", "今天是几号", "今天日期", "今天多少号",
            "今天是几月几号", "几号", "今天是什么日期");
    private static final Set<String> WEEKDAY = words("今天星期几", "今天周几", "今天礼拜几", "今天是星期几", "今天是周几", "星期几",
            "今天是礼拜几");
    /** The Chinese calendar, from data (Almanac): a model guesses lunar dates badly. */
    private static final Set<String> LUNAR = words("今天农历几号", "农历今天几号", "今天农历多少", "今天农历是几号", "农历几号",
            "今天的农历", "今天农历", "农历今天是几号", "今天阴历几号", "阴历几号", "今天阴历多少", "今天是农历几号", "今天农历几月几号",
            "今天是农历多少", "今天阴历是几号");
    private static final Set<String> TERM = words("今天什么节气", "现在什么节气", "今天是什么节气", "现在是什么节气", "下一个节气是什么",
            "下个节气是什么", "下一个节气", "最近是什么节气", "这几天是什么节气");
    private static final Set<String> NEXT_FESTIVAL = words("下一个节日是什么", "下个节日是什么", "下一个节日", "最近有什么节日",
            "快到什么节日", "下一个节是什么", "下一个过什么节", "最近有什么节", "接下来有什么节日");
    private static final Set<String> FESTIVAL = words("今天什么节日", "今天是什么节日", "今天有什么节日", "今天是什么节", "今天过什么节",
            "今天是不是什么节日", "今天是什么日子", "今天什么日子");
    /** 「明天上班吗」: the State Council's schedule decides, not the weekday (调休 Saturdays, the days off). */
    private static final Pattern WORKDAY = Pattern.compile("^(今天|明天|后天)(?:是不是|是)?(?:要|用|需要)?(?:上班|放假|休息|工作日|调休|周末|休息日|假期|节假日)(?:吗|么|不)?$"
            + "|^(今天|明天|后天)(?:要不要|用不用|需不需要)上班$");
    /** 「明天天气怎么样」「现在几度」「空气质量怎么样」: straight to the weather tool, whose answer is spoken as it is
     * (2026-11-06: the most asked question; 2.5-4.4 s through the model, about 1 s without it). */
    private static final Pattern WEATHER = Pattern.compile(
            "^(今天|明天|后天|大后天|这几天|最近几天|最近|周末|这周末|这个周末|今晚|今天晚上|明早|明天早上|明晚|明天晚上|现在|外面|今天外面|"
            + "(?:这个|这|本|下个|下)?(?:周|星期|礼拜)[一二三四五六日天])?"
            + "的?(?:天气(?:怎么样|如何|咋样|好不好|好吗|情况|预报)?|(?:是)?什么天气|天气是什么样)$");
    /** 「明天热不热」「今晚几度」「风大吗」: one day's (or part of a day's) forecast. */
    private static final Pattern HOW = Pattern.compile("^(今天|明天|后天|大后天|今晚|今天晚上|明早|明天早上|明晚|明天晚上|现在|外面|今天外面|外面现在|现在外面)?"
            + "(?:外面)?的?(?:几度|多少度|气温多少|温度多少|气温怎么样|温度怎么样|多少温度|冷不冷|冷吗|会冷吗|会不会冷|热不热|热吗|会热吗|会不会热|凉不凉|"
            + "最高温度多少|最低温度多少|最高气温多少|最低气温多少|最高几度|最低几度|最高多少度|最低多少度|"
            + "风大吗|风大不大|风大不|刮风吗|有风吗)$");
    /** 现在 / 外面 / nothing said: today's forecast (which has the weather now in it). */
    private static String today(String day) {
        return day == null || "现在".equals(day) || "外面".equals(day) || "今天外面".equals(day) || "外面现在".equals(day)
                || "现在外面".equals(day) ? "今天" : day;
    }

    /** 「还有几天过年」「离国庆还有多少天」「冬至还有几天」: the almanac's countdown, when it knows the name. */
    private static final Pattern COUNTDOWN = Pattern.compile("^(?:还有|还剩)(?:几天|多少天|多久)(?:就)?([\u4e00-\u9fa5]{2,6})$"
            + "|^(?:离|距离|到)?([\u4e00-\u9fa5]{2,5}?)还有(?:几天|多少天|多久)$");
    /** 「我定了哪些预约」: the appointment book, said as list_schedules says it (2026-11-06: two model rounds before). */
    private static final Set<String> SCHEDULE_LIST = words("我定了哪些预约", "我有哪些预约", "有哪些预约", "我的预约", "看看我的预约",
            "有什么预约", "现在有什么预约", "我设了哪些预约", "我定了什么预约", "有几个预约", "我有什么预约", "我定的预约",
            "查一下预约", "看一下预约", "我都定了哪些预约", "我有什么预约吗", "有预约吗", "我有预约吗", "有没有预约");
    /** 「今天大盘怎么样」「上证指数多少点」「茅台股价多少」: stock_quote (2026-11-06). 昨晚 / 昨天 either side of the name
     * (2026-11-10 morning sweep: 「美股昨晚怎么样」 took 2.6 s through the model, and the close is what the tool reads). */
    private static final Pattern MARKET = Pattern.compile("^(?:今天|现在|昨晚|昨天晚上|昨天|昨夜)?的?(大盘|股市|A股|上证指数|上证|深证成指|创业板|沪深300|恒生指数|恒指|港股|"
            + "纳斯达克|纳指|道琼斯|道指|标普|美股)(?:现在|昨晚|昨天晚上|昨天|昨夜)?"
            + "(?:怎么样|多少点|多少|行情怎么样|行情|涨了吗|跌了吗|是涨是跌|涨了还是跌了)?$");
    /** 「查询制储的股价」 (2026-11-09): the verb in front is not part of the name — stock_quote was sent 「查询制储」
     * and the lookup failed. Longest first: 帮我查一下 before 帮我查 and 查. */
    private static final Pattern STOCK_VERB = Pattern.compile("^(?:帮我查一下|帮我查|查一下|看一下|问一下|查询|查查|查下|看看|看下|查)");
    /** A name may carry a space between Latin words (「MINI MAX」, said on 2026-11-06 and missed); it is at most 12
     * characters and at least two, so a single Latin letter is no name. */
    private static final Pattern STOCK = Pattern.compile("^(?:现在|今天)?"
            + "([\\u4e00-\\u9fa5A-Za-z0-9][\\u4e00-\\u9fa5A-Za-z0-9 ]{0,10}?[\\u4e00-\\u9fa5A-Za-z0-9])"
            + "(?:现在|今天|目前|最新)?的?(?:股价|股票价格)(?:现在)?(?:多少|是多少|多少钱|怎么样)?$");
    /** 「狂飙豆瓣多少分」「流浪地球2的评分是多少」: movie_rating. 「繁花好看吗」 is the model's (it may say more than a score). */
    private static final Pattern MOVIE = Pattern.compile("^(?:电影|电视剧|动画|综艺)?《?([\\u4e00-\\u9fa5A-Za-z0-9·：:]{2,16}?)》?"
            + "(?:这部(?:电影|剧|片子|片|动画)|这个(?:电影|剧|综艺|动画|片子))?的?(?:豆瓣)?(?:评分|分数)(?:是|有)?(?:多少|多少分|几分|怎么样|高吗|高不高)?$"
            + "|^(?:电影|电视剧|动画|综艺)?《?([\\u4e00-\\u9fa5A-Za-z0-9·：:]{2,16}?)》?(?:在)?豆瓣(?:上)?(?:多少分|几分|什么分|评分多少|评分是多少|评分|打几分|有几分)$");
    /** 「美元汇率」「一百欧元换多少人民币」: exchange_rate. */
    private static final String MONEY = "(美元|美金|欧元|日元|港币|港元|英镑|澳元|加元|韩元|泰铢|新加坡元|瑞士法郎|卢布|新台币|台币)";
    private static final Pattern FX = Pattern.compile("^(?:今天|现在)?的?(?:人民币)?(?:对|兑)?" + MONEY + "(?:兑人民币|对人民币|和人民币)?的?(?:汇率|牌价)(?:是)?(?:多少)?$");
    private static final Pattern FX_AMOUNT = Pattern.compile("^([0-9]{1,7}|[一两二三四五六七八九十百千万]{1,5})?" + MONEY
            + "(?:现在)?(?:能)?(?:换|兑换|兑|等于|是|合)(?:多少)(?:人民币|钱|块钱|块|元)?$");
    private static final Pattern FX_RMB = Pattern.compile("^(?:今天|现在)?的?人民币(?:的)?汇率(?:是)?(?:多少)?$");
    /** 「现在有什么热搜」「B站有什么热门视频」: hot_list. Not 「微博热搜」: Weibo's board needs a login, the model searches. */
    private static final Set<String> HOT = words("今天有什么热搜", "现在有什么热搜", "最近有什么热搜", "热搜", "热搜榜", "百度热搜", "今天热搜",
            "今天的热搜", "看看热搜", "热搜有什么", "热搜是什么", "现在热搜是什么", "今天热搜是什么", "今天热搜有什么", "有什么热搜");
    private static final Set<String> BILI_HOT = words("B站热门", "B站有什么热门视频", "B站最近有什么热门", "B站热门视频", "哔哩哔哩热门",
            "B站今天什么最火", "B站最近什么最火", "B站排行榜", "B站有什么热门", "b站热门", "b站有什么热门视频",
            // 2026-11-10 morning sweep: 「B站现在什么最火」 took 2.0 s through the model, hot_list answers it whole
            "B站现在什么最火", "B站什么最火", "B站最火的视频是什么", "B站最火的视频", "B站现在最火的是什么");
    /** 「百度热搜第一是什么」「热搜前三」 (2026-11-10): the first n of the board, n as it is said. */
    private static final Pattern HOT_TOP = Pattern.compile("^(?:百度|微博)?热搜(?:榜)?(?:上|里|中的|里的)?(?:第|前)"
            + "([一二三四五六七八九十]|[1-9]|10)(?:条|个|名|位)?(?:是什么|是谁|是什么内容|是什么事|都有什么|有什么)?$");
    /** 「微博热搜」 (2026-11-10): Weibo's own board needs a login, so the tool answers with Baidu's list and says so. */
    private static final Set<String> WEIBO_HOT = words("微博热搜", "微博热搜是什么", "微博热搜榜", "今天的微博热搜", "微博热搜有什么",
            "微博的热搜", "微博热搜都有什么");

    /** 「今天金价多少」「黄金多少钱一克」: gold_price. */
    private static final Set<String> GOLD = words("金价", "今天金价", "今天金价多少", "金价多少", "现在金价多少", "金价是多少", "今天金价是多少",
            "黄金价格", "今天黄金价格", "黄金多少钱一克", "今天黄金多少钱一克", "金价多少钱一克", "今天金价多少钱一克", "国际金价", "国内金价",
            "现在黄金多少钱", "黄金现在多少钱", "金价怎么样", "今天金价怎么样", "黄金涨了还是跌了", "金价涨了吗", "金价跌了吗");
    /** 「双色球开奖结果」「大乐透开奖号码是多少」「昨天排列三开的什么」: lottery. 「最新一期」 is said in front of the game and
     * 「最新」 after it (2026-11-10 morning sweep: 「双色球最新开奖号码是多少」 took 2.3 s through the model). */
    private static final Pattern LOTTERY = Pattern.compile("^(?:今天|昨天|昨晚|最新一期|最新|最近一期|最近|上一期|这期|这一期)?的?(双色球|福彩3D|福彩三D|3D|七乐彩|快乐8|快乐八|"
            + "超级大乐透|大乐透|排列三|排列3|排三|排列五|排列5|排五|七星彩|7星彩)"
            + "(?:最新一期|最新|最近一期|最近|上一期|这期|这一期|今天|昨天|昨晚)?(?:的)?"
            + "(?:开奖结果|开奖号码|开奖号|开奖|中奖号码|结果|号码)?"
            + "(?:是多少|是什么|是啥|多少|出来了吗|开了吗|开的什么|开的啥)?$");

    /** 「尼克斯昨天赢了吗」「湖人下一场什么时候打」: nba_games for a team. */
    private static final Pattern NBA_TEAM = Pattern.compile("^(?:NBA|nba)?(?:今天|昨天|最近|上一场)?的?(" + Sports.names() + ")队?"
            + "(?:今天|昨天|最近|上一场|这场)?(?:比赛)?(?:赢了吗|输了吗|赢了没|赢没赢|比赛结果|结果|打得怎么样|打得咋样|比分多少|比分|几比几|"
            + "下一场什么时候|下一场什么时候打|下一场是什么时候|什么时候比赛|什么时候打)$");
    /** 「今天有什么NBA比赛」「昨天NBA比分」: nba_games for a day (not 今晚: NBA nights are Beijing mornings, the model says so).
     * 「今天有NBA比赛吗」「今天有没有NBA比赛」「NBA今天有比赛吗」 (2026-11-10 morning sweep: 2.9 s through the model). */
    private static final Pattern NBA_DAY = Pattern.compile("^(?:(今天|昨天|明天)?(?:有没有|有什么|有哪些|有)?的?(?:NBA|nba)"
            + "(?:有没有|有什么|有哪些|有)?(?:比赛|比分|赛程|赛果)?(?:结果|怎么样|有哪些)?(?:吗|么)?"
            + "|(?:NBA|nba)(今天|昨天|明天)(?:有没有|有什么|有哪些|有)?的?(?:比赛|比分|赛程|赛果)?(?:结果|怎么样|有哪些)?(?:吗|么)?)$");
    /** 「湖人昨天赢了吗」「尼克斯赢了吗」「湖人上一场比分」 (2026-11-10): the last game. The 昨天 used to be dropped, so the
     * question was asked as today's and the tool answered with the next game. */
    private static final Pattern NBA_LAST = Pattern.compile("昨天|昨晚|上一场|赢了吗|输了吗|赢了没|赢没赢|输了没");

    /** 「曼联昨天赢了吗」「申花下一场什么时候」: football_games for a club. */
    private static final Pattern FOOTBALL_TEAM = Pattern.compile("^(?:今天|昨天|最近|上一场)?的?(" + Football.names() + ")队?"
            + "(?:今天|昨天|最近|上一场|这场)?(?:比赛)?(?:赢了吗|输了吗|赢了没|赢没赢|比赛结果|结果|踢得怎么样|打得怎么样|比分多少|比分|几比几|"
            + "下一场什么时候|下一场什么时候踢|下一场是什么时候|什么时候比赛|什么时候踢)$");
    /** 「今天有什么英超比赛」「昨天中超比分」: football_games for a league's day. */
    private static final Pattern FOOTBALL_DAY = Pattern.compile("^(今天|昨天|明天)?(?:有什么|有哪些)?的?(英超|西甲|德甲|意甲|法甲|欧冠|中超)"
            + "(?:比赛|比分|赛程|赛果)?(?:结果|怎么样|有哪些)?$");

    /** 「纽约现在几点」「东京几点了」: world_clock (before 2026-11-06 another city's time went to the model). */
    private static final Pattern WORLD_TIME = Pattern.compile("^(?:现在)?(" + WorldClock.names() + ")(?:现在|那边|那里|当地)?(?:是)?"
            + "(?:几点|几点钟|什么时间|时间|几号)(?:了)?$");
    /** 「伦敦和北京时差几个小时」「北京跟纽约差几个小时」. */
    private static final Pattern TIME_DIFF = Pattern.compile("^(?:(" + WorldClock.names() + ")(?:和|跟|与)?(?:北京|中国|国内)?|(?:北京|中国|国内)(?:和|跟|与)("
            + WorldClock.names() + "))的?(?:时差|差几个小时|差几小时|差多少小时|差多久)(?:是)?(?:多少|几个小时|几小时|多少小时|多久)?$");
    /** 「CCTV5 现在在放什么」「湖南卫视在播什么」: tv_guide, now — only when the channel is one the guide knows. */
    private static final Pattern TV_NOW = Pattern.compile("^(.{2,10}?)(?:现在|正在|这会儿)?(?:在)?(?:播|放|演)(?:的是)?(?:什么|啥)(?:节目)?$");
    /** 「今晚湖南卫视有什么节目」「CCTV1今晚播什么」. */
    private static final Pattern TV_PART_FIRST = Pattern.compile("^(今晚|今天晚上|明晚|明天晚上|明天)(.{2,10}?)(?:有什么|有啥|播什么|放什么|演什么)(?:节目|好看的)?$");
    private static final Pattern TV_PART_LAST = Pattern.compile("^(.{2,10}?)(今晚|今天晚上|明晚|明天晚上|明天)(?:有什么|有啥|播什么|放什么|演什么)(?:节目|好看的)?$");
    /** 「最近有什么新闻」「今天有什么国际新闻」「科技新闻」: news. Not 新闻联播 (a programme). */
    private static final Pattern NEWS = Pattern.compile("^(?:今天|最近|现在|最新)?(?:有什么|有啥|有哪些)?(?:最新的|最新)?(国内|国际|社会|财经|体育|科技|AI|ai|人工智能|大模型|数码)?(?:圈)?"
            + "(?:最近|今天)?(?:有什么|有啥|有哪些)?(?:新的|最新的|最新|新)?的?(?:新闻|要闻|头条|消息)(?:有哪些|有什么|说了什么)?$");
    /** 「最近有什么电影上映」「有什么好看的韩剧」: movie_list (2026-11-07). */
    private static final Pattern MOVIE_LIST = Pattern.compile("^(?:最近|现在|这几天|今天)?(?:有什么|有啥|有哪些)?(?:好看的|热门的|新的|新上映的)?"
            + "(电影|剧|电视剧|国产剧|美剧|韩剧|日剧|动画|综艺)(?:上映|在上映|好看|推荐|可以看)?(?:吗|呀)?$"
            + "|^(?:最近|现在)?(?:有什么|有啥)?(电影)(?:在)?(?:上映|热映)$|^(?:推荐|推荐一下|推荐几部|推荐个)(?:好看的)?(电影|剧|电视剧|美剧|韩剧|日剧|动画|综艺)$");
    /** What the name slot caught that is no singer (2026-11-07 review): a request to play (「播放周杰伦的新歌」 plays it),
     * a time, a pronoun, the app. */
    private static final Pattern NOT_SINGER = Pattern.compile("^(?:播放|播|放|听|来|打开|点|给我放|帮我放|我想听|想听|我要听|要听)"
            + "|今天|现在|最近|这周|本周|今年|明天|网易云|有什么|有没有|什么|哪些|他|她|它|你|我|这首|那首");
    /** What the title slot caught that is no title: 「这部电影」「这个剧」「它的」 (the search's first hit was read for them). */
    private static final Pattern NOT_TITLE = Pattern.compile("^(?:这|那|它|他|她|该|此)|^(?:电影|电视剧|剧|片子|动画|综艺|节目)$|的$");
    /** 「繁花在哪个平台能看」「庆余年第二季一共几集」 (2026-11-10 morning sweep: 4.5 s and 2.0 s through the model): the
     * title's own page says where it streams and how many episodes it has, so it is one movie_rating with `where`. */
    private static final Pattern MOVIE_WHERE = Pattern.compile("^(?:电影|电视剧|动画|综艺)?《?([\\u4e00-\\u9fa5A-Za-z0-9·：:]{2,16}?)》?"
            + "(?:这部(?:电影|剧|片子|片|动画)|这个(?:电影|剧|综艺|动画|片子))?(?:一共几集|一共多少集|总共有几集|总共几集|有几集|多少集|"
            + "在哪个平台能看|在哪个平台可以看|在哪个平台|在什么平台|在哪个软件|在哪个app|在哪个APP|在哪看|在哪能看|在哪可以看|哪里能看|哪里可以看|哪里看)$");
    /** 「这个在哪看」「刚才那个有几集」「我在哪看」: a pronoun or a reference is not a title — the model knows what it means. */
    private static final Pattern NOT_WHERE_TITLE = Pattern.compile("^(?:这|那|它|他|她|该|此|我|你|刚)|^(?:电影|电视剧|剧|片子|动画|综艺|节目)$|的$");
    /** 「林俊杰最近发新歌了吗」「周杰伦有新歌吗」: music_new (2026-11-07); the name is two to eight characters. */
    private static final Pattern MUSIC_NEW = Pattern.compile("^(.{2,8}?)(?:最近|最近有没有|这阵子)?(?:发|出|有)(?:了)?(?:什么)?新歌(?:了)?(?:吗|没有|没)?$"
            + "|^(.{2,8}?)(?:最新的歌|最新的单曲|的新歌)(?:是哪首|是什么|叫什么|有哪些)?$");
    /** 「现在什么歌最火」「网易云热歌榜」「最近有什么新歌」: hot_list on a NetEase chart. 「网易云热歌榜前三是什么」 (2026-11-10):
     * the first n songs, n as it is said. */
    private static final Pattern CHART = Pattern.compile("^(?:网易云)?(?:音乐)?的?(热歌榜|新歌榜|飙升榜|原创榜)"
            + "(?:前([一二三四五六七八九十]|[1-9]|10)(?:名|首|个|条)?(?:是什么|有什么|有哪些|都有什么)?|有什么|有哪些|是什么|都有什么)?$");
    private static final Set<String> HIT_SONGS = words("现在什么歌最火", "最近什么歌最火", "最近流行什么歌", "现在流行什么歌", "最近有什么好听的歌",
            "最近什么歌好听", "最火的歌是什么", "现在最火的歌是什么", "最近最火的歌", "热门歌曲");
    private static final Set<String> NEW_SONGS = words("最近有什么新歌", "有什么新歌", "最近出了什么新歌", "最新的歌有哪些");

    /** 「刚才是不是地震了」: earthquake nearby; 「最近哪里地震了」: the latest. */
    private static final Set<String> QUAKE_NEAR = words("刚才是不是地震了", "刚才地震了吗", "是不是地震了", "地震了吗", "刚刚是不是地震了", "刚刚地震了吗",
            "刚才是地震吗", "刚才有地震吗", "是地震了吗", "刚才是不是有地震", "刚刚是不是有地震", "刚才好像地震了", "刚刚好像地震了");
    private static final Set<String> QUAKE_LATEST = words("最近哪里地震了", "最近有地震吗", "最近哪里发生地震了", "哪里地震了", "最近的地震",
            "最新地震", "地震速报", "最近地震了吗", "今天哪里地震了", "今天有地震吗");
    /** 「今天油价多少」「92号汽油多少钱」: fuel_price here. */
    private static final Set<String> FUEL = words("今天油价多少", "油价多少", "现在油价多少", "油价是多少", "今天油价", "最近油价", "油价",
            "92号汽油多少钱", "九二号汽油多少钱", "95号汽油多少钱", "九五号汽油多少钱", "汽油多少钱一升", "油价涨了吗", "油价降了吗",
            "最近油价怎么样", "油价是多少钱", "今天的油价", "柴油多少钱一升");
    /** 「易方达蓝筹基金今天涨了多少」: fund_nav — only with 基金 said (without it the name may be a stock's). */
    private static final Pattern FUND = Pattern.compile("^(.{2,16}?)基金(?:今天|最新|现在)?的?(?:净值|涨了多少|跌了多少|怎么样|涨了吗|跌了吗|收益怎么样|表现怎么样)"
            + "(?:是多少|多少)?$");
    /** 「今天有什么番更新」「明天有什么新番」: bili_bangumi (2026-11-07). A show by its name is the model's. */
    private static final Set<String> BANGUMI_TODAY = words("今天有什么番更新", "今天有什么新番", "今天更新什么番", "今天有什么番", "今天更新了什么番",
            "今天有什么动漫更新", "今天有什么动画更新", "今天番剧更新", "今天的新番", "今天的番剧", "新番时间表", "番剧时间表", "番剧更新表", "今天有什么国创",
            "今天有什么国创更新", "今天更新什么国创", "今天有什么番剧更新", "今天B站有什么番更新", "今天b站有什么番更新");
    private static final Set<String> BANGUMI_TOMORROW = words("明天有什么番更新", "明天有什么新番", "明天更新什么番", "明天有什么番", "明天有什么番剧更新",
            "明天有什么国创", "明天有什么国创更新", "明天的新番");
    /** 「这周有什么新番」「最近有什么新番」 (2026-11-10 morning sweep: 7.1 s and three model rounds): the week's table. */
    private static final Set<String> BANGUMI_WEEK = words("这周有什么新番", "这周有什么番更新", "这周更新什么番", "这周有什么番", "这周有什么番剧更新",
            "这周有什么动漫更新", "这周有什么国创更新", "本周有什么新番", "最近有什么新番", "最近有什么番更新", "最近更新什么番",
            "这几天有什么番", "这几天有什么新番", "这几天更新什么番", "这几天有什么番更新");
    /** 「现在有什么天气预警」「有台风吗」: weather_alert (2026-11-07). */
    private static final Set<String> ALERT = words("现在有什么天气预警", "有什么天气预警", "有没有天气预警", "有天气预警吗", "现在有天气预警吗", "有预警吗",
            "现在有预警吗", "有没有气象预警", "有什么气象预警", "现在有什么预警", "气象预警", "天气预警", "今天有什么预警", "今天有天气预警吗");
    private static final Set<String> TYPHOON = words("有台风吗", "现在有台风吗", "最近有台风吗", "有没有台风", "台风来了吗", "台风在哪", "台风在哪里",
            "现在有没有台风", "台风到哪了", "台风现在在哪", "最近有没有台风", "台风要来了吗");
    /** 「今天看了多久电视」「昨晚看了多久电视」: tv_usage (2026-11-07, two model rounds before; its sentence is said as it is
     * now that its times are words). 「昨晚看了什么」 is the model's: it adds what 星仪 played (recall_history). */
    private static final Set<String> USAGE_TODAY = words("今天看了多久电视", "电视今天开了多久", "今天电视开了多久", "今天开了多久电视",
            "今天看了多长时间电视", "今天电视开了多长时间", "今天看电视看了多久");
    private static final Set<String> USAGE_YESTERDAY = words("昨天看了多久电视", "昨天电视开了多久", "电视昨天开了多久", "昨天看了多长时间电视",
            "昨天电视开了多长时间", "昨天看电视看了多久");
    private static final Set<String> USAGE_LAST_NIGHT = words("昨晚看了多久电视", "昨天晚上看了多久电视", "昨晚电视开了多久", "昨天晚上电视开了多久",
            "昨晚我看了多久电视", "昨天晚上我看了多久电视", "昨晚看电视看了多久");
    /** 「今天黄历」「今天宜什么忌什么」: almanac (2026-11-07); a thing to do on a day is the model's. */
    private static final Set<String> ALMANAC_TODAY = words("今天黄历", "今天的黄历", "黄历", "看看黄历", "查一下黄历", "今天宜什么", "今天忌什么",
            "今天宜什么忌什么", "今天黄历宜什么", "今天黄历宜什么忌什么", "今天宜忌", "今天的宜忌", "今天黄历怎么说", "今天是好日子吗", "今天是不是好日子");
    private static final Set<String> ALMANAC_TOMORROW = words("明天黄历", "明天的黄历", "明天宜什么", "明天忌什么", "明天宜什么忌什么", "明天的宜忌",
            "明天黄历宜什么", "明天是好日子吗");
    /** 「今天宜搬家吗」「明天适合结婚吗」「后天宜不宜出行」 (2026-11-10 morning sweep: 2.3 s through the model): the almanac's
     * verdict on a thing it has a 宜/忌 line for. Only these things — anything else about a day is still the model's. */
    private static final String THINGS = "搬新家|搬家|结婚|出行|开业|装修|理发|签约|提车|领证|订婚|动土|入宅|安床|出游";
    private static final Pattern ALMANAC_THING = Pattern.compile("^(今天|明天|后天)?(?:宜不宜|适不适合|适合|宜|能不能|可不可以|可以)"
            + "(" + THINGS + ")(?:吗|么|不|好不好)?$");
    private static final Set<String> HISTORY = words("历史上的今天", "历史上的今天发生了什么", "今天历史上发生过什么", "历史上的今天有什么事",
            "历史上今天发生了什么", "今天在历史上发生了什么", "历史上的今天有什么", "历史上的今天是什么日子");

    /** 「一百」「两千」「3万」 → 100 / 2000 / 30000; -1 when not a number. */
    static double amount(String s) {
        if (s == null || s.isEmpty()) return 1;
        if (s.matches("[0-9]+")) return Double.parseDouble(s);
        double total = 0, part = 0;
        for (char c : s.toCharArray()) {
            int d = "零一二三四五六七八九".indexOf(c);
            if (c == '两') d = 2;
            if (d >= 0) { part = d; continue; }
            int unit = c == '十' ? 10 : c == '百' ? 100 : c == '千' ? 1000 : c == '万' ? 10000 : 0;
            if (unit == 0) return -1;
            if (unit == 10000) { total = (total + (part == 0 ? 1 : part)) * 10000; part = 0; }
            else { total += (part == 0 ? 1 : part) * unit; part = 0; }
        }
        return total + part;
    }

    /** 「随手记里有什么」: the notes, said as note_list says them. Not 「我让你记了什么」, which may mean what 星仪 remembers. */
    private static final Set<String> NOTE_LIST = words("随手记里有什么", "随手记里有啥", "看看随手记", "看一下随手记", "我的随手记",
            "随手记", "随手记有什么", "随手记里记了什么", "随手记里还有什么", "打开随手记", "念一下随手记", "读一下随手记");
    /** 「今天是今年的第几天」「今年还剩多少天」. */
    private static final Set<String> YEAR_DAY = words("今天是今年的第几天", "今天是第几天", "今天是一年中的第几天", "今年过了多少天",
            "今年已经过了多少天", "今年过去了多少天", "今年过了几天", "今天是今年第几天", "今年第几天");
    private static final Set<String> YEAR_LEFT = words("今年还剩多少天", "今年还剩几天", "今年还有多少天", "今年还有几天",
            "今年还剩下多少天", "离今年结束还有几天", "离今年结束还有多少天", "今年还剩下几天", "距离今年结束还有几天");
    /** 「明年清明放几天假」「国庆放几天」「春节放假安排」: the State Council's schedule, for a festival that is a day off by law. */
    private static final Pattern HOLIDAY_ASK = Pattern.compile("^(今年|明年|去年)?的?([\u4e00-\u9fa5]{2,4}?)节?(?:要)?(?:放|休)(?:几天|多少天|多久)假?$"
            + "|^(今年|明年|去年)?的?([\u4e00-\u9fa5]{2,4}?)节?的?(?:放假|假期)(?:安排|是哪几天|从哪天开始|几号开始|放几天|有几天|几天|怎么安排|怎么放)?$");
    /** 「还有几天放假」「下个假期是什么时候」: the next days off by law. */
    private static final Set<String> NEXT_HOLIDAY = words("还有几天放假", "还有多少天放假", "还有多久放假", "下个假期是什么时候",
            "下一个假期是什么时候", "下个假期", "下一个假期", "下次放假是什么时候", "下次什么时候放假", "什么时候放假", "最近什么时候放假",
            "下一个法定节假日", "下个法定节假日是什么时候", "下一个法定假日是什么时候", "下一个节假日是什么时候", "下个节假日是什么时候",
            "离放假还有几天", "距离放假还有几天", "下一次放假是什么时候", "下个假期放几天");

    /** 「明天星期几」「后天几号」「昨天农历几号」: the clock, some days off (2026-11-06 overnight pass). */
    private static final Pattern DAY_ASK = Pattern.compile("^(今天|明天|后天|大后天|昨天|前天)(?:是)?(?:几号|几月几号|几月几日|多少号|什么日期|"
            + "星期几|周几|礼拜几|几号星期几|几月几号星期几|几月几日星期几|农历几号|农历多少|阴历几号|农历是几号|阴历是几号|是农历几号)$");
    /** 「下周一是几号」「这周六几号」「周日是几号」. */
    private static final Pattern WEEK_ASK = Pattern.compile("^(下下个|下下|下个|下|这个|这|本|上个|上)?(?:周|星期|礼拜)([一二三四五六日天])(?:是)?"
            + "(?:几号|几月几号|几月几日|多少号|哪天|哪一天)$");

    /**
     * How many days from today a clock question is about: in (明天 1, 昨天 -1), or for 「下周一」 the week (下 1, 这 0, 上
     * -1, nothing said: the coming one) and the weekday (1 Monday … 7 Sunday), weeks starting on Monday.
     */
    public static int daysAhead(org.json.JSONObject args, java.util.Calendar c) {
        if (!args.has("wd")) return args.optInt("in", 0);
        int wd = args.optInt("wd"), now = (c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1; // Monday 1 … Sunday 7
        if (!args.has("week")) return Math.floorMod(wd - now, 7);
        return args.optInt("week") * 7 + wd - now;
    }

    /** The days a place's weather may be asked for, as they are said: 「周六」「下周一」「星期天」 — the weather tool knows them
     * (2026-11-10: 「广州明天天气怎么样」 took 4.4 s through the model, and a weekday used to end up inside the place). */
    private static final String PLACE_DAY = "(?:大后天|后天|今天|明天|今晚|明早|明晚|这周末|周末|这几天|"
            + "(?:这个|这|本|下个|下)?(?:周|星期|礼拜)[一二三四五六日天])";
    /** A place is never a day and never ends in one: 「北京周六天气怎么样」 is 北京 on 周六, not a place called 北京周六. */
    private static final Pattern DAY_TAIL = Pattern.compile(PLACE_DAY + "$");
    /** 里 / 内 after a noun says where something is, not which city it is: 「房间里几度」 is the room's temperature. */
    private static final Pattern NOT_PLACE_INSIDE = Pattern.compile("[里内]$");

    /** 「明天上海天气怎么样」→「明天天气怎么样」 when the place is the TV's own; null otherwise. */
    static String dayFirst(String s) {
        Matcher d = DAY_FIRST.matcher(s);
        return d.matches() && here.contains(d.group(2)) ? d.group(1) + d.group(3) : null;
    }

    /** 「明天上海天气怎么样」「周六上海天气怎么样」: a day, then a place, then the rest. */
    private static final Pattern DAY_FIRST = Pattern.compile("^(" + PLACE_DAY + ")"
            + "([\u4e00-\u9fa5]{2,4}?)[市区]?(的?(?:天气|空气|几度|多少度|气温|冷|会不会下雨|会下雨|下雨|下不下雨|有雨|有没有雨|要带伞|要不要带伞|用带伞|需要带伞).*)$");
    /** 「几点天黑」「明天几点日出」「日落时间」: the weather tool's sunrise and sunset (sun). */
    private static final Pattern SUN = Pattern.compile("^(今天|明天|后天)?(?:(?:几点|什么时候)(?:日落|天黑|太阳下山|日出|天亮|太阳出来|太阳升起)"
            + "|的?(?:日落|日出)时间|(?:日落|日出|天黑|天亮)(?:是)?(?:几点|什么时候)|太阳(?:几点|什么时候)(?:下山|落山|出来|升起))$");
    /** 「明天穿什么」「要不要穿外套」「出门要加衣服吗」: the weather tool's advice (dress). */
    private static final Pattern DRESS = Pattern.compile("^(今天|明天|后天|今晚|明早|现在|外面)?(?:出门)?(?:要|需要|该|应该)?"
            + "(?:穿什么(?:衣服)?(?:合适|好|比较好)?|穿啥|穿多少|(?:要不要|用不用|需不需要)?(?:穿|加)(?:外套|厚点|厚一点|衣服|件外套)吗?)$");
    /** 「明天要带伞吗」「今晚会下雨吗」「外面在下雨吗」: the weather tool's verdict (rain). */
    private static final Pattern RAIN = Pattern.compile("^(现在|外面|今天|明天|后天|大后天|今晚|明早|明晚|周末|这周末)?"
            + "(?:会不会下雨|会下雨吗|下雨吗|下不下雨|有雨吗|有没有雨|要带伞吗|要不要带伞|用带伞吗|用不用带伞|需要带伞吗|需不需要带伞|"
            + "在下雨吗|下雨了吗|是不是在下雨|出门要带伞吗|出门要不要带伞)$");
    private static final Set<String> TEMPERATURE = words("现在几度", "现在多少度", "外面几度", "外面多少度", "今天几度", "今天多少度",
            "气温多少", "现在气温多少", "今天气温多少", "现在温度多少", "外面冷不冷", "今天冷不冷", "今天冷吗", "外面冷吗");
    private static final Set<String> AIR = words("空气质量怎么样", "今天空气质量怎么样", "空气怎么样", "今天空气怎么样",
            "空气好不好", "今天空气好不好", "有雾霾吗", "今天有雾霾吗", "空气质量如何", "今天空气质量如何");
    /** 「广州天气怎么样」「北京周六天气怎么样」「明天杭州下雨吗」 (2026-11-10: another city's weather took 4.4 s through the
     * model, and 「北京周六天气怎么样」 asked about a place called 北京周六): the weather tool looks a place up by its name and
     * answers the day it is asked for. Only after the TV's own place was tried, so 上海 still comes back without a place. */
    private static final String PLACE_ASK = "(?:天气(?:怎么样|如何|咋样|好不好|好吗|情况|预报)?|会不会下雨|会下雨吗|下雨吗|下不下雨|有雨吗|有没有雨|"
            + "要带伞吗|要不要带伞|用带伞吗|用不用带伞|需要带伞吗|需不需要带伞|在下雨吗|几度|多少度|冷吗|冷不冷)";
    private static final Pattern PLACE_WEATHER = Pattern.compile("^([\\u4e00-\\u9fa5]{2,4}?)(" + PLACE_DAY + ")?的?(" + PLACE_ASK + ")$");
    private static final Pattern PLACE_WEATHER_DAY_FIRST = Pattern.compile("^(" + PLACE_DAY + ")([\\u4e00-\\u9fa5]{2,4}?)的?(" + PLACE_ASK + ")$");
    private static final Set<String> PLACE_RAIN = words("会不会下雨", "会下雨吗", "下雨吗", "下不下雨", "有雨吗", "有没有雨", "要带伞吗",
            "要不要带伞", "用带伞吗", "用不用带伞", "需要带伞吗", "需不需要带伞", "在下雨吗");
    /** What is never a place: a day, a part of a day, a pronoun or a word for where the TV is. 这个 / 那个 point at the
     * weather itself (「这个明天天气怎么样」), never at a city. */
    private static final Set<String> NOT_PLACE = words("今天", "明天", "后天", "大后天", "今晚", "明早", "明晚", "周末", "这周末",
            "这几天", "这两天", "最近", "最近几天", "现在", "外面", "那边", "这边", "这里", "那里", "家里", "你那", "我这", "我们这",
            "本地", "当地", "电视", "这种", "那种", "这个", "那个", "这些", "那些", "什么", "怎么", "哪里", "哪儿");
    /** 「帮我看看明天天气怎么样」「明天去广州天气怎么样」「打开天气」: a verb in front is no place — the sentence stays the
     * model's (it waits for the running task, Dispatch.queueable). No city name starts with one of these (开封 is a city, so
     * 开 alone is not here). */
    private static final Pattern NOT_PLACE_WORD = Pattern.compile("^(?:看|查|问|说|讲|告诉|想|要|帮|搜|念|读|打|到|去|回|飞|往)");
    /** The names of the place the TV is in (「上海」「徐汇」「上海徐汇」, set by the host from the location setting): 「明天上海
     * 天气怎么样」 is the TV's own weather, so it is quick too; another city is quick as well since 2026-11-10, but carries
     * its own place. */
    private static volatile Set<String> here = new HashSet<>();

    public static void here(Collection<String> names) {
        Set<String> s = new HashSet<>();
        if (names != null) for (String n : names) if (n != null && n.trim().length() >= 2) s.add(n.trim());
        here = s;
    }

    /** s without the TV's own place in front (the longest name that fits), or s. */
    static String withoutHere(String s) {
        String best = null;
        for (String n : here) if (s.startsWith(n) && s.length() > n.length() && (best == null || n.length() > best.length())) best = n;
        if (best == null) return s;
        String rest = s.substring(best.length());
        return rest.startsWith("市") || rest.startsWith("区") ? rest.substring(1) : rest;
    }

    /** 「现在放的是什么歌」: the media session says, no model round. */
    private static final Set<String> PLAYING = words("现在放的是什么歌", "现在放的是什么", "现在在放什么", "正在放什么", "现在在放什么歌",
            "正在放什么歌", "现在播的是什么", "现在播放的是什么", "这首歌叫什么", "现在放的什么歌", "放的是什么歌");
    /** 「星仪自检」: what works and what does not (self_check). */
    private static final Set<String> SELF_CHECK = words("自检", "自检一下", "星仪自检", "做个自检", "检查一下你自己", "诊断一下",
            "你是不是坏", "你是不是出问题", "检查一下星仪");
    /** 「清理内存」 (2026-11-07): free_memory stops the apps sitting in the background; 电视管家's 加速 took 星仪 down with them. */
    private static final Set<String> FREE_MEMORY = words("清理内存", "清理一下内存", "清一下内存", "清内存", "内存清理", "释放内存",
            "释放一下内存", "清理电视内存", "清理电视的内存", "清理一下电视内存", "清理一下电视的内存", "电视内存清理", "清理后台",
            "清理一下后台", "清一下后台", "清理后台应用", "关掉后台", "关掉后台应用", "关闭后台应用", "关掉后台的应用", "电视加速",
            "给电视加速", "加速一下电视", "一键加速", "内存加速", "清理下内存", "清下内存", "内存不够了", "内存不够");
    /** 「早上好」「今天有什么安排」: the day in one answer (Brief). */
    private static final Set<String> BRIEF = words("早上好", "早安", "早", "今天有什么安排", "我今天有什么安排", "今天的安排",
            "今天怎么安排", "说说今天", "给我说说今天", "今天有什么事", "今天有啥安排", "今天有什么要做的");
    /** 「音量现在多少」: read off the TV, no model round (2026-11-06 sweep: two rounds, 2.4 s). */
    private static final Set<String> VOLUME_NOW = words("音量多少", "音量现在多少", "现在音量多少", "现在音量是多少", "音量是多少",
            "现在声音多大", "声音多大", "音量多大", "现在音量多大", "音量现在是多少", "现在的音量是多少");
    /** The last answer read again (after the user woke XiaoAi over it, or did not catch it). */
    private static final Set<String> REPLAY = words("再说一遍", "再说一次", "重复一遍", "重复一下", "再讲一遍", "再念一遍",
            "刚才说什么", "你刚才说什么", "你刚说什么", "没听清", "我没听清", "没听清楚", "你说什么", "重复");
    /** 「打开显示器」「开机」: the screen on. 开机 / 开电视 / 打开电视 is the screen, not an app to open (the user said
     * 「嗯开机」 on 2026-11-09 and it took three model rounds). Checked before the 「打开<app>」 rule. */
    private static final Set<String> SCREEN = words("亮屏", "开屏", "点亮屏幕", "打开屏幕", "开屏幕", "屏幕打开", "唤醒屏幕",
            "屏幕亮起来", "亮一下屏幕", "屏幕亮一下", "把屏幕点亮", "屏幕点亮",
            "打开显示器", "显示器打开", "开显示器", "点亮显示器", "开机", "开电视", "打开电视", "电视打开");
    /** 关电视 / 熄屏 / 待机 / 关显示器: the screen off, the TV in standby (XiaoAi still hears). 显示器 is the word the
     * user used on 2026-11-09 20:41 (「然后关闭显示器」, 8.9 s through the model). */
    private static final Set<String> OFF = words("关电视", "关掉电视", "电视关", "电视关掉", "关机", "关屏幕", "关闭屏幕", "关掉屏幕",
            "屏幕关", "屏幕关掉", "熄屏", "息屏", "熄灭屏幕", "屏幕可以熄灭", "屏幕熄灭", "待机", "进入待机", "电视待机",
            "关闭桌面", "关掉桌面", "关桌面", "桌面关掉",
            "关闭显示器", "关掉显示器", "关显示器", "显示器关", "显示器关掉", "显示器关闭", "熄灭显示器", "显示器熄灭",
            "关闭电视屏幕", "关掉电视屏幕");
    /** 「关闭桌面不要关机」 (2026-11-08 14:56 and 17:59; the user: the screen black and the TV in standby, not shut down):
     * 「不要关机」 after a screen-off only says how far, so it is taken off before the lookup. */
    private static final Pattern NOT_SHUTDOWN = Pattern.compile("[，,、\\s　]*(?:但是|但|只是|就是)?(?:不要|别|不用|不准|不许)(?:给我)?(?:彻底)?(?:关机|断电|关电视)$");
    private static final Pattern HDMI = Pattern.compile("^(?:切换到|切到|换到|打开)?[Hh][Dd][Mm][Ii][\\s　]*([123一二三])$");
    private static final Pattern SLEEP = Pattern.compile("^(.+?)(分钟|小时)(?:后|以后|之后)(?:关机|关电视|休眠|待机|睡眠)$");
    private static final String[][] APP_ALIASES = {{"b站", "哔哩哔哩"}, {"哔哩", "哔哩哔哩"}, {"网易云", "网易云音乐"},
            {"云视听", "云视听极光"}, {"设置", "电视设置"}};
    /** 「退出网易云」「关掉B站」「把网易云关了」「回到桌面退出网易云」: leaving an app for the home in one step
     * (2026-11-08: through the model this was either a stopped song called done, or 9 rounds). Only a sentence that is
     * nothing else; a bare 「关掉」「退出」 stays the model's — it decides by what is playing. */
    private static final String HOME_WORDS = "回到桌面|返回桌面|回桌面|回到主页|返回主页|回主页|回到首页|返回首页";
    private static final String JOIN = "[\\s　，,、]*(?:并|然后|再)?[\\s　，,、]*";
    private static final Pattern HOME_LEAD = Pattern.compile("^(?:" + HOME_WORDS + ")" + JOIN);
    private static final Pattern HOME_TAIL = Pattern.compile(JOIN + "(?:" + HOME_WORDS + ")$");
    private static final Pattern EXIT_FIRST = Pattern.compile("^(?:退出|关掉|关闭|关了|关)(.+)$");
    private static final Pattern EXIT_LAST = Pattern.compile("^(.+?)(?:关掉|关了|关)$");
    /** What is playing, not an app to leave: exit acts on whatever is in front, so 「关掉音乐」 while a video plays would
     * leave the wrong app — the model decides by what is playing, as with a bare 「关掉」. */
    private static final Set<String> NOT_APP = words("音乐", "视频", "歌曲", "歌", "声音", "画面", "屏幕", "全屏", "登录", "弹幕", "应用", "后台");

    /** 「我想看CCTV5」（2026-11-11 校准：这台电视上没有能看电视直播的应用，模型猜 HDMI、装应用、开网页，6–10 轮、
     * 17–60 秒之后才说「放不了」）：一句 tv_guide，工具自己说放不了，再说这个台现在在播什么。 */
    private static final Pattern LIVE_WANT = Pattern.compile("^(?:我|我们)?(?:想要|想|要)?看(?:一下|看)?(.+)$");
    private static final Pattern LIVE_GO = Pattern.compile("^(?:打开|切换到|切到|换到|调到|转到|播放|播|放)(.+)$");
    /** 名字后面的话：只是把句子说全了（的直播、的节目、卫视台），不是频道名的一部分。 */
    private static final Pattern LIVE_TAIL = Pattern.compile("的?(?:直播|节目|台)$");
    /** TvGuide.channel 见到含着频道名的字就认（「CCTV5的NBA」也算），所以这些字一个都不能在频道名里。 */
    private static final Pattern LIVE_NOT_CHANNEL = Pattern.compile("[的吗呢吧能可什哪怎有没和跟与、，,?？]");
    /** 一句话里提到了信号源或者接着它的设备：只有这种时候才切信号源（switch_input 的 gate）。 */
    private static final Pattern INPUT_WORDS = Pattern.compile("hdmi|信号源|输入源|机顶盒|盒子|游戏机|主机|ps[45]|switch|xbox|电脑|笔记本|投影|高清线|外接",
            Pattern.CASE_INSENSITIVE);

    /** 这句话有没有说到信号源或接着它的设备（HDMI、机顶盒、游戏机、电脑…）。用户没提，切过去就是一片无信号
     * （2026-11-11 校准：「我想看CCTV5」被当成了「切到 HDMI1」）。 */
    public static boolean namesInput(String utterance) {
        return utterance != null && INPUT_WORDS.matcher(utterance).find();
    }

    /** 「我想看 X」：X 是一个电视台的频道，不是一部片子（那是 watch）。别的（一个节目、一个问题、一个应用）交回模型。 */
    private static QuickCommand live(String s) {
        for (Pattern form : new Pattern[]{LIVE_WANT, LIVE_GO}) {
            Matcher m = form.matcher(s);
            if (!m.matches()) continue;
            String rest = m.group(1), said = rest;
            // 「我想看CCTV5直播」是 CCTV5 的直播；「我想看中央五台」里的台是名字的一部分（去了它 TvGuide 就不认了）
            String cut = LIVE_TAIL.matcher(rest).replaceFirst("");
            if (cut.length() >= 2 && TvGuide.channel(cut) != null) said = cut;
            if (said.length() < 2 || said.length() > 10 || LIVE_NOT_CHANNEL.matcher(said).find()) continue;
            if (TvGuide.channel(said) == null) continue;
            return cmd("tv_guide", "channel", said, "part", "现在", "watch", true, "final", true);
        }
        return null;
    }

    /** 「我想看 X」（2026-11-11）：腾讯视频的搜索自己说有没有这部片子——模型猜不出来（16 次里错 7 次），搜索 0.2–0.6 秒
     * 就知道。工具带 strict：只有搜索说得死死的才动手，差一点就交回模型（A 说的是「我想看」，B 要先说类型）。 */
    private static final String WATCH_KIND = "电视剧|电影|动画片|动画|动漫|综艺|纪录片";
    /** 「这部电视剧」「这个综艺」跟在名字后面：只是把话说全了，不是又一个类型。 */
    private static final Pattern WATCH_TAIL = Pattern.compile(
            "^(.*?)(?:这部(?:电视剧|电影|剧|片子|动漫|综艺|纪录片)|这个(?:电视剧|电影|动漫|综艺|纪录片))$");
    private static final Pattern WATCH_WANT = Pattern.compile(
            "^(?:我|我们|咱们)?(?:现在)?(?:还)?(?:想要|想|要)看(?:看|一下)?(" + WATCH_KIND + ")?(.+)$");
    private static final Pattern WATCH_PLAY = Pattern.compile(
            "^(?:帮我|给我)?(?:播放|播|放|打开|看)(" + WATCH_KIND + ")(.+)$");
    private static final Pattern WATCH_BARE = Pattern.compile("^看(?:看|一下)?(" + WATCH_KIND + ")?(.+)$");
    /** 名字里不该有的字：语气词和标点（《长安的荔枝》包在书名号里的是一个名字，这些字就不算）。 */
    private static final Pattern WATCH_PARTICLE = Pattern.compile("[吗呢吧呀啊么嘛，。？！,.?!]");
    /** 「的」在一个名字里面是名字的一部分（《漫长的季节》），挂在末尾的才是句子没说完。 */
    private static final Pattern WATCH_PARTICLE_END = Pattern.compile("的$");
    /** 名字里不该有的词：问句、要的是别的东西（视频、直播、演唱会…）、别的应用、第几集。 */
    private static final Pattern WATCH_WORD = Pattern.compile(
            "什么|哪|怎么|几|多少|谁|有没有|能不能|可不可以|是不是|"
            + "视频|直播|新闻|比赛|教程|攻略|解说|混剪|演唱会|MV|mv|预告|花絮|集锦|合集|热搜|排行|榜|频道|卫视|CCTV|cctv|央视|短剧|"
            + "B站|b站|哔哩哔哩|网易云|腾讯视频|云视听极光|极光|爱奇艺|优酷|芒果|抖音|快手|小红书|浏览器|推特|"
            + "最新|最后|上一集|下一集");
    private static final Pattern WATCH_EPISODE = Pattern.compile("第.{0,3}[集期话]");
    /** 名字末尾的「第3集」「第三十一期」「第5话」：名字是它前面那一截，这一集交给 tencent_video_open 去找。 */
    private static final Pattern WATCH_EPISODE_END = Pattern.compile("第([0-9零〇一二三四五六七八九十百两]{1,4})[集期话]$");
    /** 这些不是一个片子的名字：说的是「看点什么」或者电视上的一个地方。 */
    private static final Set<String> NOT_WATCH_TITLE = new HashSet<>(Arrays.asList("电影", "电视剧", "电视", "动画片", "动画",
            "动漫", "综艺", "纪录片", "节目", "片子", "大片", "东西", "别的", "其他的", "天气预报", "相册", "照片", "图片",
            "设置", "桌面", "屏幕", "日历", "时间", "历史记录", "我的收藏", "收藏夹", "稍后再看", "一下", "一会儿"));

    /** 「广州明天天气怎么样」「明天广州天气」「北京周六天气怎么样」「成都后天冷不冷」: another place's weather on any day, said
     * with the place before or after the day. Null when what the place slot caught is a day word or ends in one
     * (「北京周六」), a pronoun, a verb, ends in 的 (「明天的天气」) or says where something is (「房间里几度」). */
    private static QuickCommand weatherThere(String s) {
        Matcher a = PLACE_WEATHER_DAY_FIRST.matcher(s), b = PLACE_WEATHER.matcher(s);
        String place = null, day = null, ask = null;
        // the day first: 「明天广州天气怎么样」 would otherwise give 「明天广州」 as the place
        if (a.matches()) { day = a.group(1); place = a.group(2); ask = a.group(3); }
        else if (b.matches()) { place = b.group(1); day = b.group(2); ask = b.group(3); }
        if (place == null || place.endsWith("的") || NOT_PLACE.contains(place) || DAY_TAIL.matcher(place).find()
                || NOT_PLACE_INSIDE.matcher(place).find() || NOT_PLACE_WORD.matcher(place).find()) return null;
        boolean rain = PLACE_RAIN.contains(ask);
        if (day == null) return rain ? cmd("weather", "place", place, "rain", true, "final", true)
                : cmd("weather", "place", place, "final", true);
        return rain ? cmd("weather", "place", place, "day", day, "rain", true, "final", true)
                : cmd("weather", "place", place, "day", day, "final", true);
    }

    /** 「快进一分钟」 (2026-11-11 20:02 on the TV, 云视听极光 playing an episode): the sentence is nothing but a seek verb,
     * an optional amount and 吧 — XiaoAi's own instruction was PlaybackController.Seek and it jumped a minute, while
     * 星仪 had no tool for it and spent 20 s on the screen to end with 「看不到画面和进度条」. Anything with more to it
     * (「快进到十分钟」「两倍速播放」「快进的时候卡不卡」) is still the model's. */
    private static final String SEEK_NUM = "([0-9]{1,4}|[零〇一二三四五六七八九十百两]{1,4})";
    private static final Pattern SEEK_FWD = Pattern.compile("^(?:再)?(?:往后快进|向后快进|快进|前进|快转)(.*?)(?:吧)?$");
    private static final Pattern SEEK_BACK = Pattern.compile("^(?:再)?(?:往回倒|倒回去|往前倒|倒回|快退|后退|倒退|回退)(.*?)(?:吧)?$");
    /** 一下 / 一点 / 一点点 / 一会儿 say no amount: 30 seconds, as does saying nothing at all. */
    private static final Pattern SEEK_SOME = Pattern.compile("^(?:一下|一点|一点点|一会儿)$");
    private static final Pattern SEEK_AMOUNT = Pattern.compile("^" + SEEK_NUM + "(秒钟?|分半钟?|分钟?)$");
    /** Below 5 seconds a press cannot be told apart from the player's own, and half an hour is the whole request. */
    private static final int SEEK_MIN = 5, SEEK_MAX = 1800;

    /** 「再? verb + amount? + 吧?」: null unless the whole sentence is one seek. */
    private static QuickCommand seek(String s) {
        Matcher f = SEEK_FWD.matcher(s), b = SEEK_BACK.matcher(s);
        if (!f.matches() && !b.matches()) return null;
        String said = f.matches() ? f.group(1) : b.group(1);
        int seconds = seekSeconds(said);
        if (seconds < SEEK_MIN || seconds > SEEK_MAX) return null;
        return cmd("media_seek", "seconds", f.matches() ? seconds : -seconds);
    }

    /** The seconds an amount says: 「一分钟」 60, 「一分半」 90, 「半分钟」 30, 「十秒」 10, nothing at all 30. -1 when it
     * is not an amount this command reads. */
    private static int seekSeconds(String said) {
        if (said.isEmpty() || SEEK_SOME.matcher(said).matches() || "半分钟".equals(said)) return 30;
        Matcher m = SEEK_AMOUNT.matcher(said);
        if (!m.matches()) return -1;
        int n = number(m.group(1));
        if (n < 0) return -1;
        String unit = m.group(2);
        if (unit.startsWith("秒")) return n;
        if (unit.startsWith("分半")) return 60 * n + 30;
        return 60 * n;
    }

    /** 「黑屏了」「屏幕什么都没有」: the user is looking at a black TV. A complaint, so the app in front is left for the
     * home (media_control exit with blank), which is the only thing that gets them out of it — screen_look had already
     * said twice 「屏幕大部分为黑色」 and nothing was done (2026-11-11 21:12 on the TV). Matched on the sentence as it was
     * said: normalise() takes a trailing 了 off, and 「黑屏」 without 了 may be a wish to make the screen dark
     * (「把屏幕调黑一点」), which must stay the model's. */
    private static final Pattern BLANK = Pattern.compile("^(?:你看|现在)*(?:"
            + "黑屏了|屏幕黑了|屏幕黑屏了|电视黑屏了|电视屏幕黑了|电视屏幕都黑了|屏幕怎么黑了|怎么黑屏了|屏幕全黑了|屏幕是黑的|"
            + "画面黑了|没画面了|屏幕没画面了|画面没了|屏幕什么都没有|屏幕上什么都没有|屏幕不显示了"
            + ")[呀啊呢吧。！]*$");

    /** The command this sentence is, or null when it is anything else. */
    public static QuickCommand match(String utterance, Collection<String> appLabels) {
        String s = normalise(utterance);
        if (s.isEmpty()) return null;
        QuickCommand q = volume(s);
        if (q == null) q = media(s);
        if (q == null) q = seek(s);
        if (q == null) q = karaoke(s);
        if (q == null) q = keys(s);
        if (q == null && SCREEN.contains(s)) q = cmd("wake_screen");
        // 黑屏了: before screen_off — the screen being black is not the screen being switched off
        if (q == null && utterance != null && BLANK.matcher(utterance.trim()).matches())
            q = cmd("media_control", "action", "exit", "blank", true);
        if (q == null && (OFF.contains(s) || OFF.contains(NOT_SHUTDOWN.matcher(s).replaceFirst("")))) q = cmd("screen_off");
        if (q == null && TIME.contains(s)) q = cmd("clock", "kind", "time");
        if (q == null && DATE.contains(s)) q = cmd("clock", "kind", "date");
        if (q == null && WEEKDAY.contains(s)) q = cmd("clock", "kind", "weekday");
        if (q == null && LUNAR.contains(s)) q = cmd("clock", "kind", "lunar");
        if (q == null) {
            Matcher d = DAY_ASK.matcher(s);
            if (d.matches()) {
                String when = d.group(1);
                int in = "明天".equals(when) ? 1 : "后天".equals(when) ? 2 : "大后天".equals(when) ? 3 : "昨天".equals(when) ? -1
                        : "前天".equals(when) ? -2 : 0;
                String kind = s.contains("农历") || s.contains("阴历") ? "lunar" : s.matches(".*(?:星期几|周几|礼拜几)$") && !s.contains("几号") ? "weekday" : "date";
                q = in == 0 ? cmd("clock", "kind", kind) : cmd("clock", "kind", kind, "in", in, "label", when);
            }
        }
        if (q == null) {
            Matcher d = WEEK_ASK.matcher(s);
            if (d.matches()) {
                String pre = d.group(1) == null ? "" : d.group(1);
                int wd = "一二三四五六".indexOf(d.group(2)) + 1;
                if (wd == 0) wd = 7;
                String label = pre + "周" + ("天".equals(d.group(2)) ? "日" : d.group(2));
                q = pre.isEmpty() ? cmd("clock", "kind", "date", "wd", wd, "label", label)
                        : cmd("clock", "kind", "date", "wd", wd, "label", label,
                        "week", pre.startsWith("下下") ? 2 : pre.startsWith("下") ? 1 : pre.startsWith("上") ? -1 : 0);
            }
        }
        if (q == null && TERM.contains(s)) q = cmd("clock", "kind", "term");
        if (q == null && FESTIVAL.contains(s)) q = cmd("clock", "kind", "festival");
        if (q == null && NEXT_FESTIVAL.contains(s)) q = cmd("clock", "kind", "nextfestival");
        if (q == null && NEXT_HOLIDAY.contains(s)) q = cmd("clock", "kind", "nextholiday");
        if (q == null && YEAR_DAY.contains(s)) q = cmd("clock", "kind", "yearday");
        if (q == null && SCHEDULE_LIST.contains(s)) q = cmd("clock", "kind", "schedules");
        if (q == null && NOTE_LIST.contains(s)) q = cmd("clock", "kind", "notes");
        if (q == null && YEAR_LEFT.contains(s)) q = cmd("clock", "kind", "yearleft");
        if (q == null) {
            Matcher h = HOLIDAY_ASK.matcher(s);
            if (h.matches()) {
                String when = h.group(1) != null ? h.group(1) : h.group(3), name = h.group(2) != null ? h.group(2) : h.group(4);
                if (Almanac.legal(name)) q = when == null ? cmd("clock", "kind", "holiday", "label", name)
                        : cmd("clock", "kind", "holiday", "label", name, "when", when);
            }
        }
        if (q == null) {
            Matcher cd = COUNTDOWN.matcher(s);
            if (cd.matches()) {
                String name = cd.group(1) != null ? cd.group(1) : cd.group(2);
                // 「还有几天到春节」「还有几天过春节」: 到/过/是 in front, unless it is the name (过年)
                if (!Almanac.knows(name) && name.length() > 2 && "到过是".indexOf(name.charAt(0)) >= 0) name = name.substring(1);
                if (Almanac.knows(name)) q = cmd("clock", "kind", "countdown", "label", name);
            }
        }
        if (q == null) {
            Matcher w = WORKDAY.matcher(s);
            if (w.matches()) {
                String when = w.group(1) != null ? w.group(1) : w.group(2);
                q = cmd("clock", "kind", "workday", "in", "今天".equals(when) ? 0 : "明天".equals(when) ? 1 : 2);
            }
        }
        if (q == null && REPLAY.contains(s)) q = cmd("replay");
        if (q == null && VOLUME_NOW.contains(s)) q = cmd("clock", "kind", "volume");
        if (q == null && BRIEF.contains(s)) q = cmd("clock", "kind", "brief");
        if (q == null && SELF_CHECK.contains(s)) q = cmd("self_check");
        if (q == null && FREE_MEMORY.contains(s)) q = cmd("free_memory");
        // the weather here, with or without the TV's own city or district said: 「明天上海天气怎么样」「上海明天天气」
        // 「徐汇今天几度」「今天上海空气质量怎么样」; another city is answered by weatherThere below, with its own place
        for (String local : new String[]{s, withoutHere(s), dayFirst(s)}) {
            if (q != null || local == null) break;
            Matcher w = WEATHER.matcher(local);
            Matcher r = RAIN.matcher(local);
            Matcher how = HOW.matcher(local);
            Matcher sun = SUN.matcher(local);
            Matcher dress = DRESS.matcher(local);
            if (w.matches()) q = cmd("weather", "day", today(w.group(1)), "final", true);
            else if (how.matches()) q = cmd("weather", "day", today(how.group(1)), "final", true);
            else if (sun.matches()) q = cmd("weather", "day", today(sun.group(1)), "sun", true, "final", true);
            else if (dress.matches()) q = cmd("weather", "day", today(dress.group(1)), "dress", true, "final", true);
            else if (r.matches()) {
                String day = r.group(1) == null ? (local.contains("在下雨") || local.contains("下雨了") ? "现在" : "今天") : r.group(1);
                q = cmd("weather", "day", day, "rain", true, "final", true);
            }
            else if (TEMPERATURE.contains(local)) q = cmd("weather", "day", "今天", "final", true);
            else if (AIR.contains(local)) q = cmd("weather", "day", "今天", "air", true, "final", true);
        }
        if (q == null) q = weatherThere(s);
        if (q == null && PLAYING.contains(s)) q = cmd("media_now_playing");
        if (q == null) {
            Matcher m = MARKET.matcher(s);
            if (m.matches()) q = cmd("stock_quote", "query", m.group(1), "final", true);
        }
        if (q == null) q = stock(s);
        if (q == null) {
            Matcher m = MOVIE.matcher(s);
            String title = m.matches() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
            if (title != null && !NOT_TITLE.matcher(title).find()) q = cmd("movie_rating", "title", title, "final", true);
        }
        if (q == null) {
            Matcher m = MOVIE_WHERE.matcher(s);
            if (m.matches() && !NOT_WHERE_TITLE.matcher(m.group(1)).find())
                q = cmd("movie_rating", "title", m.group(1), "where", true, "final", true);
        }
        if (q == null) {
            Matcher m = FX.matcher(s);
            if (m.matches()) q = cmd("exchange_rate", "from", m.group(1), "to", "人民币", "final", true);
        }
        if (q == null && FX_RMB.matcher(s).matches()) q = cmd("exchange_rate", "from", "美元", "to", "人民币", "final", true);
        if (q == null) {
            Matcher m = FX_AMOUNT.matcher(s);
            if (m.matches()) {
                double n = amount(m.group(1));
                if (n > 0) q = cmd("exchange_rate", "from", m.group(2), "to", "人民币", "amount", n == Math.rint(n) ? (Object) (long) n : (Object) n,
                        "final", true);
            }
        }
        if (q == null && HOT.contains(s)) q = cmd("hot_list", "source", "百度", "count", 5, "final", true);
        if (q == null && WEIBO_HOT.contains(s)) q = cmd("hot_list", "source", "百度", "count", 5, "final", true);
        if (q == null) {
            Matcher m = HOT_TOP.matcher(s);
            if (m.matches()) {
                int n = number(m.group(1));
                if (n >= 1 && n <= 10) q = cmd("hot_list", "source", "百度", "count", n, "final", true);
            }
        }
        if (q == null && GOLD.contains(s)) q = cmd("gold_price", "final", true);
        if (q == null && USAGE_TODAY.contains(s)) q = cmd("tv_usage", "day", "今天");
        if (q == null && USAGE_YESTERDAY.contains(s)) q = cmd("tv_usage", "day", "昨天");
        if (q == null && USAGE_LAST_NIGHT.contains(s)) q = cmd("tv_usage", "day", "昨晚");
        if (q == null && ALMANAC_TODAY.contains(s)) q = cmd("almanac", "final", true);
        if (q == null && ALMANAC_TOMORROW.contains(s)) q = cmd("almanac", "date", "明天", "final", true);
        if (q == null) {
            Matcher m = ALMANAC_THING.matcher(s);
            if (m.matches()) {
                String thing = "搬新家".equals(m.group(2)) ? "搬家" : m.group(2);
                q = cmd("almanac", "date", m.group(1) == null ? "今天" : m.group(1), "thing", thing, "final", true);
            }
        }
        if (q == null && BANGUMI_TODAY.contains(s)) q = cmd("bili_bangumi", "final", true);
        if (q == null && BANGUMI_TOMORROW.contains(s)) q = cmd("bili_bangumi", "day", "明天", "final", true);
        if (q == null && BANGUMI_WEEK.contains(s)) q = cmd("bili_bangumi", "day", "这周", "final", true);
        if (q == null && ALERT.contains(s)) q = cmd("weather_alert", "final", true);
        if (q == null && TYPHOON.contains(s)) q = cmd("weather_alert", "typhoon", true, "final", true);
        if (q == null && QUAKE_NEAR.contains(s)) q = cmd("earthquake", "nearby", true, "final", true);
        if (q == null && QUAKE_LATEST.contains(s)) q = cmd("earthquake", "count", 3, "final", true);
        if (q == null && FUEL.contains(s)) q = cmd("fuel_price", "final", true);
        if (q == null && HISTORY.contains(s)) q = cmd("history_today", "count", 4, "final", true);
        if (q == null) {
            Matcher m = FUND.matcher(s);
            if (m.matches()) q = cmd("fund_nav", "query", m.group(1), "final", true);
        }
        if (q == null) {
            Matcher m = WORLD_TIME.matcher(s);
            if (m.matches()) q = cmd("world_clock", "place", m.group(1), "final", true);
        }
        if (q == null) {
            Matcher m = TIME_DIFF.matcher(s);
            if (m.matches()) q = cmd("world_clock", "place", m.group(1) != null ? m.group(1) : m.group(2), "final", true);
        }
        if (q == null) {
            Matcher a = TV_PART_FIRST.matcher(s), b = TV_PART_LAST.matcher(s);
            String when = null, which = null;
            if (a.matches()) { when = a.group(1); which = a.group(2); }
            else if (b.matches()) { which = b.group(1); when = b.group(2); }
            if (which != null && TvGuide.channel(which) != null) {
                boolean tomorrow = when.startsWith("明");
                boolean evening = when.contains("晚");
                q = cmd("tv_guide", "channel", which, "day", tomorrow ? "明天" : "今天", "part", evening ? "晚上" : "全天", "final", true);
            }
        }
        // after the parts of the day: 「东方卫视明晚播什么」 is tomorrow evening, not now
        if (q == null) {
            Matcher m = TV_NOW.matcher(s);
            if (m.matches() && TvGuide.channel(m.group(1)) != null && !m.group(1).matches(".*(?:今晚|明晚|明天|今天晚上).*"))
                q = cmd("tv_guide", "channel", m.group(1), "part", "现在", "final", true);
        }
        if (q == null) {
            Matcher m = NEWS.matcher(s);
            boolean said = m.matches() && (m.group(1) != null || !s.contains("消息") || s.matches("^(?:最近|今天|最新).*"));
            if (said) q = cmd("news", "category", m.group(1) == null ? "最新" : m.group(1), "count", 5, "final", true);
        }
        if (q == null) {
            Matcher m = MOVIE_LIST.matcher(s);
            String bare = m.matches() ? (m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3)) : null;
            if (bare != null && !bare.equals(s)) {
                String l = bare;
                q = cmd("movie_list", "list", "剧".equals(l) || "电视剧".equals(l) ? "剧集" : l, "count", 5, "final", true);
            }
        }
        if (q == null) {
            Matcher m = MUSIC_NEW.matcher(s);
            String who = m.matches() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
            if (who != null && !NOT_SINGER.matcher(who).find()) q = cmd("music_new", "artist", who, "count", 3, "final", true);
        }
        if (q == null) {
            Matcher m = CHART.matcher(s);
            if (m.matches()) {
                int n = m.group(2) == null ? 5 : number(m.group(2));
                q = cmd("hot_list", "source", "网易云" + m.group(1), "count", n >= 1 && n <= 10 ? n : 5, "final", true);
            }
        }
        if (q == null && HIT_SONGS.contains(s)) q = cmd("hot_list", "source", "网易云热歌榜", "count", 5, "final", true);
        if (q == null && NEW_SONGS.contains(s)) q = cmd("hot_list", "source", "网易云新歌榜", "count", 5, "final", true);
        if (q == null) {
            Matcher m = NBA_TEAM.matcher(s);
            if (m.matches()) q = NBA_LAST.matcher(s).find()
                    ? cmd("nba_games", "team", m.group(1), "last", true, "final", true)
                    : cmd("nba_games", "team", m.group(1), "final", true);
        }
        if (q == null) {
            Matcher m = FOOTBALL_TEAM.matcher(s);
            if (m.matches()) q = cmd("football_games", "team", m.group(1), "final", true);
        }
        if (q == null) {
            Matcher m = FOOTBALL_DAY.matcher(s);
            if (m.matches()) q = cmd("football_games", "league", m.group(2), "day", m.group(1) == null ? "今天" : m.group(1), "final", true);
        }
        if (q == null) {
            Matcher m = NBA_DAY.matcher(s);
            if (m.matches()) {
                String day = m.group(1) != null ? m.group(1) : m.group(2);
                q = cmd("nba_games", "day", day == null ? "今天" : day, "final", true);
            }
        }
        if (q == null) {
            Matcher m = LOTTERY.matcher(s);
            // a bare game name is a draw only with what makes it one (「双色球」 alone may be a question about the game)
            if (m.matches() && !s.equals(m.group(1))) q = cmd("lottery", "game", m.group(1), "final", true);
        }
        if (q == null && BILI_HOT.contains(s)) q = cmd("hot_list", "source", "B站", "count", 5, "final", true);
        if (q == null) q = input(s);
        if (q == null) q = sleep(s);
        if (q == null) q = exit(s, appLabels);
        if (q == null) q = app(s, appLabels);
        // 一个频道的直播（这台电视上放不了，tv_guide 会如实说，并说它现在在播什么）；一部片子才轮到 watch
        if (q == null) q = live(s);
        // last: everything above is a command about the TV itself; this one is about a film, and only its search decides
        if (q == null) q = watch(s);
        return q;
    }

    /** What is spoken right after the tool ran; at most 20 characters. */
    public String reply(JSONObject result) {
        if ("set_volume".equals(tool) || "adjust_volume".equals(tool)) {
            String direction = args.optString("direction");
            if ("mute".equals(direction)) return "已静音";
            if ("unmute".equals(direction)) return "已取消静音";
            return result != null && result.has("volume") ? "音量 " + result.optInt("volume") : "好的";
        }
        if ("open_app".equals(tool)) return "已打开" + args.optString("app");
        // 退出网易云: the tool's own sentence says which app was left and whether the home is in front
        if ("media_control".equals(tool) && "exit".equals(args.optString("action")))
            return result == null ? "好的" : result.optString("message", "好的");
        // 继续播放 after the standby park closed NetEase: say that the song is back, from the start (2026-11-09)
        if ("media_control".equals(tool) && result != null && result.has("parked"))
            return result.optString("message", "好的");
        // 伴奏 / 原唱: the tool read the mic's label itself and checked it after the toggle, so its sentence is the
        // true one either way (2026-11-09)
        if ("netease_karaoke".equals(tool)) return result == null ? "好的" : result.optString("message", "好的");
        // 「我想看 X」：工具自己说的是找到了哪一部，或者为什么没动手（不确定、腾讯视频没有、还没上线）
        if ("tencent_video_open".equals(tool)) return result == null ? "好的" : result.optString("message", "好的");
        // 快进 / 快退：工具自己说的是跳了多久，或者为什么按不了键
        if ("media_seek".equals(tool)) return result == null ? "好的" : result.optString("message", "好的");
        if ("switch_input".equals(tool)) return "已切到 " + args.optString("name");
        if ("sleep_timer".equals(tool)) return "好的，" + args.optInt("minutes") + " 分钟后关机";
        if ("wake_screen".equals(tool)) return "屏幕本来就开着".equals(result == null ? "" : result.optString("message"))
                ? "屏幕亮着呢" : "屏幕已点亮"; // already on, or XiaoAi lit it a moment ago: true either way
        // the sentence is the answer: what works (self_check), what was stopped and what it freed (free_memory)
        if ("self_check".equals(tool) || "free_memory".equals(tool)) return result == null ? "好的" : result.optString("message", "好的");
        if ("weather".equals(tool) || "stock_quote".equals(tool) || "exchange_rate".equals(tool) || "hot_list".equals(tool)
                || "gold_price".equals(tool) || "lottery".equals(tool) || "nba_games".equals(tool) || "football_games".equals(tool)
                || "world_clock".equals(tool) || "tv_guide".equals(tool) || "news".equals(tool) || "earthquake".equals(tool)
                || "fuel_price".equals(tool) || "fund_nav".equals(tool) || "history_today".equals(tool) || "movie_rating".equals(tool)
                || "bili_bangumi".equals(tool) || "weather_alert".equals(tool) || "almanac".equals(tool)
                || "movie_list".equals(tool) || "music_new".equals(tool) || "tv_usage".equals(tool))
            return result == null ? "好的" : result.optString("answer", result.optString("message", "好的"));
        if ("media_now_playing".equals(tool)) {
            if (result == null || result.isNull("session") || !result.has("session")) return "现在没有在放东西";
            return "现在" + result.optString("message", "");
        }
        // 关屏幕 on this TV is standby, not off: the TV stays on, XiaoAi still listens, music keeps playing. The old
        // wording claimed the TV was off and had to be corrected twice (2026-11-06 「关闭屏幕但是不要关机」, 2026-11-08
        // 「我说的是关闭屏幕不是关闭电视」), so the tool's own sentence is what is said — it is the true one either way.
        if ("screen_off".equals(tool)) {
            String said = result == null ? "" : result.optString("message");
            return SCREEN_OFF.equals(said) || SCREEN_OFF_ALREADY.equals(said) ? said : "好的";
        }
        return "好的";
    }

    /** The phrases as the sentence is matched: normalised the same way (「刚才是不是地震了」 is matched as 「刚才是不是地震」;
     * a phrase ending in 了 / 吧 / 呢 was never matched before 2026-11-07). */
    private static Set<String> words(String... w) {
        Set<String> out = new HashSet<>();
        for (String x : w) out.add(normalise(x));
        return out;
    }

    /** What the clock says, the way it is said: 「现在是凌晨 3 点 25 分」「今天是 11 月 6 日，星期五」「今天星期五」. */
    public static String clockText(String kind, java.util.Calendar c) { return clockText(kind, 0, c); }

    /** in: how many days ahead the question is about (「明天上班吗」 is 1). */
    public static String clockText(String kind, int in, java.util.Calendar c) { return clockText(kind, in, null, c); }

    /** label: what the day was called (「明天」「下周一」), for a date, weekday or lunar date some days off. */
    public static String clockText(String kind, int in, String label, java.util.Calendar c) {
        if (in != 0 && ("date".equals(kind) || "weekday".equals(kind) || "lunar".equals(kind))) {
            java.util.Calendar o = (java.util.Calendar) c.clone();
            o.add(java.util.Calendar.DAY_OF_MONTH, in);
            String name = label == null || label.isEmpty() ? (in == 1 ? "明天" : in == 2 ? "后天" : in == -1 ? "昨天" : "那天") : label;
            String wk = "星期" + "日一二三四五六".charAt(o.get(java.util.Calendar.DAY_OF_WEEK) - 1);
            int y2 = o.get(java.util.Calendar.YEAR), mo2 = o.get(java.util.Calendar.MONTH) + 1, d2 = o.get(java.util.Calendar.DAY_OF_MONTH);
            Almanac.Lunar l2 = Almanac.lunar(y2, mo2, d2);
            if ("weekday".equals(kind)) return name + wk;
            if ("lunar".equals(kind)) return l2 == null ? null : name + "是农历" + l2.text();
            boolean weekNamed = name.contains("周");
            String year = y2 != c.get(java.util.Calendar.YEAR) ? y2 + " 年 " : "";
            String ahead = weekNamed && in > 2 ? "，还有 " + in + " 天" : weekNamed && in < 0 ? "，已经过了" : "";
            return name + "是 " + year + mo2 + " 月 " + d2 + " 日" + (weekNamed ? "" : "，" + wk)
                    + (l2 == null ? "" : "，农历" + l2.text()) + ahead;
        }
        String week = "星期" + "日一二三四五六".charAt(c.get(java.util.Calendar.DAY_OF_WEEK) - 1);
        int y = c.get(java.util.Calendar.YEAR), mo = c.get(java.util.Calendar.MONTH) + 1, d = c.get(java.util.Calendar.DAY_OF_MONTH);
        if ("date".equals(kind)) {
            Almanac.Lunar l = Almanac.lunar(y, mo, d);
            return "今天是 " + mo + " 月 " + d + " 日，" + week + (l == null ? "" : "，农历" + l.text());
        }
        if ("lunar".equals(kind)) return Almanac.lunarAnswer(y, mo, d);
        if ("term".equals(kind)) return Almanac.termAnswer(y, mo, d);
        if ("festival".equals(kind)) return Almanac.festivalAnswer(y, mo, d);
        if ("nextfestival".equals(kind)) return Almanac.nextFestivalAnswer(y, mo, d);
        if ("nextholiday".equals(kind)) return Almanac.nextHolidayAnswer(y, mo, d);
        if ("yearday".equals(kind) || "yearleft".equals(kind)) return Almanac.yearDayAnswer(y, mo, d, "yearleft".equals(kind));
        if ("countdown".equals(kind)) return label == null || label.isEmpty() ? null : Almanac.countdownAnswer(y, mo, d, label);
        if ("workday".equals(kind)) return Almanac.workAnswer(y, mo, d, in);
        if ("weekday".equals(kind)) return "今天" + week;
        int h = c.get(java.util.Calendar.HOUR_OF_DAY), m = c.get(java.util.Calendar.MINUTE);
        String part = h < 5 ? "凌晨" : h < 8 ? "早上" : h < 11 ? "上午" : h < 13 ? "中午" : h < 18 ? "下午" : "晚上";
        int h12 = h > 12 ? h - 12 : h;
        return "现在是" + part + " " + h12 + " 点" + (m == 0 ? "整" : " " + m + " 分");
    }

    /** Wake word, politeness and trailing particles removed from both ends, as often as they repeat. */
    static String normalise(String raw) {
        String s = raw == null ? "" : raw.trim();
        for (String before = null; !s.equals(before); ) {
            before = s;
            s = LEAD.matcher(s).replaceFirst("").trim();
            s = TAIL.matcher(s).replaceFirst("").trim();
        }
        return s;
    }

    /** Digits, or Chinese numerals up to 百 (二十→20, 十五→15, 一百→100, 两→2, 十→10); -1 when s is not a number. */
    static int number(String s) {
        if (s == null || s.isEmpty()) return -1;
        if (s.matches("[0-9]{1,4}")) return Integer.parseInt(s);
        int total = 0, digit = -1, tens = 0, hundreds = 0;
        for (int i = 0; i < s.length(); i++) {
            int d = digitOf(s.charAt(i));
            if (d >= 0) {
                if (digit > 0) return -1; // two numerals in a row, as in 二三; 零五 (一百零五) is fine
                digit = d;
                continue;
            }
            char c = s.charAt(i);
            if (c == '十') { if (++tens > 1) return -1; total += (digit < 0 ? 1 : digit) * 10; }
            else if (c == '百') { if (++hundreds > 1) return -1; total += (digit < 0 ? 1 : digit) * 100; }
            else return -1;
            digit = -1;
        }
        return total + Math.max(digit, 0);
    }

    private static int digitOf(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c == '两') return 2;
        int i = "零〇一二三四五六七八九".indexOf(c);
        return i < 0 ? -1 : i <= 1 ? 0 : i - 1;
    }

    /** 「查一下MINI MAX最新的股价」: the name as the lookup needs it — the verb in front taken off, the spaces inside
     * collapsed to one. Null when what is left is no name (empty, only spaces, a single Latin letter, too long). */
    private static QuickCommand stock(String s) {
        Matcher m = STOCK.matcher(STOCK_VERB.matcher(s).replaceFirst(""));
        if (!m.matches()) return null;
        String name = m.group(1).trim().replaceAll(" +", " ");
        // 「查A的股价」: 的 belongs to the sentence, not to the name — a name never ends in it
        if (name.isEmpty() || name.length() > 12 || name.endsWith("的") || name.matches("[A-Za-z]")) return null;
        return cmd("stock_quote", "query", name, "final", true);
    }

    private static QuickCommand volume(String s) {
        Matcher set = SET.matcher(s);
        if (set.matches()) {
            int level = level(set.group(1));
            return level < 0 ? null : cmd("set_volume", "level", Math.max(0, Math.min(100, level)));
        }
        if (SET_MAX.matcher(s).matches()) return cmd("set_volume", "level", 100);
        if (UP.matcher(s).matches() || TOO_QUIET.matcher(s).matches()) return cmd("adjust_volume", "direction", "up", "steps", 10);
        if (DOWN.matcher(s).matches() || TOO_LOUD.matcher(s).matches()) return cmd("adjust_volume", "direction", "down", "steps", 10);
        if ("静音".equals(s)) return cmd("adjust_volume", "direction", "mute");
        return UNMUTE.contains(s) ? cmd("adjust_volume", "direction", "unmute") : null;
    }

    /** The level as it is said: 「20」「20%」「百分之三十」「百分之百」 and 「最大」「最大声」「最高」「满」, which is 100.
     * -1 when it is not a level (最小 is nothing: 0 or 1 is not clear). */
    private static int level(String said) {
        String x = said == null ? "" : said.trim();
        if (x.endsWith("%") || x.endsWith("％")) x = x.substring(0, x.length() - 1);
        else if (x.startsWith("百分之")) x = x.substring(3);
        return x.matches("最大声|最大|最高|满") ? 100 : number(x);
    }

    private static QuickCommand media(String s) {
        String action = PAUSE.contains(s) ? "pause" : PLAY.contains(s) ? "play" : NEXT.contains(s) ? "next"
                : PREVIOUS.contains(s) ? "previous" : STOP.contains(s) ? "stop" : null;
        return action == null ? null : cmd("media_control", "action", action);
    }

    /** 伴奏 / 原唱 / 随心唱：on=true 是伴奏（随心唱开着），on=false 是原唱。放某首歌的伴奏版是找歌，不是切模式，
     * 所以整句必须只是这个命令（「放演员的伴奏」不是）。 */
    private static QuickCommand karaoke(String s) {
        if (BANZOU.matcher(s).matches() || MIC_ON.matcher(s).matches()) return cmd("netease_karaoke", "on", true);
        if (YUANCHANG.matcher(s).matches() || MIC_OFF.matcher(s).matches()) return cmd("netease_karaoke", "on", false);
        return null;
    }

    private static QuickCommand keys(String s) {
        if (HOME.contains(s)) return cmd("press_keys", "keys", new JSONArray().put("home"));
        return BACK.contains(s) ? cmd("press_keys", "keys", new JSONArray().put("back")) : null;
    }

    private static QuickCommand input(String s) {
        // "换到 HDMI 2": the spaces around the name carry nothing, drop them before matching.
        Matcher m = HDMI.matcher(s.replaceAll("[\\s　]", ""));
        if (!m.matches()) return null;
        int n = number(m.group(1));
        return n >= 1 && n <= 3 ? cmd("switch_input", "name", "HDMI" + n) : null;
    }

    private static QuickCommand sleep(String s) {
        Matcher m = SLEEP.matcher(s);
        if (!m.matches()) return null;
        int n = number(m.group(1));
        if (n < 0) return null;
        int minutes = "小时".equals(m.group(2)) ? n * 60 : n;
        return minutes >= 1 && minutes <= 600 ? cmd("sleep_timer", "minutes", minutes) : null;
    }

    /** 打开 + a name that is one installed label, or is contained in exactly one (at least 2 characters). */
    private static QuickCommand app(String s, Collection<String> labels) {
        if (labels == null || !s.startsWith("打开") || s.length() <= 2) return null;
        String found = resolveApp(s.substring(2), labels);
        return found == null ? null : cmd("open_app", "app", found);
    }

    /**
     * 「我想看 X」：一个片子的名字，交给 tencent_video_open 去腾讯视频的搜索里对一次（strict）。
     *
     * <p>三种说法：想/要看 + 名字（类型说不说都行）、播放/放/打开/看 + 类型 + 名字（类型必须有：「播放别无选择」可能是一首歌）、
     * 只有一个「看」+ 名字。名字 3–20 个字（说了类型两个字也够），书名号里的原样当名字。名字末尾的「第3集」是要看的那一集
     * （第几集写在别处、「最新一集」「最后一集」还是模型的）。别的一概交回模型：它可能是问句、是要看某个视频/直播、
     * 或者要打开的是另一个应用。
     */
    private static QuickCommand watch(String s) {
        Matcher tail = WATCH_TAIL.matcher(s);
        String core = tail.matches() ? tail.group(1) : s;
        if (core.isEmpty()) return null;
        Pattern[] forms = {WATCH_WANT, WATCH_PLAY, WATCH_BARE};
        for (Pattern form : forms) {
            Matcher m = form.matcher(core);
            if (!m.matches()) continue;
            String kind = m.group(1), title = m.group(2).trim();
            int episode = 0;
            Matcher at = WATCH_EPISODE_END.matcher(title);
            if (at.find()) {
                int n = number(at.group(1));
                // 第0集、第几万集：不是要看的那一集，整句交回模型
                if (n >= 1 && n <= 999) { episode = n; title = title.substring(0, at.start()).trim(); }
            }
            boolean wrapped = title.length() >= 2 && title.startsWith("《") && title.endsWith("》");
            if (wrapped) title = title.substring(1, title.length() - 1).trim();
            if (!watchTitle(title, wrapped, kind != null, episode > 0)) continue;
            if (kind == null) return watchOpen(title, null, episode);
            String said = "动画片".equals(kind) || "动画".equals(kind) ? "动漫" : kind;
            return watchOpen(title, said, episode);
        }
        return null;
    }

    /** tencent_video_open 的参数：名字、类型（没说就没有）、第几集（没说就没有），加上快命令才带的 strict。 */
    private static QuickCommand watchOpen(String title, String kind, int episode) {
        if (kind == null)
            return episode > 0 ? cmd("tencent_video_open", "title", title, "episode", episode, "strict", true)
                    : cmd("tencent_video_open", "title", title, "strict", true);
        return episode > 0
                ? cmd("tencent_video_open", "title", title, "kind", kind, "episode", episode, "strict", true)
                : cmd("tencent_video_open", "title", title, "kind", kind, "strict", true);
    }

    /** 说的是不是一个片子的名字：够长、不是问句、不是要看某段视频、不是电视上的一个地方。
     *
     *  <p>说了要看第几集时，末尾那个语气词是名字的一部分（《奔跑吧》）：句子已经说完了，它挂在名字上。 */
    private static boolean watchTitle(String title, boolean wrapped, boolean kindNamed, boolean episodeSaid) {
        int n = title.length();
        if (n < (kindNamed ? 2 : 3) || n > 20) return false;
        String body = episodeSaid && n > 0 && WATCH_PARTICLE.matcher(title.substring(n - 1)).find()
                ? title.substring(0, n - 1) : title;
        if (!wrapped && (WATCH_PARTICLE.matcher(body).find() || WATCH_PARTICLE_END.matcher(body).find())) return false;
        if (WATCH_WORD.matcher(title).find() || WATCH_EPISODE.matcher(title).find()) return false;
        return !NOT_WATCH_TITLE.contains(title);
    }

    /** 退出 / 关掉 / 关闭 / 关了 + an installed app, with 回到桌面 (回到主页…) said before or after it and 并 / 然后 /
     * 再 or a comma between; and 「把<app>关了/关掉」, which normalise() leaves as 「<app>关」/「<app>关掉」.
     * 「回到桌面停止播放」 (2026-11-09, 8.4 s and four model rounds) is the home and the playing stopped in one step:
     * exit acts on what is playing / in front, so no app goes along. */
    private static QuickCommand exit(String s, Collection<String> labels) {
        Matcher lead = HOME_LEAD.matcher(s);
        boolean homeSaid = lead.find();
        String core = homeSaid ? s.substring(lead.end()) : s;
        Matcher rest = HOME_TAIL.matcher(core);
        if (rest.find()) { core = core.substring(0, rest.start()); homeSaid = true; }
        if (homeSaid && STOP.contains(normalise(core))) return cmd("media_control", "action", "exit");
        Matcher first = EXIT_FIRST.matcher(core), last = EXIT_LAST.matcher(core);
        String name = first.matches() ? first.group(1) : last.matches() ? last.group(1) : null;
        if (name == null || name.isEmpty() || NOT_APP.contains(name)) return null;
        // the app named goes along: exit leaves that one, not whatever is in front (2026-11-09 review)
        String app = resolveApp(name, labels);
        return app == null ? null : cmd("media_control", "action", "exit", "app", app);
    }

    /** The installed app a name means, the way 「打开…」 resolves it: an alias, then an exact label, then the one label
     * the name is part of (at least 2 characters). Null when it is not exactly one installed app. */
    private static String resolveApp(String name, Collection<String> labels) {
        if (labels == null || name == null || name.isEmpty()) return null;
        String x = name;
        for (String[] alias : APP_ALIASES) if (alias[0].equalsIgnoreCase(x)) { x = alias[1]; break; }
        String found = null;
        int hits = 0;
        for (String label : labels) {
            if (label == null) continue;
            if (label.equals(x)) return label;
            if (x.length() >= 2 && label.contains(x) && ++hits == 1) found = label;
        }
        return hits == 1 ? found : null;
    }

    private static QuickCommand cmd(String tool, Object... kv) {
        JSONObject args = new JSONObject();
        try {
            for (int i = 0; i < kv.length; i += 2) args.put((String) kv[i], kv[i + 1]);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        return new QuickCommand(tool, args);
    }
}
