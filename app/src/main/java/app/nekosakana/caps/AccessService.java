package app.nekosakana.caps;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

import app.nekosakana.core.ControlLog;
import app.nekosakana.core.Logx;
import app.nekosakana.core.Store;

/**
 * 无障碍服务。在无 root/ADB 时提供远程控制能力：
 * 手势注入、全局操作、文字输入、截图（Android 11+）与前台应用跟踪。
 */
public class AccessService extends AccessibilityService {
    private static volatile AccessService instance;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // 无障碍回调运行在主线程，绝不能在其中做文件/位图等重活，否则会拖住系统无障碍框架。
    private static final java.util.concurrent.ExecutorService IO =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile String foregroundPackage = "";

    public static AccessService get() {
        return instance;
    }

    public static boolean isConnected() {
        return instance != null;
    }

    public String foregroundPackage() {
        return foregroundPackage;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        Logx.i("access", "无障碍服务已连接");
        ControlLog.add("sys", "accessibility", "无障碍服务已连接");
    }

    @Override
    public void onDestroy() {
        instance = null;
        Logx.w("access", "无障碍服务已断开");
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            CharSequence pkg = event.getPackageName();
            if (pkg != null) {
                String p = pkg.toString();
                if (!p.equals(foregroundPackage) && !p.equals("com.android.systemui")) {
                    foregroundPackage = p;
                    appendUsage(p);
                }
            }
        }
    }

    private void appendUsage(String pkg) {
        IO.execute(() -> {
            try {
                String line = System.currentTimeMillis() + "\t" + pkg + "\n";
                java.io.FileWriter writer = new java.io.FileWriter(Store.get().file("logs/usage-events.log"), true);
                writer.write(line);
                writer.close();
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    protected boolean onKeyEvent(android.view.KeyEvent event) {
        // 用于锁定页拦截返回键等；正常情况不拦截。
        return false;
    }

    // ---- 手势 ----

    public boolean tap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 40);
        return dispatch(new GestureDescription.Builder().addStroke(stroke).build());
    }

    public boolean longPress(float x, float y, long duration) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, Math.max(500, duration));
        return dispatch(new GestureDescription.Builder().addStroke(stroke).build());
    }

    public boolean swipe(float x1, float y1, float x2, float y2, long duration) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, Math.max(50, duration));
        return dispatch(new GestureDescription.Builder().addStroke(stroke).build());
    }

    public boolean gesture(List<float[]> points, long duration) {
        if (points.size() < 2) return false;
        Path path = new Path();
        path.moveTo(points.get(0)[0], points.get(0)[1]);
        for (int i = 1; i < points.size(); i++) {
            path.lineTo(points.get(i)[0], points.get(i)[1]);
        }
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, Math.max(50, duration));
        return dispatch(new GestureDescription.Builder().addStroke(stroke).build());
    }

    private boolean dispatch(GestureDescription description) {
        try {
            return dispatchGesture(description, null, null);
        } catch (Throwable t) {
            Logx.e("access", "手势注入失败 " + t);
            return false;
        }
    }

    // ---- 全局操作 ----

    public boolean globalAction(int action) {
        try {
            return performGlobalAction(action);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean back() {
        return globalAction(GLOBAL_ACTION_BACK);
    }

    public boolean home() {
        return globalAction(GLOBAL_ACTION_HOME);
    }

    public boolean recents() {
        return globalAction(GLOBAL_ACTION_RECENTS);
    }

    public boolean notifications() {
        return globalAction(GLOBAL_ACTION_NOTIFICATIONS);
    }

    public boolean lockScreen() {
        if (Build.VERSION.SDK_INT >= 28) {
            return globalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
        return false;
    }

    // ---- 文字输入 ----

    public boolean setText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return paste(text);
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) return paste(text);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    public boolean paste(String text) {
        try {
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("nekosakana", text));
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) return false;
            return focused.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- 截图（Android 11+） ----

    public interface ShotCallback {
        void onShot(Bitmap bitmap);
    }

    public void screenshot(ShotCallback callback) {
        if (Build.VERSION.SDK_INT < 30) {
            callback.onShot(null);
            return;
        }
        try {
            // 用后台线程执行并做位图拷贝，避免主线程被整屏位图阻塞。
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
                    IO,
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            try (android.hardware.HardwareBuffer buffer = result.getHardwareBuffer()) {
                                Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                                Bitmap bitmap = hardware == null ? null : hardware.copy(Bitmap.Config.ARGB_8888, false);
                                if (hardware != null) hardware.recycle();
                                callback.onShot(bitmap);
                            } catch (Throwable t) {
                                callback.onShot(null);
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            callback.onShot(null);
                        }
                    });
        } catch (Throwable t) {
            callback.onShot(null);
        }
    }

    // ---- 节点操作（供自动化用） ----

    public List<AccessibilityNodeInfo> findNodes(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        if (root == null) return result;
        collect(root, text, result);
        return result;
    }

    private void collect(AccessibilityNodeInfo node, String text, List<AccessibilityNodeInfo> out) {
        if (node == null || out.size() > 100) return;
        CharSequence t = node.getText();
        if (t != null && text != null && t.toString().contains(text)) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            collect(node.getChild(i), text, out);
        }
    }
}
