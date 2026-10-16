package app.nekosakana.caps;

import android.os.Handler;
import android.os.Looper;

import app.nekosakana.core.ControlLog;

/**
 * 远程输入注入。优先使用 Root/ADB 的 {@code input} 命令，
 * 否则回退到无障碍手势（见 docs/07-远程控制.md）。
 */
public final class Control {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadLocal<String> ERROR = new ThreadLocal<>();
    private static Process touchProcess;
    private static java.io.BufferedWriter touchWriter;
    private static java.io.BufferedReader touchReader;
    private static int touchBackend;
    private static long gestureStart;
    private static volatile long touchDisabledUntil;
    private static final java.util.List<float[]> touchPoints = new java.util.ArrayList<>();

    public static synchronized boolean touch(int action, float x, float y) {
        return touch(action, x, y, -1);
    }

    /**
     * @param durationMs 控制端实际按住时长（毫秒）；&gt;0 时用于手势时长，
     *                   避免网络往返被误判为长按。
     */
    public static synchronized boolean touch(int action, float x, float y, long durationMs) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || x < 0 || y < 0) return false;
        if (action != 0 && action != 1 && action != 2 && action != 3) return false;
        ERROR.remove();
        if (ShellX.hasPrivilege() && android.os.SystemClock.uptimeMillis() >= touchDisabledUntil) {
            try {
                int backend = ShellX.backend();
                boolean alive = touchProcess instanceof rikka.shizuku.ShizukuRemoteProcess
                        ? ((rikka.shizuku.ShizukuRemoteProcess) touchProcess).alive()
                        : touchProcess != null && touchProcess.isAlive();
                if (!alive || touchBackend != backend) {
                    closeTouch();
                    String apk = app.nekosakana.App.get().getApplicationInfo().sourceDir;
                    touchProcess = ShellX.openProcess(backend, "CLASSPATH=" + ShellX.quote(apk)
                            + " app_process / app.nekosakana.caps.TouchBridge");
                    touchBackend = backend;
                    touchWriter = new java.io.BufferedWriter(new java.io.OutputStreamWriter(touchProcess.getOutputStream()));
                    touchReader = new java.io.BufferedReader(new java.io.InputStreamReader(touchProcess.getInputStream()));
                    if (!"ready".equals(touchReader.readLine())) throw new java.io.IOException("触摸服务启动失败");
                }
                touchWriter.write(action + " " + x + " " + y + "\n");
                touchWriter.flush();
                String response = touchReader.readLine();
                if (!"ok".equals(response)) throw new java.io.IOException("触摸服务: " + response);
                return true;
            } catch (Throwable t) {
                ERROR.set(String.valueOf(t));
                closeTouch();
                // 失败后冷却 30s 再尝试特权注入，避免每个触摸事件都重启 app_process。
                touchDisabledUntil = android.os.SystemClock.uptimeMillis() + 30_000;
            }
        }
        if (!AccessService.isConnected()) return false;
        if (action == 0) { touchPoints.clear(); gestureStart = android.os.SystemClock.uptimeMillis(); }
        if (action == 3) { touchPoints.clear(); return true; }
        if (touchPoints.size() < 512) touchPoints.add(new float[]{x, y});
        if (action == 1) {
            long measured = durationMs > 0 ? durationMs : (android.os.SystemClock.uptimeMillis() - gestureStart);
            long duration = Math.max(40, Math.min(10000, measured));
            java.util.List<float[]> points = new java.util.ArrayList<>(touchPoints);
            touchPoints.clear();
            if (points.size() == 1) points.add(new float[]{x, y});
            return onAccess(s -> s.gesture(points, duration));
        }
        return true;
    }

    public static synchronized void closeTouch() {
        try { if (touchWriter != null) touchWriter.close(); } catch (Exception ignored) { }
        if (touchProcess != null) touchProcess.destroy();
        touchProcess = null;
        touchWriter = null;
        touchReader = null;
    }

    public static String error() {
        String detail = ERROR.get();
        if (detail != null && !detail.isEmpty()) return "输入失败：" + detail;
        return "需要在被控设备上授权 Shizuku 或开启无障碍服务";
    }

    private static boolean shell(String command) {
        ERROR.remove();
        if (!ShellX.hasPrivilege()) return false;
        ShellX.Result result = ShellX.exec(command);
        if (result.ok()) return true;
        ERROR.set(result.text());
        app.nekosakana.core.Logx.w("control", "注入失败 " + result);
        ShellX.invalidate();
        return false;
    }

    private Control() {
    }

    public static String backendName() {
        int b = ShellX.backend();
        if (b >= ShellX.SHIZUKU) return ShellX.name(b);
        if (AccessService.isConnected()) return "accessibility";
        return "none";
    }

    public static boolean available() {
        return ShellX.hasPrivilege() || AccessService.isConnected();
    }

    public static boolean tap(float x, float y) {
        ControlLog.add("in", "control.tap", "x=" + (int) x + " y=" + (int) y);
        if (shell("input tap " + (int) x + " " + (int) y)) return true;
        return onAccess(s -> s.tap(x, y));
    }

    public static boolean swipe(float x1, float y1, float x2, float y2, long duration) {
        ControlLog.add("in", "control.swipe", (int) x1 + "," + (int) y1 + " -> " + (int) x2 + "," + (int) y2);
        if (shell("input swipe " + (int) x1 + " " + (int) y1 + " "
                    + (int) x2 + " " + (int) y2 + " " + Math.max(50, Math.min(10000, duration)))) return true;
        return onAccess(s -> s.swipe(x1, y1, x2, y2, duration));
    }

    public static boolean longPress(float x, float y, long duration) {
        ControlLog.add("in", "control.longPress", "x=" + (int) x + " y=" + (int) y);
        if (shell("input swipe " + (int) x + " " + (int) y + " " + (int) x + " " + (int) y + " "
                    + Math.max(600, Math.min(10000, duration)))) return true;
        return onAccess(s -> s.longPress(x, y, duration));
    }

    public static boolean text(String value) {
        ControlLog.add("in", "control.text", mask(value));
        // Accessibility supports Unicode, unlike Android's shell input text.
        ERROR.remove();
        if (onAccess(s -> s.setText(value))) return true;
        return shell("input text " + ShellX.quote(value.replace(" ", "%s")));
    }

    public static boolean key(String keyName) {
        ControlLog.add("in", "control.key", keyName);
        if (keyName == null || !keyName.matches("KEYCODE_[A-Z0-9_]+|[0-9]+")) return false;
        if (shell("input keyevent " + keyName)) return true;
        AccessService s = AccessService.get();
        if (s == null) return false;
        switch (keyName == null ? "" : keyName) {
            case "KEYCODE_BACK":
                return onAccess(a -> a.back());
            case "KEYCODE_HOME":
                return onAccess(a -> a.home());
            case "KEYCODE_APP_SWITCH":
                return onAccess(a -> a.recents());
            case "KEYCODE_NOTIFICATION":
                return onAccess(a -> a.notifications());
            case "KEYCODE_POWER":
                return onAccess(a -> a.lockScreen());
            default:
                return false;
        }
    }

    public static boolean back() {
        return key("KEYCODE_BACK");
    }

    public static boolean home() {
        return key("KEYCODE_HOME");
    }

    public static boolean recents() {
        return key("KEYCODE_APP_SWITCH");
    }

    public static boolean power() {
        return key("KEYCODE_POWER");
    }

    private interface AccessAction {
        boolean run(AccessService service);
    }

    private static boolean onAccess(AccessAction action) {
        AccessService s = AccessService.get();
        if (s == null) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) return action.run(s);
        final boolean[] result = {false};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        MAIN.post(() -> {
            try {
                result[0] = action.run(s);
            } catch (Throwable ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            // 上限控制等待时间，避免主线程卡住时长时间拖住协议读取线程。
            latch.await(800, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return result[0];
    }

    private static String mask(String value) {
        if (value == null) return "";
        if (value.length() <= 24) return value;
        return value.substring(0, 24) + "…(" + value.length() + ")";
    }
}
