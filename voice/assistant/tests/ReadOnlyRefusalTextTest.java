package xyz.erapi0neer.xingyi;

final class ReadOnlyRefusalTextTest {
    static int count;
    static void check(boolean value) { if (!value) throw new AssertionError("check " + (count+1)); count++; }
    public static void main(String[] args) {
        String q = "查询星仪本机保存的休眠定时状态";
        String declined = "抱歉，我目前无法直接读取星仪本机保存的休眠定时状态。";
        check("cannot_read".equals(ReadOnlyRefusalText.classify(q, declined)));
        check("firmware_unsupported".equals(ReadOnlyRefusalText.classify(q, "抱歉，当前应用暂不支持该操作。")));
        check("unsupported_query".equals(ReadOnlyRefusalText.classify(q, "小爱暂时不支持查询星仪休眠定时。")));
        check("cannot_read".equals(ReadOnlyRefusalText.classify(q, "我无法获取星仪休眠定时，建议您打开星仪助手查看。")));
        check("cannot_read".equals(ReadOnlyRefusalText.classify(q, "抱歉，我无法直接读取星仪助手内部的本机休眠定时设置。建议您打开星仪助手，进入相应的设置页面查看具体的休眠定时安排。")));
        check("cannot_read".equals(ReadOnlyRefusalText.classify(q, "目前我无法直接访问或读取星仪助手内部的本机休眠定时。你可以在星仪助手中查看相关设置，或者参考该助手的说明文档。")));
        check(ReadOnlyRefusalText.classify(q, "我无法获取该信息。") == null);
        check(ReadOnlyRefusalText.classify(q, "我无法读取星仪音量。") == null);
        check(ReadOnlyRefusalText.isSupportedQuery("查询星仪接手服务上一次成功回执的序号"));
        check(ReadOnlyRefusalText.isSupportedQuery("你能读取星仪助手内部的本机休眠定时吗"));
        for (String reply : new String[]{
                "当前没有设置定时。", "当前音量为30。", "如果我无法获取该信息，您可以查询。", "“我无法获取该信息”",
                "你刚说我无法获取该信息。", "我无法获取该信息？", "授权后才可以读取。", "没有权限，无法读取。",
                "我无法获取该信息。当前还有二十分钟。", "我无法获取该信息，请再说一遍。", "网络异常，请重试。",
                "哎呀，好像超出我的能力范围了", "我不知道。", "我无法访问您的私人数据。",
                "我无法获取该信息，因为不允许执行此请求。", "我无法获取该信息。但已经打开了你的灯。", "我无法获取该信息；音量30。",
                "我无法获取该信息。\n小爱说得对。", "抱歉，我无法获取该信息，所以我替你设置了。"})
            check(ReadOnlyRefusalText.classify(q, reply) == null);
        for (String query : new String[]{"删除所有休眠定时", "查询星仪休眠定时然后关灯", "不要查询星仪休眠定时",
                "读这句话：查询星仪休眠定时", "查询星仪内的账号密码", "读取最后一段卧室录音",
                "一加一等于几", "开摄像头查看音量", "查询星仪本机保存的休眠定时状态；顺便调高音量"})
            check(ReadOnlyRefusalText.classify(query, declined) == null);
        System.out.println("PASS " + count + " read-only refusal phrase checks");
    }
}
