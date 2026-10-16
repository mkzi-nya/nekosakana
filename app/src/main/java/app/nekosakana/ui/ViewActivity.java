package app.nekosakana.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;

import app.nekosakana.core.FileSharer;
import app.nekosakana.core.Logx;

/** 查看收到的图片 / 视频 / 音频，或用其他应用打开。 */
public class ViewActivity extends Activity {
    private MediaPlayer player;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        String path = getIntent().getStringExtra("path");
        String kind = getIntent().getStringExtra("kind");
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 16));
        setContentView(root);

        if (path == null || !new File(path).exists()) {
            TextView t = Ui.text(this, "文件不存在", 16, Ui.DANGER, true);
            t.setGravity(Gravity.CENTER);
            root.addView(t, new LinearLayout.LayoutParams(-1, -1));
            return;
        }

        if ("image".equals(kind)) {
            ImageView image = new ImageView(this);
            image.setImageBitmap(BitmapFactory.decodeFile(path));
            image.setAdjustViewBounds(true);
            root.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
        } else if ("video".equals(kind)) {
            VideoView video = new VideoView(this);
            video.setVideoPath(path);
            MediaController controller = new MediaController(this);
            controller.setAnchorView(video);
            video.setMediaController(controller);
            root.addView(video, new LinearLayout.LayoutParams(-1, 0, 1));
            video.start();
        } else if ("audio".equals(kind)) {
            TextView label = Ui.text(this, "正在播放音频…", 16, Ui.TEXT, true);
            label.setGravity(Gravity.CENTER);
            root.addView(label, new LinearLayout.LayoutParams(-1, 0, 1));
            try {
                player = new MediaPlayer();
                player.setDataSource(path);
                player.prepare();
                player.start();
            } catch (Exception e) {
                Logx.e("view", "音频播放失败 " + e);
            }
        } else {
            openWithOtherApp(path);
            TextView label = Ui.text(this, "已尝试用其他应用打开。", 14, Ui.MUTED, false);
            label.setGravity(Gravity.CENTER);
            root.addView(label, new LinearLayout.LayoutParams(-1, -1));
        }
    }

    private void openWithOtherApp(String path) {
        try {
            File file = new File(path);
            Uri uri = FileSharer.uriFor(getPackageName(), file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "用其他应用打开"));
        } catch (Exception e) {
            Logx.e("view", "打开失败 " + e);
        }
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }
}
