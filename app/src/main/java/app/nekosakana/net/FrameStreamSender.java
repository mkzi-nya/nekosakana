package app.nekosakana.net;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 实时流发送器：把「采集」与「发送」解耦。采集线程只入队（不阻塞），独立发送线程做阻塞发送；
 * 网络不畅导致积压时主动丢弃旧帧，保证只保留最新画面、延迟有界，而不是把历史画面一帧帧慢慢补发。
 *
 * <p>丢弃策略分两类：</p>
 * <ul>
 *   <li>帧间编码（H.264/H.265，{@code keyframeResync=true}）：清空积压并进入「等待关键帧」，
 *       丢弃其间的非关键帧并回请求编码器产生关键帧，收到关键帧后从该处恢复——解码端在关键帧
 *       重新同步，避免因缺失参考帧而花屏。</li>
 *   <li>独立帧（每帧自包含，如 WebP，{@code keyframeResync=false}）：只保留最新一帧，
 *       等价于「直接跳到当前帧」。</li>
 * </ul>
 */
public final class FrameStreamSender {
    public interface Transport {
        /** 阻塞式发送一帧，返回是否成功。meta 为该帧的附加信息（可为 null）。 */
        boolean send(byte[] data, boolean key, Object meta);
    }

    public interface KeyRequester {
        void requestKeyFrame();
    }

    private static final class Entry {
        final byte[] data;
        final boolean key;
        final Object meta;
        Entry(byte[] data, boolean key, Object meta) {
            this.data = data;
            this.key = key;
            this.meta = meta;
        }
    }

    private final Transport transport;
    private final boolean keyframeResync;
    private final int maxQueue;
    private final KeyRequester keyRequester;

    private final Deque<Entry> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private boolean waitingKey;
    private volatile boolean running;
    private Thread worker;

    public FrameStreamSender(Transport transport, boolean keyframeResync, int maxQueue, KeyRequester keyRequester) {
        this.transport = transport;
        this.keyframeResync = keyframeResync;
        this.maxQueue = Math.max(1, maxQueue);
        this.keyRequester = keyRequester;
    }

    public void start() {
        synchronized (lock) {
            if (running) return;
            running = true;
            queue.clear();
            waitingKey = false;
            worker = new Thread(this::loop, "frame-sender");
            worker.setDaemon(true);
            worker.start();
        }
    }

    /** 采集线程调用：非阻塞入队。 */
    public void offer(byte[] data, boolean key, Object meta) {
        if (data == null) return;
        boolean askKey = false;
        synchronized (lock) {
            if (!running) return;
            if (keyframeResync && waitingKey) {
                // 已进入等待关键帧：非关键帧直接丢弃。
                if (!key) return;
                waitingKey = false;
            }
            queue.addLast(new Entry(data, key, meta));
            if (queue.size() > maxQueue) {
                if (keyframeResync) {
                    queue.clear();
                    waitingKey = true;
                    askKey = true;
                } else {
                    Entry latest = queue.peekLast();
                    queue.clear();
                    queue.addLast(latest);
                }
            }
            lock.notifyAll();
        }
        if (askKey && keyRequester != null) keyRequester.requestKeyFrame();
    }

    /** 停止发送但保留可再次 {@link #start()}；用于会话结束。 */
    public void stop() {
        Thread t;
        synchronized (lock) {
            running = false;
            queue.clear();
            waitingKey = false;
            lock.notifyAll();
            t = worker;
            worker = null;
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 清空积压但保持发送线程运行（例如停止共享后再次开始）。 */
    public void clear() {
        synchronized (lock) {
            queue.clear();
            waitingKey = false;
        }
    }

    private void loop() {
        while (true) {
            Entry e;
            synchronized (lock) {
                while (running && queue.isEmpty()) {
                    try {
                        lock.wait();
                    } catch (InterruptedException x) {
                        if (!running) return;
                    }
                }
                if (!running) return;
                e = queue.pollFirst();
            }
            boolean ok;
            try {
                ok = transport.send(e.data, e.key, e.meta);
            } catch (Throwable t) {
                ok = false;
            }
            if (!ok) {
                boolean ask;
                synchronized (lock) {
                    ask = !waitingKey;
                    queue.clear();
                    if (keyframeResync) waitingKey = true;
                }
                if (ask && keyRequester != null) keyRequester.requestKeyFrame();
            }
        }
    }
}
