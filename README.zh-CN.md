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
