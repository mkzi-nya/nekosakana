package app.nekosakana.caps;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 通知历史（内存环形缓冲，最新在前）。由 {@link NotifListener} 填充，
 * 通过 {@code notif.list} 请求供对端分页读取（见 docs/06-操控日志.md）。
 */
public final class NotifHub {
    public static final class Entry {
        public final String app;
        public final String pkg;
        public final String title;
        public final String text;
        public final long time;

        Entry(String app, String pkg, String title, String text, long time) {
            this.app = app == null ? "" : app;
            this.pkg = pkg == null ? "" : pkg;
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
            this.time = time;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("app", app);
                o.put("pkg", pkg);
                o.put("title", title);
                o.put("text", text);
                o.put("time", time);
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    private static final Object LOCK = new Object();
    private static final Deque<Entry> LIST = new ArrayDeque<>();
    private static final int MAX = 200;

    private NotifHub() {
    }

    public static void add(Entry entry) {
        if (entry == null) return;
        synchronized (LOCK) {
            LIST.addFirst(entry);
            while (LIST.size() > MAX) LIST.removeLast();
        }
    }

    public static int size() {
        synchronized (LOCK) {
            return LIST.size();
        }
    }

    /** 分页读取，最新在前。 */
    public static JSONArray page(int offset, int count) {
        JSONArray arr = new JSONArray();
        synchronized (LOCK) {
            int i = 0;
            int added = 0;
            for (Entry entry : LIST) {
                if (i++ < offset) continue;
                if (added++ >= count) break;
                arr.put(entry.toJson());
            }
        }
        return arr;
    }

    public static void clear() {
        synchronized (LOCK) {
            LIST.clear();
        }
    }
}
