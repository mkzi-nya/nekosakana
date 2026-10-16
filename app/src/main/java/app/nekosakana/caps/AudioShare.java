package app.nekosakana.caps;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;

import java.nio.ByteBuffer;

import app.nekosakana.core.Logx;

/**
 * 音频采集（MediaProjection 回放捕获 + AAC/ADTS 编码）。
 * 需要在取得屏幕录制授权后使用；编码后以 ADTS 帧发送给对端。
 */
public final class AudioShare {
    public interface Sink {
        void onAudio(byte[] adtsFrame);
    }

    private static AudioShare instance;

    private AudioRecord record;
    private MediaCodec codec;
    private Thread thread;
    private volatile boolean running;
    private int sampleRate = 44100;
    private int channelCount = 2;
    private Sink sink;

    private static final int[] RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

    private AudioShare() {
    }

    public static synchronized AudioShare get() {
        if (instance == null) instance = new AudioShare();
        return instance;
    }

    public static boolean isRunning() {
        return instance != null && instance.running;
    }

    public boolean start(MediaProjection projection, Sink audioSink, int rate, int channels, int bitrate) {
        stop();
        if (projection == null) return false;
        this.sampleRate = rate;
        this.channelCount = channels;
        this.sink = audioSink;
        try {
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            int channelMask = channels == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(channelMask)
                    .build();
            int minBuffer = AudioRecord.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = channels == 1 ? Math.max(minBuffer, 16384) : Math.max(minBuffer, 32768);
            record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();

            MediaFormat mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels);
            mediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            record.startRecording();
            running = true;

            thread = new Thread(this::loop, "audio-share");
            thread.setDaemon(true);
            thread.start();
            Logx.i("audio", "音频采集开始 " + rate + "Hz " + channels + "ch");
            return true;
        } catch (SecurityException t) {
            Logx.e("audio", "需要音频录制授权");
            stop();
            return false;
        } catch (Throwable t) {
            Logx.e("audio", "启动失败 " + t);
            stop();
            return false;
        }
    }

    private void loop() {
        byte[] pcm = new byte[8192];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            try {
                int read = record.read(pcm, 0, pcm.length);
                if (read <= 0) continue;
                int index = codec.dequeueInputBuffer(10_000);
                if (index >= 0) {
                    ByteBuffer buffer = codec.getInputBuffer(index);
                    if (buffer != null) {
                        buffer.clear();
                        buffer.put(pcm, 0, read);
                        codec.queueInputBuffer(index, 0, read, System.nanoTime() / 1000, 0);
                    }
                }
                drain(info);
            } catch (Throwable t) {
                if (running) Logx.e("audio", "采集循环异常 " + t);
                break;
            }
        }
    }

    private void drain(MediaCodec.BufferInfo info) {
        int index;
        while ((index = codec.dequeueOutputBuffer(info, 0)) >= 0) {
            ByteBuffer out = codec.getOutputBuffer(index);
            if (out != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                byte[] data = new byte[info.size];
                out.position(info.offset);
                out.limit(info.offset + info.size);
                out.get(data);
                byte[] adts = adts(data, rateIndex(sampleRate), channelCount);
                if (sink != null) sink.onAudio(adts);
            }
            codec.releaseOutputBuffer(index, false);
        }
    }

    private static int rateIndex(int rate) {
        for (int i = 0; i < RATES.length; i++) {
            if (RATES[i] == rate) return i;
        }
        return 4;
    }

    /** 为 AAC 裸帧添加 7 字节 ADTS 头。 */
    private static byte[] adts(byte[] aac, int sampleRateIndex, int channels) {
        int frameLength = aac.length + 7;
        byte[] out = new byte[frameLength];
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xF1; // MPEG-4, no CRC
        out[2] = (byte) (((1) << 6) | (sampleRateIndex << 2) | ((channels >> 2) & 0x01));
        out[3] = (byte) (((channels & 0x03) << 6) | ((frameLength >> 11) & 0x03));
        out[4] = (byte) ((frameLength >> 3) & 0xFF);
        out[5] = (byte) (((frameLength & 0x07) << 5) | 0x1F);
        out[6] = (byte) 0xFC;
        System.arraycopy(aac, 0, out, 7, aac.length);
        return out;
    }

    public void stop() {
        running = false;
        try {
            if (record != null) {
                record.stop();
                record.release();
            }
        } catch (Throwable ignored) {
        }
        record = null;
        try {
            if (codec != null) {
                codec.stop();
                codec.release();
            }
        } catch (Throwable ignored) {
        }
        codec = null;
        if (thread != null) thread.interrupt();
        thread = null;
        sink = null;
    }
}
