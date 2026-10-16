package app.nekosakana.caps;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;

import app.nekosakana.core.ControlLog;
import app.nekosakana.core.FileSharer;
import app.nekosakana.core.Logx;
import app.nekosakana.core.Store;

/** APK 安装：优先 Root/ADB，其次系统安装器。 */
public final class InstallerX {
    private InstallerX() {
    }

    public static String installBytes(Context c, byte[] data, String name) {
        if (name == null || !name.endsWith(".apk")) name = "install-" + System.currentTimeMillis() + ".apk";
        File apk = Store.get().file("apk/" + name);
        if (!Store.get().write("apk/" + name, data)) return "写入失败";
        return install(c, apk);
    }

    public static String install(Context c, File apk) {
        ControlLog.add("in", "install", apk.getName());
        boolean self = isSelf(c, apk);
        if (ShellX.hasPrivilege()) {
            ShellX.Result r;
            try {
                // 覆盖安装自身会杀掉当前进程，让 shell 在安装完成后重新拉起应用。
                String command = "pm install -r -S " + apk.length();
                if (self) {
                    command += "; sleep 1; am start -n " + c.getPackageName()
                            + "/app.nekosakana.ui.MainActivity";
                }
                r = ShellX.execWithInput(60000, java.nio.file.Files.readAllBytes(apk.toPath()), command);
            } catch (Exception e) { return "安装失败: " + e; }
            if (r.ok() || r.text().toLowerCase().contains("success")) {
                return "已安装 (" + ShellX.name(r.backend) + ")";
            }
            Logx.w("install", "特权安装失败，回退系统安装器: " + r.text());
        }
        try {
            Uri uri = FileSharer.uriFor(c.getPackageName(), apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(intent);
            return "已调用系统安装器";
        } catch (Exception e) {
            return "安装失败: " + e;
        }
    }

    /** 判断待安装的 APK 是否为本应用自身。 */
    private static boolean isSelf(Context c, File apk) {
        try {
            android.content.pm.PackageInfo info = c.getPackageManager()
                    .getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            return info != null && c.getPackageName().equals(info.packageName);
        } catch (Throwable t) {
            return false;
        }
    }
}
