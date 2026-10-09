package com.example.vtrans.model;

/**
 * 模型清单。
 *
 * <p>语音识别模型随 APK 一起打包在 <code>assets/models/</code> 下（见 app/build.gradle
 * 的 noCompress 配置），首次启动时由 {@link ModelManager} 解包到 app 私有目录。
 * 英→中 CTranslate2 int8 翻译模型也随 APK 打包，首次运行时解包到应用私有目录。
 *
 * <p><code>files</code> 是相对路径，既表示解包后的位置（相对 modelsDir），
 * 也表示 APK 内的位置（相对 assets/models/）。
 *
 * <p>模型的来源与实测体积见 tools/fetch_models.sh（下载）与 README「模型」一节。
 */
public final class ModelsManifest {

    /** APK 内模型的根目录 */
    public static final String ASSET_ROOT = "models";

    // ---- VAD：206KB，必备 ----
    public static final Model VAD = new Model(
            "vad", "Silero VAD v5 (int8)", true,
            "vad/silero_vad.int8.onnx");

    // ---- SenseVoice-Small (int8)：离线英文识别主模型 ----
    public static final Model SENSEVOICE = new Model(
            "sensevoice", "SenseVoice-Small (int8)", true,
            "sense-voice/model.int8.onnx",
            "sense-voice/tokens.txt");

    // ---- Whisper-small (int8)：解压后约 250MB，可选 ----
    // 没有它也能跑（均衡档只用 SenseVoice）；有它时非中文会自动改用 Whisper 重跑。
    public static final Model WHISPER = new Model(
            "whisper", "Whisper-Small (int8)", false,
            "whisper/small-encoder.int8.onnx",
            "whisper/small-decoder.int8.onnx",
            "whisper/small-tokens.txt");

    // ---- 流式英文 Zipformer (int8)：保留旧资源定义，不再用于识别 ----
    public static final Model ZIPFORMER_EN = new Model(
            "zipformer-en", "Streaming Zipformer EN (int8)", false,
            "zipformer-en/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "zipformer-en/decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
            "zipformer-en/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "zipformer-en/tokens.txt");

    // ---- CTranslate2 Marian/OPUS-MT 英中 int8：固定离线翻译模型 ----
    public static final Model OPUS_MT_EN_ZH = new Model(
            "opus-mt-en-zh", "OPUS-MT English→Chinese (int8)", true,
            "opus-mt-en-zh/model.bin",
            "opus-mt-en-zh/config.json",
            "opus-mt-en-zh/shared_vocabulary.json",
            "opus-mt-en-zh/source.spm",
            "opus-mt-en-zh/target.spm");

    /** 启动必需：VAD、SenseVoice 英文识别与英中翻译模型。 */
    public static Model[] required() {
        return new Model[]{VAD, SENSEVOICE, OPUS_MT_EN_ZH};
    }

    /** 模型条目：files 是相对路径，archive 那套下载/解压逻辑已经随离线打包去掉了。 */
    public static final class Model {
        public final String id;
        public final String label;
        public final boolean required;
        public final String[] files;

        Model(String id, String label, boolean required, String... files) {
            this.id = id;
            this.label = label;
            this.required = required;
            this.files = files;
        }
    }

    private ModelsManifest() {
    }
}
