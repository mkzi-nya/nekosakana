package app.nekosakana.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.nekosakana.App;
import app.nekosakana.core.Config;
import app.nekosakana.net.NetInfo;
import libtailscale.InputStream;

/** Auth-key login and node-state reconnect through Tailscale's local API. */
public final class TailscaleController {
    public interface Callback { void complete(boolean connected, String message); }
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private TailscaleController() { }

    public static void login(Context context, String authKey) {
        IO.execute(() -> {
            try {
                App app = App.get();
                if (app.tailscale() == null) throw new IllegalStateException("Tailscale 后端未初始化");
                Config.putBool("tailscale_authenticated", false);
                JSONObject prefs = new JSONObject();
                prefs.put("LoggedOut", false);
                prefs.put("WantRunning", true);
                prefs.put("ShieldsUp", false);
                JSONObject options = new JSONObject();
                options.put("UpdatePrefs", prefs);
                options.put("AuthKey", authKey);
                call(app, "POST", "start", options.toString());
                call(app, "POST", "login-interactive", "{}");
                if (waitForAddress("已连接 Tailscale")) {
                    Config.putBool("tailscale_authenticated", true);
                } else {
                    Config.putBool("tailscale_authenticated", false);
                }
            } catch (Exception e) {
                Log.e("nekosakana.tailscale", "Auth-key login failed", e);
                Config.putBool("tailscale_authenticated", false);
                MAIN.post(() -> notifyCallback(false, "连接失败：" + e.getMessage()));
            }
        });
    }

    private static volatile Callback callback;

    public static void connect(Context context, Callback result) {
        callback = result;
        if (Config.getBool("tailscale_authenticated", false)) {
            IO.execute(() -> {
                try {
                    JSONObject prefs = new JSONObject();
                    prefs.put("WantRunning", true);
                    prefs.put("LoggedOut", false);
                    prefs.put("ShieldsUp", false);
                    JSONObject options = new JSONObject();
                    options.put("UpdatePrefs", prefs);
                    call(App.get(), "POST", "start", options.toString());
                    if (waitForAddress("Tailscale 已连接")) {
                        Config.putBool("tailscale_authenticated", true);
                    } else {
                        Config.putBool("tailscale_authenticated", false);
                    }
                } catch (Exception e) {
                    MAIN.post(() -> notifyCallback(false, "连接失败：" + e.getMessage()));
                }
            });
        } else {
            notifyCallback(false, "请填写 Tailscale Auth Key");
        }
    }

    public static void beginLogin(Context context, String authKey, Callback result) {
        callback = result;
        login(context, authKey);
    }

    public static TailscaleSocket dial(String host, int port) throws Exception {
        return new TailscaleSocket(host, port);
    }

    public static libtailscale.TCPListener listen(int port) throws Exception {
        String address = Config.selfAddress();
        if (address.isEmpty()) throw new IllegalStateException("Tailscale 地址尚未就绪");
        return App.get().tailscale().listenTCP(address + ":" + port);
    }

    private static void notifyCallback(boolean connected, String message) {
        Callback current = callback;
        if (current != null) current.complete(connected, message);
    }

    private static boolean waitForAddress(String successMessage) {
        long deadline = System.currentTimeMillis() + 30000;
        Config.putString(Config.K_SELF_ADDR, "");
        while (System.currentTimeMillis() < deadline) {
            String address = "";
            try {
                JSONObject status = call(App.get(), "GET", "status", "");
                JSONObject self = status.optJSONObject("Self");
                JSONArray addresses = self == null ? null : self.optJSONArray("TailscaleIPs");
                if (addresses != null) {
                    for (int i = 0; i < addresses.length(); i++) {
                        String candidate = addresses.optString(i, "");
                        if (NetInfo.isTailscaleAddress(candidate)) {
                            address = candidate;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) { }
            if (address != null && !address.isEmpty()) {
                Config.putString(Config.K_SELF_ADDR, address);
                String readyAddress = address;
                MAIN.post(() -> notifyCallback(true, successMessage + " · " + readyAddress));
                return true;
            }
            try { Thread.sleep(500); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        MAIN.post(() -> notifyCallback(false, "未获取到 Tailscale 地址，请检查 Auth Key 和网络连接"));
        return false;
    }

    private static JSONObject call(App app, String method, String endpoint, String body) throws Exception {
        libtailscale.Application backend = app.tailscale();
        ByteArrayInputStream source = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        InputStream input = new InputStream() {
            @Override public byte[] read() {
                byte[] buffer = new byte[8192];
                int count = source.read(buffer, 0, buffer.length);
                if (count < 0) return null;
                return java.util.Arrays.copyOf(buffer, count);
            }
            @Override public void close() { source.reset(); }
        };
        libtailscale.LocalAPIResponse response = backend.callLocalAPI(
                60000, method, "/localapi/v0/" + endpoint, input);
        byte[] data = response.bodyBytes();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(new String(data, StandardCharsets.UTF_8));
        }
        if (data == null || data.length == 0) return new JSONObject();
        try { return new JSONObject(new String(data, StandardCharsets.UTF_8)); }
        catch (Exception ignored) { return new JSONObject(); }
    }
}
