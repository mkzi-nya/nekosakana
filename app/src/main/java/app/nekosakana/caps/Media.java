package app.nekosakana.caps;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;

/** 图片编解码辅助：高压缩度 WebP。 */
public final class Media {
    private Media() {
    }

    public static byte[] webp(Bitmap bitmap, int quality) {
        if (bitmap == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Bitmap.CompressFormat format = android.os.Build.VERSION.SDK_INT >= 30
                ? Bitmap.CompressFormat.WEBP_LOSSY
                : Bitmap.CompressFormat.WEBP;
        bitmap.compress(format, clamp(quality), bos);
        return bos.toByteArray();
    }

    public static byte[] jpeg(Bitmap bitmap, int quality) {
        if (bitmap == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, clamp(quality), bos);
        return bos.toByteArray();
    }

    public static Bitmap decode(byte[] data) {
        return data == null ? null : BitmapFactory.decodeByteArray(data, 0, data.length);
    }

    public static Bitmap scale(Bitmap src, int maxWidth) {
        if (src == null || maxWidth <= 0 || src.getWidth() <= maxWidth) return src;
        int w = maxWidth;
        int h = Math.max(1, src.getHeight() * maxWidth / src.getWidth());
        return Bitmap.createScaledBitmap(src, w, h, true);
    }

    private static int clamp(int quality) {
        return Math.max(1, Math.min(100, quality));
    }
}
