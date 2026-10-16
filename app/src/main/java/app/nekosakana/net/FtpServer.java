package app.nekosakana.net;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import app.nekosakana.core.ControlLog;
import app.nekosakana.core.Logx;

/**
 * 轻量 FTP 服务，根目录为本机 /sdcard，仅监听 127.0.0.1 私有端口（见 docs/08-转发与VPN.md）。
 * 仅供 {@link FtpBridge} 在对端设备经隧道访问本机存储，不对外暴露。
 */
public final class FtpServer {
    private static final FtpServer INSTANCE = new FtpServer();

    /** 本机 FTP 服务器端口（根 /sdcard）。仅回环，供 FtpBridge 在对方设备访问。 */
    public static final int SERVER_PORT = 2222;

    private ServerSocket serverSocket;
    private volatile boolean running;
    private volatile String bindAddress = "";
    private volatile long generation;

    private FtpServer() {
    }

    public static FtpServer get() {
        return INSTANCE;
    }

    public boolean isRunning() {
        return running;
    }

    /** 本机 /sdcard 的 FTP 地址，形如 127.0.0.1:2222。 */
    public String address() {
        if (!running || bindAddress.isEmpty()) return "";
        return bindAddress + ":" + SERVER_PORT;
    }

    public synchronized void start(Context context) {
        if (running) return;
        running = true;
        final long run = ++generation;
        Thread thread = new Thread(() -> {
            while (running && generation == run) {
              ServerSocket listener = null;
              try {
                int port = SERVER_PORT;
                listener = new ServerSocket();
                listener.setReuseAddress(true);
                listener.bind(new java.net.InetSocketAddress("127.0.0.1", port));
                final String host = "127.0.0.1";
                synchronized (FtpServer.this) {
                    if (!running || generation != run) { listener.close(); return; }
                    serverSocket = listener;
                    bindAddress = host;
                }
                Logx.i("ftp", "FTP 监听 " + host + ":" + port + "（根 /sdcard）");
                while (running && generation == run) {
                    Socket socket = listener.accept();
                    Thread worker = new Thread(() -> handle(socket), "ftp-client");
                    worker.setDaemon(true);
                    worker.start();
                }
              } catch (Exception e) {
                if (running && generation == run) Logx.w("ftp", "FTP 等待/重试: " + e.getMessage());
              } finally {
                if (listener != null) try { listener.close(); } catch (Exception ignored) { }
                if (generation == run) bindAddress = "";
              }
              try { Thread.sleep(2000); }
              catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }, "ftp-server");
        thread.setDaemon(true);
        thread.start();
        ControlLog.add("sys", "ftp", "FTP 服务已启动 @ " + bindAddress);
    }

    public synchronized void stop() {
        running = false;
        generation++;
        bindAddress = "";
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
        serverSocket = null;
    }

    private void handle(Socket socket) {
        try (Socket control = socket) {
            control.setSoTimeout(120_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(control.getOutputStream(), StandardCharsets.UTF_8), true);

            File root = sdcard();
            File cwd = root;
            ServerSocket pasv = null;

            send(out, "220 nekosakana FTP ready");
            String line;
            while ((line = in.readLine()) != null) {
                String[] parts = line.split(" ", 2);
                String cmd = parts[0].toUpperCase(Locale.US);
                String arg = parts.length > 1 ? parts[1] : "";
                // 不记录 PASS 参数，避免日志泄露口令。
                Logx.i("ftp", "> " + ("PASS".equals(cmd) ? "PASS ***" : line));
                switch (cmd) {
                    case "USER":
                        send(out, "331 need password");
                        break;
                    case "PASS":
                        send(out, "230 logged in");
                        break;
                    case "SYST":
                        send(out, "215 UNIX Type: L8");
                        break;
                    case "FEAT":
                        out.print("211-Features:\r\n");
                        out.print(" MLST type*;size*;modify*;perm*;\r\n");
                        out.print(" MLSD\r\n");
                        out.print(" PASV\r\n");
                        out.print(" EPSV\r\n");
                        out.print(" UTF8\r\n");
                        send(out, "211 End");
                        break;
                    case "OPTS":
                        send(out, "200 OK");
                        break;
                    case "PWD":
                        send(out, "257 \"" + cwd.getAbsolutePath() + "\"");
                        break;
                    case "CWD": {
                        File target = resolve(root, cwd, arg);
                        if (target != null && target.isDirectory()) {
                            cwd = target;
                            send(out, "250 OK");
                        } else {
                            send(out, "550 no such directory");
                        }
                        break;
                    }
                    case "CDUP":
                        if (cwd.getParentFile() != null && cwd.getAbsolutePath().startsWith(root.getAbsolutePath())) {
                            cwd = cwd.getParentFile();
                        }
                        send(out, "250 OK");
                        break;
                    case "TYPE":
                        send(out, "200 OK");
                        break;
                    case "PASV": {
                        if (pasv != null) {
                            try {
                                pasv.close();
                            } catch (Exception ignored) {
                            }
                        }
                        pasv = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
                        int p = pasv.getLocalPort();
                        send(out, "227 Entering Passive Mode (127,0,0,1," + (p >> 8) + "," + (p & 0xff) + ")");
                        break;
                    }
                    case "EPSV": {
                        if (pasv != null) {
                            try {
                                pasv.close();
                            } catch (Exception ignored) {
                            }
                        }
                        pasv = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
                        send(out, "229 Entering Extended Passive Mode (|||" + pasv.getLocalPort() + "|)");
                        break;
                    }
                    case "MLST": {
                        File target = arg.isEmpty() ? cwd : resolve(root, cwd, arg);
                        if (target == null || !target.exists()) {
                            send(out, "550 not found");
                            break;
                        }
                        out.print("250-Listing " + target.getAbsolutePath() + "\r\n");
                        out.print(" " + facts(target) + " " + target.getAbsolutePath() + "\r\n");
                        send(out, "250 End");
                        break;
                    }
                    case "MLSD": {
                        if (pasv == null) {
                            send(out, "425 use PASV first");
                            break;
                        }
                        send(out, "150 here comes the listing");
                        File target = arg.isEmpty() ? cwd : resolve(root, cwd, arg);
                        StringBuilder listing = new StringBuilder();
                        File[] files = target != null && target.isDirectory() ? target.listFiles() : null;
                        if (files != null) {
                            for (File f : files) {
                                listing.append(facts(f)).append(' ').append(f.getName()).append("\r\n");
                            }
                        }
                        try (Socket data = pasv.accept(); OutputStream os = data.getOutputStream()) {
                            os.write(listing.toString().getBytes(StandardCharsets.UTF_8));
                        }
                        pasv.close();
                        pasv = null;
                        send(out, "226 done");
                        break;
                    }
                    case "MDTM": {
                        File target = resolve(root, cwd, arg);
                        if (target != null && target.exists()) {
                            send(out, "213 " + new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(new Date(target.lastModified())));
                        } else {
                            send(out, "550 not found");
                        }
                        break;
                    }
                    case "LIST":
                    case "NLST": {
                        if (pasv == null) {
                            send(out, "425 use PASV first");
                            break;
                        }
                        send(out, "150 here comes the listing");
                        File target = arg.isEmpty() ? cwd : resolve(root, cwd, arg);
                        StringBuilder listing = new StringBuilder();
                        File[] files = target != null && target.isDirectory() ? target.listFiles() : null;
                        SimpleDateFormat fmt = new SimpleDateFormat("MMM dd HH:mm", Locale.US);
                        if (files != null) {
                            for (File f : files) {
                                if ("NLST".equals(cmd)) {
                                    listing.append(f.getName()).append("\r\n");
                                } else {
                                    listing.append(f.isDirectory() ? "drwxr-xr-x" : "-rw-r--r--").append(" 1 ftp ftp ")
                                            .append(f.length()).append(' ').append(fmt.format(new Date(f.lastModified())))
                                            .append(' ').append(f.getName()).append("\r\n");
                                }
                            }
                        }
                        try (Socket data = pasv.accept(); OutputStream os = data.getOutputStream()) {
                            os.write(listing.toString().getBytes(StandardCharsets.UTF_8));
                        }
                        pasv.close();
                        pasv = null;
                        send(out, "226 done");
                        break;
                    }
                    case "RETR": {
                        File target = resolve(root, cwd, arg);
                        if (target == null || !target.isFile() || pasv == null) {
                            send(out, "550 not found");
                            break;
                        }
                        send(out, "150 sending");
                        try (Socket data = pasv.accept(); OutputStream os = data.getOutputStream();
                             InputStream is = new FileInputStream(target)) {
                            byte[] buf = new byte[32 * 1024];
                            int n;
                            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                        }
                        pasv.close();
                        pasv = null;
                        send(out, "226 done");
                        break;
                    }
                    case "STOR": {
                        File target = resolve(root, cwd, arg);
                        if (target == null || pasv == null) {
                            send(out, "550 invalid path");
                            break;
                        }
                        send(out, "150 receiving");
                        try (Socket data = pasv.accept(); InputStream is = data.getInputStream();
                             OutputStream os = new FileOutputStream(target)) {
                            byte[] buf = new byte[32 * 1024];
                            int n;
                            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                        }
                        pasv.close();
                        pasv = null;
                        send(out, "226 done");
                        break;
                    }
                    case "SIZE": {
                        File target = resolve(root, cwd, arg);
                        if (target != null && target.isFile()) {
                            send(out, "213 " + target.length());
                        } else {
                            send(out, "550 not found");
                        }
                        break;
                    }
                    case "DELE": {
                        File target = resolve(root, cwd, arg);
                        if (target != null && target.isFile() && target.delete()) {
                            send(out, "250 deleted");
                        } else {
                            send(out, "550 failed");
                        }
                        break;
                    }
                    case "MKD": {
                        File target = resolve(root, cwd, arg);
                        if (target != null && target.mkdirs()) {
                            send(out, "257 created");
                        } else {
                            send(out, "550 failed");
                        }
                        break;
                    }
                    case "QUIT":
                        send(out, "221 bye");
                        if (pasv != null) pasv.close();
                        return;
                    default:
                        send(out, "502 not implemented");
                }
            }
        } catch (Exception e) {
            Logx.w("ftp", "会话结束: " + e.getMessage());
        }
    }

    /** FTP 根目录：本机 /sdcard。 */
    private File sdcard() {
        return Environment.getExternalStorageDirectory();
    }

    private File resolve(File root, File cwd, String path) {
        if (path == null || path.isEmpty()) return cwd;
        File target = new File(path.startsWith("/") ? path : new File(cwd, path).getAbsolutePath());
        try {
            String canonicalRoot = root.getCanonicalPath();
            String canonicalTarget = target.getCanonicalPath();
            if (!canonicalTarget.equals(canonicalRoot) && !canonicalTarget.startsWith(canonicalRoot + File.separator)) {
                return null;
            }
            return target;
        } catch (IOException e) {
            return null;
        }
    }

    /** RFC 3659 MLSD/MLST 事实串。 */
    private String facts(File f) {
        String type = f.isDirectory() ? "dir" : "file";
        String modify = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(new Date(f.lastModified()));
        String perm = f.isDirectory() ? "el" : "r";
        return "type=" + type + ";size=" + f.length() + ";modify=" + modify + ";perm=" + perm + ";";
    }

    private void send(PrintWriter out, String message) {
        // FTP 控制通道必须使用 CRLF（RFC 959）。PrintWriter.println 在 Android 上只发 LF，
        // Python ftplib 能容忍，但 MT 管理器等严格客户端会一直等 → 表现为「连不上」。
        out.print(message);
        out.print("\r\n");
        out.flush();
    }
}
