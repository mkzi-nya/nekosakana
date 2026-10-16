package app.nekosakana.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 网络探测与 Tailscale 地址识别。 */
public final class NetInfo {
    private NetInfo() {
    }

    public static final class Iface {
        public final String name;
        public final String address;
        public final boolean tailscale;

        Iface(String name, String address, boolean tailscale) {
            this.name = name;
            this.address = address;
            this.tailscale = tailscale;
        }
    }

    public static boolean isTailscale(byte[] ip) {
        if (ip.length == 4) {
            int a = ip[0] & 0xff;
            int b = ip[1] & 0xff;
            return a == 100 && b >= 64 && b <= 127;
        }
        return false;
    }

    public static boolean isTailscaleV6(String host) {
        return host != null && host.toLowerCase().startsWith("fd7a:115c:a1e0:");
    }

    public static boolean isTailscaleAddress(String host) {
        if (host == null || host.isEmpty()) return false;
        if (host.indexOf(':') < 0 && !host.matches("[0-9.]+")) return false;
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address instanceof Inet4Address) return isTailscale(address.getAddress());
            if (!(address instanceof Inet6Address)) return false;
            byte[] bytes = address.getAddress();
            return (bytes[0] & 0xff) == 0xfd
                    && (bytes[1] & 0xff) == 0x7a
                    && (bytes[2] & 0xff) == 0x11
                    && (bytes[3] & 0xff) == 0x5c
                    && (bytes[4] & 0xff) == 0xa1
                    && (bytes[5] & 0xff) == 0xe0;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Route Tailnet IPs through the userspace stack and LAN IPs through Android's network. */
    public static Socket dial(String host, int port, int timeoutMs) throws Exception {
        if (isTailscaleAddress(host)) return TailscaleController.dial(host, port);
        return dialSystem(host, port, timeoutMs);
    }

    public static Socket dialSystem(String host, int port, int timeoutMs) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return socket;
        } catch (Exception e) {
            try { socket.close(); } catch (Exception ignored) { }
            throw e;
        }
    }

    /** 返回 Tailscale 地址（100.64/10 或 fd7a:115c:a1e0::/48），没有则返回空串。 */
    public static String tailscaleAddress() {
        for (Iface iface : interfaces()) {
            if (iface.tailscale) return iface.address;
        }
        return "";
    }

    /** 非 Tailscale 的主内网地址。 */
    public static String localAddress() {
        for (Iface iface : interfaces()) {
            if (!iface.tailscale && !iface.address.startsWith("127.") && iface.address.contains(".")) {
                return iface.address;
            }
        }
        return "";
    }

    public static List<Iface> interfaces() {
        List<Iface> list = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    String host = addr.getHostAddress();
                    if (host == null) continue;
                    int percent = host.indexOf('%');
                    if (percent > 0) host = host.substring(0, percent);
                    boolean ts = addr instanceof Inet4Address ? isTailscale(addr.getAddress()) : isTailscaleV6(host);
                    list.add(new Iface(ni.getName(), host, ts));
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    /** TCP 连接延迟（毫秒），失败返回 -1。 */
    public static long ping(String host, int port, int timeoutMs) {
        if (host == null || host.isEmpty()) return -1;
        long start = System.nanoTime();
        try (Socket socket = dial(host, port, timeoutMs)) {
            return (System.nanoTime() - start) / 1_000_000;
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean reachable(String host, int port, int timeoutMs) {
        return ping(host, port, timeoutMs) >= 0;
    }
}
