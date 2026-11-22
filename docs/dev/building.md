# 构建与检查

开发只需要 Git 中的源码和工具链，不需要 Android Studio、模拟器、官方 APK 或任何逆向工具。

## 1. 工具链

| 组件 | 版本 |
|---|---|
| JDK | 27 |
| Node | 26 或更新（`package.json` 要求 >= 26） |
| Gradle | Wrapper 9.8.2，不需要全局安装 |
| Android Gradle Plugin / Kotlin | 9.5.1 / 2.4.21 |
| Android SDK | `platforms;android-37.2`、`build-tools;37.0.0` |
| NDK | r27c `27.2.12479018`（PTY、投屏加速和进程内模块的原生代码；全量检查需要） |
| 主机 C++ 编译器 | 运行原生代码的主机测试 |

库的版本以 [libs.versions.toml](../../android/gradle/libs.versions.toml) 和各模块的配置为准。依赖升级单独提交并按实际兼容性验证。

在用户可写的目录安装 JDK、Node 和 Android command-line tools：

```bash
export JAVA_HOME="$HOME/tools/jdk-27"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-37.2" "build-tools;37.0.0" "platform-tools" "ndk;27.2.12479018"
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
```

Windows 使用对应的发行包，在 PowerShell 中设置 `JAVA_HOME`、`ANDROID_HOME` 和 `Path`，运行 `sdkmanager.bat`。也可以在 `android/local.properties`（不提交）中用 `sdk.dir` 指定 SDK。Node 工具只使用内置模块，不需要 `npm install`；Gradle 首次构建会下载依赖。

## 2. 检查

在仓库根目录：

```bash
npm run check          # Node 编解码测试 + 文档链接检查
npm run android:check  # 全量 Android 检查（release）
npm run android:package
```

`android:check` 运行 release 的 `backendCheck`：各模块的单元测试、Android Lint、Spotless、手机和手表 APK 的构建、进程内模块的原生构建和 ZIP 校验；另外运行手表 debug 专用的测试，但不交付 debug APK。随后检查实际 APK 的变体、版本、包名、最低 API、两端是同一预期证书、release 清单中没有开发页面、手表 APK 没有 INTERNET 权限，以及手机内嵌的 `assets/watch/local-bridge.apk` 与同一轮的手表 APK 逐字节一致、Agent 入口没有被 R8 删除、scrcpy 载荷与固定来源一致。成功后写入（被忽略的）`build/verification/android-release.json`，绑定所有源码和 APK 的 SHA-256。额外参数传给 Gradle，例如 `npm run android:check -- --max-workers=1`。

生产 SQLite 的 DDL 变化时，另外运行 `npm run android:sqlite-check`。

日常的小改动只需运行相关模块的任务：

```bash
./android/gradlew -p android :core:protocol:test :core:engine:test --console=plain
./android/gradlew -p android :core:platform:testReleaseUnitTest :watch:healthbridge:testReleaseUnitTest --console=plain
./android/gradlew -p android spotlessCheck :ui:testReleaseUnitTest :demo:testReleaseUnitTest :ui:lintRelease --console=plain
```

没有 NDK 时，`:watch:toolboxagent` 的任务需要加 `-x buildPtyNative -x packagePtyAssets`；它的主机原生测试仍可用系统的 C++ 编译器构建。同一个工作区不要同时运行多个会写输出的 Gradle 任务。

每台机器可以在仓库根目录放一个自己的 `build.sh`，写上适合本机的命令（环境变量、`--max-workers`、要跳过的任务等）。它已在 `.gitignore` 中，不会被提交，例如：

```bash
#!/bin/sh
set -e
export TICBOX_SIGNING_STORE_FILE="$HOME/keys/ticbox.jks"
npm run android:check -- --max-workers=4
```

确实需要 debug 构建时：

```bash
npm run android:check -- --debug
npm run android:package -- debug
```

## 3. 产物与签名

| 产物 | 路径 | 最低版本 |
|---|---|---|
| 手机 App | `android/app/build/outputs/apk/release/app-release.apk` | Android 14（API 34） |
| 手表 Local Bridge | `android/watch/healthbridge/build/outputs/apk/release/healthbridge-release.apk` | API 30 |
| 进程内模块 ZIP | `android/watch/healthmodule/build/distributions/ticbox-healthmodule-<版本>.zip` | Magisk 26+ / Zygisk |

两端使用相同的 applicationId `dev.ticbox.companion` 和同一把签名密钥，这是本应用私有路由的前提。覆盖安装必须保留签名，不能用卸载或清除数据来解决签名不匹配。

release 构建从环境变量读取签名，缺少任何一项都会失败，不会退回临时签名：

| 变量 | 内容 |
|---|---|
| `TICBOX_SIGNING_STORE_FILE` | keystore 的绝对路径 |
| `TICBOX_SIGNING_STORE_PASSWORD` | keystore 密码 |
| `TICBOX_SIGNING_KEY_ALIAS` | 私钥别名 |
| `TICBOX_SIGNING_KEY_PASSWORD` | 私钥密码 |
| `TICBOX_SIGNING_CERT_SHA256` | 预期证书的 SHA-256（可带冒号） |

发布版本的证书 SHA-256 为 `90cb883f02e88974c7bd586b9aa047b1f33ef41039638a894d483c62476a9e99`，可以用来核对下载的 APK。密钥和密码不能进入 Git、CI 日志或环境脚本。

`android:package` 复核 APK、检查回执、当前源码的摘要和干净的 checkout，在 `build/distributions/ticbox-<版本>-release-<短 commit>/` 生成两个 APK、`SHA256SUMS.txt`、`README.txt` 和绑定源码提交、变体、版本、证书和 APK 摘要的 `manifest.json`。它不重新签名，也不安装或连接设备。

release 启用 R8 和资源收缩，只压缩和优化，不混淆类名。新增按类名启动或反射加载的代码时，要同步保留规则：[proguard-common.pro](../../android/proguard-common.pro)、[手机规则](../../android/app/proguard-rules.pro)和[手表规则](../../android/watch/healthbridge/proguard-rules.pro)（root 入口在手表规则中）。两端固定 `ndkVersion=27.2.12479018`，使原生库的调试符号被正常剥离，保留 JNI 导出和 16 KiB 段对齐。

## 4. 版本与发布

版本号统一在 [android/version.properties](../../android/version.properties)（`versionName`、`versionCode`），发布时同步修改 `package.json` 的版本，并在 `docs/releases/<版本>.md` 写更新说明。tag 必须与 APK 的版本一致。

GitHub Actions 需要以下 Repository secrets：

| Secret | 内容 |
|---|---|
| `TICBOX_SIGNING_STORE_BASE64` | keystore 文件的 Base64（含私钥） |
| `TICBOX_SIGNING_STORE_PASSWORD` | keystore 密码 |
| `TICBOX_SIGNING_KEY_ALIAS` | 私钥别名 |
| `TICBOX_SIGNING_KEY_PASSWORD` | 私钥密码 |
| `TICBOX_SIGNING_CERT_SHA256` | 预期证书指纹 |

Linux 可用 `base64 -w0 <keystore>` 生成，Windows 可用 `[Convert]::ToBase64String([IO.File]::ReadAllBytes('<keystore>')) | Set-Clipboard`。Base64 不是加密。

| 工作流 | 触发 | 作用 |
|---|---|---|
| [signed-package.yml](../../.github/workflows/signed-package.yml) | 推送 `v*` tag；或手动 | tag：release 检查、构建、打包，通过后先建草稿并上传资产，再发布 GitHub Release（说明取 `docs/releases/<版本>.md`）。手动：只上传 Actions 产物 |
| [ci.yml](../../.github/workflows/ci.yml) | 手动 | debug 构建，不接收签名 secrets |
| [android-build.yml](../../.github/workflows/android-build.yml) | 被调用 | 共享的构建步骤，报告保留 14 天；签名文件以 0600 写入临时目录，结束时删除 |

普通的提交、push 和 PR 不触发构建。发布：

```bash
git tag -a v1.0.0 -m "Release 1.0.0"
git push origin refs/tags/v1.0.0
```

构建 job 只有 `contents:read`；发布 job 只在 tag push 后运行，不接收签名私钥。所有 Actions 固定到完整的 commit SHA。

## 5. 数据库与格式的兼容

手机的 `data-sync.sqlite`（[DataSyncSqlSchema.VERSION](../../android/core/platform/src/main/kotlin/dev/ticbox/companion/platform/data/DataSyncSqlSchema.kt)）的当前版本是 1，是正式的基线。手表的 `health-incremental.sqlite`（[HealthIncrementalStore](../../android/watch/healthbridge/src/main/kotlin/dev/ticbox/companion/healthbridge/HealthIncrementalStore.kt)）以版本 1 为基线，1.0.9 升到 2（每张表可以有多个等待确认的批次），带从 1 升级的迁移。

修改任何一个数据库的表、列或索引，都必须提高版本，在 `onUpgrade` 中写迁移，并用旧版本的库测试升级；不能靠删库、清数据或卸载解决，也不能不升版本直接改 DDL。两个 `onUpgrade` 只在缺少迁移时报错，不会删除数据；降级时 SQLite 拒绝打开更高版本的库，同样不删除。手机库的表或列变化，以及设置、身份等文件的格式变化，还必须同步更新[数据迁移](data-migration.md)，保证旧版本导出的文件能导入新版本。

## 6. 代码风格

- `ui`、`app`、`demo` 和手表的 `ui` 使用 Spotless + ktfmt（kotlinlang 风格，100 列）；`core/*` 和 Agent 使用紧凑的写法，不经过 ktfmt。
- 任何一行都不超过 200 个字符。
- 代码注释使用英文；文档使用中文。
- 图标使用 Material Symbols；导航使用 navigation-compose 的预测性返回。
- 界面文字不写免责声明；界面的变化要有动画，避免突然跳动。

## 7. 隐私与测试数据

仓库是公开的，以下内容不能进入 Git、Actions 产物、构建日志或 APK 的测试资源：logcat 和 ADB 输出、健康 JSON、数据库及 WAL、蓝牙或网络抓包、分区镜像、设备诊断、官方 APK 或固件、反编译的代码、签名密钥。`.gitignore` 排除了这些目录，但忽略规则不能清除已经提交的内容，所以提交和推送前要检查**整个**待推送的提交范围。

健康导出的“标识已匿名化”只表示那几个字段被替换了，健康数值、时间和轨迹仍然是真实的，不能整份提交。

可以提交的是协议结论：字段、类型编号、状态码、单位、帧格式和能力。测试使用独立构造的合成数据，保留验证协议所需的结构、边界和精度；不能把真实数据改个名字、替换账号或平移时间后称为合成数据。日志中不得出现健康数值、密码、ADB 配对码、剪贴板、终端内容、SOS 号码或联系人姓名，这些内容也不能写入 SavedState。

## 8. 协议维护

规范正文给出字节格式、状态机、路由、单位和未知项；[schemas](../../schemas) 中的 `.proto` 是字段描述，Kotlin 和 Node 的编解码器是手写的，不需要 protoc。新发现的格式配合最小的合成向量一起提交。不要把官方实现的反编译代码复制进源码。
