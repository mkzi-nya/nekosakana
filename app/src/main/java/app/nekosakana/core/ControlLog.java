package app.nekosakana.core;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 操控日志：记录对方对自己设备、以及自己向对方发起的操作。
 * 持久化到 &lt;存储根&gt;/logs/control.log，供 UI 展示（见 docs/06-操控日志.md）。
 */
public final class ControlLog {
    private static final Object LOCK = new Object();
    private static final List<JSONObject> MEM = new ArrayList<>();
    private static final int MAX = 800;

    private ControlLog() {
    }

    public static void add(String direction, String type, String detail) {
        try {
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("dir", direction); // in=对方操作我, out=我操作对方, sys=本机
            o.put("type", type);
            o.put("detail", detail == null ? "" : detail);
            synchronized (LOCK) {
                MEM.add(o);
                if (MEM.size() > MAX) MEM.remove(0);
            }
            append(o);
        } catch (Exception ignored) {
        }
    }

    private static void append(JSONObject o) {
        File f = Store.get().file("logs/control.log");
        try (FileWriter writer = new FileWriter(f, true)) {
            writer.write(o.toString());
            writer.write('\n');
        } catch (IOException e) {
            Logx.w("control-log", "写入失败 " + e);
        }
    }

    /** 内存紧张时收缩内存缓冲，降低内存占用（磁盘日志不受影响）。 */
    public static void trim(int keep) {
        synchronized (LOCK) {
            while (MEM.size() > keep) MEM.remove(0);
        }
    }

    public static List<JSONObject> all() {
        synchronized (LOCK) {
            return new ArrayList<>(MEM);
        }
    }

    public static String asText(int limit) {
        StringBuilder sb = new StringBuilder();
        List<JSONObject> list = all();
        int from = Math.max(0, list.size() - limit);
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US);
        for (int i = from; i < list.size(); i++) {
            JSONObject o = list.get(i);
            String dir = o.optString("dir", "");
            String arrow = dir.equals("in") ? "←" : dir.equals("out") ? "→" : "·";
            sb.append(fmt.format(new java.util.Date(o.optLong("ts"))))
                    .append(' ').append(arrow).append(' ')
                    .append(o.optString("type", "")).append("  ")
                    .append(o.optString("detail", "")).append('\n');
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (LOCK) {
            MEM.clear();
        }
        //noinspection ResultOfMethodCallIgnored
        Store.get().file("logs/control.log").delete();
    }
}
