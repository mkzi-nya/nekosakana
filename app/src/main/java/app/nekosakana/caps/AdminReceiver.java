package app.nekosakana.caps;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

import app.nekosakana.core.ControlLog;
import app.nekosakana.core.Logx;

/** 设备管理员接收器。用于锁屏、停用等能力。 */
public class AdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        Logx.i("admin", "设备管理员已启用");
        ControlLog.add("sys", "admin", "设备管理员已启用");
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Logx.w("admin", "设备管理员已停用");
        ControlLog.add("sys", "admin", "设备管理员已停用");
    }

    @Override
    public void onPasswordFailed(Context context, Intent intent) {
        ControlLog.add("sys", "admin", "锁屏密码错误");
    }
}
