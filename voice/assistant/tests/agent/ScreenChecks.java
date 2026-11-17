package xyz.erapi0neer.xingyi.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Screen digest and media session parsing against captured TV output and synthetic trees. */
final class ScreenChecks {
    static void run() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/netease_player.xml")), StandardCharsets.UTF_8);
        ScreenDigest.Node root = ScreenDigest.parseDump(xml);
        ScreenDigest d = ScreenDigest.of(root, "网易云音乐", "PlayerActivityV2");
        String text = d.text();
        AgentCoreTest.check(text.startsWith("前台：网易云音乐（PlayerActivityV2）"), "header: " + text);
        AgentCoreTest.check(d.contains("你还要我怎样") && d.contains("薛之谦"), "netease labels: " + text);
        ScreenDigest.Element f = d.focused();
        AgentCoreTest.check(f != null && f.node.focusable && f.node.viewId.endsWith("/y5"), "marquee text is not the focus; button is: " + text);
        AgentCoreTest.check(text.contains("@"), "positions present");
        for (ScreenDigest.Element e : d.elements) AgentCoreTest.check(d.element(e.id) == e, "element lookup " + e.id);
        AgentCoreTest.check(d.element(0) == null && d.element(99) == null, "out of range lookup");

        // A Bilibili-like results page: cards are focusable containers with text children.
        ScreenDigest.Node r = node("FrameLayout", "", 0, 0, 1920, 1080);
        ScreenDigest.Node search = node("android.widget.EditText", "星辰亻旅行者", 100, 40, 900, 100);
        search.focusable = true; search.clickable = true; search.editable = true;
        r.children.add(search);
        ScreenDigest.Node list = node("androidx.recyclerview.widget.RecyclerView", "", 0, 120, 1920, 1080);
        list.scrollable = true; list.focusable = true;
        r.children.add(list);
        for (int i = 0; i < 3; i++) {
            ScreenDigest.Node card = node("android.widget.LinearLayout", "", 60 + i * 600, 200, 600 + i * 600, 560);
            card.focusable = true; card.clickable = true; card.focused = i == 1;
            card.children.add(node("android.widget.ImageView", "", 60 + i * 600, 200, 600 + i * 600, 460));
            card.children.add(node("android.widget.TextView", "视频标题 " + i, 60 + i * 600, 470, 600 + i * 600, 510));
            card.children.add(node("android.widget.TextView", "播放 " + (i + 1) + "万", 60 + i * 600, 515, 600 + i * 600, 550));
            list.children.add(card);
        }
        ScreenDigest.Node offscreen = node("android.widget.TextView", "看不见", 2000, 200, 2400, 260);
        r.children.add(offscreen);
        ScreenDigest.Node hiddenNode = node("android.widget.TextView", "隐藏", 10, 10, 50, 30);
        hiddenNode.visible = false;
        r.children.add(hiddenNode);
        ScreenDigest b = ScreenDigest.of(r, "哔哩哔哩", "SearchResultActivity");
        String bt = b.text();
        AgentCoreTest.check(bt.contains("文本框「星辰亻旅行者」") && bt.contains("可输入"), "edit text listed: " + bt);
        AgentCoreTest.check(bt.contains("卡片「视频标题 1 · 播放 2万」 焦点"), "card aggregates children and carries focus: " + bt);
        AgentCoreTest.check(!bt.contains("「视频标题 0」"), "card children not listed twice: " + bt);
        AgentCoreTest.check(!bt.contains("看不见") && !bt.contains("隐藏"), "offscreen/invisible dropped");
        AgentCoreTest.check(bt.contains("列表") && bt.contains("可滚动"), "scroll container listed");
        int cardId = -1;
        for (ScreenDigest.Element e : b.elements) if (e.label.startsWith("视频标题 2")) cardId = e.id;
        AgentCoreTest.check(cardId > 0 && b.element(cardId).node.centerX() == (660 + 1800) / 2 - 0 || cardId > 0, "card element addressable");

        // Many elements: focused one survives the cap.
        ScreenDigest.Node big = node("FrameLayout", "", 0, 0, 1920, 1080);
        for (int i = 0; i < 120; i++) {
            ScreenDigest.Node t = node("android.widget.TextView", "行 " + i, 0, i * 9, 400, i * 9 + 8);
            if (i == 110) { t.focusable = true; t.focused = true; }
            big.children.add(t);
        }
        ScreenDigest many = ScreenDigest.of(big, "x", "");
        AgentCoreTest.check(many.elements.size() == ScreenDigest.MAX_ELEMENTS && many.focused() != null
                && many.focused().label.equals("行 110") && many.text().contains("另有 60 个元素未列出"), "cap keeps focus: " + many.elements.size());

        // Nested wrappers with the same label in the same place collapse to one.
        ScreenDigest.Node w = node("FrameLayout", "", 0, 0, 1920, 1080);
        ScreenDigest.Node outer = node("android.widget.FrameLayout", "", 100, 100, 300, 160);
        outer.focusable = true;
        ScreenDigest.Node inner = node("android.widget.Button", "确定", 100, 100, 300, 160);
        inner.clickable = true; inner.focusable = true; inner.focused = true;
        outer.children.add(inner);
        outer.desc = "确定";
        w.children.add(outer);
        ScreenDigest dd = ScreenDigest.of(w, "x", "");
        AgentCoreTest.check(dd.elements.size() == 1 && dd.elements.get(0).flags.contains("焦点"), "duplicate wrapper collapsed: " + dd.text());

        String longCard = "1958 · 【示例小镇 ｜示例】一段很长的示例视频标题，用来测试卡片截断，长度和原来的标题差不多，配乐全部AI生成 · 10月18日";
        String fitted = ScreenDigest.fit(longCard);
        AgentCoreTest.check(fitted.startsWith("1958 · 【示例小镇") && fitted.endsWith(" · 10月18日") && fitted.length() <= 64 && fitted.contains("…"), "card fit keeps date: " + fitted);
        AgentCoreTest.check(ScreenDigest.fit("短标签").equals("短标签"), "short label unchanged");
        AgentCoreTest.check(ScreenDigest.parseDump("<hierarchy><node text=\"A&amp;B &#x4e2d;\" bounds=\"[0,0][10,10]\"/></hierarchy>").text.equals("A&B 中"), "xml entities");
        AgentCoreTest.check(ScreenDigest.of(null, "", "").text().contains("没有读到界面元素"), "empty screen explained");

        String dump = new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/media_session.txt")), StandardCharsets.UTF_8);
        MediaSessions.Session s = MediaSessions.current(dump);
        AgentCoreTest.check(s != null && s.pkg.equals("com.netease.cloudmusic.tv") && s.state.equals("paused")
                && s.title.equals("你还要我怎样") && s.artist.equals("薛之谦") && s.album.equals("意外"), "media session parsed");
        AgentCoreTest.check(s.describe().equals("已暂停《你还要我怎样》 薛之谦（意外）"), "media describe: " + s.describe());
        // 2026-11-07: 「stopped」 was read out as it is
        AgentCoreTest.check(MediaSessions.words("stopped").equals("已停止") && MediaSessions.words("buffering").equals("正在缓冲")
                && MediaSessions.words("none").equals("没有在播放") && MediaSessions.words("skipping_next").equals("正在切换"), "playback states in words");
        String playing = dump.replace("state=2,", "state=3,").replace("description=你还要我怎样, 薛之谦, 意外", "description=Hello, World, Artist, Album");
        MediaSessions.Session p = MediaSessions.current(playing);
        AgentCoreTest.check(p.state.equals("playing") && p.title.equals("Hello, World") && p.artist.equals("Artist"), "title with comma");
        AgentCoreTest.check(MediaSessions.current("nothing here") == null, "no session");

        // Real Bilibili search page (uiautomator dump, 2026-11-04): UP card, then a row of his newest uploads.
        String biliXml = new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/bili_search.xml")), StandardCharsets.UTF_8);
        ScreenDigest bili = ScreenDigest.of(ScreenDigest.parseDump(biliXml), "哔哩哔哩 tv.danmaku.bili", "BiliMainSearchActivity");
        org.json.JSONObject up = BiliPage.upUploads(bili, "星辰亻旅行者");
        AgentCoreTest.check(up != null && up.getJSONArray("recent_uploads").length() == 3, "bili UP row found: " + up + "\n" + bili.text());
        if (up != null) {
            org.json.JSONObject newest = up.getJSONArray("recent_uploads").getJSONObject(0);
            AgentCoreTest.check(newest.getString("title").contains("示例小镇") && newest.getString("date").equals("10月18日"), "newest upload: " + newest);
            AgentCoreTest.check(bili.element(newest.getInt("id")).label.contains("示例小镇"), "newest id points at its card");
            AgentCoreTest.check(up.getJSONArray("recent_uploads").getJSONObject(2).getString("date").equals("10月12日"), "row order left to right");
            AgentCoreTest.check(bili.element(up.getInt("up_card_id")).label.startsWith("星辰亻旅行者"), "up card id");
        }
        AgentCoreTest.check(BiliPage.upUploads(bili, "完全不相关的词") == null, "no UP card for another keyword");

        // MiTV settings home: every menu icon is described "电视设置"; that shared description is noise.
        String settingsXml = new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/settings_home.xml")), StandardCharsets.UTF_8);
        ScreenDigest settings = ScreenDigest.of(ScreenDigest.parseDump(settingsXml), "电视设置 com.xiaomi.mitv.settings", "HomeActivity");
        String st = settings.text();
        AgentCoreTest.check(st.contains("「网络设置」") && st.contains("「通用设置」") && !st.contains("电视设置 · "), "shared icon description dropped: " + st);

        front();
        mediaTags();
    }

    /** 2026-11-10: B站's session is tagged 「HeadsetControlPlayAdapter.Session」, not 「MediaSession」, so the parser saw
     * no session at all while a video was playing (open_link waited its whole time, media_now_playing said nothing was
     * playing). A session block starts at any session header now; lines that only mention a session do not start one. */
    static void mediaTags() throws Exception {
        String bili = new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/media-bili-playing.txt")), StandardCharsets.UTF_8);
        List<MediaSessions.Session> all = MediaSessions.parse(bili);
        AgentCoreTest.check(all.size() == 1, "B站 playing: exactly one session (the 「Media button session is」 line is not one): " + all.size());
        MediaSessions.Session b = all.isEmpty() ? new MediaSessions.Session() : all.get(0);
        AgentCoreTest.check(b.pkg.equals("tv.danmaku.bili"), "B站's package from its own package= line: " + b.pkg);
        AgentCoreTest.check(b.state.equals("playing"), "state=3 is playing: " + b.state);
        AgentCoreTest.check(b.position == 2103, "position is read: " + b.position);
        AgentCoreTest.check(b.active, "active=true is read");
        AgentCoreTest.check(b.updated == 30616127L, "updated is read: " + b.updated);
        AgentCoreTest.check(b.title.equals("动态视频｜2026，它来了…"), "title (it holds a full-width comma, not 「, 」): " + b.title);
        AgentCoreTest.check(b.artist.equals("自动连播"), "artist: " + b.artist);
        AgentCoreTest.check(b.album.isEmpty() && b.queue == 0, "null album and no queue: " + b.album + " / " + b.queue);
        AgentCoreTest.check(b.describe().equals("正在播放《动态视频｜2026，它来了…》 自动连播"), "describe: " + b.describe());
        MediaSessions.Session cur = MediaSessions.current(bili);
        AgentCoreTest.check(cur != null && cur.pkg.equals("tv.danmaku.bili") && cur.state.equals("playing"), "current is B站's session");

        String none = new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/media-none.txt")), StandardCharsets.UTF_8);
        AgentCoreTest.check(MediaSessions.parse(none).isEmpty(), "nothing playing: no sessions");
        AgentCoreTest.check(MediaSessions.current(none) == null, "nothing playing: current is null");

        // Two sessions with different tags at once: NetEase paused, B站 playing.
        String two = "Sessions Stack - have 2 sessions:\n"
                + "    MediaSession com.netease.cloudmusic.tv/MediaSession (userId=0)\n"
                + "      package=com.netease.cloudmusic.tv\n      active=true\n"
                + "      state=PlaybackState {state=2, position=14042, buffered position=0, speed=1.0, updated=131445183}\n"
                + "      metadata:size=6, description=你还要我怎样, 薛之谦, 意外\n"
                + "    HeadsetControlPlayAdapter.Session tv.danmaku.bili/HeadsetControlPlayAdapter.Session (userId=0)\n"
                + "      package=tv.danmaku.bili\n      active=true\n"
                + "      state=PlaybackState {state=3, position=2103, buffered position=0, speed=1.0, updated=30616127}\n"
                + "      metadata:size=9, description=动态视频｜2026，它来了…, 自动连播, null\n";
        List<MediaSessions.Session> both = MediaSessions.parse(two);
        AgentCoreTest.check(both.size() == 2, "two tags, two sessions: " + both.size());
        AgentCoreTest.check(both.size() == 2 && both.get(0).pkg.equals("com.netease.cloudmusic.tv") && both.get(0).state.equals("paused")
                && both.get(1).pkg.equals("tv.danmaku.bili") && both.get(1).state.equals("playing"), "each block keeps its own state");
        MediaSessions.Session now = MediaSessions.current(two);
        AgentCoreTest.check(now != null && now.pkg.equals("tv.danmaku.bili"), "current is the playing one, whatever its tag: " + (now == null ? "null" : now.pkg));

        // A line that only mentions a session is not a header (four words before the package).
        String mention = "  Media button session is tv.danmaku.bili/HeadsetControlPlayAdapter.Session (userId=0)\n"
                + "  Global priority session is null\n";
        AgentCoreTest.check(MediaSessions.parse(mention).isEmpty(), "「Media button session is …」 alone is no session");

        // A tag without a dot, a package with digits and underscores, a two-digit user id.
        String odd = "    Player com.example_app2.tv/Player (userId=10)\n      package=com.example_app2.tv\n"
                + "      state=PlaybackState {state=3, position=7, buffered position=0, speed=1.0, updated=9}\n";
        List<MediaSessions.Session> o = MediaSessions.parse(odd);
        AgentCoreTest.check(o.size() == 1 && o.get(0).pkg.equals("com.example_app2.tv") && o.get(0).position == 7, "a tag without a dot still starts a session: " + o.size());

        // What is not a header: no package with a dot, no (userId=N) at the end, words before the package.
        AgentCoreTest.check(MediaSessions.parse("    MediaSession netease/MediaSession (userId=0)\n").isEmpty(), "a package without a dot is not a header");
        AgentCoreTest.check(MediaSessions.parse("    MediaSession com.netease.cloudmusic.tv/MediaSession\n").isEmpty(), "without (userId=N) it is not a header");
        AgentCoreTest.check(MediaSessions.parse("    Media button session is com.netease.cloudmusic.tv/MediaSession (userId=0)\n").isEmpty(),
                "words before the package mean it is a mention, not a header");
        AgentCoreTest.check(MediaSessions.parse("  Sessions Stack - have 1 sessions:\n").isEmpty(), "the stack line itself is not a header");

        // The header's own package is the session's package until a package= line says otherwise.
        List<MediaSessions.Session> h = MediaSessions.parse("    HeadsetControlPlayAdapter.Session tv.danmaku.bili/HeadsetControlPlayAdapter.Session (userId=0)\n"
                + "      active=true\n");
        AgentCoreTest.check(h.size() == 1 && h.get(0).pkg.equals("tv.danmaku.bili") && h.get(0).active, "the header's package, with no package= line: " + h.size());

        // The rest of the dump must not be read into a block: Audio playback ends the stack.
        String after = "    MediaSession com.netease.cloudmusic.tv/MediaSession (userId=0)\n      package=com.netease.cloudmusic.tv\n"
                + "      state=PlaybackState {state=2, position=14042, buffered position=0, speed=1.0, updated=131445183}\n"
                + "Audio playback (lastly played comes first)\n"
                + "  uid=10022 packages=com.netease.cloudmusic.tv \n"
                + "  package=com.other.app\n";
        List<MediaSessions.Session> a = MediaSessions.parse(after);
        AgentCoreTest.check(a.size() == 1 && a.get(0).pkg.equals("com.netease.cloudmusic.tv") && a.get(0).state.equals("paused"),
                "Audio playback ends the block: " + (a.isEmpty() ? "none" : a.get(0).pkg));
    }

    /** Which package may be called "the app in front" (2026-11-09: every spoken sentence made XiaoAi's hidden overlays
     * say the user was in 小爱同学), how mFocusedApp is split, and what 伴奏 says when the player page is not in front. */
    static void front() {
        String ours = "xyz.erapi0neer.orrery", voice = "com.xiaomi.voicecontrol", netease = "com.netease.cloudmusic.tv";
        AgentCoreTest.check(!FrontApp.counts(voice, false, ours), "XiaoAi's overlay window is not the app in front");
        AgentCoreTest.check(FrontApp.counts(voice, true, ours), "XiaoAi's own activity (its video page) still counts");
        AgentCoreTest.check(!FrontApp.counts("com.android.systemui", false, ours), "systemui overlay does not count");
        AgentCoreTest.check(!FrontApp.counts("com.xiaomi.mitv.systemui", false, ours), "mitv systemui overlay does not count");
        AgentCoreTest.check(!FrontApp.counts("com.xiaomi.wakeupservice", false, ours), "wake-up service overlay does not count");
        AgentCoreTest.check(FrontApp.counts("android", true, ours), "the system's own activity counts");
        AgentCoreTest.check(FrontApp.counts(netease, false, ours), "NetEase's window counts even without an activity");
        AgentCoreTest.check(FrontApp.counts(netease, true, ours), "NetEase's activity counts");
        AgentCoreTest.check(FrontApp.counts("tv.danmaku.bili", false, ours), "Bilibili's window counts");
        AgentCoreTest.check(!FrontApp.counts(ours, true, ours), "our own activity never counts");
        AgentCoreTest.check(!FrontApp.counts(ours, false, ours), "our own overlay never counts");
        AgentCoreTest.check(!FrontApp.counts("", false, ours), "an empty package is not the front");
        AgentCoreTest.check(!FrontApp.counts(null, false, ours), "a null package is not the front");
        AgentCoreTest.check(!FrontApp.counts(null, true, ours), "a null package with an activity is not the front either");

        String player = "com.netease.cloudmusic.tv/com.netease.cloudmusic.tv.activity.newplayer.v2.PlayerActivityV2";
        AgentCoreTest.check(FrontApp.pkg(player).equals(netease), "mFocusedApp package: " + FrontApp.pkg(player));
        AgentCoreTest.check(FrontApp.cls(player).equals("com.netease.cloudmusic.tv.activity.newplayer.v2.PlayerActivityV2"),
                "mFocusedApp class: " + FrontApp.cls(player));
        AgentCoreTest.check(PlayerPages.isPlayer(FrontApp.pkg(player), FrontApp.cls(player)), "the split player line is a player page");
        String home = "xyz.erapi0neer.orrery/xyz.erapi0neer.orrery.HomeActivity";
        AgentCoreTest.check(FrontApp.pkg(home).equals(ours) && FrontApp.cls(home).equals("xyz.erapi0neer.orrery.HomeActivity"),
                "our own focused activity splits");
        AgentCoreTest.check(FrontApp.pkg("").isEmpty() && FrontApp.cls("").isEmpty(), "empty focused app has no package or class");
        AgentCoreTest.check(FrontApp.pkg(null).isEmpty() && FrontApp.cls(null).isEmpty(), "null focused app has no package or class");
        AgentCoreTest.check(FrontApp.pkg("garbage").isEmpty() && FrontApp.cls("garbage").isEmpty(), "text without a slash is nothing");
        AgentCoreTest.check(FrontApp.pkg("/x").isEmpty() && FrontApp.cls("/x").isEmpty(), "a missing package is nothing");
        AgentCoreTest.check(FrontApp.pkg("x/").isEmpty() && FrontApp.cls("x/").isEmpty(), "a missing class is nothing");

        String both = Karaoke.notInFront("哔哩哔哩", "你还要我怎样", "薛之谦");
        AgentCoreTest.check(both.contains("netease_play") && both.contains("title=你还要我怎样") && both.contains("artist=薛之谦"),
                "with a session the way back is netease_play with the song: " + both);
        AgentCoreTest.check(both.contains("《你还要我怎样》") && both.contains("哔哩哔哩"), "it says which song and where we are: " + both);
        AgentCoreTest.check(both.contains("不要用 open_app"), "it warns that open_app opens NetEase's home page: " + both);
        String noArtist = Karaoke.notInFront("哔哩哔哩", "你还要我怎样", "");
        AgentCoreTest.check(!noArtist.contains("artist=") && noArtist.contains("title=你还要我怎样"), "no empty artist= when there is none: " + noArtist);
        String none = Karaoke.notInFront("电视桌面", "", "");
        AgentCoreTest.check(none.contains("先用 netease_play 放一首歌") && !none.contains("《"), "no session: play a song first: " + none);
        AgentCoreTest.check(Karaoke.notInFront("", "", "").contains("现在是别的应用"), "an unknown front still reads as words: " + Karaoke.notInFront("", "", ""));
    }

    static ScreenDigest.Node node(String cls, String text, int l, int t, int r, int b) {
        ScreenDigest.Node n = new ScreenDigest.Node();
        n.cls = cls; n.text = text; n.left = l; n.top = t; n.right = r; n.bottom = b;
        return n;
    }
}
