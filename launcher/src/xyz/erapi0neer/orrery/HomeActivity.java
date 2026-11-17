package xyz.erapi0neer.orrery;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Orrery 3 home: a live particle fluid (FlowRenderer) under a type layer (HomeUi).
 * The entries sit on shelves (Shelves: 常用, 影音, 信号源, 工具, 全部) as a band of cells at the bottom centre: the arrows
 * walk the band (LEFT off its first page goes to the shelf ruler, where UP/DOWN open the shelf above / below and RIGHT
 * comes back), OK opens, MENU opens settings, HOME comes back.
 * The flow renders only while this screen is resumed and the TV screen is on.
 */
public class HomeActivity extends Activity implements HomeInfo.Callback {

    static final String SYSUI = "com.xiaomi.mitv.systemui";
    static final String PREFS = "home", LAST = "last", LAST_SHELF = "last_shelf", LAUNCHES = "n:";
    static final String ACTION_SNAPSHOT = "xyz.erapi0neer.orrery.SNAPSHOT";
    static final String ACTION_RELOAD = "xyz.erapi0neer.orrery.RELOAD";
    static final String ACTION_FOCUS = "xyz.erapi0neer.orrery.FOCUS";
    static final String ACTION_UI = "xyz.erapi0neer.orrery.UI";

    /** Never on the axis: ourselves, our test tools, system plumbing (settings has its own entry). */
    static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "xyz.erapi0neer.orrery", "xyz.erapi0neer.fluidlab", "xyz.erapi0neer.xingyi.voicecheck",
            "com.spocky.projengmenu", "com.xiaomi.voicecontrol", "com.xiaomi.mitv.settings"));
    /** The daily three lead; the TV's own tools follow the HDMI inputs. Anything else sits between. */
    static final List<String> LEAD = Arrays.asList("xyz.erapi0neer.xingyi", "com.netease.cloudmusic.tv", "com.ktcp.video");
    static final List<String> TOOLS = Arrays.asList("com.xiaomi.mitv.smartshare", "com.mitv.gallery", "com.xiaomi.mitv.tvmanager");

    GLSurfaceView glView;
    FlowRenderer flow;
    HomeUi ui;
    List<Entry> entries = new ArrayList<>();
    List<Shelves.Shelf> shelves = new ArrayList<>();
    /** The open shelf, and the focus on it. */
    int shelf, focus;
    /** LEFT off the band's first page leaves the keys on the shelf ruler. */
    boolean onRuler;
    /** Where the focus was on each shelf (by name), so coming back to a shelf lands where it was. */
    final Map<String, String> shelfFocus = new HashMap<>();
    /** What 星仪's bridge last said about each app, for the line under an entry's name. */
    final Map<String, String> notes = new HashMap<>();
    boolean resumed, rendering;
    BroadcastReceiver tick, pkgs, screen, debug;
    final Handler main = new Handler(Looper.getMainLooper());
    /** Paces the flow: one frame every flow.divider vsyncs (30 fps), timed by the vsync itself. */
    final Choreographer.FrameCallback vsync = new Choreographer.FrameCallback() {
        long n;
        public void doFrame(long t) {
            if (!rendering) return;
            if (n++ % flow.divider == 0) { flow.vsyncNs = t; glView.requestRender(); }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };
    final Runnable watch = new Runnable() {
        public void run() { updateRendering(); if (resumed) main.postDelayed(this, 3000); }
    };
    /** 星仪's bridge is asked again every minute while this screen is up. */
    final Runnable askInfo = new Runnable() {
        public void run() {
            HomeInfo.fetch(HomeActivity.this, HomeActivity.this);
            if (resumed) main.postDelayed(this, 60000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Typeface inter = variable("fonts/Inter-Variable.ttf", "'wght' 200, 'opsz' 32");
        Typeface mono = variable("fonts/JetBrainsMono.ttf", "'wght' 400");
        Typeface light = asset("fonts/MiSans-Light.otf");
        Typeface cjk = sysFont("/system/fonts/MiSans-Regular.otf");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff000000);
        setContentView(root);
        immersive();

        flow = new FlowRenderer(this);
        glView = new GLSurfaceView(this);
        glView.getHolder().setFormat(PixelFormat.RGBX_8888);
        glView.setEGLContextClientVersion(3);
        glView.setEGLConfigChooser(8, 8, 8, 0, 0, 0);
        glView.setPreserveEGLContextOnPause(true);
        glView.setRenderer(flow);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        glView.onPause();               // starts only when resumed with the screen on
        root.addView(glView, new FrameLayout.LayoutParams(-1, -1));

        ui = new HomeUi(this, inter, mono, light, cjk);
        root.addView(ui, new FrameLayout.LayoutParams(-1, -1));
        root.setFocusable(true);
        root.requestFocus();

        // adb only: these need android.permission.DUMP, which the shell has and apps do not
        debug = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (ACTION_RELOAD.equals(a)) flow.reloadLook = true;
                else if (ACTION_FOCUS.equals(a)) {
                    if (i.hasExtra("shelf")) setShelf(i.getIntExtra("shelf", 0), 1);
                    setFocus(i.getIntExtra("index", 0), 1);
                }
                else if (ACTION_UI.equals(a)) ui.setVisibility(i.getBooleanExtra("show", true) ? View.VISIBLE : View.GONE);
                else if (ACTION_SNAPSHOT.equals(a)) {
                    String name = i.getStringExtra("name");
                    flow.sink = HomeActivity.this::composeSnapshot;
                    flow.snapName = name != null ? name : "home.png";
                }
            }
        };
        IntentFilter df = new IntentFilter(ACTION_SNAPSHOT);
        df.addAction(ACTION_RELOAD);
        df.addAction(ACTION_FOCUS);
        df.addAction(ACTION_UI);
        registerReceiver(debug, df, "android.permission.DUMP", null);

        screen = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) { updateRendering(); }
        };
        IntentFilter sf = new IntentFilter(Intent.ACTION_SCREEN_ON);
        sf.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, sf);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        shelfFocus.put(prefs.getString(LAST_SHELF, "常用"), prefs.getString(LAST, ""));
        rebuild(prefs.getString(LAST_SHELF, "常用"));
    }

    // ------------------------------------------------------------------ entries
    static final class Entry {
        final String id, label;
        final Intent intent;
        /** The app's package, when it is one: what 星仪's notes are keyed by. */
        final String pkg;
        /** What the shelves sort by. */
        Shelves.Item item;
        Entry(String id, String label, Intent intent) { this(id, label, intent, null, "other"); }
        Entry(String id, String label, Intent intent, String pkg, String group) {
            this.id = id; this.label = label; this.intent = intent; this.pkg = pkg;
            this.item = new Shelves.Item(id, label, pkg, group, -1, 0);
        }
        String key() { return id; }
    }

    /** An app entry, with its store category and install time for the shelves. */
    Entry app(String pkg, String label, Intent intent, String group) {
        Entry e = new Entry(pkg, label, intent, pkg, group);
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            int cat = android.os.Build.VERSION.SDK_INT >= 26 ? pi.applicationInfo.category : -1;
            e.item = new Shelves.Item(pkg, label, pkg, group, cat, pi.firstInstallTime);
        } catch (Exception ignored) { }
        return e;
    }

    List<Entry> entries() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = new ArrayList<>();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        found.addAll(pm.queryIntentActivities(q, 0));
        Intent q2 = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        found.addAll(pm.queryIntentActivities(q2, 0));
        Set<String> seen = new HashSet<>();
        List<Entry> lead = new ArrayList<>(), others = new ArrayList<>(), tools = new ArrayList<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (HIDDEN.contains(pkg) || !seen.add(pkg)) continue;
            Intent li = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (li == null) li = pm.getLaunchIntentForPackage(pkg);
            if (li == null) continue;
            String group = LEAD.contains(pkg) ? "lead" : TOOLS.contains(pkg) ? "tool" : "other";
            Entry e = app(pkg, String.valueOf(ri.loadLabel(pm)).trim(), li, group);
            if (LEAD.contains(pkg)) lead.add(e);
            else if (TOOLS.contains(pkg)) tools.add(e);
            else others.add(e);
        }
        Collections.sort(lead, (a, b) -> LEAD.indexOf(a.id) - LEAD.indexOf(b.id));
        Collections.sort(tools, (a, b) -> TOOLS.indexOf(a.id) - TOOLS.indexOf(b.id));
        final Collator zh = Collator.getInstance(Locale.CHINA);
        Collections.sort(others, (a, b) -> zh.compare(a.label, b.label));

        List<Entry> out = new ArrayList<>(lead);
        out.addAll(others);
        for (int k = 1; k <= 3; k++) out.add(new Entry("hdmi" + k, "HDMI " + k, hdmi(22 + k), null, "hdmi"));
        out.addAll(tools);
        // Xiaomi system apps that only expose non-LAUNCHER entry points
        addComponent(out, "应用商店", "com.xiaomi.mitv.appstore", "com.xiaomi.mitv.appstore.MainActivity", "store");
        addComponent(out, "U盘 / 媒体", "com.xiaomi.mitv.mediaexplorer", "com.xiaomi.mitv.mediaexplorer.NewScraperMainEntryActivity", "media");
        out.add(new Entry("settings", "设置", settings(), null, "settings"));
        return out;
    }

    List<String> labels() {
        List<String> l = new ArrayList<>();
        for (Entry e : items()) l.add(e.label);
        return l;
    }

    List<String> kinds() {
        List<String> l = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Entry e : items()) l.add(HomeLayout.stateLine(Shelves.kind(e.item, now), note(e)));
        return l;
    }

    /** The package behind each entry (null for the HDMI inputs), for the English line in its cell. */
    List<String> pkgs() {
        List<String> l = new ArrayList<>();
        for (Entry e : items()) l.add(e.pkg);
        return l;
    }

    /** What 星仪 says about an app, when it says anything about it. */
    String note(Entry e) {
        if (e.pkg == null) return "";
        String n = notes.get(e.pkg);
        return n == null ? "" : n;
    }

    /** The bridge answered: the third line, the strip and 星仪's own word go up, new notes change the state lines. */
    @Override
    public void onInfo(HomeInfo info) {
        ui.setInfo(HomeLayout.infoLine(info.lunar, info.weather), info.weather,
                info.playing ? info.title : "", info.playing ? info.artist : "");
        ui.setAgent(info.agent);
        if (!info.notes.equals(notes)) {
            notes.clear();
            notes.putAll(info.notes);
            pushToUi();                     // the state lines changed; the shelf and the focus stay where they are
        }
    }

    List<Boolean> fresh() {
        List<Boolean> l = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Entry e : items()) l.add(Shelves.isNew(e.item, now));
        return l;
    }

    void addComponent(List<Entry> out, String label, String pkg, String cls, String group) {
        Intent i = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(pkg, cls));
        if (getPackageManager().resolveActivity(i, 0) != null) out.add(new Entry(pkg, label, i, pkg, group));
    }

    static Intent settings() {
        return new Intent().setComponent(new ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
    }

    static Intent hdmi(int input) {
        Intent i = new Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY");
        i.setComponent(new ComponentName("com.xiaomi.mitv.tvplayer", "com.xiaomi.mitv.tvplayer.ExternalSourceActivity"));
        i.putExtra("input", input);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return i;
    }

    void refreshEntries() {
        String name = shelves.isEmpty() ? "常用" : shelves.get(Math.min(shelf, shelves.size() - 1)).name;
        List<Entry> list = items();
        if (!list.isEmpty() && focus < list.size()) shelfFocus.put(name, list.get(focus).key());
        rebuild(name);
    }

    /** The shelf open now (empty when nothing is installed at all). */
    List<Entry> items() {
        List<Entry> out = new ArrayList<>();
        if (shelf < 0 || shelf >= shelves.size()) return out;
        for (Shelves.Item it : shelves.get(shelf).items) out.add(entry(it));
        return out;
    }

    /** The entry behind one of a shelf's items. The shelf-openers (常用's 信号源, 全部应用) are made here: no app to launch. */
    Entry entry(Shelves.Item it) {
        for (Entry e : entries) if (e.item == it) return e;
        Entry e = new Entry(it.key, it.label, null);
        e.item = it;
        return e;
    }

    /** Entries and shelves read again; the shelf by name and its focus by key stay where they were. */
    void rebuild(String shelfName) {
        entries = entries();
        List<Shelves.Item> all = new ArrayList<>();
        for (Entry e : entries) all.add(e.item);
        shelves = Shelves.build(all, launches(), System.currentTimeMillis());
        shelf = 0;
        for (int k = 0; k < shelves.size(); k++) if (shelves.get(k).name.equals(shelfName)) shelf = k;
        focus = indexOf(items(), shelfFocus.get(shelfName));
        pushToUi();
    }

    static int indexOf(List<Entry> list, String key) {
        for (int k = 0; k < list.size(); k++) if (list.get(k).key().equals(key)) return k;
        return 0;
    }

    void pushToUi() {
        List<String> names = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (Shelves.Shelf s : shelves) { names.add(s.name); counts.add(s.items.size()); }
        ui.setPackages(pkgs());
        ui.setShelves(names, counts, shelf, labels(), kinds(), fresh(), focus);
        flow.focusTarget = focusValue(focus);
    }

    Map<String, Integer> launches() {
        Map<String, Integer> m = new HashMap<>();
        for (Map.Entry<String, ?> e : getSharedPreferences(PREFS, MODE_PRIVATE).getAll().entrySet())
            if (e.getKey().startsWith(LAUNCHES) && e.getValue() instanceof Integer) m.put(e.getKey().substring(LAUNCHES.length()), (Integer) e.getValue());
        return m;
    }

    /** The camera yaws with the focus, centred on the middle of the axis. */
    float focusValue(int i) { return i - (items().size() - 1) / 2f; }

    void setFocus(int i, int dir) {
        List<Entry> list = items();
        if (list.isEmpty() || i < 0 || i >= list.size() || i == focus) return;
        focus = i;
        shelfFocus.put(shelves.get(shelf).name, list.get(focus).key());
        ui.moveTo(focus, dir);
        flow.focusTarget = focusValue(focus);
    }

    /** LEFT off the first page leaves the band for the shelf ruler; RIGHT comes back to its first slot. */
    void setRuler(boolean on) {
        if (on == onRuler) return;
        onRuler = on;
        ui.setOnRuler(on);
    }

    /** Another shelf opens (UP/DOWN); the one before folds away. No wrap: the top and the bottom are ends. */
    void setShelf(int k, int dir) {
        if (shelves.isEmpty()) return;
        int next = Math.max(0, Math.min(shelves.size() - 1, k));
        if (next == shelf) return;
        shelf = next;
        focus = indexOf(items(), shelfFocus.get(shelves.get(shelf).name));
        ui.setPackages(pkgs());
        ui.switchShelf(shelf, labels(), kinds(), fresh(), focus, dir);
        flow.focusTarget = focusValue(focus);
    }

    /** OK on a shelf-opener: that shelf opens the way UP/DOWN on the ruler opens one, the focus on its first slot. */
    void openShelf(String name) {
        int k = shelfIndex(name);
        if (k < 0 || k == shelf) return;
        shelfFocus.remove(name);               // the focus lands on the new shelf's first slot, not where it was
        setShelf(k, k > shelf ? 1 : -1);
    }

    int shelfIndex(String name) {
        for (int k = 0; k < shelves.size(); k++) if (shelves.get(k).name.equals(name)) return k;
        return -1;
    }

    void launch(Entry e) {
        if (e.item != null && Shelves.isShelf(e.item)) { openShelf(Shelves.shelfOf(e.item)); return; }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putString(LAST, e.key()).putString(LAST_SHELF, shelves.isEmpty() ? "常用" : shelves.get(shelf).name)
                .putInt(LAUNCHES + e.key(), prefs.getInt(LAUNCHES + e.key(), 0) + 1).apply();
        ui.outro();
        flow.boostTarget = 24f;
        flow.fadeTarget = 0.15f;
        try {
            startActivity(new Intent(e.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, "无法打开 " + e.label, Toast.LENGTH_SHORT).show();
            ui.intro();
            flow.boostTarget = 1f;
            flow.fadeTarget = 1f;
        }
    }

    // ------------------------------------------------------------------ keys
    /** One arrow, on the band or on the ruler. */
    boolean move(int dir, int sign) {
        if (onRuler) {
            if (dir == HomeLayout.UP) setShelf(shelf - 1, -1);
            else if (dir == HomeLayout.DOWN) setShelf(shelf + 1, 1);
            else if (dir == HomeLayout.RIGHT) { setRuler(false); setFocus(0, 1); }
            return true;
        }
        int to = HomeLayout.neighbor(items().size(), focus, dir);
        if (to == HomeLayout.RULER) setRuler(true);
        else if (to >= 0) setFocus(to, sign);
        return true;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return move(HomeLayout.LEFT, -1);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return move(HomeLayout.RIGHT, 1);
            case KeyEvent.KEYCODE_DPAD_UP: return move(HomeLayout.UP, -1);
            case KeyEvent.KEYCODE_DPAD_DOWN: return move(HomeLayout.DOWN, 1);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (ev.getRepeatCount() == 0 && !onRuler && !items().isEmpty()) launch(items().get(focus));
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (ev.getRepeatCount() == 0) launch(new Entry("settings", "设置", settings()));
                return true;
        }
        return super.onKeyDown(code, ev);
    }

    @Override
    public void onBackPressed() { /* home stays home */ }

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        hideSystemStatusBar(true);
        resumed = true;
        refreshEntries();
        ui.tick();
        ui.intro();
        flow.boostTarget = 1f;
        flow.fadeTarget = 1f;
        updateRendering();
        main.removeCallbacks(watch);
        main.postDelayed(watch, 3000);
        main.removeCallbacks(askInfo);
        main.post(askInfo);
        tick = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) { ui.tick(); }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, f);
        pkgs = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) { refreshEntries(); }
        };
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(pkgs, pf);
    }

    @Override
    protected void onPause() {
        super.onPause();
        hideSystemStatusBar(false);
        resumed = false;
        main.removeCallbacks(watch);
        main.removeCallbacks(askInfo);
        updateRendering();
        try { unregisterReceiver(tick); } catch (Exception ignored) { }
        try { unregisterReceiver(pkgs); } catch (Exception ignored) { }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(debug); } catch (Exception ignored) { }
        try { unregisterReceiver(screen); } catch (Exception ignored) { }
        super.onDestroy();
    }

    /** Render only while this screen is resumed and the TV is awake (standby keeps apps running, screen dark). */
    void updateRendering() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean want = resumed && (pm == null || pm.isInteractive());
        if (want && !rendering) {
            glView.onResume();
            rendering = true;
            Choreographer.getInstance().removeFrameCallback(vsync);
            Choreographer.getInstance().postFrameCallback(vsync);
        } else if (!want && rendering) {
            rendering = false;
            Choreographer.getInstance().removeFrameCallback(vsync);
            glView.queueEvent(flow::paused);
            glView.onPause();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) immersive();
    }

    // ------------------------------------------------------------------ debug snapshot: GL frame + type layer
    void composeSnapshot(final Bitmap frame, final String name) {
        main.post(() -> {
            Canvas c = new Canvas(frame);
            c.scale(frame.getWidth() / (float) ui.getWidth(), frame.getHeight() / (float) ui.getHeight());
            ui.draw(c);
            final File f = new File(getExternalFilesDir(null), name);
            new Thread(() -> {
                try (FileOutputStream o = new FileOutputStream(f)) {
                    frame.compress(Bitmap.CompressFormat.PNG, 100, o);
                } catch (Exception ignored) { }
                frame.recycle();
            }).start();
        });
    }

    // ------------------------------------------------------------------ system ui
    void hideSystemStatusBar(boolean hide) {
        try {
            Intent i = new Intent("com.android.systemui.STATUSBAR_CONTROL");
            i.setClassName(SYSUI, "com.xiaomi.mitv.systemui.MiTVSystemUIService");
            i.putExtra("show_status_bar", hide ? 0 : 1);
            if (!hide) { i.putExtra("status_show_flag", 1); i.putExtra("status_show_customizedzone", 1); }
            startService(i);
        } catch (Exception ignored) { }
    }

    void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ------------------------------------------------------------------ fonts
    Typeface variable(String asset, String axes) {
        try {
            Typeface t = new Typeface.Builder(getAssets(), asset).setFontVariationSettings(axes).build();
            if (t != null) return t;
        } catch (Exception ignored) { }
        return asset(asset);
    }

    Typeface asset(String asset) {
        try { return Typeface.createFromAsset(getAssets(), asset); } catch (Exception e) { return Typeface.DEFAULT; }
    }

    Typeface sysFont(String path) {
        try { return Typeface.createFromFile(path); } catch (Exception e) { return Typeface.DEFAULT; }
    }
}
