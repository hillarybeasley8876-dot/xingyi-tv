package xyz.erapi0neer.xingyi.agent;

/** Which foreground page means "the video is starting here", so the host can wait for playback instead of making the
 * model ask again. Seen on this TV 2026-11-04: Bilibili's player is
 * com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity (and VideoDetailsActivity), NetEase's is *Player*. */
public final class PlayerPages {
    public static final String BILI = "tv.danmaku.bili", NETEASE = "com.netease.cloudmusic.tv",
            JIGUANG = "com.ktcp.video";
    /** How many characters 云视听极光's search box holds (2026-11-10 10:41: a longer keyword came back cut). */
    private static final int TENCENT_BOX = 10;

    private PlayerPages() { }

    public static boolean isPlayer(String pkg, String activity) {
        if (pkg == null || activity == null || activity.isEmpty()) return false;
        String name = simple(activity);
        if (BILI.equals(pkg)) return name.contains("DetailsActivity") || name.contains("PlayerActivity");
        if (NETEASE.equals(pkg)) return name.contains("Player");
        return false;
    }

    /**
     * 云视听极光's own players, the two pages whose window takes a media key (2026-11-11 00:11 on the TV): the
     * full-screen {@code TVPlayerActivity} and a show's detail page {@code NormalOnePageActivity}, which carries a small
     * player. Nothing else of that app plays anything — its search and its ads are not players — and another app's
     * page of the same name is not 云视听极光's, so both the package and the class's own name are required.
     */
    public static boolean isJiguangPlayer(String pkg, String cls) {
        if (!JIGUANG.equals(pkg) || cls == null || cls.isEmpty()) return false;
        String name = simple(cls);
        return TV_PLAYER.equals(name) || ONE_PAGE.equals(name);
    }

    /**
     * 云视听极光's media keys are toggles, and its own voice agent (com.ktcp.tvagent) acts on the sentence about a second
     * and a half after it was said (2026-11-11 20:03 on the TV: 「暂停」 to XiaoAi paused it at 18.898, 星仪's key 164 ms
     * later started it again; 「继续播放」 then paused it). So the key is only for what the app did not do itself:
     * 暂停/停止 need it while it plays, 继续 needs it unless it plays. {@code state} is {@link AudioPlayers#state}.
     */
    public static boolean jiguangNeedsKey(String action, String state) {
        if ("pause".equals(action) || "stop".equals(action)) return AudioPlayers.STARTED.equals(state);
        if ("play".equals(action)) return !AudioPlayers.STARTED.equals(state);
        return true;
    }

    /** Whether the app is where the request wants it — the same reading, asked the other way round. */
    public static boolean jiguangSettled(String action, String state) {
        if ("pause".equals(action) || "stop".equals(action)) return !AudioPlayers.STARTED.equals(state);
        if ("play".equals(action)) return AudioPlayers.STARTED.equals(state);
        return true;
    }

    /**
     * What a seek says it did, in whole minutes when there are any: 「已快进 1 分钟」「已快进 30 秒」「已快进 1 分 30 秒」.
     * The sign picks the direction, so the tool's sentence and the amount the quick command asked for can never drift.
     */
    public static String seekSays(int seconds) {
        String said = seconds < 0 ? "已快退 " : "已快进 ";
        int n = Math.abs(seconds), min = n / 60, sec = n % 60;
        if (min == 0) return said + sec + " 秒";
        if (sec == 0) return said + min + " 分钟";
        return said + min + " 分 " + sec + " 秒";
    }

    /** The two class names of 云视听极光's players, without their package. */
    private static final String TV_PLAYER = "TVPlayerActivity", ONE_PAGE = "NormalOnePageActivity";

    /** The class name without its package, so a match is on the page's own name and not on its path. */
    private static String simple(String activity) {
        int dot = activity.lastIndexOf('.');
        return dot >= 0 ? activity.substring(dot + 1) : activity;
    }

    /**
     * B站 sometimes drops its own 「登录注册解锁更多精彩内容」 modal on top of the page it was just asked for (2026-11-10
     * 10:45: {@code bilibili://history} opened 历史记录 behind {@code .sms.SmsLoginDialogActivityV3}, and the model spent
     * 90 s reading it instead of closing it). Only B站's own, and only a dialog activity — a page is never a dialog.
     */
    public static boolean isBiliLogin(String pkg, String cls) {
        if (!BILI.equals(pkg) || cls == null || cls.isEmpty()) return false;
        return simple(cls).contains("LoginDialog");
    }

    /**
     * The keyword as 云视听极光 wants it in {@code tenvideo2://?action=9&search_key=}: its SearchActivity takes the query
     * text as it is, without percent-decoding it (2026-11-10 10:41: 「狂飙」 arrived as 「%E7%8B%82%」 and the search
     * wandered for two minutes). So no {@code Uri.encode} here — only the characters that would break the link's own
     * query string come off, and what is left is cut to what the box holds.
     */
    public static String tencentSearchKey(String keyword) {
        String asked = keyword == null ? "" : keyword.trim();
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < asked.length(); i++) {
            char c = asked.charAt(i);
            if (c != '&' && c != '#' && c != '?' && c != '=' && c != '%') clean.append(c);
        }
        String value = clean.toString().trim();
        if (value.length() <= TENCENT_BOX) return value;
        // never cut a character in half: a lone surrogate would reach the app as a replacement character
        int end = Character.isHighSurrogate(value.charAt(TENCENT_BOX - 1)) ? TENCENT_BOX - 1 : TENCENT_BOX;
        return value.substring(0, end);
    }

    /** The whole link 云视听极光's search is opened with. */
    public static String tencentSearchLink(String keyword) {
        return "tenvideo2://?action=9&search_key=" + tencentSearchKey(keyword);
    }

    /**
     * B站's splash ad covers a cold start, and comes back every time B站 returns from the background (2026-11-10 evening:
     * 「跳过 5」 counting down over the home). Nothing behind it can be read until it is skipped, and its countdown is
     * not worth waiting out, so the id of that 「跳过」 is what the host clicks. -1 when this is not that ad: B站's own
     * home, another app, a video page whose comments say 跳过 — the ad's own marks (splash_layout, 「广告」) are required,
     * so a 跳过 in any other screen is never taken for it.
     */
    public static int biliSplashSkip(String screenDigestText) {
        if (screenDigestText == null || screenDigestText.isEmpty()) return -1;
        String[] lines = screenDigestText.split("\n");
        if (lines.length == 0 || !lines[0].contains(BILI)) return -1;
        int skip = -1;
        boolean ad = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("splash_layout")) { ad = true; continue; }
            int id = id(line);
            String label = id > 0 ? label(line) : null;
            if (label == null) continue;
            if ("广告".equals(label)) ad = true;
            else if (skip < 0 && isSkip(label)) skip = id;
        }
        return ad ? skip : -1;
    }

    /**
     * B站 on this TV is the phone app: a video opens on its own page with the player in the top half of the screen and
     * the description, a shop card and the comments below (2026-11-11 00:02, probe8). The controls only exist while they
     * are up — one touch on the player brings them up for a few seconds, and 「展开全屏」 is then the control that fills
     * the screen. -1 when this is not that control: another app, a screen whose controls are hidden, the full-screen
     * player itself (it has no 展开全屏 any more), or a comment that merely says the words — the label must be exactly
     * 「展开全屏」, and only on a B站 screen.
     */
    public static int biliFullScreenControl(String screenDigestText) {
        if (screenDigestText == null || screenDigestText.isEmpty()) return -1;
        String[] lines = screenDigestText.split("\n");
        if (lines.length == 0 || !lines[0].contains(BILI)) return -1;
        for (int i = 1; i < lines.length; i++) {
            int id = id(lines[i]);
            if (id > 0 && FULL_SCREEN.equals(label(lines[i]))) return id;
        }
        return -1;
    }

    /**
     * B站's player once it fills the screen, in the same activity as the half-screen page: its own controls are
     * 「锁定屏幕」 and 「清晰度调节」, and 「展开全屏」 is gone (2026-11-11 00:02 on the TV, bili-player-full.txt). Both marks
     * are required to be B站's — another app's screen that happens to carry them is not this player — and a screen that
     * still offers 展开全屏 is the half-screen page, never the full one.
     */
    public static boolean biliIsFullScreen(String screenDigestText) {
        if (screenDigestText == null || screenDigestText.isEmpty()) return false;
        String[] lines = screenDigestText.split("\n");
        if (lines.length == 0 || !lines[0].contains(BILI)) return false;
        boolean full = false;
        for (int i = 1; i < lines.length; i++) {
            int id = id(lines[i]);
            if (id <= 0) continue;
            String label = label(lines[i]);
            if (FULL_SCREEN.equals(label)) return false;
            if (LOCK.equals(label) || QUALITY.equals(label)) full = true;
        }
        return full;
    }

    /** B站's own words for the control that fills the screen, and the two that only the full-screen player has. */
    private static final String FULL_SCREEN = "展开全屏", LOCK = "锁定屏幕", QUALITY = "清晰度调节";

    /** 「跳过」 or 「跳过 N」 and nothing else: a comment that says 跳过广告 is not the countdown. */
    private static boolean isSkip(String label) {
        String s = label.trim();
        if (!s.startsWith("跳过")) return false;
        String rest = s.substring("跳过".length()).trim();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** The number an element line starts with, or -1 when the line is not one. */
    private static int id(String line) {
        int end = line.indexOf(']');
        if (!line.startsWith("[") || end < 2) return -1;
        for (int i = 1; i < end; i++) if (line.charAt(i) < '0' || line.charAt(i) > '9') return -1;
        return Integer.parseInt(line.substring(1, end));
    }

    /** What stands between 「 and 」 on an element line, or null when the line has no label. */
    private static String label(String line) {
        int open = line.indexOf('「');
        if (open < 0) return null;
        int close = line.indexOf('」', open + 1);
        return close < 0 ? null : line.substring(open + 1, close);
    }
}
