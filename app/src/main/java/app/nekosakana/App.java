package app.nekosakana;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

import app.nekosakana.core.Config;
import app.nekosakana.core.Logx;
import app.nekosakana.core.Store;

/**
 * 应用入口。负责初始化配置、存储与日志。
 * 服务是否启动由 {@code CoreService} 自行根据配置决定，避免在后台被系统拉起时做多余工作。
 */
public final class App extends Application implements libtailscale.AppContext {
    private static App instance;
    private SharedPreferences tailscalePrefs;
    private libtailscale.Application tailscale;

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Store.init(this);
        Config.init(this);
        Logx.init(this);
        app.nekosakana.caps.ShellX.init();
        try {
            // 原生后端的工作目录同样放在应用外部专属私有目录，不写 /data/data。
            File stateDir = getExternalFilesDir(null);
            if (stateDir == null) stateDir = getFilesDir();
            tailscale = libtailscale.Libtailscale.start(
                    stateDir.getAbsolutePath(), stateDir.getAbsolutePath(), false, this);
        } catch (Throwable t) {
            Log.e("nekosakana.tailscale", "Tailscale backend failed to start", t);
        }
        Logx.i("app", "nekosakana 启动 versionName=" + Config.versionName(this)
                + " sdk=" + android.os.Build.VERSION.SDK_INT);
    }

    public libtailscale.Application tailscale() {
        return tailscale;
    }

    /** 后台/内存紧张时收缩内存缓冲，降低内存占用（磁盘数据不受影响）。 */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                || level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            app.nekosakana.core.Logx.trim(200);
            app.nekosakana.core.ControlLog.trim(400);
            app.nekosakana.caps.ScreenShare.get().dropCachedFrame();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        app.nekosakana.core.Logx.trim(100);
        app.nekosakana.core.ControlLog.trim(200);
        app.nekosakana.caps.ScreenShare.get().dropCachedFrame();
    }

    private synchronized SharedPreferences securePrefs() throws Exception {
        if (tailscalePrefs == null) {
            MasterKey key = new MasterKey.Builder(this)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
            tailscalePrefs = EncryptedSharedPreferences.create(this, "tailscale-secure",
                    key, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        }
        return tailscalePrefs;
    }

    @Override public void log(String tag, String line) { Log.d("Tailscale/" + tag, line); }
    @Override public void encryptToPref(String key, String value) throws Exception {
        securePrefs().edit().putString(key, value).commit();
    }
    @Override public String decryptFromPref(String key) throws Exception {
        return securePrefs().getString(key, "");
    }
    @Override public String getStateStoreKeysJSON() {
        JSONArray keys = new JSONArray();
        try {
            for (String key : securePrefs().getAll().keySet()) {
                if (key.startsWith("statestore-")) keys.put(key.substring("statestore-".length()));
            }
        } catch (Exception e) {
            Log.e("nekosakana.tailscale", "Unable to read state store keys", e);
        }
        return keys.toString();
    }
    @Override public String getOSVersion() { return Build.VERSION.RELEASE; }
    @Override public long getSDKInt() { return Build.VERSION.SDK_INT; }
    @Override public String getDeviceName() {
        String name = android.provider.Settings.Global.getString(getContentResolver(), "device_name");
        return name == null || name.trim().isEmpty() ? Build.MODEL : name;
    }
    @Override public String getInstallSource() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                String installer = getPackageManager().getInstallSourceInfo(getPackageName()).getInstallingPackageName();
                return installer == null ? "unknown" : installer;
            }
            String installer = getPackageManager().getInstallerPackageName(getPackageName());
            return installer == null ? "unknown" : installer;
        } catch (Exception e) { return "unknown"; }
    }
    @Override public boolean shouldUseGoogleDNSFallback() { return true; }
    @Override public boolean isChromeOS() { return getPackageManager().hasSystemFeature("android.hardware.type.pc"); }
    @Override public boolean isClientLoggingEnabled() { return false; }
    @Override public String getInterfacesAsJson() throws Exception {
        JSONArray out = new JSONArray();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) return "[]";
        for (NetworkInterface ni : Collections.list(interfaces)) {
            JSONObject iface = new JSONObject();
            JSONArray addresses = new JSONArray();
            for (InetAddress address : Collections.list(ni.getInetAddresses())) {
                JSONObject entry = new JSONObject();
                entry.put("ip", address.getHostAddress().split("%", 2)[0]);
                entry.put("prefixLen", address.getAddress().length == 4 ? 32 : 128);
                addresses.put(entry);
            }
            iface.put("name", ni.getName());
            iface.put("index", ni.getIndex());
            iface.put("mtu", ni.getMTU());
            iface.put("up", ni.isUp());
            iface.put("broadcast", ni.supportsMulticast());
            iface.put("loopback", ni.isLoopback());
            iface.put("pointToPoint", ni.isPointToPoint());
            iface.put("multicast", ni.supportsMulticast());
            iface.put("addrs", addresses);
            out.put(iface);
        }
        return out.toString();
    }
    @Override public String getPlatformDNSConfig() { return ""; }
    @Override public String getSyspolicyStringValue(String key) throws Exception { throw new Exception("not configured"); }
    @Override public boolean getSyspolicyBooleanValue(String key) throws Exception { throw new Exception("not configured"); }
    @Override public String getSyspolicyStringArrayJSONValue(String key) throws Exception { throw new Exception("not configured"); }
    @Override public boolean hardwareAttestationKeySupported() { return false; }
    @Override public String hardwareAttestationKeyCreate() throws Exception { throw new Exception("unsupported"); }
    @Override public void hardwareAttestationKeyRelease(String id) throws Exception { throw new Exception("unsupported"); }
    @Override public byte[] hardwareAttestationKeyPublic(String id) throws Exception { throw new Exception("unsupported"); }
    @Override public byte[] hardwareAttestationKeySign(String id, byte[] data) throws Exception { throw new Exception("unsupported"); }
    @Override public void hardwareAttestationKeyLoad(String id) throws Exception { throw new Exception("unsupported"); }
    @Override public boolean bindSocketToNetwork(int fd) { return false; }
    @Override public byte[] getUserCACertsPEM() { return new byte[0]; }
}
