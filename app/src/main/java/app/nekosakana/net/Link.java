package app.nekosakana.net;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import app.nekosakana.core.Logx;

/**
 * 一条 TCP 链路。帧格式：[headerLen:int][header:JSON][bodyLen:int][body]。
 * 同一链路允许多线程发送，写操作已加锁。
 */
public final class Link {
    public interface Listener {
        void onFrame(Link link, JSONObject header, byte[] body);

        void onClosed(Link link);
    }

    private static final int MAX_BODY = 32 * 1024 * 1024;
    private static final int MAX_HEADER = 64 * 1024;

    private final Socket socket;
    private final boolean outgoing;
    private final Listener listener;
    private final Object writeLock = new Object();
    private DataInputStream in;
    private DataOutputStream out;
    private volatile boolean closed;
    private Thread reader;
    private volatile boolean peerReady;

    public Link(Socket socket, boolean outgoing, Listener listener) {
        this.socket = socket;
        this.outgoing = outgoing;
        this.listener = listener;
    }

    public boolean isOutgoing() {
        return outgoing;
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isPeerReady() { return peerReady; }
    public void setPeerReady(boolean ready) { peerReady = ready; }

    public void start() {
        try {
            // 读超时只用于探测长时间无任何数据的死链；心跳间隔为 10s，这里放宽到 30s。
            socket.setSoTimeout(30000);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
        } catch (Exception e) {
            close();
            return;
        }
        reader = new Thread(this::readLoop, "link-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop() {
        try {
            while (!closed) {
                int headerLen = in.readInt();
                if (headerLen <= 0 || headerLen > MAX_HEADER) throw new EOFException("bad header length " + headerLen);
                byte[] headerBytes = new byte[headerLen];
                in.readFully(headerBytes);
                int bodyLen = in.readInt();
                if (bodyLen < 0 || bodyLen > MAX_BODY) throw new EOFException("bad body length " + bodyLen);
                byte[] body = null;
                if (bodyLen > 0) {
                    body = new byte[bodyLen];
                    in.readFully(body);
                }
                JSONObject header = new JSONObject(new String(headerBytes, StandardCharsets.UTF_8));
                if (listener != null) listener.onFrame(this, header, body);
            }
        } catch (Exception e) {
            if (!closed) Logx.w("link", (outgoing ? "出站" : "入站") + "链路断开: " + e.getMessage());
        } finally {
            close();
        }
    }

    public boolean send(JSONObject header, byte[] body) {
        if (closed) return false;
        try {
            byte[] headerBytes = header.toString().getBytes(StandardCharsets.UTF_8);
            synchronized (writeLock) {
                out.writeInt(headerBytes.length);
                out.write(headerBytes);
                out.writeInt(body == null ? 0 : body.length);
                if (body != null && body.length > 0) out.write(body);
                out.flush();
            }
            return true;
        } catch (Exception e) {
            Logx.w("link", "发送失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            close();
            return false;
        }
    }

    public String remote() {
        try {
            return socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        } catch (Exception e) {
            return "?";
        }
    }

    public OutputStream rawOut() {
        return out;
    }

    public void close() {
        if (closed) return;
        closed = true;
        try {
            socket.close();
        } catch (Exception ignored) {
        }
        if (listener != null) listener.onClosed(this);
    }
}
