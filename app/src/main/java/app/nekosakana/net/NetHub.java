package app.nekosakana.net;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import app.nekosakana.caps.ShellX;
import app.nekosakana.core.Config;
import app.nekosakana.core.ControlLog;
import app.nekosakana.core.Logx;

/**
 * 连接中枢：监听本机端口、主动连接对方、维护链路、心跳与消息去重。
 * 所有上行消息经 {@code send} 广播到主链路。
 */
public final class NetHub implements Link.Listener {
    public interface Peer {
        void onFrame(JSONObject header, byte[] body);

        void onStatus();
    }

    private static final NetHub INSTANCE = new NetHub();

    private final List<Link> links = new CopyOnWriteArrayList<>();
    private final Set<String> seen = new LinkedHashSet<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong();
    private volatile Peer peer;
    private volatile Context context;
    private volatile libtailscale.TCPListener serverListener;
    private volatile ServerSocket lanListener;
    private Thread serverThread;
    private Thread lanServerThread;
    private Thread clientThread;
    private HandlerThread heartbeatThread;
    private Handler heartbeatHandler;
    private volatile long lastRtt = -1;
    private volatile String peerHello = "";
    private volatile String lastConnectError = "";

    private NetHub() {
    }

    public static NetHub get() {
        return INSTANCE;
    }

    public synchronized void start(Context ctx, Peer p) {
        this.context = ctx.getApplicationContext();
        this.peer = p;
        if (running.get()) {
            notifyStatus();
            return;
        }
        final long run = generation.incrementAndGet();
        running.set(true);
        if (Config.tailnetMode()) startServer(run);
        else startLanServer(run);
        startClient(run);
        startHeartbeat(run);
        Logx.i("nethub", "启动 监听端口=" + Config.port() + " 对方=" + Config.peer());
    }

    public synchronized void stop() {
        generation.incrementAndGet();
        running.set(false);
        libtailscale.TCPListener listener = serverListener;
        serverListener = null;
        if (listener != null) listener.close();
        ServerSocket localListener = lanListener;
        lanListener = null;
        if (localListener != null) {
            try { localListener.close(); } catch (Exception ignored) { }
        }
        for (Link link : links) link.close();
        links.clear();
        if (heartbeatThread != null) {
            heartbeatThread.quitSafely();
            heartbeatThread = null;
        }
        interrupt(serverThread);
        interrupt(lanServerThread);
        interrupt(clientThread);
        serverThread = null;
        lanServerThread = null;
        clientThread = null;
        heartbeatHandler = null;
        peerHello = "";
        lastRtt = -1;
        notifyStatus();
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 监听端口是否已成功绑定（用于看门狗判断服务是否真的可用）。 */
    public boolean isListening() {
        return Config.tailnetMode() ? serverListener != null : lanListener != null;
    }

    public boolean connected() {
        for (Link link : links) if (!link.isClosed() && link.isPeerReady()) return true;
        return false;
    }

    public int linkCount() {
        int n = 0;
        for (Link link : links) if (!link.isClosed() && link.isPeerReady()) n++;
        return n;
    }

    public long latency() {
        return lastRtt;
    }

    public String peerHello() {
        return peerHello;
    }

    private void startServer(long run) {
        serverThread = new Thread(() -> {
            while (active(run)) {
                libtailscale.TCPListener listener = null;
                try {
                    listener = TailscaleController.listen(Config.port());
                    if (!active(run)) { listener.close(); break; }
                    serverListener = listener;
                    Logx.i("nethub", "Tailscale netstack 监听 " + Config.port() + " 成功");
                    while (active(run)) {
                        libtailscale.TCPConn accepted = listener.accept();
                        if (!active(run)) { accepted.close(); break; }
                        addIncomingLink(new TailscaleSocket(accepted, "", Config.port()), "Tailnet", run);
                    }
                } catch (Exception e) {
                    if (active(run)) Logx.w("nethub", "Tailscale 监听等待/重试: " + e.getMessage());
                } finally {
                    if (listener != null) listener.close();
                    if (serverListener == listener) serverListener = null;
                }
                sleep(run, 2000);
            }
        }, "nethub-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void startClient(long run) {
        clientThread = new Thread(() -> {
            while (active(run)) {
                String host = Config.peer();
                if (host == null || host.isEmpty()) {
                    lastConnectError = "";
                    sleep(run, 2000);
                    continue;
                }
                if (hasOutgoing()) {
                    sleep(run, 3000);
                    continue;
                }
                try {
                    Socket socket = Config.tailnetMode()
                            ? TailscaleController.dial(host, Config.port())
                            : NetInfo.dialSystem(host, Config.port(), 5000);
                    if (!active(run)) { socket.close(); break; }
                    addOutgoingLink(socket, run);
                    lastConnectError = "";
                    Logx.i("nethub", (Config.tailnetMode() ? "已通过 Tailnet 连接对方 " : "已通过系统网络连接对方 ") + host);
                    notifyStatus();
                } catch (Exception e) {
                    lastConnectError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    Logx.w("nethub", "连接对方失败: " + e.getMessage());
                    notifyStatus();
                }
                sleep(run, 3000);
            }
        }, "nethub-client");
        clientThread.setDaemon(true);
        clientThread.start();
    }

    private void startLanServer(long run) {
        lanServerThread = new Thread(() -> {
            while (active(run)) {
                ServerSocket listener = null;
                try {
                    listener = new ServerSocket();
                    listener.setReuseAddress(true);
                    // 仅绑定回环：不向局域网暴露控制端口（即使局域网模式也只允许本机回环连接）。
                    listener.bind(new InetSocketAddress("127.0.0.1", Config.port()));
                    lanListener = listener;
                    Logx.i("nethub", "局域网监听 127.0.0.1:" + Config.port() + " 成功（仅回环）");
                    while (active(run)) {
                        Socket accepted = listener.accept();
                        if (!active(run)) { accepted.close(); break; }
                        if (links.size() >= 8) { accepted.close(); continue; }
                        addIncomingLink(accepted, "局域网", run);
                    }
                } catch (Exception e) {
                    if (active(run)) Logx.w("nethub", "局域网监听等待/重试: " + e.getMessage());
                } finally {
                    if (listener != null) {
                        try { listener.close(); } catch (Exception ignored) { }
                    }
                    if (lanListener == listener) lanListener = null;
                }
                sleep(run, 2000);
            }
        }, "nethub-lan-server");
        lanServerThread.setDaemon(true);
        lanServerThread.start();
    }

    private synchronized void addIncomingLink(Socket socket, String transport, long run) {
        if (!active(run) || links.size() >= 8) {
            try { socket.close(); } catch (Exception ignored) { }
            return;
        }
        Link link = new Link(socket, false, this);
        links.add(link);
        link.start();
        if (link.isClosed()) { links.remove(link); return; }
        sendHello(link);
        Logx.i("nethub", "接受 " + transport + " 入站连接 " + link.remote());
        notifyStatus();
    }

    private synchronized void addOutgoingLink(Socket socket, long run) {
        if (!active(run)) {
            try { socket.close(); } catch (Exception ignored) { }
            return;
        }
        Link link = new Link(socket, true, this);
        links.add(link);
        link.start();
        if (link.isClosed()) { links.remove(link); return; }
        sendHello(link);
    }

    private boolean hasOutgoing() {
        for (Link link : links) {
            if (link.isOutgoing() && !link.isClosed()) return true;
        }
        return false;
    }

    private void startHeartbeat(long run) {
        heartbeatThread = new HandlerThread("nethub-heartbeat");
        heartbeatThread.start();
        heartbeatHandler = new Handler(heartbeatThread.getLooper());
        final Handler handler = heartbeatHandler;
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!active(run)) return;
                try {
                    if (!links.isEmpty()) {
                        JSONObject ping = new JSONObject();
                        ping.put("id", UUID.randomUUID().toString());
                        ping.put("t", "ping");
                        ping.put("ts", System.currentTimeMillis());
                        for (Link link : links) {
                            if (!link.isClosed() && link.isPeerReady()) link.send(ping, null);
                        }
                        // RTT 由 pong 计算，这里仅做超时标记
                        if (!connected()) lastRtt = -1;
                    }
                } catch (Exception ignored) {
                }
                // 心跳适度稀疏（10s）以降低待机功耗；链路读超时已放宽到 30s，不会被误判断开。
                if (active(run)) handler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
            }
        });
    }

    private static final long HEARTBEAT_INTERVAL_MS = 10000;

    private void sendHello(Link link) {
        try {
            JSONObject hello = new JSONObject();
            hello.put("id", UUID.randomUUID().toString());
            hello.put("t", "hello");
            hello.put("name", Config.deviceName());
            hello.put("node", Config.nodeId());
            hello.put("version", Config.versionName(context));
            hello.put("backend", ShellX.backend());
            hello.put("ts", System.currentTimeMillis());
            link.send(hello, null);
        } catch (Exception ignored) {
        }
    }

    /** 发送消息。会自动补充 id，并广播到可用链路。 */
    public boolean send(JSONObject header, byte[] body) {
        try {
            if (!header.has("id")) header.put("id", UUID.randomUUID().toString());
            if (isStreamFrame(header.optString("t")) && !header.has("packet")) {
                header.put("packet", UUID.randomUUID().toString());
            }
        } catch (Exception ignored) {
        }
        Link primary = primary();
        if (primary == null || !primary.isPeerReady()) return false;
        boolean ok = false;
        if (primary != null) {
            ok = primary.send(header, body);
        }
        if (!ok) {
            for (Link link : links) {
                if (!link.isClosed() && link.isPeerReady() && link != primary) {
                    if (link.send(header, body)) ok = true;
                }
            }
        }
        return ok;
    }

    private Link primary() {
        Link fallback = null;
        for (Link link : links) {
            if (link.isClosed() || !link.isPeerReady()) continue;
            if (link.isOutgoing()) return link;
            if (fallback == null) fallback = link;
        }
        return fallback;
    }

    @Override
    public void onFrame(Link link, JSONObject header, byte[] body) {
        String type = header.optString("t", "");
        String id = header.optString(isStreamFrame(type) ? "packet" : "id", "");
        if (!id.isEmpty()) {
            synchronized (seen) {
                if (seen.contains(id)) return;
                seen.add(id);
                while (seen.size() > 2048) {
                    java.util.Iterator<String> it = seen.iterator();
                    if (it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }
            }
        }
        if ("ping".equals(type)) {
            try {
                JSONObject pong = new JSONObject();
                pong.put("id", UUID.randomUUID().toString());
                pong.put("t", "pong");
                pong.put("ts", header.optLong("ts"));
                link.send(pong, null);
            } catch (Exception ignored) {
            }
            return;
        }
        if ("pong".equals(type)) {
            lastRtt = Math.max(0, System.currentTimeMillis() - header.optLong("ts"));
            return;
        }
        if ("hello".equals(type)) {
            if (header.optString("node", "").isEmpty()) { link.close(); return; }
            link.setPeerReady(true);
            peerHello = header.optString("name", "") + " · " + header.optString("version", "");
            Logx.i("nethub", "对方握手: " + peerHello);
            notifyStatus();
            return;
        }
        if (peer != null) peer.onFrame(header, body);
    }

    @Override
    public void onClosed(Link link) {
        links.remove(link);
        if (links.isEmpty()) peerHello = "";
        Logx.w("nethub", "链路关闭 " + link.remote());
        notifyStatus();
    }

    private void notifyStatus() {
        if (peer != null) peer.onStatus();
    }

    public String statusText() {
        if (!running.get()) return "未启动";
        if (!connected()) {
            if (!links.isEmpty()) return "链路已建立，正在验证对端";
            String host = Config.peer();
            if (host.isEmpty()) return "未设置对方 IP";
            String route = Config.tailnetMode() ? "Tailnet" : "局域网/系统网络";
            if (!lastConnectError.isEmpty()) return route + "失败，自动重试";
            return "正在通过 " + route + " 连接对方";
        }
        long rtt = lastRtt;
        return "已连接 " + peerHello + " · " + (Config.tailnetMode() ? "Tailnet" : "系统网络")
                + " · " + linkCount() + " 条链路 · 延迟 " + (rtt >= 0 ? rtt + " ms" : "测量中");
    }

    /**
     * 流式隧道帧（端口转发 {@code tunnel.*}、FTP 桥 {@code ftpb.*}）：同一条会话的所有数据帧
     * 复用同一个 {@code id}，若按 {@code id} 去重会被误判为重复而丢弃。此类帧由 {@code send}
     * 逐帧补一个唯一的 {@code packet}，去重按 {@code packet} 进行。
     */
    private static boolean isStreamFrame(String type) {
        return type.startsWith("tunnel.") || type.startsWith("ftpb.");
    }

    private boolean active(long run) { return running.get() && generation.get() == run; }

    private static void interrupt(Thread thread) {
        if (thread != null) thread.interrupt();
    }

    private void sleep(long run, long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
