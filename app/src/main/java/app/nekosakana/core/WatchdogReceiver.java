package app.nekosakana.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 看门狗闹钟接收器：恢复核心服务并重新排程（见 {@link Watchdog}）。 */
public class WatchdogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Watchdog.tick(context);
    }
}
