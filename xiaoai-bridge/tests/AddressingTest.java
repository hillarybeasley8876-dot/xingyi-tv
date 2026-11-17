package xyz.erapi0neer.xiaoaibridge;

/** JVM test for Addressing: who a sentence is for in 点名模式, and the spoken mode switch (2026-11-06). */
public final class AddressingTest {
    private static int pass, fail;
    private static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("FAIL " + what); } }

    public static void main(String[] args) {
        // calls 星仪 by name
        check(Addressing.namesXingyi("星仪帮我查一下明天的天气"), "星仪 first");
        check(Addressing.namesXingyi("星仪，把音量调大一点"), "星仪 with a comma");
        check(Addressing.namesXingyi("让星仪帮我放首歌"), "星仪 inside the sentence");
        check(Addressing.namesXingyi("星移帮我查一下新闻"), "a homophone first: 星移");
        check(Addressing.namesXingyi("新仪，打开B站"), "a homophone first: 新仪");
        check(Addressing.namesXingyi("心仪帮我看看推特"), "a homophone first: 心仪");
        // not addressed to 星仪
        check(!Addressing.namesXingyi("今天天气怎么样"), "a plain request");
        check(!Addressing.namesXingyi("放一首我心仪的歌"), "心仪 inside a sentence is a word, not the name");
        check(!Addressing.namesXingyi("星期一有什么安排"), "星期一 is not 星仪");
        check(!Addressing.namesXingyi(null) && !Addressing.namesXingyi(""), "nothing said");
        // the spoken switch
        check("refusals".equals(Addressing.modeRequest("小爱走小爱，星仪走星仪")), "小爱走小爱");
        check("refusals".equals(Addressing.modeRequest("切换到点名模式")), "点名模式");
        check("refusals".equals(Addressing.modeRequest("以后叫星仪才找星仪")), "叫星仪才找星仪");
        check("refusals".equals(Addressing.modeRequest("不要全部交给星仪")), "不要全部交给星仪 is by name, not all");
        check("refusals".equals(Addressing.modeRequest("小爱的事让小爱做")), "小爱的事让小爱做");
        check("all".equals(Addressing.modeRequest("全部交给星仪")), "全部交给星仪");
        check("all".equals(Addressing.modeRequest("以后什么都交给星仪")), "什么都交给星仪");
        check("all".equals(Addressing.modeRequest("星仪接管全部")), "星仪接管全部");
        check(Addressing.modeRequest("星仪帮我查一下天气") == null, "a request is not a mode switch");
        check(Addressing.modeRequest("把这个视频全部交给星仪处理然后帮我下载下来再转成音频发到我的手机上好吗") == null,
                "a long request that mentions it is not a switch");
        check(Addressing.modeRequest(null) == null, "nothing said");
        System.out.println("AddressingTest: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
