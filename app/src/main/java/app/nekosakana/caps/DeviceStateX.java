package app.nekosakana.caps;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Set;

import app.nekosakana.core.ControlLog;
import app.nekosakana.core.Logx;

/** 设备状态读取与控制：亮度、音量、各种开关、电池、连接等。 */
public final class DeviceStateX {
    private DeviceStateX() {
    }

    public static JSONObject snapshot(Context c) {
        JSONObject o = new JSONObject();
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            o.put("brightness", Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1));
            o.put("brightnessMode", Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1 ? "自动" : "手动");
            o.put("screenTimeout", Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, -1));
            o.put("autoRotate", Settings.System.getInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1);

            JSONObject vol = new JSONObject();
            vol.put("music", am.getStreamVolume(AudioManager.STREAM_MUSIC));
            vol.put("musicMax", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            vol.put("ring", am.getStreamVolume(AudioManager.STREAM_RING));
            vol.put("ringMax", am.getStreamMaxVolume(AudioManager.STREAM_RING));
            vol.put("alarm", am.getStreamVolume(AudioManager.STREAM_ALARM));
            vol.put("alarmMax", am.getStreamMaxVolume(AudioManager.STREAM_ALARM));
            vol.put("notification", am.getStreamVolume(AudioManager.STREAM_NOTIFICATION));
            vol.put("call", am.getStreamVolume(AudioManager.STREAM_VOICE_CALL));
            vol.put("callMax", am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL));
            vol.put("system", am.getStreamVolume(AudioManager.STREAM_SYSTEM));
            o.put("volume", vol);
            o.put("ringerMode", ringerModeName(am.getRingerMode()));

            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            o.put("screenOn", pm.isInteractive());
            o.put("batterySaver", pm.isPowerSaveMode());

            Intent battery = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                o.put("batteryLevel", level * 100 / Math.max(1, scale));
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
                o.put("chargePlug", plugName(battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)));
                o.put("batteryTemp", battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
                o.put("batteryVoltage", battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0));
                o.put("batteryHealth", battery.getIntExtra(BatteryManager.EXTRA_HEALTH, 0));
            }
            BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                o.put("currentNow", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)); // 微安
                o.put("capacity", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
            }

            o.put("airplane", Settings.Global.getInt(c.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
            o.put("adb", Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1);
            o.put("developer", Settings.Global.getInt(c.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1);
            o.put("autoTime", Settings.Global.getInt(c.getContentResolver(), Settings.Global.AUTO_TIME, 0) == 1);
            o.put("fontScale", Settings.System.getFloat(c.getContentResolver(), Settings.System.FONT_SCALE, 1f) * 100);
            o.put("defaultRing", Settings.System.getString(c.getContentResolver(), Settings.System.RINGTONE));
            o.put("defaultNotify", Settings.System.getString(c.getContentResolver(), Settings.System.NOTIFICATION_SOUND));
            o.put("time", System.currentTimeMillis());
            o.put("tz", java.util.TimeZone.getDefault().getID());
            o.put("uptime", android.os.SystemClock.elapsedRealtime());

            // Wi-Fi
            try {
                WifiManager wifi = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                JSONObject w = new JSONObject();
                w.put("enabled", wifi.isWifiEnabled());
                WifiInfo info = wifi.getConnectionInfo();
                if (info != null) {
                    w.put("ssid", info.getSSID());
                    w.put("rssi", info.getRssi());
                    int ip = info.getIpAddress();
                    w.put("ip", (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff));
                }
                o.put("wifi", w);
            } catch (Exception ignored) {
            }

            // 蓝牙
            try {
                BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
                JSONObject b = new JSONObject();
                if (bt != null && hasBtConnect(c)) {
                    b.put("enabled", bt.isEnabled());
                    JSONArray devices = new JSONArray();
                    if (bt.isEnabled() && hasBtConnect(c)) {
                        Set<BluetoothDevice> bonded = bt.getBondedDevices();
                        if (bonded != null) {
                            for (BluetoothDevice d : bonded) {
                                try {
                                    devices.put(d.getName() + " (" + d.getAddress() + ")");
                                } catch (SecurityException ignored) {
                                }
                            }
                        }
                    }
                    b.put("bonded", devices);
                }
                o.put("bluetooth", b);
            } catch (SecurityException ignored) {
            } catch (Exception ignored) {
            }

            // GPS / 定位
            try {
                android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
                JSONObject g = new JSONObject();
                g.put("gps", lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER));
                g.put("network", lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER));
                o.put("location", g);
            } catch (Exception ignored) {
            }

            // 移动数据 / 连接
            try {
                ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.Network net = cm.getActiveNetwork();
                NetworkCapabilities cap = net == null ? null : cm.getNetworkCapabilities(net);
                JSONObject n = new JSONObject();
                n.put("wifi", cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                n.put("cellular", cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
                n.put("vpn", cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
                o.put("network", n);
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            Logx.e("devstate", "snapshot 失败 " + e);
        }
        return o;
    }

    /** 执行设备控制。返回结果描述。 */
    public static String set(Context c, String key, String value) {
        ControlLog.add("in", "device.set", key + "=" + value);
        try {
            switch (key) {
                case "brightness": {
                    int v = Integer.parseInt(value);
                    if (Settings.System.canWrite(c)) {
                        Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, v);
                        Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                        return "亮度已设为 " + v;
                    }
                    return ShellX.exec("settings put system screen_brightness " + v).text();
                }
                case "volume": {
                    String[] parts = value.split(":");
                    int stream = Integer.parseInt(parts[0]);
                    int vol = Integer.parseInt(parts[1]);
                    AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                    am.setStreamVolume(stream, vol, 0);
                    return "音量已设置";
                }
                case "ringer": {
                    AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                    am.setRingerMode(Integer.parseInt(value));
                    return "铃声模式已设置";
                }
                case "wifi":
                    return ShellX.exec("svc wifi " + (parseBool(value) ? "enable" : "disable")).text();
                case "bluetooth":
                    return ShellX.exec("svc bluetooth " + (parseBool(value) ? "enable" : "disable")).text();
                case "airplane":
                    return ShellX.exec("settings put global airplane_mode_on " + (parseBool(value) ? 1 : 0)
                            + " && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " + parseBool(value)).text();
                case "gps":
                    return ShellX.exec("settings put secure location_mode " + (parseBool(value) ? 3 : 0)).text();
                case "mobile_data":
                    return ShellX.exec("svc data " + (parseBool(value) ? "enable" : "disable")).text();
                case "rotation":
                    return ShellX.exec("settings put system accelerometer_rotation " + (parseBool(value) ? 1 : 0)).text();
                case "screenTimeout":
                    return ShellX.exec("settings put system screen_off_timeout " + Integer.parseInt(value)).text();
                case "screen":
                    if (parseBool(value)) {
                        return ShellX.exec("input keyevent KEYCODE_WAKEUP").text();
                    }
                    if (ShellX.hasPrivilege()) return ShellX.exec("input keyevent KEYCODE_SLEEP").text();
                    return Control.power() ? "已熄屏" : "无法熄屏（缺少权限）";
                case "lock":
                    return lock(iDevicePolicy(c)) ? "已锁定" : "锁屏失败";
                default:
                    return ShellX.exec("settings put global " + key + " " + value).text();
            }
        } catch (Exception e) {
            return "失败: " + e;
        }
    }

    private static android.app.admin.DevicePolicyManager iDevicePolicy(Context c) {
        return (android.app.admin.DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    public static boolean lock(android.app.admin.DevicePolicyManager dpm) {
        try {
            if (dpm != null) {
                dpm.lockNow();
                return true;
            }
        } catch (Exception ignored) {
        }
        return Control.power();
    }

    private static boolean hasBtConnect(Context c) {
        return Build.VERSION.SDK_INT < 31
                || c.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean parseBool(String v) {
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v) || "enable".equalsIgnoreCase(v);
    }

    private static String ringerModeName(int mode) {
        switch (mode) {
            case AudioManager.RINGER_MODE_SILENT:
                return "静音";
            case AudioManager.RINGER_MODE_VIBRATE:
                return "振动";
            default:
                return "响铃";
        }
    }

    private static String plugName(int plugged) {
        switch (plugged) {
            case BatteryManager.BATTERY_PLUGGED_AC:
                return "交流充电";
            case BatteryManager.BATTERY_PLUGGED_USB:
                return "USB 充电";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS:
                return "无线充电";
            default:
                return "未充电";
        }
    }
}
