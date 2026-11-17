package xyz.erapi0neer.xingyi;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Hands this app's loopback-ADB identity and model routes to the agent runtime in the XiaoAi bridge app
 * (same signing key). The runtime lives there because MiTV's PeriodicCleaner force-stops packages that show a
 * full-screen activity and then sit idle, which this app does; the bridge has no activity and is left alone.
 * Signature permission plus an explicit caller check: nothing else can read these files. */
public final class ShareProvider extends ContentProvider {
    static final String RUNTIME = "xyz.erapi0neer.xiaoaibridge";

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if (!"export".equals(method) || !RUNTIME.equals(getCallingPackage())) {
            out.putString("result", "denied");
            return out;
        }
        File dir = getContext().getFilesDir();
        try {
            // Make sure the identity exists; LocalAdb creates it on first use.
            File key = new File(dir, "xingyi-adb-key.pk8"), config = new File(dir, "config.json");
            if (key.isFile()) out.putByteArray("adb_key", read(key));
            if (config.isFile()) out.putString("model_config", new String(read(config), "UTF-8"));
            out.putString("result", "ok");
        } catch (IOException e) {
            out.putString("result", "error");
        }
        return out;
    }

    private static byte[] read(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) > 0; ) {
                if (out.size() + n > 64 * 1024) throw new IOException("too large");
                out.write(b, 0, n);
            }
            return out.toByteArray();
        }
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
