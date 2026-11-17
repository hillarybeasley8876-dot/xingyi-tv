package xyz.erapi0neer.xingyi.agent.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import xyz.erapi0neer.xingyi.LocalAdb;
import xyz.erapi0neer.xingyi.agent.Repairs;

/** What self_check does about what it finds, from inside the bridge (2026-11-12 01:30: 「主要是harness目前的问题是
 * 他修不了系统的问题」): the four repairs below were measured on this TV, the bridge holds WRITE_SECURE_SETTINGS and can
 * drive the TV over its own loopback ADB, and every one of them is followed by the same probe again — so what the user
 * hears is 「修好了」 only when the thing really answers afterwards. What needs root, a firmware decision or a reboot is
 * said as such ({@link Repairs#cannot}) instead of 正常.
 *
 * <p>The words live in the pure core ({@link Repairs}); nothing here throws out of {@link #run}.</p> */
final class SelfRepair {
    private SelfRepair() { }

    /** A piece of work on the TV's own loopback ADB link. */
    private interface Call<T> { T run(LocalAdb a) throws Exception; }
    /** Is the thing fine again? May wait (the service needs a moment to come back). */
    private interface Probe { boolean ok() throws Exception; }
    /** One of {@link Repairs#plan}'s steps, carried out. */
    private interface Step { void apply(String step) throws Exception; }

    /** 读屏 comes back 1.5 s after the rebind (measured by hand, 2026-11-12 01:33); the listener answers again within
     * a second or two of ADB_ENABLED being written back. */
    private static final long EYES_WAIT_MS = 8000, ADB_WAIT_MS = 6000, POLL_MS = 500;

    /** Checks the four things above and, when one fails, does what {@link Repairs#plan} says and checks again.
     * @return {"ok":[…], "mended":[…], "broken":[…], "unfixable":[…], "say":"…"} — the labels in Chinese as above. */
    static JSONObject run(TvTools tools) throws JSONException {
        Context c = tools.context;
        Tally t = new Tally();
        check(c, t, Repairs.EYES);
        check(c, t, Repairs.PANEL);
        check(c, t, Repairs.ADB);
        check(c, t, Repairs.XIAOI);
        List<String> said = new ArrayList<>(t.unfixable);
        said.addAll(t.broken);   // the broken ones that are not fixable are said as unfixable too
        String say = t.broken.isEmpty() && t.unfixable.isEmpty() ? Repairs.normal(t.ok, t.mended)
                : Repairs.report(t.mended, said);
        return new JSONObject().put("ok", new JSONArray(t.ok)).put("mended", new JSONArray(t.mended))
                .put("broken", new JSONArray(t.broken)).put("unfixable", new JSONArray(t.unfixable)).put("say", say);
    }

    /** One check, and its repair, with nothing allowed out: an exception is a check that could not be mended. */
    private static void check(Context c, Tally t, String symptom) {
        try {
            if (Repairs.EYES.equals(symptom)) eyes(c, t);
            else if (Repairs.PANEL.equals(symptom)) panel(t);
            else if (Repairs.ADB.equals(symptom)) adb(c, t);
            else xiaoi(c, t);
        } catch (Exception e) {
            t.lost(symptom);
        }
    }

    /** 读屏: the service is listed but not bound (or was dropped). The rebind names 星仪's own component; the service
     * has to be up again within {@link #EYES_WAIT_MS} for this to count as mended. */
    private static void eyes(Context c, Tally t) throws Exception {
        if (Eyes.get() != null) { t.fine(Repairs.EYES); return; }
        final String component = new ComponentName(c, Eyes.class).flattenToShortString();
        Link link = new Link(c);
        try {
            String step = mend(Repairs.EYES, () -> awaitEyes(EYES_WAIT_MS), s -> link.call(a -> {
                a.connect();   // the rebind is a shell command, so the link has to be up first
                a.rebindAccessibility(component);
                return null;
            }));
            if (step == null) t.lost(Repairs.EYES); else t.mend(step);
        } finally { link.close(); }
    }

    /** 面板: the HUD's window is not being added. Taken down and put up again, once. Nothing to say at all when there
     * is no HUD (the accessibility service is not bound — that is the 读屏 check, and the panel comes with it). */
    private static void panel(Tally t) throws Exception {
        Eyes eyes = Eyes.get();
        final Hud hud = eyes == null ? null : eyes.hud();
        if (hud == null) return;
        if (up(hud)) { t.fine(Repairs.PANEL); return; }
        String step = mend(Repairs.PANEL, () -> up(hud), s -> hud.dispose());
        if (step == null) t.lost(Repairs.PANEL); else t.mend(step);
    }

    /** The test card, with a window that throws counted as not up — a window in that state is what the reset is for. */
    private static boolean up(Hud hud) {
        try { return hud.probe(); } catch (RuntimeException e) { return false; }
    }

    /** 本机调试: the listener refuses the connection. Writing ADB_ENABLED back needs the bridge's own
     * WRITE_SECURE_SETTINGS (the ADB guard writes it the same way); without it nothing can be done from here. */
    private static void adb(Context c, Tally t) throws Exception {
        Link link = new Link(c);
        try {
            if (connect(link)) { t.fine(Repairs.ADB); return; }
            if (c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
                t.lost(Repairs.ADB);
                return;
            }
            String step = mend(Repairs.ADB, () -> connectWithin(link, ADB_WAIT_MS), s -> Settings.Global.putInt(
                    c.getContentResolver(), Settings.Global.ADB_ENABLED, 1));
            if (step == null) t.lost(Repairs.ADB); else t.mend(step);
        } finally { link.close(); }
    }

    /** 小爱的卡片: its overlay appop has been reset, so its cards come up over what 星仪 is doing. Switched off again
     * through the same appops call the bridge uses at boot. */
    private static void xiaoi(Context c, Tally t) throws Exception {
        Link link = new Link(c);
        try {
            if (hidden(link)) { t.fine(Repairs.XIAOI); return; }
            String step = mend(Repairs.XIAOI, () -> hidden(link),
                    s -> link.call(a -> { a.xiaoaiOverlays(false); return null; }));
            if (step == null) t.lost(Repairs.XIAOI); else t.mend(step);
        } finally { link.close(); }
    }

    // ---- the steps, and the probes that say whether they worked ------------------------------------

    /** The table's steps for this symptom, in order, each one followed by the probe again; the step that mended it,
     * or null when none did. A step that throws does not stop the ones after it. */
    private static String mend(String symptom, Probe probe, Step step) {
        for (String s : Repairs.plan(symptom)) {
            try { step.apply(s); } catch (Exception e) { continue; }
            try { if (probe.ok()) return s; } catch (Exception e) { /* the probe itself failed: the next step, if any */ }
        }
        return null;
    }

    /** 读屏 is back: the service binds itself a moment after the rebind. */
    private static boolean awaitEyes(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (Eyes.get() == null) {
            if (System.currentTimeMillis() >= end) return false;
            sleep(POLL_MS);
        }
        return true;
    }

    /** One connection attempt; an exception means the listener is not there. */
    private static boolean connect(Link link) {
        try { link.call(a -> { a.connect(); return null; }); return true; }
        catch (Exception e) { return false; }
    }

    /** The listener is back within this long, asked every {@link #POLL_MS}. */
    private static boolean connectWithin(Link link, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!connect(link)) {
            if (System.currentTimeMillis() >= end) return false;
            sleep(POLL_MS);
        }
        return true;
    }

    /** True when XiaoAi's overlay appop is {@code ignore}, i.e. its cards stay hidden. */
    private static boolean hidden(Link link) throws Exception {
        Boolean b = link.call(LocalAdb::overlayHidden);
        return b != null && b.booleanValue();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** What a symptom is called in the ok list — the words self_check has always used. */
    private static String name(String symptom) {
        if (Repairs.EYES.equals(symptom)) return "读屏";
        if (Repairs.PANEL.equals(symptom)) return "面板";
        if (Repairs.ADB.equals(symptom)) return "本机调试";
        if (Repairs.XIAOI.equals(symptom)) return "小爱的卡片";
        return symptom;
    }

    /** Where a check's outcome goes. Still broken is said with what the user can do about it; a symptom the table has
     * no repair for could not even be tried, so it is kept apart in {@code broken} as well. */
    private static final class Tally {
        final List<String> ok = new ArrayList<>(), mended = new ArrayList<>(),
                broken = new ArrayList<>(), unfixable = new ArrayList<>();
        void fine(String symptom) { ok.add(name(symptom)); }
        void mend(String step) { mended.add(Repairs.stepSays(step)); }
        void lost(String symptom) {
            String note = Repairs.cannot(symptom);
            if (note.isEmpty()) note = name(symptom);
            if (Repairs.plan(symptom).isEmpty()) broken.add(note); else unfixable.add(note);
        }
    }

    /** One loopback link, thrown away and remade after a failure — as {@code TvTools.Adb} does. */
    private static final class Link {
        private final Context c;
        private LocalAdb adb;
        Link(Context c) { this.c = c; }
        <T> T call(Call<T> f) throws Exception {
            try { return f.run(get()); }
            catch (Exception first) { reset(); return f.run(get()); }
        }
        private LocalAdb get() throws Exception { if (adb == null) adb = new LocalAdb(c); return adb; }
        private void reset() { if (adb != null) adb.cancel(); adb = null; }
        void close() { reset(); }
    }
}
