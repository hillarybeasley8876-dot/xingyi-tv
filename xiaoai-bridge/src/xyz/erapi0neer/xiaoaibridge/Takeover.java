package xyz.erapi0neer.xiaoaibridge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import xyz.erapi0neer.xingyi.MusicIntent;
import xyz.erapi0neer.xingyi.MusicResolver;

/** Decides whether a recognized utterance is a music request to take away from XiaoAi, and plays it on NetEase. */
final class Takeover {
    private final Context context;
    private final BridgeLog log;
    private final MusicResolver.Search search;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService verifier = Executors.newSingleThreadExecutor();
    /** A newer request stops the verification of an older one. */
    private final AtomicInteger generation = new AtomicInteger();

    Takeover(Context context, BridgeLog log, MusicResolver.Search search) {
        this.context = context.getApplicationContext();
        this.log = log;
        this.search = search;
    }

    /** XiaoAi's own cloud understanding of the same request, read from its log a moment later. */
    interface Hint { boolean xiaoaiSaysMusic(long waitMs); }

    /** Hands the request to 星仪 instead (why goes to the log). */
    interface ToAgent { void give(String why); }

    /**
     * A song described instead of named: a catalog search on the description plays nothing ("林俊杰目前收听人数最高的单曲",
     * "林俊杰最新发表的一个单曲", 2026-11-05). 星仪 can look the title up first.
     */
    private static final java.util.regex.Pattern DESCRIBED = java.util.regex.Pattern.compile(
            "最新|最近|新歌|新出|刚出|最火|最热|热门|最红|收听|播放量|播放最多|听的人|人数最|最高|最多|排行|热歌榜|新歌榜|飙升榜|榜单|榜首|排名|第一首|代表作|成名曲|主题曲|片尾曲|插曲");

    static boolean described(String text) { return text != null && DESCRIBED.matcher(text).find(); }

    /** The request this generation took, so a later failure can still hand it to 星仪. */
    private volatile ToAgent toAgent;
    private volatile int toAgentGen;

    /**
     * XiaoAi answered "I can't": keep it quiet for a few seconds, let the model answer, speak the answer with
     * XiaoAi's own voice and show it. Runs on the answers thread.
     */
    void answerWithModel(XiaoaiLog.Dialog d, String refusal, Llm llm) {
        d.silenceUntil = System.currentTimeMillis() + 6000;
        Xiaoai.stopSpeaking(context);
        long t0 = SystemClock.elapsedRealtime();
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        main.postDelayed(() -> {
            if (done.get()) return;
            d.silenceUntil = 0;
            Xiaoai.say(context, "正在思考中");
        }, 2000);
        String answer = null, error = null;
        try {
            answer = llm.ask(d.text, 25000);
        } catch (IOException | RuntimeException e) {
            error = e.getClass().getSimpleName() + " " + e.getMessage();
        }
        done.set(true);
        d.silenceUntil = 0;
        long ms = SystemClock.elapsedRealtime() - t0;
        if (answer == null || answer.isEmpty()) {
            log.add("模型没答出来(" + ms + "ms " + error + ") ← " + d.text);
            Xiaoai.say(context, "我这边也没想出来，换个问法试试吧");
            return;
        }
        log.add("模型接手 " + ms + "ms ← " + d.text + " · 小爱:" + refusal + " · 答:" + answer);
        Xiaoai.say(context, answer);
        String shown = answer;
        main.post(() -> Toast.makeText(context, shown, Toast.LENGTH_LONG).show());
    }

    // ---------------- which refusals are operations; requests that name 星仪 ----------------

    /** "打开…/调…/帮我…" — requests that need something done rather than said. */
    private static final java.util.regex.Pattern ACTION = java.util.regex.Pattern.compile(
            "打开|关闭|关掉|启动|退出|切换|切到|调到|调成|调高|调低|调大|调小|设置|设成|设为|定时|休眠|关机|返回|回到|"
            + "搜索|搜一下|找一下|帮我|给我|播放|放一下|我要看|我想看|静音|投屏|截图|截屏|屏幕上|看一下屏幕|安装|卸载|下载|发给|提醒我|添加|删除|收藏");
    static boolean isAction(String text) { return text != null && ACTION.matcher(text).find(); }

    static boolean namesXingyi(String text) { return Addressing.namesXingyi(text); }

    /** A quick command whose effect does not depend on whether XiaoAi already did the same (labels are not needed: 打开X
     * needs them and so waits like any other request). */
    static boolean absoluteQuick(String text) {
        xyz.erapi0neer.xingyi.agent.QuickCommand q = xyz.erapi0neer.xingyi.agent.QuickCommand.match(text, java.util.Collections.<String>emptySet());
        if (q == null) return false;
        String direction = q.args.optString("direction"), action = q.args.optString("action");
        if ("adjust_volume".equals(q.tool)) return !"up".equals(direction) && !"down".equals(direction);
        if ("media_control".equals(q.tool)) return !"next".equals(action) && !"previous".equals(action);
        if ("press_keys".equals(q.tool)) return !q.args.toString().contains("back");
        // the power key: XiaoAi may be turning the screen on or off for the same sentence, and a second press undoes
        // it — wait for what XiaoAi did (2026-11-06: 关电视 became a quick command)
        if ("screen_off".equals(q.tool) || "wake_screen".equals(q.tool)) return false;
        // the quick answers only read (the clock, the calendar, the weather, what is playing): nothing to wait for
        return true;
    }

    /** XiaoAi instruction names that say what it did for a request, without its protocol traffic. */
    static boolean meaningful(String name) {
        return !(name.startsWith("Nlp.") || name.startsWith("Dialog.") || name.startsWith("System.Heartbeat")
                || name.startsWith("SpeechRecognizer.") || name.startsWith("SpeechSynthesizer.") || name.startsWith("General.")
                || name.equals("Template.SetDisplayProperty") || name.equals("Template.Query"));
    }

    /** XiaoAi started audio of its own for the request (news, a story, radio): 「我想知道最近有什么新动向」 played news
     * while 星仪 answered (2026-11-05 22:57). */
    static boolean ownAudio(Set<String> did) { return did.contains("AudioPlayer.Play"); }

    static Set<String> xiaoaiDid(List<String> names) {
        Set<String> did = new LinkedHashSet<>();
        for (String n : names) if (meaningful(n)) did.add(n);
        return did;
    }

    /** What the hand-over tells 星仪 about XiaoAi's own handling (contract: environment.note "小爱自己的处理"). */
    static String xiaoaiNote(Set<String> did, List<String> answers) {
        StringBuilder sb = new StringBuilder("小爱自己的处理：");
        if (did.isEmpty()) sb.append("没有动作");
        else {
            sb.append("动作 ");
            int i = 0;
            for (String n : did) sb.append(i++ == 0 ? "" : "、").append(n);
        }
        String said = answers.isEmpty() ? "" : answers.get(answers.size() - 1);
        if (!said.isEmpty()) sb.append("；它本来要说「").append(said.length() > 120 ? said.substring(0, 120) + "…" : said).append("」");
        return sb.toString();
    }

    /** XiaoAi cannot work the Bilibili app: it answers B站 requests with its own video search page ("为您推荐以下相关内容"),
     * so they go to 星仪 straight away instead of after XiaoAi's refusal (2026-11-05). */
    static boolean namesBili(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(java.util.Locale.ROOT);
        return t.contains("b站") || t.contains("哔哩") || t.contains("bilibili");
    }

    /** Runs when the song path decides to take the request (the service opens XiaoAi's silence window). */
    private Runnable onTake = () -> { };

    boolean handle(String text, String source, Hint hint, Runnable taking) { return handle(text, source, hint, taking, null); }

    /** Called on the bridge worker thread. Returns true when the bridge took the request. agent: where a request
     * the music path cannot finish goes (null: XiaoAi says the failure). */
    boolean handle(String text, String source, Hint hint, Runnable taking, ToAgent agent) {
        onTake = taking == null ? () -> { } : taking;
        MusicIntent it = MusicIntent.parse(text);
        if (it == null) return false;
        // Without "网易云" in the sentence, only requests XiaoAi itself classifies as music are taken
        // ("播放CCTV1" must stay a channel even if some song has that title).
        if (!it.netease && it.kind != MusicIntent.Kind.OPEN_APP && it.kind != MusicIntent.Kind.PAUSE
                && it.kind != MusicIntent.Kind.RESUME && it.kind != MusicIntent.Kind.NEXT && it.kind != MusicIntent.Kind.PREVIOUS
                && !hint.xiaoaiSaysMusic(2500)) {
            log.add(source + " 小爱没有当成音乐请求，留给小爱 ← " + text);
            return false;
        }
        if (!NeteasePlayer.installed(context)) {
            log.add(source + " 网易云未安装，不接管 ← " + text);
            return false;
        }
        int gen = generation.incrementAndGet();
        toAgent = agent;
        toAgentGen = gen;
        if (agent != null && (it.kind == MusicIntent.Kind.SONG || it.kind == MusicIntent.Kind.ARTIST) && described(text)) {
            silenceXiaoai();
            log.add(source + " 点歌只给了描述，交给星仪先查是哪首 ← " + text);
            agent.give("点歌要先查是哪首");
            return true;
        }
        switch (it.kind) {
            case PAUSE: case RESUME: case NEXT: case PREVIOUS:
                if (!it.netease) return false;
                silenceXiaoai();
                NeteasePlayer.mediaKey(context, keyFor(it.kind));
                log.add(source + " 控制 " + it.kind + " ← " + text);
                return true;
            case OPEN_APP:
                return false;
            case FAVORITES: case PRIVATE_FM: case DAILY:
                // the content alone ("我喜欢的音乐", "私人FM", "每日推荐") already says music
                silenceXiaoai();
                String what = it.kind == MusicIntent.Kind.FAVORITES ? "我喜欢的音乐" : it.kind == MusicIntent.Kind.PRIVATE_FM ? "私人 FM" : "每日推荐";
                rememberBefore(gen);
                if (it.kind == MusicIntent.Kind.FAVORITES) NeteasePlayer.playFavorites(context);
                else if (it.kind == MusicIntent.Kind.PRIVATE_FM) NeteasePlayer.playPrivateFm(context);
                else NeteasePlayer.openDaily(context);
                log.add(source + " 播放" + what + " ← " + text);
                if (it.kind != MusicIntent.Kind.DAILY) verifyAny(gen, what, it.content, text);
                return true;
            case ARTIST:
                return artist(it, text, source, gen);
            default:
                return song(it, text, source, gen);
        }
    }

    private boolean song(MusicIntent it, String text, String source, int gen) {
        MusicResolver.Pick pick = null;
        String why = "";
        try {
            pick = MusicResolver.resolve(it.content, it.splits, it.wantsVersion, search);
        } catch (IOException e) {
            why = " · 曲库查询失败 " + e.getMessage();
        }
        if (pick == null || !pick.confident) {
            String candidate = pick == null ? "" : " · 候选 " + pick.id + " " + pick.artist + "《" + pick.title + "》";
            // Without "网易云" in the sentence an unsure match stays with XiaoAi.
            if (!it.netease) {
                log.add(source + " 曲库没有把握，留给小爱 ← " + text + candidate + why);
                return false;
            }
            silenceXiaoai();
            String keywords = keywords(it);
            rememberBefore(gen);
            NeteasePlayer.playKeywords(context, keywords);
            log.add(source + " 关键词点歌「" + keywords + "」 ← " + text + candidate + why);
            verifyAny(gen, "「" + keywords + "」", keywords, text);
            return true;
        }
        silenceXiaoai();
        rememberBefore(gen);
        NeteasePlayer.playSong(context, pick.id);
        log.add(source + " 点歌 " + pick.id + " " + pick.artist + "《" + pick.title + "》 score=" + pick.score + " ← " + text);
        verifySong(gen, pick, keywords(it), text);
        return true;
    }

    private boolean artist(MusicIntent it, String text, String source, int gen) {
        // "播放不为谁而作的歌" also reads as "songs by 不为谁而作": a catalog song with exactly that title wins.
        try {
            List<String[]> whole = new ArrayList<>();
            whole.add(new String[]{"", it.content});
            MusicResolver.Pick p = MusicResolver.resolve(it.content, whole, false, search);
            if (p != null && p.confident && SongArrival.sameTitle(p.title, it.content)) {
                silenceXiaoai();
                rememberBefore(gen);
                NeteasePlayer.playSong(context, p.id);
                log.add(source + " 点歌(按歌名) " + p.id + " " + p.artist + "《" + p.title + "》 ← " + text);
                verifySong(gen, p, it.content, text);
                return true;
            }
        } catch (IOException ignored) {
            // the artist route below needs no catalog lookup
        }
        silenceXiaoai();
        rememberBefore(gen);
        NeteasePlayer.playKeywords(context, it.artist);
        log.add(source + " 播放歌手「" + it.artist + "」 ← " + text);
        verifyAny(gen, it.artist + "的歌", it.artist, text);
        return true;
    }

    private static String keywords(MusicIntent it) {
        for (String[] s : it.splits) if (!s[0].isEmpty()) return s[0] + " " + s[1];
        return it.content;
    }

    private static int keyFor(MusicIntent.Kind kind) {
        switch (kind) {
            case PAUSE: return KeyEvent.KEYCODE_MEDIA_PAUSE;
            case RESUME: return KeyEvent.KEYCODE_MEDIA_PLAY;
            case NEXT: return KeyEvent.KEYCODE_MEDIA_NEXT;
            default: return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        }
    }

    /** XiaoAi keeps answering in parallel: stop it now, and the service stops its reply the moment it starts. */
    private void silenceXiaoai() {
        onTake.run();
        Xiaoai.stopSpeaking(context);
    }

    /** What NetEase was playing when this generation's request was taken (null: nothing was there). A cold NetEase
     * start resumes that song first, and it is never the request arriving (2026-11-08). */
    private volatile NowPlaying before;
    private volatile int beforeGen;

    /** Reads it once, before this generation's play intent. */
    private void rememberBefore(int gen) {
        before = NeteasePlayer.nowPlaying();
        beforeGen = gen;
    }

    /** The before-song of this generation; null once a newer request has taken over. */
    private NowPlaying beforeOf(int gen) { return beforeGen == gen ? before : null; }

    private void verifySong(int gen, MusicResolver.Pick pick, String intentKeywords, String text) {
        final String fallback = SongArrival.fallbackKeywords(pick.artist, pick.title, intentKeywords);
        verifier.execute(() -> {
            // A cold NetEase start resumes its own song first and switches a moment later: 9 s was not enough
            // (2026-11-08 14:21 the right song was still on its way when the bridge fell back to a search).
            NowPlaying np = waitFor(gen, 14000, pick.title, intentKeywords);
            if (arrived(gen, np, pick.title, intentKeywords)) { done(np, text, false); return; }
            if (generation.get() != gen) return;
            // Something else (XiaoAi's own player, a dialog) took over: ask once more, then let NetEase search.
            NeteasePlayer.playSong(context, pick.id);
            np = waitFor(gen, 8000, pick.title, intentKeywords);
            if (arrived(gen, np, pick.title, intentKeywords)) { done(np, text, false); return; }
            if (generation.get() != gen) return;
            log.add("  原版没有播放(" + np + ")，改用网易云搜索「" + fallback + "」");
            NeteasePlayer.playKeywords(context, fallback);
            np = waitFor(gen, 9000, null, fallback);
            if (arrived(gen, np, null, fallback)) done(np, text, !SongArrival.sameTitle(np.title, pick.title));
            else fail(gen, "网易云没能播放《" + pick.title + "》");
        });
    }

    private void verifyAny(int gen, String what, String keywords, String text) {
        verifier.execute(() -> {
            NowPlaying np = waitFor(gen, 12000, null, keywords);
            if (arrived(gen, np, null, keywords)) done(np, text, false);
            else fail(gen, "网易云没能播放" + what);
        });
    }

    /** What NetEase reports now is the request arriving (never the song that was already playing). */
    private boolean arrived(int gen, NowPlaying np, String wanted, String keywords) {
        return generation.get() == gen && SongArrival.arrived(beforeOf(gen), np, wanted, keywords);
    }

    /** Polls NetEase's media session until the request arrives (a wanted title, or anything new without one). */
    private NowPlaying waitFor(int gen, long ms, String wanted, String keywords) {
        long end = SystemClock.elapsedRealtime() + ms;
        NowPlaying last = null;
        while (SystemClock.elapsedRealtime() < end && generation.get() == gen) {
            last = NeteasePlayer.nowPlaying();
            // the song NetEase had before the request is not a failure while it waits: the new one is on its way
            if (SongArrival.arrived(beforeOf(gen), last, wanted, keywords)) return last;
            SystemClock.sleep(700);
        }
        return last;
    }

    private void done(NowPlaying np, String text, boolean otherVersion) {
        log.add("  ✓ 网易云正在播放 " + np.artist + "《" + np.title + "》" + (otherVersion ? "（不是原来那版）" : ""));
        // this path plays songs itself and never reaches 星仪's loop: its panel shows the result (the toast only when
        // there is no panel to show it on), and its habit memory is told what the user listens to
        xyz.erapi0neer.xingyi.agent.tv.AgentEngine engine = xyz.erapi0neer.xingyi.agent.tv.AgentEngine.get(context);
        if (!engine.songAnswered(text, "网易云正在播放" + np.artist + "《" + np.title + "》" + (otherVersion ? "（不是原来那版）" : "")))
            main.post(() -> Toast.makeText(context, "正在播放 " + np.artist + "《" + np.title + "》", Toast.LENGTH_LONG).show());
        engine.habit("artist", np.artist);
        engine.habit("song", np.title);
        // and its 30-day history: 「昨天那首歌再放一遍」 — with what the user actually said, not words made up from
        // the song (the model reads those lines back as recent_turns)
        engine.played(text, np.artist, np.title);
    }

    private void fail(int gen, String message) {
        if (generation.get() != gen) return;
        log.add("  ✗ " + message);
        ToAgent agent = toAgent;
        if (agent != null && toAgentGen == gen) {
            agent.give("网易云没找到，交给星仪再试");
            return;
        }
        Xiaoai.say(context, message);
    }
}
