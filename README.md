# 英语实时翻译成中文（Android）

Android 端实时收音，将英文识别结果和中文译文逐句显示。译文语音**只允许通过已连接的耳机播放**；耳机断开时会停止当前播报。设置页只提供麦克风增益。

## 数据流

```text
麦克风 → Silero VAD 分段 → sherpa-onnx SenseVoice-Small int8（英文识别）
       → 句子切分 → CTranslate2 + OPUS-MT int8（English → Chinese）
       → 原文/译文显示 → 系统 TTS（仅耳机）
```

- UI 与服务：Java + Android XML，`minSdk 28`，`arm64-v8a`
- ASR：预编译 sherpa-onnx Android JNI、内置 SenseVoice-Small int8 与 Silero VAD；VAD 定稿后将完整、未门控音频段交给 SenseVoice
- 翻译：仓库现有的预编译 `libvtrans-mt.so`，加载已转换的 CTranslate2 int8 OPUS-MT 英中模型；正常使用不需编译 native 库
- 麦克风增益：0、+6、+12、+18 dB；提高增益也会放大底噪，实际拾音距离受手机麦克风和环境限制
- 播报安全：没有耳机输出时不提交 TTS；拔除耳机时清除待播内容并停止 TTS
- 离线：ASR 与翻译模型均随 APK 内置；运行时不需要 Google Play 服务、网络权限或模型下载

## 内置翻译模型

翻译模型是 [Helsinki-NLP/opus-mt-en-zh](https://huggingface.co/Helsinki-NLP/opus-mt-en-zh) 的 CTranslate2 int8 预转换版本，权重约 76 MiB。预转换文件取自 [jiangzhuo9357/opus-mt-en-zh-ct2](https://huggingface.co/jiangzhuo9357/opus-mt-en-zh-ct2)，固定 revision `06fb49e2f6cb0485043ae703a4c2afddd4e700d7`；下载脚本会对每个模型文件校验 SHA-256。上游 Helsinki-NLP 模型标记为 Apache-2.0；APK 的 `assets/licenses/Apache-2.0.txt` 随包提供许可证文本。

这是英中专用模型，体积和解码开销小于原来的多语种 NLLB 路线，适合实时场景；实际译文质量与速度仍需目标手机和实际语料验证，不能据模型大小推断绝对准确率。

## 内置语音识别模型

ASR 使用 Silero VAD 做语音分段，再将未门控的完整语音段交给 sherpa-onnx SenseVoice-Small int8（固定英文输入）。模型文件固定在 `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` revision `2365baeacb507f821a0c8120fcee3d484dba7a07`，约 228 MiB；开发/打包时由 `tools/fetch_models.sh` 获取并校验 SHA-256，随 APK 内置，手机运行时不下载。模型仓库的许可证指向 FunASR 的 MIT 许可证，随包提供 `assets/licenses/MIT-FunASR.txt`。

## 准备模型

构建前从仓库根目录运行：

```bash
bash tools/fetch_models.sh
```

或 Windows 环境运行：

```powershell
python tools\fetch_en_zh.py
```

脚本获取 Silero VAD、固定 revision 的 SenseVoice-Small int8、OPUS-MT CTranslate2 int8 权重及相应许可证文本，并放入 `app/src/main/assets/`。这是**开发/打包阶段**下载；全部模型会装进最终 APK，手机端不下载任何模型。脚本只取预训练文件，不构建模型或 native 库。

## 构建与版本

```bash
./gradlew :app:assembleDebug
```

每次构建自动从本地台账递增版本号，APK 文件名包含同一构建号，例如 `vtrans-debug-b<buildNo>.apk`。也可以通过 `-PbuildNo=N` 显式指定构建号。测试推包脚本 `scripts/push_apk.ps1` 使用本地递增台账，并以 `VTransPerf` 标签启动日志采集。

构建要求 Android SDK 35、JDK 17+。首次构建仍需获取 Gradle/Android 依赖；手机运行时无需访问 Google Maven 或任何翻译服务。通常不需运行 `native/build_android.sh` 或 `native/build_vtrans_android.sh`；仅在修改 native 源码或预编译库缺失时才需要重编。

## 性能日志

使用 `VTransPerf` 过滤即可查看性能阶段：

```bash
adb logcat -v threadtime -s VTransPerf:V
```

记录包括 APK 版本和设备信息、模型解包/初始化/预热耗时、VAD 定稿段的识别排队和推理耗时、逐句翻译队列等待时间及从识别定稿到译文完成的总耗时。测试时请保留完整的 `VTransPerf`、`MainActivity`、`VadSegmenter` 和 `TranslateService` 日志，并记录测试句子，以便区分模型初始化、VAD 分段、识别排队和实际推理延迟。

## 项目结构

```text
app/src/main/
├── java/com/example/vtrans/
│   ├── MainActivity.java           # 主界面、权限和模型准备
│   ├── SettingsActivity.java       # 麦克风增益设置
│   ├── TranslateService.java       # 后台录音、识别、翻译和耳机播报
│   ├── audio/                      # 音频采集、增益处理、TTS 和音频效果
│   ├── model/                      # 模型清单及 APK assets 解包
│   ├── pipeline/                   # VAD、SenseVoice ASR、句子切分和 JNI 翻译桥接
│   └── util/                       # 设置、性能统计和 WAV 读取
├── assets/
│   ├── models/                     # 随 APK 内置的 VAD、SenseVoice 和 OPUS-MT 模型
│   └── licenses/                   # 随模型分发的许可证
├── cpp/                            # 翻译 JNI/C++ 源码及 CMake 配置
├── jniLibs/arm64-v8a/              # 已有的 Android native 运行库
└── res/                            # 两个界面布局、字符串、主题和启动图标

tools/                              # 模型准备、下载和验证脚本
native/                             # 仅在需要重建 native 库时使用的构建脚本
scripts/                            # APK 推送及真机日志采集脚本
```

模型文件位于 `app/src/main/assets/models/` 和仓库的 `.models/` 缓存目录。清理代码或界面资源时不要删除这些已下载模型；重新准备模型可运行 `bash tools/fetch_models.sh`。

## 修改记录

- **2026-10-09**：以本 README 作为项目文档入口，补充项目结构和变更摘要；清除旧设置界面遗留的多语种、识别档位和后端选项资源，保留当前英中翻译、耳机播报和麦克风增益功能。
- **2026-10-09**：翻译固定为离线英译中，使用随 APK 内置的 OPUS-MT int8 模型和仓库现有 JNI 库；不再要求手机运行时联网或依赖 Google Play 服务。
- **2026-10-09**：加入模型加载、流式识别和逐句翻译的性能日志，并为每次构建自动生成新的版本号及带版本号的 APK 文件名。
- **2026-10-09**：native 翻译初始化失败时将 SentencePiece/CTranslate2 具体错误从 JNI 传回 Java 和性能日志，便于真机诊断。
- **2026-10-09**：根据真机日志停用会丢帧的流式 Zipformer 主路径，改为 Silero VAD 分段后使用随 APK 内置的 SenseVoice-Small int8 识别，并记录每段 ASR 排队与推理耗时。

## 验证建议

1. 断网或在无 Google 服务的手机上安装 APK，验证不需额外下载即可启动并英译中。
2. 播放相同英语测试语料，记录首次预热前后及稳定阶段的识别和翻译耗时，核对原文与译文。
3. 不接耳机时确认译文只上屏、不从手机扬声器播放；接入耳机后确认可播报；播报期间拔掉耳机，确认语音停止。
4. 逐档调整麦克风增益，检查远近场拾音变化及底噪；不要将增益档位理解为保证拾音距离。
