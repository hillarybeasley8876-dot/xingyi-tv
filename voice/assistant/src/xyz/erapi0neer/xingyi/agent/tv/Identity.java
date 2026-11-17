package xyz.erapi0neer.xingyi.agent.tv;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The runtime borrows 星仪's already-authorized loopback-ADB identity and model routes, so moving the agent
 * into the bridge app needs no new authorization dialog on the TV. */
final class Identity {
    private static final Uri SHARE = Uri.parse("content://xyz.erapi0neer.xingyi.share");
    private static final String KEY = "xingyi-adb-key.pk8", CONFIG = "config.json";

    private Identity() { }

    /** Copies whatever is missing. Returns a short status for logs. */
    static synchronized String ensure(Context c) {
        File dir = c.getFilesDir(), key = new File(dir, KEY), config = new File(dir, CONFIG);
        if (key.isFile() && config.isFile()) return "present";
        Bundle b;
        try { b = c.getContentResolver().call(SHARE, "export", null, null); }
        catch (RuntimeException e) { return "星仪未安装或拒绝：" + e.getClass().getSimpleName(); }
        if (b == null || !"ok".equals(b.getString("result"))) return "星仪拒绝导出";
        try {
            byte[] k = b.getByteArray("adb_key");
            String m = b.getString("model_config");
            if (!key.isFile() && k != null) write(key, k);
            if (!config.isFile() && m != null) write(config, m.getBytes(StandardCharsets.UTF_8));
            return "copied";
        } catch (IOException e) {
            return "写入失败";
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(data); out.getFD().sync(); }
        tmp.setReadable(false, false); tmp.setWritable(false, false);
        tmp.setReadable(true, true); tmp.setWritable(true, true);
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
    }
}
