package app.nekosakana.api;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONObject;

import app.nekosakana.caps.PermsX;
import app.nekosakana.caps.ShellX;
import app.nekosakana.core.Config;
import app.nekosakana.core.ControlLog;
import app.nekosakana.core.Logx;
import app.nekosakana.engine.Engine;
import app.nekosakana.net.NetHub;

/**
 * 开放 API。其他作者应用可通过 {@code content://<包名>.api} 调用 {@link #call}。
 *
 * <p>授权方式二选一：调用方与本应用同签名，或在 extras 中携带正确的 api_token。
 * 具体方法见 docs/09-开放API.md。</p>
 */
public class ApiProvider extends ContentProvider {
    public static final String AUTHORITY_SUFFIX = ".api";
    private static final String EXTRA_TOKEN = "token";
    private static final String EXTRA_JSON = "json";
    private static final String EXTRA_BODY = "body";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        Context context = getContext();
        if (context == null) {
            result.putBoolean("ok", false);
            result.putString("error", "no context");
            return result;
        }
        if (!isAuthorized(context, extras)) {
            result.putBoolean("ok", false);
            result.putString("error", "unauthorized");
            return result;
        }
        try {
            switch (method) {
                case "status": {
                    result.putBoolean("ok", true);
                    result.putBoolean("connected", NetHub.get().connected());
                    result.putString("text", NetHub.get().statusText());
                    result.putString("backend", ShellX.name(ShellX.backend()));
                    result.putInt("missingPermissions", PermsX.missingCount(context));
                    return result;
                }
                case "exec": {
                    ShellX.Result r = ShellX.exec(arg == null ? "" : arg);
                    result.putBoolean("ok", r.ok());
                    result.putInt("code", r.code);
                    result.putString("out", r.out);
                    result.putString("err", r.err);
                    return result;
                }
                case "chat": {
                    Engine.get().sendChat(arg == null ? "" : arg);
                    result.putBoolean("ok", true);
                    return result;
                }
                case "push_apk": {
                    final String path = arg == null ? "" : arg.trim();
                    new Thread(() -> {
                        try {
                            java.io.File file = new java.io.File(path);
                            if (!file.isFile()) {
                                Logx.w("api", "push_apk 文件不存在 " + path);
                                return;
                            }
                            byte[] data = new byte[(int) file.length()];
                            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                                int off = 0;
                                while (off < data.length) {
                                    int n = in.read(data, off, data.length - off);
                                    if (n < 0) break;
                                    off += n;
                                }
                            }
                            JSONObject payload = new JSONObject();
                            payload.put("name", file.getName());
                            Logx.i("api", "push_apk 开始推送 " + file.getName() + " (" + data.length + " 字节)");
                            Engine.Reply reply = Engine.get().request("install", payload, data, 120000);
                            Logx.i("api", "push_apk 结果 " + (reply.ok ? "成功 " : "失败 ") + reply.message);
                            ControlLog.add("out", "api.push_apk", (reply.ok ? "ok" : "fail") + " " + reply.message);
                        } catch (Throwable t) {
                            Logx.w("api", "push_apk 异常 " + t);
                        }
                    }, "push-apk").start();
                    result.putBoolean("ok", true);
                    result.putString("msg", "已开始推送安装包到对端：" + path);
                    return result;
                }
                case "request": {
                    JSONObject request = new JSONObject(arg == null ? "{}" : arg);
                    String type = request.optString("t");
                    byte[] body = extras == null ? null : extras.getByteArray(EXTRA_BODY);
                    Engine.Reply reply = Engine.get().request(type, request, body, request.optLong("timeout", 8000));
                    result.putBoolean("ok", reply.ok);
                    result.putString("msg", reply.message);
                    if (reply.data != null) result.putString(EXTRA_JSON, reply.data.toString());
                    if (reply.body != null) result.putByteArray(EXTRA_BODY, reply.body);
                    ControlLog.add("in", "api.request", type);
                    return result;
                }
                default:
                    result.putBoolean("ok", false);
                    result.putString("error", "unknown method " + method);
                    return result;
            }
        } catch (Exception e) {
            Logx.e("api", "调用失败 " + e);
            result.putBoolean("ok", false);
            result.putString("error", String.valueOf(e));
            return result;
        }
    }

    private boolean isAuthorized(Context context, Bundle extras) {
        if (extras != null) {
            String token = extras.getString(EXTRA_TOKEN);
            if (token != null && token.equals(Config.apiToken())) return true;
        }
        return sameSignature(context);
    }

    private boolean sameSignature(Context context) {
        try {
            String caller = getCallingPackage();
            if (caller == null) return false;
            PackageManager pm = context.getPackageManager();
            Signature[] mine = signatures(pm, context.getPackageName());
            Signature[] theirs = signatures(pm, caller);
            if (mine == null || theirs == null || mine.length == 0 || theirs.length == 0) return false;
            return mine[0].equals(theirs[0]);
        } catch (Exception e) {
            return false;
        }
    }

    private Signature[] signatures(PackageManager pm, String pkg) {
        try {
            android.content.pm.PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            return info.signatures;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
