package app.nekosakana.caps;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import app.nekosakana.App;
import app.nekosakana.core.Logx;
import app.nekosakana.engine.Engine;
import app.nekosakana.ui.MainActivity;
import app.nekosakana.ui.Ui;

/** One conversation window; new messages update it without replacing the composer. */
public final class Overlay {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static View dialog;
    private static LinearLayout messages;
    private static ScrollView scroll;
    private static AudioBar audioBar;
    private static FrameLayout videoHolder;
    private Overlay() { }
    public interface OpenHandler { void onOpen(); }
    public static boolean canDraw(Context c) { return Settings.canDrawOverlays(c); }

    public static void message(String title, String body, OpenHandler onOpen) {
        MAIN.post(() -> {
            Context c = App.get();
            if (MainActivity.chatVisible()) return;
            if (!canDraw(c)) { Logx.w("overlay", "无悬浮窗权限"); return; }
            try {
                if (dialog == null) create(c, onOpen);
                refresh(c, false);
            } catch (Throwable t) { Logx.e("overlay", "展示失败 " + t); dismissNow(); }
        });
    }

    private static void create(Context c, OpenHandler onOpen) {
        LinearLayout card = Ui.column(c);
        card.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 10));
        card.setBackground(Ui.round(Ui.SURFACE, 14, c));
        card.setElevation(Ui.dp(c, 16));
        LinearLayout header = Ui.row(c);
        TextView title = Ui.text(c, "nekosakana · 对话", 14, Ui.ACCENT, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = Ui.button(c, "关闭", Ui.SURFACE2); close.setTextColor(Ui.TEXT);
        header.addView(close, Ui.lp(c, 64, 36, 0, 0, 0, 0));
        card.addView(header);
        scroll = new ScrollView(c);
        messages = Ui.column(c);
        scroll.addView(messages);
        card.addView(scroll, Ui.lp(c, -1, 230, 0, 8, 0, 8));

        videoHolder = new FrameLayout(c);
        videoHolder.setVisibility(View.GONE);
        card.addView(videoHolder, Ui.lp(c, -1, -2, 0, 0, 0, 6));

        audioBar = new AudioBar(c);
        card.addView(audioBar.view(), Ui.lp(c, -1, -2, 0, 0, 0, 6));

        LinearLayout compose = Ui.row(c);
        EditText input = Ui.input(c, "输入消息");
        compose.addView(input, new LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1));
        Button send = Ui.button(c, "发送");
        compose.addView(send, Ui.lp(c, 64, 44, 6, 0, 0, 0));
        card.addView(compose);
        Button open = Ui.button(c, "打开会话", Ui.SURFACE2); open.setTextColor(Ui.TEXT);
        card.addView(open, Ui.lp(c, -1, 38, 0, 6, 0, 0));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.min(c.getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 24), Ui.dp(c, 460)),
                -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = Ui.dp(c, 40);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        input.setOnTouchListener((v, event) -> {
            if ((lp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                wm.updateViewLayout(card, lp);
                input.post(() -> {
                    input.requestFocus();
                    ((android.view.inputmethod.InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE))
                            .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                });
            }
            return false;
        });
        close.setOnClickListener(v -> dismissNow());
        open.setOnClickListener(v -> {
            dismissNow();
            Intent intent = new Intent(c, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("page", "会话");
            c.startActivity(intent);
            if (onOpen != null) onOpen.onOpen();
        });
        send.setOnClickListener(v -> {
            String value = input.getText().toString();
            if (value.trim().isEmpty()) return;
            Engine.get().sendChat(value);
            input.setText("");
            refresh(c, true);
            input.requestFocus();
        });
        wm.addView(card, lp);
        dialog = card;
        refresh(c, true);
    }

    private static void refresh(Context c, boolean forceBottom) {
        boolean atBottom = forceBottom || scroll.getScrollY() + scroll.getHeight() + Ui.dp(c, 24) >= messages.getHeight();
        messages.removeAllViews();
        for (Chat.Msg msg : Chat.history(300)) {
            messages.addView(bubble(c, msg), Ui.lp(c, -1, -2, 0, 0, 0, 6));
        }
        messages.post(() -> { if (dialog != null && atBottom) scroll.scrollTo(0, messages.getHeight()); });
    }

    private static View bubble(Context c, Chat.Msg msg) {
        LinearLayout box = Ui.column(c);
        box.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8));
        box.setBackground(Ui.round(msg.out ? 0xFF20443C : Ui.SURFACE2, 8, c));
        TextView head = Ui.text(c, msg.out ? "我" : "对方", 11, msg.out ? Ui.ACCENT : Ui.WARN, true);
        box.addView(head);

        boolean text = msg.kind == null || "text".equals(msg.kind);
        if (text) {
            if (msg.text != null && !msg.text.isEmpty()) {
                TextView t = Ui.text(c, msg.text, 14, Ui.TEXT, false);
                t.setPadding(0, Ui.dp(c, 4), 0, 0);
                box.addView(t);
            }
            return box;
        }

        java.io.File file = (msg.file != null && !msg.file.isEmpty()) ? new java.io.File(msg.file) : null;
        boolean hasFile = file != null && file.exists();
        String name = (msg.text != null && !msg.text.isEmpty()) ? msg.text : (file != null ? file.getName() : "");

        if (hasFile && ("image".equals(msg.kind) || "video".equals(msg.kind))) {
            Bitmap thumb = "video".equals(msg.kind) ? videoThumb(msg.file) : decodeThumb(msg.file, Ui.dp(c, 360));
            if (thumb != null) {
                ImageView image = new ImageView(c);
                image.setImageBitmap(thumb);
                image.setAdjustViewBounds(true);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setMaxHeight(Ui.dp(c, 180));
                if ("video".equals(msg.kind)) {
                    FrameLayout wrap = new FrameLayout(c);
                    wrap.addView(image, new FrameLayout.LayoutParams(-2, -2));
                    TextView play = Ui.text(c, "▶", 22, 0xFFFFFFFF, true);
                    play.setGravity(Gravity.CENTER);
                    play.setBackground(Ui.round(0x66000000, 26, c));
                    wrap.addView(play, new FrameLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 52), Gravity.CENTER));
                    wrap.setOnClickListener(v -> playVideo(c, msg.file));
                    box.addView(wrap, Ui.lp(c, -2, -2, 0, 6, 0, 0));
                } else {
                    image.setOnClickListener(v -> {
                        dismissNow();
                        openInViewer(c, msg.file, msg.kind);
                    });
                    box.addView(image, Ui.lp(c, -2, -2, 0, 6, 0, 0));
                }
            }
        }
        if (hasFile && "audio".equals(msg.kind)) {
            Button play = Ui.button(c, "▶ 播放音频", Ui.SURFACE);
            play.setTextColor(Ui.TEXT);
            play.setOnClickListener(v -> {
                if (audioBar != null) audioBar.toggle(msg.file, name);
            });
            box.addView(play, Ui.lp(c, -2, 40, 0, 6, 0, 0));
        }

        String icon = "image".equals(msg.kind) ? "图片" : "video".equals(msg.kind) ? "视频"
                : "audio".equals(msg.kind) ? "音频" : "文件";
        String size = hasFile ? " · " + humanSize(file.length()) : "";
        TextView label = Ui.text(c, icon + "：" + name + size, 12, hasFile ? Ui.ACCENT : Ui.MUTED, true);
        label.setPadding(0, Ui.dp(c, 6), 0, 0);
        if (hasFile && "file".equals(msg.kind)) {
            label.setOnClickListener(v -> {
                dismissNow();
                openInViewer(c, msg.file, msg.kind);
            });
        }
        box.addView(label);
        return box;
    }

    private static void playVideo(Context c, String path) {
        if (videoHolder == null) return;
        videoHolder.removeAllViews();
        VideoView video = new VideoView(c);
        videoHolder.addView(video, new FrameLayout.LayoutParams(-1, Ui.dp(c, 220)));
        MediaController controller = new MediaController(c);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        video.setVideoPath(path);
        video.setOnPreparedListener(mp -> video.start());
        videoHolder.setVisibility(View.VISIBLE);
        video.start();
    }

    private static void openInViewer(Context c, String path, String kind) {
        try {
            Intent intent = new Intent(c, app.nekosakana.ui.ViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra("path", path);
            intent.putExtra("kind", kind);
            c.startActivity(intent);
        } catch (Throwable t) {
            Logx.e("overlay", "打开文件失败 " + t);
        }
    }

    private static Bitmap decodeThumb(String path, int maxPx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int sample = 1;
            int longer = Math.max(bounds.outWidth, bounds.outHeight);
            while (longer / sample > maxPx) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, options);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap videoThumb(String path) {
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

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    public static void dismissAll() { MAIN.post(Overlay::dismissNow); }
    private static void dismissNow() {
        if (audioBar != null) { try { audioBar.stop(); } catch (Throwable ignored) { } }
        audioBar = null;
        if (videoHolder != null) {
            try { ((android.widget.VideoView) videoHolder.getChildAt(0)).stopPlayback(); } catch (Throwable ignored) { }
        }
        videoHolder = null;
        if (dialog != null) {
            try { ((WindowManager) App.get().getSystemService(Context.WINDOW_SERVICE)).removeView(dialog); }
            catch (Throwable ignored) { }
        }
        dialog = null; messages = null; scroll = null;
    }
}
