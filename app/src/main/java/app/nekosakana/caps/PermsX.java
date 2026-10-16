package app.nekosakana.caps;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

import app.nekosakana.core.Config;
import rikka.shizuku.Shizuku;

/**
 * 权限与授权清单。集中描述“还有哪些没授权”，并提供跳转/请求动作。
 * 所有权限均为可选：未授权时对应功能在运行时不会被调用（见 docs/03-权限模型.md）。
 */
public final class PermsX {
    public static final class Perm {
        public final String id;
        public final String title;
        public final String desc;
        public final boolean granted;
        public final String action; // 按钮文案，空则无需操作
        public final String kind;   // runtime / special / capability

        Perm(String id, String title, String desc, boolean granted, String action, String kind) {
            this.id = id;
            this.title = title;
            this.desc = desc;
            this.granted = granted;
            this.action = action;
            this.kind = kind;
        }
    }

    private PermsX() {
    }

    public static List<Perm> collect(Context c) {
        List<Perm> list = new ArrayList<>();

        // ---- 运行时权限 ----
        boolean storage = Build.VERSION.SDK_INT >= 30
                ? android.os.Environment.isExternalStorageManager()
                : granted(c, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        list.add(new Perm("storage", "存储与文件", "读写公共存储；未授权时文件仍可存放到应用外部私有目录（Android/data）。",
                storage, storage ? "已授权" : "去授权", "special"));

        boolean phone = granted(c, Manifest.permission.READ_PHONE_STATE) || granted(c, Manifest.permission.CALL_PHONE);
        list.add(new Perm("phone", "电话与通话", "读取通话状态、拨打电话。",
                phone, phone ? "已授权" : "授权", "runtime"));

        boolean contacts = granted(c, Manifest.permission.READ_CONTACTS);
        list.add(new Perm("contacts", "联系人", "读取联系人。",
                contacts, contacts ? "已授权" : "授权", "runtime"));

        boolean calllog = granted(c, Manifest.permission.READ_CALL_LOG);
        list.add(new Perm("calllog", "通话记录", "读取通话记录。",
                calllog, calllog ? "已授权" : "授权", "runtime"));

        boolean location = granted(c, Manifest.permission.ACCESS_FINE_LOCATION);
        list.add(new Perm("location", "位置", "读取定位，用于轨迹与位置查看。",
                location, location ? "已授权" : "授权", "runtime"));

        boolean backgroundLocation = Build.VERSION.SDK_INT < 29 || granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        list.add(new Perm("location_bg", "后台位置", "熄屏后仍可记录轨迹（Android 10+ 需单独授权）。",
                backgroundLocation, backgroundLocation ? "已授权" : "授权", "runtime"));

        boolean camera = granted(c, Manifest.permission.CAMERA);
        list.add(new Perm("camera", "相机", "拍照与扫码等。",
                camera, camera ? "已授权" : "授权", "runtime"));

        boolean mic = granted(c, Manifest.permission.RECORD_AUDIO);
        list.add(new Perm("mic", "麦克风", "采集音频。",
                mic, mic ? "已授权" : "授权", "runtime"));

        boolean sensors = granted(c, Manifest.permission.BODY_SENSORS);
        list.add(new Perm("sensors", "身体传感器", "步数、心率等。",
                sensors, sensors ? "已授权" : "授权", "runtime"));

        boolean activityRecognition = granted(c, Manifest.permission.ACTIVITY_RECOGNITION);
        list.add(new Perm("activity", "活动识别", "步数与运动状态识别。",
                activityRecognition, activityRecognition ? "已授权" : "授权", "runtime"));

        boolean notification = Build.VERSION.SDK_INT < 33 || granted(c, Manifest.permission.POST_NOTIFICATIONS);
        list.add(new Perm("notification", "通知", "显示前台服务状态与消息提醒。",
                notification, notification ? "已授权" : "授权", "runtime"));

        // ---- 特殊权限 ----
        boolean overlay = Settings.canDrawOverlays(c);
        list.add(new Perm("overlay", "悬浮窗", "在对方屏幕上展示消息、锁定页等悬浮内容。",
                overlay, overlay ? "已授权" : "去授权", "special"));

        boolean usage = hasUsageAccess(c);
        list.add(new Perm("usage", "使用情况访问", "查看应用使用历史与当前前台应用。",
                usage, usage ? "已授权" : "去授权", "special"));

        boolean battery = isIgnoringBattery(c);
        list.add(new Perm("battery", "忽略电池优化", "保证后台连接与传输稳定。",
                battery, battery ? "已授权" : "去授权", "special"));

        boolean writeSettings = Settings.System.canWrite(c);
        list.add(new Perm("write_settings", "修改系统设置", "直接调整亮度等系统设置。",
                writeSettings, writeSettings ? "已授权" : "去授权", "special"));

        boolean install = c.getPackageManager().canRequestPackageInstalls();
        list.add(new Perm("install", "安装应用", "安装 APK；未授权时改调用系统安装器。",
                install, install ? "已授权" : "去授权", "special"));

        boolean admin = isAdmin(c);
        list.add(new Perm("admin", "设备管理员", "锁屏、停用部分功能等。",
                admin, admin ? "已授权" : "去授权", "special"));

        boolean access = AccessService.isConnected();
        list.add(new Perm("accessibility", "无障碍服务", "在无 root/ADB 时用于远程点击、滑动、文字输入。",
                access, access ? "已授权" : "去授权", "special"));

        boolean notifListener = Build.VERSION.SDK_INT >= 27 && notifListenerEnabled(c);
        list.add(new Perm("notif_listener", "通知使用权", "读取通知内容（可选）。",
                notifListener, notifListener ? "已授权" : "去授权", "special"));

        // ---- 提权能力 ----
        int backend = ShellX.backend();
        boolean root = backend == ShellX.ROOT;
        list.add(new Perm("root", "Root 权限", "优先使用 Root 执行系统级操作。",
                root, root ? "已就绪" : "检测", "capability"));

        boolean shizuku = backend >= ShellX.SHIZUKU;
        list.add(new Perm("shizuku", "Shizuku / ADB", "通过无线调试或电脑 ADB 授权执行系统级操作。",
                shizuku, shizuku ? "已就绪" : "去授权", "capability"));

        return list;
    }

    public static boolean granted(Context c, String permission) {
        return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasUsageAccess(Context c) {
        try {
            android.app.AppOpsManager appOps = (android.app.AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), c.getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isIgnoringBattery(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isAdmin(Context c) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm != null && dpm.isAdminActive(adminComponent(c));
        } catch (Exception e) {
            return false;
        }
    }

    public static ComponentName adminComponent(Context c) {
        return new ComponentName(c, AdminReceiver.class);
    }

    private static boolean notifListenerEnabled(Context c) {
        try {
            String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(c.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    /** 未授权项数量（用于主页提示）。 */
    public static int missingCount(Context c) {
        int n = 0;
        for (Perm p : collect(c)) if (!p.granted) n++;
        return n;
    }

    /** 执行授权动作。requestCode 用于 admin/overlay 等需要回跳的场景。 */
    public static void perform(Activity a, Perm p, int requestCode) {
        try {
            switch (p.id) {
                case "storage":
                    if (Build.VERSION.SDK_INT >= 30) {
                        Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + a.getPackageName()));
                        a.startActivity(i);
                    } else {
                        a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                Manifest.permission.READ_EXTERNAL_STORAGE}, requestCode);
                    }
                    return;
                case "phone":
                    a.requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS}, requestCode);
                    return;
                case "contacts":
                    a.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, requestCode);
                    return;
                case "calllog":
                    a.requestPermissions(new String[]{Manifest.permission.READ_CALL_LOG}, requestCode);
                    return;
                case "location":
                    a.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, requestCode);
                    return;
                case "location_bg":
                    a.requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, requestCode);
                    return;
                case "camera":
                    a.requestPermissions(new String[]{Manifest.permission.CAMERA}, requestCode);
                    return;
                case "mic":
                    a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, requestCode);
                    return;
                case "sensors":
                    a.requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, requestCode);
                    return;
                case "activity":
                    a.requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, requestCode);
                    return;
                case "notification":
                    if (Build.VERSION.SDK_INT >= 33) {
                        a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, requestCode);
                    }
                    return;
                case "overlay":
                    a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + a.getPackageName())));
                    return;
                case "usage":
                    a.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    return;
                case "battery":
                    try {
                        a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + a.getPackageName())));
                    } catch (Exception e) {
                        a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                    return;
                case "write_settings":
                    a.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:" + a.getPackageName())));
                    return;
                case "install":
                    a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + a.getPackageName())));
                    return;
                case "admin":
                    Intent adminIntent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                    adminIntent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(a));
                    adminIntent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "用于锁定设备与停用应用等远程协助功能。");
                    a.startActivity(adminIntent);
                    return;
                case "accessibility":
                    a.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    return;
                case "notif_listener":
                    a.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                    return;
                case "shizuku":
                    if (Shizuku.pingBinder()) {
                        try {
                            Shizuku.requestPermission(requestCode);
                        } catch (Throwable t) {
                            openShizuku(a);
                        }
                    } else {
                        openShizuku(a);
                    }
                    return;
                case "root":
                    ShellX.invalidate();
                    ShellX.backend();
                    return;
                default:
            }
        } catch (Exception e) {
            android.util.Log.w("perms", "perform " + p.id + " 失败", e);
        }
    }

    /**
     * 借助 Root / Shizuku(ADB) 批量自动授予其余权限。
     * 需要在工作线程调用（会执行多条 shell 命令）。返回结果摘要。
     */
    public static String autoGrant(Context c) {
        if (!ShellX.hasPrivilege()) return "未检测到 Root / ADB(Shizuku) 权限";
        String pkg = c.getPackageName();
        String[] runtime = {
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.POST_NOTIFICATIONS,
        };
        String[] appops = {
                "SYSTEM_ALERT_WINDOW",
                "GET_USAGE_STATS",
                "WRITE_SETTINGS",
                "REQUEST_INSTALL_PACKAGES",
                "MANAGE_EXTERNAL_STORAGE",
                "ACCESS_BACKGROUND_LOCATION",
        };
        List<String> cmds = new ArrayList<>();
        for (String p : runtime) cmds.add("pm grant " + pkg + " " + p);
        for (String op : appops) cmds.add("appops set " + pkg + " " + op + " allow");
        cmds.add("dumpsys deviceidle whitelist +" + pkg);
        cmds.add("cmd deviceidle whitelist +" + pkg);
        cmds.add("dpm set-active-admin " + pkg + "/" + AdminReceiver.class.getName());
        int ok = 0;
        for (String cmd : cmds) {
            try {
                if (ShellX.exec(30000, cmd).ok()) ok++;
            } catch (Throwable ignored) {
            }
        }
        int total = cmds.size();
        // 无障碍：追加而非覆盖，避免关掉其他无障碍服务。
        if (appendSecure("enabled_accessibility_services", pkg + "/" + AccessService.class.getName())) ok++;
        if (ShellX.exec(15000, "settings put secure accessibility_enabled 1").ok()) ok++;
        if (ShellX.exec(15000, "settings put secure touch_exploration_enabled 0").ok()) ok++;
        total += 3;
        // 通知使用权：cmd notification 优先，同时直接写 secure 设置兜底（追加，不覆盖其他应用）。
        String notif = pkg + "/" + NotifListener.class.getName();
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                if (ShellX.exec(15000, "cmd notification allow_listener " + notif).ok()) ok++;
            } catch (Throwable ignored) {
            }
            total++;
        }
        if (appendSecure("enabled_notification_listeners", notif)) ok++;
        total++;
        if (ShellX.exec(15000, "cmd notification set_dnd off").ok()) ok++;
        ShellX.invalidate();
        return "已通过 " + ShellX.name(ShellX.backend()) + " 自动授予 " + ok + "/" + total
                + " 项（个别项受系统版本限制可能失败）";
    }

    /** 向以冒号分隔的 secure 设置项追加一个组件（已存在则跳过），避免覆盖其他应用。 */
    private static boolean appendSecure(String key, String component) {
        try {
            String current = ShellX.exec(15000, "settings get secure " + key).text();
            if (current == null || current.equals("null") || current.trim().isEmpty()) current = "";
            current = current.trim();
            if (current.contains(component)) return true;
            String merged = current.isEmpty() ? component : current + ":" + component;
            return ShellX.exec(15000, "settings put secure " + key + " " + ShellX.quote(merged)).ok();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void openShizuku(Context c) {
        try {
            Intent launch = c.getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(launch);
            } else {
                Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"));
                web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(web);
            }
        } catch (Exception ignored) {
        }
    }
}
