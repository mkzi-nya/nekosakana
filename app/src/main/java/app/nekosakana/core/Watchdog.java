package app.nekosakana.core;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import app.nekosakana.net.NetHub;
import app.nekosakana.net.TailscaleController;

/**
 * 保活看门狗：定时用 AlarmManager 唤醒，确保核心前台服务与网络链路仍在运行。
 *
 * 专治厂商 ROM（ColorOS / EMUI 等）在后台杀掉前台服务、却保留进程的情况：
 * tsnet 后端在 {@code App.onCreate} 中启动，进程存活时对端仍看到本节点在线，
 * 但 {@code CoreService.onDestroy} 已执行 {@code NetHub.stop()} 关闭了监听端口，
 * 于是表现为「对端节点在线，但连接 45871 被拒绝」。看门狗会定期把服务拉回来。
 *
 * 本应用持有 {@code SYSTEM_ALERT_WINDOW}，属于 Android 12+ 后台启动前台服务的豁免项，
 * 因此从闹钟广播里 {@code startForegroundService} 是允许的。
 */
public final class Watchdog {
    private static final int REQ = 0x9e30;
    // 10 分钟一次：Android 在 Doze 下本身会把精确闹钟节流到约 9 分钟，缩短间隔不会更快恢复，
    // 反而增加唤醒耗电；服务在运行时的自愈由 CoreService 的 15s pinger 负责。
    public static final long INTERVAL_MS = 10 * 60 * 1000L;

    private Watchdog() {
    }

    private static PendingIntent pending(Context context) {
        Intent intent = new Intent(context, WatchdogReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, REQ, intent, flags);
    }

    /** 确保看门狗闹钟处于激活状态（幂等）。 */
    public static void schedule(Context context) {
        if (!Config.getBool(Config.K_AUTOSTART, true)) {
            cancel(context);
            return;
        }
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long trigger = SystemClock.elapsedRealtime() + INTERVAL_MS;
            PendingIntent pi = pending(context);
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            } catch (Throwable t) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            }
        } catch (Throwable t) {
            Logx.w("watchdog", "调度失败 " + t);
        }
    }

    public static void cancel(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pending(context));
        } catch (Throwable ignored) {
        }
    }

    /** 闹钟触发：尽力恢复服务与链路，并重新排程。 */
    public static void tick(Context context) {
        Context ctx = context.getApplicationContext();
        try {
            if (Config.getBool(Config.K_AUTOSTART, true)) {
                CoreService.start(ctx);
                if (Config.tailnetMode() && Config.getBool("tailscale_authenticated", false)
                        && !NetHub.get().isRunning()) {
                    TailscaleController.connect(ctx, null);
                }
            }
        } catch (Throwable t) {
            Logx.w("watchdog", "恢复服务失败 " + t);
        }
        schedule(ctx);
    }
}
