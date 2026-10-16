package app.nekosakana.caps;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import app.nekosakana.core.Logx;

/**
 * 硬件视频解码。把 {@code screen.video} 收到的编码帧解码到 {@link Surface} 直接渲染，
 * 输入与解码在独立线程，网络线程只做入队，避免阻塞收包。
 */
public final class VideoPlay {
    public interface Listener {
        void onSize(int width, int height);

        void onError(String message);

        void onFirstFrame();

        void onNeedsKeyFrame();
    }

    // 队列保持较小以限制端到端延迟（8 帧 @20fps ≈ 0.4s）。
    private static final int MAX_QUEUE = 8;

    private MediaCodec codec;
    private Surface surface;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean waitingKey = true;
    private volatile Listener listener;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private int width, height;
    private boolean firstFrameReported;

    public synchronized boolean configure(String mime, int w, int h, byte[] csd0, byte[] csd1,
                                          Surface output, Listener l) {
        release();
        if (output == null) return false;
        width = w;
        height = h;
        surface = output;
        listener = l;
        firstFrameReported = false;
        waitingKey = true;
        queue.clear();
        try {
            MediaFormat format = MediaFormat.createVideoFormat(mime, Math.max(16, w), Math.max(16, h));
            if (csd0 != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
            if (csd1 != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1));
            try {
                // API 30+：低延迟解码，关闭输出重排/内部缓冲，减少画面滞后。
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            } catch (Throwable ignored) {
            }
            try {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            } catch (Throwable ignored) {
            }
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, output, null, 0);
            codec.start();
            running = true;
            thread = new Thread(this::loop, "video-decode");
            thread.setDaemon(true);
            thread.start();
            return true;
        } catch (Throwable t) {
            Logx.e("video", "解码启动失败 " + t);
            release();
            if (l != null) l.onError(String.valueOf(t));
            return false;
        }
    }

    public void feed(byte[] data, boolean key) {
        if (!running || data == null) return;
        if (waitingKey) {
            if (!key) return;
            waitingKey = false;
        }
        if (queue.size() >= MAX_QUEUE) {
            // 积压过多说明解码跟不上：丢弃并在下一个关键帧重新同步，避免延迟持续增大。
            queue.clear();
            waitingKey = !key;
            Listener l = listener;
            if (l != null) l.onNeedsKeyFrame();
            if (!key) return;
        }
        queue.offer(data);
    }

    public void setSurface(Surface target) {
        if (target == null) return;
        surface = target;
        MediaCodec current = codec;
        if (current != null) {
            try {
                current.setOutputSurface(target);
            } catch (Throwable t) {
                Logx.w("video", "切换解码输出 Surface 失败 " + t);
            }
        }
    }

    public Surface surface() {
        return surface;
    }

    public boolean isRunning() {
        return running;
    }

    private void loop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long pts = 0;
        while (running) {
            try {
                byte[] data = queue.poll(50, TimeUnit.MILLISECONDS);
                if (data != null) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(inIndex);
                        if (buffer != null) {
                            buffer.clear();
                            if (data.length <= buffer.capacity()) {
                                buffer.put(data);
                                codec.queueInputBuffer(inIndex, 0, data.length, pts++, 0);
                            } else {
                                codec.queueInputBuffer(inIndex, 0, 0, pts++, 0);
                            }
                        }
                    } else {
                        queue.offer(data);
                    }
                }
                drain(info);
            } catch (IllegalStateException e) {
                if (running) Logx.e("video", "解码状态异常 " + e);
                break;
            } catch (Throwable t) {
                if (running) Logx.e("video", "解码失败 " + t);
            }
        }
    }

    private void drain(MediaCodec.BufferInfo info) {
        while (true) {
            int outIndex = codec.dequeueOutputBuffer(info, 0);
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                int w = width;
                int h = height;
                try {
                    MediaFormat of = codec.getOutputFormat();
                    w = of.getInteger(MediaFormat.KEY_WIDTH, w);
                    h = of.getInteger(MediaFormat.KEY_HEIGHT, h);
                } catch (Throwable ignored) {
                }
                Listener l = listener;
                if (l != null) l.onSize(w, h);
                continue;
            }
            if (outIndex < 0) return;
            boolean render = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
            codec.releaseOutputBuffer(outIndex, render);
            if (render && !firstFrameReported) {
                firstFrameReported = true;
                Logx.i("video", "解码首帧 " + width + "x" + height);
                Listener l = listener;
                if (l != null) l.onFirstFrame();
            }
        }
    }

    public synchronized void release() {
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
        queue.clear();
        listener = null;
        surface = null;
    }
}
