package xyz.erapi0neer.xingyi.agent.tv;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import xyz.erapi0neer.xingyi.agent.PhoneApi;

/**
 * The phone console on the TV (phone-console, 2026-11-08): a small HTTP server on the LAN that hands the web app in
 * {@code assets/agent/phone.html} to a phone's browser and carries what it types into {@link AgentEngine}.
 *
 * <p>It lives here, in the XiaoAi bridge's runtime, because that process is always up — the 星仪 app on the TV is not.
 * Port 18780 on every interface; one daemon thread accepts, two handle. Nothing here ever throws into the engine: a
 * phone that connects and says nothing is dropped after 10 s, and a port already taken only means the console is off.
 */
public final class PhoneServer implements PhoneApi.Engine, PhoneApi.Phones {
    private static final String TAG = "Xingyi";
    static final int PORT = 18780;
    /** A phone that connected and then said nothing must not hold one of the two threads. */
    private static final int TIMEOUT_MS = 10_000;
    /** A whole request arrives in this long, and this many connections wait for the two that are served. */
    private static final long REQUEST_MS = 15_000;
    private static final int QUEUE = 8;

    private static PhoneServer instance;

    private final Context app;
    private final AgentEngine engine;
    private final File file;
    private final PhoneApi api;
    private ServerSocket server;

    private PhoneServer(Context context, AgentEngine engine) {
        this.app = context;
        this.engine = engine;
        this.file = new File(new File(context.getFilesDir(), "agent"), "phones.json");
        this.api = new PhoneApi(this, this, PhoneApi.SYSTEM_CLOCK, asset(context, "agent/phone.html"));
    }

    /** Starts the console once, when the engine is created. A taken port is logged and left alone: the TV keeps
     * working, only the phone console is missing. */
    public static synchronized void start(Context context, AgentEngine engine) {
        if (instance != null) return;
        PhoneServer server = new PhoneServer(context, engine);
        if (!server.listen()) return;
        instance = server;
        Log.i(TAG, "phone console on port " + PORT);
    }

    private boolean listen() {
        try {
            // unbound first, so SO_REUSEADDR is set before the bind (a restart must not wait out TIME_WAIT)
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(PORT), 8);
            server = socket;
        } catch (IOException taken) {
            Log.w(TAG, "phone console off: port " + PORT + " is taken (" + taken + ")");
            return false;
        }
        Thread accept = new Thread(this::accept, "xingyi-phone");
        accept.setDaemon(true);
        accept.start();
        return true;
    }

    private void accept() {
        // two at a time and at most QUEUE waiting: a pile of idle connections is closed, not kept until the bridge runs
        // out of file descriptors (2026-11-09 review)
        ExecutorService pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(QUEUE), r -> {
            Thread t = new Thread(r, "xingyi-phone-conn");
            t.setDaemon(true);
            return t;
        });
        while (true) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                if (server.isClosed()) return;
                // a passing error (too many open files) is no reason to leave the console off until the next restart
                Log.w(TAG, "phone console: accept failed, retrying (" + e + ")");
                try { Thread.sleep(500); } catch (InterruptedException stop) { return; }
                continue;
            }
            try {
                pool.execute(() -> serve(socket));
            } catch (RejectedExecutionException shuttingDown) {
                close(socket);
            }
        }
    }

    private void serve(Socket socket) {
        try (Socket open = socket) {
            open.setSoTimeout(TIMEOUT_MS);
            // TIMEOUT_MS bounds one silent read; a request trickled a byte at a time gets REQUEST_MS in all
            final long until = android.os.SystemClock.uptimeMillis() + REQUEST_MS;
            InputStream in = new FilterInputStream(new BufferedInputStream(open.getInputStream())) {
                @Override public int read() throws IOException { due(); return super.read(); }
                @Override public int read(byte[] b, int off, int len) throws IOException { due(); return super.read(b, off, len); }
                private void due() throws IOException {
                    if (android.os.SystemClock.uptimeMillis() > until) throw new IOException("request too slow");
                }
            };
            api.serve(in, open.getOutputStream());
        } catch (IOException phoneWentAway) {
            // the phone closed the tab or walked out of range mid-request: nothing to say about it
        } catch (RuntimeException unexpected) {
            Log.w(TAG, "phone console: " + unexpected);
        }
    }

    private static void close(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static String asset(Context context, String name) {
        try (InputStream in = context.getAssets().open(name)) {
            return AgentEngine.read(in);
        } catch (IOException missing) {
            Log.w(TAG, "phone console: " + name + " is missing (" + missing + ")");
            return "";
        }
    }

    // ---- PhoneApi.Engine ------------------------------------------------------------------------

    /** A typed sentence is a typed sentence: {@code phone-quiet} is answered on the TV's screen like the 星仪 app's
     * own typing, {@code phone} is read out loud through XiaoAi. */
    @Override public String submit(String text, boolean speak) {
        return engine.submit(text, speak ? "phone" : "phone-quiet");
    }

    @Override public JSONObject state() { return engine.state(); }

    /** Any app playing music now (the audio system's own answer: no ADB round for a chip). */
    @Override public boolean playing() {
        android.media.AudioManager am = (android.media.AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        return am != null && am.isMusicActive();
    }

    @Override public void consent(boolean yes) { engine.consent(yes); }

    @Override public void stop() { engine.stop(); }

    @Override public JSONArray history(int days) { return engine.history("", days, 50); }

    /** The pair code as a hint card, whatever else was hinted lately. False when it cannot be shown — the screen is
     * off, the accessibility service is not running, or a task's panel is up (the phone is then told to try again,
     * which is the same thing it does when the screen is dark). */
    @Override public boolean showPairCode(String code) {
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        if (power != null && !power.isInteractive()) return false;
        Eyes eyes = Eyes.get();
        if (eyes == null || eyes.hud() == null) return false;
        String digits = code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code;
        return eyes.hud().showHint(new Hud.State("hint", "", null, "",
                "手机配对码　" + digits + "\n两分钟内在手机上输入"));
    }

    // ---- PhoneApi.Phones ------------------------------------------------------------------------

    @Override public synchronized JSONArray load() {
        try (InputStream in = new FileInputStream(file)) {
            return new JSONArray(AgentEngine.read(in));
        } catch (Exception missingOrBroken) {
            return new JSONArray();
        }
    }

    @Override public synchronized void save(JSONArray phones) {
        File dir = file.getParentFile();
        File tmp = new File(dir, "phones.json.tmp");
        try {
            dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(phones.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) throw new IOException("rename failed");
        } catch (Exception e) {
            Log.w(TAG, "phone console: the phone list was not saved (" + e + ")");
            tmp.delete();
        }
    }
}
