package com.example.vtrans.pipeline;

import android.util.Log;

import com.example.vtrans.model.ModelManager;
import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.util.Locale;

/**
 * 流式识别（sherpa-onnx OnlineRecognizer + Zipformer transducer）。
 *
 * <p>与 {@link AsrEngine} 的整段识别是两种完全不同的时序：
 * <ul>
 *   <li>整段式：等 VAD 判停 → 把整段音频一次性喂进去 → 算完才出字。所以「说完」
 *       到「有文本」之间必然隔着一段判停等待 + 一段与音频长度成正比的识别时间。</li>
 *   <li>流式：每来一小段音频就推进一次解码，字是跟着话音往外冒的；句子边界由
 *       endpoint 规则判。<b>说完的那一刻，这句话其实已经算完了</b>，剩下的只有判停
 *       的静音确认时间。</li>
 * </ul>
 *
 * <p>这也是本方向（英→中）该用的模型：SenseVoice 的英文实测不可用，而 Whisper 是
 * 整段自回归、手机上一句要好几秒，只有流式 zipformer 能同时满足「英文够准」和
 * 「不额外花时间」。
 *
 * <p><b>线程</b>：{@link #feed} 必须在专用的单线程上调用，不能放录音线程——一次
 * decode 要几毫秒到十几毫秒，会把 20ms 一帧的节拍拖崩；一个 {@code OnlineStream}
 * 也只允许这一条线程碰。
 */
public final class StreamingAsr {

    private static final String TAG = "StreamingAsr";
    private static final String PERF_TAG = "VTransPerf";

    public static final int SAMPLE_RATE = 16000;

    /**
     * 说过话之后连续静音多久算一句说完。这是流式方案里唯一还需要「等」的时间，
     * 定得比 VAD 的 0.32s 略长一点，因为流式没有段尾保留静音的额外边距，
     * 而且这里等的时候并不欠识别时间（文本已经算好了）。
     */
    private static final float ENDPOINT_SILENCE = 0.35f;
    /** 一整段都没出声时的兜底断句：清掉累计的静音，免得静音越攒越长 */
    private static final float ENDPOINT_SILENCE_NO_SPEECH = 1.5f;
    /** 一口气说太久强制断句，避免单句过长把 MT 拖垮 */
    private static final float ENDPOINT_MAX_UTTERANCE = 15f;

    /**
     * 首次解码前至少要喂进去多少样本。
     *
     * <p>模型是 chunk-16-left-128：一次解码要吞掉 16 帧新特征 + 128 帧左上下文，
     * 按 10ms/帧算是 1.44s。这里取 1.5s（真机日志显示解码 1 次要 45 帧可用，
     * 1.5s = 150 帧，余量充足）。
     *
     * <p><b>为什么不再用 {@code recognizer.isReady} 做门控</b>：真机日志里连续
     * 51s 音频、5100+ 帧之后它仍然返回 false——那个帧数计数在这条路径上不涨，
     * 于是一次解码都不放行，屏幕上永远不出字。既然不能信它，就自己按 chunk
     * 节拍放行：宁可把时机算得保守，也不能撞上 features.cc 那个 exit(-1)。
     */
    private static final int FIRST_DECODE_SAMPLES = 16000 * 15 / 10;
    /**
     * 每多攒多少样本才多解一次。
     *
     * <p>真机反推出来的数：崩前那一行是 {@code 352 + 45 > 391}——已吃掉 352 帧、
     * 再要 45 帧、总共只有 391 帧，而它是 7 次解码之后到 352 的，所以
     * <b>一次 decode 大约吃掉 50 帧</b>。之前按 16 帧（160ms）的节拍放行，
     * 喂入永远追不上消耗，迟早踩到「剩余 < 45 帧」那条线——那就是上一轮
     * {@code 0 + 45 > 1} 的同一个不变式，只是方向相反。
     * <p>这里取 600ms（60 帧）：新喂 60 帧 > 吃掉 50 帧，未消耗帧缓慢上升，
     * 安全余量靠时间累积而不是靠一次留很多。
     * <p>真机测得单次解码只花 29ms（7.1s 音频解 5 次共 145ms），算力上有二十倍
     * 余量，节拍再往 500ms 压就得靠校准上面的 50 帧估值了——心跳日志里那个
     * 「余 N 帧」就是用来校它的。
     */
    private static final int DECODE_STRIDE_SAMPLES = 16000 * 6 / 10;

    private final OnlineRecognizer recognizer;
    private final OnlineStream stream;
    private final Object lock = new Object();

    private volatile boolean released;
    /** 最近一次解码出来的增量文本，给 UI 做「正在说」的预览用 */
    private volatile String partial = "";
    /** partial 是否有新内容可读走一次（避免每帧都广播） */
    private volatile boolean partialDirty;
    /** 解码节奏自测：每帧平均花多少毫秒。帧长 20ms，超了就是跟不上实时 */
    private long decodeMsSum;
    private long decodeMsMax;
    private int decodeCount;
    /** 距上次复位累计喂进去的样本数，只给 {@link #FIRST_DECODE_SAMPLES} 用 */
    private int samplesSinceReset;
    /** 已经解过多少个 chunk，用来算这一帧该不该再解一次 */
    private int decodedChunks;
    /** 首次解码放行只在第一次打一行日志，用来核对上面那个 2s 估算准不准 */
    private boolean gateLogged;
    /** 诊断心跳计数：feed 被调到第几帧（排「到底有没有帧进来」用） */
    private int feedCalls;

    private StreamingAsr(OnlineRecognizer recognizer, OnlineStream stream) {
        this.recognizer = recognizer;
        this.stream = stream;
    }

    /** @return 引擎；模型缺失或加载失败返回 null，固定英中流程会报告启动错误 */
    public static StreamingAsr create(ModelManager mm, String provider, int threads) {
        if (mm == null || !mm.isReady(com.example.vtrans.model.ModelsManifest.ZIPFORMER_EN)) {
            return null;
        }
        try {
            OnlineRecognizerConfig cfg = new OnlineRecognizerConfig();

            FeatureConfig feat = cfg.getFeatConfig();
            feat.setSampleRate(SAMPLE_RATE);
            feat.setFeatureDim(80);

            OnlineTransducerModelConfig tr = new OnlineTransducerModelConfig();
            tr.setEncoder(mm.zipformerEncoder().getAbsolutePath());
            tr.setDecoder(mm.zipformerDecoder().getAbsolutePath());
            tr.setJoiner(mm.zipformerJoiner().getAbsolutePath());

            OnlineModelConfig model = cfg.getModelConfig();
            model.setTransducer(tr);
            model.setTokens(mm.zipformerTokens().getAbsolutePath());
            model.setNumThreads(threads);
            // 流式模型的 NNAPI/XNNPACK 路径在这类 chunk 化的 zipformer 上并没有稳定
            // 收益（算子回退 + 反复转换 layout），cpu 反而是最快且行为可预期的一档。
            model.setProvider(provider == null || "auto".equals(provider) ? "cpu" : provider);
            model.setDebug(false);

            EndpointConfig ep = new EndpointConfig(
                    new EndpointRule(false, ENDPOINT_SILENCE_NO_SPEECH, 0f),
                    new EndpointRule(true, ENDPOINT_SILENCE, 0f),
                    new EndpointRule(false, 0f, ENDPOINT_MAX_UTTERANCE));
            cfg.setEndpointConfig(ep);
            cfg.setEnableEndpoint(true);
            cfg.setDecodingMethod("greedy_search");
            cfg.setMaxActivePaths(4);

            long t0 = System.currentTimeMillis();
            OnlineRecognizer r = new OnlineRecognizer(null, cfg);
            Log.i(PERF_TAG, "stage=asr_initialize status=ok elapsed_ms="
                    + (System.currentTimeMillis() - t0) + " provider="
                    + (provider == null ? "cpu" : provider) + " threads=" + threads);
            Log.i(TAG, "流式识别器就绪，加载 " + (System.currentTimeMillis() - t0) + "ms");
            return new StreamingAsr(r, r.createStream(""));
        } catch (Throwable t) {
            Log.w(TAG, "流式识别器不可用，固定英中流程无法启动", t);
            Log.e(PERF_TAG, "stage=asr_initialize status=error", t);
            return null;
        }
    }

    /**
     * 喂一段音频并推进解码。
     *
     * @return endpoint 命中时返回该句的完整文本（同时已复位流）；否则返回 null。
     *         命中但整段没出字（纯静音）也返回 null——不复用空文本占位，省得上屏一串空白。
     */
    public String feed(float[] pcm) {
        if (released || pcm == null || pcm.length == 0) return null;
        synchronized (lock) {
            if (released) return null;
            long d0 = System.currentTimeMillis();
            boolean hasProgress = false;
            samplesSinceReset += pcm.length;
            try {
                stream.acceptWaveform(pcm, SAMPLE_RATE);
                // 解码时机完全由样本数推出来，不认 isReady（理由见上面常量注释）。
                // 每攒够一个 chunk 解一次：解少了出字慢，解多了会把没攒够的特征
                // 抽干——那就是上一轮 exit(-1) 的成因。
                if (samplesSinceReset >= FIRST_DECODE_SAMPLES) {
                    int due = (samplesSinceReset - FIRST_DECODE_SAMPLES) / DECODE_STRIDE_SAMPLES
                            - decodedChunks;
                    while (due-- > 0) {
                        recognizer.decode(stream);
                        decodedChunks++;
                        hasProgress = true;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "流式解码失败", t);
                return null;
            }
            if (!hasProgress) {
                // 还在攒第一个 chunk，这一帧没产生任何新文本，
                // 既不用刷新预览也不用计时，直接回去等下一帧。
                // 心跳：每 50 帧报一次，用来区分「根本没有帧进来」和
                // 「帧进来了但闸门/isReady 不让解码」——这两种故障在 UI 上
                // 长得一模一样（都是屏幕上一个字也不出）。
                if ((++feedCalls % 50) == 0) {
                    // 「余 N 帧」是按 50 帧/次解调推出来的未消耗特征帧估值：它
                    // 应该是慢慢上涨的。如果在真机上反而越解越少，说明 50 估小了
                    // （真实消耗更多），那就是下一次 exit(-1) 的前兆。
                    Log.i(TAG, String.format(Locale.ROOT,
                            "feed#%d len=%d 累计%.2fs 已解%d次 余%d帧 isReady=%b",
                            feedCalls, pcm.length, samplesSinceReset / 16000.0f,
                            decodedChunks, samplesSinceReset / 160 - decodedChunks * 50,
                            recognizer.isReady(stream)));
                }
                return null;
            }
            if (!gateLogged) {
                gateLogged = true;
                Log.i(TAG, "流式首次解码放行：累计 "
                        + (samplesSinceReset * 1000L / SAMPLE_RATE) + "ms 音频，已解 "
                        + decodedChunks + " 个 chunk");
            }
            long feedDecodeMs = System.currentTimeMillis() - d0;
            decodeMsSum += feedDecodeMs;
            decodeMsMax = Math.max(decodeMsMax, feedDecodeMs);
            if (++decodeCount >= 20) {
                // 20 次就报一行：真机上一行也没等到过（先崩了），现在要的就是
                // “单次解码多少毫秒”这个数——它直接决定节拍能往多低压。
                Log.i(TAG, String.format(Locale.ROOT,
                        "流式解码: 20 次平均 %.1fms，最大 %dms（预算 20ms）",
                        decodeMsSum / 20.0, decodeMsMax));
                Log.i(PERF_TAG, String.format(Locale.ROOT,
                        "stage=asr_decode sample_count=20 average_ms=%.1f max_ms=%d "
                                + "frame_budget_ms=20",
                        decodeMsSum / 20.0, decodeMsMax));
                decodeMsSum = 0;
                decodeMsMax = 0;
                decodeCount = 0;
            }
            String text = currentText();
            partial = text;
            partialDirty = true;
            if (recognizer.isEndpoint(stream)) {
                recognizer.reset(stream);
                samplesSinceReset = 0;
                decodedChunks = 0;
                partial = "";
                return text.isEmpty() ? null : text;
            }
            return null;
        }
    }

    /** 正在说的这句话（增量结果），无内容返回空串 */
    public String partialText() {
        return partial;
    }

    /** 取走一次「partial 变了」的标志，用来给 UI 节流 */
    public boolean takePartialDirty() {
        if (!partialDirty) return false;
        partialDirty = false;
        return true;
    }

    /** 会话重新开始：丢掉手里没成句的半句 */
    public void reset() {
        synchronized (lock) {
            if (released) return;
            try {
                recognizer.reset(stream);
            } catch (Throwable t) {
                Log.w(TAG, "复位流失败", t);
            }
            samplesSinceReset = 0;
            decodedChunks = 0;
            partial = "";
            partialDirty = false;
        }
    }

    public void release() {
        synchronized (lock) {
            if (released) return;
            released = true;
            try {
                stream.release();
            } catch (Throwable ignored) {
                // 释放失败不影响后面的 recognizer 释放
            }
            try {
                recognizer.release();
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }

    /** 只读一遍图，不关心结果：sherpa 的首次 run 要做图优化与内存分配，明显更慢 */
    public void warmUp(float[] pcm, int frameSize) {
        // 预热必须覆盖到首次解码以后，否则等于白跑：现在的首次门槛是 2s 音频，
        // 而 bench_en.wav 不一定有够长——不够就用静音把样本数补过门槛，
        // 让「首次 run 的图优化与内存分配」真的在正式说话前发生一次。
        int target = Math.max(pcm == null ? 0 : pcm.length,
                FIRST_DECODE_SAMPLES + 2 * DECODE_STRIDE_SAMPLES);
        float[] frame = new float[frameSize];
        for (int off = 0; off + frameSize <= target; off += frameSize) {
            if (pcm != null && off + frameSize <= pcm.length) {
                System.arraycopy(pcm, off, frame, 0, frameSize);
            } else {
                java.util.Arrays.fill(frame, 0f);
            }
            feed(frame);
        }
        reset();
        partialDirty = false;
    }

    private String currentText() {
        OnlineRecognizerResult r;
        try {
            r = recognizer.getResult(stream);
        } catch (Throwable t) {
            return "";
        }
        return normalize(r == null ? null : r.getText());
    }

    /**
     * 识别文本归一化。
     *
     * <p>LibriSpeech 系的 BPE 词表习惯整句大写输出（" COULD YOU HELP ME"），而 NLLB
     * 收到全大写的英文时翻译质量会掉（它把这当强调/专名处理），所以这里把全大写
     * 的句子改回正常书写：首字母大写 + 其余小写，并单独把作主语的 "i" 提回大写。
     * <p>句中大写的专名（"iPhone" 这种）本来也不在该模型的输入分布里，不做额外处理。
     */
    static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return "";
        int letters = 0;
        boolean hasLower = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLowerCase(c)) hasLower = true;
            if (Character.isLetter(c)) letters++;
        }
        if (hasLower || letters < 3) return s;
        String low = s.toLowerCase(Locale.ROOT);
        String fixed = Character.toUpperCase(low.charAt(0)) + low.substring(1);
        return fixed.replaceAll("(?i)\\bi\\b", "I");
    }
}
