package app.nekosakana.net;

import android.content.Context;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import app.nekosakana.core.Config;
import app.nekosakana.core.ControlLog;
import app.nekosakana.core.Logx;

/**
 * 本地端口转发：把本机端口映射到对方手机的本地服务，实现“像局域网一样”访问。
 * 数据通过已有 TCP 链路以 tunnel.* 帧隧道传输（见 docs/08-转发与VPN.md）。
 *
 * 配置格式（Config.K_FORWARD_SPEC）：本地端口,对方主机,对方端口;...
 */
public final class PortForward {
    private static final PortForward INSTANCE = new PortForward();

    private static final class Session {
        Socket socket;
        int localPort;
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final List<ServerSocket> servers = new ArrayList<>();
    private volatile boolean running;

    private PortForward() {
    }

    public static PortForward get() {
        return INSTANCE;
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void start(Context context) {
        if (running) return;
        running = true;
        String spec = Config.getString(Config.K_FORWARD_SPEC, "");
        if (spec.isEmpty()) {
            Logx.w("forward", "未配置转发规则");
            return;
        }
        for (String rule : spec.split(";")) {
            String[] parts = rule.trim().split(",");
            if (parts.length != 3) continue;
            try {
                int localPort = Integer.parseInt(parts[0].trim());
                String host = parts[1].trim();
                int port = Integer.parseInt(parts[2].trim());
                startListener(localPort, host, port);
            } catch (Exception e) {
                Logx.w("forward", "规则无效: " + rule);
            }
        }
    }

    /** 按最新配置重建所有转发（含内置 FTP 桥）。 */
    public synchronized void apply(Context context) {
        stop();
        start(context);
    }

    private void startListener(int localPort, String host, int port) {
        Thread thread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress("127.0.0.1", localPort));
                servers.add(server);
                Logx.i("forward", "本地 " + localPort + " -> 对方 " + host + ":" + port);
                while (running) {
                    Socket client = server.accept();
                    handleLocal(client, host, port);
                }
            } catch (Exception e) {
                if (running) Logx.w("forward", "监听 " + localPort + " 失败: " + e.getMessage());
            }
        }, "forward-" + localPort);
        thread.setDaemon(true);
        thread.start();
    }

    private void handleLocal(Socket client, String host, int port) {
        String id = UUID.randomUUID().toString();
        Session session = new Session();
        session.socket = client;
        session.localPort = client.getPort();
        sessions.put(id, session);
        ControlLog.add("out", "forward.open", host + ":" + port);
        try {
            JSONObject open = new JSONObject();
            open.put("t", "tunnel.open");
            open.put("id", id);
            open.put("host", host);
            open.put("port", port);
            NetHub.get().send(open, null);

            Thread reader = new Thread(() -> pump(client, id, "req"), "forward-req-" + id);
            reader.setDaemon(true);
            reader.start();
        } catch (Exception e) {
            close(id);
        }
    }

    private void pump(Socket socket, String id, String dir) {
        try {
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                byte[] data = new byte[n];
                System.arraycopy(buffer, 0, data, 0, n);
                JSONObject header = new JSONObject();
                header.put("t", "tunnel.data");
                header.put("id", id);
                header.put("dir", dir);
                NetHub.get().send(header, data);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                JSONObject close = new JSONObject();
                close.put("t", "tunnel.close");
                close.put("id", id);
                NetHub.get().send(close, null);
            } catch (Exception ignored) {
            }
            close(id);
        }
    }

    /** 处理来自对方的 tunnel 帧。 */
    public void onFrame(String type, JSONObject header, byte[] body) {
        String id = header.optString("id", "");
        switch (type) {
            case "tunnel.open": {
                String host = header.optString("host", "127.0.0.1");
                int port = header.optInt("port");
                try {
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress(host, port), 5000);
                    Session session = new Session();
                    session.socket = socket;
                    sessions.put(id, session);
                    ControlLog.add("in", "forward.open", host + ":" + port);
                    Thread reader = new Thread(() -> pump(socket, id, "res"), "forward-res-" + id);
                    reader.setDaemon(true);
                    reader.start();
                } catch (Exception e) {
                    Logx.w("forward", "对方本地服务连接失败 " + host + ":" + port);
                    try {
                        JSONObject close = new JSONObject();
                        close.put("t", "tunnel.close");
                        close.put("id", id);
                        NetHub.get().send(close, null);
                    } catch (Exception ignored) {
                    }
                }
                break;
            }
            case "tunnel.data": {
                Session session = sessions.get(id);
                if (session == null || session.socket == null || body == null) return;
                try {
                    OutputStream out = session.socket.getOutputStream();
                    out.write(body);
                    out.flush();
                } catch (Exception e) {
                    close(id);
                }
                break;
            }
            case "tunnel.close":
                close(id);
                break;
            default:
        }
    }

    private void close(String id) {
        Session session = sessions.remove(id);
        if (session != null && session.socket != null) {
            try {
                session.socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized void stop() {
        running = false;
        for (ServerSocket server : servers) {
            try {
                server.close();
            } catch (Exception ignored) {
            }
        }
        servers.clear();
        for (String id : new ArrayList<>(sessions.keySet())) close(id);
    }
}
