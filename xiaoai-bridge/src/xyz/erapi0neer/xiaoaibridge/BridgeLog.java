package xyz.erapi0neer.xiaoaibridge;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Last 200 bridge events, mirrored to files/bridge-log.txt for `adb pull`. Only taken-over requests are recorded. */
final class BridgeLog {
    static final String TAG = "XiaoaiBridge";
    private static final int KEEP = 200;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final File file;

    BridgeLog(Context context) {
        File dir = context.getExternalFilesDir(null);
        file = new File(dir == null ? context.getFilesDir() : dir, "bridge-log.txt");
    }

    synchronized void add(String event) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date()) + "  " + event;
        Log.i(TAG, event);
        lines.addLast(line);
        while (lines.size() > KEEP) lines.removeFirst();
        StringBuilder all = new StringBuilder();
        for (String l : lines) all.append(l).append('\n');
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(all.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(file)) tmp.delete();
    }

    synchronized String tail(int n) {
        StringBuilder b = new StringBuilder();
        int skip = Math.max(0, lines.size() - n), i = 0;
        for (String l : lines) if (i++ >= skip) b.append(l).append('\n');
        return b.toString();
    }
}
