package app.nekosakana.net;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

import app.nekosakana.App;
import libtailscale.TCPConn;

/** Socket-compatible adapter for TCP carried by Tailscale's userspace netstack. */
final class TailscaleSocket extends Socket {
    private TCPConn connection;
    private String host;
    private int port;
    private volatile boolean closed;
    private InputStream input;
    private OutputStream output;

    TailscaleSocket(String host, int port) throws IOException {
        this.host = host;
        this.port = port;
        try {
            connection = App.get().tailscale().dialTCP(host + ":" + port);
        } catch (Exception e) {
            throw new IOException("Tailscale dial failed: " + e.getMessage(), e);
        }
    }

    TailscaleSocket(TCPConn connection, String host, int port) {
        this.connection = connection;
        this.host = host;
        this.port = port;
    }

    @Override public void connect(SocketAddress endpoint) throws IOException {
        if (!(endpoint instanceof InetSocketAddress)) throw new IOException("Unsupported address");
    }
    @Override public void connect(SocketAddress endpoint, int timeout) throws IOException { connect(endpoint); }
    @Override public void setTcpNoDelay(boolean enabled) { }
    @Override public void setKeepAlive(boolean enabled) { }
    @Override public void setSoTimeout(int timeout) {
        if (timeout < 0) throw new IllegalArgumentException("Negative timeout");
        connection.setReadTimeout(timeout);
    }
    @Override public boolean getTcpNoDelay() { return true; }
    @Override public boolean getKeepAlive() { return true; }
    @Override public boolean isConnected() { return connection != null; }
    @Override public boolean isClosed() { return closed; }
    @Override public InetAddress getInetAddress() {
        try { return InetAddress.getByName(host); }
        catch (Exception e) { return null; }
    }
    @Override public int getPort() { return port; }
    @Override public int getLocalPort() { return 0; }
    @Override public InetAddress getLocalAddress() {
        try { return InetAddress.getByName(app.nekosakana.core.Config.selfAddress()); }
        catch (Exception e) { return InetAddress.getLoopbackAddress(); }
    }

    @Override
    public synchronized InputStream getInputStream() throws IOException {
        if (connection == null || closed) throw new IOException("Socket is closed");
        if (input == null) {
            input = new InputStream() {
                @Override public int read() throws IOException {
                    byte[] one = new byte[1];
                    int count = read(one, 0, 1);
                    return count < 0 ? -1 : one[0] & 0xff;
                }
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (closed) throw new IOException("Socket is closed");
                    if (length == 0) return 0;
                    byte[] chunk;
                    try {
                        chunk = connection.readBytes(Math.min(length, 64 * 1024));
                    } catch (Exception e) {
                        throw new IOException("Tailscale read failed", e);
                    }
                    if (chunk == null || chunk.length == 0) return -1;
                    System.arraycopy(chunk, 0, buffer, offset, chunk.length);
                    return chunk.length;
                }
            };
        }
        return input;
    }

    @Override
    public synchronized OutputStream getOutputStream() throws IOException {
        if (connection == null || closed) throw new IOException("Socket is closed");
        if (output == null) {
            output = new OutputStream() {
                @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}); }
                @Override public void write(byte[] data, int offset, int length) throws IOException {
                    if (closed) throw new IOException("Socket is closed");
                    byte[] chunk = new byte[length];
                    System.arraycopy(data, offset, chunk, 0, length);
                    if (connection.write(chunk) != length) throw new IOException("Tailscale write failed");
                }
            };
        }
        return output;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (connection != null) connection.close();
    }
}
