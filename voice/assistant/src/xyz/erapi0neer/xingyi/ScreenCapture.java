package xyz.erapi0neer.xingyi;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** One authorized screen frame, intended for this Android 9 (API 28) TV.
 * No storage or network access. A new instance is required for each capture.
 * Modern Android versions require a foreground mediaProjection service, not implemented here.
 */
public final class ScreenCapture {
    public interface Callback {
        void success(byte[] jpeg);
        void failure(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Callback callback;
    private Activity owner;
    private Application app;
    private MediaProjection projection;
    private ImageReader reader;
    private VirtualDisplay display;
    private boolean started;
    private boolean done;
    private boolean blackFrame;

    public static Intent requestIntent(Activity activity) {
        MediaProjectionManager manager = (MediaProjectionManager) activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) throw new IllegalStateException("电视未提供屏幕捕获服务");
        Intent intent = manager.createScreenCaptureIntent();
        ResolveInfo resolved = activity.getPackageManager().resolveActivity(intent, 0);
        if (resolved == null || resolved.activityInfo == null || !resolved.activityInfo.enabled
                || !resolved.activityInfo.applicationInfo.enabled || !resolved.activityInfo.exported) {
            throw new IllegalStateException("此电视固件缺少屏幕捕获授权界面，标准截图当前不可用");
        }
        String permission = resolved.activityInfo.permission;
        if (permission != null && activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw new IllegalStateException("此电视的屏幕捕获授权入口不允许普通应用使用");
        }
        return intent;
    }

    public void capture(Activity activity, int resultCode, Intent data, Callback result) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> capture(activity, resultCode, data, result));
            return;
        }
        if (result == null) throw new IllegalArgumentException("Callback is required");
        if (started || done) { result.failure("请新建一次截图请求"); return; }
        started = true;
        callback = result;
        if (resultCode != Activity.RESULT_OK || data == null) { fail("未授权屏幕捕获"); return; }
        if (Build.VERSION.SDK_INT != 28) { fail("当前截图实现仅支持 Android 9 电视"); return; }
        if (activity.isFinishing() || activity.isDestroyed()) { fail("助手窗口已关闭"); return; }
        owner = activity;
        app = activity.getApplication();
        app.registerActivityLifecycleCallbacks(lifecycle);
        main.postDelayed(timeout, 6000);
        try {
            MediaProjectionManager manager = (MediaProjectionManager) activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) { fail("电视未提供屏幕捕获服务"); return; }
            projection = manager.getMediaProjection(resultCode, data);
            if (projection == null) { fail("屏幕捕获授权无效"); return; }
            projection.registerCallback(projectionCallback, main);
            // Put the assistant task behind the previous app/home before requesting any pixels.
            if (!activity.moveTaskToBack(true)) { fail("无法隐藏助手界面，请返回桌面后再试"); return; }
            main.postDelayed(beginFrame, 1000);
        } catch (RuntimeException e) {
            fail("无法启动屏幕捕获，请重新授权");
        }
    }

    private final Runnable beginFrame = () -> {
        if (done || owner == null || projection == null) return;
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            owner.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
            float scale = Math.min(1f, Math.min(1280f / Math.max(1, metrics.widthPixels), 720f / Math.max(1, metrics.heightPixels)));
            int width = Math.max(1, Math.round(metrics.widthPixels * scale));
            int height = Math.max(1, Math.round(metrics.heightPixels * scale));
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(this::receiveFrame, main);
            display = projection.createVirtualDisplay("Xingyi single screenshot", width, height,
                    Math.max(1, Math.round(metrics.densityDpi * scale)), DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, main);
            if (display == null) fail("电视未能创建截图画面");
        } catch (RuntimeException e) {
            fail("电视无法提供截图画面");
        }
    };

    private void receiveFrame(ImageReader source) {
        if (done) return;
        Image image = null;
        Bitmap bitmap = null;
        byte[] jpeg = null;
        String error = null;
        try {
            image = source.acquireLatestImage();
            if (image == null) return;
            Image.Plane plane = image.getPlanes()[0];
            int width = image.getWidth(), height = image.getHeight();
            if (plane.getPixelStride() != 4) { error = "电视返回了不支持的截图格式"; }
            else {
                ByteBuffer input = plane.getBuffer();
                int rowStride = plane.getRowStride();
                byte[] compact = new byte[width * height * 4];
                // Copy only visible row bytes; the final row is not guaranteed to include padding.
                for (int y = 0; y < height; y++) {
                    input.position(y * rowStride);
                    input.get(compact, y * width * 4, width * 4);
                }
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(compact));
                if (looksBlack(bitmap)) {
                    blackFrame = true; // Wait for a rendered frame until the existing deadline.
                } else {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) error = "截图编码失败";
                    else jpeg = output.toByteArray();
                }
            }
        } catch (RuntimeException e) {
            error = "读取电视画面失败";
        } finally {
            if (image != null) image.close();
            if (bitmap != null) bitmap.recycle();
        }
        // Close the acquired image before releasing its ImageReader.
        if (error != null) fail(error);
        else if (jpeg != null) succeed(jpeg);
    }

    private static boolean looksBlack(Bitmap bitmap) {
        int visible = 0;
        for (int y = 0; y < 18; y++) for (int x = 0; x < 32; x++) {
            int pixel = bitmap.getPixel(Math.min(bitmap.getWidth() - 1, (2 * x + 1) * bitmap.getWidth() / 64),
                    Math.min(bitmap.getHeight() - 1, (2 * y + 1) * bitmap.getHeight() / 36));
            if (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) > 30 && ++visible >= 3) return false;
        }
        return true;
    }

    private final Runnable timeout = () -> fail(blackFrame
            ? "截图持续全黑，电视可能待机或画面受保护（HDMI / 受保护视频）"
            : "截图超时，电视未在 6 秒内提供画面");

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() { fail("屏幕捕获已被系统停止"); }
    };

    private void succeed(byte[] jpeg) {
        if (done) return;
        Callback result = callback;
        cleanup();
        if (result != null) result.success(jpeg);
    }

    private void fail(String message) {
        if (done) return;
        Callback result = callback;
        cleanup();
        if (result != null) result.failure(message);
    }

    /** Silent lifecycle cancellation; no late callback after Activity destruction. */
    public void cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::cancel); return; }
        cleanup();
    }

    private void cleanup() {
        if (done) return;
        done = true;
        callback = null;
        owner = null;
        main.removeCallbacks(timeout);
        main.removeCallbacks(beginFrame);
        if (app != null) { app.unregisterActivityLifecycleCallbacks(lifecycle); app = null; }
        if (reader != null) reader.setOnImageAvailableListener(null, null);
        if (display != null) { try { display.release(); } catch (RuntimeException ignored) { } display = null; }
        if (reader != null) { try { reader.close(); } catch (RuntimeException ignored) { } reader = null; }
        if (projection != null) {
            try { projection.unregisterCallback(projectionCallback); projection.stop(); } catch (RuntimeException ignored) { }
            projection = null;
        }
    }

    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityDestroyed(Activity activity) { if (activity == owner) cancel(); }
        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityResumed(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    };
}
