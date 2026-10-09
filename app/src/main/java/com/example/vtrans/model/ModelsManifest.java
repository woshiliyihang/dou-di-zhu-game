package com.example.vtrans.model;

/**
 * 模型清单。
 *
 * <p>模型文件随 APK 一起打包在 <code>assets/models/</code> 下（见 app/build.gradle 的
 * noCompress 配置），首次启动时由 {@link ModelManager} 解包到 app 私有目录。
 * 全程不联网：装完 APK 就能离线跑。
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

    // ---- SenseVoice-Small (int8)：解压后约 237MB，均衡档必备 ----
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

    // ---- NLLB-200-distilled-600M（CTranslate2 int8）：622MB ----
    // config.json 是 CTranslate2 自己要读的模型配置（<模型目录>/config.json），
    // 少了它 ctranslate2::Translator 会直接构造失败，报出来的错却不含文件名。
    public static final Model NLLB_MODEL = new Model(
            "nllb", "NLLB-200-distilled-600M (CT2 int8)", true,
            "nllb/model.bin",
            "nllb/config.json");

    public static final Model NLLB_TOKENIZER = new Model(
            "nllb_spm", "NLLB 分词模型", true,
            "nllb/sentencepiece.bpe.model");

    public static final Model NLLB_VOCAB = new Model(
            "nllb_vocab", "NLLB 词表", true,
            "nllb/shared_vocabulary.txt");

    // ---- 流式英文 Zipformer (int8)：英→中方向的主识别器，可选 ----
    // 与 SenseVoice 的整段识别不同：边说边出字，句子边界由 endpoint 规则判。
    // 好处不是“算得更快”，而是“说话的那段时间已经在算了”——说完之后
    // 几乎不需要再等识别，判停等待和整段重跑一起消失。
    public static final Model ZIPFORMER_EN = new Model(
            "zipformer-en", "Streaming Zipformer EN (int8)", false,
            "zipformer-en/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "zipformer-en/decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
            "zipformer-en/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "zipformer-en/tokens.txt");

    // ---- OPUS-MT en→zh（CTranslate2）：约 60~120MB，可选，只做英→中时拿它替掉 NLLB ----
    // CT2 解码是内存带宽受限的：每生成一个 token 都要把整个权重读一遍。
    // NLLB-600M int8 ≈600MB → 实测每 token 约 45ms；这个模型 ~60MB → 个位数 ms。
    // 它又只在英/中这一对语言上训过，准确率不比多语模型差，代价是换对语言就没法用。
    //
    // 分词器文件名以仓库实际内容为准（source.spm / target.spm）：native 侧
    // firstExisting 的候选里就有 source.spm，而它拿「有没有 sentencepiece.bpe.model」
    // 当作要不要加 NLLB 语言码前缀的判据——所以这个目录里**绝对不能出现**
    // sentencepiece.bpe.model，否则会被当成 NLLB 处理、硬塞 eng_Latn 前缀。
    // shared_vocabulary.json 是 CTranslate2 自己读的词条表，不能缺。
    // 下完还要配套 libvtrans-mt.so（云端重编的那份），旧 .so 加载它会失败并自动退回 NLLB。
    public static final Model OPUS_MT_EN_ZH = new Model(
            "opus_mt_en_zh", "OPUS-MT en→zh (CT2)", false,
            "opus-mt-en-zh/model.bin",
            "opus-mt-en-zh/config.json",
            "opus-mt-en-zh/source.spm",
            "opus-mt-en-zh/target.spm",
            "opus-mt-en-zh/shared_vocabulary.json");

    public static final Model[] ALL = {
            VAD, SENSEVOICE, NLLB_MODEL, NLLB_TOKENIZER, NLLB_VOCAB, WHISPER,
            ZIPFORMER_EN, OPUS_MT_EN_ZH,
    };

    /** 启动就必须有的模型（Whisper 是可选的） */
    public static Model[] required() {
        return new Model[]{VAD, SENSEVOICE, NLLB_MODEL, NLLB_TOKENIZER, NLLB_VOCAB};
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
