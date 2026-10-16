package app.nekosakana.caps;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import app.nekosakana.core.CoreService;
import app.nekosakana.core.Logx;

/** Frame dimensions describe the physical display, independent of compression. */
public final class ScreenShare {
    public interface FrameSink { void onFrame(byte[] webp, int width, int height); }
    private static final ScreenShare INSTANCE = new ScreenShare();
    private volatile MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;
    private volatile FrameSink sink;
    private int targetWidth = 720, quality = 55;
    private long interval = 125, lastSent;
    private volatile boolean running;
    private volatile boolean paused;
    private int epoch;
    private int densityDpi = 320;
    /** MediaProjection 只允许注册一个回调；记录已注册的令牌，避免重复注册抛异常。 */
    private MediaProjection callbackToken;
    private String error = "";
    private volatile Frame latestFrame;
    private static final class Frame {
        final byte[] bytes;
        final int w, h;
        final long time = SystemClock.uptimeMillis();
        Frame(byte[] bytes, int w, int h) { this.bytes = bytes; this.w = w; this.h = h; }
    }
    private ScreenShare() { }
    public static ScreenShare get() { return INSTANCE; }
    public static boolean isRunning() { return INSTANCE.running; }

    /** 暂停/恢复发送（采集保持）。 */
    public void setPaused(boolean value) { paused = value; }

    public boolean isPaused() { return paused; }
    public MediaProjection projection() { return projection; }
    public boolean hasProjection() { return projection != null; }
    public String error() { return error; }

    public synchronized boolean prepare(Context context, int resultCode, Intent data) {
        stop();
        if (resultCode != android.app.Activity.RESULT_OK || data == null) return false;
        try {
            projection = ((MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE))
                    .getMediaProjection(resultCode, data);
            if (projection != null) ensureProjectionCallback(projection);
            return projection != null;
        } catch (Throwable t) {
            error = String.valueOf(t);
            Logx.e("screen", "准备投影失败 " + t);
            CoreService.endProjection();
            return false;
        }
    }

    /**
     * MediaProjection 只允许注册一个回调（Android 14+ 重复注册会抛 IllegalStateException）。
     * 视频直采（VideoShare）与截图采集共用同一个令牌，因此回调在此统一注册：
     * 系统停止捕获时同时停掉视频与截图；尺寸变化时自适应重建采集面。
     */
    public synchronized void ensureProjectionCallback(MediaProjection token) {
        if (token == null || token == callbackToken) return;
        callbackToken = token;
        token.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                Logx.w("screen", "系统停止屏幕捕获");
                VideoShare.get().stop();
                synchronized (ScreenShare.this) { if (projection == token) stop(); }
                CoreService.endProjection();
            }
            @Override public void onCapturedContentResize(int w, int h) {
                onContentResize(w, h);
            }
        }, new Handler(android.os.Looper.getMainLooper()));
    }

    private void onContentResize(int w, int h) {
        synchronized (ScreenShare.this) {
            if (!running || display == null || reader == null || w <= 0 || h <= 0) return;
            if (reader.getWidth() == w && reader.getHeight() == h) return;
            int session = epoch;
            display.setSurface(null);
            reader.close();
            createReader(w, h, session);
            display.resize(w, h, densityDpi);
            display.setSurface(reader.getSurface());
        }
    }

    public synchronized boolean startWithProjection(Context c, int width, int fps, int q, FrameSink output) {
        configure(width, fps, q, output);
        if (running && display != null) return true;
        if (projection == null) return false;
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
            startThread();
            int session = epoch;
            MediaProjection token = projection;
            densityDpi = metrics.densityDpi;
            // 回调已在 prepare() 中统一注册（见 ensureProjectionCallback），此处不再重复注册。
            createReader(metrics.widthPixels, metrics.heightPixels, session);
            running = true;
            display = token.createVirtualDisplay("nekosakana", metrics.widthPixels, metrics.heightPixels,
                    metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
            if (android.os.Build.VERSION.SDK_INT < 34) {
                displayManager = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
                displayListener = new DisplayManager.DisplayListener() {
                    public void onDisplayAdded(int id) { }
                    public void onDisplayRemoved(int id) { }
                    public void onDisplayChanged(int id) {
                        if (id != android.view.Display.DEFAULT_DISPLAY) return;
                        synchronized (ScreenShare.this) {
                            if (!running || session != epoch || display == null || reader == null) return;
                            DisplayMetrics changed = new DisplayMetrics();
                            ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(changed);
                            if (reader.getWidth() == changed.widthPixels && reader.getHeight() == changed.heightPixels) return;
                            display.setSurface(null);
                            reader.close();
                            createReader(changed.widthPixels, changed.heightPixels, session);
                            display.resize(changed.widthPixels, changed.heightPixels, changed.densityDpi);
                            display.setSurface(reader.getSurface());
                        }
                    }
                };
                displayManager.registerDisplayListener(displayListener, handler);
            }
            Logx.i("screen", "屏幕采集开始 projection " + metrics.widthPixels + "x" + metrics.heightPixels);
            return true;
        } catch (Throwable t) {
            error = String.valueOf(t);
            Logx.e("screen", "启动失败 " + t);
            stop();
            return false;
        }
    }

    public synchronized boolean startDirect(Context c, int width, int fps, int q, FrameSink output, Runnable fallback) {
        if (running) { configure(width, fps, q, output); return true; }
        if (!ShellX.hasPrivilege() && !AccessService.isConnected()) return false;
        stop();
        configure(width, fps, q, output);
        Bitmap first = capture();
        if (first == null) return false;
        startThread();
        running = true;
        int session = epoch;
        handler.post(() -> { if (session == epoch && running) emit(first, session); else first.recycle(); });
        Runnable poll = new Runnable() {
            private int failures;
            @Override public void run() {
                if (!running || session != epoch) return;
                long begin = SystemClock.uptimeMillis();
                Bitmap bitmap = capture();
                if (bitmap != null) { failures = 0; emit(bitmap, session); }
                else {
                    Logx.w("screen", "截图失败 " + error);
                    if (++failures >= 3) {
                        synchronized (ScreenShare.this) {
                            if (!running || session != epoch) return;
                            stop();
                        }
                        fallback.run();
                        return;
                    }
                }
                Handler worker = handler;
                if (running && session == epoch && worker != null) worker.postDelayed(this,
                        Math.max(50, Math.max(350, interval) - (SystemClock.uptimeMillis() - begin)));
            }
        };
        handler.postDelayed(poll, Math.max(250, interval));
        Logx.i("screen", "屏幕采集开始 " + Control.backendName());
        return true;
    }

    private void configure(int width, int fps, int q, FrameSink output) {
        targetWidth = Math.max(240, Math.min(2160, width));
        quality = Math.max(1, Math.min(100, q));
        interval = Math.max(50, 1000 / Math.max(1, Math.min(20, fps)));
        sink = output;
        lastSent = 0;
        paused = false;
    }
    private void startThread() {
        thread = new HandlerThread("screen-share");
        thread.start();
        handler = new Handler(thread.getLooper());
    }
    private void createReader(int w, int h, int session) {
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(r -> {
            try (Image image = r.acquireLatestImage()) {
                if (image == null || !running || session != epoch) return;
                long now = SystemClock.uptimeMillis();
                if (now - lastSent < interval) return;
                lastSent = now;
                Image.Plane plane = image.getPlanes()[0];
                int paddedWidth = plane.getRowStride() / plane.getPixelStride();
                Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
                padded.copyPixelsFromBuffer(plane.getBuffer());
                Bitmap bitmap = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
                if (bitmap != padded) padded.recycle();
                emit(bitmap, session);
            } catch (Throwable t) { Logx.e("screen", "帧处理失败 " + t); }
        }, handler);
    }
    private Bitmap capture() {
        if (ShellX.hasPrivilege()) {
            ShellX.BinaryResult result = ShellX.execBytes(ShellX.backend(), 4000, null, "screencap -p");
            Bitmap bitmap = result.code == 0 ? Media.decode(result.out) : null;
            if (bitmap != null && !isBlank(bitmap)) return bitmap;
            if (bitmap != null) {
                bitmap.recycle();
                error = "ADB 截图为空白，请点亮屏幕或允许系统屏幕共享";
            } else error = "ADB 截图失败 code=" + result.code + " bytes=" + result.out.length + " " + result.err;
        }
        AccessService service = AccessService.get();
        if (service != null && android.os.Build.VERSION.SDK_INT >= 30) {
            for (int attempt = 0; attempt < 2; attempt++) {
            Bitmap[] result = new Bitmap[1];
            boolean[] expired = new boolean[1];
            CountDownLatch shotLatch = new CountDownLatch(1);
            service.screenshot(bitmap -> {
                synchronized (result) {
                    if (expired[0]) { if (bitmap != null) bitmap.recycle(); }
                    else result[0] = bitmap;
                    shotLatch.countDown();
                }
            });
            try {
                if (shotLatch.await(3, TimeUnit.SECONDS) && result[0] != null) return result[0];
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            synchronized (result) {
                expired[0] = true;
                if (result[0] != null) result[0].recycle();
            }
            error = "无障碍截图未完成";
            if (attempt == 0) try { Thread.sleep(350); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        return null;
    }
    private static boolean isBlank(Bitmap bitmap) {
        // Some Android 10 vendors return a successful but empty shell screenshot.
        for (int y = 0; y < bitmap.getHeight(); y += Math.max(1, bitmap.getHeight() / 40)) {
            for (int x = 0; x < bitmap.getWidth(); x += Math.max(1, bitmap.getWidth() / 40)) {
                if ((bitmap.getPixel(x, y) & 0x00ffffff) != 0) return false;
            }
        }
        return true;
    }
    private void emit(Bitmap bitmap, int session) {
        try {
            Bitmap scaled = Media.scale(bitmap, targetWidth);
            // 上报的尺寸必须是实际编码（缩放后）的尺寸，否则接收端按 header 显示会变形。
            int w = scaled.getWidth(), h = scaled.getHeight();
            byte[] bytes;
            try { bytes = Media.webp(scaled, quality); }
            finally { if (scaled != bitmap) scaled.recycle(); }
            FrameSink output = sink;
            if (session == epoch && running && !paused && output != null && bytes != null) {
                latestFrame = new Frame(bytes, w, h);
                output.onFrame(bytes, w, h);
            }
        } finally { bitmap.recycle(); }
    }
    public static void screenshot(Context context, FrameSink output) {
        // 主动截图：始终抓取新画面，避免复用旧帧导致「点了没刷新」的错觉。
        Bitmap bitmap = INSTANCE.captureForShot();
        if (bitmap == null) bitmap = INSTANCE.capture();
        if (bitmap == null) {
            // 抓取失败时退回最近一帧，至少给出可见反馈。
            Frame frame = INSTANCE.latestFrame;
            if (INSTANCE.running && frame != null) {
                output.onFrame(frame.bytes, frame.w, frame.h);
                return;
            }
            output.onFrame(null, 0, 0);
            return;
        }
        try { output.onFrame(Media.webp(bitmap, 55), bitmap.getWidth(), bitmap.getHeight()); }
        finally { bitmap.recycle(); }
    }

    /**
     * 主动截图专用抓取：有 Root/ADB 时直接 {@code screencap -p}，且不因「疑似空白」而丢弃——
     * 真实黑屏也应返回。仅在抓取本身失败时才返回 null。
     */
    private Bitmap captureForShot() {
        if (!ShellX.hasPrivilege()) return null;
        ShellX.BinaryResult result = ShellX.execBytes(ShellX.backend(), 4000, null, "screencap -p");
        if (result.code != 0 || result.out.length == 0) {
            Logx.w("screen", "adb 截图失败 code=" + result.code + " bytes=" + result.out.length + " " + result.err);
            return null;
        }
        return Media.decode(result.out);
    }
    /** 停止采集但保留 MediaProjection 授权，便于再次共享时免二次授权。 */
    public synchronized void stopCapture() {
        releaseCapture();
    }

    /** 内存紧张时丢弃缓存的最近一帧，降低内存占用。 */
    public void dropCachedFrame() {
        latestFrame = null;
    }

    public synchronized void stop() {
        releaseCapture();
        MediaProjection token = projection;
        projection = null;
        if (token != null) {
            AudioShare.get().stop();
            try { token.stop(); } catch (Throwable ignored) { }
            CoreService.endProjection();
        }
    }

    private void releaseCapture() {
        running = false;
        epoch++;
        sink = null;
        latestFrame = null;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (displayManager != null && displayListener != null) displayManager.unregisterDisplayListener(displayListener);
        displayManager = null;
        displayListener = null;
        if (display != null) display.release();
        display = null;
        if (reader != null) reader.close();
        reader = null;
        if (thread != null) thread.quitSafely();
        thread = null;
        handler = null;
    }
}
