# 09 · 开放 API

其他应用可通过本应用暴露的 `ContentProvider` 调用远程控制能力。

## 授权

`api/ApiProvider.java`，authority 为 `<包名>.api`，即 `app.nekosakana.api`。

调用需满足其一：

1. **同签名**：调用方与本应用签名一致（`getCallingPackage()` 校验首签名）。
2. **携带 token**：在 extras 中传入 `token` = 应用「设置」页展示的 `api_token`。

## 调用方法

```java
Bundle extras = new Bundle();
extras.putString("token", TOKEN);          // 或使用同签名
Bundle result = getContentResolver().call(
        Uri.parse("content://app.nekosakana.api"), method, arg, extras);
```

### method = `status`
返回连接状态。

| key | 类型 | 含义 |
| --- | --- | --- |
| `ok` | boolean | 是否成功 |
| `connected` | boolean | 是否已连接对方 |
| `text` | String | 状态文本 |
| `backend` | String | shell 后端（root/adb/app） |
| `missingPermissions` | int | 未授权项数量 |

### method = `exec`
`arg` 为 shell 命令，返回 `code/out/err`。

### method = `chat`
`arg` 为文本，发送给对方。

### method = `request`
`arg` 为 JSON 字符串（≥ 协议消息字段，须含 `t`）；可选 `extras.getByteArray("body")`。
返回：`ok`、`msg`、`json`（data 的 JSON 字符串）、`body`（二进制）。

```java
Bundle ex = new Bundle();
ex.putString("token", TOKEN);
String req = "{\"t\":\"devstate.get\"}";
Bundle r = resolver.call(Uri.parse("content://app.nekosakana.api"), "request", req, ex);
String data = r.getString("json");
```

可用于调用 [02-协议与链路](02-协议与链路.md) 中的任意请求类型，实现“有授权的应用远程控制对方设备”。

## 从电脑控制

由于 API 建立在 ContentProvider 之上，可：
- 通过 `adb shell content call --uri content://app.nekosakana.api --method status` 等调用；
- 或在电脑端通过 USB 调试直接 `adb shell` 执行命令与本应用 `exec` 联动。

> `adb shell content` 调用 `call` 的方式依赖系统版本；若不可用，可编写一个同签名的
> 电脑端辅助 APK/脚本经 USB（`adb forward`）与本应用通信。

## 安全建议

- token 具备完整控制能力，切勿泄露。
- 生产环境建议只依赖同签名授权，或为 token 增加轮换与失效机制。
