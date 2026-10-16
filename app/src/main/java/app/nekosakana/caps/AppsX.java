package app.nekosakana.caps;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

import app.nekosakana.core.ControlLog;
import app.nekosakana.core.Logx;

/** 应用列表、冻结/解冻、启动与卸载。 */
public final class AppsX {
    private AppsX() {
    }

    public static JSONArray list(Context c, boolean includeSystem) {
        JSONArray arr = new JSONArray();
        try {
            PackageManager pm = c.getPackageManager();
            DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            boolean owner = dpm != null && dpm.isDeviceOwnerApp(c.getPackageName());
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA
                    | (owner ? PackageManager.MATCH_UNINSTALLED_PACKAGES : 0));
            for (ApplicationInfo info : apps) {
                boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (system && !includeSystem) continue;
                JSONObject o = new JSONObject();
                o.put("pkg", info.packageName);
                o.put("label", pm.getApplicationLabel(info).toString());
                o.put("system", system);
                o.put("enabled", info.enabled);
                o.put("frozen", !info.enabled || (owner && dpm.isApplicationHidden(PermsX.adminComponent(c), info.packageName)));
                arr.put(o);
            }
        } catch (Exception e) {
            Logx.e("apps", "list 失败 " + e);
        }
        return arr;
    }

    public static JSONArray frozen(Context c) {
        JSONArray all = list(c, true);
        JSONArray result = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && o.optBoolean("frozen")) result.put(o);
        }
        return result;
    }

    public static String freeze(Context c, String pkg) {
        ControlLog.add("in", "apps.freeze", pkg);
        // 1) 设备所有者可直接隐藏
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(c.getPackageName())) {
                if (dpm.setApplicationHidden(PermsX.adminComponent(c), pkg, true)) {
                    return "已通过设备所有者冻结";
                }
            }
        } catch (Exception ignored) {
        }
        // 2) Root / ADB
        ShellX.Result r = ShellX.exec("pm disable-user --user 0 " + pkg);
        if (r.ok()) return "已冻结 (" + ShellX.name(r.backend) + ")";
        // 3) 普通管理员无法隐藏第三方应用
        return "冻结失败：" + r.text();
    }

    public static String unfreeze(Context c, String pkg) {
        ControlLog.add("in", "apps.unfreeze", pkg);
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(c.getPackageName())) {
                if (dpm.setApplicationHidden(PermsX.adminComponent(c), pkg, false)) {
                    return "已通过设备所有者解冻";
                }
            }
        } catch (Exception ignored) {
        }
        ShellX.Result r = ShellX.exec("pm enable " + pkg);
        if (r.ok()) return "已解冻 (" + ShellX.name(r.backend) + ")";
        return "解冻失败：" + r.text();
    }

    public static boolean launch(Context c, String pkg) {
        try {
            Intent intent = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) return false;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(intent);
            ControlLog.add("in", "apps.launch", pkg);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 强制停止。 */
    public static String forceStop(String pkg) {
        ControlLog.add("in", "apps.forceStop", pkg);
        return ShellX.exec("am force-stop " + pkg).text();
    }

    public static String clearData(String pkg) {
        ControlLog.add("in", "apps.clearData", pkg);
        return ShellX.exec("pm clear " + pkg).text();
    }

    public static String uninstall(Context c, String pkg) {
        ControlLog.add("in", "apps.uninstall", pkg);
        if (ShellX.hasPrivilege()) {
            ShellX.Result r = ShellX.exec("pm uninstall --user 0 " + pkg);
            return r.text();
        }
        try {
            Intent intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(intent);
            return "已调用系统卸载界面";
        } catch (Exception e) {
            return "卸载失败: " + e;
        }
    }
}
