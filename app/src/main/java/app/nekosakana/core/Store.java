package app.nekosakana.core;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 文件存储。应用自身的所有文件（日志、媒体、会话、APK、临时文件）都放在应用的外部专属私有目录：
 *
 * /storage/emulated/0/Android/data/&lt;包名&gt;/files
 *
 * 不使用 /data/data 私有目录（仅在外部存储完全不可用时兜底）。
 */
public final class Store {
    public static final String ROOT_NAME = "nekosakana";

    private static Store instance;
    private final File base;
    private final File fallback;

    private Store(Context context) {
        File internal = context.getFilesDir();
        File external = context.getExternalFilesDir(null);
        this.fallback = internal;
        this.base = (external != null && writable(external)) ? external : internal;
    }

    public static synchronized void init(Context context) {
        if (instance == null) instance = new Store(context.getApplicationContext());
    }

    public static Store get() {
        return instance;
    }

    /** 实际使用的根目录。 */
    public File base() {
        return base;
    }

    /** 是否使用外部专属目录 Android/data/&lt;包名&gt;/files（false 表示兜底到内部目录）。 */
    public boolean isPublicRoot() {
        return !base.equals(fallback);
    }

    public File dir(String name) {
        File f = new File(base, name);
        if (!f.exists()) //noinspection ResultOfMethodCallIgnored
            f.mkdirs();
        return f;
    }

    public File file(String relative) {
        File f = new File(base, relative);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        return f;
    }

    public File logsDir() {
        return dir("logs");
    }

    public File mediaDir() {
        return dir("media");
    }

    public File chatDir() {
        return dir("chat");
    }

    public File apkDir() {
        return dir("apk");
    }

    public File tmpDir() {
        return dir("tmp");
    }

    public boolean write(String relative, byte[] data) {
        File f = file(relative);
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
            return true;
        } catch (IOException e) {
            Logx.e("store", "写入失败 " + relative + ": " + e);
            return false;
        }
    }

    public boolean write(String relative, String text) {
        return write(relative, text.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] read(String relative) {
        try {
            File f = file(relative);
            if (!f.exists()) return null;
            return Files.readAllBytes(f.toPath());
        } catch (IOException e) {
            return null;
        }
    }

    public String readText(String relative) {
        byte[] b = read(relative);
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
    }

    /** 探测目录是否可写。 */
    private static boolean writable(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) return false;
            File probe = new File(dir, ".probe");
            try (FileOutputStream out = new FileOutputStream(probe)) {
                out.write(1);
            }
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
