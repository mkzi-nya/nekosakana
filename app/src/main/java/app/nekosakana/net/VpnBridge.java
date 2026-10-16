package app.nekosakana.net;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;

import app.nekosakana.core.ControlLog;
import app.nekosakana.core.Logx;

/**
 * 可选 VPN（实验性）。仅接管一个独立子网 10.66.0.0/24，用以为“局域网式访问”预留隧道，
 * 不会影响其他应用的正常上网。见 docs/08-转发与VPN.md。
 */
public class VpnBridge extends VpnService {
    public static final String ACTION_START = "app.nekosakana.vpn.START";
    public static final String ACTION_STOP = "app.nekosakana.vpn.STOP";

    private ParcelFileDescriptor descriptor;
    private Thread reader;
    private volatile boolean running;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopTunnel();
        } else {
            startTunnel();
        }
        return START_STICKY;
    }

    private synchronized void startTunnel() {
        if (running) return;
        try {
            Builder builder = new Builder();
            builder.setSession("nekosakana");
            builder.addAddress("10.66.0.2", 24);
            builder.addRoute("10.66.0.0", 24);
            builder.addDnsServer("10.66.0.1");
            builder.setBlocking(true);
            descriptor = builder.establish();
            if (descriptor == null) {
                Logx.w("vpn", "VPN 建立失败（可能未授权）");
                return;
            }
            running = true;
            ControlLog.add("sys", "vpn", "VPN 已开启");
            reader = new Thread(this::loop, "vpn-reader");
            reader.setDaemon(true);
            reader.start();
        } catch (Throwable t) {
            Logx.e("vpn", "启动失败 " + t);
        }
    }

    private void loop() {
        byte[] buffer = new byte[32767];
        try (FileInputStream in = new FileInputStream(descriptor.getFileDescriptor())) {
            while (running) {
                int n = in.read(buffer);
                if (n < 0) break;
                // 预留隧道处理；当前仅接收以保证接口活跃。
            }
        } catch (IOException ignored) {
        }
    }

    private synchronized void stopTunnel() {
        running = false;
        try {
            if (descriptor != null) descriptor.close();
        } catch (Exception ignored) {
        }
        descriptor = null;
        ControlLog.add("sys", "vpn", "VPN 已关闭");
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }
}
