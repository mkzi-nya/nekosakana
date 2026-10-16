package app.nekosakana.caps;

import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import app.nekosakana.core.Logx;
import rikka.shizuku.Shizuku;

/**
 * 统一 shell 执行入口，按优先级自动选择后端：
 * ROOT（su） &gt; SHIZUKU（adb/无线调试授权） &gt; APP（应用自身权限）。
 *
 * <p>所有方法都可能阻塞，必须在工作线程调用。</p>
 */
public final class ShellX {
    public static final int NONE = 0;
    public static final int APP = 1;
    public static final int SHIZUKU = 2;
    public static final int ROOT = 3;

    private static volatile int cachedBackend = -1;

    public static void init() {
        Shizuku.addBinderReceivedListenerSticky(ShellX::invalidate);
        Shizuku.addBinderDeadListener(ShellX::invalidate);
        Shizuku.addRequestPermissionResultListener((code, result) -> invalidate());
    }

    private ShellX() {
    }

    public static final class Result {
        public final int backend;
        public final int code;
        public final String out;
        public final String err;

        Result(int backend, int code, String out, String err) {
            this.backend = backend;
            this.code = code;
            this.out = out == null ? "" : out;
            this.err = err == null ? "" : err;
        }

        public boolean ok() {
            return code == 0;
        }

        public String text() {
            String s = out.trim();
            if (s.isEmpty()) s = err.trim();
            return s;
        }

        @Override
        public String toString() {
            return "[" + name(backend) + "] code=" + code + (out.isEmpty() ? "" : "\n" + out) + (err.isEmpty() ? "" : "\n" + err);
        }
    }

    public static String name(int backend) {
        switch (backend) {
            case ROOT:
                return "root";
            case SHIZUKU:
                return "adb/shizuku";
            case APP:
                return "app";
            default:
                return "none";
        }
    }

    /** 探测并缓存可用后端。 */
    public static int backend() {
        if (cachedBackend == ROOT) return ROOT;
        if (shizukuReady()) return SHIZUKU;
        if (cachedBackend >= 0) return APP;
        int b = APP;
        if (shizukuReady()) b = SHIZUKU;
        if (rootReady()) b = ROOT;
        cachedBackend = b;
        Logx.i("shell", "后端=" + name(b));
        return b;
    }

    public static void invalidate() {
        cachedBackend = -1;
    }

    public static boolean hasRoot() {
        return backend() == ROOT;
    }

    public static boolean hasShizuku() {
        int b = backend();
        return b == SHIZUKU || b == ROOT;
    }

    public static boolean hasPrivilege() {
        return backend() >= SHIZUKU;
    }

    private static boolean shizukuReady() {
        try {
            if (!Shizuku.pingBinder()) return false;
            if (Shizuku.isPreV11()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile String[] rootPrefix;

    private static boolean rootReady() {
        // Magisk / 多数 root 用 su -c；AOSP（模拟器、部分设备）用 su 0 sh -c。
        if (probeRoot(new String[]{"su", "-c"})) return true;
        if (probeRoot(new String[]{"su", "0", "sh", "-c"})) return true;
        return false;
    }

    private static boolean probeRoot(String[] prefix) {
        try {
            String[] argv = new String[prefix.length + 1];
            System.arraycopy(prefix, 0, argv, 0, prefix.length);
            argv[prefix.length] = "id";
            Process p = Runtime.getRuntime().exec(argv);
            boolean done = p.waitFor(4, TimeUnit.SECONDS);
            if (!done) {
                p.destroy();
                return false;
            }
            String out = readAll(p.getInputStream()) + readAll(p.getErrorStream());
            if (out.contains("uid=0")) {
                rootPrefix = prefix;
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Process rootProcess(String command) throws Exception {
        String[] prefix = rootPrefix != null ? rootPrefix : new String[]{"su", "-c"};
        String[] argv = new String[prefix.length + 1];
        System.arraycopy(prefix, 0, argv, 0, prefix.length);
        argv[prefix.length] = command;
        return Runtime.getRuntime().exec(argv);
    }

    /** Shizuku 的 newProcess 在 13.x 为私有方法，此处通过反射调用。 */
    private static Process shizukuProcess(String command) throws Exception {
        java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, new Object[]{new String[]{"sh", "-c", command}, null, null});
    }

    public static Process openProcess(int backend, String command) throws Exception {
        if (backend == ROOT) return rootProcess(command);
        if (backend == SHIZUKU) return shizukuProcess(command);
        return Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
    }

    public static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public static final class BinaryResult {
        public final int code;
        public final byte[] out;
        public final String err;
        BinaryResult(int code, byte[] out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }
    }

    private static boolean await(Process process, long timeoutMs) throws Exception {
        // Android Process.waitFor(timeout) is not implemented by ShizukuRemoteProcess.
        if (process instanceof rikka.shizuku.ShizukuRemoteProcess) {
            return ((rikka.shizuku.ShizukuRemoteProcess) process).waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        }
        return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private static final class Drain extends Thread {
        final InputStream input;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Drain(InputStream input) { this.input = input; setDaemon(true); }
        @Override public void run() {
            try (InputStream stream = input) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = stream.read(buffer)) != -1) {
                    synchronized (bytes) {
                        if (bytes.size() + n > 32 * 1024 * 1024) break;
                        bytes.write(buffer, 0, n);
                    }
                }
            } catch (Exception ignored) { }
        }
        byte[] result() { synchronized (bytes) { return bytes.toByteArray(); } }
    }

    public static BinaryResult execBytes(int backend, long timeoutMs, byte[] input, String command) {
        Process process = null;
        try {
            process = openProcess(backend, command);
            Drain out = new Drain(process.getInputStream());
            Drain err = new Drain(process.getErrorStream());
            out.start();
            err.start();
            Process child = process;
            Thread writer = new Thread(() -> {
                try (OutputStream os = child.getOutputStream()) {
                    if (input != null) os.write(input);
                } catch (Exception ignored) { }
            }, "shell-input");
            writer.setDaemon(true);
            writer.start();
            boolean done = await(process, timeoutMs);
            if (!done) process.destroy();
            out.join(1000);
            err.join(1000);
            return new BinaryResult(done ? process.exitValue() : -1, out.result(),
                    new String(err.result(), StandardCharsets.UTF_8) + (done ? "" : "\n[超时]"));
        } catch (Throwable t) {
            return new BinaryResult(-1, new byte[0], String.valueOf(t));
        } finally {
            if (process != null) process.destroy();
        }
    }

    /** 以指定后端执行命令。backend 传 0 表示自动选择。 */
    public static Result exec(int forceBackend, long timeoutMs, String command) {
        int b = forceBackend > 0 ? forceBackend : backend();
        BinaryResult result = execBytes(b, timeoutMs, null, command);
        return new Result(b, result.code, new String(result.out, StandardCharsets.UTF_8), result.err);
    }

    public static Result exec(String command) {
        return exec(0, 15000, command);
    }

    public static Result exec(long timeoutMs, String command) {
        return exec(0, timeoutMs, command);
    }

    /** 执行并把 stdin 写入进程，用于 pm install 等。 */
    public static Result execWithInput(long timeoutMs, byte[] input, String command) {
        int b = backend();
        BinaryResult result = execBytes(b, timeoutMs, input, command);
        return new Result(b, result.code, new String(result.out, StandardCharsets.UTF_8), result.err);
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } catch (Exception ignored) {
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 便捷：读取单行输出的命令。 */
    public static String execLine(String command) {
        return exec(command).text();
    }

}
