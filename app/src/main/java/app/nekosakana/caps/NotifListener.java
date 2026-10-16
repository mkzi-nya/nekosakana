package app.nekosakana.caps;

import android.app.Notification;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import app.nekosakana.core.Logx;

/**
 * 通知使用权服务。既让本应用出现在系统「通知使用权」设置中，
 * 也把收到的通知记入 {@link NotifHub}，供对端通过 {@code notif.list} 分页读取。
 */
public class NotifListener extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Logx.i("notif", "通知使用权已连接");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Logx.w("notif", "通知使用权已断开");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        try {
            if (sbn == null || sbn.getNotification() == null) return;
            String pkg = sbn.getPackageName();
            if (getPackageName().equals(pkg)) return;
            Notification n = sbn.getNotification();
            Bundle extras = n.extras;
            String title = text(extras, Notification.EXTRA_TITLE);
            if (title.isEmpty()) title = text(extras, Notification.EXTRA_TITLE_BIG);
            String body = text(extras, Notification.EXTRA_TEXT);
            if (body.isEmpty()) body = text(extras, Notification.EXTRA_BIG_TEXT);
            if (body.isEmpty()) body = text(extras, Notification.EXTRA_SUMMARY_TEXT);
            NotifHub.add(new NotifHub.Entry(label(pkg), pkg, title, body, sbn.getPostTime()));
        } catch (Throwable t) {
            Logx.w("notif", "记录通知失败 " + t);
        }
    }

    private static String text(Bundle extras, String key) {
        if (extras == null) return "";
        CharSequence value = extras.getCharSequence(key);
        return value == null ? "" : value.toString();
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
