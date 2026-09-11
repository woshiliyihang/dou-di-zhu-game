# vTrans 生产级代码走查报告

范围：`TranslateService` / `AudioCapture` / `VoicePreprocessor` / `AsrEngine` / `MtEngine` / `VadSegmenter` / `ModelManager` / `MainActivity` / `SettingsActivity` 全链路。本报告含本轮已修复项与遗留建议。

## 已修复（本轮落地，编译/lint 通过）

| # | 严重度 | 位置 | 问题 | 修复 |
|---|---|---|---|---|
| 1 | P0 | `TranslateService.onStartCommand` | `startAsForeground` 未兜底 `ForegroundServiceStartNotAllowedException`（START_STICKY 后台重启、通知被禁），可能崩溃 | 包 try/catch，失败则复位状态 + `stopSelf` + `START_NOT_STICKY` |
| 2 | P0 | `shutdown()` 引擎释放 | 释放排入队列后**执行时读字段**，若服务快速重启会误释放新引擎 | 同步取引用并置空字段，释放任务只操作局部引用；无论重启与否旧引擎只被自己释放 |
| 3 | P0 | 服务停止后通知 | 防抖 notifier 在服务停止后延迟触发，会弹一条停不掉的 ongoing 通知 | `pushNotification` 检查 `running`；`shutdown()` 清 pending |
| 4 | P1 | `AudioCapture.loop/stop` | `stop()` 后字段置 null，loop 用字段引用可能访问已释放对象；`startRecording` 与 stop 竞态遗留录制态 | loop 用局部 `rec` 快照；`!running` 时补 `rec.stop()`；finally 判空 |
| 5 | P1 | `AudioCapture.start()` | setup 阶段异常导致 record/fx/pre 泄漏；重复 start 覆盖 | 整体 try/catch + `releaseQuietly()` 清理；检测重复 start |
| 6 | P1 | `MainActivity` | receiver 在 `onStop` 注销 → 切后台/设置页期间翻译结果全丢 | 改为 `onCreate` 注册 / `onDestroy` 注销 |
| 7 | P1 | `MainActivity` 进程重建 | 本地三块 buffer 为空，只等增量广播 → 历史丢失 | 服务端维护进程级会话快照（同拼接规则），`onStart` 本地为空时回填 |
| 8 | P2 | `startCaptureLocked` | 高精档每 800ms 空转 `partialTimer` | 仅 `partialsEnabled()` 时启动定时器 |
| 9 | P2 | `AsrEngine.cleanup` | 每句重新编译正则 | 静态 `Pattern` |
| 10 | P2 | `ModelManager.copyAsset` | 拷贝长度不校验，半截文件 rename 成成品静默损坏 | 总长精确时校验 `done==total`，不符则删 `.part` 抛异常 |
| 11 | P2 | `VadSegmenter.release` | 未同步，理论可与 feed/snapshot 并发 | 加 `synchronized` |
| 12 | P2 | 通知刷新 | 每句译文 notify + 重建 PendingIntent | 主线程 250ms 防抖合并 |
| 13 | P2 | `SettingsActivity` | 服务运行中删/覆盖模型文件（可能 mmap）→ native 崩溃；误删需重新解包 | 运行态保护 + `confirmDelete` 二次确认 |
| 14 | P2 | `MainActivity.onStart` | 服务在跑但 UI 停在可点开始 | 静态 `isRunning()` + 前台刷新状态栏 |

## 关键设计决策

- **引擎释放排队而非阻塞等待**：ASR/MT 释放动作排到各自单线程 executor 队尾，正在 native 解码的任务先自然结束，避免 UAF，且 `onDestroy` 不阻塞。
- **capture/vad/timer 的创建与销毁共用 `lifecycleLock`**：杜绝「shutdown 先跑、startCapture 后把资源建出来」的泄漏。
- **会话快照用进程级静态而非 binder**：服务与 Activity 同进程，进程死=会话死，天然一致；避免 binder 拖住 Service 生命周期（用户点停止但 Activity 仍绑定导致服务不销毁）。

## 遗留建议（未改，需权衡）

1. **P1 会话文本落盘**：快照只存活在进程内存；进程被杀后整段丢失。若要求「重启 App 还能看到上次内容」，需把累计文本周期性写入内部存储/DataStore，并在 UI 首帧异步恢复。注意防抖与磁盘磨损。
2. **P1 结果上限**：长会话 `TextView` 全量 `setText` 会卡顿/超 1MB bundle 限制。建议行数上限（如保留最近 200 句）+ 超限归档。
3. **P2 START_STICKY 语义**：进程被杀重启时服务自动继续翻译。若产品不希望「用户不知情时后台自动开麦」，改 `START_NOT_STICKY` 并提示重新开始。
4. **P2 可观测性**：目前仅 `Log`。建议加 `UncaughtExceptionHandler` 落盘崩溃日志（服务在后台死掉无 UI 时），便于线上排障。
5. **P3 签名发布**：README 已知 `release` 使用 debug 签名，上架前必须换正式签名并核对 proguard keep 规则（native 方法）。

## 验证

- `gradlew :app:compileDebugJavaWithJavac` 通过
- IDE lint 0 错误
- 建议真机回归：开始→切设置页→回主界面（不丢结果）、停止→删除模型（弹确认+运行保护）、快速开始/停止连点、来电/插拔耳机时的录音恢复。
