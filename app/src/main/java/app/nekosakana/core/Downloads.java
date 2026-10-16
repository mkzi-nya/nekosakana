package app.nekosakana.core;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 把收到的文件保存到公共「下载」目录，方便在文件管理器里直接找到。
 * 优先用 MediaStore（Android 10+ 无需存储权限即可写入公共 Download）；
 * 失败再退回直接写公共 Download 目录（需要 MANAGE_EXTERNAL_STORAGE / 旧版权限）。
 */
public final class Downloads {
    private Downloads() {
    }

    /** 保存成功返回公共下载目录中的文件（可能为按实际路径推断），失败返回 null。 */
    public static File save(Context context, String name, String kind, byte[] data) {
        if (name == null || name.trim().isEmpty()) name = "nekosakana-" + System.currentTimeMillis();
        name = safeName(name);
        String mime = guessMime(name, kind);
        File saved = saveViaMediaStore(context, name, mime, data);
        if (saved != null && saved.exists()) return saved;
        File direct = saveDirect(name, data);
        return direct != null ? direct : saved;
    }

    private static File saveDirect(String name, byte[] data) {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return null;
            if (!dir.exists() && !dir.mkdirs()) return null;
            File out = unique(dir, name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(data);
            }
            return out;
        } catch (Throwable t) {
            Logx.w("downloads", "直接写入下载目录失败 " + t);
            return null;
        }
    }

    private static File saveViaMediaStore(Context context, String name, String mime, byte[] data) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) return null;
                out.write(data);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            context.getContentResolver().update(uri, done, null, null);
            File path = queryPath(context, uri);
            if (path != null) return path;
            return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
        } catch (Throwable t) {
            Logx.w("downloads", "MediaStore 写入失败 " + t);
            return null;
        }
    }

    private static File queryPath(Context context, Uri uri) {
        try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String data = cursor.getString(0);
                if (data != null && !data.isEmpty()) return new File(data);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static File unique(File dir, String name) {
        File out = new File(dir, name);
        if (!out.exists()) return out;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            File candidate = new File(dir, stem + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, System.currentTimeMillis() + "-" + name);
    }

    public static String safeName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        return cleaned.isEmpty() ? "file" : cleaned;
    }

    private static String guessMime(String name, String kind) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.US);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        if ("image".equals(kind)) return "image/*";
        if ("video".equals(kind)) return "video/*";
        if ("audio".equals(kind)) return "audio/*";
        return "application/octet-stream";
    }
}
