package com.example.vtrans.util;

import android.content.Context;
import android.content.SharedPreferences;

/** 所有设置的唯一读写入口，避免各处散落 getSharedPreferences。 */
public final class Prefs {
    public static final String FILE = "vtrans";

    /** 目标语言：zh / en */
    private static final String K_TARGET_LANG = "target_lang";
    /** 源语言：auto / zh / en / ja / ko / yue / fr / de / es / ru ... */
    private static final String K_SOURCE_LANG = "source_lang";
    /** 档位：balanced（均衡）/ quality（高精） */
    private static final String K_TIER = "tier";
    /** ASR provider：auto / nnapi / xnnpack / cpu */
    private static final String K_PROVIDER = "provider";
    /** MT 线程数 */
    private static final String K_MT_THREADS = "mt_threads";
    /** MT beam：1 或 4 */
    private static final String K_MT_BEAM = "mt_beam";
    /** 增量预览间隔 ms */
    private static final String K_PARTIAL_INTERVAL = "partial_interval";
    /** 基准测试测出来的最佳 provider */
    private static final String K_BENCH_PROVIDER = "bench_provider";
    /** 收音增益（远场增强），单位 dB：0 / 6 / 12 */
    private static final String K_MIC_BOOST_DB = "mic_boost_db";
    /**
     * 语音前置处理方案：auto / system / software / off。
     * 见 AUDIO_AUTO 等常量；改动在下次点「开始翻译」时生效。
     */
    private static final String K_AUDIO_MODE = "audio_mode";
    /** 译文语音播报是否仅限耳机（默认开：避免外放被麦克风拾取形成回环） */
    private static final String K_TTS_HEADSET_ONLY = "tts_headset_only";

    public static final String TIER_BALANCED = "balanced";
    public static final String TIER_QUALITY = "quality";

    /** 自动：优先用系统 AEC/NS/AGC，系统缺哪项软件自动补（高通+门控+AGC） */
    public static final String AUDIO_AUTO = "auto";
    /** 系统链路：通话音源走手机自带 AEC/NS/AGC，缺项软件补 */
    public static final String AUDIO_SYSTEM = "system";
    /** 纯软件：不挂系统效果，全部用本机 DSP 算法 */
    public static final String AUDIO_SOFTWARE = "software";
    /** 关闭：输出原始信号 */
    public static final String AUDIO_OFF = "off";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String targetLang() {
        return sp.getString(K_TARGET_LANG, "zh");
    }

    public void setTargetLang(String v) {
        sp.edit().putString(K_TARGET_LANG, v).apply();
    }

    public String sourceLang() {
        return sp.getString(K_SOURCE_LANG, "auto");
    }

    public void setSourceLang(String v) {
        sp.edit().putString(K_SOURCE_LANG, v).apply();
    }

    public String tier() {
        return sp.getString(K_TIER, TIER_BALANCED);
    }

    public void setTier(String v) {
        sp.edit().putString(K_TIER, v).apply();
    }

    /** 用户选择的 provider；auto 表示用基准测试的结果 */
    public String provider() {
        return sp.getString(K_PROVIDER, "auto");
    }

    public void setProvider(String v) {
        sp.edit().putString(K_PROVIDER, v).apply();
    }

    /** 基准测试选出的 provider，provider() 为 auto 时使用 */
    public String benchedProvider() {
        return sp.getString(K_BENCH_PROVIDER, "");
    }

    public void setBenchedProvider(String v) {
        sp.edit().putString(K_BENCH_PROVIDER, v).apply();
    }

    public String audioMode() {
        return sp.getString(K_AUDIO_MODE, AUDIO_AUTO);
    }

    /** 收音预增益 dB：0=标准 / 6=增强 / 12=远场。远场档用于手机放远、免提、人声偏小场景。 */
    public int micBoostDb() {
        return sp.getInt(K_MIC_BOOST_DB, 0);
    }

    public void setMicBoostDb(int v) {
        sp.edit().putInt(K_MIC_BOOST_DB, Math.max(0, Math.min(12, v))).apply();
    }

    /** 译文语音播报：true=仅检测到耳机时才朗读；false=是否插耳机都朗读。默认开启。 */
    public boolean ttsHeadsetOnly() {
        return sp.getBoolean(K_TTS_HEADSET_ONLY, true);
    }

    public void setTtsHeadsetOnly(boolean v) {
        sp.edit().putBoolean(K_TTS_HEADSET_ONLY, v).apply();
    }

    public void setAudioMode(String v) {
        sp.edit().putString(K_AUDIO_MODE, v).apply();
    }

    public int mtThreads() {
        return sp.getInt(K_MT_THREADS, defaultThreads());
    }

    public void setMtThreads(int v) {
        sp.edit().putInt(K_MT_THREADS, v).apply();
    }

    public int mtBeam() {
        return sp.getInt(K_MT_BEAM, 1);
    }

    public void setMtBeam(int v) {
        sp.edit().putInt(K_MT_BEAM, v).apply();
    }

    public int partialIntervalMs() {
        return sp.getInt(K_PARTIAL_INTERVAL, 800);
    }

    public void setPartialIntervalMs(int v) {
        sp.edit().putInt(K_PARTIAL_INTERVAL, v).apply();
    }

    /** 高精档不做增量预览：Whisper 单次解码就要 1~2s，增量只会拖慢最终结果 */
    public boolean partialsEnabled() {
        return TIER_BALANCED.equals(tier());
    }

    /** 高精档 beam=4，均衡档 beam=1 */
    public int beamForTier() {
        return TIER_QUALITY.equals(tier()) ? 4 : 1;
    }

    static int defaultThreads() {
        // 骁龙 888：1×X1 + 3×A78 + 4×A55。开满 4 个能跑的核，再多只会抢音频线程。
        int n = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(4, n));
    }
}
