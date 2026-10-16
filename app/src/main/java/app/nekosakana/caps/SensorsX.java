package app.nekosakana.caps;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 传感器状态：枚举与关键读数。 */
public final class SensorsX {
    private SensorsX() {
    }

    private static final int[] KEY_TYPES = {
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_PROXIMITY,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_AMBIENT_TEMPERATURE,
            Sensor.TYPE_RELATIVE_HUMIDITY,
            Sensor.TYPE_STEP_COUNTER,
            Sensor.TYPE_STEP_DETECTOR,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_HEART_RATE
    };

    public static JSONObject snapshot(Context c) {
        JSONObject o = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
            List<Sensor> sensors = sm.getSensorList(Sensor.TYPE_ALL);
            for (Sensor s : sensors) {
                JSONObject j = new JSONObject();
                j.put("name", s.getName());
                j.put("type", s.getStringType());
                j.put("vendor", s.getVendor());
                j.put("power", s.getPower());
                j.put("maxRange", s.getMaximumRange());
                j.put("resolution", s.getResolution());
                arr.put(j);
            }
            o.put("sensors", arr);

            JSONObject values = new JSONObject();
            for (int type : KEY_TYPES) {
                Sensor s = sm.getDefaultSensor(type);
                if (s == null) continue;
                String v = read(c, sm, type);
                if (v != null) values.put(s.getStringType(), v);
            }
            o.put("values", values);
        } catch (Exception e) {
            o.optString("error", e.toString());
        }
        return o;
    }

    /** 手机姿态：方位角（朝向）与俯仰/翻滚，附带中文描述。 */
    public static JSONObject orientation(Context c) {
        JSONObject o = new JSONObject();
        try {
            float[] gravity = readVec(c, Sensor.TYPE_GRAVITY);
            if (gravity == null) gravity = readVec(c, Sensor.TYPE_ACCELEROMETER);
            float[] mag = readVec(c, Sensor.TYPE_MAGNETIC_FIELD);
            if (gravity != null) {
                float[] rot = new float[9];
                float[] inc = new float[9];
                if (mag != null && SensorManager.getRotationMatrix(rot, inc, gravity, mag)) {
                    float[] orient = new float[3];
                    SensorManager.getOrientation(rot, orient);
                    double azimuth = Math.toDegrees(orient[0]);
                    if (azimuth < 0) azimuth += 360;
                    double pitch = Math.toDegrees(orient[1]);
                    double roll = Math.toDegrees(orient[2]);
                    o.put("azimuth", azimuth);
                    o.put("pitch", pitch);
                    o.put("roll", roll);
                    o.put("headingText", headingText(azimuth));
                }
                o.put("postureText", postureText(gravity));
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    private static float[] readVec(Context c, int type) {
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        final Sensor sensor = sm.getDefaultSensor(type);
        if (sensor == null) return null;
        HandlerThread thread = new HandlerThread("sensor-vec");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        final CountDownLatch latch = new CountDownLatch(1);
        final float[][] result = {null};
        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                result[0] = event.values.clone();
                latch.countDown();
            }

            @Override
            public void onAccuracyChanged(Sensor s, int accuracy) {
            }
        };
        try {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, handler);
            latch.await(700, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        } finally {
            sm.unregisterListener(listener);
            thread.quitSafely();
        }
        return result[0];
    }

    private static String headingText(double azimuth) {
        String[] dirs = {"正北", "东北", "正东", "东南", "正南", "西南", "正西", "西北"};
        int idx = ((int) Math.round(azimuth / 45.0)) % 8;
        return dirs[idx] + "（" + Math.round(azimuth) + "°）";
    }

    private static String postureText(float[] g) {
        if (g.length < 3) return "";
        double z = g[2];
        if (z > 7) return "屏幕朝上，大致平放";
        if (z < -7) return "屏幕朝下";
        if (Math.abs(g[1]) > Math.abs(g[0])) return g[1] > 0 ? "竖直握持" : "竖直倒置";
        return g[0] > 0 ? "横屏（右侧朝下）" : "横屏（左侧朝下）";
    }

    private static String read(Context c, SensorManager sm, int type) {
        final Sensor sensor = sm.getDefaultSensor(type);
        if (sensor == null) return null;
        HandlerThread thread = new HandlerThread("sensor-read");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] result = {null};
        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                result[0] = format(event);
                latch.countDown();
            }

            @Override
            public void onAccuracyChanged(Sensor s, int accuracy) {
            }
        };
        try {
            // 单次读取用中速采样即可，避免 FASTEST 在低端/高负载机型上造成瞬时压力。
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, handler);
            latch.await(700, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        } finally {
            sm.unregisterListener(listener);
            thread.quitSafely();
        }
        return result[0];
    }

    private static String format(SensorEvent e) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < e.values.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format(java.util.Locale.US, "%.3f", e.values[i]));
        }
        return sb.toString();
    }
}
