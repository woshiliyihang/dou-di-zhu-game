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

/**
 * ASR 引擎：SenseVoice（快）与 Whisper-small（准）二选一，按语种和档位决定。
 *
 * <p>实测结论（tools/engine_compare.py）：
 * <ul>
 *   <li>SenseVoice int8 中文/粤语很准，RTF≈0.06；但日/韩/英基本不可用。</li>
 *   <li>Whisper-small int8 各语种都明显更准，代价是慢 6~10 倍（RTF≈0.35）。</li>
 * </ul>
 * 所以默认走"SenseVoice 先出结果，发现不是中文再用 Whisper 重跑"的组合。
 */
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
        OfflineRecognizer wh = null;
        String lang = whisperLang(sourceLang);
        if (mm.isReady(com.example.vtrans.model.ModelsManifest.SENSEVOICE)) {
            sv = buildSenseVoice(mm, provider, threads, sourceLang);
        }
        if (mm.isReady(com.example.vtrans.model.ModelsManifest.WHISPER)) {
            wh = buildWhisper(mm, provider, threads, lang);
        }
        if (sv == null && wh == null) {
            throw new IllegalStateException("没有任何可用的 ASR 模型，请先下载");
        }
        return new AsrEngine(sv, wh, lang);
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
     * （375MB 模型，重建约 1~2s），所以只在真的切换语种时才做。
     */
    public synchronized void switchWhisperLang(ModelManager mm, String provider,
                                               int threads, String detectedLang) {
        String want = whisperLang(detectedLang);
        if (whisper != null && want.equals(whisperLang)) return;
        safeRelease(whisper);
        whisper = buildWhisper(mm, provider, threads, want);
        whisperLang = want;
    }

    /** @return 识别文本；失败返回 null */
    public String transcribe(Which which, float[] samples) {
        OfflineRecognizer r = which == Which.WHISPER ? whisper : senseVoice;
        if (r == null || samples == null || samples.length == 0) return null;
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
    static String cleanup(String text) {
        if (text == null) return null;
        String s = text.replaceAll("<\\|[^|]*\\|>", "").trim();
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
