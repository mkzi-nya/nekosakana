package app.nekosakana.caps;

import android.content.Context;
import android.media.MediaPlayer;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.nekosakana.core.Logx;
import app.nekosakana.ui.Ui;

/**
 * 页面内联音频播放条：在当前页面直接播放，不跳转其他界面。
 * 可复用于会话页与悬浮窗。
 */
public final class AudioBar {
    private final Context context;
    private final LinearLayout row;
    private final TextView title;
    private final Button toggle;
    private MediaPlayer player;
    private String path;
    private boolean prepared;
    private boolean wantPlay;

    public AudioBar(Context c) {
        this.context = c;
        row = Ui.row(c);
        row.setBackground(Ui.round(Ui.SURFACE2, 10, c));
        row.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
        title = Ui.text(c, "", 13, Ui.TEXT, false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        toggle = Ui.button(c, "▶", Ui.SURFACE);
        toggle.setTextColor(Ui.TEXT);
        row.addView(toggle, Ui.lp(c, 52, 40, 8, 0, 0, 0));
        toggle.setOnClickListener(v -> {
            if (player == null || !prepared) return;
            if (player.isPlaying()) pause(); else play();
        });
        row.setVisibility(View.GONE);
    }

    public View view() {
        return row;
    }

    public void toggle(String file, String name) {
        if (file == null || file.isEmpty()) return;
        if (file.equals(path) && player != null) {
            if (player.isPlaying()) pause(); else play();
            return;
        }
        releasePlayer();
        path = file;
        prepared = false;
        wantPlay = true;
        row.setVisibility(View.VISIBLE);
        title.setText("加载中… " + (name == null ? "" : name));
        toggle.setText("▶");
        try {
            player = new MediaPlayer();
            player.setDataSource(file);
            player.setOnPreparedListener(mp -> {
                prepared = true;
                if (wantPlay) {
                    mp.start();
                    toggle.setText("⏸");
                }
            });
            player.setOnCompletionListener(mp -> {
                wantPlay = false;
                toggle.setText("▶");
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Logx.e("audio", "内联播放失败 " + file + " (" + what + "/" + extra + ")");
                wantPlay = false;
                prepared = false;
                title.setText("无法播放：" + name + "（" + what + "/" + extra + "）");
                toggle.setText("▶");
                return true;
            });
            player.prepareAsync();
        } catch (Throwable t) {
            Logx.e("audio", "内联播放异常 " + t);
            title.setText("无法播放：" + name + "（" + t + "）");
        }
    }

    private void play() {
        if (player == null) return;
        wantPlay = true;
        if (prepared) {
            try {
                player.start();
                toggle.setText("⏸");
            } catch (Throwable t) {
                Logx.w("audio", "resume 失败 " + t);
            }
        }
    }

    private void pause() {
        wantPlay = false;
        if (player != null && prepared) {
            try {
                if (player.isPlaying()) player.pause();
            } catch (Throwable ignored) {
            }
            toggle.setText("▶");
        }
    }

    public boolean isPlaying() {
        try {
            return player != null && prepared && player.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.reset(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
        }
        player = null;
        prepared = false;
        wantPlay = false;
    }

    public void stop() {
        releasePlayer();
        path = null;
        if (row != null) row.setVisibility(View.GONE);
    }
}
