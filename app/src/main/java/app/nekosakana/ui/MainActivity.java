package app.nekosakana.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaFormat;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.Toast;
import android.text.InputType;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.nekosakana.caps.AudioBar;
import app.nekosakana.caps.AudioPlayer;
import app.nekosakana.caps.Chat;
import app.nekosakana.caps.Media;
import app.nekosakana.caps.PermsX;
import app.nekosakana.caps.ShellX;
import app.nekosakana.caps.VideoPlay;
import app.nekosakana.core.Config;
import app.nekosakana.core.ControlLog;
import app.nekosakana.core.CoreService;
import app.nekosakana.core.Logx;
import app.nekosakana.core.Store;
import app.nekosakana.engine.Engine;
import app.nekosakana.net.NetHub;
import app.nekosakana.net.NetInfo;
import app.nekosakana.net.TailscaleController;
import rikka.shizuku.Shizuku;

/** 主界面：主页 / 远程 / 会话 / 应用 / 设备 / 授权 / 日志 / 设置。 */
public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 101;
    private static final int REQ_PERM = 100;
    private static final int REQ_FILE_ANY = 102;
    private static final int REQ_FILE_MEDIA = 103;
    private static final int REQ_FILE_APK = 104;
    private static final int REQ_SHIZUKU = 105;

    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final ExecutorService touchIo = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler ticker = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private LinearLayout content;
    private String page = "主页";
    private final Deque<String> pageHistory = new ArrayDeque<>();
    private int generation = 0;

    // 主页
    private TextView latencyView;
    private TextView statusView;
    private TextView tailAddressView;
    private TextView addressLabelView;
    private TextView ftpStatusView;
    private String lastLatency = "延迟 -- ms";

    // 远程
    private ImageView screenView;
    private ImageView zoomScreenView;
    private FrameLayout screenFrame;
    private TextureView videoView;
    private Surface videoSurface;
    private SurfaceTexture videoTexture;
    private VideoPlay videoPlay;
    private Button remoteZoomButton;
    private Dialog zoomDialog;
    private TextView remoteStatus;
    private TextView remoteEmpty;
    private TextView lockEventView;
    private int remoteW = 1080;
    private int remoteH = 1920;
    private Bitmap lastFrame;
    private volatile boolean viewing;
    private volatile boolean videoActive;
    private boolean videoConfigured;
    private boolean videoFallbackTried;
    private String videoCodec = "";
    private int videoW, videoH;
    private byte[] videoCsd0, videoCsd1;
    private ScrollView chatScroll;
    private LinearLayout chatList;
    private EditText chatInput;
    private AudioBar chatAudio;
    private static volatile boolean chatVisible;
    private boolean watchOnly;
    private boolean micListening;
    private boolean remotePermissionFlow;
    private boolean needsAccessibility;
    private boolean captureRequested;
    private boolean activityResumed;
    private boolean waitingForAccessibilitySettings;
    private AlertDialog remotePermissionDialog;
    public static boolean chatVisible() { return chatVisible; }

    private final Engine.ScreenListener screenListener = (webp, w, h) -> ui.post(() -> {
        if (w > 0) remoteW = w;
        if (h > 0) remoteH = h;
        byte[] data = webp;
        io.execute(() -> {
            Bitmap bmp = Media.decode(data);
            ui.post(() -> {
                if (bmp != null && viewing) {
                    if (lastFrame != null) lastFrame.recycle();
                    lastFrame = bmp;
                    if (screenView != null) screenView.setImageBitmap(bmp);
                    if (zoomScreenView != null) zoomScreenView.setImageBitmap(bmp);
                    if (remoteEmpty != null) remoteEmpty.setVisibility(View.GONE);
                    if (remoteZoomButton != null) remoteZoomButton.setEnabled(true);
                    if (remoteStatus != null) remoteStatus.setText("画面 " + remoteW + "×" + remoteH);
                } else if (bmp != null) {
                    bmp.recycle();
                }
            });
        });
    });

    private final Engine.VideoListener videoListener = new Engine.VideoListener() {
        @Override
        public void onConfig(String codec, int vw, int vh, int sw, int sh, byte[] csd0, byte[] csd1) {
            ui.post(() -> {
                videoCodec = codec;
                videoW = vw;
                videoH = vh;
                videoCsd0 = csd0;
                videoCsd1 = csd1;
                if (sw > 0) remoteW = sw;
                if (sh > 0) remoteH = sh;
                videoActive = true;
                videoConfigured = true;
                videoFallbackTried = false;
                if (remoteEmpty != null) remoteEmpty.setVisibility(View.GONE);
                if (remoteZoomButton != null) remoteZoomButton.setEnabled(true);
                if (remoteStatus != null) {
                    remoteStatus.setText("画面 " + vw + "×" + vh + " · " + codec.toUpperCase());
                }
                if (screenView != null) screenView.setVisibility(View.GONE);
                if (videoView != null) videoView.setVisibility(View.VISIBLE);
                applyScreenAspect();
                startVideoPlay();
            });
        }

        @Override
        public void onFrame(byte[] data, boolean key) {
            VideoPlay play = videoPlay;
            if (play != null) play.feed(data, key);
        }
    };

    private final TextureView.SurfaceTextureListener videoSurfaceListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            releaseVideoSurface();
            videoTexture = st;
            videoSurface = new Surface(st);
            if (videoConfigured) startVideoPlay();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            if (st == videoTexture) {
                releaseVideoPlay();
                releaseVideoSurface();
                videoTexture = null;
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
        }
    };

    private final Engine.AudioListener audioListener = adts -> AudioPlayer.get().feed(adts);

    private final Engine.StatusListener statusListener = text -> ui.post(() -> {
        if (statusView != null) statusView.setText(text);
        if (text.startsWith("对方操作：") && lockEventView != null) {
            lockEventView.setText(formatLockEvent(text.substring("对方操作：".length())));
        }
        if (viewing && remoteStatus != null && (text.startsWith("请对方") || text.startsWith("屏幕共享")
                || text.startsWith("对方取消"))) remoteStatus.setText(text);
    });

    private final Engine.ChatListener chatListener = () -> ui.post(() -> {
        if ("会话".equals(page)) refreshChat(false);
    });

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateLatency();
            if (latencyView != null) latencyView.setText(lastLatency);
            if (statusView != null) statusView.setText(NetHub.get().statusText());
            if (ftpStatusView != null) {
                String ftp = app.nekosakana.net.FtpBridge.get().address();
                ftpStatusView.setText(ftp.isEmpty() ? "FTP · 等待网络就绪" : "对方文件 · ftp://" + ftp);
            }
            if (tailAddressView != null) {
                boolean tailnet = Config.tailnetMode();
                String address = tailnet ? Config.selfAddress() : NetInfo.localAddress();
                if (addressLabelView != null) addressLabelView.setText(tailnet ? "本机 TAILSCALE 地址" : "本机系统网络地址");
                tailAddressView.setText(address.isEmpty() ? (tailnet ? "未连接 Tailscale" : "未获取到系统网络地址") : address);
                tailAddressView.setTextColor(address.isEmpty() ? Ui.MUTED : Ui.TEXT);
            }
            ticker.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        }
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        CoreService.start(this);
        watchOnly = Config.getBool("remote_watch_only", false);
        Engine.get().addScreenListener(screenListener);
        Engine.get().addVideoListener(videoListener);
        Engine.get().addAudioListener(audioListener);
        Engine.get().addStatusListener(statusListener);
        Engine.get().addChatListener(chatListener);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String wanted = intent.getStringExtra("page");
        if (wanted != null) page = wanted;
        String action = intent.getStringExtra("action");
        if ("request_projection".equals(action) || "request_remote_access".equals(action)) {
            remotePermissionFlow = true;
            needsAccessibility = intent.getBooleanExtra("need_accessibility", false);
        }
        render();
        if (remotePermissionFlow) ui.post(this::continueRemoteAuthorization);
        if ("request_mic".equals(action)) ui.postDelayed(this::requestMicPermission, 400);
    }

    private void requestMicPermission() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERM);
        }
    }

    private void continueRemoteAuthorization() {
        if (!remotePermissionFlow || captureRequested || !activityResumed || waitingForAccessibilitySettings || isFinishing()) return;
        if (!Engine.get().screenAuthorizationPending()) {
            remotePermissionFlow = false;
            if (remotePermissionDialog != null) remotePermissionDialog.dismiss();
            return;
        }
        Engine.get().screenAuthorizationOpened();
        if (needsAccessibility && !app.nekosakana.caps.AccessService.isConnected()) {
            if (remotePermissionDialog != null && remotePermissionDialog.isShowing()) return;
            remotePermissionDialog = new AlertDialog.Builder(this)
                    .setTitle("远程协助授权")
                    .setMessage("对方请求远程协助。请先开启「nekosakana 辅助控制」无障碍服务，用于点击、滑动和输入；返回后再允许屏幕共享。")
                    .setPositiveButton("开启无障碍", (dialog, which) -> {
                        if (Engine.get().screenAuthorizationPending()) {
                            waitingForAccessibilitySettings = true;
                            startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } else remotePermissionFlow = false;
                    })
                    .setNegativeButton("取消", (dialog, which) -> cancelRemoteAuthorization())
                    .setOnCancelListener(dialog -> cancelRemoteAuthorization()).show();
            return;
        }
        if (remotePermissionDialog != null) remotePermissionDialog.dismiss();
        if (app.nekosakana.caps.ScreenShare.get().hasProjection()) {
            remotePermissionFlow = false;
            io.execute(() -> Engine.get().resumeAuthorizedScreen());
            return;
        }
        captureRequested = true;
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent capture = Build.VERSION.SDK_INT >= 34
                ? manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
        startActivityForResult(capture, REQ_CAPTURE);
    }

    private void cancelRemoteAuthorization() {
        remotePermissionFlow = false;
        captureRequested = false;
        Engine.get().cancelScreenAuthorization();
    }

    // ---- 框架 ----

    private void shell(String title, String subtitle) {
        generation++;
        if (chatAudio != null) { chatAudio.stop(); chatAudio = null; }
        chatVisible = "会话".equals(page) && hasWindowFocus();
        boolean wasViewing = viewing;
        viewing = "远程".equals(page);
        if (videoActive && viewing != wasViewing) {
            // 离开远程页时暂停推流以省流量，返回时恢复并请求关键帧。
            Engine.get().sendEvent(viewing ? "screen.resume" : "screen.pause", new JSONObject());
        }
        if (!viewing && zoomDialog != null) zoomDialog.dismiss();
        if (!viewing) {
            screenView = null;
            remoteZoomButton = null;
            videoView = null;
            remoteStatus = null;
            remoteEmpty = null;
        }
        root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        // 适配边到边显示：为状态栏与系统导航栏预留安全区，
        // 否则三键导航手机的底部目录栏会被系统导航栏遮住。
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = 0, bottom = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();

        LinearLayout header = Ui.column(this);
        header.setPadding(Ui.dp(this, 18), Ui.dp(this, 13), Ui.dp(this, 18), Ui.dp(this, 11));
        TextView brand = Ui.text(this, "NEKOSAKANA", 10, Ui.ACCENT, true);
        TextView heading = Ui.text(this, title, 19, Ui.TEXT, true);
        heading.setPadding(0, Ui.dp(this, 3), 0, 0);
        TextView sub = Ui.text(this, subtitle, 11, Ui.MUTED, false);
        header.addView(brand);
        header.addView(heading);
        if (subtitle != null && !subtitle.isEmpty()) {
            sub.setPadding(0, Ui.dp(this, 2), 0, 0);
            header.addView(sub);
        }
        root.addView(header);

        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        root.addView(line, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));

        ScrollView scroll = new ScrollView(this);
        content = Ui.column(this);
        content.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 22));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        root.addView(buildNav());
    }

    private View buildNav() {
        LinearLayout nav = Ui.row(this);
        nav.setBackgroundColor(Ui.SURFACE);
        nav.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 8));
        String[][] items = {{"主页", "主页"}, {"远程", "远程"}, {"会话", "会话"}, {"目录", "目录"}};
        for (String[] item : items) {
            boolean active;
            if ("目录".equals(item[0])) {
                active = !"主页".equals(page) && !"远程".equals(page) && !"会话".equals(page);
            } else {
                active = item[0].equals(page);
            }
            TextView tab = Ui.text(this, item[0], 13, active ? Ui.BG : Ui.MUTED, true);
            tab.setGravity(Gravity.CENTER);
            tab.setBackground(Ui.round(active ? Ui.ACCENT : Ui.SURFACE2, 8, this));
            tab.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            nav.addView(tab, lp);
            tab.setOnClickListener(v -> {
                openPage(item[1]);
            });
        }
        return nav;
    }

    private void showDirectory() {
        page = "目录";
        shell("目录", "全部功能");
        String[][] entries = {
                {"授权", "权限清单与请求"},
                {"应用", "使用情况 · 冻结 · 安装"},
                {"设备", "状态 · 控制 · 传感器 · 定位"},
                {"日志", "操控日志与运行日志"},
                {"设置", "连接与本地服务"},
        };
        for (String[] e : entries) {
            LinearLayout card = Ui.card(this, e[0], e[0], e[1], Ui.ACCENT);
            card.setOnClickListener(v -> {
                openPage(e[0]);
            });
            Ui.add(content, card, 0, 12);
        }
    }

    private void render() {
        switch (page) {
            case "远程":
                showRemote();
                break;
            case "会话":
                showChat();
                break;
            case "应用":
                showApps();
                break;
            case "设备":
                showDevice();
                break;
            case "授权":
                showPermissions();
                break;
            case "日志":
                showLogs();
                break;
            case "设置":
                showSettings();
                break;
            case "目录":
                showDirectory();
                break;
            default:
                showHome();
        }
    }

    private void openPage(String target) {
        if (target == null || target.equals(page)) return;
        pageHistory.push(page);
        page = target;
        render();
    }

    @Override
    public void onBackPressed() {
        if (!pageHistory.isEmpty()) {
            page = pageHistory.pop();
            render();
        } else if (!"主页".equals(page)) {
            page = "主页";
            render();
        } else {
            Toast.makeText(this, "已经在主页", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- 主页 ----

    private void showHome() {
        page = "主页";
        shell("连接", "DEVICE LINK");

        TextView title = Ui.text(this, "设备连接", 25, Ui.TEXT, true);
        Ui.add(content, title, 4, 2);
        TextView intro = Ui.text(this, "选择连接通道，然后输入对端地址。", 13, Ui.MUTED, false);
        Ui.add(content, intro, 0, 18);

        boolean tailnetMode = Config.tailnetMode();
        LinearLayout modeRow = Ui.row(this);
        Button tailnetModeButton = Ui.button(this, "Tailnet", tailnetMode ? Ui.ACCENT : Ui.SURFACE2);
        Button lanModeButton = Ui.button(this, "局域网 / 系统网络", tailnetMode ? Ui.SURFACE2 : Ui.ACCENT);
        if (!tailnetMode) tailnetModeButton.setTextColor(Ui.TEXT);
        if (tailnetMode) lanModeButton.setTextColor(Ui.TEXT);
        modeRow.addView(tailnetModeButton, Ui.lp(this, 0, 44, 0, 0, 4, 0));
        modeRow.addView(lanModeButton, Ui.lp(this, 0, 44, 4, 0, 0, 0));
        ((LinearLayout.LayoutParams) tailnetModeButton.getLayoutParams()).width = 0;
        ((LinearLayout.LayoutParams) tailnetModeButton.getLayoutParams()).weight = 1;
        ((LinearLayout.LayoutParams) lanModeButton.getLayoutParams()).width = 0;
        ((LinearLayout.LayoutParams) lanModeButton.getLayoutParams()).weight = 1;
        Ui.add(content, modeRow, 0, 14);
        View.OnClickListener selectMode = v -> {
            boolean useTailnet = v == tailnetModeButton;
            if (Config.tailnetMode() == useTailnet) return;
            Config.putString(Config.K_CONN_MODE, useTailnet ? "tailnet" : "lan");
            if (useTailnet && Config.getBool("tailscale_authenticated", false)) {
                TailscaleController.connect(this, null);
            }
            NetHub.get().stop();
            NetHub.get().start(this, Engine.get());
            app.nekosakana.net.FtpServer.get().stop();
            app.nekosakana.net.FtpServer.get().start(this);
            render();
        };
        tailnetModeButton.setOnClickListener(selectMode);
        lanModeButton.setOnClickListener(selectMode);

        LinearLayout addressPanel = Ui.column(this);
        addressPanel.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 17));
        addressPanel.setBackground(Ui.round(Ui.SURFACE, 8, this));
        addressLabelView = Ui.text(this, tailnetMode ? "本机 TAILSCALE 地址" : "本机系统网络地址", 11, Ui.ACCENT, true);
        addressPanel.addView(addressLabelView);
        tailAddressView = Ui.text(this, tailnetMode ? "未连接 Tailscale" : "未获取到系统网络地址", 21, Ui.MUTED, true);
        tailAddressView.setSingleLine(false);
        tailAddressView.setPadding(0, Ui.dp(this, 9), 0, 0);
        addressPanel.addView(tailAddressView);
        TextView addressHint = Ui.text(this, "连接成功后，对方可使用此地址访问本机。", 12, Ui.MUTED, false);
        addressHint.setPadding(0, Ui.dp(this, 7), 0, 0);
        addressPanel.addView(addressHint);
        Ui.add(content, addressPanel, 0, 12);

        if (tailnetMode) {
            LinearLayout keyPanel = Ui.column(this);
            keyPanel.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 15));
            keyPanel.setBackground(Ui.round(Ui.SURFACE, 8, this));
            TextView keyTitle = Ui.text(this, "Tailscale Auth Key", 15, Ui.TEXT, true);
            keyPanel.addView(keyTitle);
            boolean authenticated = Config.getBool("tailscale_authenticated", false);
            TextView keyHint = Ui.text(this, authenticated ? "已登录 · 启动时自动连接" : "Tailnet 模式需要此设备登录", 12, Ui.MUTED, false);
            keyHint.setPadding(0, Ui.dp(this, 4), 0, 0);
            keyPanel.addView(keyHint);
            EditText key = Ui.input(this, "tskey-auth-...");
            key.setSingleLine(true);
            key.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            key.setText(Config.getString(Config.K_TAIL_KEY, ""));
            key.setVisibility(authenticated ? View.GONE : View.VISIBLE);
            keyPanel.addView(key, Ui.lp(this, -1, 48, 0, 11, 0, 0));
            Button connect = Ui.button(this, authenticated ? "重新连接 Tailnet" : "登录并连接 Tailnet");
            keyPanel.addView(connect, Ui.lp(this, -1, 46, 0, 10, 0, 0));
            Ui.add(content, keyPanel, 0, 12);
            connect.setOnClickListener(v -> {
                if (Config.getBool("tailscale_authenticated", false)) {
                    TailscaleController.connect(this, (connected, message) ->
                            Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
                    return;
                }
                String authKey = key.getText().toString().trim();
                if (authKey.isEmpty()) {
                    Toast.makeText(this, "请填写此设备的 Tailscale Auth Key", Toast.LENGTH_SHORT).show();
                    return;
                }
                Config.putString(Config.K_TAIL_KEY, authKey);
                TailscaleController.beginLogin(this, authKey, (connected, message) -> {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    if (connected) {
                        NetHub.get().stop();
                        NetHub.get().start(this, Engine.get());
                        showHome();
                    }
                });
                Toast.makeText(this, "正在登录并连接 Tailscale", Toast.LENGTH_SHORT).show();
            });
        }

        LinearLayout statusPanel = Ui.column(this);
        statusPanel.setPadding(Ui.dp(this, 16), Ui.dp(this, 13), Ui.dp(this, 16), Ui.dp(this, 13));
        statusPanel.setBackground(Ui.round(Ui.SURFACE, 8, this));
        TextView statusLabel = Ui.text(this, "设备链路", 11, Ui.WARN, true);
        statusPanel.addView(statusLabel);
        statusView = Ui.text(this, NetHub.get().statusText(), 14, Ui.TEXT, true);
        statusView.setPadding(0, Ui.dp(this, 5), 0, 0);
        statusPanel.addView(statusView);
        latencyView = Ui.text(this, lastLatency, 12, Ui.MUTED, false);
        latencyView.setPadding(0, Ui.dp(this, 4), 0, 0);
        statusPanel.addView(latencyView);
        Ui.add(content, statusPanel, 0, 12);

        TextView peerHeading = Ui.text(this, "对端设备", 16, Ui.TEXT, true);
        Ui.add(content, peerHeading, 2, 7);
        EditText peer = Ui.input(this, "输入对方 Tailscale IP 或局域网 IP");
        peer.setText(Config.peer());
        Ui.add(content, peer, 0, 8);
        LinearLayout row = Ui.row(this);
        Button save = Ui.button(this, "保存并连接");
        row.addView(save, Ui.lp(this, 0, 46, 0, 0, 5, 0));
        Button test = Ui.button(this, "测试连接", Ui.SURFACE2);
        test.setTextColor(Ui.TEXT);
        row.addView(test, Ui.lp(this, 0, 46, 5, 0, 0, 0));
        ((LinearLayout.LayoutParams) save.getLayoutParams()).width = 0;
        ((LinearLayout.LayoutParams) save.getLayoutParams()).weight = 1;
        ((LinearLayout.LayoutParams) test.getLayoutParams()).width = 0;
        ((LinearLayout.LayoutParams) test.getLayoutParams()).weight = 1;
        Ui.add(content, row, 0, 12);
        save.setOnClickListener(v -> {
            Config.putString(Config.K_PEER, peer.getText().toString().trim());
            NetHub.get().stop();
            NetHub.get().start(this, Engine.get());
            Toast.makeText(this, "已保存，正在连接", Toast.LENGTH_SHORT).show();
            render();
        });
        test.setOnClickListener(v -> {
            String host = peer.getText().toString().trim();
            io.execute(() -> {
                long started = System.nanoTime();
                long rtt = -1;
                try (java.net.Socket ignored = Config.tailnetMode()
                        ? app.nekosakana.net.TailscaleController.dial(host, Config.port())
                        : NetInfo.dialSystem(host, Config.port(), 2000)) {
                    rtt = (System.nanoTime() - started) / 1_000_000;
                } catch (Exception ignored) { }
                final long result = rtt;
                ui.post(() -> Toast.makeText(this, result >= 0 ? "TCP 可达 · " + result + " ms" : "TCP 暂不可达", Toast.LENGTH_SHORT).show());
            });
        });

        String ftp = app.nekosakana.net.FtpBridge.get().address();
        TextView ftpInfo = Ui.text(this, ftp.isEmpty() ? "FTP · 等待网络就绪" : "对方文件 · ftp://" + ftp,
                12, Ui.MUTED, false);
        ftpStatusView = ftpInfo;
        Ui.add(content, ftpInfo, 0, 4);
        updateLatency();
    }

    private void updateLatency() {
        long rtt = NetHub.get().latency();
        lastLatency = rtt >= 0 ? "链路延迟 " + rtt + " ms"
                : Config.peer().isEmpty() ? "填写对端地址以连接" : "等待握手 · 自动重试";
    }

    // ---- 远程 ----

    private void showRemote() {
        page = "远程";
        shell("远程", "设备控制");
        releaseVideoPlay();

        screenView = new ImageView(this);
        screenView.setBackgroundColor(0xFF06090A);
        screenView.setAdjustViewBounds(true);
        screenView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (lastFrame != null) screenView.setImageBitmap(lastFrame);

        videoView = new TextureView(this);
        videoView.setVisibility(videoActive ? View.VISIBLE : View.GONE);
        videoView.setSurfaceTextureListener(videoSurfaceListener);

        screenFrame = new FrameLayout(this);
        screenFrame.setBackground(Ui.round(0xFF06090A, 12, this));
        screenFrame.addView(screenView, new FrameLayout.LayoutParams(-1, -1));
        screenFrame.addView(videoView, new FrameLayout.LayoutParams(-1, -1));
        screenFrame.setOnTouchListener(new ScreenTouchListener());
        if (videoActive) screenView.setVisibility(View.GONE);

        remoteEmpty = Ui.text(this, "尚未共享画面", 14, Ui.MUTED, false);
        remoteEmpty.setGravity(Gravity.CENTER);
        remoteEmpty.setClickable(false);
        if (lastFrame != null || videoActive) remoteEmpty.setVisibility(View.GONE);
        screenFrame.addView(remoteEmpty, new FrameLayout.LayoutParams(-1, -1));

        remoteStatus = Ui.chip(this, lastFrame != null ? "画面 " + remoteW + "×" + remoteH : "未共享", Ui.INFO);
        FrameLayout.LayoutParams chipParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        chipParams.setMargins(Ui.dp(this, 12), Ui.dp(this, 12), 0, 0);
        screenFrame.addView(remoteStatus, chipParams);

        // 先按默认宽高比占位，等测量/收到画面后改成对方屏幕比例。
        content.addView(screenFrame, Ui.lp(this, -1, 320, 0, 0, 0, 14));
        screenFrame.post(this::applyScreenAspect);

        Button start = Ui.button(this, "共享屏幕");
        Button stop = Ui.button(this, "停止共享", Ui.SURFACE2);
        stop.setTextColor(Ui.DANGER);
        LinearLayout shareRow = Ui.row(this);
        shareRow.addView(start, Ui.grow(this, 48, 0, 0, 6, 0));
        shareRow.addView(stop, Ui.grow(this, 48, 6, 0, 0, 0));
        Ui.add(content, shareRow, 0, 8);

        Button shot = Ui.button(this, "截屏", Ui.SURFACE2);
        shot.setTextColor(Ui.TEXT);
        Button zoom = Ui.button(this, "全屏查看", Ui.SURFACE2);
        zoom.setTextColor(Ui.TEXT);
        zoom.setEnabled(lastFrame != null);
        remoteZoomButton = zoom;
        LinearLayout toolRow = Ui.row(this);
        toolRow.addView(shot, Ui.grow(this, 44, 0, 0, 6, 0));
        toolRow.addView(zoom, Ui.grow(this, 44, 6, 0, 0, 0));
        Ui.add(content, toolRow, 0, 10);

        Button watchToggle = Ui.button(this, watchOnly ? "观看模式：开" : "观看模式：关",
                watchOnly ? Ui.ACCENT : Ui.SURFACE2);
        Ui.add(content, watchToggle, 0, 16);
        watchToggle.setOnClickListener(v -> {
            watchOnly = !watchOnly;
            Config.putBool("remote_watch_only", watchOnly);
            watchToggle.setText(watchOnly ? "观看模式：开" : "观看模式：关");
            Ui.colorButton(this, watchToggle, watchOnly ? Ui.ACCENT : Ui.SURFACE2);
            Toast.makeText(this, watchOnly ? "已进入观看模式，不转发触控" : "已恢复控制", Toast.LENGTH_SHORT).show();
        });

        Ui.add(content, Ui.section(this, "画面设置"), 6, 4);
        LinearLayout settingsRow = Ui.row(this);
        TextView fpsLabel = Ui.text(this, "帧率", 12, Ui.MUTED, false);
        EditText fpsInput = Ui.input(this, "20");
        fpsInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        fpsInput.setText(String.valueOf(Config.getInt("remote_video_fps", 20)));
        TextView scaleLabel = Ui.text(this, "分辨率", 12, Ui.MUTED, false);
        EditText scaleInput = Ui.input(this, "1");
        scaleInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        scaleInput.setText(scaleText(Config.getFloat("remote_video_scale", 1f)));
        settingsRow.addView(fpsLabel, Ui.lp(this, 30, -2, 0, 0, 8, 0));
        settingsRow.addView(fpsInput, Ui.grow(this, 48, 0, 0, 12, 0));
        settingsRow.addView(scaleLabel, Ui.lp(this, 46, -2, 0, 0, 8, 0));
        settingsRow.addView(scaleInput, Ui.grow(this, 48, 0, 0, 0, 0));
        Ui.add(content, settingsRow, 0, 8);
        Ui.add(content, Ui.text(this, "分辨率 1 = 100%，可填 0.25～1，越小越省流量", 10, Ui.MUTED, false), 0, 6);

        final boolean[] projection = {Config.screenProjection()};
        Button adbToggle = Ui.button(this, projectionToggleText(projection[0]), Ui.SURFACE2);
        adbToggle.setTextColor(Ui.TEXT);
        Ui.add(content, adbToggle, 0, 10);
        Ui.add(content, Ui.text(this, "默认：有 Shizuku/ADB 权限时免确认直接开始共享。开启后用系统投屏，画质更流畅，但每次都要在被控设备上确认授权。", 10, Ui.MUTED, false), 0, 6);
        adbToggle.setOnClickListener(v -> {
            projection[0] = !projection[0];
            Config.setScreenProjection(projection[0]);
            adbToggle.setText(projectionToggleText(projection[0]));
        });

        start.setOnClickListener(v -> {
            viewing = true;
            remoteStatus.setText("等待授权");
            remoteEmpty.setVisibility(View.VISIBLE);
            int fps = Math.max(1, Math.min(60, parseInt(fpsInput.getText().toString(), 20)));
            float scale = parseScale(scaleInput.getText().toString(), 1f);
            Config.putInt("remote_video_fps", fps);
            Config.putFloat("remote_video_scale", scale);
            runRequest("screen.start", streamPayload(fps, scale, true), "已请求对方共享屏幕");
        });
        shot.setOnClickListener(v -> io.execute(() -> {
            Engine.Reply reply = Engine.get().request("screen.shot", new JSONObject(), null, 15000);
            if (reply.ok && reply.body != null) {
                viewing = true;
                int w = reply.data == null ? 0 : reply.data.optInt("w");
                int h = reply.data == null ? 0 : reply.data.optInt("h");
                ui.post(() -> {
                    if (remoteEmpty != null) remoteEmpty.setVisibility(View.GONE);
                });
                showStill(reply.body, w, h);
            } else ui.post(() -> Toast.makeText(this, reply.message, Toast.LENGTH_LONG).show());
        }));
        stop.setOnClickListener(v -> {
            viewing = false;
            runRequest("screen.stop", new JSONObject(), "已停止");
            if (zoomDialog != null) zoomDialog.dismiss();
            videoActive = false;
            videoConfigured = false;
            videoFallbackTried = false;
            releaseVideoPlay();
            if (videoView != null) videoView.setVisibility(View.GONE);
            screenView.setImageDrawable(null);
            screenView.setVisibility(View.VISIBLE);
            remoteEmpty.setVisibility(View.VISIBLE);
            remoteStatus.setText("未共享");
            Bitmap previous = lastFrame;
            lastFrame = null;
            if (previous != null && !previous.isRecycled()) previous.recycle();
            zoom.setEnabled(false);
        });
        zoom.setOnClickListener(v -> showZoomView());

        LinearLayout fallbackCard = Ui.card(this, "", "系统投屏", "共享异常或画面黑屏时的备用方式", Ui.WARN);
        Button alternateCapture = Ui.button(this, "切换到系统投屏", Ui.SURFACE2);
        alternateCapture.setTextColor(Ui.TEXT);
        fallbackCard.addView(alternateCapture, Ui.lp(this, -1, 44, 0, 10, 0, 0));
        Ui.add(content, fallbackCard, 0, 16);
        alternateCapture.setOnClickListener(v -> {
            viewing = true;
            remoteStatus.setText("等待授权");
            remoteEmpty.setVisibility(View.VISIBLE);
            int fps = Math.max(1, Math.min(60, parseInt(fpsInput.getText().toString(), 20)));
            float scale = parseScale(scaleInput.getText().toString(), 1f);
            Config.putInt("remote_video_fps", fps);
            Config.putFloat("remote_video_scale", scale);
            JSONObject p = streamPayload(fps, scale, true);
            try { p.put("projection", true); } catch (Exception ignored) { }
            runRequest("screen.start", p, "已请求系统屏幕共享");
        });

        Ui.add(content, Ui.section(this, "设备导航"), 0, 0);
        LinearLayout keys = Ui.row(this);
        String[][] keyDefs = {{"返回", "KEYCODE_BACK"}, {"主页", "KEYCODE_HOME"}, {"任务", "KEYCODE_APP_SWITCH"}, {"电源", "KEYCODE_POWER"}};
        for (String[] kd : keyDefs) {
            Button b = Ui.button(this, kd[0], Ui.SURFACE2);
            b.setTextColor(Ui.TEXT);
            keys.addView(b, Ui.lp(this, 0, 44, 0, 0, 4, 0));
            ((LinearLayout.LayoutParams) b.getLayoutParams()).weight = 1;
            ((LinearLayout.LayoutParams) b.getLayoutParams()).width = 0;
            b.setOnClickListener(v -> {
                JSONObject p = new JSONObject();
                try {
                    p.put("action", "key");
                    p.put("key", kd[1]);
                } catch (Exception ignored) {
                }
                runRequest("control", p, null);
            });
        }
        Ui.add(content, keys, 0, 12);

        Ui.add(content, Ui.section(this, "文字输入"), 8, 4);
        LinearLayout textRow = Ui.row(this);
        EditText textInput = Ui.input(this, "输入要发送的文字");
        textInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        Button sendText = Ui.button(this, "发送");
        textRow.addView(textInput, Ui.lp(this, 0, 48, 0, 0, 7, 0));
        textRow.addView(sendText, Ui.lp(this, 72, 48, 0, 0, 0, 0));
        ((LinearLayout.LayoutParams) textInput.getLayoutParams()).weight = 1;
        ((LinearLayout.LayoutParams) textInput.getLayoutParams()).width = 0;
        Ui.add(content, textRow, 0, 12);
        View.OnClickListener sendRemoteText = v -> {
            String value = textInput.getText().toString();
            if (value.trim().isEmpty()) return;
            JSONObject p = new JSONObject();
            try {
                p.put("action", "text");
                p.put("text", value);
            } catch (Exception ignored) {
            }
            runRequest("control", p, "已发送");
            textInput.setText("");
        };
        sendText.setOnClickListener(sendRemoteText);
        textInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendRemoteText.onClick(v);
                return true;
            }
            return false;
        });

        Ui.add(content, Ui.section(this, "音频与锁定"), 8, 4);
        LinearLayout audioRow = Ui.row(this);
        Button audioToggle = Ui.button(this, AudioPlayer.get().isPlaying() ? "停止收听" : "收听音频", Ui.SURFACE2);
        audioToggle.setTextColor(Ui.TEXT);
        Button micToggle = Ui.button(this, micListening ? "停止收听" : "收听语音", Ui.SURFACE2);
        micToggle.setTextColor(Ui.TEXT);
        audioRow.addView(audioToggle, Ui.grow(this, 44, 0, 0, 4, 0));
        audioRow.addView(micToggle, Ui.grow(this, 44, 4, 0, 0, 0));
        Ui.add(content, audioRow, 0, 10);
        audioToggle.setOnClickListener(v -> {
            if (AudioPlayer.get().isPlaying()) {
                AudioPlayer.get().stop();
                audioToggle.setText("收听音频");
                runRequest("audio.stop", new JSONObject(), null);
            } else {
                AudioPlayer.get().start();
                audioToggle.setText("停止收听");
                runRequest("audio.start", new JSONObject(), null);
            }
        });
        micToggle.setOnClickListener(v -> {
            if (micListening) {
                micListening = false;
                micToggle.setText("收听语音");
                AudioPlayer.get().stop();
                runRequest("mic.stop", new JSONObject(), null);
            } else {
                AudioPlayer.get().start();
                micListening = true;
                micToggle.setText("停止收听");
                runRequest("mic.start", new JSONObject(), "正在收听对方麦克风");
            }
        });

        LinearLayout lockRow = Ui.row(this);
        Button configureLock = Ui.button(this, "设置锁定", Ui.WARN);
        Button releaseLock = Ui.button(this, "解锁", Ui.SURFACE2);
        releaseLock.setTextColor(Ui.TEXT);
        lockRow.addView(configureLock, Ui.lp(this, 0, 44, 0, 0, 4, 0));
        lockRow.addView(releaseLock, Ui.lp(this, 0, 44, 4, 0, 0, 0));
        ((LinearLayout.LayoutParams) configureLock.getLayoutParams()).weight = 1;
        ((LinearLayout.LayoutParams) configureLock.getLayoutParams()).width = 0;
        ((LinearLayout.LayoutParams) releaseLock.getLayoutParams()).weight = 1;
        ((LinearLayout.LayoutParams) releaseLock.getLayoutParams()).width = 0;
        configureLock.setOnClickListener(v -> showLockConfig());
        releaseLock.setOnClickListener(v -> runRequest("lock.release", new JSONObject(), "已解锁"));
        lockEventView = Ui.text(this, "", 12, Ui.MUTED, false);
        lockEventView.setPadding(0, Ui.dp(this, 7), 0, 0);
        Ui.add(content, lockRow, 0, 6);
        Ui.add(content, lockEventView, 0, 4);
        Ui.add(content, Ui.text(this, "控制方式 · " + shellBackendLabel(), 11, Ui.MUTED, false), 12, 0);
    }

    /** 展示一张主动截屏的静帧；视频推流时短暂置顶显示后再恢复实时画面。 */
    private void showStill(byte[] webp, int w, int h) {
        io.execute(() -> {
            Bitmap bmp = Media.decode(webp);
            ui.post(() -> {
                if (bmp == null) return;
                if (w > 0) remoteW = w;
                if (h > 0) remoteH = h;
                if (lastFrame != null && lastFrame != bmp && !lastFrame.isRecycled()) lastFrame.recycle();
                lastFrame = bmp;
                if (screenView != null) {
                    screenView.setImageBitmap(bmp);
                    if (videoActive) {
                        screenView.setVisibility(View.VISIBLE);
                        screenView.bringToFront();
                    }
                }
                if (zoomScreenView != null) zoomScreenView.setImageBitmap(bmp);
                if (remoteEmpty != null) remoteEmpty.setVisibility(View.GONE);
                if (remoteZoomButton != null) remoteZoomButton.setEnabled(true);
                if (remoteStatus != null) remoteStatus.setText("画面 " + remoteW + "×" + remoteH);
                if (videoActive && screenView != null) {
                    // 静帧只作短暂反馈，之后恢复实时视频画面。
                    ui.postDelayed(() -> {
                        if (!videoActive || screenView == null) return;
                        screenView.setVisibility(View.GONE);
                        if (videoView != null) videoView.setVisibility(View.VISIBLE);
                    }, 2500);
                }
            });
        });
    }

    private String shellBackendLabel() {
        switch (ShellX.backend()) {
            case ShellX.ROOT:
                return "Root";
            case ShellX.SHIZUKU:
                return "Shizuku / ADB";
            default:
                return "无障碍";
        }
    }

    private String formatLockEvent(String json) {
        try {
            JSONObject event = new JSONObject(json);
            String action = event.optString("action");
            int index = event.optInt("index", -1) + 1;
            switch (action) {
                case "input": {
                    int inputIndex = event.optInt("index", -1);
                    String value = event.optString("text", "");
                    return "输入框 " + (inputIndex + 1) + " · " + (value.isEmpty() ? "(空)" : value);
                }
                case "selection":
                    return "选项 " + index + (event.optBoolean("checked") ? " · 已选择" : " · 已取消");
                case "focus":
                    return "输入框 " + (event.optInt("index", -1) + 1)
                            + (event.optBoolean("focused") ? " · 正在输入" : " · 已离开");
                case "submit":
                    return summarizeLockSubmission(event);
                case "correct":
                    return "回答正确 · 已解锁";
                case "maxwrong":
                    return "错误次数已用尽 · " + event.optInt("attempts") + " 次";
                case "force_exit":
                    return "对方强行退出了锁定页";
                case "back":
                    return "对方退出了锁定页";
                case "scroll":
                    return "对方正在浏览选项";
                default:
                    return "收到锁定页操作";
            }
        } catch (Exception ignored) {
            return "收到锁定页操作";
        }
    }

    private String summarizeLockSubmission(JSONObject event) {
        StringBuilder summary = new StringBuilder("已提交");
        JSONArray selection = event.optJSONArray("selection");
        if (selection != null && selection.length() > 0) {
            summary.append(" · 选项 ");
            for (int i = 0; i < selection.length(); i++) {
                if (i > 0) summary.append(", ");
                summary.append(selection.optInt(i) + 1);
            }
        }
        JSONArray inputs = event.optJSONArray("inputs");
        if (inputs != null) {
            for (int i = 0; i < inputs.length(); i++) {
                summary.append(i == 0 ? " · " : " / ")
                        .append(inputs.optString(i).isEmpty() ? "(空)" : inputs.optString(i));
            }
        }
        return summary.toString();
    }

    /** 按对方屏幕比例设置预览尺寸：保持宽高比、限制最大高度并水平居中。 */
    private void applyScreenAspect() {
        FrameLayout frame = screenFrame;
        if (frame == null) return;
        int bw, bh;
        if (videoActive && videoW > 0 && videoH > 0) {
            bw = videoW;
            bh = videoH;
        } else {
            bw = remoteW;
            bh = remoteH;
        }
        if (bw <= 0 || bh <= 0) return;
        View parent = (View) frame.getParent();
        int availWidth = parent != null ? parent.getWidth() : frame.getWidth();
        if (availWidth <= 0) availWidth = frame.getWidth();
        if (availWidth <= 0) return;
        float aspect = bw / (float) bh;
        int maxHeight = Math.round(getResources().getDisplayMetrics().heightPixels * 0.52f);
        int width, height;
        if (availWidth / aspect <= maxHeight) {
            width = availWidth;
            height = Math.round(availWidth / aspect);
        } else {
            height = maxHeight;
            width = Math.round(maxHeight * aspect);
        }
        ViewGroup.LayoutParams params = frame.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) params;
            if (lp.width == width && lp.height == height && lp.gravity == Gravity.CENTER_HORIZONTAL) return;
            lp.width = width;
            lp.height = height;
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            frame.setLayoutParams(lp);
        } else if (params != null && (params.width != width || params.height != height)) {
            params.width = width;
            params.height = height;
            frame.setLayoutParams(params);
        }
    }

    private void startVideoPlay() {
        createVideoPlay(videoSurface);
    }

    private void createVideoPlay(Surface target) {
        if (!videoConfigured || target == null) return;
        releaseVideoPlay();
        String mime = "hevc".equals(videoCodec) ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        VideoPlay play = new VideoPlay();
        boolean prepared = play.configure(mime, videoW, videoH, videoCsd0, videoCsd1, target,
                new VideoPlay.Listener() {
                    @Override
                    public void onSize(int w, int h) {
                    }

                    @Override
                    public void onError(String message) {
                        ui.post(() -> fallbackToWebp(message));
                    }

                    @Override
                    public void onFirstFrame() {
                        ui.post(() -> {
                            if (remoteEmpty != null) remoteEmpty.setVisibility(View.GONE);
                            if (remoteStatus != null) {
                                remoteStatus.setText("画面 " + videoW + "×" + videoH + " · "
                                        + (videoCodec.isEmpty() ? "" : videoCodec.toUpperCase()));
                            }
                        });
                    }

                    @Override
                    public void onNeedsKeyFrame() {
                        Engine.get().sendEvent("screen.key", new JSONObject());
                    }
                });
        if (prepared) {
            videoPlay = play;
            Engine.get().sendEvent("screen.key", new JSONObject());
        } else {
            play.release();
        }
    }

    private void releaseVideoPlay() {
        if (videoPlay != null) {
            videoPlay.release();
            videoPlay = null;
        }
    }

    private void releaseVideoSurface() {
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
    }

    private void fallbackToWebp(String reason) {
        if (videoFallbackTried) return;
        videoFallbackTried = true;
        videoActive = false;
        videoConfigured = false;
        releaseVideoPlay();
        if (videoView != null) videoView.setVisibility(View.GONE);
        if (screenView != null) screenView.setVisibility(View.VISIBLE);
        if (remoteStatus != null) remoteStatus.setText("兼容画面模式");
        Logx.w("video", "硬件解码失败，回退 WebP：" + reason);
        JSONObject p = new JSONObject();
        try {
            p.put("video", false);
            p.put("projection", true);
            p.put("scale", Config.getFloat("remote_video_scale", 1f));
            p.put("fps", Config.getInt("remote_video_fps", 20));
            p.put("bitrate", autoBitrate(Config.getFloat("remote_video_scale", 1f)));
        } catch (Exception ignored) {
        }
        runRequest("screen.start", p, "已切换兼容画面模式");
    }

    private void showZoomView() {
        if (videoActive && videoPlay != null && videoPlay.isRunning()) {
            showVideoZoom();
            return;
        }
        if (lastFrame == null || !viewing) {
            Toast.makeText(this, "等待对方画面", Toast.LENGTH_SHORT).show();
            return;
        }
        if (zoomDialog != null && zoomDialog.isShowing()) return;

        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
        FrameLayout fullscreen = new FrameLayout(this);
        fullscreen.setBackgroundColor(0xFF05080A);
        ImageView image = new ImageView(this);
        image.setBackgroundColor(0xFF05080A);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(lastFrame);
        fullscreen.addView(image, new FrameLayout.LayoutParams(-1, -1));
        fullscreen.setOnTouchListener(new ScreenTouchListener());

        Button close = Ui.button(this, "收起", Ui.SURFACE2);
        close.setTextColor(Ui.TEXT);
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                Ui.dp(this, 76), Ui.dp(this, 42), Gravity.TOP | Gravity.END);
        closeParams.setMargins(0, Ui.dp(this, 20), Ui.dp(this, 16), 0);
        fullscreen.addView(close, closeParams);
        close.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(fullscreen);
        dialog.setOnDismissListener(d -> {
            if (zoomDialog == dialog) zoomDialog = null;
            if (zoomScreenView == image) zoomScreenView = null;
        });
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.black);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        zoomScreenView = image;
        zoomDialog = dialog;
    }

    private void showVideoZoom() {
        if (zoomDialog != null && zoomDialog.isShowing()) return;
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
        FrameLayout fullscreen = new FrameLayout(this);
        fullscreen.setBackgroundColor(0xFF05080A);
        TextureView view = new TextureView(this);
        fullscreen.addView(view, new FrameLayout.LayoutParams(-1, -1));
        fullscreen.setOnTouchListener(new ScreenTouchListener());

        Button close = Ui.button(this, "收起", Ui.SURFACE2);
        close.setTextColor(Ui.TEXT);
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                Ui.dp(this, 76), Ui.dp(this, 42), Gravity.TOP | Gravity.END);
        closeParams.setMargins(0, Ui.dp(this, 20), Ui.dp(this, 16), 0);
        fullscreen.addView(close, closeParams);
        close.setOnClickListener(v -> dialog.dismiss());

        final Surface[] zoomSurface = new Surface[1];
        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                if (zoomSurface[0] != null) zoomSurface[0].release();
                zoomSurface[0] = new Surface(st);
                // 用新的解码器输出到全屏 Surface，避免部分设备不支持运行时切换输出面。
                createVideoPlay(zoomSurface[0]);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (zoomSurface[0] != null) {
                    zoomSurface[0].release();
                    zoomSurface[0] = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });

        dialog.setContentView(fullscreen);
        dialog.setOnDismissListener(d -> {
            // 关闭全屏后重建解码器输出回预览区，保证画面继续刷新。
            createVideoPlay(videoSurface);
            zoomDialog = null;
        });
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.black);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        zoomDialog = dialog;
    }

    private void showLockConfig() {
        LinearLayout form = Ui.column(this);
        form.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        form.addView(Ui.section(this, "问题"));
        EditText question = Ui.input(this, "输入问题");
        form.addView(question, Ui.lp(this, -1, 48, 0, 2, 0, 10));

        form.addView(Ui.section(this, "题型"));
        android.widget.RadioGroup mode = new android.widget.RadioGroup(this);
        mode.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        android.widget.RadioButton inputMode = new android.widget.RadioButton(this);
        inputMode.setText("输入题");
        inputMode.setTextColor(Ui.TEXT);
        int inputModeId = View.generateViewId();
        inputMode.setId(inputModeId);
        android.widget.RadioButton choiceMode = new android.widget.RadioButton(this);
        choiceMode.setText("选择题");
        choiceMode.setTextColor(Ui.TEXT);
        int choiceModeId = View.generateViewId();
        choiceMode.setId(choiceModeId);
        mode.addView(inputMode, new android.widget.RadioGroup.LayoutParams(0, Ui.dp(this, 44), 1));
        mode.addView(choiceMode, new android.widget.RadioGroup.LayoutParams(0, Ui.dp(this, 44), 1));
        form.addView(mode);

        CheckBox multi = new CheckBox(this);
        multi.setText("允许多选");
        multi.setTextColor(Ui.TEXT);
        form.addView(multi);
        CheckBox force = new CheckBox(this);
        force.setText("允许对方强行退出");
        force.setTextColor(Ui.TEXT);
        form.addView(force);

        LinearLayout optionsGroup = Ui.column(this);
        optionsGroup.addView(Ui.section(this, "选项"));
        EditText options = Ui.input(this, "每行一个选项");
        options.setSingleLine(false);
        options.setMinLines(3);
        options.setGravity(Gravity.TOP | Gravity.START);
        options.setPadding(Ui.dp(this, 13), Ui.dp(this, 10), Ui.dp(this, 13), Ui.dp(this, 10));
        optionsGroup.addView(options, Ui.lp(this, -1, -2, 0, 2, 0, 5));
        form.addView(optionsGroup);

        LinearLayout inputsGroup = Ui.column(this);
        inputsGroup.addView(Ui.section(this, "输入项"));
        EditText inputs = Ui.input(this, "每行一个名称；留空使用一个输入框");
        inputs.setSingleLine(false);
        inputs.setMinLines(2);
        inputs.setGravity(Gravity.TOP | Gravity.START);
        inputs.setPadding(Ui.dp(this, 13), Ui.dp(this, 10), Ui.dp(this, 13), Ui.dp(this, 10));
        inputsGroup.addView(inputs, Ui.lp(this, -1, -2, 0, 2, 0, 5));
        form.addView(inputsGroup);

        TextView answerTitle = Ui.section(this, "正确答案");
        form.addView(answerTitle);
        EditText answers = Ui.input(this, "每行一个正确答案");
        answers.setSingleLine(false);
        answers.setMinLines(2);
        answers.setGravity(Gravity.TOP | Gravity.START);
        answers.setPadding(Ui.dp(this, 13), Ui.dp(this, 10), Ui.dp(this, 13), Ui.dp(this, 10));
        form.addView(answers, Ui.lp(this, -1, -2, 0, 2, 0, 10));

        form.addView(Ui.section(this, "错误上限"));
        EditText attempts = Ui.input(this, "允许错误次数");
        attempts.setInputType(InputType.TYPE_CLASS_NUMBER);
        attempts.setText("3");
        form.addView(attempts, Ui.lp(this, 108, 48, 0, 2, 0, 4));
        java.util.function.BooleanSupplier isChoice = () -> mode.getCheckedRadioButtonId() == choiceModeId;
        Runnable updateMode = () -> {
            boolean choiceSelected = isChoice.getAsBoolean();
            optionsGroup.setVisibility(choiceSelected ? View.VISIBLE : View.GONE);
            inputsGroup.setVisibility(choiceSelected ? View.GONE : View.VISIBLE);
            multi.setVisibility(choiceSelected ? View.VISIBLE : View.GONE);
            answers.setHint(choiceSelected ? "选项序号，例如 1,3" : "每行一个正确答案");
            answerTitle.setText(choiceSelected ? "正确选项" : "正确答案");
        };
        mode.setOnCheckedChangeListener((group, checkedId) -> updateMode.run());
        try {
            JSONObject draft = new JSONObject(Config.getString("remote_lock_draft", "{}"));
            question.setText(draft.optString("question"));
            mode.check("choice".equals(draft.optString("mode")) ? choiceModeId : inputModeId);
            multi.setChecked(draft.optBoolean("multi")); force.setChecked(draft.optBoolean("allowForceExit"));
            attempts.setText(String.valueOf(draft.optInt("maxWrong", 3)));
            options.setText(arrayLines(draft.optJSONArray("options"), false));
            inputs.setText(arrayLines(draft.optJSONArray("inputs"), false));
            answers.setText(arrayLines(draft.optJSONArray("answers"), isChoice.getAsBoolean()));
        } catch (Exception ignored) { }
        if (mode.getCheckedRadioButtonId() == -1) mode.check(inputModeId);
        updateMode.run();
        java.util.function.Function<String, Boolean> submit = type -> {
            JSONObject config = new JSONObject();
            try {
                config.put("question", question.getText().toString().trim());
                config.put("mode", isChoice.getAsBoolean() ? "choice" : "input");
                config.put("multi", multi.isChecked());
                config.put("allowForceExit", force.isChecked());
                config.put("maxWrong", Integer.parseInt(attempts.getText().toString().trim()));
                JSONArray optionArray = new JSONArray();
                for (String line : options.getText().toString().split("\\r?\\n")) if (!line.trim().isEmpty()) optionArray.put(line.trim());
                config.put("options", optionArray);
                JSONArray inputArray = new JSONArray();
                for (String line : inputs.getText().toString().split("\\r?\\n")) if (!line.trim().isEmpty()) inputArray.put(line.trim());
                config.put("inputs", inputArray);
                JSONArray answerArray = new JSONArray();
                if (isChoice.getAsBoolean()) {
                    for (String part : answers.getText().toString().split(",")) if (!part.trim().isEmpty()) {
                        int index = Integer.parseInt(part.trim()) - 1;
                        answerArray.put(index);
                    }
                } else {
                    for (String line : answers.getText().toString().split("\\r?\\n")) {
                        if (!line.trim().isEmpty()) answerArray.put(line.trim());
                    }
                }
                config.put("answers", answerArray);
                String validationError = LockConfigValidator.error(config);
                if (validationError != null) {
                    Toast.makeText(this, validationError, Toast.LENGTH_LONG).show();
                    return false;
                }
                Config.putString("remote_lock_draft", config.toString());
                JSONObject request = new JSONObject(); request.put("config", config);
                runRequest(type, request, "lock.engage".equals(type) ? "已发送锁定" : "已更新问题与次数");
                return true;
            } catch (Exception e) {
                Toast.makeText(this, "配置格式错误：" + e.getMessage(), Toast.LENGTH_LONG).show();
                return false;
            }
        };
        ScrollView scrollForm = new ScrollView(this); scrollForm.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("锁定设置").setView(scrollForm)
                .setPositiveButton("锁定", null)
                .setNeutralButton("更新设置", null)
                .setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(Ui.round(Ui.SURFACE, 10, this));
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Ui.ACCENT);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Ui.INFO);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Ui.MUTED);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (submit.apply("lock.engage")) dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (submit.apply("lock.config")) dialog.dismiss();
            });
        });
        dialog.show();
    }

    private String arrayLines(JSONArray array, boolean indices) {
        if (array == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            if (i > 0) result.append(indices ? "," : "\n");
            result.append(indices ? String.valueOf(array.optInt(i) + 1) : array.optString(i));
        }
        return result.toString();
    }

    private final class ScreenTouchListener implements View.OnTouchListener {
        private boolean touching;
        private long lastMove;
        private long downTime;
        private float lastX, lastY;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (watchOnly) return false;
            if (remoteW <= 0 || remoteH <= 0) return false;
            if (!videoActive && lastFrame == null) return false;
            float[] mapped = mapToRemote(v, event.getX(), event.getY());
            if (mapped == null && !touching) return false;
            if (mapped == null) mapped = new float[]{lastX, lastY};
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touching = true;
                    lastMove = 0;
                    downTime = event.getEventTime();
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    lastX = mapped[0]; lastY = mapped[1];
                    sendTouch(0, lastX, lastY, 0);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lastX = mapped[0]; lastY = mapped[1];
                    if (touching && event.getEventTime() - lastMove >= 20) {
                        lastMove = event.getEventTime();
                        sendTouch(2, lastX, lastY, 0);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (touching) {
                        long held = Math.max(0, event.getEventTime() - downTime);
                        sendTouch(event.getActionMasked() == MotionEvent.ACTION_UP ? 1 : 3,
                                mapped[0], mapped[1], held);
                    }
                    touching = false;
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    v.performClick();
                    return true;
                default:
                    return true;
            }
        }

        /** 全部触摸事件都在同一串行线程按顺序、不等待回包发出，保证低延迟且不误判长按。 */
        private void sendTouch(int phase, float x, float y, long heldMs) {
            JSONObject p = new JSONObject();
            try {
                p.put("action", "touch");
                p.put("phase", phase);
                p.put("x", x);
                p.put("y", y);
                if (phase == 1 && heldMs > 0) p.put("duration", heldMs);
            } catch (Exception ignored) { }
            touchIo.execute(() -> Engine.get().sendEvent("control", p));
        }

        private float[] mapToRemote(View v, float x, float y) {
            int vw = v.getWidth();
            int vh = v.getHeight();
            int bw, bh;
            if (videoActive && videoW > 0 && videoH > 0) {
                bw = videoW;
                bh = videoH;
            } else if (lastFrame != null) {
                bw = lastFrame.getWidth();
                bh = lastFrame.getHeight();
            } else {
                return null;
            }
            if (vw == 0 || vh == 0 || bw == 0 || bh == 0) return null;
            float scale = Math.min((float) vw / bw, (float) vh / bh);
            float dw = bw * scale;
            float dh = bh * scale;
            float left = (vw - dw) / 2f;
            float top = (vh - dh) / 2f;
            if (x < left || x > left + dw || y < top || y > top + dh) return null;
            float nx = (x - left) / scale;
            float ny = (y - top) / scale;
            return new float[]{nx * remoteW / (float) bw, ny * remoteH / (float) bh};
        }
    }

    // ---- 会话 ----

    private void showChat() {
        page = "会话";
        shell("会话", "文字与文件");
        ViewGroup outer = (ViewGroup) content.getParent();
        outer.removeView(content);
        root.removeView(outer);
        root.addView(content, 2, new LinearLayout.LayoutParams(-1, 0, 1));
        chatVisible = true;
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        chatScroll = new ScrollView(this);
        chatList = Ui.column(this);
        chatScroll.addView(chatList);
        content.addView(chatScroll, Ui.lp(this, -1, 0, 0, 0, 0, 0));
        ((LinearLayout.LayoutParams) chatScroll.getLayoutParams()).weight = 1;
        refreshChat(false);

        chatAudio = new AudioBar(this);
        Ui.add(content, chatAudio.view(), 6, 0);

        LinearLayout compose = Ui.row(this);
        chatInput = Ui.input(this, "输入消息");
        Button send = Ui.button(this, "发送");
        compose.addView(chatInput, Ui.lp(this, 0, 48, 0, 0, 6, 0));
        compose.addView(send, Ui.lp(this, 64, 48, 0, 0, 0, 0));
        ((LinearLayout.LayoutParams) chatInput.getLayoutParams()).weight = 1;
        ((LinearLayout.LayoutParams) chatInput.getLayoutParams()).width = 0;
        Ui.add(content, compose, 0, 8);
        Runnable sendCurrent = () -> {
            String value = chatInput.getText().toString().trim();
            if (value.isEmpty()) return;
            Engine.get().sendChat(value);
            chatInput.setText("");
            refreshChat(true);
            chatInput.requestFocus();
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(chatInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        };
        send.setOnClickListener(v -> sendCurrent.run());
        // 回车（软键盘发送键或硬件 Enter）发送。
        chatInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        chatInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                    && event.getAction() == android.view.KeyEvent.ACTION_DOWN;
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND || enter) {
                sendCurrent.run();
                return true;
            }
            return false;
        });

        LinearLayout attachments = Ui.row(this);
        String[][] kinds = {{"图片", "image/*"}, {"视频", "video/*"}, {"音频", "audio/*"}, {"任意文件", "*/*"}};
        for (String[] k : kinds) {
            Button b = Ui.button(this, k[0], Ui.SURFACE2);
            b.setTextColor(Ui.TEXT);
            attachments.addView(b, Ui.lp(this, 0, 42, 0, 0, 4, 0));
            ((LinearLayout.LayoutParams) b.getLayoutParams()).weight = 1;
            ((LinearLayout.LayoutParams) b.getLayoutParams()).width = 0;
            b.setOnClickListener(v -> pickFile(k[1]));
        }
        Ui.add(content, attachments, 0, 0);
    }

    private void refreshChat(boolean follow) {
        if (chatList == null) return;
        boolean atBottom = follow || chatScroll.getChildAt(0) == null
                || chatScroll.getScrollY() + chatScroll.getHeight() + Ui.dp(this, 32)
                >= chatScroll.getChildAt(0).getHeight();
        chatList.removeAllViews();
        List<Chat.Msg> history = Chat.history(300);
        if (history.isEmpty()) {
            TextView empty = Ui.text(this, "暂无消息", 13, Ui.MUTED, false);
            empty.setGravity(Gravity.CENTER);
            chatList.addView(empty, Ui.lp(this, -1, 120, 0, 0, 0, 0));
        }
        for (Chat.Msg m : history) {
            chatList.addView(bubble(m), Ui.lp(this, -1, -2, 0, 0, 0, 8));
        }
        chatList.post(() -> {
            if (atBottom) chatScroll.scrollTo(0, chatList.getHeight());
        });
    }

    private View bubble(Chat.Msg m) {
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        box.setBackground(Ui.round(m.out ? 0xFF20443C : Ui.SURFACE, 8, this));
        box.setGravity(m.out ? Gravity.END : Gravity.START);
        TextView head = Ui.text(this, m.out ? "我" : "对方", 10, m.out ? Ui.ACCENT : Ui.WARN, true);
        box.addView(head);

        if ("text".equals(m.kind) || m.kind == null) {
            if (!m.text.isEmpty()) {
                TextView t = Ui.text(this, m.text, 14, Ui.TEXT, false);
                t.setPadding(0, Ui.dp(this, 4), 0, 0);
                box.addView(t);
            }
            return box;
        }

        java.io.File file = (m.file != null && !m.file.isEmpty()) ? new java.io.File(m.file) : null;
        boolean hasFile = file != null && file.exists();
        if (hasFile && ("image".equals(m.kind) || "video".equals(m.kind))) {
            Bitmap thumb = "video".equals(m.kind) ? videoThumb(m.file) : decodeThumb(m.file, Ui.dp(this, 220));
            if (thumb != null) {
                ImageView image = new ImageView(this);
                image.setImageBitmap(thumb);
                image.setAdjustViewBounds(true);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setOnClickListener(v -> openFile(m.file, m.kind));
                if ("video".equals(m.kind)) {
                    FrameLayout wrap = new FrameLayout(this);
                    wrap.addView(image, new FrameLayout.LayoutParams(-2, -2));
                    TextView play = Ui.text(this, "▶", 24, 0xFFFFFFFF, true);
                    play.setGravity(Gravity.CENTER);
                    play.setBackground(Ui.round(0x66000000, 28, this));
                    wrap.addView(play, new FrameLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56), Gravity.CENTER));
                    wrap.setOnClickListener(v -> openFile(m.file, m.kind));
                    box.addView(wrap, Ui.lp(this, -2, -2, 0, 6, 0, 0));
                } else {
                    box.addView(image, Ui.lp(this, -2, -2, 0, 6, 0, 0));
                }
            }
        }
        if (hasFile && "audio".equals(m.kind)) {
            Button play = Ui.button(this, "▶ 播放音频", Ui.SURFACE2);
            play.setTextColor(Ui.TEXT);
            play.setOnClickListener(v -> {
                if (chatAudio != null) chatAudio.toggle(m.file, m.text);
            });
            box.addView(play, Ui.lp(this, -2, 42, 0, 6, 0, 0));
        }
        String name = (m.text != null && !m.text.isEmpty()) ? m.text : (file != null ? file.getName() : "");
        String icon = "image".equals(m.kind) ? "图片" : "video".equals(m.kind) ? "视频"
                : "audio".equals(m.kind) ? "音频" : "文件";
        String size = hasFile ? " · " + humanSize(file.length()) : "";
        TextView label = Ui.text(this, icon + "：" + name + size, 13, hasFile ? Ui.ACCENT : Ui.MUTED, true);
        label.setPadding(0, Ui.dp(this, 6), 0, 0);
        if (hasFile) label.setOnClickListener(v -> openFile(m.file, m.kind));
        box.addView(label);
        return box;
    }

    private Bitmap videoThumb(String path) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            return retriever.getFrameAtTime(0);
        } catch (Throwable t) {
            return null;
        } finally {
            try { retriever.release(); } catch (Throwable ignored) { }
        }
    }

    private Bitmap decodeThumb(String path, int maxPx) {
        try {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, bounds);
            int sample = 1;
            int longer = Math.max(bounds.outWidth, bounds.outHeight);
            while (longer / sample > maxPx) sample *= 2;
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeFile(path, options);
        } catch (Throwable t) {
            return null;
        }
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private void openFile(String path, String kind) {
        Intent intent = new Intent(this, ViewActivity.class);
        intent.putExtra("path", path);
        intent.putExtra("kind", kind);
        startActivity(intent);
    }

    private void pickFile(String mime) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        int code = mime.equals("application/vnd.android.package-archive") ? REQ_FILE_APK
                : (mime.startsWith("*") ? REQ_FILE_ANY : REQ_FILE_MEDIA);
        startActivityForResult(intent, code);
    }

    // ---- 应用 ----

    private void showApps() {
        page = "应用";
        shell("应用", "使用情况 · 冻结 · 安装");
        int gen = generation;

        Button refresh = Ui.button(this, "刷新对方应用与使用情况");
        Ui.add(content, refresh, 0, 10);
        TextView current = Ui.value(this, "当前应用：--", Ui.MUTED);
        TextView usage = Ui.value(this, "", Ui.MUTED);
        LinearLayout listBox = Ui.column(this);
        content.addView(current, Ui.lp(this, -1, -2, 0, 4, 0, 4));
        content.addView(usage, Ui.lp(this, -1, -2, 0, 0, 0, 8));
        Ui.add(content, listBox, 0, 10);

        Button installBtn = Ui.button(this, "安装 APK 到对方设备", Ui.WARN);
        Ui.add(content, installBtn, 0, 10);
        installBtn.setOnClickListener(v -> pickFile("application/vnd.android.package-archive"));

        Runnable load = () -> io.execute(() -> {
            Engine.Reply cur = Engine.get().request("usage.current", new JSONObject(), null, 6000);
            Engine.Reply stats = Engine.get().request("usage.stats", new JSONObject(), null, 8000);
            Engine.Reply apps = Engine.get().request("apps.list", new JSONObject(), null, 10000);
            Engine.Reply frozen = Engine.get().request("apps.frozen", new JSONObject(), null, 8000);
            ui.post(() -> {
                if (gen != generation) return;
                if (cur != null && cur.data != null) {
                    String label = cur.data.optString("label", "");
                    String pkg = cur.data.optString("pkg", "");
                    String shown = !label.isEmpty() ? label : (pkg.isEmpty() ? "--" : pkg);
                    current.setText("对方当前应用：" + shown + (pkg.isEmpty() ? "" : "（" + pkg + "）"));
                }
                usage.setText(summarizeUsage(stats));
                listBox.removeAllViews();
                renderAppList(listBox, apps, gen);
                if (frozen != null && frozen.data != null) {
                    TextView ft = Ui.section(this, "已冻结应用");
                    listBox.addView(ft);
                    JSONArray arr = frozen.data.optJSONArray("list");
                    if (arr == null || arr.length() == 0) {
                        listBox.addView(Ui.text(this, "无", 12, Ui.MUTED, false));
                    } else {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            if (o == null) continue;
                            LinearLayout rowBox = Ui.row(this);
                            rowBox.addView(Ui.text(this, o.optString("label"), 13, Ui.TEXT, false), Ui.lp(this, 0, -2, 0, 4, 0, 4));
                            ((LinearLayout.LayoutParams) rowBox.getChildAt(0).getLayoutParams()).weight = 1;
                            ((LinearLayout.LayoutParams) rowBox.getChildAt(0).getLayoutParams()).width = 0;
                            Button unfreeze = Ui.button(this, "解冻", Ui.ACCENT);
                            rowBox.addView(unfreeze, Ui.lp(this, 72, 38, 0, 4, 0, 4));
                            listBox.addView(rowBox);
                            unfreeze.setOnClickListener(v -> runRequest("apps.unfreeze", pkgPayload(o.optString("pkg")), "已解冻，刷新中"));
                        }
                    }
                }
            });
        });
        refresh.setOnClickListener(v -> load.run());
        load.run();
    }

    private void renderAppList(LinearLayout listBox, Engine.Reply apps, int gen) {
        listBox.addView(Ui.section(this, "应用列表"));
        if (apps == null || apps.data == null) {
            listBox.addView(Ui.text(this, "无法获取（请检查授权与连接）", 12, Ui.MUTED, false));
            return;
        }
        JSONArray arr = apps.data.optJSONArray("list");
        if (arr == null) return;
        for (int i = 0; i < arr.length() && i < 120; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            LinearLayout rowBox = Ui.row(this);
            LinearLayout col = Ui.column(this);
            col.addView(Ui.text(this, o.optString("label"), 13, Ui.TEXT, true));
            col.addView(Ui.text(this, o.optString("pkg"), 10, Ui.MUTED, false));
            rowBox.addView(col, Ui.lp(this, 0, -2, 0, 6, 6, 6));
            ((LinearLayout.LayoutParams) col.getLayoutParams()).weight = 1;
            ((LinearLayout.LayoutParams) col.getLayoutParams()).width = 0;
            Button launch = Ui.button(this, "打开", Ui.SURFACE2);
            launch.setTextColor(Ui.TEXT);
            Button freeze = Ui.button(this, o.optBoolean("frozen") ? "解冻" : "冻结", Ui.WARN);
            rowBox.addView(launch, Ui.lp(this, 60, 36, 0, 6, 4, 6));
            rowBox.addView(freeze, Ui.lp(this, 60, 36, 0, 6, 0, 6));
            listBox.addView(rowBox);
            String pkg = o.optString("pkg");
            launch.setOnClickListener(v -> runRequest("apps.launch", pkgPayload(pkg), null));
            freeze.setOnClickListener(v -> runRequest(o.optBoolean("frozen") ? "apps.unfreeze" : "apps.freeze", pkgPayload(pkg), "已发送，刷新中"));
        }
    }

    private JSONObject pkgPayload(String pkg) {
        JSONObject p = new JSONObject();
        try {
            p.put("pkg", pkg);
        } catch (Exception ignored) {
        }
        return p;
    }

    private String summarizeUsage(Engine.Reply stats) {
        if (stats == null || stats.data == null) return "使用情况：不可用";
        JSONArray today = stats.data.optJSONArray("today");
        JSONArray week = stats.data.optJSONArray("week");
        boolean emptyToday = today == null || today.length() == 0;
        boolean emptyWeek = week == null || week.length() == 0;
        if (emptyToday && emptyWeek) return "使用情况：暂无数据（需授予「使用情况访问」权限）";
        StringBuilder sb = new StringBuilder("今天（0 点刷新）：\n");
        appendUsage(sb, today, 8);
        sb.append("\n近 7 天：\n");
        appendUsage(sb, week, 8);
        return sb.toString();
    }

    private void appendUsage(StringBuilder sb, JSONArray arr, int limit) {
        if (arr == null || arr.length() == 0) {
            sb.append("· 暂无\n");
            return;
        }
        for (int i = 0; i < arr.length() && i < limit; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            sb.append("· ").append(o.optString("label")).append("  ")
                    .append(formatUsageDuration(o.optLong("total"))).append("\n");
        }
    }

    private static String formatUsageDuration(long ms) {
        if (ms < 60_000) return "不到 1 分钟";
        long min = ms / 60_000;
        if (min < 60) return min + " 分钟";
        long h = min / 60;
        long m = min % 60;
        return m == 0 ? h + " 小时" : h + " 小时 " + m + " 分";
    }

    // ---- 设备 ----

    private void showDevice() {
        page = "设备";
        shell("设备", "状态 · 控制 · 传感器 · 定位");
        int gen = generation;

        LinearLayout stateCard = Ui.card(this, "设备状态", "", "", Ui.INFO);
        TextView state = Ui.value(this, "正在读取设备状态…", Ui.MUTED);
        stateCard.addView(state, Ui.lp(this, -1, -2, 0, 7, 0, 0));
        Ui.add(content, stateCard, 0, 10);

        Button refresh = Ui.button(this, "刷新设备状态", Ui.SURFACE2);
        refresh.setTextColor(Ui.TEXT);
        Ui.add(content, refresh, 0, 12);

        LinearLayout controls = Ui.card(this, "亮度与音量", "拖动松手后同步到对方设备", "", Ui.ACCENT);
        DeviceSlider brightness = addDeviceSlider(controls, "亮度", 255, v -> sendDeviceSet("brightness", String.valueOf(v)));
        DeviceSlider music = addDeviceSlider(controls, "媒体音量", 15, v -> sendDeviceSet("volume", "3:" + v));
        DeviceSlider ring = addDeviceSlider(controls, "铃声音量", 7, v -> sendDeviceSet("volume", "2:" + v));
        DeviceSlider alarm = addDeviceSlider(controls, "闹钟音量", 7, v -> sendDeviceSet("volume", "4:" + v));
        DeviceSlider call = addDeviceSlider(controls, "通话音量", 5, v -> sendDeviceSet("volume", "0:" + v));
        DeviceSlider system = addDeviceSlider(controls, "系统音量", 7, v -> sendDeviceSet("volume", "1:" + v));
        Ui.add(content, controls, 0, 12);

        LinearLayout locCard = Ui.card(this, "定位", "", "", Ui.ACCENT);
        TextView locRaw = Ui.value(this, "正在读取位置…", Ui.MUTED);
        locRaw.setMaxLines(14);
        locCard.addView(locRaw, Ui.lp(this, -1, -2, 0, 8, 0, 0));
        TextView locHuman = Ui.value(this, "", Ui.ACCENT);
        locHuman.setMaxLines(16);
        locCard.addView(locHuman, Ui.lp(this, -1, -2, 0, 6, 0, 0));
        final double[] coord = {Double.NaN, Double.NaN};
        final String[] addr = {""};
        Button locGet = Ui.button(this, "获取位置（自动开关 GPS）", Ui.WARN);
        locCard.addView(locGet, Ui.lp(this, -1, 44, 0, 10, 0, 0));
        LinearLayout locBtns = Ui.row(this);
        Button locMap = Ui.button(this, "地图", Ui.INFO);
        Button locCopy = Ui.button(this, "复制", Ui.SURFACE2);
        locCopy.setTextColor(Ui.TEXT);
        locBtns.addView(locMap, Ui.grow(this, 44, 0, 8, 5, 0));
        locBtns.addView(locCopy, Ui.grow(this, 44, 8, 8, 0, 5));
        locCard.addView(locBtns);
        Ui.add(content, locCard, 0, 12);
        locGet.setOnClickListener(v -> {
            locRaw.setText("正在开启 GPS 并获取位置…");
            locHuman.setText("");
            io.execute(() -> loadLocation(gen, locRaw, locHuman, coord, addr, true));
        });
        locMap.setOnClickListener(v -> {
            if (Double.isNaN(coord[0])) {
                Toast.makeText(this, "请先获取位置", Toast.LENGTH_SHORT).show();
                return;
            }
            showMapDialog(coord[0], coord[1], addr[0]);
        });
        locCopy.setOnClickListener(v -> {
            String text = locRaw.getText().toString() + "\n" + locHuman.getText().toString();
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("位置", text));
            Toast.makeText(this, "已复制位置信息", Toast.LENGTH_SHORT).show();
        });

        LinearLayout notifCard = Ui.card(this, "通知", "查看对方设备最近的通知（需对方开启「通知使用权」）", "", Ui.ACCENT);
        Button openNotif = Ui.button(this, "查看通知");
        notifCard.addView(openNotif, Ui.lp(this, -1, 42, 0, 8, 0, 0));
        Ui.add(content, notifCard, 0, 10);
        openNotif.setOnClickListener(v -> showNotifications());

        LinearLayout sensorCard = Ui.card(this, "传感器", "", "", Ui.WARN);
        TextView sensorText = Ui.value(this, "", Ui.MUTED);
        sensorText.setMaxLines(16);
        sensorCard.addView(sensorText, Ui.lp(this, -1, -2, 0, 8, 0, 0));
        Button loadSensor = Ui.button(this, "读取传感器");
        sensorCard.addView(loadSensor, Ui.lp(this, -1, 42, 0, 8, 0, 0));
        Ui.add(content, sensorCard, 0, 0);
        loadSensor.setOnClickListener(v -> io.execute(() -> {
            Engine.Reply sensor = Engine.get().request("sensor.get", new JSONObject(), null, 8000);
            ui.post(() -> {
                if (gen != generation) return;
                StringBuilder sb = new StringBuilder();
                if (sensor != null && sensor.data != null) {
                    JSONObject values = sensor.data.optJSONObject("values");
                    if (values != null) {
                        java.util.Iterator<String> it = values.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            sb.append(k).append(": ").append(values.optString(k)).append('\n');
                        }
                    }
                }
                sensorText.setText(sb.length() == 0 ? "暂无传感器数据" : sb.toString());
            });
        }));
        io.execute(() -> loadLocation(gen, locRaw, locHuman, coord, addr, false));

        Runnable load = () -> io.execute(() -> {
            Engine.Reply r = Engine.get().request("devstate.get", new JSONObject(), null, 8000);
            ui.post(() -> {
                if (gen != generation) return;
                if (r == null || r.data == null) {
                    state.setText("读取失败（请确认连接与授权）");
                    return;
                }
                JSONObject data = r.data;
                state.setText(formatDeviceState(data));
                if (data.has("brightness")) brightness.sync(data.optInt("brightness", 0), 255);
                JSONObject volume = data.optJSONObject("volume");
                if (volume != null) {
                    music.sync(volume.optInt("music", 0), volume.optInt("musicMax", 15));
                    ring.sync(volume.optInt("ring", 0), volume.optInt("ringMax", 7));
                    alarm.sync(volume.optInt("alarm", 0), volume.optInt("alarmMax", 7));
                    call.sync(volume.optInt("call", 0), volume.optInt("callMax", 5));
                    system.sync(volume.optInt("system", 0), 7);
                }
            });
        });
        refresh.setOnClickListener(v -> load.run());
        load.run();
    }

    // ---- 通知 ----

    private void showNotifications() {
        page = "通知";
        shell("通知", "对方设备的最新通知");
        int gen = generation;
        LinearLayout list = Ui.column(this);
        Ui.add(content, list, 0, 8);
        final int pageSize = 10;
        final int[] offset = {0};
        final boolean[] loading = {false};
        final boolean[] done = {false};
        Button more = Ui.button(this, "显示更多");
        Ui.add(content, more, 0, 10);

        Runnable load = () -> {
            if (loading[0] || done[0]) return;
            loading[0] = true;
            more.setEnabled(false);
            final int start = offset[0];
            io.execute(() -> {
                JSONObject payload = new JSONObject();
                try {
                    payload.put("offset", start);
                    payload.put("count", pageSize);
                } catch (Exception ignored) {
                }
                Engine.Reply reply = Engine.get().request("notif.list", payload, null, 8000);
                JSONArray items = reply != null && reply.data != null ? reply.data.optJSONArray("list") : null;
                ui.post(() -> {
                    if (gen != generation) return;
                    loading[0] = false;
                    if (items == null || items.length() == 0) {
                        done[0] = true;
                        more.setEnabled(false);
                        if (offset[0] == 0) {
                            Ui.add(list, Ui.card(this, "通知", "暂无通知",
                                    "对方需开启「通知使用权」并收到通知后才可见。", Ui.MUTED), 0, 8);
                        } else {
                            more.setText("已显示全部通知");
                        }
                        return;
                    }
                    renderNotifications(list, items);
                    offset[0] += items.length();
                    more.setEnabled(true);
                });
            });
        };
        more.setOnClickListener(v -> load.run());
        load.run();
    }

    private void renderNotifications(LinearLayout list, JSONArray items) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            LinearLayout box = Ui.column(this);
            box.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11));
            box.setBackground(Ui.round(Ui.SURFACE, 8, this));
            String app = o.optString("app", "");
            String pkg = o.optString("pkg", "");
            box.addView(Ui.text(this, app.isEmpty() ? pkg : app, 12, Ui.ACCENT, true));
            String title = o.optString("title", "");
            if (!title.isEmpty()) {
                TextView t = Ui.text(this, title, 14, Ui.TEXT, true);
                t.setPadding(0, Ui.dp(this, 4), 0, 0);
                box.addView(t);
            }
            String text = o.optString("text", "");
            if (!text.isEmpty()) {
                TextView c = Ui.text(this, text, 13, Ui.MUTED, false);
                c.setPadding(0, Ui.dp(this, 3), 0, 0);
                box.addView(c);
            }
            String meta = fmtTime(o.optLong("time", 0)) + (pkg.isEmpty() ? "" : "  ·  " + pkg);
            TextView ts = Ui.text(this, meta, 11, Ui.MUTED, false);
            ts.setPadding(0, Ui.dp(this, 5), 0, 0);
            box.addView(ts);
            Ui.add(list, box, 0, 8);
        }
    }

    // ---- 定位与地图 ----

    private void loadLocation(int gen, TextView rawView, TextView humanView, double[] coord, String[] addr, boolean acquire) {
        Engine.Reply reply = Engine.get().request(acquire ? "location.acquire" : "location.report",
                new JSONObject(), null, acquire ? 22000 : 12000);
        JSONObject loc = reply != null && reply.data != null ? reply.data.optJSONObject("location") : null;
        boolean hasFix = loc != null && loc.has("lat");
        JSONObject regeo = hasFix ? amapRegeo(loc.optDouble("lat"), loc.optDouble("lon")) : null;
        ui.post(() -> {
            if (gen != generation) return;
            applyLocation(rawView, humanView, reply, regeo, coord, addr);
        });
    }

    private void applyLocation(TextView rawView, TextView humanView, Engine.Reply reply,
                               JSONObject regeo, double[] coord, String[] addr) {
        if (reply == null || reply.data == null) {
            rawView.setText("定位：读取失败（请确认连接与授权）");
            humanView.setText("");
            return;
        }
        JSONObject data = reply.data;
        JSONObject orientation = data.optJSONObject("orientation");
        boolean gps = data.optBoolean("gps", false);
        JSONObject loc = data.optJSONObject("location");
        boolean hasFix = loc != null && loc.has("lat");

        StringBuilder raw = new StringBuilder();
        if (hasFix) {
            coord[0] = loc.optDouble("lat");
            coord[1] = loc.optDouble("lon");
            raw.append("纬度 ").append(fmt(coord[0])).append('\n');
            raw.append("经度 ").append(fmt(coord[1])).append('\n');
            if (loc.has("accuracy")) raw.append("精度 ±").append(Math.round(loc.optDouble("accuracy"))).append(" 米\n");
            if (loc.has("altitude")) raw.append("海拔 ").append(Math.round(loc.optDouble("altitude"))).append(" 米\n");
            if (loc.has("speed")) raw.append("速度 ").append(fmt(loc.optDouble("speed"))).append(" m/s\n");
            raw.append("来源 ").append(loc.optString("provider", "--")).append(" · ")
                    .append(fmtTime(loc.optLong("time"))).append('\n');
        } else {
            raw.append(gps ? "GPS 已开启，暂未取到坐标（可稍后重试）\n" : "GPS 未开启，未获取位置\n");
        }
        if (data.has("toggled")) {
            raw.append(data.optBoolean("toggled") ? "已自动开启 GPS，获取后已关闭\n" : "GPS 原本已开启\n");
        }
        if (orientation != null && orientation.has("azimuth")) {
            raw.append("方位角 ").append(Math.round(orientation.optDouble("azimuth"))).append("°")
                    .append(" / 俯仰 ").append(Math.round(orientation.optDouble("pitch", 0))).append("°")
                    .append(" / 翻滚 ").append(Math.round(orientation.optDouble("roll", 0))).append("°\n");
        }
        rawView.setText(raw.toString());

        String human = humanReadableLocation(hasFix ? regeo : null, orientation);
        if (hasFix && regeo == null && Config.amapKey().isEmpty()) {
            human = "未配置高德地图 Key，请在「设置」中填写后重试\n";
        }
        if (human.isEmpty()) {
            human = hasFix ? "（未能解析为文字，点「地图」查看）" : "（开启 GPS 后可解析位置）";
        }
        humanView.setText(human);
        addr[0] = regeo != null ? regeo.optString("formatted_address", "") : "";
    }

    private String humanReadableLocation(JSONObject regeo, JSONObject orientation) {
        StringBuilder sb = new StringBuilder();
        if (regeo != null) {
            String address = regeo.optString("formatted_address", "");
            if (!address.isEmpty()) sb.append("当前位置：").append(address).append('\n');
            JSONObject comp = regeo.optJSONObject("addressComponent");
            if (comp != null) {
                StringBuilder line = new StringBuilder();
                line.append(amapStr(comp, "province"));
                line.append(amapStr(comp, "city"));
                line.append(amapStr(comp, "district"));
                line.append(amapStr(comp, "township"));
                JSONObject sn = comp.optJSONObject("streetNumber");
                if (sn != null) {
                    line.append(sn.optString("street", "")).append(sn.optString("number", ""));
                }
                if (line.length() > 0) sb.append("行政区划：").append(line).append('\n');
            }
        }
        if (orientation != null && orientation.has("headingText")) {
            sb.append("手机朝向：").append(orientation.optString("headingText"));
            String posture = orientation.optString("postureText", "");
            if (!posture.isEmpty()) sb.append("，").append(posture);
            sb.append('\n');
        }
        return sb.toString();
    }

    private void showMapDialog(double lat, double lon, String address) {
        Dialog dialog = new Dialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        box.addView(Ui.text(this, "附近地图", 17, Ui.TEXT, true));
        TextView addrView = Ui.text(this, address == null || address.isEmpty() ? (fmt(lat) + ", " + fmt(lon)) : address,
                12, Ui.MUTED, false);
        addrView.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 8));
        box.addView(addrView);
        ImageView map = new ImageView(this);
        map.setScaleType(ImageView.ScaleType.CENTER_CROP);
        map.setBackgroundColor(Ui.SURFACE2);
        box.addView(map, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 220)));
        TextView pois = Ui.value(this, "正在加载附近地点…", Ui.MUTED);
        pois.setMaxLines(16);
        box.addView(pois, Ui.lp(this, -1, -2, 0, 10, 0, 0));
        Button open = Ui.button(this, "在高德 / 地图中打开", Ui.INFO);
        box.addView(open, Ui.lp(this, -1, 44, 0, 12, 0, 0));
        scroll.addView(box);
        dialog.setContentView(scroll);
        open.setOnClickListener(v -> openInMap(lat, lon, address));
        dialog.show();
        if (Config.amapKey().isEmpty()) {
            pois.setText("未配置高德地图 Key：请在「设置」中填写后可显示地图与周边地点。");
            return;
        }
        io.execute(() -> {
            Bitmap bmp = amapStaticMap(lat, lon, 16, 640, 440);
            JSONArray around = amapAround(lat, lon, 1000);
            ui.post(() -> {
                if (bmp != null) map.setImageBitmap(bmp);
                pois.setText(formatAround(around));
            });
        });
    }

    private void openInMap(double lat, double lon, String name) {
        try {
            String label = URLEncoder.encode(name == null || name.isEmpty() ? "目标位置" : name, "UTF-8");
            String uri = "https://uri.amap.com/marker?position=" + lon + "," + lat
                    + "&name=" + label + "&coordinate=gaode&callnative=1";
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开地图", Toast.LENGTH_SHORT).show();
        }
    }

    private String formatAround(JSONArray arr) {
        if (arr == null || arr.length() == 0) return "附近地点：暂无";
        StringBuilder sb = new StringBuilder("附近地点：\n");
        for (int i = 0; i < arr.length() && i < 12; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            sb.append("· ").append(o.optString("name"));
            String dist = o.optString("distance", "");
            if (!dist.isEmpty()) sb.append("（").append(dist).append(" 米）");
            String addr = o.optString("address", "");
            if (!addr.isEmpty() && !"[]".equals(addr)) sb.append(" · ").append(addr);
            sb.append('\n');
        }
        return sb.toString();
    }

    private static JSONObject amapRegeo(double lat, double lon) {
        String key = Config.amapKey();
        if (key.isEmpty()) return null;
        String url = "https://restapi.amap.com/v3/geocode/regeo?key=" + key
                + "&location=" + lon + "," + lat + "&extensions=base&radius=1000";
        JSONObject o = httpGetJson(url);
        if (o == null || !"1".equals(o.optString("status"))) return null;
        return o.optJSONObject("regeocode");
    }

    private static JSONArray amapAround(double lat, double lon, int radius) {
        String key = Config.amapKey();
        if (key.isEmpty()) return null;
        String url = "https://restapi.amap.com/v3/place/around?key=" + key
                + "&location=" + lon + "," + lat + "&radius=" + radius
                + "&offset=20&page=1&extensions=base&sortrule=distance";
        JSONObject o = httpGetJson(url);
        if (o == null || !"1".equals(o.optString("status"))) return null;
        return o.optJSONArray("pois");
    }

    private static Bitmap amapStaticMap(double lat, double lon, int zoom, int w, int h) {
        String key = Config.amapKey();
        if (key.isEmpty()) return null;
        String url = "https://restapi.amap.com/v3/staticmap?key=" + key
                + "&location=" + lon + "," + lat + "&zoom=" + zoom + "&size=" + w + "*" + h
                + "&scale=2&markers=mid,,A:" + lon + "," + lat;
        return httpGetBitmap(url);
    }

    private static JSONObject httpGetJson(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "nekosakana/1.0");
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap httpGetBitmap(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "nekosakana/1.0");
            try (InputStream in = conn.getInputStream()) {
                return android.graphics.BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String amapStr(JSONObject o, String key) {
        if (o == null || !o.has(key)) return "";
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return "";
        String s = String.valueOf(v);
        return ("[]".equals(s) || "null".equals(s)) ? "" : s;
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.6f", v);
    }

    private static String fmtTime(long ms) {
        if (ms <= 0) return "--";
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new java.util.Date(ms));
    }

    private DeviceSlider addDeviceSlider(LinearLayout parent, String title, int defaultMax,
                                         java.util.function.IntConsumer onSet) {
        LinearLayout head = Ui.row(this);
        TextView label = Ui.text(this, title, 12, Ui.MUTED, false);
        TextView value = Ui.text(this, "", 12, Ui.TEXT, true);
        value.setGravity(Gravity.END);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(label, labelLp);
        head.addView(value);
        parent.addView(head, Ui.lp(this, -1, -2, 0, 8, 0, 0));
        SeekBar bar = new SeekBar(this);
        bar.setMax(defaultMax);
        parent.addView(bar, Ui.lp(this, -1, -2, 0, 0, 0, 4));
        DeviceSlider slider = new DeviceSlider(bar, value);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(progress + " / " + seekBar.getMax());
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                onSet.accept(seekBar.getProgress());
            }
        });
        slider.sync(0, defaultMax);
        return slider;
    }

    private static final class DeviceSlider {
        final SeekBar bar;
        final TextView value;

        DeviceSlider(SeekBar bar, TextView value) {
            this.bar = bar;
            this.value = value;
        }

        void sync(int level, int max) {
            if (max > 0) bar.setMax(max);
            int clamped = Math.max(0, Math.min(level, bar.getMax()));
            bar.setProgress(clamped);
            value.setText(clamped + " / " + bar.getMax());
        }
    }

    private String formatDeviceState(JSONObject data) {
        StringBuilder text = new StringBuilder();
        JSONObject volume = data.optJSONObject("volume");
        JSONObject wifi = data.optJSONObject("wifi");
        JSONObject bluetooth = data.optJSONObject("bluetooth");
        JSONObject location = data.optJSONObject("location");
        JSONObject network = data.optJSONObject("network");

        appendStateGroup(text, "屏幕");
        appendStateValue(text, "显示", data.has("screenOn")
                ? (data.optBoolean("screenOn") ? "亮屏" : "熄屏") : null);
        if (data.has("brightness")) {
            int brightness = data.optInt("brightness", -1);
            if (brightness >= 0) {
                int percent = Math.round(brightness * 100f / 255f);
                appendStateValue(text, "亮度", percent + "% · " + data.optString("brightnessMode", "手动调节"));
            }
        }
        if (data.has("screenTimeout")) {
            long timeout = data.optLong("screenTimeout");
            appendStateValue(text, "自动息屏", timeout >= Integer.MAX_VALUE ? "永不" : formatDuration(timeout));
        }
        appendStateValue(text, "自动旋转", stateSwitch(data, "autoRotate"));
        appendStateValue(text, "省电模式", stateSwitch(data, "batterySaver"));

        appendStateGroup(text, "音量");
        if (volume != null) {
            StringBuilder levels = new StringBuilder();
            appendVolume(levels, volume, "媒体", "music", "musicMax");
            appendVolume(levels, volume, "铃声", "ring", "ringMax");
            appendVolume(levels, volume, "闹钟", "alarm", "alarmMax");
            appendVolume(levels, volume, "通话", "call", "callMax");
            appendVolume(levels, volume, "系统", "system", null);
            if (levels.length() > 0) appendStateValue(text, "级别", levels.toString());
        }
        appendStateValue(text, "铃声模式", data.optString("ringerMode", null));

        appendStateGroup(text, "电池");
        if (data.has("batteryLevel")) {
            String charge = data.optBoolean("charging") ? "充电中" : "未充电";
            appendStateValue(text, "电量", data.optInt("batteryLevel") + "% · " + charge);
        }
        if (data.has("batteryTemp")) {
            appendStateValue(text, "温度", String.format(Locale.getDefault(), "%.1f °C", data.optDouble("batteryTemp")));
        }
        appendStateValue(text, "电源", data.optString("chargePlug", null));

        appendStateGroup(text, "连接");
        if (wifi != null) {
            String wifiState = wifi.optBoolean("enabled") ? "已开启" : "已关闭";
            String ssid = cleanDeviceValue(wifi.optString("ssid", ""));
            if (!ssid.isEmpty()) wifiState += " · " + ssid;
            else if (wifi.optBoolean("enabled") && (network == null || !network.optBoolean("wifi"))) wifiState += " · 未连接";
            appendStateValue(text, "Wi-Fi", wifiState);
            String ip = cleanDeviceValue(wifi.optString("ip", ""));
            if (!ip.isEmpty() && !"0.0.0.0".equals(ip)) appendStateValue(text, "Wi-Fi 地址", ip);
        }
        if (bluetooth != null && bluetooth.has("enabled")) {
            appendStateValue(text, "蓝牙", bluetooth.optBoolean("enabled") ? "已开启" : "已关闭");
        }
        if (location != null) {
            appendStateValue(text, "定位", "GPS " + (location.optBoolean("gps") ? "开启" : "关闭")
                    + " · 网络定位 " + (location.optBoolean("network") ? "开启" : "关闭"));
        }
        if (network != null) {
            StringBuilder active = new StringBuilder();
            if (network.optBoolean("wifi")) active.append("Wi-Fi");
            if (network.optBoolean("cellular")) appendJoined(active, "移动网络");
            if (network.optBoolean("vpn")) appendJoined(active, "VPN");
            appendStateValue(text, "当前网络", active.length() == 0 ? "未连接" : active.toString());
        }

        appendStateGroup(text, "系统开关");
        appendStateValue(text, "飞行模式", stateSwitch(data, "airplane"));
        appendStateValue(text, "ADB 调试", stateSwitch(data, "adb"));
        appendStateValue(text, "开发者选项", stateSwitch(data, "developer"));
        appendStateValue(text, "自动校时", stateSwitch(data, "autoTime"));

        if (data.has("uptime")) appendStateValue(text, "运行时间", formatDuration(data.optLong("uptime")));
        return text.length() == 0 ? "没有可显示的设备状态" : text.toString().trim();
    }

    private void appendStateGroup(StringBuilder text, String title) {
        if (text.length() > 0) text.append('\n');
        text.append(title).append('\n');
    }

    private void appendStateValue(StringBuilder text, String label, String value) {
        if (value == null || value.trim().isEmpty() || "null".equals(value)) return;
        text.append("  ").append(label).append("：").append(value).append('\n');
    }

    private String stateSwitch(JSONObject data, String key) {
        return data.has(key) ? (data.optBoolean(key) ? "已开启" : "已关闭") : null;
    }

    private void appendVolume(StringBuilder text, JSONObject volume, String label, String currentKey, String maxKey) {
        if (!volume.has(currentKey)) return;
        if (text.length() > 0) text.append(" · ");
        text.append(label).append(' ').append(volume.optInt(currentKey));
        if (maxKey != null && volume.has(maxKey)) text.append('/').append(volume.optInt(maxKey));
    }

    private String formatDuration(long millis) {
        if (millis < 0) return "未知";
        long minutes = millis / 60000;
        if (minutes == 0) return Math.max(1, millis / 1000) + " 秒";
        long days = minutes / 1440;
        long hours = (minutes % 1440) / 60;
        long remainder = minutes % 60;
        StringBuilder value = new StringBuilder();
        if (days > 0) value.append(days).append(" 天");
        if (hours > 0) value.append(hours).append(" 小时");
        if (remainder > 0 || value.length() == 0) value.append(remainder).append(" 分钟");
        return value.toString();
    }

    private String cleanDeviceValue(String value) {
        if (value == null) return "";
        String result = value.trim();
        if (result.length() >= 2 && result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length() - 1);
        }
        if (result.isEmpty() || "<unknown ssid>".equalsIgnoreCase(result) || "unknown".equalsIgnoreCase(result)) return "";
        return result;
    }

    private void appendJoined(StringBuilder text, String value) {
        if (text.length() > 0) text.append(" · ");
        text.append(value);
    }

    private void sendDeviceSet(String key, String value) {
        JSONObject p = new JSONObject();
        try {
            p.put("key", key);
            p.put("value", value);
        } catch (Exception ignored) {
        }
        runRequest("devstate.set", p, "已同步 " + deviceControlLabel(key));
    }

    private String deviceControlLabel(String key) {
        switch (key) {
            case "brightness":
                return "亮度";
            case "volume":
                return "音量";
            case "ringer":
                return "铃声模式";
            case "rotation":
                return "自动旋转";
            case "screenTimeout":
                return "息屏时间";
            default:
                return key;
        }
    }

    // ---- 授权 ----

    private void showPermissions() {
        page = "授权";
        shell("授权", "按需授权 · 未授权时不会调用对应功能");

        boolean privileged = ShellX.hasPrivilege();
        LinearLayout tip = Ui.card(this, "自动授权",
                privileged ? "已检测到 adb/root 权限，可一键自动授予其余权限"
                        : "授予 adb/root 权限（Root 或 Shizuku）后，可自动授予其他权限",
                "", privileged ? Ui.ACCENT : Ui.WARN);
        if (privileged) {
            Button auto = Ui.button(this, "一键自动授予其余权限");
            tip.addView(auto, Ui.lp(this, -1, 44, 0, 10, 0, 0));
            auto.setOnClickListener(v -> {
                auto.setEnabled(false);
                io.execute(() -> {
                    String summary = PermsX.autoGrant(this);
                    ui.post(() -> {
                        Toast.makeText(this, summary, Toast.LENGTH_LONG).show();
                        render();
                    });
                });
            });
        }
        Ui.add(content, tip, 0, 10);

        int missing = 0;
        for (PermsX.Perm p : PermsX.collect(this)) {
            LinearLayout box = Ui.column(this);
            box.setPadding(Ui.dp(this, 15), Ui.dp(this, 12), Ui.dp(this, 15), Ui.dp(this, 12));
            box.setBackground(Ui.round(Ui.SURFACE, 8, this));
            LinearLayout head = Ui.row(this);
            TextView title = Ui.text(this, p.title, 14, Ui.TEXT, true);
            head.addView(title, Ui.lp(this, 0, -2, 0, 0, 6, 0));
            ((LinearLayout.LayoutParams) title.getLayoutParams()).weight = 1;
            ((LinearLayout.LayoutParams) title.getLayoutParams()).width = 0;
            TextView state = Ui.text(this, p.granted ? "已授权" : "未授权", 11, p.granted ? Ui.ACCENT : Ui.DANGER, true);
            head.addView(state);
            box.addView(head);
            TextView desc = Ui.text(this, p.desc, 12, Ui.MUTED, false);
            desc.setPadding(0, Ui.dp(this, 6), 0, 0);
            box.addView(desc);
            if (!p.action.isEmpty() && !"已授权".equals(p.action) && !"已就绪".equals(p.action)) {
                Button b = Ui.button(this, p.action, Ui.SURFACE2);
                b.setTextColor(Ui.TEXT);
                box.addView(b, Ui.lp(this, -1, 40, 0, 8, 0, 0));
                b.setOnClickListener(v -> {
                    if ("shizuku".equals(p.id)) {
                        Shizuku.addRequestPermissionResultListener((code, result) -> ui.post(() -> {
                            ShellX.invalidate();
                            render();
                        }));
                    }
                    PermsX.perform(this, p, REQ_SHIZUKU);
                    ui.postDelayed(this::render, 400);
                });
            }
            if (!p.granted) missing++;
            Ui.add(content, box, 0, 9);
        }
        if (missing == 0) {
            Ui.add(content, Ui.card(this, "状态", "全部已授权", "所有功能均可用。", Ui.ACCENT), 4, 0);
        }
    }

    // ---- 日志 ----

    private void showLogs() {
        page = "日志";
        shell("日志", "操控日志与运行日志");
        Button refresh = Ui.button(this, "刷新");
        Ui.add(content, refresh, 0, 10);
        TextView control = Ui.value(this, ControlLog.asText(300), Ui.TEXT);
        TextView appLog = Ui.value(this, Logx.dump(200), Ui.MUTED);
        Ui.add(content, Ui.card(this, "操控日志", "", "", Ui.ACCENT), 0, 4);
        Ui.add(content, control, 0, 12);
        Ui.add(content, Ui.card(this, "运行日志", "", "", Ui.WARN), 0, 4);
        Ui.add(content, appLog, 0, 0);
        refresh.setOnClickListener(v -> {
            control.setText(ControlLog.asText(300));
            appLog.setText(Logx.dump(200));
        });
    }

    // ---- 设置 ----

    private void showSettings() {
        page = "设置";
        shell("设置", "Tailscale 与本地服务");
        int gen = generation;

        boolean tailscaleAuthenticated = Config.getBool("tailscale_authenticated", false);
        LinearLayout tsCard = Ui.card(this, "Tailscale", tailscaleAuthenticated ? "已登录 · 启动时自动连接" : "需要 Auth Key 才能登录", "", Ui.ACCENT);
        Button reconnect = Ui.button(this, "立即连接", Ui.SURFACE2);
        reconnect.setTextColor(Ui.TEXT);
        tsCard.addView(reconnect, Ui.lp(this, -1, 44, 0, 10, 0, 0));
        reconnect.setOnClickListener(v -> {
            TailscaleController.connect(this, (connected, message) ->
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
        });
        TextView amapTitle = Ui.text(this, "高德地图 Key", 15, Ui.TEXT, true);
        amapTitle.setPadding(0, Ui.dp(this, 16), 0, 0);
        tsCard.addView(amapTitle);
        TextView amapHint = Ui.text(this, "用于定位页的地图与周边地点（高德 Web 服务 Key，源码不内置）", 12, Ui.MUTED, false);
        amapHint.setPadding(0, Ui.dp(this, 4), 0, 0);
        tsCard.addView(amapHint);
        EditText amapKey = Ui.input(this, "高德 Web 服务 Key");
        amapKey.setSingleLine(true);
        amapKey.setText(Config.amapKey());
        tsCard.addView(amapKey, Ui.lp(this, -1, 50, 0, 10, 0, 0));
        Button saveAmap = Ui.button(this, "保存地图 Key", Ui.SURFACE2);
        saveAmap.setTextColor(Ui.TEXT);
        tsCard.addView(saveAmap, Ui.lp(this, -1, 44, 0, 8, 0, 0));
        saveAmap.setOnClickListener(v -> {
            Config.putString(Config.K_AMAP_KEY, amapKey.getText().toString().trim());
            Toast.makeText(this, "已保存地图 Key", Toast.LENGTH_SHORT).show();
        });
        Ui.add(content, tsCard, 0, 12);

        LinearLayout netCard = Ui.card(this, "连接", "Tailnet 或局域网直连", "", Ui.WARN);
        EditText peer = Ui.input(this, "对方 Tailscale IP 或局域网 IP");
        peer.setText(Config.peer());
        netCard.addView(peer, Ui.lp(this, -1, 50, 0, 10, 0, 0));
        EditText port = Ui.input(this, "监听端口");
        port.setText(String.valueOf(Config.port()));
        netCard.addView(port, Ui.lp(this, -1, 50, 0, 8, 0, 0));
        Button save = Ui.button(this, "保存并重启连接");
        netCard.addView(save, Ui.lp(this, -1, 44, 0, 10, 0, 0));
        Ui.add(content, netCard, 0, 12);

        LinearLayout svcCard = Ui.card(this, "文件访问", "对方 /sdcard · 经链路隧道", "", Ui.ACCENT);
        String ftp = app.nekosakana.net.FtpBridge.get().address();
        TextView ftpInfo = Ui.text(this, ftp.isEmpty() ? "FTP：等待网络就绪后自动开启"
                : "对方文件：ftp://" + ftp + "（对方手机 /sdcard）", 12, Ui.MUTED, false);
        svcCard.addView(ftpInfo, Ui.lp(this, -1, -2, 0, 6, 0, 2));
        TextView bridgeInfo = Ui.text(this, "支持 PASV/EPSV，自动为数据端口建隧道；本机不对外提供 FTP。", 12, Ui.MUTED, false);
        svcCard.addView(bridgeInfo, Ui.lp(this, -1, -2, 0, 0, 0, 6));
        EditText forward = Ui.input(this, "额外转发规则：本地端口,对方主机,对方端口;...");
        forward.setText(Config.getString(Config.K_FORWARD_SPEC, ""));
        svcCard.addView(forward, Ui.lp(this, -1, 48, 0, 6, 0, 6));
        LinearLayout row = Ui.row(this);
        Button forwardToggle = Ui.button(this, Config.getBool(Config.K_FORWARD_ENABLED, false) ? "关闭转发" : "开启转发", Ui.SURFACE2);
        forwardToggle.setTextColor(Ui.TEXT);
        row.addView(forwardToggle, Ui.lp(this, 0, 44, 0, 6, 4, 0));
        ((LinearLayout.LayoutParams) forwardToggle.getLayoutParams()).weight = 1;
        ((LinearLayout.LayoutParams) forwardToggle.getLayoutParams()).width = 0;
        svcCard.addView(row);
        Ui.add(content, svcCard, 0, 12);

        LinearLayout apiCard = Ui.card(this, "开放 API", "其他应用（同签名或携带 token）可调用", "", Ui.ACCENT);
        TextView token = Ui.value(this, "Token: " + Config.apiToken(), Ui.TEXT);
        apiCard.addView(token, Ui.lp(this, -1, -2, 0, 8, 0, 0));
        TextView apiHint = Ui.text(this, "授权：" + getPackageName() + ".api", 11, Ui.MUTED, false);
        apiCard.addView(apiHint, Ui.lp(this, -1, -2, 0, 4, 0, 0));
        Ui.add(content, apiCard, 0, 12);

        LinearLayout miscCard = Ui.card(this, "其他", "", "", Ui.WARN);
        Button autostart = Ui.button(this, (Config.getBool(Config.K_AUTOSTART, true) ? "已开启" : "已关闭") + " 开机/被清理后自动恢复连接", Ui.SURFACE2);
        autostart.setTextColor(Ui.TEXT);
        miscCard.addView(autostart, Ui.lp(this, -1, 44, 0, 6, 0, 0));
        TextView path = Ui.text(this, "存储目录：" + Store.get().base().getAbsolutePath()
                + (Store.get().isPublicRoot() ? "（应用外部私有目录）" : "（内部兜底目录）"), 11, Ui.MUTED, false);
        path.setPadding(0, Ui.dp(this, 8), 0, 0);
        miscCard.addView(path);
        String versionName;
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            versionName = "未知";
        }
        TextView version = Ui.text(this, "版本 " + versionName
                + " · versionCode 1 · Android " + Build.VERSION.RELEASE, 11, Ui.MUTED, false);
        version.setPadding(0, Ui.dp(this, 6), 0, 0);
        miscCard.addView(version);
        Ui.add(content, miscCard, 0, 0);

        save.setOnClickListener(v -> {
            Config.putString(Config.K_PEER, peer.getText().toString().trim());
            try {
                Config.putInt(Config.K_PORT, Integer.parseInt(port.getText().toString().trim()));
            } catch (Exception ignored) {
            }
            NetHub.get().stop();
            NetHub.get().start(this, Engine.get());
            Toast.makeText(this, "已保存并重启连接", Toast.LENGTH_SHORT).show();
        });
        forwardToggle.setOnClickListener(v -> {
            boolean enabled = !Config.getBool(Config.K_FORWARD_ENABLED, false);
            Config.putBool(Config.K_FORWARD_ENABLED, enabled);
            Config.putString(Config.K_FORWARD_SPEC, forward.getText().toString().trim());
            app.nekosakana.net.PortForward.get().apply(this);
            render();
        });
        autostart.setOnClickListener(v -> {
            boolean enabled = !Config.getBool(Config.K_AUTOSTART, true);
            Config.putBool(Config.K_AUTOSTART, enabled);
            if (enabled) app.nekosakana.core.Watchdog.schedule(this);
            else app.nekosakana.core.Watchdog.cancel(this);
            render();
        });
        if (gen == generation) {
            // 无需额外动作
        }
    }

    private JSONObject streamPayload(int fps, float scale, boolean video) {
        JSONObject p = new JSONObject();
        try {
            p.put("scale", scale);
            p.put("fps", fps);
            // 码率不暴露给用户：按分辨率自动取一个延迟与流量折中的值。
            p.put("bitrate", autoBitrate(scale));
            p.put("quality", 60);
            p.put("video", video);
            p.put("codec", "hevc");
            p.put("audio", false);
        } catch (Exception ignored) {
        }
        return p;
    }

    private static int autoBitrate(float scale) {
        int base = Math.round(2_200_000 * scale);
        return Math.max(700_000, Math.min(6_000_000, base));
    }

    private static float parseScale(String value, float def) {
        try {
            float v = Float.parseFloat(value.trim());
            if (v <= 0f) return def;
            return Math.max(0.2f, Math.min(1f, v));
        } catch (Exception e) {
            return def;
        }
    }

    private static String scaleText(float value) {
        if (Math.abs(value - Math.round(value)) < 0.001f) return String.valueOf(Math.round(value));
        return String.valueOf(value);
    }

    private static String projectionToggleText(boolean enabled) {
        return enabled ? "画面来源：系统投屏（每次需确认）" : "画面来源：ADB 直采（免确认）";
    }

    private int parseInt(String value, int def) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        waitingForAccessibilitySettings = false;
        CoreService.start(this);
        chatVisible = "会话".equals(page);
        // 仅在界面可见时刷新状态，避免后台空转耗电。
        ticker.removeCallbacks(tick);
        ticker.post(tick);
        if (remotePermissionFlow) ui.postDelayed(this::continueRemoteAuthorization, 300);
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        chatVisible = false;
        ticker.removeCallbacks(tick);
        super.onPause();
    }

    // ---- 通用 ----

    private void runRequest(String type, JSONObject payload, String toast) {
        io.execute(() -> {
            Engine.Reply reply = Engine.get().request(type, payload, null, 15000);
            ui.post(() -> {
                String message = reply.ok ? (toast != null ? toast : "成功")
                        : "失败" + (reply.message.isEmpty() ? "" : "：" + reply.message);
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private String pretty(JSONObject object) {
        try {
            return object.toString(2);
        } catch (Exception e) {
            return object.toString();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_CAPTURE) {
            remotePermissionFlow = false;
            captureRequested = false;
            if (result == RESULT_OK && data != null) {
                app.nekosakana.core.CoreService.projection(this, result, data);
            } else {
                Engine.get().cancelScreenAuthorization();
                Toast.makeText(this, "未授权屏幕共享", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if ((request == REQ_FILE_MEDIA || request == REQ_FILE_ANY || request == REQ_FILE_APK) && result == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            io.execute(() -> {
                try {
                    byte[] bytes = readAll(uri);
                    String name = queryName(uri);
                    if (request == REQ_FILE_APK) {
                        Engine.Reply reply = Engine.get().request("install", namePayload(name), bytes, 60000);
                        ui.post(() -> Toast.makeText(this, reply.ok ? "已发送安装：" + reply.message : "安装失败：" + reply.message, Toast.LENGTH_LONG).show());
                    } else if (bytes.length == 0) {
                        ui.post(() -> Toast.makeText(this, "文件为空，未发送", Toast.LENGTH_SHORT).show());
                    } else {
                        String kind = guessKind(name);
                        Engine.get().sendFile(name, kind, bytes);
                        ui.post(() -> {
                            Toast.makeText(this, "已发送文件：" + name, Toast.LENGTH_SHORT).show();
                            if ("会话".equals(page)) refreshChat(true);
                        });
                    }
                } catch (Exception e) {
                    ui.post(() -> Toast.makeText(this, "读取文件失败: " + e, Toast.LENGTH_SHORT).show());
                }
            });
        }
    }

    private JSONObject namePayload(String name) {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
        } catch (Exception ignored) {
        }
        return o;
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) bos.write(buffer, 0, n);
            return bos.toByteArray();
        }
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        }
        return "file-" + System.currentTimeMillis();
    }

    private String guessKind(String name) {
        String lower = name.toLowerCase();
        if (lower.matches(".*\\.(png|jpg|jpeg|gif|webp|bmp)$")) return "image";
        if (lower.matches(".*\\.(mp4|mkv|mov|webm|avi)$")) return "video";
        if (lower.matches(".*\\.(mp3|wav|aac|flac|ogg|m4a)$")) return "audio";
        return "file";
    }

    @Override
    protected void onDestroy() {
        Engine.get().removeScreenListener(screenListener);
        Engine.get().removeVideoListener(videoListener);
        Engine.get().removeAudioListener(audioListener);
        Engine.get().removeStatusListener(statusListener);
        Engine.get().removeChatListener(chatListener);
        if (chatAudio != null) { chatAudio.stop(); chatAudio = null; }
        ticker.removeCallbacksAndMessages(null);
        io.shutdownNow();
        touchIo.shutdownNow();
        super.onDestroy();
    }
}
