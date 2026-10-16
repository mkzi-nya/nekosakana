package app.nekosakana.caps;

import android.content.Context;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import app.nekosakana.core.Logx;

/**
 * 有 Root / Shizuku（adb shell 权限）时的直采视频：直接调用 {@code screenrecord} 输出
 * H.264 裸流，经 stdout 实时读取，不依赖屏幕录制授权。无权限时由上层改用 MediaProjection。
 */
public final class VideoShell {
    private static final VideoShell INSTANCE = new VideoShell();
    private static final String MIME = "video/avc";
    private static final int TIME_LIMIT_SECONDS = 180;

    private volatile boolean running;
    private volatile boolean started;
    private volatile boolean paused;
    private volatile Process process;
    private Thread thread;
    private volatile VideoShare.Sink sink;
    private int captureWidth, captureHeight, screenWidth, screenHeight;
    private int frameDurationUs = 33333;
    private volatile boolean restartRequested;

    private VideoShell() {
    }

    public static VideoShell get() {
        return INSTANCE;
    }

    public static boolean isRunning() {
        return INSTANCE.running;
    }

    /** 是否已产出可解码的码流（收到 SPS/PPS 并发出配置）。 */
    public static boolean isStarted() {
        return INSTANCE.started;
    }

    public synchronized boolean start(Context context, int targetWidth, int fps, int bitrate, VideoShare.Sink output) {
        stop();
        if (!ShellX.hasPrivilege()) return false;
        DisplayMetrics metrics = new DisplayMetrics();
        ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        int even = Math.max(2, Math.min(screenWidth, targetWidth) & ~1);
        captureWidth = even;
        captureHeight = Math.max(2, Math.round(even * (float) screenHeight / screenWidth) & ~1);
        frameDurationUs = 1_000_000 / Math.max(1, Math.min(60, fps));
        sink = output;
        running = true;
        started = false;
        paused = false;
        restartRequested = false;
        thread = new Thread(() -> runLoop(bitrate), "video-shell");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /** 暂停/恢复发送（进程保持，恢复时请求关键帧）。 */
    public void setPaused(boolean value) {
        paused = value;
    }

    public boolean isPaused() {
        return paused;
    }

    /** 请求关键帧：重启一次 screenrecord，新进程开头即为 SPS/PPS/IDR。 */
    public void requestKeyFrame() {
        restartRequested = true;
        Process current = process;
        if (current != null) {
            try {
                current.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    public synchronized void updateSink(VideoShare.Sink output) {
        sink = output;
    }

    private static final long MIN_RESTART_INTERVAL_MS = 900;

    private void runLoop(int bitrate) {
        int failures = 0;
        boolean withSize = true;
        long lastStart = 0;
        while (running) {
            restartRequested = false;
            // 关键：保证同一时刻只有一个 screenrecord 在跑，且两次启动之间有最小间隔，
            // 否则反复起进程会压垮 SurfaceFlinger（在华为等机型上可触发看门狗重启）。
            killProcess();
            long since = SystemClock.uptimeMillis() - lastStart;
            if (since < MIN_RESTART_INTERVAL_MS) {
                try {
                    Thread.sleep(MIN_RESTART_INTERVAL_MS - since);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!running) break;
            Process p = null;
            boolean produced = false;
            lastStart = SystemClock.uptimeMillis();
            try {
                p = ShellX.openProcess(ShellX.backend(), command(bitrate, withSize));
                process = p;
                produced = parse(p.getInputStream());
            } catch (Throwable t) {
                Logx.w("vshell", "采集异常 " + t);
            } finally {
                killProcess();
                p = null;
            }
            if (!running) break;
            if (produced) {
                failures = 0;
                withSize = true;
            } else if (withSize) {
                // 指定分辨率不被支持时，退回整屏分辨率再试。
                withSize = false;
                Logx.w("vshell", "指定尺寸采集失败，改用整屏分辨率");
            } else if (++failures >= 2) {
                Logx.w("vshell", "screenrecord 不可用，停止直采");
                running = false;
                break;
            }
            long backoff = produced ? 0 : Math.min(4000, 400L * (failures + 1));
            if (backoff > 0) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        killProcess();
    }

    /** 结束当前 screenrecord 进程并等待其真正退出，避免进程堆积。 */
    private void killProcess() {
        Process p = process;
        process = null;
        if (p == null) return;
        try {
            p.destroy();
        } catch (Throwable ignored) {
        }
        try {
            if (!p.waitFor(600, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                p.waitFor(400, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (Throwable ignored) {
        }
    }

    private String command(int bitrate, boolean withSize) {
        StringBuilder sb = new StringBuilder("screenrecord --output-format=h264");
        sb.append(" --bit-rate ").append(Math.max(500_000, bitrate));
        sb.append(" --time-limit ").append(TIME_LIMIT_SECONDS);
        if (withSize) sb.append(" --size ").append(captureWidth).append('x').append(captureHeight);
        sb.append(" -");
        return sb.toString();
    }

    private boolean parse(InputStream rawIn) throws Exception {
        BufferedInputStream in = new BufferedInputStream(rawIn, 64 * 1024);
        Assembler assembler = new Assembler();
        byte[] buf = new byte[32 * 1024];
        boolean first = true;
        boolean any = false;
        int n;
        while (running && (n = in.read(buf)) > 0) {
            if (first) {
                first = false;
                String head = new String(buf, 0, Math.min(n, 32), StandardCharsets.US_ASCII);
                if (head.startsWith("Encoder failed") || head.startsWith("Error")
                        || head.startsWith("Unable to")) {
                    Logx.w("vshell", "screenrecord 报错：" + head.trim());
                    running = false;
                    return false;
                }
            }
            any = true;
            assembler.feed(buf, n);
        }
        assembler.finish();
        return assembler.produced;
    }

    public synchronized void stop() {
        running = false;
        started = false;
        paused = false;
        killProcess();
        Thread worker = thread;
        thread = null;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 线程退出后再确认一次，杜绝残留 screenrecord。
        killProcess();
        sink = null;
    }

    /** 把 H.264 Annex-B 字节流切成访问单元（每帧一个），并提取 SPS/PPS 作为 csd。 */
    private final class Assembler {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final ByteArrayOutputStream au = new ByteArrayOutputStream();
        private boolean auHasVcl;
        private boolean auHasIdr;
        private boolean configSent;
        private volatile boolean produced;
        private byte[] sps, pps;
        private long pts;

        void feed(byte[] data, int length) {
            buffer.write(data, 0, length);
            byte[] bytes = buffer.toByteArray();
            int cursor = 0;
            int start = findStartCode(bytes, 0);
            while (start >= 0) {
                int next = findStartCode(bytes, start + 3);
                if (next < 0) break;
                int headerLen = startCodeLength(bytes, start);
                int nalStart = start + headerLen;
                if (nalStart < next) handleNal(bytes, nalStart, next);
                cursor = next;
                start = next;
            }
            if (cursor > 0) {
                buffer.reset();
                buffer.write(bytes, cursor, bytes.length - cursor);
            } else if (bytes.length > 4 * 1024 * 1024) {
                buffer.reset();
            }
        }

        void finish() {
            byte[] bytes = buffer.toByteArray();
            int start = findStartCode(bytes, 0);
            if (start >= 0) {
                int headerLen = startCodeLength(bytes, start);
                if (start + headerLen < bytes.length) handleNal(bytes, start + headerLen, bytes.length);
            }
            flushAu();
        }

        private void handleNal(byte[] bytes, int from, int to) {
            int type = bytes[from] & 0x1F;
            boolean vcl = type == 1 || type == 5;
            boolean boundary = (type == 9 || type == 7 || type == 8) && auHasVcl;
            if (boundary || (vcl && auHasVcl)) {
                flushAu();
            }
            if (type == 7) {
                sps = slice(bytes, from, to);
            } else if (type == 8) {
                pps = slice(bytes, from, to);
            }
            if (!configSent && sps != null && pps != null) {
                configSent = true;
                produced = true;
                started = true;
                VideoShare.Sink out = sink;
                if (out != null) {
                    out.onConfig(MIME, captureWidth, captureHeight, screenWidth, screenHeight, sps, pps);
                }
            }
            // AU 从起始码开始写入，保证关键帧自带参数集。
            int start = from - startCodeLengthAt(bytes, from);
            au.write(bytes, start, to - start);
            if (vcl) {
                auHasVcl = true;
                if (type == 5) auHasIdr = true;
            }
        }

        private void flushAu() {
            if (au.size() > 0 && auHasVcl) {
                VideoShare.Sink out = sink;
                if (out != null && !paused) {
                    out.onFrame(au.toByteArray(), auHasIdr, pts);
                }
                pts += frameDurationUs;
            }
            au.reset();
            auHasVcl = false;
            auHasIdr = false;
        }

        private int startCodeLengthAt(byte[] bytes, int nalStart) {
            if (nalStart >= 4 && bytes[nalStart - 4] == 0 && bytes[nalStart - 3] == 0
                    && bytes[nalStart - 2] == 0 && bytes[nalStart - 1] == 1) return 4;
            return 3;
        }
    }

    private static byte[] slice(byte[] bytes, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(bytes, from, out, 0, out.length);
        return out;
    }

    private static int findStartCode(byte[] bytes, int from) {
        for (int i = Math.max(0, from); i + 3 <= bytes.length; i++) {
            if (bytes[i] == 0 && bytes[i + 1] == 0) {
                if (bytes[i + 2] == 1) return i;
                if (i + 3 < bytes.length && bytes[i + 2] == 0 && bytes[i + 3] == 1) return i;
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] bytes, int at) {
        if (at + 3 < bytes.length && bytes[at + 2] == 0 && bytes[at + 3] == 1) return 4;
        return 3;
    }
}
