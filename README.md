# 离线实时语音翻译（红米 K40 Pro / 骁龙 888）

一个**完全离线**的实时语音翻译 App：按住开始，说话，屏幕上同时出现原文和译文。
不联网、不上传、不依赖任何云端 API。

- 目标设备：Redmi K40 Pro（骁龙 888，`arm64-v8a`），`minSdk 28`
- 技术栈：纯 Java + XML（无 Kotlin），JNI 只用在翻译引擎那一层
- 产物：`app/build/outputs/apk/release/app-release.apk`（约 37MB，只含 arm64）

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
翻译线程数、解码宽度、模型管理、**基准测试（同时充当真机自检）**。

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
│   ├── fetch_models.sh           # 下载模型到 /tmp/models 并算 SHA256
│   ├── recipe_probe.py           # 确定 NLLB 的正确 token 配方 + 延迟基线
│   ├── asr_probe.py              # VAD + SenseVoice 的基线
│   ├── engine_compare.py         # SenseVoice vs Whisper 的准确率/延迟对比
│   └── host_test/                # host 端到端：wav → MT → 译文
├── app/
│   ├── libs/sherpa-onnx-classes.jar       # sherpa-onnx v1.13.7 的 Java API
│   ├── src/main/jniLibs/arm64-v8a/        # libonnxruntime.so / libsherpa-onnx-jni.so / libvtrans-mt.so
│   ├── src/main/cpp/                      # 唯一自写的 native 代码
│   │   ├── mt_engine.{h,cpp}              # CTranslate2 + SentencePiece 封装（host/安卓共用）
│   │   └── jni_bridge.cpp                 # 只有 Android 编
│   └── src/main/java/com/example/vtrans/  # UI / 服务 / 管线 / 下载 / 设置
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

首次启动需要下载（之后完全离线）：

| 模型 | 体积 | 必需 |
|---|---|---|
| Silero VAD v5 int8 | 0.2MB | ✅ |
| SenseVoice-Small int8 | 237MB | ✅ |
| NLLB-200-distilled-600M CT2 int8 | 622MB | ✅ |
| Whisper-Small int8 | 375MB | 可选（非中文或高精档才需要） |

- 存放位置：`getExternalFilesDir(null)/models/`，卸载时自动清理
- 支持断点续传；每个文件都内嵌了实测的 SHA256
- `INTERNET` 权限**只**用于下载模型。下载完成后可以撤掉：
  `adb shell pm revoke com.example.vtrans android.permission.INTERNET`
- 也支持手动导入：把同样结构的目录推到 `/sdcard/Android/data/com.example.vtrans/files/models/` 即可

---

## 7. 从零构建

```bash
# 1) 环境：JDK17 + Android SDK(35) + build-tools(35) + NDK r27
bash scripts/setup_env.sh

# 2) 交叉编译第三方 native 库（arm64）→ /tmp/native-prefix/arm64
bash native/build_android.sh

# 3) 编出 libvtrans-mt.so → app/src/main/jniLibs/arm64-v8a/
bash native/build_vtrans_android.sh

# 4) 打 APK
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # AGP 8.7 要 JDK17
./gradlew :app:assembleRelease
```

`app/src/main/jniLibs/arm64-v8a/*.so`（约 31MB）已经提交进仓库，
所以只想改 Java 层的话，直接跳到第 4 步即可。

### 可选：本机验证

```bash
bash tools/fetch_models.sh                    # 下模型到 /tmp/models
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
adb shell ls /sdcard/Android/data/com.example.vtrans/files/models/   # 模型是否就位
```

首启清单：

1. 点「开始翻译」→ 授权麦克风 + 通知
2. 首次会提示下载模型（约 0.8GB，Wi-Fi 下几分钟）
3. 进设置 → **跑一次基准测试**：确认 NNAPI 是否可用、延迟多少、识别文本对不对
4. 对着手机说中文，看原文（灰色→定稿）与译文是否逐句跳出
5. 说一句英文，确认它自动切到了 Whisper（状态栏延迟会明显变大）
6. 息屏 / 切后台，确认翻译仍在跑（通知栏常驻，可一键停止）

---

## 9. 已知限制

- **只支持 `arm64-v8a`**：为体积和性能砍掉了 32 位与 x86，模拟器跑不了，只能真机
- **Whisper 不做增量预览**：单次解码就要 1~2s，做增量只会拖慢最终定稿，所以高精档只出定稿结果
- **粤语**：SenseVoice 认得很准，但 whisper-small 不支持 `yue`，高精档会回退成中文
- **没有 TTS / 没有历史记录**：按需求刻意不做
- **Release 用的是 debug 签名**（`~/.android/debug.keystore`），方便 `adb install` 验证。
  正式分发前请在 `app/build.gradle` 里换成自己的签名配置
- NLLB(622MB) + SenseVoice(237MB) + Whisper(375MB) 同时驻留，已开 `largeHeap`；
  内存更小的机器上建议只装必要的模型

---

## 10. 第三方依赖

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) v1.13.7（Apache-2.0）— VAD / ASR / ONNX Runtime Android 预编译库
- [CTranslate2](https://github.com/OpenNMT/CTranslate2) v4.8.2（MIT）— 端侧 NLLB 推理
- [SentencePiece](https://github.com/google/sentencepiece) v0.1.99（Apache-2.0）— NLLB 分词
- [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/)（Apache-2.0）— 解 tar.bz2 模型包
- 模型：SenseVoice-Small、Whisper-Small、NLLB-200-distilled-600M
