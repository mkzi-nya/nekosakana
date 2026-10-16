package app.nekosakana.engine;

import android.content.Context;
import android.content.Intent;
import android.media.MediaFormat;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import app.nekosakana.App;
import app.nekosakana.caps.AccessService;
import app.nekosakana.caps.AppsX;
import app.nekosakana.caps.AudioShare;
import app.nekosakana.caps.Chat;
import app.nekosakana.caps.Control;
import app.nekosakana.caps.DeviceStateX;
import app.nekosakana.caps.InstallerX;
import app.nekosakana.caps.LocationX;
import app.nekosakana.caps.Media;
import app.nekosakana.caps.MicShare;
import app.nekosakana.caps.Overlay;
import app.nekosakana.caps.PermsX;
import app.nekosakana.caps.ScreenShare;
import app.nekosakana.caps.SensorsX;
import app.nekosakana.caps.ShellX;
import app.nekosakana.caps.UsageX;
import app.nekosakana.caps.VideoShare;
import app.nekosakana.caps.VideoShell;
import app.nekosakana.core.Config;
import app.nekosakana.core.ControlLog;
import app.nekosakana.core.CoreService;
import app.nekosakana.core.Downloads;
import app.nekosakana.core.Logx;
import app.nekosakana.core.Store;
import app.nekosakana.net.FrameStreamSender;
import app.nekosakana.net.NetHub;
import app.nekosakana.ui.LockActivity;
import app.nekosakana.ui.LockConfigValidator;

/**
 * 协议分发引擎。接收对方消息、在本机执行、并回传结果。
 * 消息类型与行为见 docs/02-协议与链路.md。
 */
public final class Engine implements NetHub.Peer {
    private static final Engine INSTANCE = new Engine();

    // 流式数据监听
    public interface ScreenListener {
        void onFrame(byte[] webp, int width, int height);
    }

    public interface VideoListener {
        void onConfig(String codec, int videoWidth, int videoHeight, int screenWidth, int screenHeight,
                      byte[] csd0, byte[] csd1);

        void onFrame(byte[] data, boolean key);
    }

    public interface AudioListener {
        void onAudio(byte[] adts);
    }

    public interface ChatListener {
        void onChat();
    }

    public interface StatusListener {
        void onStatus(String text);
    }

    private final Map<String, Holder> waiters = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService events =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    /**
     * 慢请求（截图、shell、应用列表、安装等）在独立线程串行执行：
     * 既不阻塞链路读线程（否则心跳/控制指令/回包都会排队导致超时），又保持请求之间的先后顺序。
     * 触摸等交互指令仍走读线程内联处理以保证实时与有序。
     */
    private final ExecutorService requestExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "engine-request");
        t.setDaemon(true);
        return t;
    });
    private final List<ScreenListener> screenListeners = new CopyOnWriteArrayList<>();
    private final List<VideoListener> videoListeners = new CopyOnWriteArrayList<>();
    private final List<AudioListener> audioListeners = new CopyOnWriteArrayList<>();
    private final List<ChatListener> chatListeners = new CopyOnWriteArrayList<>();
    private final List<StatusListener> statusListeners = new CopyOnWriteArrayList<>();

    public static final class Reply {
        public final boolean ok;
        public final JSONObject data;
        public final byte[] body;
        public final String message;

        Reply(boolean ok, JSONObject data, byte[] body, String message) {
            this.ok = ok;
            this.data = data;
            this.body = body;
            this.message = message == null ? "" : message;
        }
    }

    private Engine() {
        webpSender.start();
    }

    public static Engine get() {
        return INSTANCE;
    }

    public Context context() {
        return App.get();
    }

    // ---- 监听注册 ----

    public void addScreenListener(ScreenListener l) {
        if (!screenListeners.contains(l)) screenListeners.add(l);
    }

    public void removeScreenListener(ScreenListener l) {
        screenListeners.remove(l);
    }

    public void addVideoListener(VideoListener l) {
        if (!videoListeners.contains(l)) videoListeners.add(l);
    }

    public void removeVideoListener(VideoListener l) {
        videoListeners.remove(l);
    }

    public void addAudioListener(AudioListener l) {
        if (!audioListeners.contains(l)) audioListeners.add(l);
    }

    public void removeAudioListener(AudioListener l) {
        audioListeners.remove(l);
    }

    public void addChatListener(ChatListener l) {
        if (!chatListeners.contains(l)) chatListeners.add(l);
    }

    public void removeChatListener(ChatListener l) {
        chatListeners.remove(l);
    }

    public void addStatusListener(StatusListener l) {
        if (!statusListeners.contains(l)) statusListeners.add(l);
    }

    public void removeStatusListener(StatusListener l) {
        statusListeners.remove(l);
    }

    // ---- 发送 ----

    public void sendEvent(String type, JSONObject payload) {
        sendEvent(type, payload, null);
    }

    public void sendEvent(String type, JSONObject payload, byte[] body) {
        try {
            JSONObject h = payload == null ? new JSONObject() : payload;
            h.put("t", type);
            // Java sockets reject writes on the UI thread in LAN mode.
            events.execute(() -> {
                if (!NetHub.get().send(h, body)) {
                    Logx.w("engine", "发送失败 " + type);
                    notifyStatus("发送失败，请等待对方连接后重试");
                }
            });
        } catch (Exception ignored) {
        }
    }

    /** 发送请求并阻塞等待结果。需在工作线程调用。 */
    public Reply request(String type, JSONObject payload, byte[] body, long timeoutMs) {
        String id = UUID.randomUUID().toString();
        try {
            JSONObject h = payload == null ? new JSONObject() : payload;
            h.put("t", type);
            h.put("id", id);
            Holder holder = new Holder();
            holder.latch = new CountDownLatch(1);
            waiters.put(id, holder);
            if (!NetHub.get().send(h, body)) {
                waiters.remove(id);
                return new Reply(false, null, null, "对方未连接或发送失败");
            }
            if (!holder.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                waiters.remove(id);
                return new Reply(false, null, null, "超时");
            }
            return new Reply(holder.ok, holder.data, holder.body, holder.message);
        } catch (Exception e) {
            waiters.remove(id);
            return new Reply(false, null, null, String.valueOf(e));
        }
    }

    private static final class Holder {
        volatile boolean ok;
        volatile JSONObject data;
        volatile byte[] body;
        volatile String message = "";
        volatile CountDownLatch latch;
    }

    // ---- NetHub.Peer ----

    @Override
    public void onFrame(JSONObject header, byte[] body) {
        String type = header.optString("t", "");
        if ("result".equals(type)) {
            String re = header.optString("re", "");
            Holder h = waiters.remove(re);
            if (h != null) {
                h.ok = header.optBoolean("ok", false);
                h.data = header.optJSONObject("data");
                h.body = body;
                h.message = header.optString("msg", "");
                if (h.latch != null) h.latch.countDown();
            }
            return;
        }
        // 流式帧
        if ("screen.frame".equals(type)) {
            for (ScreenListener l : screenListeners) l.onFrame(body, header.optInt("w"), header.optInt("h"));
            return;
        }
        if ("screen.video".equals(type)) {
            if (header.optBoolean("cfg", false)) {
                String codec = header.optString("codec", "avc");
                byte[] csd0 = base64(header.optString("csd0", ""));
                byte[] csd1 = base64(header.optString("csd1", ""));
                int vw = header.optInt("w");
                int vh = header.optInt("h");
                int sw = header.optInt("pw", vw);
                int sh = header.optInt("ph", vh);
                for (VideoListener l : videoListeners) l.onConfig(codec, vw, vh, sw, sh, csd0, csd1);
            } else {
                boolean key = header.optBoolean("key", false);
                for (VideoListener l : videoListeners) l.onFrame(body, key);
            }
            return;
        }
        if ("audio.frame".equals(type)) {
            for (AudioListener l : audioListeners) l.onAudio(body);
            return;
        }
        if ("mic.frame".equals(type)) {
            for (AudioListener l : audioListeners) l.onAudio(body);
            return;
        }
        if ("lock.event".equals(type)) {
            ControlLog.add("in", "lock.event", header.toString());
            String action = header.optString("action");
            String text = "对方操作：" + header.toString();
            notifyStatus(text);
            if ("maxwrong".equals(action)) {
                try {
                    android.app.NotificationManager nm = (android.app.NotificationManager) context().getSystemService(Context.NOTIFICATION_SERVICE);
                    nm.createNotificationChannel(new android.app.NotificationChannel("lock-events", "锁定页提醒", android.app.NotificationManager.IMPORTANCE_HIGH));
                    Intent intent = new Intent(context(), app.nekosakana.ui.MainActivity.class).putExtra("page", "远程");
                    android.app.PendingIntent open = android.app.PendingIntent.getActivity(context(), 9, intent,
                            android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                    nm.notify(9, new android.app.Notification.Builder(context(), "lock-events")
                            .setSmallIcon(app.nekosakana.R.drawable.ic_stat).setContentTitle("对方回答次数已用尽")
                            .setContentText("仍未答对，可以修改问题、次数或解锁").setContentIntent(open).setAutoCancel(true).build());
                } catch (Exception e) { Logx.w("lock", "通知失败 " + e); }
            }
            return;
        }
        if ("notify".equals(type)) {
            notifyStatus(header.optString("msg", ""));
            return;
        }
        if (type.startsWith("tunnel.")) {
            app.nekosakana.net.PortForward.get().onFrame(type, header, body);
            return;
        }
        if (type.startsWith("ftpb.")) {
            app.nekosakana.net.FtpBridge.get().onFrame(type, header, body);
            return;
        }

        // 请求处理：慢请求挪到独立线程串行执行，避免阻塞链路读线程（心跳/控制/回包排队会导致超时）。
        if (isInteractiveRequest(type)) {
            sendResult(header, handle(type, header, body));
        } else {
            requestExecutor.execute(() -> sendResult(header, handle(type, header, body)));
        }
    }

    /** 交互类请求：耗时极短且要求即时、有序，直接在读线程内处理。 */
    private static boolean isInteractiveRequest(String type) {
        switch (type) {
            case "control":
            case "screen.key":
            case "screen.pause":
            case "screen.resume":
            case "audio.stop":
            case "mic.stop":
            case "lock.release":
                return true;
            default:
                return false;
        }
    }

    private void sendResult(JSONObject header, Reply reply) {
        if (reply == null || !header.has("id")) return;
        try {
            JSONObject res = new JSONObject();
            res.put("t", "result");
            res.put("re", header.optString("id"));
            res.put("ok", reply.ok);
            res.put("msg", reply.message);
            if (reply.data != null) res.put("data", reply.data);
            NetHub.get().send(res, reply.body);
        } catch (Exception e) {
            Logx.e("engine", "回传失败 " + e);
        }
    }

    @Override
    public void onStatus() {
        String text = NetHub.get().statusText();
        for (StatusListener l : statusListeners) l.onStatus(text);
    }

    private void notifyStatus(String text) {
        for (StatusListener l : statusListeners) l.onStatus(text);
    }

    // ---- 请求处理 ----

    private Reply handle(String type, JSONObject h, byte[] body) {
        ControlLog.add("in", type, h.optString("action", ""));
        Context c = context();
        try {
            switch (type) {
                case "chat.text": {
                    String text = h.optString("text", "");
                    Chat.add(false, text, null, "text");
                    Overlay.message("收到消息", text, null);
                    for (ChatListener l : chatListeners) l.onChat();
                    return ok();
                }
                case "chat.file": {
                    String name = h.optString("name", "file");
                    String kind = h.optString("kind", "file");
                    byte[] data = body == null ? new byte[0] : body;
                    String safe = Downloads.safeName(name);
                    java.io.File local = Store.get().file("media/" + System.currentTimeMillis() + "-" + safe);
                    Store.get().write("media/" + local.getName(), data);
                    java.io.File saved = Downloads.save(c, name, kind, data);
                    Cache.pendingFile = local;
                    Logx.i("chat", "收到文件 " + name + " " + data.length + "B"
                            + (saved != null ? " -> " + saved.getAbsolutePath() : "（保存到下载目录失败）"));
                    Chat.add(false, name, local.getAbsolutePath(), kind);
                    Overlay.message("收到文件", name + " (" + human(data.length) + ")"
                            + (saved != null ? "，已存到下载目录" : ""), null);
                    for (ChatListener l : chatListeners) l.onChat();
                    return ok();
                }
                case "screen.shot": {
                    if (!ShellX.hasPrivilege() && (!AccessService.isConnected() || !ScreenShare.get().hasProjection())) {
                        Reply consent = startScreen(pendingWidth, pendingFps, pendingQuality, false);
                        return fail(consent.ok ? "屏幕共享正在启动，请稍后重试截图" : consent.message);
                    }
                    final CountDownLatch latch = new CountDownLatch(1);
                    final byte[][] out = new byte[1][];
                    final int[] dim = new int[2];
                    ScreenShare.screenshot(c, (webp, w, hh) -> {
                        out[0] = webp;
                        dim[0] = w;
                        dim[1] = hh;
                        latch.countDown();
                    });
                    latch.await(6, TimeUnit.SECONDS);
                    if (out[0] == null) {
                        Reply consent = startScreen(pendingWidth, pendingFps, pendingQuality, false);
                        return fail(consent.ok ? "屏幕共享正在启动，请稍后重试截图" : consent.message);
                    }
                    JSONObject meta = new JSONObject();
                    meta.put("w", dim[0]);
                    meta.put("h", dim[1]);
                    return new Reply(true, meta, out[0], "ok");
                }
                case "screen.start": {
                    boolean withAudio = h.optBoolean("audio", false);
                    pendingVideo = h.optBoolean("video", true);
                    pendingCodec = h.optString("codec", "hevc");
                    pendingBitrate = h.optInt("bitrate", 4_000_000);
                    return startScreen(resolveWidth(h), h.optInt("fps", 30), h.optInt("quality", 60), withAudio,
                            h.optBoolean("projection", false));
                }
                case "screen.key": {
                    if (VideoShell.isRunning()) VideoShell.get().requestKeyFrame();
                    else VideoShare.get().requestKeyFrame();
                    return ok();
                }
                case "screen.pause": {
                    if (VideoShell.isRunning()) VideoShell.get().setPaused(true);
                    if (VideoShare.isRunning()) VideoShare.get().setPaused(true);
                    ScreenShare.get().setPaused(true);
                    return ok();
                }
                case "screen.resume": {
                    if (VideoShell.isRunning()) {
                        VideoShell.get().setPaused(false);
                        VideoShell.get().requestKeyFrame();
                    }
                    if (VideoShare.isRunning()) {
                        VideoShare.get().setPaused(false);
                        VideoShare.get().emitConfig();
                        VideoShare.get().requestKeyFrame();
                    }
                    ScreenShare.get().setPaused(false);
                    return ok();
                }
                case "screen.stop": {
                    screenRequested = false;
                    authorizationRequested = false;
                    clearScreenConsentNotification();
                    Control.closeTouch();
                    VideoShell.get().stop();
                    // Android 14 不允许用同一令牌重建采集：保留会话仅暂停发送，下次共享免二次授权。
                    if (VideoShare.isRunning()) VideoShare.get().setPaused(true);
                    else ScreenShare.get().stopCapture();
                    // 停止画面共享不再连带停止「收听音频 / 收听语音」：二者由各自开关独立控制。
                    webpSender.clear();
                    return ok();
                }
                case "audio.start": {
                    return startAudio(h.optInt("rate", 44100), h.optInt("channels", 2), h.optInt("bitrate", 96000));
                }
                case "audio.stop": {
                    AudioShare.get().stop();
                    return ok();
                }
                case "mic.start": {
                    return startMic(h.optInt("rate", 44100), h.optInt("channels", 1), h.optInt("bitrate", 64000));
                }
                case "mic.stop": {
                    MicShare.get().stop();
                    CoreService.setMicActive(false);
                    return ok();
                }
                case "control": {
                    String action = h.optString("action");
                    boolean result;
                    switch (action) {
                        case "touch":
                            result = Control.touch(h.optInt("phase", -1), (float) h.optDouble("x"),
                                    (float) h.optDouble("y"), h.optLong("duration", -1));
                            break;
                        case "tap":
                            result = Control.tap((float) h.optDouble("x"), (float) h.optDouble("y"));
                            break;
                        case "swipe":
                            result = Control.swipe((float) h.optDouble("x1"), (float) h.optDouble("y1"),
                                    (float) h.optDouble("x2"), (float) h.optDouble("y2"), h.optLong("duration", 200));
                            break;
                        case "longpress":
                            result = Control.longPress((float) h.optDouble("x"), (float) h.optDouble("y"), h.optLong("duration", 700));
                            break;
                        case "text":
                            result = Control.text(h.optString("text"));
                            break;
                        case "key":
                            result = Control.key(h.optString("key"));
                            break;
                        default:
                            result = false;
                    }
                    return result ? ok() : fail(Control.error());
                }
                case "apps.list":
                    return data(AppsX.list(c, h.optBoolean("system", false)));
                case "apps.frozen":
                    return data(AppsX.frozen(c));
                case "apps.freeze":
                    return ok(AppsX.freeze(c, h.optString("pkg")));
                case "apps.unfreeze":
                    return ok(AppsX.unfreeze(c, h.optString("pkg")));
                case "apps.launch":
                    return AppsX.launch(c, h.optString("pkg")) ? ok() : fail("无法启动");
                case "apps.forceStop":
                    return ok(AppsX.forceStop(h.optString("pkg")));
                case "apps.uninstall":
                    return ok(AppsX.uninstall(c, h.optString("pkg")));
                case "usage.current": {
                    JSONObject o = new JSONObject();
                    String pkg = UsageX.currentApp(c);
                    o.put("pkg", pkg);
                    o.put("label", pkg == null || pkg.isEmpty() ? "" : UsageX.label(c, pkg));
                    return data(o);
                }
                case "usage.stats": {
                    long dayStart = todayStart();
                    long weekStart = Math.min(dayStart, System.currentTimeMillis() - 7L * 24 * 3600_000L);
                    JSONObject o = new JSONObject();
                    o.put("today", UsageX.stats(c, dayStart));
                    o.put("week", UsageX.stats(c, weekStart));
                    return data(o);
                }
                case "usage.events":
                    return data(UsageX.events(c, System.currentTimeMillis() - h.optLong("since", 6 * 3600_000L), h.optInt("limit", 200)));
                case "devstate.get":
                    return data(DeviceStateX.snapshot(c));
                case "devstate.set":
                    return ok(DeviceStateX.set(c, h.optString("key"), h.optString("value")));
                case "sensor.get":
                    return data(SensorsX.snapshot(c));
                case "notif.list": {
                    int offset = Math.max(0, h.optInt("offset", 0));
                    int count = Math.max(1, Math.min(50, h.optInt("count", 10)));
                    JSONObject o = new JSONObject();
                    o.put("list", app.nekosakana.caps.NotifHub.page(offset, count));
                    o.put("total", app.nekosakana.caps.NotifHub.size());
                    return data(o);
                }
                case "location.get":
                    return data(LocationX.current(c));
                case "location.report": {
                    JSONObject o = LocationX.report(c);
                    o.put("orientation", SensorsX.orientation(c));
                    return data(o);
                }
                case "location.acquire": {
                    JSONObject o = LocationX.acquire(c, h.optLong("timeout", 12000));
                    o.put("orientation", SensorsX.orientation(c));
                    return data(o);
                }
                case "location.track":
                    return data(LocationX.track(h.optInt("limit", 500)));
                case "install": {
                    String name = h.optString("name", "remote.apk");
                    return ok(InstallerX.installBytes(c, body == null ? new byte[0] : body, name));
                }
                case "shell.exec": {
                    ShellX.Result r = ShellX.exec(h.optLong("timeout", 15000), h.optString("cmd"));
                    JSONObject o = new JSONObject();
                    o.put("code", r.code);
                    o.put("backend", ShellX.name(r.backend));
                    o.put("out", r.out);
                    o.put("err", r.err);
                    return data(o);
                }
                case "lock.config": {
                    JSONObject config = h.optJSONObject("config");
                    if (config == null) config = h;
                    String error = LockConfigValidator.error(config);
                    if (error != null) return fail(error);
                    Config.putString(Config.K_LOCK_CONFIG, config.toString());
                    LockActivity.applyConfig();
                    return ok();
                }
                case "lock.engage": {
                    JSONObject config = h.optJSONObject("config");
                    if (config == null) {
                        try {
                            config = new JSONObject(Config.getString(Config.K_LOCK_CONFIG, "{}"));
                        } catch (Exception ignored) {
                            config = new JSONObject();
                        }
                    }
                    String error = LockConfigValidator.error(config);
                    if (error != null) return fail(error);
                    Config.putString(Config.K_LOCK_CONFIG, config.toString());
                    LockActivity.applyConfig();
                    Intent i = new Intent(c, LockActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    c.startActivity(i);
                    return ok();
                }
                case "lock.release": {
                    LockActivity.releaseAll();
                    return ok();
                }
                case "control.log": {
                    JSONObject o = new JSONObject();
                    o.put("text", ControlLog.asText(h.optInt("limit", 500)));
                    return data(o);
                }
                case "log.get": {
                    JSONObject o = new JSONObject();
                    o.put("text", Logx.dump(h.optInt("limit", 400)));
                    return data(o);
                }
                case "perm.status": {
                    JSONArray arr = new JSONArray();
                    for (PermsX.Perm p : PermsX.collect(c)) {
                        JSONObject o = new JSONObject();
                        o.put("id", p.id);
                        o.put("title", p.title);
                        o.put("granted", p.granted);
                        arr.put(o);
                    }
                    return data(arr);
                }
                default:
                    return fail("未知的操作类型: " + type);
            }
        } catch (Exception e) {
            Logx.e("engine", "处理 " + type + " 失败 " + e);
            return fail(String.valueOf(e));
        }
    }

    private volatile int pendingWidth = 960;
    private volatile int pendingFps = 30;
    private volatile int pendingQuality = 60;
    private volatile int pendingBitrate = 4_000_000;
    private volatile String pendingCodec = "hevc";
    private volatile boolean pendingVideo = true;
    private volatile boolean pendingAudio = false;
    private volatile boolean screenRequested;
    private volatile boolean authorizationRequested;

    // 实时帧发送器：采集与网络发送解耦，网络不畅时丢弃旧帧只发最新（视频在关键帧处重新同步）。
    private final FrameStreamSender webpSender = new FrameStreamSender(
            (data, key, meta) -> {
                try {
                    int[] d = (int[]) meta;
                    JSONObject header = new JSONObject();
                    header.put("t", "screen.frame");
                    header.put("w", d[0]);
                    header.put("h", d[1]);
                    return NetHub.get().send(header, data);
                } catch (Throwable t) {
                    return false;
                }
            }, false, 1, null);

    public boolean screenAuthorizationPending() { return screenRequested && authorizationRequested; }
    public void screenAuthorizationOpened() { clearScreenConsentNotification(); }

    public void cancelScreenAuthorization() {
        screenRequested = false;
        authorizationRequested = false;
        clearScreenConsentNotification();
        JSONObject status = new JSONObject();
        try { status.put("msg", "对方取消了远程协助授权"); } catch (Exception ignored) { }
        sendEvent("notify", status);
    }

    private synchronized Reply requestScreenAuthorization() {
        if (!screenRequested) return fail("屏幕共享请求已停止");
        if (!authorizationRequested) {
            authorizationRequested = true;
            try {
                Intent intent = new Intent(context(), app.nekosakana.ui.MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                intent.putExtra("action", "request_remote_access");
                intent.putExtra("need_accessibility", !ShellX.hasPrivilege());
                android.app.NotificationManager nm = (android.app.NotificationManager) context().getSystemService(Context.NOTIFICATION_SERVICE);
                nm.createNotificationChannel(new android.app.NotificationChannel("remote-consent", "远程协助授权", android.app.NotificationManager.IMPORTANCE_HIGH));
                android.app.PendingIntent open = android.app.PendingIntent.getActivity(context(), 10, intent,
                        android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                nm.notify(10, new android.app.Notification.Builder(context(), "remote-consent")
                        .setSmallIcon(app.nekosakana.R.drawable.ic_stat).setContentTitle("对方请求远程协助")
                        .setContentText("点击完成无障碍和屏幕共享授权").setContentIntent(open).setAutoCancel(true).build());
                context().startActivity(intent);
            } catch (Exception e) {
                authorizationRequested = false;
                return fail("请在被控设备打开应用完成远程授权：" + e);
            }
        }
        String message = "请对方在设备上授权无障碍和屏幕共享";
        if (ShellX.hasPrivilege()) message = "请对方在设备上确认屏幕共享：" + ScreenShare.get().error();
        JSONObject status = new JSONObject();
        try { status.put("msg", message); } catch (Exception ignored) { }
        sendEvent("notify", status);
        return fail(message);
    }

    private void clearScreenConsentNotification() {
        ((android.app.NotificationManager) context().getSystemService(Context.NOTIFICATION_SERVICE)).cancel(10);
    }

    private Reply startScreen(int targetWidth, int fps, int quality, boolean withAudio) {
        return startScreen(targetWidth, fps, quality, withAudio, false);
    }

    private Reply startScreen(int targetWidth, int fps, int quality, boolean withAudio, boolean forceProjection) {
        pendingWidth = targetWidth;
        pendingFps = fps;
        pendingQuality = quality;
        pendingAudio = withAudio;
        screenRequested = true;
        if (forceProjection && !ScreenShare.get().hasProjection()) ScreenShare.get().stop();
        // 默认：有 Shizuku/ADB 权限就直接 ADB 直采（screenrecord），免去系统投屏授权弹窗。
        // 仅当用户显式开启「系统投屏」时才走 MediaProjection（更流畅但每次都要确认）。
        if (pendingVideo && !forceProjection && ShellX.hasPrivilege() && !Config.screenProjection()) {
            return startShellVideo();
        }
        // 已授权并在推流：无论无障碍状态如何都直接复用，避免二次授权。
        if (VideoShare.isRunning() || VideoShell.isRunning() || ScreenShare.isRunning()
                || ScreenShare.get().hasProjection()) {
            return beginScreen();
        }
        if (!ShellX.hasPrivilege() && !AccessService.isConnected()) return requestScreenAuthorization();
        // 无权限直采时的兼容截图路径。
        if (!pendingVideo && !forceProjection && !withAudio && ShellX.hasPrivilege()
                && ScreenShare.get().startDirect(context(), targetWidth, fps, quality, this::sendScreenFrame,
                    this::requestScreenAuthorization)) {
            authorizationRequested = false;
            clearScreenConsentNotification();
            return ok();
        }
        if (!ScreenShare.get().hasProjection()) {
            return requestScreenAuthorization();
        }
        return beginScreen();
    }

    private Reply startShellVideo() {
        if (VideoShell.isRunning()) {
            VideoShell.get().updateSink(videoSink);
            VideoShell.get().requestKeyFrame();
            authorizationRequested = false;
            clearScreenConsentNotification();
            return ok();
        }
        // 切换到 adb 直采：暂停可能仍在运行的系统投影采集，避免同时推两路画面。
        if (VideoShare.isRunning()) VideoShare.get().setPaused(true);
        if (ScreenShare.isRunning()) ScreenShare.get().setPaused(true);
        if (VideoShell.get().start(context(), pendingWidth, pendingFps, pendingBitrate, videoSink)) {
            // screenrecord 首次产出 SPS/PPS 需要一点时间，给足窗口再判定成败。
            // 此方法运行在独立请求线程，不会阻塞链路读线程。
            long deadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < deadline && VideoShell.isRunning() && !VideoShell.isStarted()) {
                try {
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (VideoShell.isStarted()) {
                authorizationRequested = false;
                clearScreenConsentNotification();
                if (pendingAudio) startAudio(44100, 2, 96000);
                return ok();
            }
            Logx.w("engine", "adb screenrecord 未产出码流，改用 adb 截图流");
            VideoShell.get().stop();
        }
        // 有 Root/ADB 权限时不再弹系统屏幕录制授权：改用 screencap 截图流（免授权直采）。
        if (ShellX.hasPrivilege() && startDirectStream()) {
            authorizationRequested = false;
            clearScreenConsentNotification();
            return ok();
        }
        if (!ScreenShare.get().hasProjection()) return requestScreenAuthorization();
        return beginScreen();
    }

    /** 免系统授权的回退：直接用 adb {@code screencap} 轮询出图（WebP）。 */
    private boolean startDirectStream() {
        return ScreenShare.get().startDirect(context(), pendingWidth, pendingFps, pendingQuality,
                this::sendScreenFrame, () -> {
                    if (!ScreenShare.get().hasProjection()) requestScreenAuthorization();
                });
    }

    /** 分辨率比例（scale=1 为本机满屏宽度）；未提供 scale 时兼容旧的绝对宽度 {@code w}。 */
    private int resolveWidth(JSONObject h) {
        double scale = h.optDouble("scale", 0);
        if (scale > 0) {
            return (int) Math.round(realScreenWidth() * Math.max(0.15, Math.min(1.0, scale)));
        }
        return h.optInt("w", 960);
    }

    private int realScreenWidth() {
        try {
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            android.view.WindowManager wm = (android.view.WindowManager) context().getSystemService(Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(metrics);
            return metrics.widthPixels > 0 ? metrics.widthPixels : 1080;
        } catch (Throwable t) {
            return 1080;
        }
    }

    /** 本机时区当天 0 点的时间戳（「今天」使用情况的起点）。 */
    private static long todayStart() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private Reply beginScreen() {
        if (pendingVideo) {
            if (VideoShare.isRunning()) {
                // 已授权：断线重连或停止后再次共享都直接复用，不重新申请授权。
                VideoShare.get().setPaused(false);
                VideoShare.get().updateSink(videoSink);
                VideoShare.get().emitConfig();
                VideoShare.get().requestKeyFrame();
                authorizationRequested = false;
                clearScreenConsentNotification();
                if (pendingAudio) startAudio(44100, 2, 96000);
                return ok();
            }
            String preferred = "avc".equals(pendingCodec)
                    ? MediaFormat.MIMETYPE_VIDEO_AVC : MediaFormat.MIMETYPE_VIDEO_HEVC;
            if (VideoShare.get().start(context(), ScreenShare.get().projection(), pendingWidth, pendingFps,
                    pendingBitrate, preferred, videoSink)) {
                authorizationRequested = false;
                clearScreenConsentNotification();
                if (pendingAudio) startAudio(44100, 2, 96000);
                return ok();
            }
            Logx.w("engine", "硬件视频不可用，回退 WebP 画面");
        }
        if (ScreenShare.isRunning()) {
            // WebP 模式重连：画面继续，无需重新授权。
            authorizationRequested = false;
            clearScreenConsentNotification();
            if (pendingAudio) startAudio(44100, 2, 96000);
            return ok();
        }
        boolean ok = ScreenShare.get().startWithProjection(context(), pendingWidth, pendingFps, pendingQuality,
                this::sendScreenFrame);
        if (ok) {
            authorizationRequested = false;
            clearScreenConsentNotification();
            if (pendingAudio) startAudio(44100, 2, 96000);
            return ok();
        }
        // 旧的录制令牌已失效：释放后重新申请授权。
        Logx.w("engine", "屏幕采集启动失败，重新申请授权：" + ScreenShare.get().error());
        ScreenShare.get().stop();
        return requestScreenAuthorization();
    }

    public void resumeAuthorizedScreen() {
        if (!screenRequested || !Control.available()) return;
        authorizationRequested = false;
        clearScreenConsentNotification();
        beginScreen();
    }

    private void sendScreenFrame(byte[] webp, int w, int h) {
        webpSender.offer(webp, false, new int[]{w, h});
    }

    private final VideoShare.Sink videoSink = new VideoShare.Sink() {
        @Override
        public void onConfig(String mime, int vw, int vh, int sw, int sh, byte[] csd0, byte[] csd1) {
            try {
                JSONObject head = new JSONObject();
                head.put("t", "screen.video");
                head.put("cfg", true);
                head.put("codec", MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime) ? "hevc" : "avc");
                head.put("w", vw);
                head.put("h", vh);
                head.put("pw", sw);
                head.put("ph", sh);
                if (csd0 != null) head.put("csd0", Base64.encodeToString(csd0, Base64.NO_WRAP));
                if (csd1 != null) head.put("csd1", Base64.encodeToString(csd1, Base64.NO_WRAP));
                NetHub.get().send(head, null);
                Logx.i("video", "编码配置 " + vw + "x" + vh + " csd=" + (csd0 == null ? 0 : csd0.length));
            } catch (Exception e) {
                Logx.w("video", "发送配置失败 " + e);
            }
        }

        @Override
        public void onFrame(byte[] data, boolean key, long ptsUs) {
            // 视频走阻塞发送形成「背压」：网络慢时编码器自然降帧而不是丢帧后反复重启关键帧，
            // 避免画面花屏/卡顿跳变，整体更流畅。
            try {
                JSONObject head = new JSONObject();
                head.put("t", "screen.video");
                head.put("key", key);
                head.put("ts", ptsUs);
                NetHub.get().send(head, data);
            } catch (Exception e) {
                Logx.w("video", "发送帧失败 " + e);
            }
        }
    };

    /** 由 MainActivity 在取得屏幕录制授权后调用。 */
    public void provideProjection(Context context, int resultCode, Intent data) {
        if (!screenRequested) { CoreService.endProjection(); return; }
        authorizationRequested = false;
        clearScreenConsentNotification();
        if (ScreenShare.get().prepare(context, resultCode, data)) {
            ControlLog.add("sys", "screen", "屏幕录制已授权");
            Reply reply = beginScreen();
            JSONObject status = new JSONObject();
            try { status.put("msg", reply.ok ? "屏幕共享已开始" : reply.message); } catch (Exception ignored) { }
            sendEvent("notify", status);
        } else {
            Logx.w("engine", "屏幕录制授权失败");
        }
    }

    private Reply startAudio(int rate, int channels, int bitrate) {
        if (!ScreenShare.get().hasProjection()) return startScreen(pendingWidth, pendingFps, pendingQuality, true);
        boolean ok = AudioShare.get().start(ScreenShare.get().projection(), adts -> {
            try {
                JSONObject header = new JSONObject();
                header.put("t", "audio.frame");
                NetHub.get().send(header, adts);
            } catch (Exception ignored) {
            }
        }, rate, channels, bitrate);
        return ok ? ok() : fail("音频采集启动失败");
    }

    private Reply startMic(int rate, int channels, int bitrate) {
        if (context().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestMicPermission();
            return fail("被控设备尚未授予麦克风权限，已发起授权请求");
        }
        // 关键：把前台服务升级为「麦克风」类型。否则应用在后台时系统会阻断麦克风采集，
        // AudioRecord 只能读到静音，表现为「一直听不到对方声音」。
        CoreService.setMicActive(true);
        boolean ok = MicShare.get().start(adts -> {
            try {
                JSONObject header = new JSONObject();
                header.put("t", "mic.frame");
                NetHub.get().send(header, adts);
            } catch (Exception ignored) {
            }
        }, rate, channels, bitrate);
        if (!ok) CoreService.setMicActive(false);
        return ok ? ok() : fail("麦克风采集启动失败");
    }

    private void requestMicPermission() {
        try {
            Intent intent = new Intent(context(), app.nekosakana.ui.MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("action", "request_mic");
            context().startActivity(intent);
        } catch (Exception e) {
            Logx.w("mic", "无法拉起麦克风授权 " + e);
        }
    }

    // ---- 本地发起（供 UI 调用） ----

    public void sendChat(String text) {
        Chat.add(true, text, null, "text");
        JSONObject h = new JSONObject();
        try {
            h.put("text", text);
        } catch (Exception ignored) {
        }
        sendEvent("chat.text", h);
    }

    public void sendFile(String name, String kind, byte[] data) {
        Logx.i("chat", "发送文件 " + name + " " + (data == null ? 0 : data.length) + "B");
        String path = null;
        if (data != null && data.length > 0) {
            try {
                String fileName = System.currentTimeMillis() + "-" + Downloads.safeName(name);
                if (Store.get().write("media/" + fileName, data)) {
                    path = Store.get().file("media/" + fileName).getAbsolutePath();
                }
            } catch (Throwable t) {
                Logx.w("chat", "保存待发文件失败 " + t);
            }
        }
        Chat.add(true, name, path, kind);
        JSONObject h = new JSONObject();
        try {
            h.put("name", name);
            h.put("kind", kind);
        } catch (Exception ignored) {
        }
        sendEvent("chat.file", h, data);
    }

    private static Reply ok() {
        return new Reply(true, null, null, "ok");
    }

    private static Reply ok(String message) {
        return new Reply(true, null, null, message == null ? "ok" : message);
    }

    private static Reply data(JSONObject data) {
        return new Reply(true, data, null, "ok");
    }

    private static Reply data(JSONArray data) {
        JSONObject o = new JSONObject();
        try {
            o.put("list", data);
        } catch (Exception ignored) {
        }
        return new Reply(true, o, null, "ok");
    }

    private static Reply fail(String message) {
        return new Reply(false, null, null, message);
    }

    private static byte[] base64(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Base64.decode(value, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    /** 简易的临时缓存，避免额外状态类。 */
    static final class Cache {
        static volatile java.io.File pendingFile;
    }
}
