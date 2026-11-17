package xyz.erapi0neer.xingyi.agent.tv;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;
import xyz.erapi0neer.xingyi.VendorSpeech;
import xyz.erapi0neer.xingyi.agent.Schedules;
import xyz.erapi0neer.xingyi.agent.ScheduleTools;

/** The TV's clock for the schedule book (files/agent/schedules.json): the bridge's heartbeat calls {@link #tick()}
 * every ~20 s, and whatever is due runs — a reminder is read out, a task is handed to the engine as a fresh request.
 * Nothing here may throw: the heartbeat must survive whatever the book contains. */
public final class Scheduler {
    /** How late an item may still run when the TV was off or the process dead; older ones are dropped silently. */
    private static final long GRACE_MS = 10 * 60_000L;
    /** How long a task waits for a busy engine before the user is told it did not run. Shorter than GRACE_MS, or
     * skipMissed would drop it silently first (lead review: both were 10 minutes, so the message never came). */
    private static final long BUSY_WAIT_MS = 5 * 60_000L;

    private static Scheduler instance;

    private final Context app;
    private final File dir;

    private Scheduler(Context c) {
        app = c;
        dir = new File(c.getFilesDir(), "agent");
        dir.mkdirs();
    }

    public static synchronized Scheduler get(Context context) {
        if (instance == null) instance = new Scheduler(context.getApplicationContext());
        return instance;
    }

    /** What the engine registers the three schedule tools with. */
    public ScheduleTools.Store store() {
        return new ScheduleTools.Store() {
            @Override public JSONObject load() { return Scheduler.this.load(); }
            @Override public void save(JSONObject book) throws IOException, JSONException { Scheduler.this.save(book); }
        };
    }

    /** Runs whatever is due, oldest first. A task waits for the engine to be free; if it has waited more than
     * BUSY_WAIT_MS it is dropped and the user is told, rather than firing much later. */
    public synchronized void tick() {
        try {
            JSONObject book = load();
            TimeZone tz = TimeZone.getDefault();
            long now = System.currentTimeMillis();
            if (!Schedules.skipMissed(book, now, GRACE_MS, tz).isEmpty()) save(book);
            for (JSONObject item : Schedules.due(book, now)) run(book, item, now, tz);
        } catch (Throwable ignored) {
            // A broken book, a full disk, a dead engine: the heartbeat must survive, the next tick tries again.
        }
    }

    private void run(JSONObject book, JSONObject item, long now, TimeZone tz) throws IOException, JSONException {
        int id = item.optInt("id", -1);
        String what = item.optString("what");
        if ("remind".equals(item.optString("kind"))) {
            VendorSpeech.speak(app, "提醒：" + what);
            Schedules.fired(book, id, now, tz);
            save(book);
            return;
        }
        AgentEngine engine = AgentEngine.get(app);
        if (idle(engine)) {
            // Written back before the task runs: a crash mid-task must not fire it a second time.
            Schedules.fired(book, id, now, tz);
            save(book);
            engine.submit(what, "schedule", note(item, now, tz), null);
        } else if (now - item.optLong("at") > BUSY_WAIT_MS) {
            Schedules.fired(book, id, now, tz);
            save(book);
            VendorSpeech.speak(app, "预约的「" + what + "」到点了，但当时在忙，没有执行。");
        }
    }

    /** Free to take a new request: nothing running and nothing waiting for the user's answer or consent. */
    private static boolean idle(AgentEngine engine) {
        return !engine.busy() && engine.state().optString("awaiting").isEmpty();
    }

    /** What the model is told about a task that came from the clock rather than from the user's voice. */
    private static String note(JSONObject item, long now, TimeZone tz) {
        String made = new SimpleDateFormat("M月d日 H:mm", Locale.CHINA).format(new Date(item.optLong("created")));
        return "这是用户预约的定时任务（" + made + " 定的「" + Schedules.describe(item, now, tz)
                + "」），现在到点了：照这句话去做，做完用一句话说结果。";
    }

    /** A missing or unreadable file is an empty book, so a scheduled item never blocks on a broken file. */
    private synchronized JSONObject load() {
        try (InputStream in = new FileInputStream(new File(dir, "schedules.json"))) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Temp file, fsync, rename: a power cut leaves either the old book or the new one, never half of one. */
    private synchronized void save(JSONObject book) throws IOException, JSONException {
        File tmp = new File(dir, "schedules.json.tmp"), dst = new File(dir, "schedules.json");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(book.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(dst)) throw new IOException("rename failed");
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
