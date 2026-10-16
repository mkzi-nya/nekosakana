package app.nekosakana.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/**
 * 全局配置存储，基于 SharedPreferences。
 * 所有对配置的读写都应经过此类，便于集中维护键名与默认值。
 */
public final class Config {
    private static final String FILE = "nekosakana";
    private static SharedPreferences prefs;

    // 连接
    public static final String K_PEER = "peer_address";
    public static final String K_PORT = "listen_port";
    public static final String K_TAIL_KEY = "tailscale_key";
    public static final String K_AMAP_KEY = "amap_key";
    public static final String K_SELF_ADDR = "self_address";
    public static final String K_DEVICE_NAME = "device_name";
    public static final String K_NODE_ID = "node_id";

    // 服务
    public static final String K_FTP_ENABLED = "ftp_enabled";
    public static final String K_FTP_PORT = "ftp_port";
    public static final String K_FTP_USER = "ftp_user";
    public static final String K_FTP_PASS = "ftp_pass";
    public static final String K_FORWARD_ENABLED = "forward_enabled";
    public static final String K_FORWARD_SPEC = "forward_spec";
    public static final String K_VPN_ENABLED = "vpn_enabled";
    public static final String K_AUTOSTART = "autostart";
    public static final String K_SERVER_RUNNING = "server_running";
    public static final String K_API_TOKEN = "api_token";
    public static final String K_CONN_MODE = "connection_mode";
    public static final String K_SCREEN_QUALITY = "screen_quality";
    public static final String K_SCREEN_FPS = "screen_fps";
    public static final String K_SCREEN_PROJECTION = "screen_use_projection";
    public static final String K_LOCK_CONFIG = "lock_config";

    public static final int DEFAULT_PORT = 45871;
    public static final int DEFAULT_FTP_PORT = 2221;

    private Config() {
    }

    public static void init(Context context) {
        if (prefs == null) {
            prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
        if (getString(K_NODE_ID, "").isEmpty()) {
            putString(K_NODE_ID, UUID.randomUUID().toString());
        }
        if (getString(K_API_TOKEN, "").isEmpty()) {
            putString(K_API_TOKEN, UUID.randomUUID().toString().replace("-", ""));
        }
        if (getString(K_DEVICE_NAME, "").isEmpty()) {
            putString(K_DEVICE_NAME, android.os.Build.MODEL == null ? "device" : android.os.Build.MODEL);
        }
    }

    public static SharedPreferences p() {
        return prefs;
    }

    public static String getString(String key, String def) {
        return prefs == null ? def : prefs.getString(key, def);
    }

    public static void putString(String key, String value) {
        if (prefs != null) prefs.edit().putString(key, value).apply();
    }

    public static int getInt(String key, int def) {
        return prefs == null ? def : prefs.getInt(key, def);
    }

    public static void putInt(String key, int value) {
        if (prefs != null) prefs.edit().putInt(key, value).apply();
    }

    public static float getFloat(String key, float def) {
        return prefs == null ? def : prefs.getFloat(key, def);
    }

    public static void putFloat(String key, float value) {
        if (prefs != null) prefs.edit().putFloat(key, value).apply();
    }

    public static boolean getBool(String key, boolean def) {
        return prefs == null ? def : prefs.getBoolean(key, def);
    }

    public static void putBool(String key, boolean value) {
        if (prefs != null) prefs.edit().putBoolean(key, value).apply();
    }

    public static String peer() {
        return getString(K_PEER, "").trim();
    }

    public static int port() {
        return getInt(K_PORT, DEFAULT_PORT);
    }

    public static String selfAddress() {
        String address = getString(K_SELF_ADDR, "");
        return app.nekosakana.net.NetInfo.isTailscaleAddress(address) ? address : "";
    }

    public static String deviceName() {
        return getString(K_DEVICE_NAME, android.os.Build.MODEL);
    }

    public static String nodeId() {
        return getString(K_NODE_ID, "");
    }

    public static String apiToken() {
        return getString(K_API_TOKEN, "");
    }

    /**
     * 是否改用系统 MediaProjection 投屏。默认 false：
     * 有 Shizuku/ADB 权限时直接 ADB 直采（screenrecord），**无需在被控设备上确认授权**；
     * 开启后才用系统投屏（更流畅，但每次共享都要在被控设备上弹窗确认）。
     */
    public static boolean screenProjection() {
        return getBool(K_SCREEN_PROJECTION, false);
    }

    public static void setScreenProjection(boolean value) {
        putBool(K_SCREEN_PROJECTION, value);
    }

    public static boolean tailnetMode() {
        return "tailnet".equals(getString(K_CONN_MODE, "tailnet"));
    }

    /** 高德地图 Web 服务 Key（在「设置」中填写，源码不内置）。 */
    public static String amapKey() {
        return getString(K_AMAP_KEY, "").trim();
    }

    public static String versionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.1.0";
        }
    }
}
