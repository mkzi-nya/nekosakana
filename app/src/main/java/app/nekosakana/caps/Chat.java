package app.nekosakana.caps;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

import app.nekosakana.core.Store;

/** 聊天记录存储（文字与文件）。 */
public final class Chat {
    public static final class Msg {
        public final boolean out;
        public final String text;
        public final String file; // 本地文件路径，可空
        public final String kind; // text / image / video / audio / file
        public final long ts;

        Msg(boolean out, String text, String file, String kind, long ts) {
            this.out = out;
            this.text = text;
            this.file = file;
            this.kind = kind;
            this.ts = ts;
        }
    }

    private Chat() {
    }

    public static void add(boolean out, String text, String file, String kind) {
        try {
            JSONObject o = new JSONObject();
            o.put("out", out);
            o.put("text", text == null ? "" : text);
            o.put("file", file == null ? "" : file);
            o.put("kind", kind == null ? "text" : kind);
            o.put("ts", System.currentTimeMillis());
            File f = Store.get().file("chat/history.log");
            try (FileWriter writer = new FileWriter(f, true)) {
                writer.write(o.toString());
                writer.write('\n');
            }
        } catch (Exception ignored) {
        }
    }

    public static List<Msg> history(int limit) {
        List<Msg> list = new ArrayList<>();
        File f = Store.get().file("chat/history.log");
        if (!f.exists()) return list;
        try {
            List<String> lines = java.nio.file.Files.readAllLines(f.toPath());
            int from = Math.max(0, lines.size() - Math.max(1, limit));
            for (int i = from; i < lines.size(); i++) {
                try {
                    JSONObject o = new JSONObject(lines.get(i));
                    list.add(new Msg(o.optBoolean("out"), o.optString("text"),
                            o.optString("file"), o.optString("kind"), o.optLong("ts")));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }
}
