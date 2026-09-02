package com.example.vtrans.model;

/**
 * 模型清单。所有 sha256 与体积都是实测值（见 tools/fetch_models.sh 的输出），
 * 不是估算。首次下载后即可完全离线运行。
 *
 * <p>下载来源：sherpa-onnx 官方 GitHub Release（ASR/VAD）与 HuggingFace（NLLB）。
 */
public final class ModelsManifest {

    private static final String SHERPA =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/";
    private static final String NLLB =
            "https://huggingface.co/JustFrederik/nllb-200-distilled-600M-ct2-int8/resolve/main/";

    // ---- VAD：206KB，必备 ----
    public static final Model VAD = new Model(
            "vad", "Silero VAD v5 (int8)", true, false,
            SHERPA + "silero_vad.int8.onnx",
            "c36d490aff5ab924ca6c7aeec4d8f6bd3d22db6fa17611b9c5b17eae58ac3a20",
            212860L,
            "vad/silero_vad.int8.onnx");

    // ---- SenseVoice：压缩包 159MB，解压后 237MB，均衡档必备 ----
    public static final Model SENSEVOICE = new Model(
            "sensevoice", "SenseVoice-Small (int8)", true, true,
            SHERPA + "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2",
            "7305f7905bfcf77fa0b39388a313f3da35c68d971661a65475b56fb2162c8e63",
            165783878L,
            "sense-voice/model.int8.onnx",
            "sense-voice/tokens.txt");

    // ---- Whisper-small int8：压缩包 610MB，解压后 375MB（只取 int8 两个文件） ----
    public static final Model WHISPER = new Model(
            "whisper", "Whisper-Small (int8)", false, true,
            SHERPA + "sherpa-onnx-whisper-small.tar.bz2",
            "486a46afbb7ba798507190ffe02fea2dd726049af212e774537efac6afb210a6",
            639387718L,
            "whisper/small-encoder.int8.onnx",
            "whisper/small-decoder.int8.onnx",
            "whisper/small-tokens.txt");

    // ---- NLLB-200-distilled-600M（CTranslate2 int8）：622MB，三个裸文件 ----
    public static final Model NLLB_MODEL = new Model(
            "nllb", "NLLB-200-distilled-600M (CT2 int8)", true, false,
            NLLB + "model.bin",
            "ed1beaf75134de7505315a5223162f56acff397eff6b50638a500d3936fe707b",
            622595991L,
            "nllb/model.bin");

    public static final Model NLLB_TOKENIZER = new Model(
            "nllb_spm", "NLLB 分词模型", true, false,
            NLLB + "sentencepiece.bpe.model",
            "14bb8dfb35c0ffdea7bc01e56cea38b9e3d5efcdcb9c251d6b40538e1aab555a",
            4852054L,
            "nllb/sentencepiece.bpe.model");

    public static final Model NLLB_VOCAB = new Model(
            "nllb_vocab", "NLLB 词表", true, false,
            NLLB + "shared_vocabulary.txt",
            "a132a83330f45514c2476eb81d1d69b3c41762264d16ce0a7ea982e5d6c728e5",
            2568098L,
            "nllb/shared_vocabulary.txt");

    public static final Model[] ALL = {
            VAD, SENSEVOICE, NLLB_MODEL, NLLB_TOKENIZER, NLLB_VOCAB, WHISPER,
    };

    /** 首次启动就必须有的模型（Whisper 是可选的，用到非中文时再提示下载） */
    public static Model[] required() {
        return new Model[]{VAD, SENSEVOICE, NLLB_MODEL, NLLB_TOKENIZER, NLLB_VOCAB};
    }

    public static long requiredBytes() {
        long total = 0;
        for (Model m : required()) total += m.downloadBytes;
        return total;
    }

    /** 模型条目。archive=true 表示下载的是 tar.bz2，files 是解包后要保留的文件。 */
    public static final class Model {
        public final String id;
        public final String label;
        public final boolean required;
        public final boolean archive;
        public final String url;
        public final String sha256;
        public final long downloadBytes;
        public final String[] files;

        Model(String id, String label, boolean required, boolean archive,
              String url, String sha256, long downloadBytes, String... files) {
            this.id = id;
            this.label = label;
            this.required = required;
            this.archive = archive;
            this.url = url;
            this.sha256 = sha256;
            this.downloadBytes = downloadBytes;
            this.files = files;
        }
    }

    private ModelsManifest() {
    }
}
