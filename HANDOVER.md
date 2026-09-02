# 项目交接文档

> 交接对象：拿到这个仓库、要在本地 Android Studio 里编译并上真机测试的人
> 最后更新：2026-09-02
> 仓库：`/workspaces/dou-di-zhu-game`（注意：仓库名沿用旧的斗地主工程，内容与此无关）

---

## 1. 这是什么

一个**完全离线**的实时语音翻译 Android App。按住"开始翻译"，说话，屏幕上同时出现原文和译文。
不联网、不上传、不依赖任何云端 API。

- 目标设备：**红米 K40 Pro（骁龙 888）**，只出 `arm64-v8a`
- 技术栈：**纯 Java + XML**（没有 Kotlin），JNI 只用在翻译引擎那一层
- `minSdk 28` / `targetSdk 35` / `compileSdk 35`
- 产物：`app/build/outputs/apk/release/app-release.apk`，**37.2MB**

### 当前状态（交接时的真实进度，不是计划）

| 项目 | 状态 |
|---|---|
| Android 工程（UI / 服务 / 管线 / 下载 / 设置） | ✅ 完成，可编译可安装 |
| 翻译引擎 native 层（CTranslate2 + SentencePiece，自研 JNI） | ✅ 完成，x86 与 arm64 双端验证通过 |
| 模型配方、延迟基线 | ✅ 实测完成（数据见 README 第 4 节） |
| 构建脚本、CI 工作流 | ✅ 完成 |
| **真机运行** | ❌ **从未跑过** —— 开发环境是 codespaces，没有真机也没有模拟器 |

**这是交接时最大的风险点：代码从未在真实设备上执行过。**
第一次上真机请严格按第 7 节的自检清单走，并把结果反馈回来。

---

## 2. 最快路径（只想装到手机上看效果）

```bash
git clone <仓库地址> vtrans && cd vtrans
export JAVA_HOME=<你的 JDK17 或 JDK21 路径>    # 见第 3 节，不要用 JDK 25
./gradlew :app:assembleDebug                  # 首次会下载依赖，约 3~5 分钟
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**不需要 NDK，不需要编任何 native 代码** —— 三个 `.so` 已经预编译好提交在仓库里（第 6 节）。

---

## 3. 环境要求

| 组件 | 要求 | 备注 |
|---|---|---|
| JDK | **17 或 21** | ⚠️ **不能用 JDK 25**：AGP 8.7 / Gradle 8.9 在 JDK 25 上直接报错 |
| Android SDK | Platform 35 + Build-Tools 35.0.0 | Android Studio 会提示安装 |
| NDK | **不需要** | 除非你要重新编译 `libvtrans-mt.so` |
| Android Studio | Hedgehog (2023.1) 或更新 | |
| 真机 | arm64 架构，Android 9(28)+ | 模拟器跑不了：APK 只有 arm64 的 so |

### Android Studio 里的两个必做设置

1. **Gradle JDK 改成 17 或 21**
   `Settings → Build Tools → Gradle → Gradle JDK`
   AS 自带的 JDK 如果是 21 没问题；如果是别的版本必须手动改，否则构建会失败。

2. **确认 `local.properties` 里的 sdk.dir 正确**
   这个文件**故意没有提交**（每台机器的 SDK 路径不同）。
   AS 打开项目时通常会自动生成；如果报 "SDK location not found"，手动建一个：

   ```properties
   sdk.dir=/Users/你的用户名/Library/Android/sdk    # macOS
   sdk.dir=C:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk   # Windows
   ```

---

## 4. 用 Android Studio 打开

1. `File → New → Project from Version Control`（或 `File → Open` 选目录）
2. 等 Gradle Sync 完成。首次会下载 AGP、Material、AppCompat、Commons-Compress，需要联网
3. `Build → Build Bundle(s)/APK(s) → Build APK(s)`
4. 手机开 USB 调试 → `Run → Run 'app'`

### 可能遇到的坑

| 现象 | 原因 / 处理 |
|---|---|
| `SDK location not found` | 缺 `local.properties`，见第 3 节 |
| `Gradle sync failed: Unsupported class file major version` | JDK 版本太高，改成 17/21 |
| 装到模拟器上闪退 | 预期行为：APK 只有 arm64 的 so。要么用真机，要么临时在 `app/build.gradle` 的 `abiFilters` 加 `x86_64`（但 sherpa 的 x86_64 so 没有提交，需要另外去 AAR 里取） |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | 同上，用了非 arm64 设备 |
| 首次打开提示下载模型 ~800MB | 正常。也可以手动导入，见第 5 节 |

---

## 5. 模型（运行时才需要）

首次点"开始翻译"时下载，之后完全离线。存放在
`/sdcard/Android/data/com.example.vtrans/files/models/`。

| 模型 | 体积 | 必需 |
|---|---|---|
| Silero VAD v5 int8 | 0.2MB | ✅ |
| SenseVoice-Small int8 | 237MB | ✅ |
| NLLB-200-distilled-600M CT2 int8 | 622MB | ✅ |
| Whisper-Small int8 | 375MB | 可选（非中文或高精档才需要） |

**不想在手机上等下载？可以手动导入**（在电脑上先下好，推到手机）：

```bash
# 1) 电脑上准备模型
bash tools/fetch_models.sh            # 会下到 /tmp/models（需要 python3 + curl）

# 2) 按 ModelsManifest 里的相对路径推到手机
adb push /tmp/models/silero_vad.int8.onnx \
     /sdcard/Android/data/com.example.vtrans/files/models/vad/
adb push /tmp/models/sense-voice-int8/model.int8.onnx \
     /sdcard/Android/data/com.example.vtrans/files/models/sense-voice/
adb push /tmp/models/sense-voice-int8/tokens.txt \
     /sdcard/Android/data/com.example.vtrans/files/models/sense-voice/
adb push /tmp/models/nllb-600m-ct2-int8/. \
     /sdcard/Android/data/com.example.vtrans/files/models/nllb/

# 可选：Whisper（非中文或高精档才需要）
adb push /tmp/models/whisper-small/small-encoder.int8.onnx \
     /sdcard/Android/data/com.example.vtrans/files/models/whisper/
adb push /tmp/models/whisper-small/small-decoder.int8.onnx \
     /sdcard/Android/data/com.example.vtrans/files/models/whisper/
adb push /tmp/models/whisper-small/small-tokens.txt \
     /sdcard/Android/data/com.example.vtrans/files/models/whisper/
```

模型的 URL 与 SHA256 都写在
`app/src/main/java/com/example/vtrans/model/ModelsManifest.java` 里，是实测值。

---

## 6. 目录说明（哪些能动，哪些不能删）

```
app/src/main/
├── java/com/example/vtrans/          ← 业务代码，随便改
├── res/                              ← 布局与资源，随便改
├── cpp/                              ← 自研 JNI 源码（mt_engine.cpp 是核心）
├── assets/bench_zh.wav, bench_en.wav ← 内置测试语音，基准测试/自检要用，别删
├── jniLibs/arm64-v8a/*.so            ← ⚠️ 预编译产物，30MB，删了就跑不起来
└── AndroidManifest.xml
app/libs/sherpa-onnx-classes.jar      ← ⚠️ sherpa-onnx 的 Java API，别删
```

**`jniLibs` 里的三个 so 是提交进仓库的**（所以克隆下来 71MB）：

| 文件 | 来源 | 用途 |
|---|---|---|
| `libonnxruntime.so` 21MB | sherpa-onnx 官方 AAR | ONNX 推理（VAD + ASR） |
| `libsherpa-onnx-jni.so` 4.8MB | sherpa-onnx 官方 AAR | VAD / ASR 的 Java 接口 |
| `libvtrans-mt.so` 5.0MB | **本仓库自己编的** | NLLB 翻译（CTranslate2 + SentencePiece） |

**只在改 `app/src/main/cpp/` 时才需要重建 `libvtrans-mt.so`**，步骤见 README 第 7 节
（要装 NDK + 交叉编译 CTranslate2，约 20 分钟）。只改 Java 层的话完全不用碰。

`native/`、`tools/`、`scripts/` 三个目录是**开发与验证用的脚本**，不参与 APK 构建，
但建议保留：`tools/` 里的对比实验脚本是准确率结论的依据。

---

## 7. 真机自检清单（请务必逐条确认并反馈）

> 这份清单存在的理由：开发环境没有真机，以下每一项都**从未被验证过**。

1. **安装与授权**：`adb install` 成功；首次点"开始翻译"弹出麦克风 + 通知授权
2. **模型下载**：进度条正常推进到 100%，提示"模型就绪"，自动开始翻译
3. **基准测试**：设置 → 跑一次基准测试。请记录返回值：
   - NNAPI 是否可用？（不可用会显示"不可用（…）"）
   - 三个后端各自多少 ms？最终选中了哪个？
   - **识别文本对不对？**（这是判断 NPU 有没有把结果算歪的关键，见第 8 节）
4. **中文实时性**：说一句中文，观察
   - 灰色（增量）原文多久出现？
   - 定稿（黑色）多久？
   - 译文多久跳出？是否在 1~3 秒内？
5. **英文/日语**：说一句英文，确认它自动切到 Whisper（延迟会明显变大）。
   日语、韩语同理
6. **后台与息屏**：息屏后翻译是否继续？通知栏"停止"按钮是否有效？
7. **稳定性**：连续说 5 分钟，观察
   - 有没有崩溃？（`adb logcat | grep -i vtrans`）
   - 发热后延迟是否翻倍？状态栏是否会提示"设备发热，已降到 N 线程"
   - 内存占用（`adb shell dumpsys meminfo com.example.vtrans`）
8. **断网**：下载完模型后开飞行模式，确认仍可翻译

---

## 8. 需要重点复核的两个判断

### (1) NPU 到底有没有用上

设置页的基准测试会依次试 `nnapi` / `xnnpack` / `cpu`。请特别关注：
**NNAPI 更快但识别文本明显更差**（比如把中文识别成乱码）的情况 ——
这说明 NNAPI 路径有数值精度问题，需要在代码里强制禁用 NNAPI。

如果 NNAPI 在 K40 Pro 上明显更快且结果一致，那当然最好。

### (2) ASR 引擎选择逻辑是否符合实际

代码里的策略（`TranslateService.needWhisper()`）：

- 均衡档：先用 SenseVoice 快速出结果，**发现识别文本不是中文时**自动用 Whisper 重跑覆盖
- 高精档：一律用 Whisper

依据是 codespaces 上的实测：SenseVoice int8 的**日/韩/英基本不可用**
（英文 `THE TRIVBAL CHIEF THIN… COOD`、韩文 `如晚醒 하면서面 훨씬过呀`），
而 Whisper-small 各语种都正确。

请在真机上复核这个结论。如果 SenseVoice 在真机上表现更好，可以简化掉这套双引擎逻辑。

---

## 9. 已知问题与未完成项

| 问题 | 说明 |
|---|---|
| **Release 用的 debug 签名** | `app/build.gradle` 里签名配置指向 `~/.android/debug.keystore`。正式分发前必须换成自己的签名 |
| **从未在真机跑过** | 见第 7 节 |
| 只支持 arm64-v8a | 为体积和性能砍掉了 32 位与 x86。需要别的 ABI 得重新取 sherpa 的 so 并交叉编译 |
| Whisper 不做增量预览 | 单次解码 1~2s，做增量只会拖慢定稿。高精档只出定稿结果 |
| 粤语的边界 | SenseVoice 认粤语很准；但 whisper-small 不支持 `yue`，高精档会回退成中文 |
| 没有 TTS、没有历史记录 | 按需求刻意不做 |
| 找不到的音频设备/蓝牙 | 目前只处理了 `AudioRecord` 主路径，蓝牙 SCO 耳机未做适配 |

---

## 10. 仓库里有什么 / 没有什么

**已提交（56 个文件）**：全部源码、构建脚本、预编译 so、资源、测试语音、CI 配置、README。

**故意没提交**：

| 文件/目录 | 为什么不提交 |
|---|---|
| `local.properties` | SDK 路径，每台机器不同 |
| `app/build/`、`.gradle/` | 构建产物 |
| 模型文件（~1.2GB） | 运行时下载；`tools/fetch_models.sh` 可随时重新获取 |
| CTranslate2 / SentencePiece 源码 | 需要时由 `native/*.sh` 从 GitHub 克隆 |

**如果克隆后发现少了东西**，先检查这三条：
```bash
git status --short        # 工作区是否干净
git ls-files | wc -l      # 应该是 56
ls app/src/main/jniLibs/arm64-v8a/    # 应该有 3 个 .so，共约 30MB
```

---

## 11. 遇到问题先查这里

| 症状 | 排查方向 |
|---|---|
| 启动就崩 | `adb logcat -s TranslateService MainActivity ModelManager` —— 多半是模型没下载完整（看 `/sdcard/Android/data/com.example.vtrans/files/models/` 下的文件是否齐全） |
| 有原文没译文 | MT 引擎初始化失败，logcat 搜 `MtEngine`。常见原因是 `nllb/` 目录下三个文件不全 |
| 译文是乱码/跑偏语言 | 源语言判断错了。看 logcat 里 `detect=` 的输出，去设置页手动指定源语言验证 |
| 翻译极慢（>10s/句） | 先看基准测试结果；再确认 PerfGuard 有没有降级线程；`dumpsys meminfo` 看是否被杀 |
| 改了 C++ 没生效 | 必须重跑 `native/build_vtrans_android.sh`（Gradle **不会**自动编 `app/src/main/cpp`） |

---

## 12. 关键设计决策一览

完整数据见 README 第 4 节，这里只列结论：

1. **纯 Java 不用 Kotlin** —— ART 上两者性能无差别，sherpa 官方 API 本身就是 JVM 字节码
2. **翻译走 CPU 不走 NPU** —— NLLB 是自回归 + KV-cache，每步只算一个 token，瓶颈在访存与小 GEMM，NNAPI 的动态 shape 支持不完整，通常更慢。NPU 只用在 ASR/VAD 的大矩阵上
3. **NLLB token 配方**：`source = [src_lang] + pieces + ["</s>"]`，`target_prefix = [[tgt_lang]]`。少任一项都会退化成复读机或译成别的语言（实测结论）
4. **不信 SenseVoice 的 `language=auto`** —— 五个语种它全返回 `<|yue|>`。源语言改为按识别文本的书写系统判定
5. **OpenMP 必须关掉忙等** —— 默认 spin-wait 在 4 核上把解码拖慢 3~4 倍（7.5s → 1.2s），已在 libomp 初始化前设掉
6. **arm64 产物用 qemu 验证过** —— 编静态 arm64 可执行文件用 `qemu-aarch64-static` 跑同一份 `mt_engine.cpp`，译文与 x86 / Python 基线逐条一致。这是没有真机时能做的最强验证

---

## 13. 反馈请包含

跑完自检后，请把这几项发回来（有 logcat 最好）：

1. 手机型号 / 系统版本 / 内存
2. 基准测试的完整输出（三个后端各自的 ms + 识别文本）
3. 中文、英文各一句的：增量延迟、定稿延迟、译文延迟
4. 是否崩溃（附 `adb logcat` 相关片段）
5. 连续 5 分钟后的发热与延迟变化
