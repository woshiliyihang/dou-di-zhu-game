# 项目交接文档

> 交接对象：拿到这个仓库、要快速熟悉项目并上真机验证的人
> 最后更新：2026-09-11
> 仓库：`dou-di-zhu-game`（仓库名沿用旧的斗地主工程，内容与此无关）
> 配套阅读：[README.md](README.md) 讲「为什么这么选」，本文讲「怎么跑起来、内部怎么运转」

---

## 0. 10 分钟快速上手

```bash
# 只想看看效果（不需要 NDK、不需要编 native，so 已进仓库）
export JAVA_HOME=<JDK17 或 JDK21 路径>       # 不要用 JDK 25
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**理解代码的最快路径**（按这个顺序读，各文件职责见第 5 节）：

1. `TranslateService.java` —— 整条管线的宿主与调度中心，看懂它就懂了一半
2. `pipeline/VadSegmenter.java` + `pipeline/AsrEngine.java` —— 「一段话」怎么被切出来、怎么被识别
3. `pipeline/SentenceSplitter.java` + `pipeline/MtEngine.java` —— 识别文本怎么变成译文
4. `audio/AudioCapture.java` + `audio/VoicePreprocessor.java` —— 麦克风信号进来前做了什么
5. `MainActivity.java` —— 结果怎么显示；`model/ModelManager.java` —— 模型怎么落地
6. 第 6 节「关键流程详解」—— 把上面这些串成端到端的一条线

---

## 1. 这是什么

一个**完全离线**的实时语音翻译 Android App：点「开始翻译」，说话，屏幕上同时出现原文和译文，并可语音播报译文。

- 不联网、不上传、不依赖任何云端 API（`AndroidManifest.xml` 里显式 `tools:node="remove"` 掉了所有网络权限）
- 目标设备：**红米 K40 Pro（骁龙 888）**，只出 `arm64-v8a`
- 技术栈：**纯 Java + XML**（没有 Kotlin），JNI 只用在翻译引擎那一层
- `minSdk 28` / `targetSdk 35` / `compileSdk 35`，`versionName 1.0.0`
- 产物：`app/build/outputs/apk/release/app-release.apk`，**约 0.9GB**（模型随 APK 打包）

### 三个使用档位

| 档位 | ASR 引擎 | 增量预览 | beam | 典型延迟 |
|---|---|---|---|---|
| **均衡**（默认） | SenseVoice，非中文自动用 Whisper 重跑 | 有（800ms 一刷原文） | 1 | 中/粤 快，其他语种慢 |
| **高精** | 一律 Whisper | 无 | 4 | 明显更慢，但更准 |

---

## 2. 当前状态（交接时的真实进度）

| 项目 | 状态 |
|---|---|
| Android 工程（UI / 服务 / 管线 / 设置） | 完成，可编译可安装 |
| 音频前置处理（回声/降噪/增益、远场增益） | 完成，链路多级兜底 |
| 翻译引擎 native 层（CTranslate2 + SentencePiece，自研 JNI） | 完成，x86 与 arm64 双端验证通过 |
| 译文语音播报（TTS）+ 耳机检测 | 完成（见 6.7） |
| 模型配方、延迟基线 | 实测完成（数据见 README 第 4 节） |
| 构建脚本、CI 工作流 | 完成 |
| 本机构建 + `adb` 推送真机 | 已跑通（Windows + 真机，release APK 约 862MB） |
| **真机逐条回归（第 9 节自检清单）** | **尚未系统完成** —— 这是交接后第一件该做的事 |

---

## 3. 环境要求与 Android Studio 设置

| 组件 | 要求 | 备注 |
|---|---|---|
| JDK | **17 或 21** | 不能用 JDK 25：AGP 8.7.3 / Gradle 8.10.2 在 JDK 25 上直接报错 |
| Android SDK | Platform 35 + Build-Tools 35.0.0 | Android Studio 会提示安装 |
| NDK | **不需要** | 除非要重新编译 `libvtrans-mt.so`（NDK 27.1.12297006） |
| Android Studio | Hedgehog (2023.1) 或更新 | |
| 真机 | arm64 架构，Android 9(28)+ | 模拟器跑不了：APK 里只有 arm64 的 so |

Android Studio 里两个必做设置：

1. **Gradle JDK 改成 17 或 21**：`Settings → Build Tools → Gradle → Gradle JDK`
2. **确认 `local.properties` 的 `sdk.dir`**：该文件故意不提交，AS 通常会自动生成；报 `SDK location not found` 就手动建：

   ```properties
   sdk.dir=/Users/你的用户名/Library/Android/sdk                      # macOS
   sdk.dir=C:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk         # Windows
   ```

---

## 4. 构建与安装

### 命令行

```bash
./gradlew :app:assembleDebug      # 调试包，首次会下依赖（3~5 分钟）
./gradlew :app:assembleRelease    # 发布包（模型大，出包慢，最终约 0.9GB）
./gradlew :app:lintRelease        # CI 会跑，abortOnError=true
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Android Studio

`File → Open` 选仓库目录 → 等 Gradle Sync → `Build → Build Bundle(s)/APK(s) → Build APK(s)` → 手机开 USB 调试后 `Run → Run 'app'`。

### 常见坑

| 现象 | 原因 / 处理 |
|---|---|
| `SDK location not found` | 缺 `local.properties`，见第 3 节 |
| `Unsupported class file major version` | JDK 版本太高，改成 17/21 |
| 装到模拟器上闪退 | 预期：APK 只有 arm64 的 so，只能真机 |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | 同上 |
| 提示「APK 里缺少模型文件」 | 打包前没跑 `tools/fetch_models.sh`，`assets/models/` 是空的 |
| 提示要解包约 0.9GB | 正常，见 6.10 |

---

## 5. 代码地图

### 5.1 Java 业务代码（`app/src/main/java/com/example/vtrans/`，16 个文件）

| 文件 | 职责 | 关键点 |
|---|---|---|
| `MainActivity.java` | 主界面：开始/停止、原文与译文渲染、模型解包进度 | 广播在 `onCreate/onDestroy` 注册注销；`onStart` 从会话快照回填 |
| `SettingsActivity.java` | 设置页：语言/档位/音频/增益/provider/线程/beam/模型管理/基准测试/**TTS 播报** | 「跑一次基准测试」同时是真机自检 |
| `TranslateService.java` | **前台服务，整条管线的宿主** | 三个单线程池 + 定时器；会话快照；PerfGuard；通知防抖 |
| `audio/AudioCapture.java` | `AudioRecord` 采集，16kHz 单声道，20ms 一帧 | 音源选择（外放播报时走通话音源以启用 AEC）、系统音效挂载、软件链兜底、增益与电平日志 |
| `audio/VoicePreprocessor.java` | 软件前置处理链（高通/门控/AGC/限幅） | 因果、逐采样、零分配，不给实时链路加延迟 |
| `audio/SystemAudioEffects.java` | 系统 AEC / NS / AGC 的挂载与能力检测 | 逐个 try/catch，失败即降级由软件补 |
| `audio/TtsSpeaker.java` | 译文语音播报 + 耳机检测 | 是否出声由「仅耳机播报」开关决定，避免外放回环 |
| `pipeline/VadSegmenter.java` | Silero VAD 封装：把连续音频切成「一句一句」 | 另存「起说点起」的缓冲供增量预览取快照；20s 硬切 |
| `pipeline/AsrEngine.java` | ASR：SenseVoice（快）与 Whisper-small（准） | Whisper 按语种懒加载，语种不变不重建 |
| `pipeline/SentenceSplitter.java` | 把识别文本切成句子喂给 NLLB | 终止符切句；超 40 字按逗号再切；残留攒着等下一段 |
| `pipeline/MtEngine.java` | NLLB 翻译的 JNI 封装 | `loadLibrary("vtrans-mt")`；7 个 native 方法 |
| `model/ModelManager.java` | 模型解包与校验 | assets 流式拷贝，先写 `.part` 再改名，长度不符即失败 |
| `model/ModelsManifest.java` | 模型清单（相对路径 + 是否必备） | VAD / SenseVoice / NLLB 必备，Whisper 可选 |
| `util/Prefs.java` | **所有设置的唯一读写入口** | 默认值都在这，改设置先看这里 |
| `util/Stats.java` | 滚动窗口延迟统计 | 通知栏展示 + PerfGuard 判据 |
| `util/WaveReader.java` | 读 assets 里的 16bit PCM wav | 基准测试用（`bench_zh.wav` / `bench_en.wav`） |

### 5.2 native 与预编译库

| 路径 | 说明 |
|---|---|
| `app/src/main/cpp/mt_engine.{h,cpp}` | **唯一自写 native 代码**，CTranslate2 + SentencePiece 封装，host 与 Android 共用同一份源码 |
| `app/src/main/cpp/jni_bridge.cpp` | JNI 桥接（仅 Android 编译），对应 `pipeline/MtEngine.java` 的 7 个 native 方法 |
| `app/src/main/cpp/CMakeLists.txt` | 目标 `mt_engine`(STATIC) / `vtrans-mt`(SHARED) / `host_test` |
| `app/src/main/jniLibs/arm64-v8a/libonnxruntime.so` | 20.7MB，sherpa-onnx 官方 AAR，ONNX 推理 |
| `app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so` | 4.5MB，sherpa-onnx 官方 AAR，VAD/ASR Java 接口 |
| `app/src/main/jniLibs/arm64-v8a/libvtrans-mt.so` | 4.7MB，**本仓库自己编的**，NLLB 翻译 |

三个 `.so` 已提交进仓库，**只改 Java 层完全不用碰 native**。

### 5.3 构建/验证脚本与 CI

| 路径 | 说明 |
|---|---|
| `scripts/setup_env.sh` | 一次性搭环境：JDK17 + cmdline-tools + Platform/Build-Tools 35 + NDK 27.1.12297006 |
| `native/build_android.sh` | 交叉编译 sentencepiece(静态) + CTranslate2(静态) → arm64，产物落 `/tmp/native-prefix/arm64` |
| `native/build_vtrans_android.sh` | 编出 `libvtrans-mt.so` 并拷进 `jniLibs`，strip 后校验 LOAD 对齐 16384 与 JNI 符号 |
| `native/build_host.sh` | x86 版依赖 + `host_test`，用同一份 `mt_engine.cpp` 验证逻辑 |
| `native/build_host_test_arm64.sh` | 编静态 arm64 可执行文件，配 `qemu-aarch64-static` 做真机等价验证 |
| `tools/fetch_models.sh` | 下载模型，直接摆成 `assets/models/` 布局（`--whisper` 追加可选模型） |
| `tools/chunkdl.py` | 分片并行下载器（代理会掐断长连接，curl 单线程下不完） |
| `tools/recipe_probe.py` | 确定 NLLB 正确 token 配方 + 延迟基线 |
| `tools/asr_probe.py` | VAD + SenseVoice 基线 |
| `tools/engine_compare.py` | SenseVoice vs Whisper 准确率/延迟对比 |
| `tools/host_test/host_test.cpp` | host 端到端：wav → MT → 译文，输出 `RESULT=OK/FAIL` |
| `.github/workflows/android.yml` | push/PR 到 main/master 或 tag `v*` 时：assembleDebug → lintRelease → assembleRelease → 校验 APK 里三个 so → 上传 artifact |

### 5.4 关键构建配置

| 配置 | 值 | 为什么 |
|---|---|---|
| `abiFilters` | `arm64-v8a` | 只做一档设备，省掉 32 位与 x86 的 so，体积减半 |
| `noCompress` | `bin, onnx, model, txt, json` | 压缩过的 asset 在 `open()` 时会被整体解压进内存，622MB 的 `model.bin` 会 OOM，且拿不到 `openFd` 长度 |
| `minifyEnabled` / `shrinkResources` | release 都开 | 需注意 native 方法的 keep 规则 |
| 签名 | release 用 **debug 签名** | 仅为方便 `adb install` 验证，正式分发前必须换 |
| `largeHeap` | true | NLLB + SenseVoice + Whisper 同时驻留，降低被 LMK 杀的概率 |

---

## 6. 关键流程详解

### 6.1 端到端数据流（总图）

```
点「开始翻译」
   │
   ├─ MainActivity：查权限 → 查模型（缺则先解包）→ startForegroundService
   │
TranslateService.onStartCommand
   ├─ startAsForeground("正在加载模型…")
   ├─ 建 3 个执行器：asrExec(单线程) / mtExec(单线程) / partialTimer(定时)
   └─ asrExec.execute(initAndRun)
          ├─ loadEngines：MtEngine(NLLB) + AsrEngine(SenseVoice)，按需预载 Whisper
          └─ startCapture：VadSegmenter + AudioCapture(+WakeLock) + 增量定时器

音频线程 vtrans-audio（每 20ms 一帧）
   AudioRecord.read → 归一化 → 预增益 → VoicePreprocessor(高通/门控/AGC/限幅)
        └─ VadSegmenter.feed(frame)
               ├─ VAD 判定「说完了」 ─────────────► onSegmentEnd
               └─ 缓冲「起说点到现在」的采样（供增量预览）

ASR 线程 vtrans-asr
   ├─ 增量预览（仅均衡档，800ms 一拍）：snapshot → SenseVoice → broadcast("partial")   ← 只出原文
   └─ 定稿：SenseVoice（必要时 Whisper 重跑）→ broadcast("final")
              └─ SentenceSplitter.push/flush → 逐句 enqueueTranslation

MT 线程 vtrans-mt
   MtEngine.translate(句, srcLang, tgtLang)
        ├─ broadcast("translation") → MainActivity 追加到「译文」
        └─ TtsSpeaker.speak(译文, targetLang, 仅耳机?)   ← 是否出声看设置与耳机

主线程
   广播接收 → render()：定稿原文正常色 / 增量原文灰色 / 译文逐句追加 → 自动滚到底
```

### 6.2 启动流程（点「开始翻译」之后发生了什么）

1. `MainActivity.onStartClicked()`
   - 没权限 → 申请 `RECORD_AUDIO`（API 33+ 还要 `POST_NOTIFICATIONS`），授权后重入
   - 必备模型没就绪 → `startUnpack()`（在 `vtrans-io` 线程解包，完成后自动 `startTranslate()`）
   - 都就绪 → `startTranslate()`
2. `startTranslate()`：清空 UI 三个缓冲区 → `startForegroundService(ACTION_START)`
3. `TranslateService.onStartCommand()`
   - 置 `running/sRunning=true`，`clearSnapshot()`（新会话清历史）
   - `startAsForeground()` **包了 try/catch**：后台启动 FGS 被拒 / 通知被禁时复位状态并 `stopSelf`
   - 建 `asrExec` / `mtExec` / `partialTimer` 三个执行器
4. `initAndRun()`（跑在 `asrExec` 上）
   - `providerOfAsr()`：用户设置非 `auto` 用设置值，否则用基准测试结果，再兜底 `cpu`
   - `loadEngines()`：`MtEngine.create()` + `AsrEngine.create()`；高精档或显式选了非中粤语种时顺带预载 Whisper
   - `startCapture()`：建 VAD、建 `AudioCapture`、取 WakeLock、开录音；均衡档启动增量定时器
5. 之后就是 6.1 的循环。

### 6.3 音频采集与前置处理

`audio/AudioCapture.java`，恒定 16kHz / 单声道 / PCM16，**320 采样（20ms）一帧**，采集线程优先级 `URGENT_AUDIO`。

处理链是「系统优先、软件兜底」：

```
AudioRecord（音源见下方「音源怎么选」）
   ├─ 预增益（0 / +6 / +12 dB，远场用，先抬进 VAD/AGC 触发区间）
   ├─ 系统音效（auto/system 档尝试挂载）：AEC / NS / AGC
   └─ 软件链 VoicePreprocessor（系统缺哪项就补哪项）
        90Hz 高通 → 降噪门控(挂尾 150ms) → AGC(目标 -28dBFS，最大 +18dB，只升不降) → 软限幅
```

**音源怎么选**（由 `TranslateService.effectiveAudioMode()` 决定，改动在下次「开始翻译」生效）：

| 情况 | 音源 | 理由 |
|---|---|---|
| 选 `system` 档 | `VOICE_COMMUNICATION` | 强制通话链路，硬件 AEC/NS/AGC 最可能生效 |
| `auto` 档 + 已关闭「仅耳机播报」 | `VOICE_COMMUNICATION` | 会出现外放播报的回声源，切通话源让硬件 AEC 生效 |
| `auto` 档 + 仅耳机播报（默认） | `VOICE_RECOGNITION` | 扬声器不发声、没有回声源，识别音源信号更干净 |
| `software` / `off` 档 | `VOICE_RECOGNITION` | 不依赖系统链路，交给软件链或原样输出 |

> 关键前提：系统 `AcousticEchoCanceler` 在识别音源上多数 ROM 会直接禁用（挂载返回 false 或抛异常），
> 所以「想吃到硬件回声消除」的前提就是走通话音源。设置页会显示本机是否支持 AEC，
> 以及当前是否因外放播报切到了通话音源。

- 门控与 AGC 的阈值都基于**自适应噪声底**：安静环境不放大底噪，停顿期压低，不误杀大声语音
- 每 250 帧（约 5 秒）打一条输入/输出电平与 AGC 增益日志（tag `AudioCapture`），判断增益是否异常就看它
- 拿不到 16kHz 时按设备最小缓冲重开；全程数组复用，避免 20ms 一次分配吵醒 GC
- 处理方案与实际挂载结果可通过 `capture.summary()` 打印（`Log.i(TAG, "录音处理方案：…")`）

### 6.4 VAD 分段与增量预览

`pipeline/VadSegmenter.java` 封装 Silero VAD v5 int8，参数：

| 参数 | 值 | 含义 |
|---|---|---|
| `threshold` | 0.5 | 语音判定门限 |
| `minSilenceDuration` | 0.35s | **静音超过它才判定「这句说完了」** |
| `minSpeechDuration` | 0.25s | 短于此不算一句 |
| `windowSize` | 512 | VAD 窗口 |
| `maxSpeechDuration` | 30s | Silero 内部上限 |
| `MAX_SPEECH_SAMPLES` | 20s | **代码层硬切**：连续说话超过 20s 强制成段 |

关键行为：

- `feed(frame)` 由音频线程调用：既喂 VAD，也把采样追加到内部缓冲
- VAD 吐出已完成段 → 回调 `onSegmentEnd(samples)`；缓冲清空
- 连续说话达到 20s → 用缓冲内容**强行成段**并 `vad.reset()`
- `snapshot()` 返回「从起说点到现在」的副本，供增量预览使用
- 静音期只保留最后 0.3s 前缀（`trimLeadingSilence`），避免起说检测的几十毫秒滞后吞字

> **直接影响用户体感**：正式译文只在「VAD 判定说完（停顿 ≥0.35s）」或「说满 20s」时才产生。
> 均衡档下 800ms 一刷的那行灰字只是**原文**增量，不翻译；高精档连增量都没有（`Prefs.partialsEnabled()` 只在均衡档为 true）。

### 6.5 最终识别：SenseVoice 与 Whisper 怎么选

`TranslateService.submitFinal()`（跑在 `asrExec` 上）：

1. 先用 SenseVoice 识别（若可用）
2. `MtEngine.detectLang(text)` **按识别文本的书写系统**判断语种。注意：SenseVoice 的 `language=auto` 不可信（实测五个语种全返回 `<|yue|>`），所以从不采信它的语种标签
3. `srcLang` = 用户设了具体语种就用设置值，否则用检测结果
4. 判断要不要用 Whisper 重跑（`shouldUseWhisper()`）：

   | 条件 | 是否用 Whisper |
   |---|---|
   | 高精档 | 一律用 |
   | 用户显式指定了非中/粤语种 | 用 |
   | `auto` 且检测结果非 `zho/yue` | 用 |
   | `auto` 且检测为中文/粤语 | **不用**（SenseVoice 又快又准，别拖慢 6~10 倍） |
   | SenseVoice 没出结果 | 兜底用（前提是 Whisper 模型已解包） |

5. Whisper 结果非空则覆盖文本，并重判一次语种
6. 有结果 → `broadcast("final")` → 交给 `SentenceSplitter`

Whisper 是**按语种懒加载**的（375MB 不常驻）：语种不变不会重建；未解包 Whisper 时自动退回 SenseVoice，不会报错。

### 6.6 分句与翻译

`SentenceSplitter`：终止符 `。！？；.!?` 与换行切句；超过 40 字（无标点或一口气说很长）就找逗号/顿号/空格再切，找不到硬切；不足 2 字的残片攒着等下一段，避免「我」「们」这类碎片进 MT。

`TranslateService.enqueueTranslation()`（跑在 `mtExec` 上，**单线程保证译文顺序**）：

```
MtEngine.translate(sentence, srcLang, targetLang)   → JNI → CTranslate2
   ├─ 计时 → Stats.add
   ├─ broadcast("translation", 译文, targetLang, 耗时)
   ├─ speakTranslation()   ← TTS 播报（见 6.7）
   ├─ guardPerformance()   ← 见 6.9
   └─ updateStats()        ← 状态栏/通知「聆听中 · 翻译 Nms」
```

native 侧（`mt_engine.cpp`）几个必须记住的配方：

```
source        = [src_lang] + sp.encode_as_pieces(text) + ["</s>"]
target_prefix = [[tgt_lang]]
```

少了 `</s>` 或少了 `target_prefix`，都会退化成复读机或翻成别的语言。另外 `max_decoding_length=256`、`repetition_penalty=1.05`，并把 OpenMP 忙等关掉（`OMP_WAIT_POLICY=passive` 等，否则 4 核上慢 3~4 倍）。

### 6.7 译文语音播报（TTS）与耳机检测

实现在 `audio/TtsSpeaker.java`，由服务持有：

- **何时播**：`TranslateService.enqueueTranslation()` 里广播译文之后调用 `speakTranslation()`，逐句朗读
- **是否出声**：由设置项 `Prefs.ttsHeadsetOnly()`（默认 **true**）决定
  - **true**：只有检测到耳机才朗读。目的是避免外放语音被本机麦克风拾取，形成
    「翻译 → 外放 → 再识别 → 再翻译」的回环自激
  - **false**：无论是否插耳机都朗读（外放场景需自担回环风险）
- **耳机检测**：`AudioManager.getDevices(GET_DEVICES_OUTPUTS)`，命中以下任一即视为有耳机，无需任何权限
  `TYPE_WIRED_HEADSET`、`TYPE_WIRED_HEADPHONES`、`TYPE_BLUETOOTH_A2DP`、`TYPE_BLUETOOTH_SCO`、`TYPE_USB_HEADSET`
- **朗读口音**跟随**目标语言**（`zh → SIMPLIFIED_CHINESE`，`en → US`），只有语种变化时才 `setLanguage`
- **播放队列** `QUEUE_ADD`，逐句排队，不打断前一句
- **初始化竞态**：TTS 引擎初始化要几百毫秒，这期间来的译文会暂存一句，`onInit` 后补播
- 服务停止时 `release()`：停止朗读并释放引擎
- `AndroidManifest.xml` 里声明了 `<queries><intent><action ...TTS_SERVICE/></intent></queries>`，否则 Android 11+ 的包可见性限制可能让 `TextToSpeech` 查不到系统引擎

**外放播报时怎么防回环**：软件前处理链里没有 AEC（回声消除必须拿到播放参考信号，
纯前处理做不到），所以只能依赖系统 AEC。而系统 AEC 只在通话音源上才可能生效，
因此 `auto` 档在「仅插入耳机时播报译文」被关闭（即允许外放）时，会自动把音源切成
`VOICE_COMMUNICATION`（见 6.3 的音源表）。默认的仅耳机播报场景扬声器不发声、
没有回声源，所以保持识别音源，不让通话链路的通信优化影响识别质量。

设置页的录音处理提示会把当前决定显示出来：本机是否支持 AEC、是否已因外放播报切到通话音源。

### 6.8 结果广播与 UI 渲染

服务 → UI 走**本地广播**（`ACTION_RESULT`，`setPackage` 限定本应用）：

| kind | 含义 | UI 行为 |
|---|---|---|
| `partial` | 增量原文（仅均衡档） | 存进 `partialSource`，渲染成**灰色** |
| `final` | 定稿原文 | 追加进 `committedSource`，清空灰色部分 |
| `translation` | 译文 | 追加进 `target`，状态栏显示本次耗时 |

- `MainActivity` 的 receiver 在 **`onCreate` 注册 / `onDestroy` 注销**：切后台或进设置页期间继续累计，回前台不丢内容
- `render()` 用 `SpannableStringBuilder` 拼「定稿 + 灰色增量」，两个 `ScrollView` 自动滚到底
- 服务侧 `broadcast()` 同时维护**进程级会话快照**（`snapSource/snapPartial/snapTarget`），拼接规则与 UI 完全一致；Activity 被回收重建后，`onStart` 发现本地为空就从快照回填

### 6.9 PerfGuard：发热降频时自动降级

`TranslateService.guardPerformance()`：骁龙 888 发热降频很凶。

- 用最近 8 句译文延迟的滚动均值建立基线（首次取 `max(200ms, 均值)`）
- 均值持续超过基线 **2.5 倍** 时逐级降级：
  - 第 1 级：`mt.setThreads(2)`，通知「设备发热，已降到 2 线程」
  - 第 2 级：`mt.setThreads(1)` + `mt.setBeam(1)`，通知「设备发热，已降到 1 线程 / beam=1」
- 每次降级后**重置基线**，避免反复抖动

### 6.10 模型解包

模型随 APK 打包在 `assets/models/`（**未压缩**，见 5.4），首次启动拷到手机：

```
APK: assets/models/...                     ← noCompress，可用 openFd 问长度
   │  ModelManager.ensure()（跑在 MainActivity 的 vtrans-io 线程）
   ▼
手机: getExternalFilesDir(null)/models/    ← 外部存储不可用时退回 getFilesDir()
```

- 逐文件流式拷贝：先写 `<file>.part` → 校验字节数 → `renameTo` 成品。中断重跑不会留下半截模型
- `androidResources.noCompress` 必须保持：压缩 asset 的 `openFd` 会失败（退回 `open()` 会整体解压进内存）
- 解包后手机占用 = APK 约 0.9GB + 存储 0.9GB
- 设置页可删除单个模型（**服务运行中禁止**，避免 native 正在 mmap 的文件被删导致崩溃），再点「解包」即可从 APK 恢复
- 模型清单在 `model/ModelsManifest.java`：VAD / SenseVoice / NLLB(3 个文件) 必备，Whisper(3 个文件) 可选

### 6.11 服务与 UI 的生命周期、会话快照

- 翻译放在**前台服务**里：息屏、切后台、Activity 被回收都不影响链路；通知栏常驻「停止」按钮
- 通知刷新做了 **250ms 防抖**（`mainHandler` + `notifier`），且 `pushNotification` 会检查 `running`，避免服务已停还弹一条停不掉的 ongoing 通知
- 取 `PARTIAL_WAKE_LOCK`（上限 4 小时），保证息屏继续跑
- `shutdown()` 用 `lifecycleLock` 保证只执行一次，顺序：停录音 → 停定时器 → 停 VAD → **ASR/MT 释放排队到各自执行器队尾**（字段先置空、取局部引用，防止快速重启时误释放新引擎）→ 清通知回调 → 释放 TTS → 复位分句器 → 释放 WakeLock → `sRunning=false`
- 会话快照是**进程级静态**：服务与 Activity 同进程，进程死则会话本来就断，不必落盘

### 6.12 线程与并发模型

| 线程 | 名字 | 干什么 |
|---|---|---|
| 主线程 | main | UI 渲染、广播接收、通知、TTS 回调、`onStartCommand` |
| 音频线程 | `vtrans-audio` | `AudioRecord.read` → 前处理 → `VadSegmenter.feed`（`URGENT_AUDIO`） |
| ASR 线程 | `vtrans-asr`（单线程） | 增量识别 + 最终识别（串行，避免和识别抢算力） |
| MT 线程 | `vtrans-mt`（单线程） | 逐句翻译（串行保证译文顺序） |
| 定时器 | `vtrans-timer` | 增量预览定时（仅均衡档） |
| IO 线程 | `vtrans-io` | `MainActivity` 里的模型解包 |
| 设置线程 | `vtrans-settings` | `SettingsActivity` 的模型解包与基准测试 |

共享状态用锁保护：`VadSegmenter` 内部一把锁（音频线程 `feed` / ASR 线程 `snapshot`）、服务的 `lifecycleLock`（创建与销毁互斥）、`SNAP_LOCK`（会话快照）。

---

## 7. 设置项一览

全部由 `util/Prefs.java` 读写（SharedPreferences 文件 `vtrans`）：

| 设置项 | key | 默认 | 生效时机 |
|---|---|---|---|
| 目标语言 | `target_lang` | `zh` | 立即（下一页译文） |
| 源语言 | `source_lang` | `auto` | 下次「开始翻译」 |
| 识别档位 | `tier` | `balanced` | 下次「开始翻译」 |
| ASR 加速后端 | `provider` | `auto` | 下次「开始翻译」 |
| 基准测试结果 | `bench_provider` | 空 | 跑基准测试后写回 |
| 翻译线程数 | `mt_threads` | `min(4, 核数)` | 下次「开始翻译」 |
| 解码宽度 beam | `mt_beam` | `1`（高精 4） | 立即 |
| 增量预览间隔 | `partial_interval` | `800ms` | 下次「开始翻译」 |
| 收音增益 | `mic_boost_db` | `0` | 下次「开始翻译」 |
| 录音处理方案 | `audio_mode` | `auto` | 下次「开始翻译」 |
| 仅耳机时播报译文 | `tts_headset_only` | `true` | 立即（每句译文播报前检查） |

---

## 8. 常见改动指南

| 我想改… | 动哪里 |
|---|---|
| 界面文案 / 新增设置项 | `res/values/strings.xml` + `res/layout/activity_settings.xml` + `SettingsActivity.java` + `util/Prefs.java` |
| 断句灵敏度（更容易/更不容易出译文） | `VadSegmenter` 的 `minSilenceDuration`；想缩短长句等待就调小 `MAX_SPEECH_SAMPLES`（20s） |
| 翻译质量 / 速度 | `Prefs.beamForTier()`、`mtThreads()`；或切换档位 |
| ASR 引擎策略 | `TranslateService.shouldUseWhisper()` / `AsrEngine.whisperLang()` |
| 音频降噪、增益行为 | `audio/VoicePreprocessor.java`（算法）、`audio/AudioCapture.java`（音源与挂载） |
| TTS 播报策略 | `audio/TtsSpeaker.java`（`speak()` / `isHeadsetOn()`） |
| native 翻译逻辑 | `app/src/main/cpp/mt_engine.cpp`，**改完必须重跑 `native/build_vtrans_android.sh`**（Gradle 不会自动编 `cpp/`） |
| 新增/更换模型 | `model/ModelsManifest.java` + `tools/fetch_models.sh`，路径两边必须对齐 |

---

## 9. 真机自检清单（交接后优先完成）

1. **安装与授权**：`adb install` 成功；首次点「开始翻译」弹出麦克风 + 通知授权
2. **模型解包**：进度条推进到 100%，提示「模型就绪」，自动开始翻译
3. **基准测试**：设置 → 跑一次基准测试，记录：NNAPI 是否可用、三个后端各自多少 ms、**识别文本对不对**（这是判断 NPU 有没有把结果算歪的关键）
4. **中文实时性**：灰色原文多久出现？定稿多久？译文多久跳出（目标 1~3 秒）？
5. **非中文**：说英文/日文/韩文，确认自动切到 Whisper（延迟会明显变大）
6. **TTS 播报**：插耳机说一句，确认朗读译文且口音正确；拔掉耳机再说，确认**默认不播**；把「仅插入耳机时播报译文」关掉，确认外放也会播
7. **后台与息屏**：息屏后翻译继续？通知栏「停止」有效？
8. **稳定性**：连续说 5 分钟，观察是否崩溃（`adb logcat | grep -i vtrans`）、发热后是否触发 PerfGuard 降级、内存占用（`adb shell dumpsys meminfo com.example.vtrans`）
9. **断网**：飞行模式下仍可翻译（本来就没有网络权限）

---

## 10. 排障手册

| 症状 | 排查方向 |
|---|---|
| 启动就崩 | `adb logcat -s TranslateService MainActivity ModelManager`；多半是模型没解包完整（看 `/sdcard/Android/data/com.example.vtrans/files/models/` 是否齐全）。提示「APK 里缺少模型文件」= 打包前没跑 `tools/fetch_models.sh` |
| 有原文没译文 | MT 引擎初始化失败，logcat 搜 `MtEngine`，常见是 `nllb/` 下文件不全（`model.bin` / `config.json` / `sentencepiece.bpe.model` / `shared_vocabulary.txt`） |
| 译文是乱码 / 跑偏语言 | 源语言判断错了。看 logcat 的 `detect=` 输出；去设置页手动指定源语言验证 |
| 很久都不出译文 | 正常行为：要等停顿 ≥0.35s 或说满 20s 才定稿，见 6.4 |
| 翻译极慢（>10s/句） | 先看基准测试结果；确认 PerfGuard 是否已降级；`dumpsys meminfo` 看是否被杀 |
| 不播报译文 | 检查「仅插入耳机时播报译文」是否开着而当前没插耳机（插蓝牙耳机也算）；logcat 搜 `TtsSpeaker` 看是「未检测到耳机，跳过播报」还是 TTS 初始化失败 |
| 播报有回声 / 自己翻译自己 | 外放播报的回环。`auto` 档会在关闭「仅耳机播报」时自动切到通话音源以启用硬件 AEC；若设置页显示「本机不支持 AEC」，只能插耳机播报，或接受回环风险 |
| 识别效果差 | 检查录音处理方案与收音增益（logcat 看 `AudioCapture` 的挂载与电平日志）；安静环境先试 `system` 或 `software` 档 |
| 改了 C++ 没生效 | 必须重跑 `native/build_vtrans_android.sh`（Gradle 不会自动编 `app/src/main/cpp`） |

---

## 11. 已知限制与遗留建议

| 项 | 说明 |
|---|---|
| 只支持 arm64-v8a | 为体积与性能砍掉 32 位与 x86，模拟器跑不了 |
| Whisper 不做增量预览 | 单次解码 1~2s，做增量只会拖慢定稿，所以高精档只出定稿 |
| 粤语边界 | SenseVoice 认粤语很准；Whisper-small 不支持 `yue`，高精档会回退成中文 |
| 没有历史记录 | 会话快照只活在进程内存，进程被杀即丢；如需留存要另做落盘（见 `REVIEW-production-2026.md`） |
| 超长会话的 UI | 长会话 `TextView` 全量 `setText` 会变卡，建议加行数上限（如保留最近 200 句） |
| START_STICKY 语义 | 进程被杀后服务会自动重启继续翻译；若不希望「用户不知情时后台自动开麦」，改成 `START_NOT_STICKY` |
| 可观测性 | 目前只有 `Log`；建议加 `UncaughtExceptionHandler` 落盘崩溃日志 |
| Release 用 debug 签名 | 上架前必须换正式签名并核对 proguard keep 规则（native 方法） |
| APK 约 0.9GB | 模型打包进去了。上架要走 Play Asset Delivery 或首启从 CDN 下载 |

更细的生产级走查结论见 [REVIEW-production-2026.md](REVIEW-production-2026.md)。

---

## 12. 反馈请包含

跑完自检后，请把这几点发回来（有 logcat 更好）：

1. 手机型号 / 系统版本 / 内存
2. 基准测试完整输出（三个后端各自的 ms + 识别文本）
3. 中文、英文各一句的：增量延迟、定稿延迟、译文延迟
4. TTS：插/拔耳机、开关打开/关闭四种组合下的表现
5. 是否崩溃（附 `adb logcat` 片段）
6. 连续 5 分钟后的发热与延迟变化
