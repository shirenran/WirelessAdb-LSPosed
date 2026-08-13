# WirelessAdb-LSPosed

**语言 / Idioma / Language:** [简体中文](README.md) · [Português (Brasil)](README.pt-BR.md) · [English](README.en.md) · [Español](README.es.md)

**LSPosed 模块**：开机首次解锁后自动开启无线 ADB，并在状态页查看地址与日志。

包名：`dev.wirelessadb.autostart`  
当前版本：`1.0.18`

<p align="center">
  <img src="docs/screenshot.png" alt="无线 ADB 自启状态页" width="360" />
</p>

## 功能

- **TLS 模式**：系统「无线调试」（`adb_wifi_enabled`），端口随机，需配对
- **TCP 模式**：等价 `adb tcpip <port>`，默认 `5555`，电脑可直接 `adb connect IP:5555`
- 监听无线调试关闭（仅 TLS）、Wi‑Fi 恢复、亮屏，必要时重新开启
- 地址变化时自动复制到剪贴板（等待微信输入法跨设备粘贴就绪）
- 获取 IP 时跳过 `172.19.*` VPN 虚拟地址
- 开机早期日志写入 `Settings.Global`，状态页可查看

## 安装

1. 安装 Release APK
2. 在 **LSPosed** 中启用本模块
3. 作用域选择 **系统框架**（`android`）
4. 重启手机并完成首次解锁

> 仅建议在可信局域网使用。切换 TCP 会重启 adbd，当前 ADB 会话可能断开。

## 构建

```bash
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

需要本机已配置 Android SDK（`local.properties` 中的 `sdk.dir`，或环境变量 `ANDROID_HOME`）。

## 许可

MIT
