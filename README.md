# nekosakana

Android 远程协助工具。两台设备通过应用内 Tailscale 建立点对点链路，用于屏幕查看、远程控制、文件传输、定位与设备状态查看等。

## 特点

- 链路基于应用内 Tailscale（Go netstack），不依赖系统 VPN、不创建 TUN。
- 提权优先级：Root > Shizuku/ADB > 应用自身权限；无提权时可用无障碍服务完成点击、滑动与文字输入。
- 应用数据存于应用外部私有目录 `Android/data/app.nekosakana/files`。
- 提供开放 API（ContentProvider），供其他应用在同一设备上调用。
- 启动即运行前台服务，维持连接；厂商 ROM 杀服务后有看门狗恢复。

## 兼容性

- 仅打包 arm64-v8a。
- minSdk 29（Android 10），compileSdk/targetSdk 35。
- 在 ColorOS 等定制系统上，部分后台策略需手动放行。

## 构建

需要 Android SDK（含 platform 35）与 JDK 17。

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`。
`assembleDebug` 使用本机默认 debug 签名；正式签名请自行配置 `signingConfigs`。

## 目录

- `app/` 应用源码
- `docs/` 文档，从 `docs/01-架构总览.md` 开始
- `native/` `libtailscale.aar` 的来源与重建说明

`app/libs/libtailscale.aar` 为基于 [tailscale-android](https://github.com/tailscale/tailscale-android) 构建的原生库，改动与重建方式见 `native/`。
