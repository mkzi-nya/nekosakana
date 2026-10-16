package app.nekosakana.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import app.nekosakana.R;
import app.nekosakana.caps.LocationX;
import app.nekosakana.engine.Engine;
import app.nekosakana.net.FtpBridge;
import app.nekosakana.net.FtpServer;
import app.nekosakana.net.NetHub;
import app.nekosakana.net.PortForward;
import app.nekosakana.ui.MainActivity;

/**
 * 前台服务：维持网络链路、FTP、端口转发与状态通知。
 * 应用启动即运行（见 docs/01-架构总览.md）。
 */
public class CoreService extends Service {
    private static final String CHANNEL = "nekosakana-core";
    private static final int NOTIFICATION_ID = 1;
    private static CoreService instance;
    private boolean projecting;
    private volatile boolean mic;

    /** 应用在后台采集麦克风时，前台服务必须声明 microphone 类型，否则系统会静音麦克风。 */
    public static void setMicActive(boolean on) {
        CoreService service = instance;
        if (service != null) new Handler(service.getMainLooper()).post(() -> {
            if (service.mic == on) return;
            service.mic = on;
            service.startForegroundCompat();
        });
    }

    public static void projection(Context context, int resultCode, Intent data) {
        Intent intent = new Intent(context, CoreService.class);
        intent.setAction("projection");
        intent.putExtra("resultCode", resultCode);
        intent.putExtra("data", data);
        context.startForegroundService(intent);
    }

    /** 屏幕录制授权状态持久化：服务被系统重启后仍以前台投屏类型运行，避免授权丢失。 */
    private static final String K_PROJECTING = "screen_projecting";

    public static void endProjection() {
        Config.putBool(K_PROJECTING, false);
        CoreService service = instance;
        if (service != null) new Handler(service.getMainLooper()).post(() -> {
            service.projecting = false;
            service.startForegroundCompat();
        });
    }

    private HandlerThread background;
    private Handler handler;
    private boolean started;
    private String lastNotificationText = "";
    private long lastTailReconnect;
    private long notListeningSince;

    public static void start(Context context) {
        Intent intent = new Intent(context, CoreService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, CoreService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        background = new HandlerThread("core-bg");
        background.start();
        handler = new Handler(background.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Config.getBool(K_PROJECTING, false)) projecting = true;
        if (intent != null && "projection".equals(intent.getAction())) {
            projecting = true;
            Config.putBool(K_PROJECTING, true);
        }
        startForegroundCompat();
        if (intent != null && "projection".equals(intent.getAction())) {
            int resultCode = intent.getIntExtra("resultCode", 0);
            Intent data = intent.getParcelableExtra("data");
            handler.post(() -> Engine.get().provideProjection(this, resultCode, data));
        }
        if (!started) {
            started = true;
            if (Config.tailnetMode() && Config.getBool("tailscale_authenticated", false)) {
                app.nekosakana.net.TailscaleController.connect(this, null);
            }
            NetHub.get().start(this, Engine.get());
            // FTP 无需配置，直接启动，仅监听 127.0.0.1（根 /sdcard）。
            FtpServer.get().start(this);
            // FTP 感知隧道：本机 127.0.0.1:2222 访问对方 /sdcard。
            FtpBridge.get().start(this);
            if (Config.getBool(Config.K_FORWARD_ENABLED, false)) {
                PortForward.get().start(this);
            }
            handler.post(pinger);
        }
        Watchdog.schedule(this);
        return START_STICKY;
    }

    private final Runnable pinger = new Runnable() {
        @Override
        public void run() {
            updateNotification();
            healthCheck();
            if (Config.getBool("track_enabled", false)) {
                handler.post(() -> {
                    try {
                        LocationX.record(CoreService.this);
                    } catch (Throwable ignored) {
                    }
                });
            }
            // 自愈与通知刷新无需高频；15s 一次足以在 30s 内发现并恢复异常，同时降低待机功耗。
            handler.postDelayed(this, 15000);
        }
    };

    /**
     * 自愈：CoreService 被厂商 ROM 杀过又拉起、或 NetHub 其内部线程异常退出时，
     * 确保网络中枢与尾网地址处于可用状态（见 Watchdog 注释）。
     */
    private void healthCheck() {
        try {
            if (!NetHub.get().isRunning()) {
                Logx.w("core", "自愈：NetHub 未运行，重新启动");
                notListeningSince = 0;
                NetHub.get().start(this, Engine.get());
            } else if (!NetHub.get().isListening()) {
                if (notListeningSince == 0) notListeningSince = System.currentTimeMillis();
                else if (System.currentTimeMillis() - notListeningSince > 30000) {
                    notListeningSince = 0;
                    Logx.w("core", "自愈：监听端口 30s 未就绪，重启网络中枢");
                    NetHub.get().stop();
                    NetHub.get().start(this, Engine.get());
                }
            } else {
                notListeningSince = 0;
            }
            if (Config.tailnetMode() && Config.getBool("tailscale_authenticated", false)
                    && !NetHub.get().isListening() && Config.selfAddress().isEmpty()) {
                long now = System.currentTimeMillis();
                if (now - lastTailReconnect > 30000) {
                    lastTailReconnect = now;
                    Logx.w("core", "自愈：尾网地址丢失，重新连接 Tailscale");
                    app.nekosakana.net.TailscaleController.connect(this, null);
                }
            }
        } catch (Throwable t) {
            Logx.w("core", "自愈检查失败 " + t);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户从最近任务划掉应用后，部分 ROM 会连带杀掉服务；排程看门狗以便恢复。
        Watchdog.schedule(this);
        super.onTaskRemoved(rootIntent);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(CHANNEL, "nekosakana 连接",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        PendingIntent pending = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("nekosakana")
                .setContentText(NetHub.get().statusText())
                .setSmallIcon(R.drawable.ic_stat)
                .setOngoing(true)
                .setContentIntent(pending)
                .build();
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            int types = Build.VERSION.SDK_INT >= 34
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (projecting) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (mic) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            try {
                startForeground(NOTIFICATION_ID, notification, types);
                return;
            } catch (Throwable ignored) {
            }
        }
        startForeground(NOTIFICATION_ID, notification);
    }

    private void updateNotification() {
        try {
            // 状态无变化时不重复刷新通知，减少后台唤醒与 IPC 开销。
            String text = NetHub.get().statusText();
            if (text.equals(lastNotificationText)) return;
            lastNotificationText = text;
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        mic = false;
        app.nekosakana.caps.VideoShell.get().stop();
        app.nekosakana.caps.AudioShare.get().stop();
        app.nekosakana.caps.MicShare.get().stop();
        // 关键：不释放 MediaProjection 令牌，否则服务被系统重启后就要重新授权。
        // 仅暂停发送/采集，画面可随时在重新共享时恢复。
        app.nekosakana.caps.VideoShare.get().setPaused(true);
        app.nekosakana.caps.ScreenShare.get().stopCapture();
        started = false;
        NetHub.get().stop();
        FtpServer.get().stop();
        FtpBridge.get().stop();
        PortForward.get().stop();
        Watchdog.schedule(this);
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (background != null) background.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
