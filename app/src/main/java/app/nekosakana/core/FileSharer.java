package app.nekosakana.core;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 轻量文件提供器，替代 androidx FileProvider，避免引入 androidX 依赖。
 * 只允许分享 {@link Store#base()} 目录下的文件。
 *
 * URI 形如：content://<包名>.fileprovider/<URL 编码的绝对路径>
 */
public class FileSharer extends ContentProvider {
    public static final String SUFFIX = ".fileprovider";

    public static String authority(String pkg) {
        return pkg + SUFFIX;
    }

    public static Uri uriFor(String pkg, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(authority(pkg))
                .appendPath(Uri.encode(file.getAbsolutePath()))
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String path;
        try {
            path = uri.getLastPathSegment();
        } catch (Exception e) {
            throw new FileNotFoundException("bad uri");
        }
        if (path == null) throw new FileNotFoundException("no path");
        File file = new File(path);
        File base = Store.get().base();
        try {
            String canonicalBase = base.getCanonicalPath();
            String canonicalFile = file.getCanonicalPath();
            if (!canonicalFile.startsWith(canonicalBase + File.separator)) {
                throw new FileNotFoundException("forbidden");
            }
        } catch (java.io.IOException e) {
            throw new FileNotFoundException("resolve failed");
        }
        if (!file.exists()) throw new FileNotFoundException(path);
        return file;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = resolve(uri);
            String[] columns = projection != null ? projection
                    : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor cursor = new MatrixCursor(columns);
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) {
                if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(file.getName());
                else if (OpenableColumns.SIZE.equals(column)) row.add(file.length());
                else row.add(null);
            }
            return cursor;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) return "*/*";
        String lower = name.toLowerCase();
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (lower.matches(".*\\.(png|jpg|jpeg|gif|webp|bmp)$")) return "image/*";
        if (lower.matches(".*\\.(mp4|mkv|mov|webm|avi)$")) return "video/*";
        if (lower.matches(".*\\.(mp3|wav|aac|flac|ogg|m4a)$")) return "audio/*";
        return "*/*";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
