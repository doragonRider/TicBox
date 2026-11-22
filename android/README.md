# Android 工程

| 模块 | 职责 |
|---|---|
| `app` | 唯一的手机 APK：Activity、服务和系统授权的宿主接线 |
| `ui` | Compose 页面、视图模型、设计系统与导航 |
| `demo` | 演示手表：`core:api` 客户端的内存实现 |
| `core:api` | 界面使用的类型、StateFlow、操作、错误和取消语义 |
| `core:protocol` | 无 Android 依赖的字节编解码和业务格式 |
| `core:engine` | 连接、Channel、Setup、DataApi、伴侣和健康业务的状态机 |
| `core:platform` | Android 蓝牙、CDM、通知、电话、媒体、SQLite、服务和后台恢复 |
| `thirdparty:kadb` | 固定版本的 Kadb（无线 ADB），见其 README |
| `watch:healthbridge` | 手表 Local Bridge APK |
| `watch:toolboxagent` | 工具箱 Agent，打包进手表 APK |
| `watch:healthmodule` | 可选的 Magisk / Zygisk 模块 |

另有 `thirdparty/scrcpy-server`（固定的 scrcpy 4.1 服务端源码、补丁和构建工具）和 `toolbox-resources`（构建好的服务端与许可）。

手机最低 API 34，手表最低 API 30。常用命令：

```bash
./gradlew :core:protocol:test :core:engine:test --console=plain
./gradlew :core:platform:testReleaseUnitTest :watch:healthbridge:testReleaseUnitTest --console=plain
./gradlew spotlessCheck :ui:testReleaseUnitTest :demo:testReleaseUnitTest --console=plain
```

全量检查、签名和发布见[构建与检查](../docs/dev/building.md)，模块边界和接口见[架构](../docs/dev/architecture.md)。
