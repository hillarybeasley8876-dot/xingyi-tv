package xyz.erapi0neer.orrery;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The home's pure typesetting: the strings the third line, the now-playing line and the about line are made of, the
 * geometry of the ruler index and the shelf-long axis, and (2026-11-08, 「明日方舟那种 ui」「线条和简约排版为主」) the band
 * at the bottom centre: its slots, its pages, which slot a direction leads to, the words inside a cell and the tilt's
 * own projection. No Android here, so tools/home_check.sh can run it on the PC.
 */
final class HomeLayout {
    private HomeLayout() { }

    /** Where a direction leads when there is nothing that way, and when LEFT leaves the band for the ruler. */
    static final int NONE = -1, RULER = -2;
    static final int LEFT = 0, RIGHT = 1, UP = 2, DOWN = 3;

    /** The grid the type layer draws on (1920 x 1080), shared with HomeUi so the two never drift apart. */
    static final float W = 1920f, MX = 96f;
    static final float SHELF_X = 404f, SHELF_R = W - MX;
    static final int PER_PAGE = 7;
    static final float COL = (SHELF_R - SHELF_X) / PER_PAGE;
    static final float INDEX_BOTTOM = 990f, INDEX_STEP = 46f;
    /** The ruler's hairline runs from the first row this far up to the last row this far down. */
    static final float RULER_UP = 30f, RULER_DOWN = 12f;
    /** How far the axis fades out after the last entry, at most. */
    static final float FADE_MAX = 260f;
    /** The separator inside a line, and the gap between a title and its artist. */
    static final String SEP = "　·　", GAP = "　";
    static final String ELLIPSIS = "…";
    /** What makes a part of the third line the weather and not the 农历: it carries a temperature. */
    static final char DEGREE = '°';
    /** The now-playing line never grows past this many characters. */
    static final int NOW_MAX = 18;

    // ------------------------------------------------------------------ the band (the terminal)
    /** The open shelf's entries on a 6 x 2 grid of cells at the bottom centre (ui_home_ark.py, layout bottom). */
    static final float BAND_X = 372f, BAND_Y = 686f, CELL_W = 186f, CELL_H = 138f, CELL_GAP = 12f;
    static final int BAND_COLS = 6, BAND_ROWS = 2, BAND_PER_PAGE = 8;
    static final float BAND_W = BAND_COLS * CELL_W + (BAND_COLS - 1) * CELL_GAP;
    static final float BAND_H = BAND_ROWS * CELL_H + (BAND_ROWS - 1) * CELL_GAP;
    /** (col, row, w, h) per slot: the entries sit on the band in this order. */
    static final int[][] SLOTS = {{0, 0, 2, 2}, {2, 0, 2, 1}, {4, 0, 1, 1}, {5, 0, 1, 1},
            {2, 1, 1, 1}, {3, 1, 1, 1}, {4, 1, 1, 1}, {5, 1, 1, 1}};
    /** The band leans back about its bottom centre, seen from CAM_PX away (the mock-up's perspective). */
    static final float BAND_TILT = 16f, CAM_PX = 2200f;
    /** HomeUi passes this to Camera.setLocation: the camera's own unit is 1/72 of a canvas pixel. */
    static final float CAM_UNITS = 72f;

    static float slotX(int s) { return BAND_X + SLOTS[s][0] * (CELL_W + CELL_GAP); }
    static float slotY(int s) { return BAND_Y + SLOTS[s][1] * (CELL_H + CELL_GAP); }
    static float slotW(int s) { return SLOTS[s][2] * CELL_W + (SLOTS[s][2] - 1) * CELL_GAP; }
    static float slotH(int s) { return SLOTS[s][3] * CELL_H + (SLOTS[s][3] - 1) * CELL_GAP; }
    static float slotR(int s) { return slotX(s) + slotW(s); }
    static float slotB(int s) { return slotY(s) + slotH(s); }
    static float slotCx(int s) { return slotX(s) + slotW(s) / 2f; }
    static float slotCy(int s) { return slotY(s) + slotH(s) / 2f; }
    /** The 2 x 2 cell is the big one, the 2 x 1 the wide one. */
    static boolean slotBig(int s) { return SLOTS[s][2] * SLOTS[s][3] >= 4; }
    static boolean slotWide(int s) { return SLOTS[s][2] == 2 && SLOTS[s][3] == 1; }

    /** How many pages a shelf of n entries takes (an empty shelf still has one). */
    static int pages(int n) { return n <= BAND_PER_PAGE ? 1 : (n + BAND_PER_PAGE - 1) / BAND_PER_PAGE; }
    static int pageOf(int index) { return index / BAND_PER_PAGE; }
    static int slotOf(int index) { return index % BAND_PER_PAGE; }
    /** The entry on a page's slot, or NONE where the shelf has run out. */
    static int entryAt(int page, int slot, int n) {
        int e = page * BAND_PER_PAGE + slot;
        return e >= 0 && e < n ? e : NONE;
    }

    /** 农历 and the day's weather: either alone, or joined; nothing at all when neither is known. */
    static String infoLine(String lunar, String weather) {
        return join(lunar, weather, SEP);
    }

    /** What is playing: the title, then the artist, cut so it never reaches the clock. */
    static String nowLine(String title, String artist) {
        return cut(join(title, artist, GAP), NOW_MAX);
    }

    /** The line under an entry's name: what it is, then what 星仪 says about it. */
    static String about(String kind, String note) {
        return join(kind, note, SEP);
    }

    /** Where the axis stops: one column past the last entry of a shelf that fits on the screen. */
    static float axisEnd(int n, float windowStart) {
        float last = Math.max(0, n - 1) - windowStart;
        return SHELF_X + COL * (last + 1);
    }

    /** How long the fade after the axis's end is: the rest of the width, never more than FADE_MAX. */
    static float fadeLength(float end) {
        return Math.max(0f, Math.min(FADE_MAX, SHELF_R - end));
    }

    /** The top of the ruler's hairline: above the first shelf's row. */
    static float rulerTop(int shelves) {
        return INDEX_BOTTOM - Math.max(0, shelves - 1) * INDEX_STEP - RULER_UP;
    }

    /** The bottom of the ruler's hairline. */
    static float rulerBottom() {
        return INDEX_BOTTOM + RULER_DOWN;
    }

    /** Two parts with a separator, whichever of them is there. */
    static String join(String a, String b, String sep) {
        String x = clean(a), y = clean(b);
        if (x.isEmpty()) return y;
        if (y.isEmpty()) return x;
        return x + sep + y;
    }

    /** Null and blank are nothing; the rest keeps its own spacing. */
    static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    /** At most max characters, the last one an ellipsis. */
    static String cut(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(1, max - 1)) + ELLIPSIS;
    }

    // ------------------------------------------------------------------ the keys on the band
    static final float EPS = 0.01f;

    /**
     * Where a direction on the band leads: the nearest slot whose centre lies that way (a tie goes to the smaller
     * sideways offset, then to the lower entry), else the next / previous page, else NONE — or RULER when LEFT
     * leaves the first page.
     */
    static int neighbor(int n, int index, int dir) {
        if (n <= 0 || index < 0 || index >= n) return NONE;
        int page = pageOf(index), slot = slotOf(index);
        float cx = slotCx(slot), cy = slotCy(slot);
        int best = NONE;
        float bd = 0f, bs = 0f;
        for (int s = 0; s < BAND_PER_PAGE; s++) {
            int e = entryAt(page, s, n);
            if (e < 0 || e == index) continue;
            float dx = slotCx(s) - cx, dy = slotCy(s) - cy;
            if (!away(dx, dy, dir)) continue;
            float d = dx * dx + dy * dy, side = Math.abs(sideways(dx, dy, dir));
            if (better(e, d, side, best, bd, bs)) { best = e; bd = d; bs = side; }
        }
        if (best != NONE) return best;
        int next = page + 1;
        if (dir == RIGHT) return next * BAND_PER_PAGE < n ? next * BAND_PER_PAGE : NONE;
        if (dir == LEFT) return page > 0 ? page * BAND_PER_PAGE - 1 : RULER;
        return NONE;                   // UP and DOWN never leave the band
    }

    /** Nearest first, then the smaller sideways offset, then the lower entry: the order the keys walk in. */
    static boolean better(int e, float d, float side, int best, float bd, float bs) {
        if (best == NONE) return true;
        if (d < bd - EPS) return true;
        if (d > bd + EPS) return false;
        if (side < bs - EPS) return true;
        if (side > bs + EPS) return false;
        return e < best;
    }

    /** Does a slot's centre lie that way at all? */
    static boolean away(float dx, float dy, int dir) {
        if (dir == LEFT) return dx < -EPS;
        if (dir == RIGHT) return dx > EPS;
        if (dir == UP) return dy < -EPS;
        return dy > EPS;
    }

    /** The offset across a direction: what a tie is broken by. */
    static float sideways(float dx, float dy, int dir) {
        return dir == LEFT || dir == RIGHT ? dy : dx;
    }

    // ------------------------------------------------------------------ the words in a cell
    /** The English line under a cell's name: what the TV's own apps are called, in the mock-up's words. */
    static final Map<String, String> EN = new HashMap<>();
    /** The same by package, for a label the TV spells its own way. */
    static final Map<String, String> EN_PKG = new HashMap<>();
    static {
        EN.put("星仪电视助手", "XINGYI · ASSISTANT");
        EN.put("网易云音乐", "NETEASE · MUSIC");
        EN.put("云视听极光", "TENCENT · VIDEO");
        EN.put("哔哩哔哩", "BILIBILI · VIDEO");
        EN.put("无线投屏", "CAST");
        EN.put("应用商店", "APP STORE");
        EN.put("设置", "SETTINGS");
        EN.put("电视管家", "MANAGER");
        EN.put("相册", "GALLERY");
        EN.put("TV Bro", "TV BRO · BROWSER");
        EN.put("U盘 / 媒体", "MEDIA");
        EN.put("夸克网盘", "QUARK · CLOUD");
        EN.put("全部应用", "ALL APPS");
        EN.put("信号源", "SOURCE · HDMI");
        EN_PKG.put("xyz.erapi0neer.xingyi", "XINGYI · ASSISTANT");
        EN_PKG.put("com.netease.cloudmusic.tv", "NETEASE · MUSIC");
        EN_PKG.put("com.ktcp.video", "TENCENT · VIDEO");
        EN_PKG.put("tv.danmaku.bili", "BILIBILI · VIDEO");
        EN_PKG.put("com.xiaomi.mitv.smartshare", "CAST");
        EN_PKG.put("com.mitv.gallery", "GALLERY");
        EN_PKG.put("com.xiaomi.mitv.tvmanager", "MANAGER");
        EN_PKG.put("com.xiaomi.mitv.appstore", "APP STORE");
        EN_PKG.put("com.xiaomi.mitv.mediaexplorer", "MEDIA");
    }
    /** What an app is, when nothing else says: the words Shelves.kind() would use. */
    static final Pattern MUSIC = Pattern.compile("音乐|K歌|听书|电台|歌曲");
    static final Pattern VIDEO = Pattern.compile("视频|影视|电影|直播|动漫|追剧|影院");
    static final Pattern GAME = Pattern.compile("游戏|斗地主|麻将|象棋|围棋");
    static final Pattern PHOTO = Pattern.compile("照片|相册|图库|壁纸");
    static final Pattern BROWSER = Pattern.compile("浏览器");
    static final Pattern HDMI = Pattern.compile("^HDMI\\s*\\d+$");

    /** The English line of a cell: by label, else by package, else by what the app is, else APP. */
    static String english(String label, String pkg) {
        String s = clean(label);
        String e = EN.get(s);
        if (e != null) return e;
        if (HDMI.matcher(s).matches()) return "SOURCE · HDMI " + s.replaceAll("^HDMI\\s*", "");
        if (pkg != null) {
            e = EN_PKG.get(pkg);
            if (e != null) return e;
        }
        return kindEn(s);
    }

    /** An app's own word for what it is, in English. */
    static String kindEn(String label) {
        if (MUSIC.matcher(label).find()) return "MUSIC";
        if (VIDEO.matcher(label).find()) return "VIDEO";
        if (GAME.matcher(label).find()) return "GAME";
        if (PHOTO.matcher(label).find()) return "GALLERY";
        if (BROWSER.matcher(label).find()) return "BROWSER";
        return "APP";
    }

    /** The name in a cell: the label, except 星仪, which the TV calls 星仪电视助手. */
    static String name(String label) {
        String s = clean(label);
        return "星仪电视助手".equals(s) ? "星仪" : s;
    }

    static boolean xingyi(String label) { return "星仪电视助手".equals(clean(label)); }

    /** The line at the bottom of a cell: what 星仪 says about the entry, else what it is. */
    static String stateLine(String kind, String note) {
        String n = clean(note);
        return n.isEmpty() ? clean(kind) : n;
    }

    /** 星仪's own line: what it is doing, and how to call it. 待命 when the bridge has not said. */
    static String agentState(String word) {
        String w = clean(word);
        return (w.isEmpty() ? "待命" : w) + SEP + "叫一声「小爱同学」";
    }

    /** The weather alone, out of the third line under the date (农历 · weather): only a part with a temperature is one. */
    static String weatherOf(String line) {
        String s = clean(line);
        int i = s.lastIndexOf(SEP);
        String w = i < 0 ? s : clean(s.substring(i + SEP.length()));
        return w.indexOf(DEGREE) < 0 ? "" : w;
    }

    /** The band label's second half: the open shelf and how many entries it has. */
    static String shelfCount(String shelf, int n) {
        return clean(shelf) + " · " + String.format(Locale.US, "%02d", n);
    }

    /** Where the focus is, when the shelf does not fit on one page. */
    static String pageAt(int page, int pages) {
        return String.format(Locale.US, "%02d / %02d", page + 1, pages);
    }

    // ------------------------------------------------------------------ the tilt
    /**
     * The band's own projection, mirroring what android.graphics.Camera does: the point is turned about the X axis
     * through the pivot (y grows downwards, so what is above the pivot leans away) and then divided by the camera's
     * distance. Returns {x, y, scale}. Nothing on Android calls this — the checks measure what the TV draws.
     */
    static float[] project(float x, float y, float tiltDeg, float camDist, float pivotX, float pivotY) {
        double a = Math.toRadians(tiltDeg);
        float dy = y - pivotY;
        float z = (float) (-dy * Math.sin(a));        // above the pivot: away from the camera
        float s = camDist / (camDist + z);
        return new float[] {pivotX + (x - pivotX) * s, pivotY + (float) (dy * Math.cos(a)) * s, s};
    }

    /** How much narrower a line at y is than one through the pivot. */
    static float projectScale(float y, float tiltDeg, float camDist, float pivotY) {
        double a = Math.toRadians(tiltDeg);
        return camDist / (camDist + (float) (-(y - pivotY) * Math.sin(a)));
    }

    /** The band's own numbers: the top edge against the bottom one, in per cent. */
    static float narrowing() {
        return (1f - projectScale(BAND_Y, BAND_TILT, CAM_PX, BAND_Y + BAND_H)) * 100f;
    }
}
