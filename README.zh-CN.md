# TicBox

[English](README.md) | 简体中文

TicBox 是 TicWatch 手表（TicWatch Pro 5、Pro 5 Enduro、Atlas）的本地优先手机伴侣应用，可以替代官方的 Mobvoi Health 手机端，并附带一套手机–手表工具箱。手机端要求 Android 14（API 34）及以上，手表端组件要求 API 30（Wear OS 3.5）及以上。

默认数据流是 **手表 → 本地蓝牙 → 手机 → 本地数据库**。Mobvoi 云、Google 云和手表上网代理默认关闭，只有用户明确开启后才使用。

## 功能

- 配对与首次设置、后台保持连接、保留数据的解除配对。
- 通知同步（动作、文字回复、按应用的声音和振动策略）、电话控制、媒体控制、表盘管理、设备设置、锁屏、紧急求助、互相查找。
- 健康数据：通过手表上的 Local Bridge 经专用蓝牙读取心率、睡眠、血氧等记录，保存在手机本地并绘制图表；root 后可读取完整的训练、GPS 路线和逐点数据，以及修改健康监测设置。
- 工具箱：经无线 ADB 或 root 的应用安装（含分包和数据包）、应用管理、文件管理、剪贴板、终端和远程屏幕。
- 勿扰同步、手机数据的导出与导入。

## 安装

请在电脑上用 ADB 安装手机 APK。直接在手机上打开 APK（从浏览器、聊天软件或文件管理器）可能会被拦截，提示“App blocked to protect your device”，只能点“Got it”。原因是 TicBox 需要通知访问权限，Google Play Protect 会拦截以这种方式安装、且申请该权限的应用；另外，本应用的开发者没有在 Android 开发者验证中登记。这两项检查都不适用于 ADB 安装。

1. 在手机上开启开发者选项，再开启 USB 调试（或无线调试），连接电脑。
2. 安装或更新 APK，`-r` 会保留应用数据：

   ```bash
   adb install -r ticbox-phone-1.0.0-release-<commit>.apk
   ```

3. 如果通知访问权限的开关是灰色的，打开本应用的“应用信息”，点右上角 ⋮ →“允许受限制的设置”，再开启通知访问。

手表上的 Local Bridge 不需要单独下载：它内置在手机 App 中，由手机经无线调试安装到手表（见[手表组件](docs/dev/watch.md)）。无论用哪种方式安装，它装好后都处于关闭状态，要在手表上打开一次并开启“允许手机读取”，在此之前不会响应手机。

## 文档

- [协议规范](docs/spec/README.md)：官方 Wear OS 伴侣协议和本应用私有协议的完整说明。
- [开发文档](docs/dev/README.md)：构建、架构、各项功能的实现和能力差距。

## 构建

需要 JDK 27、Node 26 和 Android SDK（详见[构建与检查](docs/dev/building.md)）：

```bash
npm run check
npm run android:check   # 需要 TICBOX_SIGNING_* 签名环境变量
```

## 许可

Copyright (C) 2026 TicBox contributors.

除另有声明的第三方材料外，本项目的原创代码和文档使用 **GNU General Public License version 3 only（GPL-3.0-only）**，完整条款见 [LICENSE](LICENSE)。第三方组件保留各自的许可和版权声明，包括[工具箱依赖](android/core/platform/src/main/assets/toolbox-licenses/NOTICE.txt)、[scrcpy 服务端](android/toolbox-resources/toolbox/SCRCPY-NOTICE.txt)和 [Material Symbols 图标](android/ui/src/main/res/raw/material_symbols_notice.txt)。

本项目与 Mobvoi、Google 没有关联。TicWatch 是 Mobvoi 的商标。
