package xyz.erapi0neer.xingyi.agent.tv;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** The data answer drawn on the panel under the steps: a day's weather with its hours, a few quotes, a list.
 * Everything is measured and built in {@link #of} — {@link #draw} only draws, because the panel is rendered in
 * software on a 4×A35 TV. White at an alpha only, sizes in units × d, never wider than the inner width.
 * A card that is not exactly what {@code of} understands yields null and the panel looks as it always did. */
final class HudCards {
    private static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final float d;
    private final int inner;
    private final String kind;
    private float height;

    // weather: the place · day line, the temperatures with the sky and the rain beside them, then the hours
    private StaticLayout head;
    private String loText = "", hiText = "";
    private float dashX, hiX, dashY;           // the drawn line between the two temperatures
    private String sky = "", rain = "";
    private float skyX, rainX, bandTop, bandBase, skyBase;
    private boolean hasHours;
    private float hoursTop, bandHourBottom, hourBase, pctBase;
    /** Whether any hour is worth a rain bar: under 30 % they read as stray grey squares (2026-11-07 render). */
    private boolean hasBar;
    private final Path curve = new Path();
    private float[] hx = new float[0], hy = new float[0];
    private int[] chance = new int[0];
    private String[] tText = new String[0], hText = new String[0], pText = new String[0];
    private float[] tW = new float[0], hW = new float[0], pW = new float[0];

    // stocks: one row each, 52 units tall, a hairline between them
    private final List<Row> rows = new ArrayList<>();
    // list: the title, then the items with their number in a 28-unit column
    private StaticLayout listTitle;
    private final List<Item> items = new ArrayList<>();

    private final TextPaint pHead, pTemps, pSky, pRain, pHourT, pHourH, pHourP, pName, pPct, pPrice, pUnit, pWhen,
            pListTitle, pNum, pItem, pMeta;
    private final Paint pLine, pDot, pBar, pDash, pMark, pHair, pRule;
    private final Rect bounds = new Rect();

    /** The rain chance below which an hour gets no bar: smaller ones are 1–4 units tall and read as stray squares. */
    private static final int RAIN_BAR = 30;

    /** Lays the card out for the inner width (px); null when the card is absent or malformed. */
    static HudCards of(JSONObject card, float d, int innerWidth) {
        if (card == null) return null;
        String type = str(card, "type");
        boolean weather = "weather".equals(type), stocks = "stocks".equals(type), list = "list".equals(type);
        if (!weather && !stocks && !list) return null;
        HudCards h = new HudCards(d, innerWidth, type);
        try {
            boolean ok = weather ? h.weather(card) : stocks ? h.stocks(card) : h.list(card);
            return ok ? h : null;
        } catch (Exception e) {
            return null; // whatever the card was, it never reaches the screen
        }
    }

    /** Height in px. */
    float height() { return height; }

    private HudCards(float d, int inner, String kind) {
        this.d = d; this.inner = inner; this.kind = kind;
        pHead = text(REGULAR, 12, 140);
        pTemps = text(LIGHT, 38, 245);
        pSky = text(REGULAR, 15, 230);
        pRain = text(REGULAR, 12, 150);
        pHourT = text(REGULAR, 10, 150);
        pHourH = text(REGULAR, 10, 110);
        pHourP = text(REGULAR, 9, 90);
        pName = text(MEDIUM, 15, 240);
        pPct = text(REGULAR, 12.5f, 200);
        pPrice = text(LIGHT, 24, 250);
        pUnit = text(REGULAR, 11, 140);
        pWhen = text(REGULAR, 11, 110);
        pListTitle = text(REGULAR, 11.5f, 130);
        pListTitle.setLetterSpacing(0.08f);
        pNum = text(LIGHT, 13, 120);
        pItem = text(REGULAR, 14, 235);
        pMeta = text(REGULAR, 11.5f, 120);
        pLine = hair(200, 1.3f);
        pLine.setStrokeJoin(Paint.Join.ROUND);
        pLine.setStrokeCap(Paint.Cap.ROUND);
        pDot = fill(235);
        pBar = fill(46);
        pDash = hair(200, 1.2f);
        pDash.setStrokeCap(Paint.Cap.ROUND);
        pMark = fill(200);
        pHair = hair(200, 1.2f);
        pHair.setStrokeCap(Paint.Cap.ROUND);
        pRule = hair(26, 0.75f);
    }

    private TextPaint text(Typeface tf, float size, int alpha) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        p.setTextSize(size * d);
        p.setColor(Color.argb(alpha, 255, 255, 255));
        return p;
    }

    private Paint fill(int alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.argb(alpha, 255, 255, 255));
        return p;
    }

    private Paint hair(int alpha, float width) {
        Paint p = fill(alpha);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, width * d));
        return p;
    }

    private StaticLayout layout(String s, TextPaint paint, int w, int lines) {
        return StaticLayout.Builder.obtain(s, 0, s.length(), paint, Math.max(10, w))
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.12f)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
    }

    /** A string field, "" for a missing or JSON-null one: Android's org.json gives "null" for a JSON null. */
    private static String str(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        String s = o.optString(key, "");
        return "null".equals(s) ? "" : s;
    }

    /** A number field; NaN when it is missing or not a number. */
    private static double num(JSONObject o, String key) {
        Object v = o == null ? null : o.opt(key);
        return v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
    }

    // ---- weather ----

    private boolean weather(JSONObject o) {
        String place = str(o, "place"), day = str(o, "day");
        double lo = num(o, "lo"), hi = num(o, "hi");
        sky = str(o, "sky");
        if (place.isEmpty() || day.isEmpty() || sky.isEmpty() || Double.isNaN(lo) || Double.isNaN(hi)) return false;
        head = layout(place + " · " + day, pHead, inner, 1);
        bandTop = head.getHeight() + 8 * d;
        bandBase = bandTop + 29 * d;             // the temperatures, and the rain chance beside them
        skyBase = bandTop + 13 * d;
        double r = num(o, "rain");
        rain = Double.isNaN(r) ? "" : "降水 " + Math.round(r) + "%";
        skyX = inner - pSky.measureText(sky);
        rainX = inner - pRain.measureText(rain);
        // 「14–18°」: the en dash is too long next to the digits, so they are drawn apart with a thin line between
        loText = Math.round(lo) + "°";
        hiText = Math.round(hi) + "°";
        dashX = pTemps.measureText(loText) + 6 * d;
        hiX = dashX + 10 * d + 6 * d;
        // measured on the digits alone: the ° sits higher and would pull the line up
        String digits = loText.substring(0, loText.length() - 1);
        pTemps.getTextBounds(digits, 0, digits.length(), bounds);
        dashY = bandBase + (bounds.top + bounds.bottom) / 2f;
        float room = Math.max(10 * d, Math.min(skyX, rainX) - 10 * d);
        hiText = cut(hiText, pTemps, room - hiX);
        hours(o);
        height = hasHours ? hoursTop + (hasBar ? 70 : 58) * d : bandTop + 38 * d;
        return true;
    }

    /** The hours strip: the temperatures as a polyline in a 22-unit band, the rain as bars behind it. Points that
     * are not a whole hour with a temperature are left out; fewer than two left and there is no strip. */
    private void hours(JSONObject o) {
        JSONArray a = o.optJSONArray("hours");
        if (a == null) return;
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < a.length() && pts.size() < 8; i++) {
            JSONObject e = a.optJSONObject(i);
            double h = num(e, "h"), t = num(e, "t");
            if (Double.isNaN(h) || h < 0 || h > 23 || h != Math.rint(h) || Double.isNaN(t)) continue;
            double p = num(e, "p");
            pts.add(new double[]{h, t, Double.isNaN(p) ? 0 : Math.min(100, Math.max(0, p))});
        }
        int n = pts.size();
        if (n < 2) return;
        hasHours = true;
        hoursTop = bandTop + 38 * d + 12 * d;
        bandHourBottom = hoursTop + 38 * d;   // the 22-unit band the temperatures are mapped into
        hourBase = hoursTop + 51 * d;
        pctBase = hourBase + 13 * d;          // the rain chance under the hour labels, only where there is a bar
        double min = pts.get(0)[1], max = min;
        for (double[] p : pts) { min = Math.min(min, p[1]); max = Math.max(max, p[1]); }
        hx = new float[n]; hy = new float[n]; chance = new int[n];
        tText = new String[n]; hText = new String[n]; pText = new String[n];
        tW = new float[n]; hW = new float[n]; pW = new float[n];
        float span = inner - 16 * d;
        for (int i = 0; i < n; i++) {
            double[] p = pts.get(i);
            hx[i] = 8 * d + span * i / (n - 1);
            hy[i] = (float) (bandHourBottom - (max > min ? (p[1] - min) / (max - min) : 0.5) * 22 * d);
            chance[i] = (int) Math.round(p[2]);
            if (chance[i] >= RAIN_BAR) hasBar = true;
            tText[i] = Math.round(p[1]) + "°";
            hText[i] = Math.round(p[0]) + "时";
            pText[i] = chance[i] + "%";
            tW[i] = pHourT.measureText(tText[i]);
            hW[i] = pHourH.measureText(hText[i]);
            pW[i] = pHourP.measureText(pText[i]);
            if (i == 0) curve.moveTo(hx[i], hy[i]); else curve.lineTo(hx[i], hy[i]);
        }
    }

    // ---- stocks ----

    private boolean stocks(JSONObject o) {
        JSONArray a = o.optJSONArray("rows");
        if (a == null || a.length() == 0) return false;
        for (int i = 0; i < a.length() && rows.size() < 4; i++) {
            Row r = row(a.optJSONObject(i), rows.size());
            if (r == null) return false; // one bad row and the whole card is not drawn
            rows.add(r);
        }
        height = 52 * d * rows.size();
        return !rows.isEmpty();
    }

    private Row row(JSONObject e, int index) {
        String name = str(e, "name"), price = str(e, "price");
        double v = pct(str(e, "pct"));
        if (name.isEmpty() || price.isEmpty() || Double.isNaN(v)) return null;
        Row r = new Row();
        r.top = 52 * d * index;
        r.nameBase = r.top + 20 * d;
        r.priceBase = r.top + 24 * d;
        r.footBase = r.top + 41 * d;
        r.price = price;
        r.unit = cut(str(e, "unit"), pUnit, 70 * d);
        r.when = str(e, "when");
        float unitW = pUnit.measureText(r.unit);
        float space = r.unit.isEmpty() ? 0 : pUnit.measureText(" ");
        // the price keeps clear of the left edge, so a name always has room and nothing leaves the panel
        r.price = cut(price, pPrice, inner - unitW - space - 28 * d);
        r.unitX = inner - unitW;
        r.priceX = r.unitX - space - pPrice.measureText(r.price);
        r.pct = String.format(Locale.US, "%.2f%%", Math.abs(v));
        // the closing time sits on the percentage's line: it may not run into it
        r.when = cut(r.when, pWhen, inner - 20 * d - pPct.measureText(r.pct));
        r.whenX = inner - pWhen.measureText(r.when);
        r.flat = v == 0;
        r.mark = mark(r.footBase, v);
        float room = Math.max(20 * d, r.priceX - 8 * d);
        r.name = TextUtils.ellipsize(name, pName, room, TextUtils.TruncateAt.END).toString();
        return r;
    }

    /** A string that fits in the room it has, with an ellipsis. */
    private String cut(String s, TextPaint paint, float room) {
        return paint.measureText(s) <= room ? s : TextUtils.ellipsize(s, paint, Math.max(10 * d, room),
                TextUtils.TruncateAt.END).toString();
    }

    /** The little arrow before the percentage: up, down, or a 6-unit dash for flat. */
    private Path mark(float base, double v) {
        float cy = base - 4.5f * d, w = 6 * d, h = 5 * d;
        Path p = new Path();
        if (v == 0) {
            p.moveTo(0, cy);
            p.lineTo(w, cy);
            return p;
        }
        float tip = v > 0 ? cy - h / 2 : cy + h / 2;
        float foot = v > 0 ? cy + h / 2 : cy - h / 2;
        p.moveTo(0, foot);
        p.lineTo(w, foot);
        p.lineTo(w / 2, tip);
        p.close();
        return p;
    }

    /** "-10.46" → -10.46; a trailing % and a leading + are allowed, anything else is NaN. */
    private static double pct(String s) {
        String t = s.trim();
        if (t.endsWith("%")) t = t.substring(0, t.length() - 1).trim();
        if (t.startsWith("+")) t = t.substring(1);
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // ---- list ----

    private boolean list(JSONObject o) {
        String title = str(o, "title");
        JSONArray a = o.optJSONArray("items");
        if (title.isEmpty() || a == null || a.length() == 0) return false;
        listTitle = layout(title, pListTitle, inner, 1);
        int column = inner - Math.round(28 * d);
        float y = listTitle.getHeight() + 8 * d;
        for (int i = 0; i < a.length() && items.size() < 5; i++) {
            JSONObject e = a.optJSONObject(i);
            String t = str(e, "title");
            if (t.isEmpty()) return false;
            Item it = new Item();
            it.num = String.format(Locale.US, "%02d", items.size() + 1);
            it.title = layout(t, pItem, column, 1);
            String meta = str(e, "meta");
            it.meta = meta.isEmpty() ? null : layout(meta, pMeta, column, 1);
            it.top = y;
            it.base = y - it.title.getLineAscent(0);
            y += it.title.getHeight() + (it.meta == null ? 0 : it.meta.getHeight()) + 8 * d;
            items.add(it);
        }
        height = y - 8 * d;
        return !items.isEmpty();
    }

    private static final class Row {
        String name, price, unit, when, pct;
        float top, nameBase, priceBase, footBase, priceX, unitX, whenX;
        boolean flat;
        Path mark;
    }

    private static final class Item {
        String num;
        StaticLayout title, meta;
        float top, base;
    }

    /** Draws with its top-left at (x, y). */
    void draw(Canvas c, float x, float y) {
        if ("weather".equals(kind)) drawWeather(c, x, y);
        else if ("stocks".equals(kind)) drawStocks(c, x, y);
        else drawList(c, x, y);
    }

    private void drawWeather(Canvas c, float x, float y) {
        c.save(); c.translate(x, y); head.draw(c); c.restore();
        c.drawText(loText, x, y + bandBase, pTemps);
        c.drawLine(x + dashX, y + dashY, x + dashX + 10 * d, y + dashY, pDash);
        if (!hiText.isEmpty()) c.drawText(hiText, x + hiX, y + bandBase, pTemps);
        c.drawText(sky, x + skyX, y + skyBase, pSky);
        if (!rain.isEmpty()) c.drawText(rain, x + rainX, y + bandBase, pRain);
        if (!hasHours) return;
        for (int i = 0; i < hx.length; i++) {
            if (chance[i] < RAIN_BAR) continue;
            float top = bandHourBottom - chance[i] / 100f * 22 * d;
            c.drawRect(x + hx[i] - 1.5f * d, y + top, x + hx[i] + 1.5f * d, y + bandHourBottom, pBar);
        }
        c.save(); c.translate(x, y); c.drawPath(curve, pLine); c.restore();
        for (int i = 0; i < hx.length; i++) {
            c.drawCircle(x + hx[i], y + hy[i], 1.8f * d, pDot);
            c.drawText(tText[i], x + centre(hx[i], tW[i]), y + hy[i] - 4 * d, pHourT);
            c.drawText(hText[i], x + centre(hx[i], hW[i]), y + hourBase, pHourH);
            if (chance[i] >= RAIN_BAR) c.drawText(pText[i], x + centre(hx[i], pW[i]), y + pctBase, pHourP);
        }
    }

    private void drawStocks(Canvas c, float x, float y) {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (i > 0) c.drawLine(x, y + r.top, x + inner, y + r.top, pRule);
            c.drawText(r.name, x, y + r.nameBase, pName);
            c.save(); c.translate(x, y); c.drawPath(r.mark, r.flat ? pHair : pMark); c.restore();
            c.drawText(r.pct, x + 12 * d, y + r.footBase, pPct);   // the 6-unit arrow, then 6 units of air
            c.drawText(r.price, x + r.priceX, y + r.priceBase, pPrice);
            if (!r.unit.isEmpty()) c.drawText(r.unit, x + r.unitX, y + r.priceBase, pUnit);
            if (!r.when.isEmpty()) c.drawText(r.when, x + r.whenX, y + r.footBase, pWhen);
        }
    }

    private void drawList(Canvas c, float x, float y) {
        c.save(); c.translate(x, y); listTitle.draw(c); c.restore();
        for (Item it : items) {
            c.drawText(it.num, x, y + it.base, pNum);
            c.save(); c.translate(x + 28 * d, y + it.top); it.title.draw(c); c.restore();
            if (it.meta == null) continue;
            c.save(); c.translate(x + 28 * d, y + it.top + it.title.getHeight()); it.meta.draw(c); c.restore();
        }
    }

    /** A label centred on a point, kept inside the inner width. */
    private float centre(float at, float w) { return Math.min(inner - w, Math.max(0, at - w / 2)); }
}
