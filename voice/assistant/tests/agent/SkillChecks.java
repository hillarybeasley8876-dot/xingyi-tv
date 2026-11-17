package xyz.erapi0neer.xingyi.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import org.json.*;

/** 应用经验 on demand (contract.json "skills", Skills.java, AgentLoop): which request gets which app's guide, in the
 * first message or in the result of the tool that reaches the app, and never twice in one task. */
final class SkillChecks {
    private SkillChecks() { }

    static void run() throws Exception {
        contractSkills();
        selection();
        calls();
        firstMessage();
        toolResult();
        hintSwitch();
        ownApps();
        karaoke();
        remoteKeys();
        tencentLink();
        biliLogin();
        biliSplash();
        biliFull();
        jiguangPlayer();
        audioPlayers();
        selfRepairWiring();
        RepairsChecks.run();
        ReportedChecks.run();
        UnverifiedChecks.run();
        RealPathChecks.run();
        BoardChecks.run();
        CostChecks.run();
        ResumeChecks.run();
        HandoffChecks.run();
    }

    /**
     * What 云视听极光's own players are doing, read out of {@code dumpsys audio} (2026-11-11 20:03 on the TV): it keeps
     * no media session, but the audio service lists every player with its owner and its state. Only the asked uid's own
     * media lines count — {@code ID:87}, an AudioTrack of uid 1000 that is always {@code started} for a notification, is
     * not the show — and only inside the {@code players:} block: the event log underneath it says {@code state:started}
     * for players that stopped minutes ago.
     */
    static void audioPlayers() throws Exception {
        String ad = fixture("audio-ktcp-ad.txt"), playing = fixture("audio-ktcp-playing.txt"),
                paused = fixture("audio-ktcp-paused.txt"), paused2 = fixture("audio-ktcp-paused-2.txt"),
                idle = fixture("audio-idle.txt");

        check(AudioPlayers.STARTED.equals(AudioPlayers.state(ad, 10021)), "the ad before the episode plays: started");
        check(AudioPlayers.STARTED.equals(AudioPlayers.state(playing, 10021)), "the episode plays: started");
        check(AudioPlayers.PAUSED.equals(AudioPlayers.state(paused, 10021)), "paused: paused");
        check(AudioPlayers.PAUSED.equals(AudioPlayers.state(paused2, 10021)),
                "paused with the app's own SoundPool idle beside it: still paused");
        check(AudioPlayers.NONE.equals(AudioPlayers.state(idle, 10021)), "the app not running: nothing");

        // ID:87 is uid 1000's notification track, started in every one of these dumps, and it is not media
        check(AudioPlayers.NONE.equals(AudioPlayers.state(ad, 1000)), "uid 1000 in the ad dump: nothing");
        check(AudioPlayers.NONE.equals(AudioPlayers.state(playing, 1000)), "uid 1000 while the episode plays: nothing");
        check(AudioPlayers.NONE.equals(AudioPlayers.state(paused, 1000)), "uid 1000 while paused: nothing");
        check(AudioPlayers.NONE.equals(AudioPlayers.state(paused2, 1000)), "uid 1000 in the second paused dump: nothing");
        check(AudioPlayers.NONE.equals(AudioPlayers.state(idle, 1000)), "uid 1000 in the idle dump: nothing");
        check(AudioPlayers.NONE.equals(AudioPlayers.state(playing, 10022)),
                "another app's uid is not answered out of 云视听极光's line");

        check(AudioPlayers.NONE.equals(AudioPlayers.state(null, 10021)), "no dump at all");
        check(AudioPlayers.NONE.equals(AudioPlayers.state("", 10021)), "an empty dump");
        check(AudioPlayers.NONE.equals(AudioPlayers.state("no players here", 10021)), "a dump with no players block");

        // the block itself: one paused and one started media track of the same uid is a player that plays
        check(AudioPlayers.STARTED.equals(AudioPlayers.state(dump(
                track(143, 10021, "paused", "USAGE_MEDIA"), track(151, 10021, "started", "USAGE_MEDIA")), 10021)),
                "one paused and one started media track: started wins");
        check(AudioPlayers.PAUSED.equals(AudioPlayers.state(dump(
                track(143, 10021, "paused", "USAGE_MEDIA"), track(151, 10021, "idle", "USAGE_MEDIA")), 10021)),
                "a paused track beside an idle one: paused");
        check(AudioPlayers.NONE.equals(AudioPlayers.state(dump(
                pool(151, 10021, "started", "USAGE_MEDIA")), 10021)),
                "a started SoundPool is the app's own click sound, not the show");
        check(AudioPlayers.NONE.equals(AudioPlayers.state(dump(
                track(143, 10021, "started", "USAGE_NOTIFICATION")), 10021)),
                "a notification track of the app is not the show");
        check(AudioPlayers.NONE.equals(AudioPlayers.state(dump(
                track(143, 10021, "idle", "USAGE_MEDIA")), 10021)),
                "an idle media track: nothing is playing and nothing is paused");
        check(AudioPlayers.NONE.equals(AudioPlayers.state(dump(
                track(143, 10022, "started", "USAGE_MEDIA")), 10021)),
                "another uid's started track is not this app's");
        check(AudioPlayers.STARTED.equals(AudioPlayers.state(dump(
                track(143, 10021, "started", "USAGE_GAME")), 10021)), "USAGE_GAME counts as media");
        check(AudioPlayers.STARTED.equals(AudioPlayers.state(dump(
                track(143, 10021, "started", "USAGE_UNKNOWN")), 10021)), "USAGE_UNKNOWN counts as media");

        // the event log under the block says state:started for players that stopped minutes ago: never read it
        String log = "  players:\n\nAudio event log: playback activity as reported through PlayerBase\n"
                + "11-11 20:03:17:358 new player piid:143 uid/pid:10021/18067 type:android.media.AudioTrack"
                + " attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC\n"
                + "11-11 20:03:19:062 player piid:143 state:started\n";
        check(AudioPlayers.NONE.equals(AudioPlayers.state(log, 10021)),
                "the event log's own state lines are history, not the players block");

        // a dump comes from a shell: every line may end with a carriage return
        check(AudioPlayers.STARTED.equals(AudioPlayers.state(playing.replace("\n", "\r\n"), 10021)),
                "the same dump with carriage returns: still started");
        String cr = "  players:\r\n  " + track(143, 10021, "paused", "USAGE_MEDIA") + "\r\n\r\n  "
                + track(151, 10021, "started", "USAGE_MEDIA") + "\r\n";
        check(AudioPlayers.PAUSED.equals(AudioPlayers.state(cr, 10021)),
                "a line of nothing but a carriage return ends the players block");

        // the decision the host acts on: 云视听极光's keys are toggles, so the key is only for what the app did not do
        check(PlayerPages.jiguangNeedsKey("pause", AudioPlayers.STARTED), "暂停 while it plays: the key is needed");
        check(!PlayerPages.jiguangNeedsKey("pause", AudioPlayers.PAUSED), "暂停 while it is paused: no key");
        check(!PlayerPages.jiguangNeedsKey("pause", AudioPlayers.NONE), "暂停 with nothing open: no key");
        check(PlayerPages.jiguangNeedsKey("stop", AudioPlayers.STARTED), "停止 while it plays: the key is needed");
        check(!PlayerPages.jiguangNeedsKey("stop", AudioPlayers.PAUSED), "停止 while it is paused: no key");
        check(!PlayerPages.jiguangNeedsKey("stop", AudioPlayers.NONE), "停止 with nothing open: no key");
        check(!PlayerPages.jiguangNeedsKey("play", AudioPlayers.STARTED), "继续 while it plays: no key");
        check(PlayerPages.jiguangNeedsKey("play", AudioPlayers.PAUSED), "继续 while it is paused: the key is needed");
        check(PlayerPages.jiguangNeedsKey("play", AudioPlayers.NONE), "继续 with nothing open: the key is needed");
        check(PlayerPages.jiguangNeedsKey("next", AudioPlayers.STARTED), "next is sent as before");

        check(!PlayerPages.jiguangSettled("pause", AudioPlayers.STARTED), "暂停 is not done while it still plays");
        check(PlayerPages.jiguangSettled("pause", AudioPlayers.PAUSED), "暂停 is done once it is paused");
        check(PlayerPages.jiguangSettled("pause", AudioPlayers.NONE), "暂停 is done when nothing plays");
        check(!PlayerPages.jiguangSettled("stop", AudioPlayers.STARTED), "停止 is not done while it still plays");
        check(PlayerPages.jiguangSettled("stop", AudioPlayers.PAUSED), "停止 is done once it is paused");
        check(PlayerPages.jiguangSettled("stop", AudioPlayers.NONE), "停止 is done when nothing plays");
        check(PlayerPages.jiguangSettled("play", AudioPlayers.STARTED), "继续 is done once it plays");
        check(!PlayerPages.jiguangSettled("play", AudioPlayers.PAUSED), "继续 is not done while it is paused");
        check(!PlayerPages.jiguangSettled("play", AudioPlayers.NONE), "继续 is not done when nothing plays");
        check(PlayerPages.jiguangSettled("next", AudioPlayers.NONE), "next is never waited for");

        // media-seek: what the tool says it jumped, in whole minutes when there are any (2026-11-11 校准)
        check("已快进 1 分钟".equals(PlayerPages.seekSays(60)), "seekSays(60): 已快进 1 分钟");
        check("已快进 30 秒".equals(PlayerPages.seekSays(30)), "seekSays(30): 已快进 30 秒");
        check("已快进 1 分 30 秒".equals(PlayerPages.seekSays(90)), "seekSays(90): 已快进 1 分 30 秒");
        check("已快退 10 秒".equals(PlayerPages.seekSays(-10)), "seekSays(-10): 已快退 10 秒");
        check("已快退 2 分钟".equals(PlayerPages.seekSays(-120)), "seekSays(-120): 已快退 2 分钟");
    }

    private static final String TRACK = "android.media.AudioTrack", POOL = "android.media.SoundPool";

    /** One line of the {@code players:} block, the way the audio service writes it. */
    private static String line(int id, String type, int uid, String state, String usage) {
        return "ID:" + id + " -- type:" + type + " -- u/pid:" + uid + "/18067 -- state:" + state
                + " -- attr:AudioAttributes: usage=" + usage + " content=CONTENT_TYPE_MUSIC flags=0x0 tags= bundle=null";
    }

    private static String track(int id, int uid, String state, String usage) {
        return line(id, TRACK, uid, state, usage);
    }

    private static String pool(int id, int uid, String state, String usage) {
        return line(id, POOL, uid, state, usage);
    }

    /** A whole dump with these player lines in its {@code players:} block, and the block that follows it. */
    private static String dump(String... players) {
        StringBuilder b = new StringBuilder("PlaybackActivityMonitor dump time: 20:03:17\n\n  players:\n");
        for (String p : players) b.append("  ").append(p).append('\n');
        return b.append('\n').append("  ducked players piids:\n").toString();
    }

    /**
     * 云视听极光 has no media session, so 「暂停」 reaches its player only as a key injected into its own window
     * (2026-11-11 00:11 on the TV, probe10: {@code input keyevent 127} paused the episode, 126 resumed it). Which
     * window that is, is the window manager's mFocusedApp: the full-screen TVPlayerActivity or a show's detail page
     * NormalOnePageActivity, which carries a small player. Everything else of that app — and any other app's page of
     * the same name — must not take the key.
     */
    static void jiguangPlayer() throws Exception {
        String pkg = "com.ktcp.video";
        check(PlayerPages.isJiguangPlayer(pkg, "com.ktcp.video.activity.TVPlayerActivity"),
                "the full-screen player in front");
        check(PlayerPages.isJiguangPlayer(pkg, ".activity.TVPlayerActivity"),
                "the class as mFocusedApp prints it when the package is left out");
        check(PlayerPages.isJiguangPlayer(pkg, "TVPlayerActivity"),
                "the class's own name alone is still that player");
        check(PlayerPages.isJiguangPlayer(pkg, "com.ktcp.video.activity.onepage.NormalOnePageActivity"),
                "a show's detail page, whose small player takes the key too");
        check(!PlayerPages.isJiguangPlayer(pkg, "com.ktcp.video.activity.SearchActivity"),
                "its search page plays nothing");
        check(!PlayerPages.isJiguangPlayer(pkg, "com.ktcp.video.activity.OpenJumpAdActivity"),
                "its ad page is not a player");
        check(!PlayerPages.isJiguangPlayer(pkg, "com.ktcp.video.activity.TVPlayerActivityV2"),
                "a class whose name merely starts with the player's is not the player");
        check(!PlayerPages.isJiguangPlayer("tv.danmaku.bili", "com.ktcp.video.activity.TVPlayerActivity"),
                "another app's page of that name is not 云视听极光's player");
        check(!PlayerPages.isJiguangPlayer(null, "com.ktcp.video.activity.TVPlayerActivity"), "no package read");
        check(!PlayerPages.isJiguangPlayer("", "com.ktcp.video.activity.TVPlayerActivity"), "an unreadable package");
        check(!PlayerPages.isJiguangPlayer(pkg, null), "no class read");
        check(!PlayerPages.isJiguangPlayer(pkg, ""), "an unreadable class");
        check(!PlayerPages.isJiguangPlayer(null, null), "nothing read at all");
    }

    /**
     * B站's half-screen player (2026-11-11 00:02 on the TV, probe8): the video opens on its own page with the player in
     * the top half and nothing of it readable, one touch brings the controls up with 「展开全屏」, and a click on that
     * fills the screen — where 「锁定屏幕」 and 「清晰度调节」 stand instead. Both readings are B站's own: another app's
     * screen, a hidden control bar and a comment that says the words are all -1 / false.
     */
    static void biliFull() throws Exception {
        String playing = fixture("bili-player-playing.txt"), tapped = fixture("bili-player-tapped.txt"),
                full = fixture("bili-player-full.txt");

        // the control, only while the controls are up
        check(PlayerPages.biliFullScreenControl(tapped) == 7,
                "「展开全屏」 on the tapped player is [7]: " + PlayerPages.biliFullScreenControl(tapped));
        check(PlayerPages.biliFullScreenControl(playing) == -1,
                "while the video plays in the half-screen player Eyes reads nothing of it: no control to click");
        check(PlayerPages.biliFullScreenControl(full) == -1, "the full-screen player has no 展开全屏 left");
        check(PlayerPages.biliFullScreenControl(tapped.replace("tv.danmaku.bili", "com.ktcp.video")) == -1,
                "the same lines under another app's head line are not B站's player");
        check(PlayerPages.biliFullScreenControl(null) == -1, "no screen text at all");
        check(PlayerPages.biliFullScreenControl("") == -1, "an empty screen has no control");
        String comment = "前台：哔哩哔哩 tv.danmaku.bili（UnitedBizDetailsActivity）\n[4] 文本「怎么展开全屏啊」 @50,61";
        check(PlayerPages.biliFullScreenControl(comment) == -1,
                "a comment that merely contains the words is not the control: " + PlayerPages.biliFullScreenControl(comment));
        String bare = "前台：哔哩哔哩 tv.danmaku.bili（UnitedBizDetailsActivity）\n[7] 选项「展开全屏」 @97,51";
        check(PlayerPages.biliFullScreenControl(bare) == 7, "the control on its own, without the page around it");
        check(PlayerPages.biliFullScreenControl("tv.danmaku.bili 的播放页：点一下展开全屏") == -1,
                "prose is not a digest: no element lines, so there is nothing to click");

        // the player once it fills the screen
        check(PlayerPages.biliIsFullScreen(full), "锁定屏幕 + 清晰度调节 and no 展开全屏: the player fills the screen");
        check(!PlayerPages.biliIsFullScreen(playing), "the half-screen page with no controls up is not full screen");
        check(!PlayerPages.biliIsFullScreen(tapped), "the tapped half-screen player still offers 展开全屏");
        check(!PlayerPages.biliIsFullScreen(full.replace("tv.danmaku.bili", "com.ktcp.video")),
                "another app's screen with 锁定屏幕 is not B站's full-screen player");
        check(!PlayerPages.biliIsFullScreen(null), "no screen text at all");
        check(!PlayerPages.biliIsFullScreen(""), "an empty screen is not full screen");
        // 展开全屏 anywhere on the screen wins: that page is the half-screen one, whatever else it shows
        check(!PlayerPages.biliIsFullScreen(full + "\n[24] 选项「展开全屏」 @97,51"),
                "a screen that still offers 展开全屏 is the half-screen page");
        check(PlayerPages.biliIsFullScreen(without(full, "锁定屏幕")),
                "清晰度调节 alone is enough: 锁定屏幕 hides itself");
        check(PlayerPages.biliIsFullScreen(without(full, "清晰度调节")),
                "锁定屏幕 alone is enough: the quality button is not always shown");
        check(!PlayerPages.biliIsFullScreen(without(without(full, "锁定屏幕"), "清晰度调节")),
                "neither mark: a B站 page that says nothing about the player");
        String prose = "前台：哔哩哔哩 tv.danmaku.bili（UnitedBizDetailsActivity）\n全屏了，可以锁定屏幕";
        check(!PlayerPages.biliIsFullScreen(prose), "a sentence is not an element: never guess from prose");
        // the two readings together are what the host acts on: tap when there is no control, click when there is
        check(PlayerPages.biliFullScreenControl(tapped) > 0 && !PlayerPages.biliIsFullScreen(tapped),
                "the tapped screen is the one to click on, not the one already full");
        check(PlayerPages.biliIsFullScreen(full) && PlayerPages.biliFullScreenControl(full) < 0,
                "the full screen is the one to leave alone");
    }

    /**
     * B站's splash ad (2026-11-10 evening: a full-screen ad with a 「跳过 5」 countdown over a cold start, and again
     * every time B站 comes back from the background). The id of that 跳过 is what the host clicks — and only on the ad
     * itself: B站's home has the same splash_layout seconds earlier, and a video page's comments may say 跳过.
     */
    static void biliSplash() throws Exception {
        String splash = fixture("bili-splash.txt"), home = fixture("bili-home.txt");
        check(PlayerPages.biliSplashSkip(splash) == 35,
                "the splash ad's 「跳过 5」 is [35]: " + PlayerPages.biliSplashSkip(splash));
        check(PlayerPages.biliSplashSkip(home) == -1,
                "B站's home a few seconds earlier has the same splash_layout but no 跳过 to click");
        // the ad's own marks without the countdown: nothing to click (and nothing to guess)
        check(PlayerPages.biliSplashSkip(without(splash, "跳过")) == -1, "splash_layout and 「广告」 alone are not a skip");
        String bare = "前台：哔哩哔哩 tv.danmaku.bili（MainActivityV2）\n[7] 选项「跳过 5」 @95,93";
        check(PlayerPages.biliSplashSkip(bare) == -1,
                "a 跳过 on a B站 page that is not the ad (a comment) is never clicked: " + PlayerPages.biliSplashSkip(bare));
        check(PlayerPages.biliSplashSkip(splash.replace("tv.danmaku.bili", "com.ktcp.video")) == -1,
                "the same ad lines under another app's head line are not B站's");
        String plain = "前台：哔哩哔哩 tv.danmaku.bili（MainActivityV2）\n[1] 图标「splash_layout」 @50,50\n"
                + "[9] 选项「跳过」 @95,93";
        check(PlayerPages.biliSplashSkip(plain) == 9,
                "「跳过」 without a number is the same button: " + PlayerPages.biliSplashSkip(plain));
        String two = "前台：哔哩哔哩 tv.danmaku.bili（MainActivityV2）\n[2] 选项「广告」 @50,50\n[8] 选项「跳过 12」 @95,93";
        check(PlayerPages.biliSplashSkip(two) == 8, "「跳过 12」 counts, and 「广告」 is the ad's own mark");
        String word = "前台：哔哩哔哩 tv.danmaku.bili（MainActivityV2）\n[1] 图标「splash_layout」 @50,50\n"
                + "[4] 文本「跳过广告只需30秒」 @50,60";
        check(PlayerPages.biliSplashSkip(word) == -1, "a label that only starts with 跳过 is a sentence, not the button");
        check(PlayerPages.biliSplashSkip(null) == -1, "no screen text at all");
        check(PlayerPages.biliSplashSkip("") == -1, "an empty screen is not the ad");
        check(PlayerPages.biliSplashSkip("tv.danmaku.bili 的开屏广告：跳过 5") == -1,
                "prose is not a digest: no element lines, so there is nothing to click");
    }

    /** The text without the lines that hold {@code needle}. */
    private static String without(String text, String needle) {
        StringBuilder s = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.contains(needle)) continue;
            if (s.length() > 0) s.append('\n');
            s.append(line);
        }
        return s.toString();
    }

    /** 云视听极光's search link (2026-11-10 10:41 on the TV: the box showed 「%E7%8B%82%」 — the app takes the query
     * text as it is, so the keyword must not be percent-encoded any more). */
    static void tencentLink() {
        check("tenvideo2://?action=9&search_key=狂飙".equals(PlayerPages.tencentSearchLink("狂飙")),
                "狂飙 goes into the link as it is: " + PlayerPages.tencentSearchLink("狂飙"));
        check("tenvideo2://?action=9&search_key=甲乙丙丁".equals(PlayerPages.tencentSearchLink("  甲乙丙丁  ")),
                "the ends are trimmed: " + PlayerPages.tencentSearchLink("  甲乙丙丁  "));
        check("tenvideo2://?action=9&search_key=abcd".equals(PlayerPages.tencentSearchLink("a&b=c#d")),
                "a&b=c#d loses the characters that would break the link: " + PlayerPages.tencentSearchLink("a&b=c#d"));
        check("tenvideo2://?action=9&search_key=ab".equals(PlayerPages.tencentSearchLink("a%b")),
                "% comes off too: it would start an escape the app does not decode");
        check("tenvideo2://?action=9&search_key=ab".equals(PlayerPages.tencentSearchLink("a?b")),
                "? comes off: it would start the query string over");
        check("狂飙".equals(PlayerPages.tencentSearchKey("狂飙")), "the key itself is what the box is checked against");
        // the box holds 10 characters (a 14-character name came back cut on the TV)
        String longName = "一二三四五六七八九十一二";
        check(longName.length() == 12 && "一二三四五六七八九十".equals(PlayerPages.tencentSearchKey(longName)),
                "a 12-character keyword is cut to the 10 the box holds: " + PlayerPages.tencentSearchKey(longName));
        check("1234567890".equals(PlayerPages.tencentSearchKey("12345678901234")), "a 14-character name is cut to 10");
        check(PlayerPages.tencentSearchKey("一二三四五六七八九十").length() == 10, "ten Chinese characters are kept whole");
        check("".equals(PlayerPages.tencentSearchKey("   ")), "nothing but spaces is nothing");
        check("".equals(PlayerPages.tencentSearchKey(null)), "no keyword at all");
        // a character outside the basic plane is one character, never half of one
        String emoji = "😀😀😀😀😀😀"; // six characters, twelve chars
        check(emoji.length() == 12 && "😀😀😀😀😀".equals(PlayerPages.tencentSearchKey(emoji)),
                "a cut never lands inside a character");
    }

    /** B站's own login dialog (2026-11-10 10:45: it covered 历史记录 and the model never closed it). */
    static void biliLogin() {
        check(PlayerPages.isBiliLogin("tv.danmaku.bili", "tv.danmaku.bili.sms.SmsLoginDialogActivityV3"),
                "B站's own SmsLoginDialogActivityV3 is the login dialog");
        check(PlayerPages.isBiliLogin("tv.danmaku.bili", "tv.danmaku.bili/.sms.SmsLoginDialogActivityV3"),
                "and so is the short form dumpsys writes");
        check(!PlayerPages.isBiliLogin("tv.danmaku.bili", "tv.danmaku.bili.MainActivityV2"), "B站's home page is not");
        check(!PlayerPages.isBiliLogin("tv.danmaku.bili", "tv.danmaku.bili.ui.intent.IntentHandlerActivity"),
                "the intent handler is not");
        check(!PlayerPages.isBiliLogin("com.ktcp.video", "com.ktcp.video.LoginDialogActivity"),
                "another app's LoginDialog is not B站's");
        check(!PlayerPages.isBiliLogin("tv.danmaku.bili", ""), "no class read: never guess");
        check(!PlayerPages.isBiliLogin("tv.danmaku.bili", null), "no class at all");
        check(!PlayerPages.isBiliLogin("", "tv.danmaku.bili.sms.SmsLoginDialogActivityV3"), "no package read");
        check(!PlayerPages.isBiliLogin(null, "tv.danmaku.bili.sms.SmsLoginDialogActivityV3"), "no package at all");
        // the player page is what openBiliVideo waits for: the dialog must not be mistaken for it either way round
        check(!PlayerPages.isPlayer("tv.danmaku.bili", "tv.danmaku.bili.sms.SmsLoginDialogActivityV3"),
                "the login dialog is not a player page");
        check(PlayerPages.isPlayer("tv.danmaku.bili", "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"),
                "B站's details activity still is the player page");
    }

    /** The TV's own remote service (port 6095, what Xiaomi's phone remote uses): the key names it answers to, and what
     * counts as a key having landed — 31–58 ms against ~0.85 s for every `input keyevent` (2026-11-10 01:44–01:47). */
    static void remoteKeys() {
        check("up".equals(KeyNames.of(19)), "19 is up");
        check("down".equals(KeyNames.of(20)), "20 is down");
        check("left".equals(KeyNames.of(21)), "21 is left");
        check("right".equals(KeyNames.of(22)), "22 is right");
        check("enter".equals(KeyNames.of(23)), "23 DPAD_CENTER is the service's enter: the OK key");
        check("enter".equals(KeyNames.of(66)), "66 is enter too");
        check("back".equals(KeyNames.of(4)), "4 is back");
        check("home".equals(KeyNames.of(3)), "3 is home");
        check("menu".equals(KeyNames.of(82)), "82 is menu");
        check("volumeup".equals(KeyNames.of(24)), "24 is volumeup");
        check("volumedown".equals(KeyNames.of(25)), "25 is volumedown");
        // no name: these stay on input keyevent (26 POWER's callers read the power state first)
        check(KeyNames.of(26) == null, "26 POWER has no name: it stays on input keyevent");
        check(KeyNames.of(0) == null, "0 has no name");
        check(KeyNames.of(85) == null, "85 play/pause has no name");
        check(KeyNames.of(126) == null, "126 has no name");
        check(KeyNames.of(999) == null, "999 has no name");
        check(KeyNames.of(-1) == null, "-1 has no name");

        // the real reply, measured on the TV 2026-11-10 01:45 (the body comes spread over lines)
        String real = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\n\"status\":0,\n\"msg\":\"success\",\n\"data\":{}}";
        check(KeyNames.ok(real), "the reply the service really sends: " + real);
        check(!KeyNames.ok("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"status\":1,\"msg\":\"failed\"}"),
                "status 1 is not a key that landed");
        check(!KeyNames.ok("HTTP/1.1 404 Not Found\r\n\r\n{\"status\":0,\"msg\":\"success\",\"data\":{}}"),
                "a 404 with a status-0 body is not this key's receipt");
        check(!KeyNames.ok("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n"), "a reply with no body");
        check(!KeyNames.ok("HTTP/1.1 200 OK\r\n\r\n{\"status\":0,"), "broken JSON");
        check(!KeyNames.ok(""), "an empty reply");
        check(!KeyNames.ok(null), "no reply at all");
        check(!KeyNames.ok("{\"status\":0}"), "a bare body with no status line");
        check(!KeyNames.ok("HTTP/1.1 200 OK\r\nContent-Type: application/json"), "headers and no blank line");

        // 伴奏 now brings the playing song's player page back first, so the reject is the last resort
        String back = Karaoke.notInFront("哔哩哔哩", "你还要我怎样", "薛之谦");
        check(back.contains("netease_play"), "the reject still names netease_play: " + back);
        check(back.contains("不要用 open_app"), "and still warns that open_app opens the home page: " + back);
    }

    /** Karaoke.state: what the text beside the focused 随心唱 mic icon says about the state it is in (2026-11-09 17:25
     * on the TV: 「开启随心唱」 is the button that turns it on, so it is OFF — read as ON it answered 「已经是伴奏模式了」). */
    static void karaoke() throws Exception {
        String off = fixture("netease-karaoke-off.txt"), on = fixture("netease-karaoke-on.txt");
        check(Boolean.FALSE.equals(Karaoke.state(off)), "「开启随心唱」 beside the focused mic: it is off");
        check(Boolean.TRUE.equals(Karaoke.state(on)), "「按 上 键调节人声」: it is on");
        // the real dump of that screen (uiautomator), read the way TvTools.read() reads it
        ScreenDigest.Node root = ScreenDigest.parseDump(fixture("netease_player.xml"));
        String dump = ScreenDigest.of(root, "网易云音乐 com.netease.cloudmusic.tv", "PlayerActivityV2").text();
        check(Boolean.FALSE.equals(Karaoke.state(dump)), "the 17:25 dump itself: 开启随心唱 is off: " + dump);
        check(dump.contains("图标 焦点 @94,73") && dump.contains("「开启随心唱」"), "the dump has the icon and its label: " + dump);
        // the label beside it wins over anything else on the screen
        check(Boolean.TRUE.equals(Karaoke.state(on.replace("[6] 文本「04:20」", "[6] 文本「人声 100」"))),
                "a vocal slider line does not confuse it");
        // focus elsewhere: on the control bar (播放列表), nowhere, or the bar hidden
        check(Karaoke.state(off.replace("[3] 图标 焦点 @94,73", "[3] 图标 @94,73")
                .replace("[18] 选项「播放列表」 @94,89", "[18] 选项「播放列表」 焦点 @94,89")) == null,
                "focus on 播放列表 in the control bar: not the mic");
        check(Karaoke.state(off.replace(" 焦点 @94,73", " @94,73")) == null, "no focus anywhere: unknown");
        check(Karaoke.state(off.replace("[3] 图标 焦点 @94,73\n", "").replace("[4] 文本「开启随心唱」 @84,73\n", "")) == null,
                "the control bar is hidden: unknown, never a guess");
        check(Karaoke.state(off.replace("[3] 图标 焦点 @94,73", "[3] 图标 @94,73")
                .replace("[10] 图标 @16,89", "[10] 图标 焦点 @16,73")) == null, "a focused icon on the left is not the mic");
        // the words must be the two real ones, on the icon's own row
        check(Karaoke.state(off.replace("「开启随心唱」", "「关闭随心唱」")) == null, "「关闭随心唱」 was never seen: unknown");
        check(Karaoke.state(off.replace("@84,73", "@84,50")) == null, "the label far from the icon is not its label");
        check(Karaoke.state(off.replace("「开启随心唱」", "「开启随心唱模式」")) != null, "the label may carry more words");
        // another app, another page, nothing at all
        check(Karaoke.state(off.replace("com.netease.cloudmusic.tv（PlayerActivityV2）", "tv.danmaku.bili（SearchActivity）")) == null,
                "another app's screen: unknown");
        check(Karaoke.state(off.replace("PlayerActivityV2", "MainActivity")) == null, "NetEase's home page: unknown");
        check(Karaoke.state("") == null && Karaoke.state(null) == null, "no screen, no state");
        check(Karaoke.state("前台：未知应用\n（没有读到界面元素：可能是视频画面、自绘界面或受保护画面，可以用 screen_look 看图）") == null,
                "a screen with no elements: unknown");
        // the page line on its own, and what the tool says when it cannot read the state
        check(Karaoke.playerPage("前台：网易云音乐 com.netease.cloudmusic.tv（PlayerActivityV2）"), "the player page");
        check(Karaoke.playerPage("前台：网易云音乐 com.netease.cloudmusic.tv"), "the page not named: the caller checked it");
        check(!Karaoke.playerPage("前台：哔哩哔哩 tv.danmaku.bili（PlayerActivity）"), "B站's player page is not NetEase's");
        check(!Karaoke.playerPage("随便一行字"), "a line that is not a screen header");
        check("图标".equals(Karaoke.focus(off)), "focus() names the focused element: " + Karaoke.focus(off));
        check("「播放列表」".equals(Karaoke.focus(off.replace("[3] 图标 焦点 @94,73", "[3] 图标 @94,73")
                .replace("[18] 选项「播放列表」 @94,89", "[18] 选项「播放列表」 焦点 @94,89"))), "focus() names 播放列表");
        check(Karaoke.focus("").isEmpty() && Karaoke.focus(null).isEmpty(), "focus() of nothing is empty");
    }

    private static String fixture(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/" + name)), StandardCharsets.UTF_8);
    }

    /** 「别弹提示了」: set_hints writes the flag the engine reads, and its message is the whole answer. */
    static void hintSwitch() throws Exception {
        JSONObject[] profile = {new JSONObject().put("facts", new JSONArray().put("用户喜欢猫"))};
        MemoryTools.Store store = new MemoryTools.Store() {
            @Override public JSONObject load() { return profile[0]; }
            @Override public void save(JSONObject p) { profile[0] = p; }
        };
        Tool t = HintTools.create(store).get(0);
        check("set_hints".equals(t.name()) && t.gate(new JSONObject(), null).risk == Tool.Gate.Risk.READ, "set_hints runs without a plan");
        JSONObject off = t.run(AgentCoreTest.o("on", false), null);
        check(off.optBoolean("ok") && !profile[0].optBoolean("hints", true), "off is stored");
        check(profile[0].optJSONArray("facts").length() == 1, "and the facts stay");
        check(t.run(AgentCoreTest.o("on", true), null).optString("message").contains("会弹") && profile[0].optBoolean("hints"), "on again");
        AgentCoreTest.Script m = new AgentCoreTest.Script().calls("set_hints", AgentCoreTest.o("on", false)).text("这一轮不该被问到");
        Ledger done = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, t).run(new AgentLoop.Request("别弹提示了", "test"));
        check(m.seen.size() == 1 && done.reply.startsWith("好的，以后亮屏不弹提示了"), "one round, the tool's words: " + done.reply);
    }

    /** own-ui（2026-11-11）：开发者自己的测试应用不上名单，星仪也不去开它。 */
    static void ownApps() {
        check(OwnApps.hidden("xyz.erapi0neer.fluidlab"), "Fluid Lab 是自己的测试应用");
        check(OwnApps.hidden("xyz.erapi0neer.xingyi.voicecheck"), "voicecheck 是自己的测试应用");
        check(!OwnApps.hidden("xyz.erapi0neer.orrery"), "Orrery 是用户的应用，不是测试应用");
        check(!OwnApps.hidden("xyz.erapi0neer.xingyi"), "桥接应用自己不是测试应用（TvTools 本来就跳过它）");
        check(!OwnApps.hidden("com.ktcp.video"), "云视听极光不是测试应用");
        check(!OwnApps.hidden("tv.danmaku.bili"), "B站不是测试应用");
        check(!OwnApps.hidden(null), "null 不是测试应用");
        check(!OwnApps.hidden(""), "空字符串不是测试应用");
    }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static List<Skills.Skill> all() { return AgentCoreTest.contract.skills; }

    private static String names(List<Skills.Skill> skills) {
        StringBuilder s = new StringBuilder();
        for (Skills.Skill k : skills) s.append(s.length() == 0 ? "" : ",").append(k.name);
        return s.toString();
    }

    private static String pick(String said, String foreground, String context) {
        return names(Skills.select(all(), said, foreground, context));
    }

    static void contractSkills() {
        check("哔哩哔哩,网易云音乐,网易云随心唱,电视设置,云视听极光".equals(names(all())), "the contract has the five app guides: " + names(all()));
        check(!AgentCoreTest.contract.system.contains("UP主卡片下面并排的三个视频卡片"), "B站's guide is no longer in the system prompt");
        check(AgentCoreTest.contract.system.contains("「应用经验」"), "the system prompt says where the guides come from");
        for (Skills.Skill s : all()) check(s.text.length() > 50 && !s.text.startsWith("- "), "a guide is a whole text: " + s.name);
    }

    static void selection() {
        check("哔哩哔哩".equals(pick("打开B站搜索星辰亻旅行者", "", "")), "B站 by name");
        check("哔哩哔哩".equals(pick("看看我的频道", "", "")), "我的频道 is B站");
        check("哔哩哔哩".equals(pick("播放星辰亻旅行者的mad", "", "")), "a kind of video, any case");
        check("网易云音乐".equals(pick("网易云播放林俊杰的不为谁而作的歌", "", "")), "NetEase by name");
        check("网易云音乐".equals(pick("放首歌", "", "")), "a song is NetEase");
        check("电视设置".equals(pick("打开WiFi设置", "", "")), "settings by a page");
        check("电视设置".equals(pick("现在连的是哪个wifi", "", "")), "settings by a question about it");
        check("".equals(pick("现在几点", "", "")), "the time needs no guide");
        // 2026-11-08 14:22: 伴奏 / 原唱 is NetEase's 随心唱 (the mic icon), only by those words — opening NetEase does not bring it
        check(pick("把这首歌切成伴奏模式", "", "").contains("网易云随心唱"), "伴奏 brings the 随心唱 guide");
        check(pick("切回原唱", "", "").contains("网易云随心唱"), "原唱 brings the 随心唱 guide");
        check(pick("我要唱跳楼机", "", "").indexOf("随心唱") < 0, "我要唱X alone is a song, not 随心唱");
        check("".equals(pick("今天上海天气怎么样", "", "")), "the weather needs no guide");
        // lead change 2026-11-05 21:00: the app in front alone brings no guide (a question about a person with B站 in
        // front was searched inside B站); a short follow-up still gets the guide of the request before it
        check("".equals(pick("我说的是姚润浩叠纸的那个姚润浩", "哔哩哔哩（tv.danmaku.bili）", "")), "the app in front alone brings no guide");
        check("哔哩哔哩".equals(pick("下一个", "哔哩哔哩（tv.danmaku.bili）", "打开B站搜猫")), "a follow-up gets the guide of the request before");
        check("网易云音乐".equals(pick("再来一首", "", "网易云播放林俊杰")), "a short follow-up is about the request before");
        check(Skills.followUp("继续") && Skills.followUp("再来一个") && !Skills.followUp("打开B站搜索星辰亻旅行者"),
                "follow-ups: 继续 and short sentences");
        check(Skills.select(all(), null, null, null).isEmpty(), "nothing said, nothing picked");
        // 2026-11-06 calibration: 「我说的是想听OPPO OPEN AI这个公司的」 got NetEase's guide by 想听 and played a song
        check("".equals(pick("我说的是想听OPPO OPEN AI这个公司的", "", "")), "想听 a company's news is not a song");
        check("".equals(pick("想听你讲讲林俊杰的故事", "", "")), "想听 a talk is not a song");
        check("云视听极光".equals(pick("我想看狂飙这部电视剧", "", "")), "a TV series is 云视听极光's");
        check("网易云音乐".equals(pick("想听林俊杰的歌", "", "")), "想听 a singer still is");
        check(Skills.parse(null).isEmpty() && Skills.parse(new JSONArray().put("x").put(new JSONObject())).isEmpty(),
                "a missing or broken skills array gives none");
    }

    static void calls() throws Exception {
        check("哔哩哔哩".equals(name(Skills.forCall(all(), "bili_search", AgentCoreTest.o("keyword", "猫")))), "bili_search reaches B站");
        check("网易云音乐".equals(name(Skills.forCall(all(), "netease_play", AgentCoreTest.o("artist", "林俊杰")))), "netease_play reaches NetEase");
        check("网易云音乐".equals(name(Skills.forCall(all(), "netease_playlist", AgentCoreTest.o("kind", "fm")))), "and its playlist connector");
        check("电视设置".equals(name(Skills.forCall(all(), "open_settings", AgentCoreTest.o("page", "wifi")))), "open_settings reaches the settings");
        check("哔哩哔哩".equals(name(Skills.forCall(all(), "open_app", AgentCoreTest.o("app", "哔哩哔哩")))), "open_app by the app's name");
        check("哔哩哔哩".equals(name(Skills.forCall(all(), "open_app", AgentCoreTest.o("app", "tv.danmaku.bili")))), "open_app by package");
        check("哔哩哔哩".equals(name(Skills.forCall(all(), "open_link", AgentCoreTest.o("uri", "bilibili://search?keyword=x")))), "a B站 link");
        check(Skills.forCall(all(), "open_app", AgentCoreTest.o("app", "相册")) == null, "an app without a guide");
        check(Skills.forCall(all(), "screen_read", new JSONObject()) == null, "a read reaches no app");
        check(Skills.forCall(all(), "open_app", null) == null, "no arguments, no guide");
    }

    private static String name(Skills.Skill s) { return s == null ? "" : s.name; }

    private static JSONObject firstUser(AgentCoreTest.Script m) throws JSONException {
        JSONArray sent = m.seen.get(0);
        return new JSONObject(sent.getJSONObject(sent.length() - 1).getString("content"));
    }

    static void firstMessage() throws Exception {
        AgentCoreTest.Script bili = new AgentCoreTest.Script().text("好的");
        AgentCoreTest.loop(bili, new AgentCoreTest.Events(), (w, l) -> true).run(new AgentLoop.Request("B站搜猫", "test"));
        JSONArray guides = firstUser(bili).optJSONArray("应用经验");
        check(guides != null && guides.length() == 1 && guides.getString(0).startsWith("哔哩哔哩"), "a B站 request carries B站's guide");

        AgentCoreTest.Script time = new AgentCoreTest.Script().text("十点");
        AgentCoreTest.loop(time, new AgentCoreTest.Events(), (w, l) -> true).run(new AgentLoop.Request("现在几点", "test"));
        check(!firstUser(time).has("应用经验"), "现在几点 carries none");
        // the guide rides in the user message: each system prompt is the contract's for the request's tools (B站's carry
        // bili_videos since 2026-11-07), so each kind of request keeps one cacheable prefix
        check(time.seen.get(0).getJSONObject(0).getString("content").equals(AgentCoreTest.contract.systemFor(java.util.Collections.<String>emptySet()))
                && bili.seen.get(0).getJSONObject(0).getString("content").equals(AgentCoreTest.contract.systemFor(AgentCoreTest.contract.gatedIn("B站搜猫"))),
                "the system prompt is the contract's either way (cacheable)");
    }

    static void toolResult() throws Exception {
        AgentCoreTest.Fake open = new AgentCoreTest.Fake("open_app", Tool.Gate.act());
        AgentCoreTest.Script m = new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("打开B站看看", "打开", "看"), "open_app", AgentCoreTest.o("app", "哔哩哔哩"))
                .calls("open_app", AgentCoreTest.o("app", "哔哩哔哩"))
                .text("好了");
        AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, open).run(new AgentLoop.Request("随便打开一个", "test"));
        check(!firstUser(m).has("应用经验"), "a request naming no app carries no guide");
        // call ids: c0, c1 in the first reply (plan, open_app), c10 in the second (AgentCoreTest.Script)
        JSONObject first = toolMessage(m, 1, "c1"), second = toolMessage(m, 2, "c10");
        check(first.optString("应用经验").startsWith("哔哩哔哩"), "opening B站 brings its guide in the tool result: " + first);
        check(first.optBoolean("ok") && second.optBoolean("ok") && !second.has("应用经验"), "never twice in one task");

        AgentCoreTest.Fake bili = new AgentCoreTest.Fake("bili_search", Tool.Gate.act());
        AgentCoreTest.Script b = new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("B站搜猫", "搜"), "bili_search", AgentCoreTest.o("keyword", "猫")).text("好了");
        AgentCoreTest.loop(b, new AgentCoreTest.Events(), (w, l) -> true, bili).run(new AgentLoop.Request("B站搜猫", "test"));
        check(!toolMessage(b, 1, "c1").has("应用经验"), "a guide already in the first message is not repeated in a result");
    }

    /** The tool message with this call id, as the model saw it in its request number {@code request}. */
    private static JSONObject toolMessage(AgentCoreTest.Script m, int request, String id) throws JSONException {
        JSONArray sent = m.seen.get(request);
        for (int i = 0; i < sent.length(); i++) {
            JSONObject msg = sent.getJSONObject(i);
            if ("tool".equals(msg.optString("role")) && id.equals(msg.optString("tool_call_id"))) return new JSONObject(msg.getString("content"));
        }
        return new JSONObject();
    }

    // ---- self-repair (briefs/self-repair.md): self_check mends what it finds ------------------------

    private static final String TVTOOLS = "src/xyz/erapi0neer/xingyi/agent/tv/TvTools.java",
            SELF_REPAIR = "src/xyz/erapi0neer/xingyi/agent/tv/SelfRepair.java",
            REPAIRS = "src/xyz/erapi0neer/xingyi/agent/Repairs.java";

    /** The TV side cannot be run here, so what is checked is that it is wired: the four checks and their repairs live
     * in SelfRepair, self_check calls it and takes its eyes/panel/adb lines from it, and Repairs stays pure. */
    static void selfRepairWiring() throws Exception {
        String tv = source(TVTOOLS), sr = source(SELF_REPAIR), rp = source(REPAIRS);
        check(!sr.isEmpty(), "tv/SelfRepair.java exists");
        check(sr.contains("static JSONObject run(TvTools"), "SelfRepair.run(TvTools) is its entry");
        check(tv.contains("SelfRepair.run(this)"), "TvTools hands its own instance to SelfRepair");
        String block = selfCheck(tv);
        check(block.contains("selfRepair()"), "the self_check tool calls it");
        check(block.contains("Repairs.normal(") || tv.contains("Repairs.normal("), "the 正常 sentence comes from Repairs");
        check(block.contains("repair.getString(\"say\")"), "what it says about a mend is SelfRepair's own sentence");
        check(!tv.contains("读屏服务没开"), "the eyes line is SelfRepair's now, not a hand-built one");
        check(!tv.contains("eyes.hud().probe()"), "the panel is probed by SelfRepair, which can also reset it");
        check(!tv.contains("bad.add(\"本机调试连不上"), "the adb line is SelfRepair's now, which can write the switch back");
        check(block.contains("bad.size() + broken + unfixable.size()"), "problems counts what is still broken");
        // a probe keeps its own board (2026-11-16): the shared one let the probe's route line replace the real turn's
        check(block.contains("ModelRouter.detached(context)"), "the model check of self_check is untouched, on its own board");
        check(block.contains("Weather.hintFor("), "the weather check of self_check is untouched");

        check(sr.contains("LocalAdb::overlayHidden"), "小爱的卡片 is read through LocalAdb::overlayHidden");
        check(sr.contains("a.xiaoaiOverlays(false)"), "and switched off again the way the bridge does at boot");
        check(sr.contains("a.rebindAccessibility(component)"), "读屏 is rebound by its own component");
        check(sr.contains("flattenToShortString()"), "the component is 星仪's own Eyes");
        check(sr.contains("Settings.Global.ADB_ENABLED, 1"), "本机调试 is written back on");
        check(sr.contains("WRITE_SECURE_SETTINGS"), "and only when the bridge holds the permission for it");
        check(sr.contains("Repairs.plan("), "the steps come from the table, in its order");
        check(sr.contains("Repairs.stepSays(") && sr.contains("Repairs.cannot("), "the words come from Repairs");
        check(sr.contains("Repairs.normal(") && sr.contains("Repairs.report("), "both sentences come from Repairs");
        check(sr.contains("Repairs.EYES") && sr.contains("Repairs.PANEL"), "读屏 and 面板 are checked by key");
        check(sr.contains("Repairs.ADB") && sr.contains("Repairs.XIAOI"), "本机调试 and 小爱的卡片 are checked by key");
        check(sr.contains("catch (Exception e)") && sr.contains("t.lost("), "a check that throws is said, not thrown");
        check(sr.contains("hud.dispose()"), "the panel's window is taken down before it is put up again");
        check(!rp.contains("import android."), "Repairs stays pure: no android.* in it");
        check(rp.contains("public static List<String> plan(String symptom)"), "the repair table is the core's");
    }

    /** The self_check tool as it is registered: from its own {@code new Base} up to the next tool. */
    private static String selfCheck(String tv) {
        int at = tv.indexOf("new Base(\"self_check\")");
        if (at < 0) return "";
        int end = tv.indexOf("tools.add(", at + 20);
        return end < 0 ? tv.substring(at) : tv.substring(at, end);
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
