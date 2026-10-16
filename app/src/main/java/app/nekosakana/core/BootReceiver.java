package app.nekosakana.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import app.nekosakana.net.TailscaleController;

/** 开机自启（受设置控制）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Config.getBool(Config.K_AUTOSTART, true)) {
            CoreService.start(context);
            if (Config.tailnetMode() && Config.getBool("tailscale_authenticated", false)) {
                TailscaleController.connect(context, null);
            }
            Watchdog.schedule(context);
            Logx.i("boot", "开机自启服务");
        }
    }
}
