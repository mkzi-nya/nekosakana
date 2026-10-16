package app.nekosakana.net;

import android.content.Context;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import app.nekosakana.core.ControlLog;
import app.nekosakana.core.Logx;

/**
 * FTP 感知隧道：把**对方** 127.0.0.1 的 FTP（根 /sdcard）映射到**本机** 127.0.0.1:2221。
 *
 * 本机不对外提供 FTP；2221 只是到对方 /sdcard 的入口。对端内部的 FTP 服务由
 * {@link FtpServer} 在私有回环端口提供，仅本隧道使用。
 *
 * 普通 TCP 转发无法承载 FTP 被动模式——数据端口是服务端临时分配的。这里解析控制通道的
 * 227 (PASV) / 229 (EPSV) 响应，为每个数据端口按需建立子隧道（见 docs/08-转发与VPN.md）。
 *
 * 帧：{@code ftpb.open{id,port}}、{@code ftpb.data{id,dir=req|res}} + body、{@code ftpb.close{id}}。
 */
public final class FtpBridge {
    /** 本机访问对方 /sdcard 的入口端口：ftp://127.0.0.1:2221 */
    public static final int LOCAL_PORT = 2221;

    private static final FtpBridge INSTANCE = new FtpBridge();

    private static final class Session {
        Socket socket;
        final StringBuilder responseBuffer = new StringBuilder();
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final List<ServerSocket> dataListeners = new CopyOnWriteArrayList<>();
    private ServerSocket controlListener;
    private volatile boolean running;

    private FtpBridge() {
    }

    public static FtpBridge get() {
        return INSTANCE;
    }

    public boolean isRunning() {
        return running;
    }

    /** 对方 /sdcard 的访问地址，形如 127.0.0.1:2221。 */
    public String address() {
        return running ? "127.0.0.1:" + LOCAL_PORT : "";
    }

    public synchronized void start(Context context) {
        if (running) return;
        running = true;
        Thread thread = new Thread(this::acceptLoop, "ftpb-bridge");
        thread.setDaemon(true);
        thread.start();
        ControlLog.add("sys", "ftp", "FTP 桥已启动 @ 127.0.0.1:" + LOCAL_PORT);
    }

    private void acceptLoop() {
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", LOCAL_PORT));
            controlListener = server;
            Logx.i("ftpb", "监听 127.0.0.1:" + LOCAL_PORT);
            while (running) {
                Socket client = server.accept();
                openTunnel(client, FtpServer.SERVER_PORT);
            }
        } catch (Exception e) {
            if (running) Logx.w("ftpb", "控制监听结束: " + e.getMessage());
        }
    }

    /** 本机侧：为一个本地连接建立到对方端口的隧道。 */
    private void openTunnel(Socket socket, int remotePort) {
        String id = UUID.randomUUID().toString();
        Session session = new Session();
        session.socket = socket;
        sessions.put(id, session);
        ControlLog.add("out", "ftpb.open", "127.0.0.1:" + remotePort);
        try {
            JSONObject open = new JSONObject();
            open.put("t", "ftpb.open");
            open.put("id", id);
            open.put("port", remotePort);
            if (!NetHub.get().send(open, null)) {
                // 对端链路未就绪：立即关闭本机连接，避免客户端长时间挂起（MT 管理器等会误判为连不上）。
                Logx.w("ftpb", "对方链路未就绪，关闭本机 FTP 连接");
                ControlLog.add("sys", "ftp", "FTP 桥：对方链路未就绪，连接被拒绝");
                close(id);
                return;
            }
            Thread reader = new Thread(() -> pump(socket, id), "ftpb-req-" + id);
            reader.setDaemon(true);
            reader.start();
        } catch (Exception e) {
            Logx.w("ftpb", "打开隧道失败: " + e.getMessage());
            close(id);
        }
    }

    private void pump(Socket socket, String id) {
        try {
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                byte[] data = new byte[n];
                System.arraycopy(buffer, 0, data, 0, n);
                JSONObject header = new JSONObject();
                header.put("t", "ftpb.data");
                header.put("id", id);
                header.put("dir", "req");
                NetHub.get().send(header, data);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                JSONObject close = new JSONObject();
                close.put("t", "ftpb.close");
                close.put("id", id);
                NetHub.get().send(close, null);
            } catch (Exception ignored) {
            }
            close(id);
        }
    }

    /** 处理来自对方的 ftpb 帧。 */
    public void onFrame(String type, JSONObject header, byte[] body) {
        String id = header.optString("id", "");
        switch (type) {
            case "ftpb.open": {
                int port = header.optInt("port");
                try {
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                    Session session = new Session();
                    session.socket = socket;
                    sessions.put(id, session);
                    ControlLog.add("in", "ftpb.open", "127.0.0.1:" + port);
                    Logx.i("ftpb", "隧道已接入本机服务 127.0.0.1:" + port);
                    Thread reader = new Thread(() -> pumpBack(socket, id), "ftpb-res-" + id);
                    reader.setDaemon(true);
                    reader.start();
                } catch (Exception e) {
                    Logx.w("ftpb", "连接本机服务失败 127.0.0.1:" + port);
                    try {
                        JSONObject close = new JSONObject();
                        close.put("t", "ftpb.close");
                        close.put("id", id);
                        NetHub.get().send(close, null);
                    } catch (Exception ignored) {
                    }
                }
                break;
            }
            case "ftpb.data": {
                Session session = sessions.get(id);
                if (session == null || session.socket == null || body == null) return;
                boolean res = "res".equals(header.optString("dir"));
                // 关键：先启动数据端口监听，再把 227 响应交给客户端，避免连接竞态。
                if (res) inspectResponse(session, body);
                try {
                    OutputStream out = session.socket.getOutputStream();
                    out.write(body);
                    out.flush();
                } catch (Exception e) {
                    close(id);
                }
                break;
            }
            case "ftpb.close":
                close(id);
                break;
            default:
        }
    }

    /** 对方侧读到服务端响应，转发回本机。 */
    private void pumpBack(Socket socket, String id) {
        try {
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                byte[] data = new byte[n];
                System.arraycopy(buffer, 0, data, 0, n);
                JSONObject header = new JSONObject();
                header.put("t", "ftpb.data");
                header.put("id", id);
                header.put("dir", "res");
                NetHub.get().send(header, data);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                JSONObject close = new JSONObject();
                close.put("t", "ftpb.close");
                close.put("id", id);
                NetHub.get().send(close, null);
            } catch (Exception ignored) {
            }
            close(id);
        }
    }

    /** 解析服务端响应流中的 PASV/EPSV 端口并建立数据隧道。 */
    private void inspectResponse(Session session, byte[] chunk) {
        session.responseBuffer.append(new String(chunk, StandardCharsets.ISO_8859_1));
        String text = session.responseBuffer.toString();
        int[] consumed = new int[1];
        int port = parseDataPort(text, consumed);
        if (port > 0) {
            startDataListener(port);
            if (consumed[0] > 0) session.responseBuffer.delete(0, consumed[0]);
        } else if (text.length() > 8192) {
            session.responseBuffer.setLength(0);
        }
    }

    /** 返回 PASV/EPSV 声明的数据端口；consumed[0] 为已处理到的下标。 */
    private static int parseDataPort(String text, int[] consumed) {
        int pasv = text.lastIndexOf("227 ");
        int epsv = text.lastIndexOf("229 ");
        if (pasv < 0 && epsv < 0) return -1;
        if (epsv > pasv) {
            int open = text.indexOf('(', epsv);
            int close = text.indexOf(')', open);
            if (open < 0 || close < 0) return -1;
            // 形如 (|||port|)
            String[] seg = text.substring(open + 1, close).split("\\|", -1);
            if (seg.length < 4) return -1;
            try {
                int port = Integer.parseInt(seg[3].trim());
                consumed[0] = close + 1;
                return port > 0 && port < 65536 ? port : -1;
            } catch (Exception e) {
                return -1;
            }
        }
        int open = text.indexOf('(', pasv);
        int close = text.indexOf(')', open);
        if (open < 0 || close < 0) return -1;
        String[] parts = text.substring(open + 1, close).split(",");
        if (parts.length != 6) return -1;
        try {
            int hi = Integer.parseInt(parts[4].trim());
            int lo = Integer.parseInt(parts[5].trim());
            consumed[0] = close + 1;
            return (hi << 8) | lo;
        } catch (Exception e) {
            return -1;
        }
    }

    private void startDataListener(int port) {
        for (ServerSocket existing : dataListeners) {
            if (existing.getLocalPort() == port) return;
        }
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", port));
            dataListeners.add(server);
            Thread thread = new Thread(() -> dataAcceptLoop(server, port), "ftpb-data-" + port);
            thread.setDaemon(true);
            thread.start();
            Logx.i("ftpb", "数据端口 127.0.0.1:" + port + " 已就绪");
        } catch (Exception e) {
            Logx.w("ftpb", "数据端口 " + port + " 监听失败: " + e.getMessage());
        }
    }

    private void dataAcceptLoop(ServerSocket server, int port) {
        try {
            Socket client = server.accept();
            openTunnel(client, port);
        } catch (Exception ignored) {
        } finally {
            // 每个 PASV 数据端口只服务一次连接，接受后立即关闭并回收，避免监听套接字泄漏。
            try {
                server.close();
            } catch (Exception ignored) {
            }
            dataListeners.remove(server);
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
        try {
            if (controlListener != null) controlListener.close();
        } catch (Exception ignored) {
        }
        controlListener = null;
        for (ServerSocket server : dataListeners) {
            try {
                server.close();
            } catch (Exception ignored) {
            }
        }
        dataListeners.clear();
        for (String id : new ArrayList<>(sessions.keySet())) close(id);
    }
}
