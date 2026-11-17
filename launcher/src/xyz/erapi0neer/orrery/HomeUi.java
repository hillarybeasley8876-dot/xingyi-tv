package xyz.erapi0neer.orrery;

import android.content.Context;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The type layer over the flow (2026-11-08, the user: 「你看看人家明日方舟那种 ui」「但是还是按照线条和简约排版为主」
 * 「下方居中」): the clock, the date and the quiet third line, a status strip at the top right, the shelves (Shelves) as
 * a ruler on the left, and the open shelf as a band of line-drawn cells at the bottom centre, leaning back about its
 * own bottom centre through a Camera. Everything is a 1 px line, text and at most a 6 % veil; one ink colour, and the
 * accent only on the ruler's tick and the focused cell's corners. Laid out on the 1920 x 1080 grid of the mock-up
 * redesign/concepts/attractor/ui_home_ark.py (layout bottom) and scaled to the view. Redraws only while something
 * moves and on the minute.
 */
final class HomeUi extends View {
    static final float W = 1920f, H = 1080f, MX = 96f;
    static final int INK = 0xEEF1F5, ACCENT = 0xA8C8EE;
    // the clock, the date and the third line under them
    static final float CLOCK_PX = 136, CLOCK_BASE = 176, DATE_PX = 26, DATE_BASE = 228;
    static final float INFO_PX = 19, INFO_BASE = DATE_BASE + 38, A_INFO = 0.38f;
    static final float A_CLOCK = 0.94f, A_DATE = 0.60f;
    // the barcode and the serial under the date
    static final float BARS_X = MX, BARS_Y = 286, BARS_W = 220, BARS_H = 14, A_BARS = 0.22f;
    static final float SERIAL_X = MX + 232, SERIAL_BASE = 295, SERIAL_PX = 11, A_SERIAL = 0.30f;
    static final String SERIAL = "ORRERY · MITV-ASTP0 · 04";
    /** bar, gap, bar, gap, bar, gap: 15 px a period, as the mock-up's repeating gradient. */
    static final int[] BARS = {1, 3, 2, 3, 1, 5};
    // the status strip at the top right
    static final float ST_LABEL_PX = 11, ST_LABEL_BASE = 134, A_ST_LABEL = 0.38f;
    static final float ST_VALUE_PX = 20, ST_VALUE_BASE = 164, A_ST_VALUE = 0.82f;
    static final float ST_PAD = 28, ST_TOP = 120, ST_BOTTOM = 172, A_ST_HAIR = 0.18f;
    static final String[] ST_LABELS = {"WEATHER", "NOW PLAYING", "XINGYI"};
    // the shelf index as a ruler (left)
    static final float INDEX_BOTTOM = HomeLayout.INDEX_BOTTOM, INDEX_STEP = HomeLayout.INDEX_STEP;
    static final float NUM_X = MX + 30, NAME_X = MX + 64, CAT_PX = 23, NUM_PX = 13;
    static final float TICK_W = 8, TICK_ON_W = 18, TICK_UP = 8;
    static final float A_HAIR = 0.18f, A_RTICK = 0.40f, A_NUM = 0.28f, A_NUM_ON = 0.80f, A_COUNT_ON = 0.55f;
    static final float A_CAT = 0.36f, A_COUNT = 0.20f;
    // the band: the open shelf's entries at the bottom centre, leaning back
    static final float BAND_X = HomeLayout.BAND_X, BAND_Y = HomeLayout.BAND_Y;
    static final float BAND_W = HomeLayout.BAND_W, BAND_H = HomeLayout.BAND_H;
    static final float BAND_CX = BAND_X + BAND_W / 2f, BAND_BOTTOM = BAND_Y + BAND_H;
    static final float TILT = HomeLayout.BAND_TILT;
    /** Camera's own unit is 1/72 of a canvas pixel; HomeLayout.CAM_PX is how far away the eye is. */
    static final float CAM_Z = -HomeLayout.CAM_PX / HomeLayout.CAM_UNITS;
    static final float FRAME_OUT = 22, FRAME_ARM = 18, A_FRAME = 0.50f;
    static final float LABEL_BASE = BAND_Y - 44, LABEL_PX = 12, A_LABEL = 0.50f, A_SUB = 0.40f, SUB_GAP = 18;
    static final String TERMINAL = "TERMINAL";
    // a cell
    static final float CELL_INK = 0.20f, CELL_ON_INK = 0.95f, CELL_FILL = 0.015f, CELL_ON_FILL = 0.06f;
    static final float CODE_X = 14, CODE_DY = 21, CODE_PX = 11, A_CODE = 0.42f, A_CODE_ON = 0.90f;
    static final float GHOST_IN = 10, GHOST_BELOW = 22, GHOST_BIG_BELOW = 52;
    static final float GHOST_PX = 118, GHOST_BIG_PX = 260, A_GHOST = 0.05f;
    static final float TEXT_X = 16, EN_X = 17, TEXT_IN = 32;
    static final float NAME_PX = 26, NAME_WIDE_PX = 30, NAME_BIG_PX = 62;
    static final float NAME_UP = 58, NAME_BIG_UP = 92, A_NAME = 0.62f;
    static final float EN_PX = 11, EN_BIG_PX = 14, EN_UP = 38, EN_BIG_UP = 64, A_EN = 0.38f, A_EN_ON = 0.70f;
    static final float STATE_PX = 14, STATE_BIG_PX = 18, STATE_UP = 14, STATE_BIG_UP = 26;
    static final float A_STATE = 0.42f, A_STATE_BIG = 0.60f, A_STATE_ON = 0.78f;
    static final float CORNER = 12, CORNER_W = 2;
    // the veil under it all, and the footer
    static final float VEIL_TOP = 560, VEIL_MID = 0.55f, VEIL_A = 0.35f, VEIL_A2 = 0.70f;
    static final float FOOT_BASE = 1020, FOOT_PX = 11, A_FOOT = 0.26f;
    static final String FOOTER = "ORRERY 4 · XINGYI TERMINAL";
    static final long MOVE_MS = 160, INTRO_MS = 900, OUTRO_MS = 220, SHELF_MS = 280;

    final Paint clock = text(), date = text(), info = text(), serial = text(), footer = text();
    final Paint stLabel = text(), stValue = text(), cat = text(), num = text();
    final Paint bandLabel = text(), bandSub = text();
    final Paint code = text(), ghost = text(), ghostBig = text();
    final Paint name = text(), nameWide = text(), nameBig = text();
    final Paint en = text(), enBig = text(), state = text(), stateBig = text();
    final Paint line = new Paint(), focus = new Paint(), fill = new Paint(), veil = new Paint();
    final Rect bounds = new Rect();
    final Camera cam = new Camera();
    final Matrix tilt = new Matrix();
    final SimpleDateFormat dateFmt = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA);
    final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm", Locale.US);
    /** The barcode's bars, laid out once. */
    final float[] barX = new float[64], barW = new float[64];
    int bars;
    /** The status strip's columns, kept so a redraw allocates nothing. */
    final String[] stL = new String[3], stV = new String[3];
    final float[] stW = new float[3];
    final float[] toRect = new float[4];

    /** One shelf's content as drawn: kept for the one folding away while the next opens. */
    static final class Page {
        List<String> labels = new ArrayList<>(), kinds = new ArrayList<>(), pkgs = new ArrayList<>();
        List<Boolean> fresh = new ArrayList<>();
        int focus;
        /** Where the focus outline is now, and where it started from. */
        final float[] rect = new float[4], from = new float[4];
        int size() { return labels.size(); }
    }

    List<String> shelfNames = new ArrayList<>();
    List<Integer> shelfCounts = new ArrayList<>();
    List<String> pkgs = new ArrayList<>();
    int shelf;
    float shelfPos, shelfFrom;
    Page page = new Page(), leaving;
    String clockText = "", dateText = "", infoText = "", weatherText = "", nowTitle = "", nowArtist = "", agent = "";
    boolean onRuler;
    // animated state
    long moveStart = -1, shelfStart = -1;
    int shelfDir;
    long introStart = -1, outroStart = -1;
    float shown = 1f;                // global opacity (intro / launch)

    HomeUi(Context c, Typeface inter, Typeface mono, Typeface cjkLight, Typeface cjk) {
        super(c);
        setFocusable(false);
        Typeface oswald500 = face(variable(c, "fonts/Oswald-Variable.ttf", "'wght' 500"), cjk);
        Typeface oswald600 = face(variable(c, "fonts/Oswald-Variable.ttf", "'wght' 600"), cjk);
        Typeface hairline = face(variable(c, "fonts/Inter-Variable.ttf", "'wght' 100"), inter);
        Typeface medium = face(sys("/system/fonts/MiSans-Medium.otf"), cjk);
        Typeface semibold = face(sys("/system/fonts/MiSans-Semibold.otf"), cjkLight);
        set(clock, inter, CLOCK_PX, -0.01f);
        clock.setFontFeatureSettings("'tnum' 1");
        set(date, cjk, DATE_PX, 0.08f);
        set(info, cjk, INFO_PX, 0.10f);
        set(serial, mono, SERIAL_PX, 0.20f);
        set(footer, oswald500, FOOT_PX, 0.30f);
        set(stLabel, oswald600, ST_LABEL_PX, 0.28f);
        set(stValue, cjk, ST_VALUE_PX, 0.04f);
        set(cat, cjk, CAT_PX, 0.14f);
        set(num, mono, NUM_PX, 0.06f);
        set(bandLabel, oswald600, LABEL_PX, 0.32f);
        set(bandSub, mono, LABEL_PX, 0.12f);
        set(code, mono, CODE_PX, 0.12f);
        set(ghost, hairline, GHOST_PX, 0f);
        set(ghostBig, hairline, GHOST_BIG_PX, 0f);
        set(name, medium, NAME_PX, 0.06f);
        set(nameWide, medium, NAME_WIDE_PX, 0.06f);
        set(nameBig, semibold, NAME_BIG_PX, 0.10f);
        set(en, oswald500, EN_PX, 0.26f);
        set(enBig, oswald500, EN_BIG_PX, 0.34f);
        set(state, cjk, STATE_PX, 0.04f);
        set(stateBig, cjk, STATE_BIG_PX, 0.04f);
        line.setStyle(Paint.Style.FILL);
        focus.setStyle(Paint.Style.FILL);
        focus.setAntiAlias(true);        // the outline glides, so it lands between pixels
        fill.setStyle(Paint.Style.FILL);
        // the veil: nothing at the top, a third at 55 %, most of it at the bottom
        veil.setShader(new LinearGradient(0, VEIL_TOP, 0, H,
                new int[] {0, (Math.round(VEIL_A * 255f) << 24), (Math.round(VEIL_A2 * 255f) << 24)},
                new float[] {0f, VEIL_MID, 1f}, Shader.TileMode.CLAMP));
        layOutBars();
        tick();
    }

    static Paint text() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setColor(0xFFFFFFFF);
        return p;
    }

    static void set(Paint p, Typeface t, float px, float spacing) {
        p.setTypeface(t);
        p.setTextSize(px);
        p.setLetterSpacing(spacing);
    }

    static Typeface face(Typeface t, Typeface fallback) { return t != null ? t : fallback; }

    /** A variable face out of the assets at one weight; null when it will not load. */
    static Typeface variable(Context c, String asset, String axes) {
        try {
            Typeface t = new Typeface.Builder(c.getAssets(), asset).setFontVariationSettings(axes).build();
            if (t != null) return t;
        } catch (Exception ignored) { }
        try { return Typeface.createFromAsset(c.getAssets(), asset); } catch (Exception e) { return null; }
    }

    /** One of the TV's own faces; null when it is not there. */
    static Typeface sys(String path) {
        try { return Typeface.createFromFile(path); } catch (Exception e) { return null; }
    }

    /** The barcode: the pattern walked across 220 px, once. */
    void layOutBars() {
        float x = 0;
        while (x < BARS_W && bars < barX.length) {
            for (int i = 0; i < BARS.length && x < BARS_W; i += 2) {
                float w = Math.min(BARS[i], BARS_W - x);
                barX[bars] = x;
                barW[bars] = w;
                bars++;
                x += w + BARS[i + 1];
            }
        }
    }

    // ------------------------------------------------------------------ model
    Page page(List<String> labels, List<String> kinds, List<Boolean> fresh, int focus) {
        Page p = new Page();
        p.labels = new ArrayList<>(labels);
        p.kinds = new ArrayList<>(kinds);
        p.fresh = new ArrayList<>(fresh);
        p.pkgs = pkgs;
        p.focus = Math.max(0, Math.min(focus, p.size() - 1));
        slotRect(p.focus, p.rect);
        System.arraycopy(p.rect, 0, p.from, 0, 4);
        return p;
    }

    /** Everything at once (start, packages changed): no animation. */
    void setShelves(List<String> names, List<Integer> counts, int open, List<String> labels, List<String> kinds,
                    List<Boolean> fresh, int focus) {
        shelfNames = new ArrayList<>(names);
        shelfCounts = new ArrayList<>(counts);
        shelf = Math.max(0, Math.min(open, shelfNames.size() - 1));
        shelfPos = shelfFrom = shelf;
        page = page(labels, kinds, fresh, focus);
        leaving = null;
        moveStart = shelfStart = -1;
        invalidate();
    }

    /** The packages behind the labels, for the English line in each cell (HomeLayout.english). */
    void setPackages(List<String> p) {
        pkgs = p == null ? new ArrayList<String>() : new ArrayList<>(p);
        if (page != null) page.pkgs = pkgs;
        invalidate();
    }

    /** UP/DOWN on the ruler: the open shelf folds away and the next one opens, the index mark slides to it. */
    void switchShelf(int open, List<String> labels, List<String> kinds, List<Boolean> fresh, int focus, int dir) {
        if (open == shelf) return;
        shelfFrom = shelfPos;
        shelf = open;
        leaving = page;
        page = page(labels, kinds, fresh, focus);
        shelfStart = SystemClock.uptimeMillis();
        shelfDir = dir;
        moveStart = -1;
        invalidate();
    }

    /** The focus moves to another entry: the outline glides there, or is already there after a page turn. */
    void moveTo(int index, int dir) {
        Page p = page;
        if (p.size() == 0 || index == p.focus || index < 0 || index >= p.size()) return;
        System.arraycopy(p.rect, 0, p.from, 0, 4);
        if (HomeLayout.pageOf(index) != HomeLayout.pageOf(p.focus)) slotRect(index, p.from);
        p.focus = index;
        moveStart = SystemClock.uptimeMillis();
        invalidate();
    }

    /** LEFT off the first page: the keys are on the shelf ruler, not on the band. */
    void setOnRuler(boolean on) {
        if (on == onRuler) return;
        onRuler = on;
        invalidate();
    }

    void tick() {
        Date now = new Date();
        clockText = clockFmt.format(now);
        dateText = dateFmt.format(now);
        invalidate();
    }

    /** The third line (农历 and weather) and what is playing: one redraw, and only when something changed. */
    void setInfo(String line, String nowTitle, String nowArtist) {
        setInfo(line, HomeLayout.weatherOf(line), nowTitle, nowArtist);
    }

    /** The same, with the weather on its own for the status strip. */
    void setInfo(String line, String weather, String nowTitle, String nowArtist) {
        String w = weather == null ? "" : weather;
        if (line.equals(infoText) && w.equals(weatherText)
                && nowTitle.equals(this.nowTitle) && nowArtist.equals(this.nowArtist)) return;
        infoText = line;
        weatherText = w;
        this.nowTitle = nowTitle;
        this.nowArtist = nowArtist;
        invalidate();
    }

    /** What 星仪 is doing (待命 / 执行中 / 等你确认 / 等你回答), for the strip and for 星仪's own cell. */
    void setAgent(String word) {
        String w = word == null ? "" : word;
        if (w.equals(agent)) return;
        agent = w;
        invalidate();
    }

    void intro() {
        introStart = SystemClock.uptimeMillis();
        outroStart = -1;
        invalidate();
    }

    void outro() {
        outroStart = SystemClock.uptimeMillis();
        invalidate();
    }

    // ------------------------------------------------------------------ grid
    /** Where an entry's slot is, in the band's own coordinates. */
    void slotRect(int index, float[] out) {
        int s = HomeLayout.slotOf(Math.max(0, index));
        out[0] = HomeLayout.slotX(s);
        out[1] = HomeLayout.slotY(s);
        out[2] = HomeLayout.slotR(s);
        out[3] = HomeLayout.slotB(s);
    }

    float inkWidth(Paint p, String s) {
        if (s.isEmpty()) return 0;
        p.getTextBounds(s, 0, s.length(), bounds);
        return bounds.right - bounds.left;
    }

    float lsb(Paint p, String s) {
        if (s.isEmpty()) return 0;
        p.getTextBounds(s, 0, s.length(), bounds);
        return bounds.left;
    }

    String fit(Paint p, String s, float max) {
        if (s == null) return "";
        if (p.measureText(s) <= max) return s;
        for (int n = s.length() - 1; n > 0; n--) {
            String t = s.substring(0, n).trim() + "…";
            if (p.measureText(t) <= max) return t;
        }
        return s.substring(0, 1);
    }

    String shelfName() {
        return shelf >= 0 && shelf < shelfNames.size() ? shelfNames.get(shelf) : "";
    }

    // ------------------------------------------------------------------ drawing
    static float easeOut(float x) { x = Math.max(0f, Math.min(1f, x)); float y = 1f - x; return 1f - y * y * y; }
    static float smooth(float x) { x = Math.max(0f, Math.min(1f, x)); return x * x * (3f - 2f * x); }

    static int alpha(float a) { return Math.round(Math.max(0f, Math.min(1f, a)) * 255f) << 24; }
    int ink(float a) { return alpha(a) | INK; }
    int accent(float a) { return alpha(a) | ACCENT; }
    int white(float a) { return alpha(a) | 0xFFFFFF; }

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        boolean moving = false;
        // global opacity: intro rises, launch falls
        float g = 1f, rise = 0f;
        if (introStart >= 0) {
            float k = (now - introStart) / (float) INTRO_MS;
            if (k >= 1f) introStart = -1; else moving = true;
            g = smooth(k);
            rise = 10f * (1f - easeOut(k));
        }
        if (outroStart >= 0) {
            float k = (now - outroStart) / (float) OUTRO_MS;
            g *= 1f - smooth(k);
            if (k < 1f) moving = true;
        }
        shown = g;
        // the focus outline glides from cell to cell
        Page p = page;
        float mk = 1f;
        if (moveStart >= 0) {
            mk = (now - moveStart) / (float) MOVE_MS;
            if (mk >= 1f) { mk = 1f; moveStart = -1; } else moving = true;
        }
        slotRect(p.focus, toRect);
        float e = easeOut(mk);
        for (int k = 0; k < 4; k++) p.rect[k] = p.from[k] + (toRect[k] - p.from[k]) * e;
        // shelf switch: the one leaving fades out, the new one fades in, the index mark slides
        float sk = 1f;
        if (shelfStart >= 0) {
            sk = (now - shelfStart) / (float) SHELF_MS;
            if (sk >= 1f) { sk = 1f; shelfStart = -1; leaving = null; } else moving = true;
        }
        shelfPos = shelfFrom + (shelf - shelfFrom) * easeOut(sk);

        c.save();
        c.scale(getWidth() / W, getHeight() / H);
        if (g > 0.002f) {
            drawVeil(c, g);
            drawTop(c, g);
            drawStatus(c, g);
            c.translate(0, rise);
            drawIndex(c, g);
            if (leaving != null) {
                float a = 1f - smooth(sk / 0.6f);
                if (a > 0f) drawBand(c, leaving, g * a, -shelfDir * 12f * smooth(sk));
                float b = smooth((sk - 0.25f) / 0.75f);
                if (b > 0f) drawBand(c, p, g * b, shelfDir * 14f * (1f - easeOut(sk)));
            } else {
                drawBand(c, p, g, 0f);
            }
            drawFooter(c, g);
        }
        c.restore();
        if (moving) postInvalidateOnAnimation();
    }

    /** The veil under the band: nothing at 560, a third at 55 %, most of it at the bottom. */
    void drawVeil(Canvas c, float g) {
        veil.setAlpha(Math.round(Math.max(0f, Math.min(1f, g)) * 255f));
        c.drawRect(0, VEIL_TOP, W, H, veil);
    }

    /** Clock and date on the left, then 农历 and the weather, then the barcode and the serial. */
    void drawTop(Canvas c, float g) {
        clock.setColor(ink(A_CLOCK * g));
        c.drawText(clockText, MX - lsb(clock, clockText), CLOCK_BASE, clock);
        date.setColor(ink(A_DATE * g));
        c.drawText(dateText, MX - lsb(date, dateText), DATE_BASE, date);
        if (!infoText.isEmpty()) {
            info.setColor(ink(A_INFO * g));
            c.drawText(infoText, MX - lsb(info, infoText), INFO_BASE, info);
        }
        line.setColor(ink(A_BARS * g));
        for (int k = 0; k < bars; k++) c.drawRect(BARS_X + barX[k], BARS_Y, BARS_X + barX[k] + barW[k], BARS_Y + BARS_H, line);
        serial.setColor(ink(A_SERIAL * g));
        c.drawText(SERIAL, SERIAL_X - lsb(serial, SERIAL), SERIAL_BASE, serial);
    }

    /** The status strip: WEATHER, NOW PLAYING and XINGYI, right-aligned, a hairline between the columns. */
    void drawStatus(Canvas c, float g) {
        stV[0] = weatherText;
        stV[1] = nowTitle.isEmpty() ? "" : HomeLayout.nowLine(nowTitle, nowArtist);
        stV[2] = agent;
        int n = 0;
        float total = 0f;
        for (int i = 0; i < ST_LABELS.length; i++) {
            if (stV[i] == null || stV[i].isEmpty()) continue;
            stL[n] = ST_LABELS[i];
            stV[n] = stV[i];
            stW[n] = Math.max(inkWidth(stLabel, stL[n]), inkWidth(stValue, stV[n])) + 2 * ST_PAD;
            total += stW[n];
            n++;
        }
        float x = W - MX - total;
        for (int k = 0; k < n; k++) {
            if (k > 0) {
                line.setColor(ink(A_ST_HAIR * g));
                c.drawRect(x, ST_TOP, x + 1, ST_BOTTOM, line);
            }
            stLabel.setColor(ink(A_ST_LABEL * g));
            c.drawText(stL[k], x + ST_PAD - lsb(stLabel, stL[k]), ST_LABEL_BASE, stLabel);
            stValue.setColor(ink(A_ST_VALUE * g));
            c.drawText(stV[k], x + ST_PAD - lsb(stValue, stV[k]), ST_VALUE_BASE, stValue);
            x += stW[k];
        }
    }

    void drawFooter(Canvas c, float g) {
        footer.setColor(ink(A_FOOT * g));
        c.drawText(FOOTER, W - MX - inkWidth(footer, FOOTER) - lsb(footer, FOOTER), FOOT_BASE, footer);
    }

    /** The shelves as a ruler: a hairline, and per shelf a tick, its number, its name and how many entries it has. */
    void drawIndex(Canvas c, float g) {
        int n = shelfNames.size();
        if (n == 0) return;
        float top = INDEX_BOTTOM - (n - 1) * INDEX_STEP;
        line.setColor(ink(A_HAIR * g));
        c.drawRect(MX, HomeLayout.rulerTop(n), MX + 1, HomeLayout.rulerBottom(), line);
        for (int k = 0; k < n; k++) {
            float y = top + k * INDEX_STEP;
            float f = Math.max(0f, 1f - Math.abs(k - shelfPos));       // 1 on the open shelf
            if (k != shelf) {
                line.setColor(ink(A_RTICK * g));
                c.drawRect(MX, y - TICK_UP, MX + TICK_W, y - TICK_UP + 1, line);
            }
            String no = String.format(Locale.US, "%02d", k + 1);
            num.setColor(ink((A_NUM + (A_NUM_ON - A_NUM) * f) * g));
            c.drawText(no, NUM_X - lsb(num, no), y - 1f, num);
            String name = shelfNames.get(k);
            cat.setColor(ink((A_CAT + (1f - A_CAT) * f) * g));
            c.drawText(name, NAME_X - lsb(cat, name), y, cat);
            String cnt = String.valueOf(shelfCounts.get(k));
            num.setColor(ink((A_COUNT + (A_COUNT_ON - A_COUNT) * f) * g));
            c.drawText(cnt, NAME_X + inkWidth(cat, name) + 10f - lsb(num, cnt), y - 1f, num);
        }
        // the open shelf's tick: longer, cold, and it slides with the shelf
        float my = top + shelfPos * INDEX_STEP - TICK_UP;
        line.setColor(accent(g));
        c.drawRect(MX, my, MX + TICK_ON_W, my + 2, line);
        if (onRuler) drawRulerFocus(c, g, top + shelf * INDEX_STEP);
    }

    /** The keys are on the ruler: the open shelf's row is boxed in the accent. */
    void drawRulerFocus(Canvas c, float g, float y) {
        float l = MX - 12, r = NAME_X + inkWidth(cat, shelfName()) + 34, t = y - 26, b = y + 12;
        line.setColor(accent(g));
        c.drawRect(l, t, r, t + 1, line);
        c.drawRect(l, b, r, b + 1, line);
        c.drawRect(l, t, l + 1, b + 1, line);
        c.drawRect(r - 1, t, r, b + 1, line);
    }

    /** The band: the open shelf's page of cells, leaning back about its bottom centre. */
    void drawBand(Canvas c, Page p, float g, float dy) {
        int n = p.size();
        if (n == 0) return;
        int page = HomeLayout.pageOf(p.focus);
        c.save();
        c.translate(0, dy);
        cam.save();
        cam.setLocation(0, 0, CAM_Z);
        cam.rotateX(TILT);
        tilt.reset();
        cam.getMatrix(tilt);
        cam.restore();
        tilt.preTranslate(-BAND_CX, -BAND_BOTTOM);
        tilt.postTranslate(BAND_CX, BAND_BOTTOM);
        c.concat(tilt);
        drawFrame(c, g);
        drawBandLabel(c, p, g);
        for (int s = 0; s < HomeLayout.BAND_PER_PAGE; s++) {
            int e = HomeLayout.entryAt(page, s, n);
            if (e >= 0) drawCell(c, p, e, s, g);
        }
        drawFocus(c, p, g);
        c.restore();
    }

    /** Four L-shaped corners 22 px outside the band. */
    void drawFrame(Canvas c, float g) {
        float l = BAND_X - FRAME_OUT, t = BAND_Y - FRAME_OUT;
        float r = BAND_X + BAND_W + FRAME_OUT, b = BAND_BOTTOM + FRAME_OUT;
        line.setColor(ink(A_FRAME * g));
        c.drawRect(l, t, l + FRAME_ARM, t + 1, line);
        c.drawRect(l, t, l + 1, t + FRAME_ARM, line);
        c.drawRect(r - FRAME_ARM, t, r, t + 1, line);
        c.drawRect(r - 1, t, r, t + FRAME_ARM, line);
        c.drawRect(l, b - 1, l + FRAME_ARM, b, line);
        c.drawRect(l, b - FRAME_ARM, l + 1, b, line);
        c.drawRect(r - FRAME_ARM, b - 1, r, b, line);
        c.drawRect(r - 1, b - FRAME_ARM, r, b, line);
    }

    /** TERMINAL, the open shelf and its count above the band's left; the page above its right. */
    void drawBandLabel(Canvas c, Page p, float g) {
        bandLabel.setColor(ink(A_LABEL * g));
        c.drawText(TERMINAL, BAND_X - lsb(bandLabel, TERMINAL), LABEL_BASE, bandLabel);
        String sub = HomeLayout.shelfCount(shelfName(), p.size());
        bandSub.setColor(ink(A_SUB * g));
        float x = BAND_X + inkWidth(bandLabel, TERMINAL) + SUB_GAP;
        c.drawText(sub, x - lsb(bandSub, sub), LABEL_BASE, bandSub);
        int pages = HomeLayout.pages(p.size());
        if (pages > 1) {
            String at = HomeLayout.pageAt(HomeLayout.pageOf(p.focus), pages);
            c.drawText(at, BAND_X + BAND_W - inkWidth(bandSub, at) - lsb(bandSub, at), LABEL_BASE, bandSub);
        }
    }

    /** One cell: a 1 px outline, a breath of white, and the code, the ghost numeral, the name, English and state. */
    void drawCell(Canvas c, Page p, int e, int s, float g) {
        float x = HomeLayout.slotX(s), y = HomeLayout.slotY(s);
        float r = HomeLayout.slotR(s), b = HomeLayout.slotB(s);
        boolean big = HomeLayout.slotBig(s), wide = HomeLayout.slotWide(s), on = e == p.focus;
        fill.setColor(white((on ? CELL_ON_FILL : CELL_FILL) * g));
        c.drawRect(x, y, r, b, fill);
        if (!on) {                              // the focused cell's outline glides, drawn once below
            line.setColor(ink(CELL_INK * g));
            c.drawRect(x, y, r, y + 1, line);
            c.drawRect(x, b - 1, r, b, line);
            c.drawRect(x, y, x + 1, b, line);
            c.drawRect(r - 1, y, r, b, line);
        }
        c.save();
        c.clipRect(x, y, r, b);
        String label = e < p.labels.size() ? p.labels.get(e) : "";
        String no = String.format(Locale.US, "%02d", e + 1);
        code.setColor(ink((on ? A_CODE_ON : A_CODE) * g));
        c.drawText(no, x + CODE_X - lsb(code, no), y + CODE_DY, code);
        Paint gh = big ? ghostBig : ghost;
        gh.setColor(ink(A_GHOST * g));
        c.drawText(no, r - GHOST_IN - inkWidth(gh, no), b + (big ? GHOST_BIG_BELOW : GHOST_BELOW), gh);
        float max = HomeLayout.slotW(s) - TEXT_IN;
        Paint np = big ? nameBig : wide ? nameWide : name;
        String nm = fit(np, HomeLayout.name(label), max);
        np.setColor(ink((on ? 1f : A_NAME) * g));
        c.drawText(nm, x + TEXT_X - lsb(np, nm), b - (big ? NAME_BIG_UP : NAME_UP), np);
        Paint ep = big ? enBig : en;
        String es = fit(ep, HomeLayout.english(label, e < p.pkgs.size() ? p.pkgs.get(e) : null), max);
        ep.setColor(ink((on ? A_EN_ON : A_EN) * g));
        c.drawText(es, x + EN_X - lsb(ep, es), b - (big ? EN_BIG_UP : EN_UP), ep);
        drawState(c, p, e, big, on, x, b, max, g, label);
        c.restore();
    }

    /** The line at the bottom of a cell: 星仪's own state, else the entry's note, else what it is. */
    void drawState(Canvas c, Page p, int e, boolean big, boolean on, float x, float b, float max,
                   float g, String label) {
        String st = HomeLayout.xingyi(label) ? HomeLayout.agentState(agent)
                : e < p.kinds.size() ? p.kinds.get(e) : "";
        if (st.isEmpty()) return;
        Paint sp = big ? stateBig : state;
        st = fit(sp, st, max);
        float a = on ? A_STATE_ON : big ? A_STATE_BIG : A_STATE;
        sp.setColor(ink(a * g));
        c.drawText(st, x + TEXT_X - lsb(sp, st), b - (big ? STATE_BIG_UP : STATE_UP), sp);
    }

    /** The focus: a 1 px outline and four accent corners, gliding from cell to cell. */
    void drawFocus(Canvas c, Page p, float g) {
        float[] q = p.rect;
        focus.setColor(ink(CELL_ON_INK * g));
        c.drawRect(q[0], q[1], q[2], q[1] + 1, focus);
        c.drawRect(q[0], q[3] - 1, q[2], q[3], focus);
        c.drawRect(q[0], q[1], q[0] + 1, q[3], focus);
        c.drawRect(q[2] - 1, q[1], q[2], q[3], focus);
        focus.setColor(accent(g));
        c.drawRect(q[0], q[1], q[0] + CORNER, q[1] + CORNER_W, focus);
        c.drawRect(q[0], q[1], q[0] + CORNER_W, q[1] + CORNER, focus);
        c.drawRect(q[2] - CORNER, q[1], q[2], q[1] + CORNER_W, focus);
        c.drawRect(q[2] - CORNER_W, q[1], q[2], q[1] + CORNER, focus);
        c.drawRect(q[0], q[3] - CORNER_W, q[0] + CORNER, q[3], focus);
        c.drawRect(q[0], q[3] - CORNER, q[0] + CORNER_W, q[3], focus);
        c.drawRect(q[2] - CORNER, q[3] - CORNER_W, q[2], q[3], focus);
        c.drawRect(q[2] - CORNER_W, q[3] - CORNER, q[2], q[3], focus);
    }
}
