# Remote Screen 双端系统跟踪

逐帧统计能拆开 capture→Agent、Agent→重组交付、解码排队、解码和显示，但 `Agent→重组交付` 仍混合了发送、Wi-Fi、内核收包队列、接收线程调度和重组。这个脚本用 Perfetto 在两端同时录制系统跟踪，补上调度信息；使用已安装的应用，不需要修改它。

## 采集

需要 Windows PowerShell 5.1 或 PowerShell 7 和 ADB。主控手机和被控设备都用 USB 连电脑，两者之间保留现有的无线 ADB / UDP 投屏连接。先在应用中开启“显示帧统计”并开始投屏，参数与要复现的场景一致。

```powershell
.\Capture-ScreenTrace.ps1 -PhoneSerial <主控序列号> -TargetSerial <被控序列号> -Adb adb.exe
```

脚本同时在两台设备上录制 30 秒（`-DurationSeconds` 可设 10–60）。提示出现后，连续滑动约 10 秒，抬手让惯性滚动约 10 秒，再交替短划和停顿直到结束。两端不是同时启动的，对齐时用各自 trace 的时钟快照，不能直接相减两台设备的 monotonic 时间。

默认在当前目录新建 `screen-system-trace-<日期时间>` 目录，`-OutputDirectory` 可指定一个不存在的目录。分析时保留整个目录：`manifest.json`、配置、两个 `.perfetto-trace` 和对应的 `*-perfetto.txt`。一端失败时，另一端的结果仍保留，错误写入 manifest。录制结果放在仓库已忽略的 `artifacts/` 下，不要提交。

## 兼容与失败

- 部分 Android 构建的 `perfetto --help` 打印帮助后返回 1。预检只对这一条命令接受 0 或 1，并核对 usage 和 `--config`、`--out`、`--txt` 选项；录制、拉取和摘要核对仍要求返回 0。
- `Both capture jobs launched` 只表示电脑上的后台任务已启动，不表示设备已开始录制。命令失败时终端显示最多 4096 字符的原始报错，完整输出在 `phone-perfetto.txt` 和 `target-perfetto.txt`。
- 配置文件放在 Android 12 起提供的 `/data/misc/perfetto-configs/`，输出在 `/data/misc/perfetto-traces/`。配置文件访问的 SELinux 限制见 [Perfetto 的 Android 配置说明](https://perfetto.dev/docs/concepts/config#android)。

## 内容与判断范围

- ftrace 调度 / 唤醒、CPU 频率 / idle，图形、视频、Binder 和运行时 atrace 分类。
- 进程 / 线程元数据、SurfaceFlinger FrameTimeline、`TicBoxProtocol` 日志。
- 可以看出手机接收线程是否长时间 runnable 而没被调度、是否在等网络，编码和转发线程的运行与等待，以及 GC 和 SurfaceFlinger 事件。
- 厂商 codec 内部、全部 Wi-Fi 重传和应用内 atrace 不保证可见。Release 应用和设备策略会限制 track；缺少某个 track 不说明没有卡顿。先检查 trace 内部的丢失计数和不支持的数据源，再下结论。

主环形缓冲 128 MiB，另有 4 MiB 元数据缓冲；长时间或事件很多时仍可能被覆盖，以 trace 内部的覆盖计数和实际保留区间为准。录制本身有开销，性能对比要在不录制时进行。系统 trace 含进程、包名和调度元数据，只做短时间复现。

脚本不改性能参数，不安装或启动应用，不清空日志，不改 ADB 配对，不需要 root。设备上只新增本次唯一命名的配置和 trace 文件；通过 `adb pull` 取回并核对 SHA-256 后删除远端文件。中断时可能留下这些文件。脚本不停止其他 Perfetto 会话，也不全局重置 ftrace；设备拒绝某个数据源时保留原始错误，不悄悄换采集方式。
