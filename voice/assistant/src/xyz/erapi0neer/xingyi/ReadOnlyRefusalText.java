package xyz.erapi0neer.xingyi;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Allow complete, affirmative declarations of inability for a bounded read-only request. */
public final class ReadOnlyRefusalText {
    private static final String WHO = "(?:我|小爱(?:同学)?)";
    private static final String WHEN = "(?:目前|当前|暂时|现在|暂|还|尚)?";
    private static final String STATION = "星仪(?:电视助手|助手|接手服务|服务)?";
    private static final String LOCAL = "(?:自己|本机|本地|内部|私有)?";
    private static final String TIMER = "(?:休眠定时(?:器|状态|设置|时间)?|定时休眠(?:状态|设置)?|定时状态|定时)";
    private static final String RECEIPT = "(?:(?:上一次|上次|最近一次|最新)(?:成功)?(?:调用)?回执(?:的)?序号)";
    private static final String METRIC = "(?:" + TIMER + "|" + RECEIPT + "|(?:当前)?(?:媒体)?音量(?:状态)?|静音状态)";
    private static final String TARGET = STATION + "(?:的)?" + LOCAL + "(?:的)?(?:保存|记录|设置|存储)?(?:的)?" + LOCAL
            + "(?:的)?" + METRIC;
    private static final Pattern QUERY = Pattern.compile("(?:小爱同学[, ]*)?(?:请问|请你|请|帮我|麻烦你)?"
            + "(?:你能|可以帮我|能不能帮我|能否)?(?:查一下|查询|读取|查看|查出|告诉我|检查)"
            + "(?:一下)?" + TARGET + "(?:是多少|是什么|吗)?[?。！!]?");
    private static final String LEAD = "(?:(?:很抱歉|抱歉|对不起|不好意思)[,，。 ]*)?";
    private static final String NOT_ABLE = "(?:无法|不能|做不到|没有办法|没办法|不具备能力)";
    // The complement is a noun phrase about the requested device metric, never a second predicate.
    private static final String OBJECT_WORD = "(?>" + STATION + "|本机|本地|内部|设备|系统|当前|相关|具体|详细|实际|所保存|保存|存储|记录"
            + "|最新|最近一次|上一次|上次|成功|调用|回执|序号|休眠|睡眠|定时器|定时|媒体|音量|静音|时间|安排|详情"
            + "|设置|设定|状态|数据|信息|配置|功能|任务|里的|中的|里的|参数|助手|平台|本身|自己的|自身|您的|你的|的|内|里|中|上)";
    private static final String OBJECT = OBJECT_WORD + "{1,30}";
    private static final String READ = "(?:查询|查看|读取|获取|访问|调取|获知)";
    private static final Pattern CANNOT_READ = Pattern.compile(LEAD + "(?:" + WHEN + WHO + "|" + WHO + WHEN + ")"
            + "(?:还|尚)?" + NOT_ABLE + "(?:直接)?(?:帮[你您]|为[你您])?" + READ
            + "(?:(?:或|以及)" + READ + ")?" + OBJECT + "[。.!！]?");
    private static final Pattern UNSUPPORTED = Pattern.compile(LEAD
            + "(?:(?:" + WHEN + WHO + "|" + WHO + WHEN + ")|(?:这台|当前|该|[你您]的)(?:设备|应用|电视))"
            + "(?:目前|当前|暂时|暂|还|尚)?不支持(?:查询|查看|读取|获取)?(?:该操作|此操作|此功能|这个功能|这项功能|这个操作|"
            + TARGET + ")[。!！]?");
    // These are complete localized messages from com.xiaomi.voicecontrol 5.12.0.7.
    private static final Pattern FIRMWARE = Pattern.compile(
            "抱歉[,，](?:当前应用暂不支持该操作|[你您]的设备暂不支持此功能|该设备暂不支持此功能|[你您]的电视暂不支持此功能)[。.]?");
    private static final String HELP_NOUN = "(?>" + STATION + "|助手|应用程序|客户端|界面|菜单|应用|设备|电视|设置|定时|休眠"
            + "|程序|功能|状态|配置|信息|数据|选项|说明书|说明|文档|平台|页面|里的|中的|的|具体|相关|相应|实际|详细|本机"
            + "|内部|本地|官方|自身|本身|系统|对应|里面|其中|该|这个|此|里|内|中|下|上|安排|详情|时间|相关|帮助|计划|参数|任务)";
    // An advisory can contain only a read/navigation verb and bounded vocabulary.
    // A number, a claimed state, a quoted phrase, or an executable instruction fails this grammar.
    private static final Pattern SUGGEST = Pattern.compile("[，,。.!！](?:(?:建议|请)[你您]?|[你您]可以|也可以|可以|可)(?:尝试|试着)?"
            + "(?:(?:打开|在|到|前往|进入|通过)" + HELP_NOUN + "{1,18}(?:[，,](?:在|到|进入)?" + HELP_NOUN + "{0,8})?)?"
            + "(?:自行|直接|手动|进行)?(?:查询|查看|查阅|了解|参考)" + HELP_NOUN + "{0,14}"
            + "(?:[，,]?(?:或者|或|也可以)(?:查询|查看|查阅|参考)" + HELP_NOUN + "{1,14})?[。.!！]?$");
    private static final Pattern PROTECTED = Pattern.compile("隐私|私有数据|权限|授权|安全|密码|登录|个人数据|敏感|未成年人");

    public static boolean isSupportedQuery(String query) {
        if (query == null || query.length() > 120) return false;
        return QUERY.matcher(normalize(query)).matches();
    }

    /** Returns a diagnostic template ID, never the input or reply. Null means stop. */
    public static String classify(String query, String reply) {
        if (!isSupportedQuery(query) || reply == null || reply.length() > 240) return null;
        String s = normalize(reply);
        if (s.isEmpty() || PROTECTED.matcher(s).find()) return null;
        // One explicitly recognized handoff suggestion is allowed. Other trailing clauses
        // (especially successful facts, qualifications, auth failures or requests) veto it.
        s = SUGGEST.matcher(s).replaceFirst("");
        if (FIRMWARE.matcher(s).matches()) return "firmware_unsupported";
        boolean sameMetric = query.contains("定时") ? s.contains("定时") || s.contains("休眠") :
                query.contains("回执") ? s.contains("回执") || s.contains("序号") :
                s.contains("音量") || s.contains("静音");
        if (!sameMetric) return null;
        if (CANNOT_READ.matcher(s).matches()) return "cannot_read";
        if (UNSUPPORTED.matcher(s).matches()) return "unsupported_query";
        return null;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
    }

    private ReadOnlyRefusalText() {}
}
