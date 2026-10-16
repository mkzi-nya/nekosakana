package app.nekosakana.core;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实时文件日志。所有日志写入 &lt;存储根&gt;/logs/nekosakana.log，
 * 超过阈值时使用 7z 压缩归档（见 docs/05-日志与归档.md）。
 * 同时维护内存环形缓冲供 UI 展示。
 */
public final class Logx {
    private static final Object LOCK = new Object();
    private static final int RING_MAX = 400;
    private static final long ROTATE_BYTES = 2L * 1024 * 1024;
    private static final Deque<String> RING = new ArrayDeque<>();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "logx-writer");
        t.setDaemon(true);
        return t;
    });
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static volatile File logFile;
    private static volatile File archiveDir;
    private static final AtomicBoolean rotating = new AtomicBoolean(false);

    private Logx() {
    }

    public static void init(Context context) {
        try {
            logFile = Store.get().file("logs/nekosakana.log");
            archiveDir = Store.get().dir("logs/archive");
        } catch (Exception e) {
            logFile = null;
        }
    }

    public static void i(String tag, String msg) {
        log('I', tag, msg);
    }

    public static void w(String tag, String msg) {
        log('W', tag, msg);
    }

    public static void e(String tag, String msg) {
        log('E', tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        log('E', tag, msg + " :: " + android.util.Log.getStackTraceString(t));
    }

    public static void log(char level, String tag, String msg) {
        String line = TIME.format(new Date()) + " [" + level + "] " + tag + ": " + msg;
        synchronized (LOCK) {
            RING.addLast(line);
            while (RING.size() > RING_MAX) RING.removeFirst();
        }
        final File out = logFile;
        if (out == null) return;
        WRITER.execute(() -> append(out, line));
    }

    private static void append(File out, String line) {
        try {
            if (out.length() > ROTATE_BYTES && rotating.compareAndSet(false, true)) {
                rotate(out);
                rotating.set(false);
            }
            try (FileWriter writer = new FileWriter(out, true)) {
                writer.write(line);
                writer.write('\n');
            }
        } catch (IOException ignored) {
        }
    }

    private static void rotate(File out) {
        File archive = new File(archiveDir, "log-" + System.currentTimeMillis() + ".7z");
        List<File> single = new ArrayList<>();
        single.add(out);
        if (Archive.compress(single, archive)) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            logFile = Store.get().file("logs/nekosakana.log");
        }
    }

    /** 内存紧张时收缩环形缓冲，降低内存占用（磁盘日志不受影响）。 */
    public static void trim(int keep) {
        synchronized (LOCK) {
            while (RING.size() > keep) RING.removeFirst();
        }
    }

    public static List<String> tail(int n) {
        synchronized (LOCK) {
            int size = RING.size();
            List<String> all = new ArrayList<>(RING);
            int from = Math.max(0, size - n);
            return all.subList(from, size);
        }
    }

    public static String dump(int n) {
        StringBuilder sb = new StringBuilder();
        for (String line : tail(n)) sb.append(line).append('\n');
        return sb.toString();
    }
}
