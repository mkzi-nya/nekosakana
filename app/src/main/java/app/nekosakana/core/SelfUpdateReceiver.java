package app.nekosakana.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 应用被覆盖安装（含通过「安装自己」升级）后自动重新打开并拉起服务。
 * {@code MY_PACKAGE_REPLACED} 由系统在替换本应用后发送给本应用自身，不受隐式广播限制；
 * 本应用持有 {@code SYSTEM_ALERT_WINDOW}，可从后台启动 Activity。
 */
public class SelfUpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        try {
            CoreService.start(context);
        } catch (Throwable t) {
            Logx.w("update", "更新后启动服务失败 " + t);
        }
        try {
            Intent open = new Intent(context, app.nekosakana.ui.MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(open);
            Logx.i("update", "更新完成，已自动打开应用");
        } catch (Throwable t) {
            Logx.w("update", "更新后自动打开失败 " + t);
        }
    }
}
