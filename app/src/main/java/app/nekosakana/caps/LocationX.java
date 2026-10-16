package app.nekosakana.caps;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import app.nekosakana.core.Store;

/** 定位与历史轨迹。 */
public final class LocationX {
    private LocationX() {
    }

    public static JSONObject current(Context c) {
        JSONObject o = new JSONObject();
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                try {
                    Location loc = lm.getLastKnownLocation(provider);
                    if (loc != null && (best == null || loc.getTime() > best.getTime())) best = loc;
                } catch (SecurityException ignored) {
                }
            }
            if (best != null) return toJson(best);
        } catch (Exception e) {
            o.optString("error", e.toString());
        }
        return o;
    }

    /** 是否已授予定位运行时权限。 */
    public static boolean hasPermission(Context c) {
        try {
            return c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || c.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean gpsEnabled(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            return lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    /** 通过 shell 打开/关闭定位（需要 Shizuku/adb 等特权，否则无效）。 */
    public static boolean setGps(boolean on) {
        try {
            return app.nekosakana.caps.ShellX.exec("settings put secure location_mode " + (on ? "3" : "0")).code == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 只读报告：GPS 未开启时返回空位置，不主动获取。
     * 返回 {@code {gps, location}}，由调用方补充朝向。
     */
    public static JSONObject report(Context c) {
        JSONObject o = new JSONObject();
        try {
            boolean on = gpsEnabled(c);
            o.put("gps", on);
            o.put("location", on && hasPermission(c) ? current(c) : new JSONObject());
        } catch (Exception ignored) {
        }
        return o;
    }

    /**
     * 主动获取：GPS 未开启则先打开，等一次定位后（若是本次打开）再关闭。
     * 返回 {@code {gps, enabled, toggled, location}}。
     */
    public static JSONObject acquire(Context c, long timeoutMs) {
        JSONObject o = new JSONObject();
        boolean wasOn = gpsEnabled(c);
        if (!wasOn) {
            setGps(true);
            sleep(1800);
        }
        JSONObject loc = hasPermission(c) ? currentFresh(c, timeoutMs) : new JSONObject();
        if (!wasOn) setGps(false);
        try {
            o.put("gps", true);
            o.put("enabled", wasOn);
            o.put("toggled", !wasOn);
            o.put("location", loc);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 先等一次实时定位，超时则回退到最后已知位置。 */
    public static JSONObject currentFresh(Context c, long timeoutMs) {
        JSONObject fix = waitForFix(c, timeoutMs);
        if (fix != null && fix.has("lat")) return fix;
        return current(c);
    }

    private static JSONObject waitForFix(Context c, long timeoutMs) {
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        final JSONObject[] box = {null};
        final CountDownLatch latch = new CountDownLatch(1);
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                synchronized (box) {
                    if (location != null && box[0] == null) {
                        try {
                            box[0] = toJson(location);
                        } catch (Exception ignored) {
                        }
                    }
                }
                latch.countDown();
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };
        try {
            String provider = null;
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) provider = LocationManager.GPS_PROVIDER;
            else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) provider = LocationManager.NETWORK_PROVIDER;
            if (provider == null) return null;
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper());
            latch.await(Math.max(1000L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        } finally {
            try {
                lm.removeUpdates(listener);
            } catch (Exception ignored) {
            }
        }
        synchronized (box) {
            return box[0];
        }
    }

    private static JSONObject toJson(Location loc) {
        JSONObject o = new JSONObject();
        try {
            o.put("lat", loc.getLatitude());
            o.put("lon", loc.getLongitude());
            o.put("accuracy", loc.getAccuracy());
            o.put("altitude", loc.getAltitude());
            o.put("speed", loc.getSpeed());
            o.put("time", loc.getTime());
            o.put("provider", loc.getProvider());
        } catch (Exception ignored) {
        }
        return o;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void record(Context c) {
        JSONObject loc = current(c);
        if (!loc.has("lat")) return;
        try {
            loc.put("ts", System.currentTimeMillis());
            try (FileWriter writer = new FileWriter(Store.get().file("logs/track.log"), true)) {
                writer.write(loc.toString());
                writer.write('\n');
            }
        } catch (Exception ignored) {
        }
    }

    public static JSONArray track(int limit) {
        JSONArray arr = new JSONArray();
        File f = Store.get().file("logs/track.log");
        if (!f.exists()) return arr;
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(f.toPath());
            int from = Math.max(0, lines.size() - limit);
            for (int i = from; i < lines.size(); i++) {
                try {
                    arr.put(new JSONObject(lines.get(i)));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return arr;
    }

    public static void clearTrack() {
        //noinspection ResultOfMethodCallIgnored
        Store.get().file("logs/track.log").delete();
    }
}
