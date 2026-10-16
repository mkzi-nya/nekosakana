package app.nekosakana.caps;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import app.nekosakana.core.Logx;

/**
 * 接收端音频播放：解码 AAC(ADTS) 并输出到 AudioTrack。
 * 配合 {@link AudioShare}（媒体回放）与 {@link MicShare}（麦克风）使用（见 docs/07-远程控制.md）。
 * 采样率与声道按首帧 ADTS 头自动适配，因此单声道麦克风与双声道回放可共用同一播放器。
 *
 * <p>解码与 AudioTrack 写入在独立线程完成：{@link #feed} 仅入队不阻塞，
 * 避免网络读线程被音频解码/播放背压卡住，导致控制指令回包延迟超时。</p>
 */
public final class AudioPlayer {
    private static final int[] RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
    private static final int MAX_PENDING = 96;

    private static AudioPlayer instance;

    private MediaCodec decoder;
    private AudioTrack track;
    private volatile boolean playing;
    private int currentRate;
    private int currentChannels;
    private final Object[] lock = new Object[0];
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audio-player");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger pending = new AtomicInteger();

    private AudioPlayer() {
    }

    public static synchronized AudioPlayer get() {
        if (instance == null) instance = new AudioPlayer();
        return instance;
    }

    public boolean isPlaying() {
        return playing;
    }

    public synchronized void start() {
        if (playing) return;
        playing = true;
        Logx.i("audio", "播放器已启动");
    }

    /** 输入一帧 ADTS AAC。非阻塞：解码在独立线程排队处理。 */
    public void feed(byte[] adts) {
        if (!playing || adts == null || adts.length <= 7) return;
        // 积压过多（网络抖动或解码落后）时丢弃旧帧，保证低延迟、避免内存膨胀。
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet();
            return;
        }
        worker.execute(() -> {
            try {
                process(adts);
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    private void process(byte[] adts) {
        if (!playing) return;
        synchronized (lock) {
            try {
                int rateIndex = (adts[2] >> 2) & 0x0F;
                int channels = ((adts[2] & 0x01) << 2) | ((adts[3] >> 6) & 0x03);
                int sampleRate = RATES[Math.max(0, Math.min(RATES.length - 1, rateIndex))];
                if (channels <= 0) channels = 2;
                if (decoder == null || sampleRate != currentRate || channels != currentChannels) {
                    if (!configure(sampleRate, channels, rateIndex)) return;
                }

                int frameLength = ((adts[3] & 0x03) << 11) | ((adts[4] & 0xFF) << 3) | ((adts[5] & 0xE0) >> 5);
                int headerLen = (adts[1] & 0x01) == 0 ? 9 : 7;
                if (frameLength <= headerLen || frameLength > adts.length) {
                    frameLength = adts.length;
                    headerLen = 7;
                }
                int payloadLen = frameLength - headerLen;
                if (payloadLen <= 0) return;

                int inIndex = decoder.dequeueInputBuffer(0);
                if (inIndex >= 0) {
                    ByteBuffer inBuf = decoder.getInputBuffer(inIndex);
                    if (inBuf != null) {
                        inBuf.clear();
                        inBuf.put(adts, headerLen, payloadLen);
                        decoder.queueInputBuffer(inIndex, 0, payloadLen, 0, 0);
                    }
                }
                drain();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 按采样率与声道数（重）建解码器与 AudioTrack。 */
    private boolean configure(int sampleRate, int channels, int rateIndex) {
        releasePipeline();
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd(rateIndex, channels)));
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            decoder.configure(format, null, null, 0);
            decoder.start();

            int channelMask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuffer, 32768))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
            currentRate = sampleRate;
            currentChannels = channels;
            return true;
        } catch (Throwable t) {
            Logx.e("audio", "播放器配置失败 " + t);
            releasePipeline();
            return false;
        }
    }

    /** AAC AudioSpecificConfig：AAC-LC + 采样率索引 + 声道数。 */
    private static byte[] csd(int rateIndex, int channels) {
        return new byte[]{
                (byte) ((2 << 3) | ((rateIndex >> 1) & 0x07)),
                (byte) (((rateIndex & 0x01) << 7) | ((channels & 0x0F) << 3))
        };
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int outIndex;
        while ((outIndex = decoder.dequeueOutputBuffer(info, 0)) >= 0) {
            ByteBuffer buffer = decoder.getOutputBuffer(outIndex);
            if (buffer != null && info.size > 0 && track != null) {
                byte[] pcm = new byte[info.size];
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                buffer.get(pcm);
                track.write(pcm, 0, pcm.length);
            }
            decoder.releaseOutputBuffer(outIndex, false);
        }
    }

    private void releasePipeline() {
        try {
            if (track != null) {
                track.stop();
                track.release();
            }
        } catch (Throwable ignored) {
        }
        track = null;
        try {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
            }
        } catch (Throwable ignored) {
        }
        decoder = null;
        currentRate = 0;
        currentChannels = 0;
    }

    public synchronized void stop() {
        playing = false;
        synchronized (lock) {
            releasePipeline();
        }
        Logx.i("audio", "播放器已停止");
    }
}
