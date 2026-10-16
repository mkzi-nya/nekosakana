# 应用内 Tailscale 原生库

本目录保存 `app/libs/libtailscale.aar` 的修改来源。Android 系统不创建 TUN，也不注册 VpnService；应用的 TCP 监听和拨号全部使用 Go netstack。

上游：`https://github.com/tailscale/tailscale-android`，提交 `264102cc702fbd844e900227ee02e5cbb4d7f801`。Tailscale 模块版本见该提交的 `go.mod`。

在该上游源码中应用 `backend.patch`，把 `app_dial.go` 放入 `libtailscale/`，然后执行下列命令。需要上游指定的 Go 工具链、gomobile/gobind、Android SDK 与 NDK；本次使用 NDK 27.2.12479018。

```sh
gomobile bind -target android/arm64 -androidapi 29 \
  -tags "$(./build-tags.sh) ts_omit_cachenetmap" \
  -ldflags "-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384 $(./version-ldflags.sh)" \
  -o libtailscale.aar ./libtailscale
```

将结果放入 `app/libs/libtailscale.aar` 后运行 `./gradlew assembleDebug`。当前交付仅支持 arm64-v8a，避免生成缺少原生库的其他架构 APK。

关键修复：`ProcessLocalIPs=true` 将本机 Tailnet 流量交给应用内监听器；JNI 读取返回 byte[]，不依赖 Go 修改 Java 输入数组；应用协议在连接后立即交换 hello；读取超时使失效连接能够自动重试。版本戳必须保留，否则 Tailscale 会在启动时崩溃。
