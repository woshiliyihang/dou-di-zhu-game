# 离线实时语音翻译（红米 K40 Pro / 骁龙 888）

> **要在本地 Android Studio 里编译 / 上真机测试？先看 [HANDOVER.md](HANDOVER.md)**
> （环境要求、导入步骤、避坑、真机自检清单、**代码地图与关键流程详解**）。本文面向"这个东西是怎么做出来、为什么这么选"。

一个**完全离线**的实时语音翻译 App：点开始，说话，屏幕上同时出现原文和译文，并可语音播报译文。
不联网、不上传、不依赖任何云端 API。

- 目标设备：Redmi K40 Pro（骁龙 888，`arm64-v8a`），`minSdk 28`
- 技术栈：纯 Java + XML（无 Kotlin），JNI 只用在翻译引擎那一层
- 产物：`app/build/outputs/apk/release/app-release.apk`，**约 0.9GB**（模型随 APK 打包，只含 arm64）

---

## 1. 界面

主界面只有两个按钮：

```
┌──────────────────────────────┐
│ 聆听中 · 翻译 812ms          │  ← 状态 / 实时延迟
│                              │
│ 原文                          │
│ 今天天气很好，我们一起去公园…  │  ← 灰色部分是"还在识别中"，会继续变
│                              │
│ 译文                          │
│ Today the weather is nice,    │  ← 逐句跳出
│ let's go for a walk…          │
│                              │
│ [ 开始翻译 ]  [ 结束翻译 ]     │
│            设置              │
└──────────────────────────────┘
```

设置页里有：目标语言（中/英）、源语言（自动或指定）、识别档位、加速后端、
翻译线程数、解码宽度、录音处理方案（回声/降噪/增益）、收音增益（远场）、
**译文语音播报（默认仅插入耳机时播报；关闭后会自动切通话音源启用硬件回声消除）**、
模型管理、**基准测试（同时充当真机自检）**。

### 一句话数据流

```
麦克风(AudioRecord 20ms/帧) → 前处理(高通/降噪/AGC/限幅) → VAD 切句
   → ASR(SenseVoice；非中文自动用 Whisper 重跑) → 分句 → NLLB 翻译(JNI)
   → 本地广播 → 原文/译文上屏 ＋ 译文语音播报(默认仅耳机)
```

三个环节各自独立线程、串行推进：音频线程只做采集与 VAD，识别在 `vtrans-asr` 上排，
翻译在 `vtrans-mt` 上排（保证译文顺序）。均衡档另有一个 800ms 定时器，用「起说点起」
的音频快照做**只出原文、不翻译**的增量预览。

> 完整的启动时序、线程模型、VAD 分段参数、ASR 选择策略、TTS 与耳机检测、PerfGuard
> 降级等，见 [HANDOVER.md 第 6 节「关键流程详解」](HANDOVER.md)。

---

## 2. 技术选型与理由

| 模块 | 选型 | 为什么 |
|---|---|---|
| 语言 / UI | **纯 Java + XML** | ART 上 Java 与 Kotlin 性能没有差别；sherpa-onnx 官方 Java API 本身就是 JVM 字节码，引入 Kotlin 只会多一层依赖 |
| 录音 | `AudioRecord` 16kHz / 单声道 / PCM16，20ms 帧，线程优先级 `URGENT_AUDIO` | 延迟足够低，比引入 Oboe/AAudio 简单且稳 |
| VAD | **Silero VAD v5 int8**（208KB） | 实测 RTF 0.011，CPU 占用可以忽略 |
| ASR（快） | **SenseVoice-Small int8**（237MB） | 中文/粤语又准又快（RTF≈0.06）；**但日韩英基本不可用**，见第 4 节 |
| ASR（准） | **Whisper-Small int8**（375MB） | 覆盖 99 语种，各语种都明显更准，代价是慢 6~10 倍 |
| 翻译 | **NLLB-200-distilled-600M int8**（622MB）+ 自研 JNI | 一个模型覆盖 200 语种 ↔ 中/英；CTranslate2 是端侧 NLLB 的事实标准 |
| NPU | **ONNX Runtime NNAPI EP**（VAD + ASR） | 骁龙 888 的 NNAPI HAL 会把支持的算子调度到 Hexagon 780；`provider=nnapi` 在 sherpa-onnx Java API 里直接暴露，零额外编译成本 |
| 译文播报 | 系统 `TextToSpeech` | 无需额外模型与权限；朗读口音跟随目标语言，默认只在插耳机时播报 |
| CPU | NEON dotprod int8 + OpenMP 多线程 | int8 点积指令 + 绑大核 |

### 为什么翻译不走 NPU / GPU

NLLB 解码是**自回归 + KV-cache**，每一步只算一个 token，瓶颈在访存与小 GEMM；
NNAPI 每次提交计算图有固定开销，且对动态 shape 支持不完整，实测通常比 CPU int8
更慢或不稳定。所以 GPU/NPU 用在该用的地方（ASR/VAD 的大矩阵），
翻译用 CPU int8 多线程 —— **稳定性优先**。

首次启动可以在设置页跑一次基准测试，让 App 自己比出最快的 provider，不靠猜。

---

## 3. 目录结构

```
.
├── scripts/setup_env.sh          # 一次性搭环境：JDK17 + Android SDK + NDK r27
├── native/
│   ├── build_android.sh          # 交叉编译 sentencepiece + CTranslate2（arm64）
│   ├── build_host.sh             # x86 版依赖 + host_test（验证同一份 C++ 源码）
│   ├── build_vtrans_android.sh   # 编出 libvtrans-mt.so 并放进 jniLibs
│   └── build_host_test_arm64.sh  # arm64 静态可执行文件，给 qemu 跑真机等价验证
├── tools/
│   ├── fetch_models.sh           # 下载模型，直接摆成 assets/models/ 的布局
│   ├── chunkdl.py                # 分片并行下载器（代理会掐断长连接，curl 单线程下不完）
│   ├── recipe_probe.py           # 确定 NLLB 的正确 token 配方 + 延迟基线
│   ├── asr_probe.py              # VAD + SenseVoice 的基线
│   ├── engine_compare.py         # SenseVoice vs Whisper 的准确率/延迟对比
│   └── host_test/                # host 端到端：wav → MT → 译文
├── app/
│   ├── libs/sherpa-onnx-classes.jar       # sherpa-onnx v1.13.7 的 Java API
│   ├── src/main/jniLibs/arm64-v8a/        # libonnxruntime.so / libsherpa-onnx-jni.so / libvtrans-mt.so
│   ├── src/main/cpp/                      # 唯一自写的 native 代码
│   │   ├── mt_engine.{h,cpp}              # CTranslate2 + SentencePiece 封装（host/安卓共用）
│   │   ├── jni_bridge.cpp                 # 只有 Android 编
│   │   └── CMakeLists.txt                 # mt_engine / vtrans-mt / host_test 三个目标
│   ├── src/main/assets/models/            # 离线模型（不进 git，用 fetch_models.sh 拉）
│   └── src/main/java/com/example/vtrans/
│       ├── MainActivity / SettingsActivity        # 主界面 / 设置页
│       ├── TranslateService                       # 前台服务：整条管线的宿主
│       ├── audio/    AudioCapture                 # 采集：音源选择/系统音效/软件链兜底
│       │             VoicePreprocessor            # 软件前处理：高通+门控+AGC+限幅
│       │             SystemAudioEffects           # 系统 AEC/NS/AGC 挂载与能力检测
│       │             TtsSpeaker                   # 译文语音播报 + 耳机检测
│       ├── pipeline/ VadSegmenter                 # Silero VAD 分段 + 增量快照
│       │             AsrEngine                    # SenseVoice / Whisper
│       │             SentenceSplitter             # 识别文本切句
│       │             MtEngine                     # NLLB 翻译的 JNI 封装
│       ├── model/    ModelManager                 # 模型解包与校验
│       │             ModelsManifest               # 模型清单
│       └── util/     Prefs / Stats / WaveReader   # 设置 / 延迟统计 / wav 读取
└── .github/workflows/android.yml
```

**`app/src/main/cpp/mt_engine.cpp` 是唯一一份需要在 x86 和 arm64 两边编译的源码，
其余逻辑全在 Java** —— 这是"没有真机也能验证"的关键设计。

---

## 4. 实测数据（本仓库的 tools/ 跑出来的，不是估算）

### ASR 准确率对比（`tools/engine_compare.py`，4 线程 x86）

| 音频 | SenseVoice int8 | Whisper-small int8 |
|---|---|---|
| 中文 `zh.wav` | 放时间早上九点至下午五点（漏 1 字） | 開放時間早上9點至下午5點 ✅ |
| 英文 `en.wav` | THE TRIVBAL CHIEF THIN … PIECES OF COOD ❌ | The tribal chieftain called for the boy and presented him with 50 pieces of gold ✅ |
| 日文 `ja.wav` | 家中学便当制持合五十円学校悭売借 ❌ | うちの中学は当性で、持っていない場合は50円の学校のパンを買う。 ✅ |
| 韩文 `ko.wav` | 如晚醒 하면서面 훨씬过呀 ❌ | 조만 생각을 하면서 살 훨씬 편할 거야 ✅ |
| 粤语 `yue.wav` | 呢几个字都表达唔到我想讲嘅意思 ✅ | 這幾個字都表達不到我想講的意思（繁体化） |

延迟：SenseVoice RTF≈0.06，Whisper RTF≈0.35（x86，4 线程）。

**结论（已写进代码）**：默认档位先用 SenseVoice 出快速结果，
发现识别结果不是中文时自动用 Whisper 重跑一遍再覆盖。
高精档则一律走 Whisper。

另一个实测坑：**SenseVoice 的 `language=auto` 不可信** —— 中英日韩粤五个文件全都返回
`<|yue|>`。所以代码里的源语言一律按"识别出的文字用了哪种书写系统"来判定
（C++ 侧 `detectSourceLang`，Java 通过 JNI 调用），而不是信 SenseVoice 的语种标签。

### NLLB token 配方（`tools/recipe_probe.py`）

四种配方逐一试过，只有一种是对的：

```
source       = [src_lang] + sp.encode_as_pieces(text) + ["</s>"]
target_prefix = [[tgt_lang]]
```

少了 `</s>` 或少了 target_prefix，都会退化成复读机
（"Could Could Could …"、"-- -- -- --"）或者干脆翻译成别的语言。

### 翻译延迟（NLLB-600M int8，beam=1，4 线程）

| 环境 | 单句延迟 |
|---|---|
| x86 / MKL（pip 版 ctranslate2 作参照） | 400~800ms |
| x86 / ruy（本仓库 host 构建） | 950~2050ms |
| arm64 / ruy（qemu 模拟，只验证正确性） | 数字无意义，只看输出是否一致 |

ruy 在 x86 上比 MKL 慢是预期的（ruy 是为 ARM 设计的，
x86 上没有 VNNI 指令可用）；在 888 上 ruy 才是原生快路径。

### 一个差点埋了的性能坑

OpenMP 默认**忙等（spin-wait）**：线程干完活会空转一会儿等下一批。
在 4 核机器上实测能把解码拖慢 **3~4 倍**（7.5s → 1.2s）。
`mt_engine.cpp` 里在 libomp 初始化前把 `OMP_WAIT_POLICY` / `KMP_BLOCKTIME` 设掉，
手机上既省电又不会跟音频线程抢核。

---

## 5. 无真机验证方案

没有真机、没有模拟器，所以做了三层验证：

1. **配方与质量基线**：`tools/recipe_probe.py` / `asr_probe.py` / `engine_compare.py`
   用模型自带的 test_wavs 跑通 VAD → ASR → MT，定下配方和基线。
2. **host x86 端到端**：`native/build_host.sh` 编出 `host_test`，
   跑**和 Android 完全同一份** `mt_engine.cpp`，逐条比对 Python 基线的译文。
3. **arm64 真机等价验证**：`native/build_host_test_arm64.sh` 编一个静态 arm64 可执行文件，
   用 `qemu-aarch64-static` 在本机跑起来 —— 验证的是真正要进 APK 的那个
   arm64 产物（ruy 的 NEON/dotprod 内核、静态链接的 libomp），而不只是"编译过了"。

   实测 arm64 与 x86 输出的译文一致：

   ```
   zho→eng  Today the weather is nice, let's go for a walk in the park together.
   eng→zho  会议已被重新安排到明天下午3点.
   jpn→eng  How much is the price of this product?
   kor→eng  He got up early in the morning.
   ```

第四层是给真机的：App 内置两段语音（中/英），设置页的"跑一次基准测试"
会同时打出三个后端（NNAPI / XNNPACK / CPU）的延迟和识别文本，
一眼就能看出 NPU 到底有没有生效、结果有没有跑歪。

---

## 6. 模型

**模型随 APK 一起打包**（`app/src/main/assets/models/`），装完即可离线运行，全程不联网。

| 模型 | 体积 | 必需 |
|---|---|---|
| Silero VAD v5 int8 | 0.2MB | ✅ |
| SenseVoice-Small int8 | 237MB | ✅ |
| NLLB-200-distilled-600M CT2 int8 | 622MB | ✅ |
| Whisper-Small int8 | 375MB | 可选（非中文或高精档才需要） |

- APK 里的模型**不压缩**存储（`androidResources { noCompress 'bin', 'onnx' }`）：
  压缩过的 asset 在 `open()` 时会被整体解压进内存，622MB 会直接 OOM
- 首次启动把模型从 APK 解包到 `getExternalFilesDir(null)/models/`，卸载时自动清理
- 解包后占用：APK 约 0.9GB + 手机存储 0.9GB。设置页可删除单个模型腾空间，再点「解包」从 APK 恢复
- 模型本身体积大且可再生，**不进 git**（`.gitignore` 已忽略 `models/`）。
  克隆仓库后要先 `./tools/fetch_models.sh` 把模型拉到 assets 下再打包，
  否则 App 会提示「APK 里缺少模型文件」
- Whisper 是可选的，默认不下载；要它就用 `./tools/fetch_models.sh --whisper`

---

## 7. 从零构建

```bash
# 1) 环境：JDK17 + Android SDK(35) + build-tools(35) + NDK r27
#    （NDK 只有重新编译 native 库时才需要，脚本路径写的是 codespaces 的默认值）
bash scripts/setup_env.sh

# 2) 交叉编译第三方 native 库（arm64）→ /tmp/native-prefix/arm64
bash native/build_android.sh

# 3) 编出 libvtrans-mt.so → app/src/main/jniLibs/arm64-v8a/
bash native/build_vtrans_android.sh

# 4) 拉模型到 app/src/main/assets/models/（模型随 APK 打包，不这一步就打不出能跑的包）
#    需要代理时先 export https_proxy=http://127.0.0.1:7897
./tools/fetch_models.sh

# 5) 打 APK
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # AGP 8.7 要 JDK17
./gradlew :app:assembleRelease
```

`app/src/main/jniLibs/arm64-v8a/*.so`（约 31MB）已经提交进仓库，
所以只想改 Java 层的话，直接跳到第 4 步即可。

### 可选：本机验证

```bash
MODELS_DIR=/tmp/models WORK_DIR=/tmp/models bash tools/fetch_models.sh   # 下模型到 /tmp/models
pip install ctranslate2 sentencepiece sherpa-onnx numpy
python3 tools/recipe_probe.py                 # 定 NLLB 配方 + 延迟基线
python3 tools/engine_compare.py               # SenseVoice vs Whisper
bash native/build_host.sh                     # x86 host_test
GOMP_SPINCOUNT=0 /tmp/native-build/host/host_test /tmp/models/nllb-600m-ct2-int8 4 1

# arm64 等价验证（需要 qemu-user-static）
sudo apt-get install -y qemu-user-static
bash native/build_host_test_arm64.sh
qemu-aarch64-static /tmp/native-build/arm64-test/host_test /tmp/models/nllb-600m-ct2-int8 4 1
```

---

## 8. 安装与真机自检

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

> APK 约 0.9GB（模型在里面），adb 安装会比平时慢，属于正常现象。

首启清单：

1. 点「开始翻译」→ 授权麦克风 + 通知
2. 首次会把内置模型解包到手机（约 0.9GB，进度条走完约 1~2 分钟）
3. 进设置 → **跑一次基准测试**：确认 NNAPI 是否可用、延迟多少、识别文本对不对
4. 对着手机说中文，看原文（灰色→定稿）与译文是否逐句跳出
5. 说一句英文，确认它自动切到了 Whisper（状态栏延迟会明显变大）
6. 插上耳机说一句，确认朗读译文（默认只在插耳机时播报；拔掉耳机应静音，设置里可关掉该限制）
7. 息屏 / 切后台，确认翻译仍在跑（通知栏常驻，可一键停止）

---

## 9. 已知限制

- **只支持 `arm64-v8a`**：为体积和性能砍掉了 32 位与 x86，模拟器跑不了，只能真机
- **Whisper 不做增量预览**：单次解码就要 1~2s，做增量只会拖慢最终定稿，所以高精档只出定稿结果
- **粤语**：SenseVoice 认得很准，但 whisper-small 不支持 `yue`，高精档会回退成中文
- **没有历史记录**：会话文本只存在进程内存（会话快照），进程被杀即丢
- **TTS 默认仅耳机播报**：外放时译文会被本机麦克风拾取，形成「翻译→外放→再识别」的回环自激。
  关掉该限制后，`auto` 档会自动改走通话音源以启用手机自带 AEC（系统 AEC 在识别音源上
  基本不生效）；若设备本身不支持 AEC，设置页会显示，外放场景需自担回环风险
- **Release 用的是 debug 签名**（`~/.android/debug.keystore`），方便 `adb install` 验证。
  正式分发前请在 `app/build.gradle` 里换成自己的签名配置
- NLLB(622MB) + SenseVoice(237MB) + Whisper(375MB) 同时驻留，已开 `largeHeap`；
  内存更小的机器上建议只装必要的模型
- **APK 约 0.9GB**：模型打包进去了。Play 商店对 APK 有 200MB 上限，正式分发要走
  Play Asset Delivery 或改成首次启动时从自己的 CDN 下载

---

## 10. 第三方依赖

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) v1.13.7（Apache-2.0）— VAD / ASR / ONNX Runtime Android 预编译库
- [CTranslate2](https://github.com/OpenNMT/CTranslate2) v4.8.2（MIT）— 端侧 NLLB 推理
- [SentencePiece](https://github.com/google/sentencepiece) v0.1.99（Apache-2.0）— NLLB 分词
- 模型：SenseVoice-Small、Whisper-Small、NLLB-200-distilled-600M
