package com.example.vtrans.pipeline;

/**
 * NLLB-200 (CTranslate2 int8) 翻译引擎的 JNI 封装。
 *
 * <p>对应 native 实现在 app/src/main/cpp/{mt_engine.cpp,jni_bridge.cpp}，
 * 那份源码在 x86 与 arm64 上跑同一套逻辑（见 tools/host_test 的验证结果）。
 */
public final class MtEngine {

    static {
        System.loadLibrary("vtrans-mt");
    }

    private long handle;

    private MtEngine(long handle) {
        this.handle = handle;
    }

    /** @return 引擎实例；模型加载失败返回 null（622MB 的模型要 0.3~1s） */
    public static MtEngine create(String modelDir, int threads, int beam) {
        long h = nativeInit(modelDir, threads, beam);
        return h == 0 ? null : new MtEngine(h);
    }

    /** @return 译文；失败返回 null（调用方要降级处理，不能让整条管线挂掉） */
    public String translate(String text, String srcLang, String tgtLang) {
        if (handle == 0 || text == null || text.isEmpty()) return null;
        return nativeTranslate(handle, text, srcLang, tgtLang);
    }

    /** 批量翻译，句子之间共享一次解码调用。srcLang 对所有句子生效。 */
    public String[] translateBatch(String[] texts, String srcLang, String tgtLang) {
        if (handle == 0 || texts == null || texts.length == 0) return null;
        return nativeTranslateBatch(handle, texts, srcLang, tgtLang);
    }

    public void setBeam(int beam) {
        if (handle != 0) nativeSetBeam(handle, beam);
    }

    public void setThreads(int threads) {
        if (handle != 0) nativeSetThreads(handle, threads);
    }

    public void destroy() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }

    public boolean valid() {
        return handle != 0;
    }

    /**
     * 按文字的书写系统猜源语言，返回 NLLB 语言码（zho_Hans / eng_Latn ...）。
     * 用来给 NLLB 指定源语言：识别出来的文本是英文却按中文喂进去，译文会完全跑偏。
     */
    public static String detectLang(String text) {
        String r = nativeDetectLang(text);
        return r == null ? "eng_Latn" : r;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            destroy();
        } finally {
            super.finalize();
        }
    }

    private static native long nativeInit(String modelDir, int threads, int beam);

    private static native String nativeTranslate(long handle, String text,
                                                 String srcLang, String tgtLang);

    private static native String[] nativeTranslateBatch(long handle, String[] texts,
                                                        String srcLang, String tgtLang);

    private static native void nativeSetBeam(long handle, int beam);

    private static native void nativeSetThreads(long handle, int threads);

    private static native void nativeDestroy(long handle);

    private static native String nativeDetectLang(String text);
}
