package com.example.vtrans.pipeline;

import android.util.Log;

import com.example.vtrans.model.ModelManager;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;

import java.util.Locale;

/** SenseVoice-Small offline recognizer; VAD supplies complete, trimmed utterances. */
public final class AsrEngine {

    private static final String TAG = "AsrEngine";
    public static final int SAMPLE_RATE = 16000;

    public enum Which { SENSEVOICE, WHISPER }

    private final OfflineRecognizer senseVoice;
    private OfflineRecognizer whisper;
    private String whisperLang;

    private AsrEngine(OfflineRecognizer senseVoice, OfflineRecognizer whisper,
                      String whisperLang) {
        this.senseVoice = senseVoice;
        this.whisper = whisper;
        this.whisperLang = whisperLang;
    }

    public static AsrEngine create(ModelManager mm, String provider, int threads,
                                   String sourceLang) {
        OfflineRecognizer sv = null;
        if (mm.isReady(com.example.vtrans.model.ModelsManifest.SENSEVOICE)) {
            sv = buildSenseVoice(mm, provider, threads, sourceLang);
        }
        if (sv == null) {
            throw new IllegalStateException("没有可用的 SenseVoice 模型，请先下载/解包");
        }
        // Whisper 不在启动时强载（375MB，中文用户根本用不到）：
        // 由 switchWhisperLang() 在第一次真的需要时按语种加载。
        return new AsrEngine(sv, null, whisperLang(sourceLang));
    }

    /** 当前 Whisper 用的语种；语种不变时不会重建，避免每句话都重加载 375MB 模型 */
    public String whisperLang() {
        return whisperLang;
    }

    public boolean hasSenseVoice() {
        return senseVoice != null;
    }

    public boolean hasWhisper() {
        return whisper != null;
    }

    /**
     * Whisper 不接受 "auto"，语种只能显式指定。语种变了必须重建识别器
     * （375MB 模型，重建约 1~2s），所以只在真的需要时才加载/切换。
     * 模型没解包（WHISPER 可选没装）时置空，识别方会优雅退回 SenseVoice。
     */
    public synchronized void switchWhisperLang(ModelManager mm, String provider,
                                               int threads, String detectedLang) {
        String want = whisperLang(detectedLang);
        if (whisper != null && want.equals(whisperLang)) return;
        if (!mm.isReady(com.example.vtrans.model.ModelsManifest.WHISPER)) {
            Log.i(TAG, "Whisper 模型未就绪，本次跳过（继续用 SenseVoice）");
            safeRelease(whisper);
            whisper = null;
            whisperLang = want;
            return;
        }
        Log.i(TAG, "加载 Whisper (" + want + ") …");
        long t0 = System.currentTimeMillis();
        safeRelease(whisper);
        whisper = buildWhisper(mm, provider, threads, want);
        whisperLang = want;
        Log.i(TAG, "Whisper (" + want + ") 加载完成，耗时 " + (System.currentTimeMillis() - t0) + "ms");
    }

    /** @return 识别文本；失败返回 null */
    public String transcribe(Which which, float[] samples) {
        OfflineRecognizer r = which == Which.WHISPER ? whisper : senseVoice;
        if (r == null || samples == null || samples.length == 0) return null;
        // 整段响度归一化：SenseVoice/Whisper 的 fbank 只减训练集的全局均值，
        // 不是逐句归一，所以输入峰值偏低时识别率会跳崖式下跌。这一步对轻声场景
        // 比任何前端调参都直接。（注意：会原地改 samples，调用方必须传自己拥有的副本）
        normalizePeak(samples);
        OfflineStream stream = null;
        try {
            stream = r.createStream();
            stream.acceptWaveform(samples, SAMPLE_RATE);
            r.decode(stream);
            OfflineRecognizerResult result = r.getResult(stream);
            return result == null ? null : cleanup(result.getText());
        } catch (Throwable t) {
            Log.e(TAG, "识别失败: " + which, t);
            return null;
        } finally {
            if (stream != null) {
                try {
                    stream.release();
                } catch (Throwable ignored) {
                    // 释放失败不影响主流程
                }
            }
        }
    }

    /** SenseVoice 在 auto 模式下偶尔会带出 &lt;|zh|&gt; 这类标签，去掉再上屏 */
    private static final java.util.regex.Pattern TAG_PATTERN =
            java.util.regex.Pattern.compile("<\\|[^|]*\\|>");

    // ---------- 整段响度归一化 ----------
    /** 目标峰值 ≈ -3dBFS */
    private static final float NORM_TARGET_PEAK = 0.7f;
    /** 峰值已经到 -16dBFS 以上就不动，避免把本来就响的句子推到限幅区 */
    private static final float NORM_MIN_PEAK = 0.15f;
    /** 最多抬 ≈+21.5dB：再多的话一段纯噪声会被抬成“很响的噪声”，反而诱使模型编造文字 */
    private static final float NORM_MAX_GAIN = 12.0f;
    /** 低于此峰值视为静音/无信号，不抬 */
    private static final float NORM_NOISE_PEAK = 1e-4f;

    /** 原地把整段峰值抬到 {@link #NORM_TARGET_PEAK}，只抬不压。 */
    static void normalizePeak(float[] x) {
        if (x == null || x.length == 0) return;
        float peak = 0;
        for (float v : x) {
            float a = Math.abs(v);
            if (a > peak) peak = a;
        }
        if (peak < NORM_NOISE_PEAK || peak >= NORM_MIN_PEAK) return;
        float g = NORM_TARGET_PEAK / peak;
        if (g > NORM_MAX_GAIN) g = NORM_MAX_GAIN;
        for (int i = 0; i < x.length; i++) x[i] *= g;
    }

    static String cleanup(String text) {
        if (text == null) return null;
        String s = TAG_PATTERN.matcher(text).replaceAll("").trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * whisper-small 不接受 "auto"，也不认识粤语 yue，这里做一次降级映射。
     */
    public static String whisperLang(String detectedOrSetting) {
        String s = detectedOrSetting == null ? "" : detectedOrSetting.toLowerCase(Locale.ROOT);
        switch (s) {
            case "zh":
            case "zh_cn":
            case "zho_hans":
            case "cmn":
            case "zh_hans":
                return "zh";
            case "yue":
            case "zh_hk":
            case "zh_tw":
            case "zho_hant":
                return "zh";   // whisper-small 没有粤语/繁体，回退到中文
            case "ja":
            case "jpn_jpan":
            case "jp":
                return "ja";
            case "ko":
            case "kor_hang":
            case "kr":
                return "ko";
            case "en":
            case "eng_latn":
                return "en";
            case "fr":
                return "fr";
            case "de":
                return "de";
            case "es":
                return "es";
            case "ru":
                return "ru";
            default:
                return "en";
        }
    }

    private static OfflineRecognizer buildSenseVoice(ModelManager mm, String provider,
                                                     int threads, String sourceLang) {
        OfflineRecognizerConfig cfg = new OfflineRecognizerConfig();
        OfflineModelConfig model = cfg.getModelConfig();
        model.getSenseVoice().setModel(mm.senseVoiceModel().getAbsolutePath());
        model.getSenseVoice().setLanguage(
                sourceLang == null || sourceLang.isEmpty() || "auto".equals(sourceLang)
                        ? "auto" : sourceLang);
        model.getSenseVoice().setUseInverseTextNormalization(true);
        model.setTokens(mm.senseVoiceTokens().getAbsolutePath());
        model.setNumThreads(threads);
        model.setProvider(provider);
        model.setDebug(false);
        cfg.getFeatConfig().setSampleRate(SAMPLE_RATE);
        cfg.getFeatConfig().setFeatureDim(80);
        return new OfflineRecognizer(null, cfg);
    }

    private static OfflineRecognizer buildWhisper(ModelManager mm, String provider,
                                                  int threads, String sourceLang) {
        OfflineRecognizerConfig cfg = new OfflineRecognizerConfig();
        OfflineModelConfig model = cfg.getModelConfig();
        model.getWhisper().setEncoder(mm.whisperEncoder().getAbsolutePath());
        model.getWhisper().setDecoder(mm.whisperDecoder().getAbsolutePath());
        model.getWhisper().setLanguage(whisperLang(sourceLang));
        model.getWhisper().setTask("transcribe");
        model.setTokens(mm.whisperTokens().getAbsolutePath());
        model.setNumThreads(threads);
        model.setProvider(provider);
        model.setDebug(false);
        FeatureConfig feat = cfg.getFeatConfig();
        feat.setSampleRate(SAMPLE_RATE);
        feat.setFeatureDim(80);
        return new OfflineRecognizer(null, cfg);
    }

    public void release() {
        safeRelease(senseVoice);
        safeRelease(whisper);
        whisper = null;
    }

    private static void safeRelease(OfflineRecognizer r) {
        if (r == null) return;
        try {
            r.release();
        } catch (Throwable t) {
            Log.w(TAG, "释放识别器异常", t);
        }
    }
}
