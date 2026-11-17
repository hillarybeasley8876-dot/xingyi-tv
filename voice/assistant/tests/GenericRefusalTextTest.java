package xyz.erapi0neer.xingyi;

import org.json.JSONObject;

public final class GenericRefusalTextTest {
    static int count;
    static void check(boolean test) { if (!test) throw new AssertionError("check " + (count+1)); count++; }
    static GenericRefusalText.Scope parse(String s, GenericRefusalText.Kind kind) {
        GenericRefusalText.Scope part=GenericRefusalText.scope(s);
        check(part!=null && part.kind==kind);
        return part;
    }
    static JSONObject arg(String name, String value) throws Exception { return new JSONObject().put(name,value); }
    public static void main(String[] args) throws Exception {
        GenericRefusalText.Scope read=parse("请问现在电视音量是多少？", GenericRefusalText.Kind.READ);
        check(read.allows("get_status",new JSONObject()) && !read.allows("set_volume",new JSONObject().put("level",18)));
        check(parse("电视当前状态",GenericRefusalText.Kind.READ).allows("get_status",new JSONObject()));
        check(parse("电视上安装了哪些应用？",GenericRefusalText.Kind.READ).allows("list_apps",new JSONObject()));
        check(parse("定时休眠还有多久",GenericRefusalText.Kind.READ).allows("get_sleep_timer",new JSONObject()));
        GenericRefusalText.Scope app=parse("请打开网易云音乐",GenericRefusalText.Kind.APP);
        check(app.allows("open_app",arg("name","网易云音乐")));
        check(app.allows("list_apps",new JSONObject()));
        check(!app.allows("get_status",new JSONObject()) && !app.allows("open_app",arg("name","网易云音乐精选")));
        check(parse("启动应用我的播放器",GenericRefusalText.Kind.APP).allows("open_app",arg("name","我的播放器")));
        check(parse("打开哔哩哔哩这个应用",GenericRefusalText.Kind.APP).allows("open_app",arg("name","哔哩哔哩")));
        for(String name : new String[]{"B站","bilibili","哔哩哔哩","BILIBILI官方客户端"}) {
            GenericRefusalText.Scope b=parse("打开"+name,GenericRefusalText.Kind.APP);
            check(b.allows("open_app",arg("name","哔哩哔哩")) && !b.allows("open_app",arg("name","云视听极光")));
            check(!b.allows("open_app",arg("name","云视听小电视")));
            GenericRefusalText.Scope install=parse("请安装"+name,GenericRefusalText.Kind.INSTALL);
            check("tv.danmaku.bili".equals(install.name) && install.allows("install_app",arg("package","tv.danmaku.bili")));
            check(!install.allows("install_app",arg("package","com.ktcp.video")) && !install.allows("open_app",arg("name",name)));
        }
        check(parse("小爱同学，帮我在电视上安装 Bilibili，不是云电视",GenericRefusalText.Kind.INSTALL).name.equals("tv.danmaku.bili"));
        for (String q : new String[]{"安装云视听小电视","别安装哔哩哔哩","安装B站然后关机","卸载哔哩哔哩","安装哔哩哔哩电视版"})
            check(GenericRefusalText.scope(q)==null);
        String keyword="星辰亻旅行者";
        for (String input : new String[]{"打开b站搜索"+keyword,"打开B站然后搜索"+keyword,
                "小爱同学，在哔哩哔哩上搜一下"+keyword,"去bilibili搜一搜"+keyword}) {
            GenericRefusalText.Scope search=parse(input,GenericRefusalText.Kind.SEARCH);
            check(GenericRefusalText.BILI_PACKAGE.equals(search.name) && keyword.equals(search.title));
            check(search.allows("search_video",arg("app","tv.danmaku.bili").put("query",keyword)));
            check(!search.allows("search_video",arg("app","哔哩哔哩").put("query","星辰旅行者")));
            check(!search.allows("search_video",arg("app","云视听小电视").put("query",keyword)) && !search.allows("open_app",arg("name","B站")));
        }
        check(parse("B站搜索Ａ Ｂ　Ｃ",GenericRefusalText.Kind.SEARCH).title.equals("Ａ Ｂ　Ｃ"));
        for (String input : new String[]{"打开B站然后搜索甲然后关机","打开B站搜索甲，并执行付款","打开B站搜索视频再点赞",
                "打开B站搜索甲，关闭电视","不要打开B站搜索甲","打开B站搜索甲；回家"}) check(GenericRefusalText.scope(input)==null);
        GenericRefusalText.Scope music = parse("打开网易云音乐，播放林俊杰的《不为谁而作的歌》",GenericRefusalText.Kind.SONG);
        String substitute = "抱歉，当前暂不支持使用网易云音乐源播放，将继续使用当前音乐源为您播放";
        check("native_music_provider_unavailable".equals(GenericRefusalText.classify(music,substitute)));
        check(GenericRefusalText.classify(parse("打开网易云音乐",GenericRefusalText.Kind.APP),substitute)==null);
        check(GenericRefusalText.classify(music,"已使用网易云音乐源为您播放")==null);
        check(GenericRefusalText.classify(music,substitute+"，请先购买会员")==null);
        JSONObject expected = arg("artist","林俊杰").put("title","不为谁而作的歌");
        check(music.allows("play_netease_music",expected));
        check(parse("用网易云播放林俊杰的不为谁而作的歌",GenericRefusalText.Kind.SONG).allows("play_netease_music",expected));
        check(parse("网易云音乐打开林俊杰的不为谁而作的歌",GenericRefusalText.Kind.SONG).allows("play_netease_music",expected));
        for (String artist : new String[]{"刘端端","黄霄云"}) {
            JSONObject desired = arg("artist",artist).put("title","空心");
            for (String text : new String[]{"播放"+artist+"的空心","小爱同学，帮我播放"+artist+"唱的《空心》",
                    "我想听"+artist+"演唱的空心","网易云音乐播放"+artist+"的空心"}) {
                check(parse(text,GenericRefusalText.Kind.SONG).allows("play_netease_music",desired));
            }
            check(GenericRefusalText.scope(artist+"的空心")==null);
            GenericRefusalText.Scope selection=GenericRefusalText.musicSelection(artist+"的空心。");
            check(selection!=null && selection.kind==GenericRefusalText.Kind.SONG && selection.allows("play_netease_music",desired));
        }
        for (String text : new String[]{"播放空心","网易云音乐播放空心","用网易云播放《空心》","我想听空心"}) {
            GenericRefusalText.Scope title = parse(text,GenericRefusalText.Kind.TITLE);
            check("空心".equals(title.title) && title.artist==null);
            check(title.allows("play_netease_title",arg("title","空心")));
            check(!title.allows("play_netease_music",arg("title","空心").put("artist","其他歌手")));
            check(!title.allows("play_netease_title",arg("title","空心").put("artist","其他歌手")));
            check(!title.allows("play_netease_title",arg("title","空心菜")));
        }
        check(parse("播放《不为谁而作的歌》",GenericRefusalText.Kind.TITLE).title.equals("不为谁而作的歌"));
        check(parse("播放刘端端的歌声与微笑",GenericRefusalText.Kind.SONG).title.equals("歌声与微笑"));
        check(parse("用网易云播放刘端端的歌曲《空心》",GenericRefusalText.Kind.SONG).title.equals("空心"));
        for (String text : new String[]{"空心","播放空心","播放黄霄云的空心","黄霄云的歌","我的歌单","黄霄云的空心然后关灯",
                "播放QQ音乐里的空心","黄霄云的空心MV","介绍黄霄云的空心","黄霄云的空心吗"})
            check(GenericRefusalText.musicSelection(text)==null);
        check(parse("小爱同学，网易云音乐，打开林俊杰的《不为谁而作的歌》",GenericRefusalText.Kind.SONG).allows("play_netease_music",expected));
        for (String q : new String[]{"网易云音乐打开林俊杰的歌","打开网易云音乐，播放林俊杰的歌曲",
                "用网易云音乐听一首林俊杰的歌", "小爱同学，网易云打开林俊杰的音乐。"}) {
            GenericRefusalText.Scope artist=parse(q,GenericRefusalText.Kind.ARTIST);
            check("林俊杰".equals(artist.artist) && artist.title==null);
            check("native_music_provider_unavailable".equals(GenericRefusalText.classify(artist,substitute)));
            check(!artist.allows("play_netease_music",expected) && !artist.allows("open_app",arg("name","网易云音乐")));
            check(GenericRefusalText.classify(artist,"正在使用网易云音乐源播放")==null);
        }
        check(parse("网易云音乐打开和田光司的歌",GenericRefusalText.Kind.ARTIST).artist.equals("和田光司"));
        check(!music.allows("open_app",arg("name","网易云音乐")));
        check(!music.allows("play_netease_music",arg("artist","翻唱者").put("title","不为谁而作的歌")));
        check(!music.allows("play_netease_music",arg("artist","林俊杰").put("title","不为谁而作的歌 (Live)")));
        check(!music.allows("cancel_sleep_timer",new JSONObject()));
        for (String s : new String[]{"打开书房灯", "打开应用星仪电视助手","打开小爱同学这个应用","别打开网易云音乐",
                "打开应用钱包","如果有网就打开网易云音乐","打开网易云音乐然后关灯",
                "空心","黄霄云的空心","打开空心","播放电视剧空心","播放电影空心","播放空心第二集","播放空心MV",
                "请用QQ音乐播放刘端端的空心","播放酷狗里的空心","播放我的收藏夹","播放每日推荐","播放私人FM",
                "查一下卧室的摄像头", "查询设备密码", "用网易云播放林俊杰的《歌》并关机",
                "网易云音乐打开我的歌","网易云音乐打开我收藏的歌","网易云音乐打开他喜欢的歌",
                "网易云音乐打开林俊杰的歌单","用网易云播放林俊杰的专辑","网易云音乐打开林俊杰和周杰伦的歌",
                "网易云音乐打开林俊杰的歌然后关灯","网易云音乐打开我的播放列表","网易云音乐打开的歌"})
            check(GenericRefusalText.scope(s)==null);
        for (String s : new String[]{"抱歉，当前应用暂不支持该操作。","我暂时不支持这个操作。",
                "抱歉，小爱同学目前还不支持这项功能。","抱歉，您的电视暂不支持此功能。"})
            check(GenericRefusalText.classify(music,s)!=null);
        for (String s : new String[]{"没帮到你","找不到","出错了","抱歉，当前应用暂不支持该操作。我已开始播放。","我还不知道",
                "“我暂时不支持这个操作”","如果我不支持这个操作，你可以手动。","不存在","没有权限打开该应用","请再说一遍",
                "请问您说的是我不支持这个操作吗？","我暂时无法播放这首歌，网络断开了。","正在为你打开。", "系统无法处理。"})
            check(GenericRefusalText.classify(music,s)==null);
        System.out.println("PASS " + count + " generic refusal / exact authorized action checks");
    }
}
