package app.nekosakana.core;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 7z 高压缩度归档。用于压缩日志与打包文件（见 docs/05-日志与归档.md）。
 */
public final class Archive {
    private Archive() {
    }

    /** 将若干文件压缩为 .7z（LZMA2，高压缩比）。 */
    public static boolean compress(List<File> inputs, File output) {
        if (inputs == null || inputs.isEmpty()) return false;
        File parent = output.getParentFile();
        if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        try (SevenZOutputFile sevenZ = new SevenZOutputFile(output)) {
            sevenZ.setContentCompression(SevenZMethod.LZMA2);
            byte[] buffer = new byte[64 * 1024];
            for (File input : inputs) {
                if (!input.exists()) continue;
                SevenZArchiveEntry entry = sevenZ.createArchiveEntry(input, input.getName());
                sevenZ.putArchiveEntry(entry);
                try (InputStream in = new FileInputStream(input)) {
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        sevenZ.write(buffer, 0, n);
                    }
                }
                sevenZ.closeArchiveEntry();
            }
            sevenZ.finish();
            return true;
        } catch (IOException e) {
            Logx.e("archive", "7z 压缩失败: " + e);
            return false;
        }
    }

    /** 解压 .7z 到指定目录。 */
    public static boolean extract(File archive, File targetDir) {
        if (!targetDir.exists()) //noinspection ResultOfMethodCallIgnored
            targetDir.mkdirs();
        try (SevenZFile sevenZ = new SevenZFile(archive)) {
            SevenZArchiveEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = sevenZ.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                File out = new File(targetDir, new File(entry.getName()).getName());
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    int n;
                    while ((n = sevenZ.read(buffer)) > 0) {
                        fos.write(buffer, 0, n);
                    }
                }
            }
            return true;
        } catch (IOException e) {
            Logx.e("archive", "7z 解压失败: " + e);
            return false;
        }
    }

    /** 内存中压缩一段文本，返回 .7z 字节。 */
    public static byte[] compressText(String name, String text) {
        File tmp = new File(Store.get().tmpDir(), name + "-" + System.nanoTime());
        try {
            //noinspection ResultOfMethodCallIgnored
            tmp.deleteOnExit();
            java.nio.file.Files.write(tmp.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            List<File> list = new ArrayList<>();
            list.add(tmp);
            File out = new File(tmp.getAbsolutePath() + ".7z");
            if (compress(list, out)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (InputStream in = new FileInputStream(out)) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) bos.write(b, 0, n);
                }
                return bos.toByteArray();
            }
        } catch (IOException ignored) {
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        return null;
    }
}
