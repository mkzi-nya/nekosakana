package app.nekosakana.caps;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;

import java.nio.ByteBuffer;

import app.nekosakana.core.Logx;

/**
 * 硬件视频编码（优先 H.265/HEVC，回退 H.264/AVC）。
 * 直接把 MediaProjection 画面接到 MediaCodec 的输入 Surface，
 * 输出编码后的关键帧/帧间帧，交由上层按 {@code screen.video} 发送。
 */
public final class VideoShare {
    public interface Sink {
        void onConfig(String mime, int videoWidth, int videoHeight, int screenWidth, int screenHeight,
                      byte[] csd0, byte[] csd1);

        void onFrame(byte[] data, boolean key, long ptsUs);
    }

    private static final VideoShare INSTANCE = new VideoShare();

    private MediaCodec codec;
    private Surface inputSurface;
    private VirtualDisplay display;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile Sink sink;
    private volatile String mime = MediaFormat.MIMETYPE_VIDEO_AVC;
    private int width, height, screenWidth, screenHeight;
    private byte[] lastCsd0, lastCsd1;

    private VideoShare() {
    }

    public static VideoShare get() {
        return INSTANCE;
    }

    public static boolean isRunning() {
        return INSTANCE.running;
    }

    /** 设备是否支持该编码器的 Surface 输入（硬编或系统软编）。 */
    public static boolean supports(String mime) {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(mime)) continue;
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    for (int color : caps.colorFormats) {
                        if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 为该 MIME 选择一个支持 Surface 输入的编码器名称，优先硬件加速（避免误用软编导致卡顿）；
     * 无硬编时回退到软编名称，仍无则返回 null（由调用方走 createEncoderByType）。
     */
    public static String findEncoder(String mime) {
        String software = null;
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(mime)) continue;
                    if (!surfaceCapable(info, type)) continue;
                    if (info.isHardwareAccelerated()) return info.getName();
                    if (software == null) software = info.getName();
                }
            }
        } catch (Throwable ignored) {
        }
        return software;
    }

    private static boolean surfaceCapable(MediaCodecInfo info, String type) {
        try {
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
            for (int color : caps.colorFormats) {
                if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 选择可用编码：按优先顺序返回 MIME，均不可用返回 null。 */
    public static String pickCodec(String[] preference) {
        if (preference != null) {
            for (String mime : preference) {
                if (mime != null && supports(mime)) return mime;
            }
        }
        if (supports(MediaFormat.MIMETYPE_VIDEO_HEVC)) return MediaFormat.MIMETYPE_VIDEO_HEVC;
        if (supports(MediaFormat.MIMETYPE_VIDEO_AVC)) return MediaFormat.MIMETYPE_VIDEO_AVC;
        return null;
    }

    public synchronized boolean start(Context context, MediaProjection projection, int targetWidth,
                                      int fps, int bitrate, String preferredMime, Sink output) {
        stop();
        paused = false;
        if (projection == null) return false;
        java.util.List<String> candidates = new java.util.ArrayList<>();
        if (preferredMime != null && supports(preferredMime)) candidates.add(preferredMime);
        String alternate = MediaFormat.MIMETYPE_VIDEO_HEVC.equals(preferredMime)
                ? MediaFormat.MIMETYPE_VIDEO_AVC : MediaFormat.MIMETYPE_VIDEO_HEVC;
        if (supports(alternate)) candidates.add(alternate);
        if (candidates.isEmpty()) candidates.add(MediaFormat.MIMETYPE_VIDEO_AVC);
        for (String candidate : candidates) {
            if (beginPipeline(context, projection, targetWidth, fps, bitrate, candidate, output)) return true;
        }
        Logx.w("video", "无可用硬件编码器");
        return false;
    }

    private boolean beginPipeline(Context context, MediaProjection projection, int targetWidth,
                                  int fps, int bitrate, String candidate, Sink output) {
        DisplayMetrics metrics = new DisplayMetrics();
        ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
        int srcW = metrics.widthPixels;
        int srcH = metrics.heightPixels;
        int encW = align16(Math.max(240, Math.min(srcW, targetWidth)));
        int encH = align16(Math.max(240, Math.round(encW * (float) srcH / srcW)));
        MediaCodec c = null;
        Surface surface = null;
        VirtualDisplay vd = null;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(candidate, encW, encH);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(500_000, bitrate));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, Math.max(1, Math.min(60, fps)));
            // 低延迟优先：关键帧间隔 1s、禁用 B 帧、实时优先级、低延迟模式，避免解码端积压造成卡顿。
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            try {
                format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            } catch (Throwable ignored) {
            }
            try {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            } catch (Throwable ignored) {
            }
            try {
                // API 30+：关闭编码器内部缓冲/重排，显著降低端到端延迟。
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            } catch (Throwable ignored) {
            }
            try {
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, Math.max(1, Math.min(60, fps)));
            } catch (Throwable ignored) {
            }
            try {
                format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            } catch (Throwable ignored) {
            }
            try {
                if (MediaFormat.MIMETYPE_VIDEO_AVC.equals(candidate)) {
                    format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                    format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
                } else {
                    format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
                }
            } catch (Throwable ignored) {
            }
            String encoderName = findEncoder(candidate);
            c = encoderName != null ? MediaCodec.createByCodecName(encoderName)
                    : MediaCodec.createEncoderByType(candidate);
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = c.createInputSurface();
            c.start();
            // 回调统一由 ScreenShare 在获得投影时注册（MediaProjection 只能注册一个回调），
            // 这里确保存在即可，避免重复注册抛 IllegalStateException 导致直采失败退化成截图。
            ScreenShare.get().ensureProjectionCallback(projection);
            vd = projection.createVirtualDisplay("nekosakana-video", encW, encH, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null);
            codec = c;
            inputSurface = surface;
            display = vd;
            mime = candidate;
            width = encW;
            height = encH;
            screenWidth = srcW;
            screenHeight = srcH;
            sink = output;
            running = true;
            thread = new Thread(this::loop, "video-encode");
            thread.setDaemon(true);
            thread.start();
            Logx.i("video", "开始 " + candidate + " " + encW + "x" + encH + " " + bitrate + "bps " + fps + "fps");
            return true;
        } catch (Throwable t) {
            Logx.w("video", candidate + " 不可用：" + t);
            if (vd != null) {
                try {
                    vd.release();
                } catch (Throwable ignored) {
                }
            }
            if (c != null) {
                try {
                    c.stop();
                } catch (Throwable ignored) {
                }
                try {
                    c.release();
                } catch (Throwable ignored) {
                }
            }
            if (surface != null) {
                try {
                    surface.release();
                } catch (Throwable ignored) {
                }
            }
            return false;
        }
    }

    private void loop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int frameCount = 0;
        while (running) {
            int index;
            try {
                index = codec.dequeueOutputBuffer(info, 10000);
            } catch (Throwable t) {
                if (running) {
                    String detail = "";
                    if (t instanceof MediaCodec.CodecException) {
                        MediaCodec.CodecException ce = (MediaCodec.CodecException) t;
                        detail = " code=" + ce.getErrorCode() + " recoverable=" + ce.isRecoverable()
                                + " transient=" + ce.isTransient() + " diag=" + ce.getDiagnosticInfo();
                    }
                    Logx.e("video", "编码出队失败 " + t + detail, t);
                    running = false;
                }
                break;
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                lastCsd0 = copyBytes(of.getByteBuffer("csd-0"));
                lastCsd1 = copyBytes(of.getByteBuffer("csd-1"));
                Sink out = sink;
                if (out != null) {
                    out.onConfig(mime, width, height, screenWidth, screenHeight, lastCsd0, lastCsd1);
                }
                continue;
            }
            if (index < 0) continue;
            try {
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    byte[] data = new byte[info.size];
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    buffer.get(data);
                    Sink out = sink;
                    if (out != null && !paused) {
                        out.onFrame(data, (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0, info.presentationTimeUs);
                    }
                    frameCount++;
                    if (frameCount % 300 == 0) {
                        Logx.i("video", "已发送 " + frameCount + " 帧");
                    }
                }
            } catch (Throwable t) {
                Logx.e("video", "取编码数据失败 " + t);
            } finally {
                try {
                    codec.releaseOutputBuffer(index, false);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 暂停只停止发送，不释放 MediaProjection：再次共享时无需二次授权
     * （Android 14 不允许用同一令牌重新创建采集）。
     */
    public void setPaused(boolean value) {
        paused = value;
    }

    public boolean isPaused() {
        return paused;
    }

    /** 断开重连后复用仍在运行的编码器：更新接收方并补发配置。 */
    public synchronized void updateSink(Sink output) {
        sink = output;
    }

    /** 重新发送编解码配置（新控制端接入时调用）。 */
    public synchronized void emitConfig() {
        Sink out = sink;
        if (out != null && running && width > 0 && height > 0) {
            out.onConfig(mime, width, height, screenWidth, screenHeight, lastCsd0, lastCsd1);
        }
    }

    public void requestKeyFrame() {
        MediaCodec current = codec;
        if (current == null) return;
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            current.setParameters(params);
        } catch (Throwable ignored) {
        }
    }

    public synchronized void stop() {
        running = false;
        Thread worker = thread;
        thread = null;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (display != null) {
            try {
                display.release();
            } catch (Throwable ignored) {
            }
            display = null;
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            try {
                codec.release();
            } catch (Throwable ignored) {
            }
            codec = null;
        }
        if (inputSurface != null) {
            try {
                inputSurface.release();
            } catch (Throwable ignored) {
            }
            inputSurface = null;
        }
        sink = null;
        width = height = 0;
        lastCsd0 = lastCsd1 = null;
    }

    private static int align16(int value) {
        return Math.max(16, (value / 16) * 16);
    }

    private static byte[] copyBytes(ByteBuffer buffer) {
        if (buffer == null) return null;
        buffer.rewind();
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);
        return out;
    }
}
