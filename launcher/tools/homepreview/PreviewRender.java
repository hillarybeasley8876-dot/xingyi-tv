package xyz.erapi0neer.orrery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Lead tool (2026-11-08): the home screen's type layer from a source tree, drawn off-screen into a transparent PNG
 * (1920×1080) in this app's external files, without touching the installed home screen. The PC lays it over a still
 * of the flow. Shelves and texts are the TV's of that night; the state per file is in SAMPLES. */
public final class PreviewRender extends BroadcastReceiver {
    static final String[][] SHELVES = {
            {"常用", "星仪电视助手", "网易云音乐", "云视听极光", "哔哩哔哩", "信号源", "无线投屏", "全部应用", "设置"},
            {"影音", "哔哩哔哩", "云视听极光", "网易云音乐", "相册"},
            {"信号源", "HDMI 1", "HDMI 2", "HDMI 3", "无线投屏"},
            {"工具", "TV Bro", "电视管家", "U盘 / 媒体", "应用商店", "设置"},
            {"全部", "星仪电视助手", "网易云音乐", "云视听极光", "哔哩哔哩", "TV Bro", "夸克网盘", "相册", "电视管家", "无线投屏",
                    "U盘 / 媒体", "应用商店", "HDMI 1", "HDMI 2", "HDMI 3", "设置"}};
    static final String[] KINDS = {"语音助手", "音乐　·　上次在听《江南》林俊杰", "影视", "视频", "浏览器　·　新安装", "网盘",
            "照片", "清理", "投屏", "文件", "下载应用", "信号源", "信号源", "信号源", "系统"};
    /** file name, open shelf, focus */
    static final Object[][] SAMPLES = {{"home-1", 0, 0}, {"home-3", 0, 2}, {"home-all", 4, 4}, {"home-tools", 3, 0}};

    @Override public void onReceive(Context c, Intent intent) {
        StringBuilder out = new StringBuilder();
        try {
            Typeface inter = variable(c, "fonts/Inter-Variable.ttf", "'wght' 200, 'opsz' 32");
            Typeface mono = variable(c, "fonts/JetBrainsMono.ttf", "'wght' 400");
            Typeface light = Typeface.createFromAsset(c.getAssets(), "fonts/MiSans-Light.otf");
            Typeface cjk;
            try { cjk = Typeface.createFromFile("/system/fonts/MiSans-Regular.otf"); } catch (Exception e) { cjk = Typeface.DEFAULT; }
            File dir = c.getExternalFilesDir(null);
            // the real answer 星仪's bridge gives the home screen (the same call HomeInfo makes), else the design's text
            String[] info = {"农历九月三十　·　多云 13° – 19°", "江南", "林俊杰", "多云 13° – 19°", "待命"};
            try {
                android.os.Bundle b = c.getContentResolver().call(android.net.Uri.parse("content://xyz.erapi0neer.xiaoaibridge.agent"), "home", null, null);
                String json = b == null ? null : b.getString("json");
                out.append("home=").append(json).append(';');
                if (json != null) {
                    org.json.JSONObject o = new org.json.JSONObject(json);
                    String lunar = o.optString("lunar"), weather = o.optString("weather");
                    info[0] = lunar.isEmpty() ? weather : weather.isEmpty() ? lunar : lunar + "　·　" + weather;
                    info[3] = weather;
                    info[4] = o.optString("agent");
                    org.json.JSONObject now = o.optJSONObject("now");
                    info[1] = now == null ? "" : now.optString("title");
                    info[2] = now == null ? "" : now.optString("artist");
                }
            } catch (Exception e) {
                out.append("home error: ").append(e).append(';');
            }
            for (Object[] s : SAMPLES) {
                HomeUi ui = new HomeUi(c, inter, mono, light, cjk);
                int open = (Integer) s[1], focus = (Integer) s[2];
                List<String> names = new ArrayList<>();
                List<Integer> counts = new ArrayList<>();
                for (String[] sh : SHELVES) { names.add(sh[0]); counts.add(sh.length - 1); }
                List<String> labels = new ArrayList<>(Arrays.asList(SHELVES[open]).subList(1, SHELVES[open].length));
                List<String> kinds = new ArrayList<>();
                List<Boolean> fresh = new ArrayList<>();
                for (String l : labels) {
                    int k = Arrays.asList(SHELVES[4]).indexOf(l) - 1;
                    kinds.add(k >= 0 && k < KINDS.length ? KINDS[k] : "信号源".equals(l) ? "HDMI 1　·　2　·　3"
                            : "全部应用".equals(l) ? "15 个　·　1 个新装" : "无线投屏".equals(l) ? "投屏" : "设置".equals(l) ? "系统" : "应用");
                    fresh.add("TV Bro".equals(l));
                }
                ui.setShelves(names, counts, open, labels, kinds, fresh, focus);
                ui.tick();
                // the TV's own path: the weather on its own for the strip, and 星仪's word (newer trees only)
                try {
                    HomeUi.class.getDeclaredMethod("setInfo", String.class, String.class, String.class, String.class)
                            .invoke(ui, info[0], info[3], info[1], info[2]);
                    HomeUi.class.getDeclaredMethod("setAgent", String.class).invoke(ui, info[4]);
                } catch (NoSuchMethodException old) {
                    ui.setInfo(info[0], info[1], info[2]);
                }
                ui.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
                ui.layout(0, 0, 1920, 1080);
                Bitmap b = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
                ui.draw(new Canvas(b));
                File f = new File(dir, s[0] + ".png");
                try (FileOutputStream o = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
                out.append(f.getName()).append(';');
            }
        } catch (Throwable e) {
            out.append("error: ").append(e);
        }
        if (isOrderedBroadcast()) setResultData(out.toString());
    }

    static Typeface variable(Context c, String asset, String axes) {
        try {
            Typeface t = new Typeface.Builder(c.getAssets(), asset).setFontVariationSettings(axes).build();
            if (t != null) return t;
        } catch (Exception ignored) { }
        return Typeface.createFromAsset(c.getAssets(), asset);
    }
}
