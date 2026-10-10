package com.example.vtrans.pipeline;

import android.util.Log;

import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

/**
 * Silero VAD 的封层：把连续音频切成"一句一句"。
 *
 * <p>sherpa-onnx 的 Vad 只吐已经结束的语音段，增量预览需要"正在说的那一段"，
 * 所以这里另存一份从起说点开始的采样，由调用方按时间间隔来取快照。
 *
 * <p><b>两路进、一路存</b>：{@code vadFrame}（带门控）只用来喂 VAD 做判定，
 * {@code asrFrame}（无门控）才是写进缓冲、最终交给识别的那份。门控对切句有利
 * （停顿期底噪被压、VAD 不被噪声误触发），但对识别有害（轻声会被整句压掉）。
 *
 * <p><b>字头保护</b>：VAD 的起说判定天然有几十到上百毫秒滞后，直接拿
 * {@code seg.getSamples()} 就会吃掉第一个字的声母（“第一句前面几个字认不到”的主因之一）。
 * 因此静音期故意留一段前缀在缓冲里，定稿段从缓冲里取就自带这段字头。
 *
 * <p>线程模型：音频线程调用 {@link #feed}，ASR 线程调用 {@link #snapshot}，
 * 所有共享状态用同一把锁保护。
 */
public final class VadSegmenter {

    private static final String TAG = "VadSegmenter";
    public static final int SAMPLE_RATE = 16000;

    /** 连续说话超过这个时长就硬切，避免一口气说话把显存/内存吃满；
     *  也避免 VAD 因噪声/AGC 不判停时用户长时间看不到输出（旧值 20s 会卡太久）*/
    private static final int MAX_SPEECH_SAMPLES = 8 * SAMPLE_RATE;

    /** 静音期在缓冲里保留的前缀：VAD 起说判定有滞后，这段就是用来补字头的 */
    private static final int SILENCE_KEEP = (int) (0.6 * SAMPLE_RATE);

    /** 增量快照短于这个长度就没什么可认的（含前缀，所以给得比以前大） */
    private static final int MIN_SNAPSHOT_SAMPLES = (int) (0.9 * SAMPLE_RATE);

    /** 增量预览只取最近这么久：越说越长会让每一拍的解码越来越慢 */
    private static final int MAX_PARTIAL_SAMPLES = 10 * SAMPLE_RATE;

    // ---------- 静音裁剪（能提早判停的关键配套） ----------
    /** 段首保留的静音：字头补偿留 0.3s，覆盖 VAD 起说滞后（实测 50~250ms）还有余 */
    private static final int TRIM_HEAD_KEEP = (int) (0.30 * SAMPLE_RATE);
    /** 段尾保留的静音：擦音与语气尾巴 */
    private static final int TRIM_TAIL_KEEP = (int) (0.15 * SAMPLE_RATE);
    /** 块峰值低于整段峰值的 4%（≈ -28dB）视为静音：宁可少裁，别把软声母裁掉 */
    private static final float TRIM_REL = 0.04f;
    /** 绝对地板：低于此一律算静音，避免把纯底噪当语音留下 */
    private static final float TRIM_ABS_FLOOR = 2e-4f;
    /** 静音判定块 = 10ms */
    private static final int TRIM_BLOCK = SAMPLE_RATE / 100;

    public interface Callback {
        /** 一句话说完了 */
        void onSegmentEnd(float[] samples, int len);

        /** VAD 认为当前在说话（可用来驱动增量识别） */
        void onSpeechStateChanged(boolean speaking);
    }

    private final Vad vad;
    private final Callback callback;

    private float[] buf = new float[8 * SAMPLE_RATE];
    private int len;
    private boolean speaking;
    /** 当前这句何时开始说话（elapsedRealtime ms）；未说话时为 0 */
    private volatile long speakingStartAtMs;

    public VadSegmenter(String vadModelPath, int numThreads, String provider,
                        Callback callback) {
        this.callback = callback;

        VadModelConfig cfg = new VadModelConfig();
        cfg.getSileroVadModelConfig().setModel(vadModelPath);
        // 0.40 → 0.55：日志实测发现 AGC 拉高 20dB 后，背景噪声足以长期跨过 0.40，
        // VAD 20s+ 不判停，导致 ASR 看起来卡住。提至 0.55 让噪声不再当语音，
        // 轻声场景交给 AGC/micBoost 保护。0.6s 前缀不变，不会吃字头。
        cfg.getSileroVadModelConfig().setThreshold(0.55f);
        // 判停等待：0.35 → 0.50 曾经是为了兑现门限放宽（句间停顿不被误切），
        // 但它给每一句都加了半秒钟的「说完后干等」，是「不实时」的第一大来源。
        // 现在字头保护已由识别路缓冲的 0.6s 前缀负责，与静音确认时长无关，所以回到
        // 0.32s；句间被拆碎的风险交给 SentenceSplitter 跨段拼接兜，优先保证体感同步。
        cfg.getSileroVadModelConfig().setMinSilenceDuration(0.32f);
        cfg.getSileroVadModelConfig().setMinSpeechDuration(0.20f);
        cfg.getSileroVadModelConfig().setWindowSize(512);
        // 30 秒硬上限，防止极长语音把缓冲撑爆
        cfg.getSileroVadModelConfig().setMaxSpeechDuration(30f);
        cfg.setSampleRate(SAMPLE_RATE);
        cfg.setNumThreads(Math.max(1, numThreads));
        cfg.setProvider(provider);
        cfg.setDebug(false);

        // assetManager 传 null：模型走的是文件系统绝对路径
        this.vad = new Vad(null, cfg);
    }

    /**
     * 音频线程调用。两路都是 16kHz 单声道 [-1,1]、长度相同的同一帧。
     *
     * @param vadFrame 带门控的一路，只喂 VAD
     * @param asrFrame 无门控的一路，进识别缓冲
     */
    public void feed(float[] vadFrame, float[] asrFrame) {
        float[] finished = null;
        int finishedLen = 0;
        boolean stateChanged = false;
        boolean newState = false;

        synchronized (this) {
            append(asrFrame);
            try {
                vad.acceptWaveform(vadFrame);
            } catch (Throwable t) {
                Log.e(TAG, "VAD 推理异常", t);
                return;
            }

            if (!vad.empty()) {
                SpeechSegment seg = vad.front();
                vad.pop();
                // 定稿音频一律用识别路缓冲，seg.getSamples() 只当“有一段结束了”的信号：
                // VAD 内部缓冲存的是<b>带门控</b>那一路，拿它做识别等于绕过双路设计，
                // 轻声照样被压；而且它的段前/段尾裁剪由 native 决定，拼前缀容易对错位置。
                // 识别路缓冲是连续无跳变的，最坏只是多带一点静音，不会重复或错序。
                // 裁掉多余首尾静音（留字头/留语气），既省 Whisper 时间，又少喂静音。
                finished = trimSilence(len > 0 ? snapshotLocked() : seg.getSamples());
                finishedLen = finished == null ? 0 : finished.length;
                // 已经拿到切好的段，缓冲可以清了
                len = 0;
            } else if (len >= MAX_SPEECH_SAMPLES) {
                // 超长硬切：VAD 迟迟不吐就用缓冲里的内容强行成段
                finished = trimSilence(snapshotLocked());
                finishedLen = finished == null ? 0 : finished.length;
                len = 0;
                vad.reset();
            }

            boolean detected = vad.isSpeechDetected();
            if (detected != speaking) {
                speaking = detected;
                stateChanged = true;
                newState = detected;
                // 开说时刷时间戳，让上层能判断“已说到句尾”——避开 partial 与 final 在
                // asrExec 上的争抢（日志中 queue_ms=155/188 都是这么来的）。
                speakingStartAtMs = detected
                        ? android.os.SystemClock.elapsedRealtime() : 0L;
            }
            if (!detected && len > 0 && vad.empty()) {
                // 起说前的静音前缀不该堆在缓冲里，但也必须留足一段给字头补偿
                trimLeadingSilence();
            }
        }

        if (stateChanged && callback != null) callback.onSpeechStateChanged(newState);
        if (finished != null && finishedLen > 0 && callback != null) {
            callback.onSegmentEnd(finished, finishedLen);
        }
    }

    /** ASR 线程调用：拿"从起说点前缀到现在"的采样副本去做增量识别。 */
    public float[] snapshot() {
        synchronized (this) {
            if (len < MIN_SNAPSHOT_SAMPLES) return null;
            float[] out = snapshotLocked();
            if (out != null && out.length > MAX_PARTIAL_SAMPLES) {
                // 预览只取最近一段：一口气说十分钟时，每一拍的解码不能越来越慢。
                // 丢掉的是灰色底稿的开头，定稿走下面完整的缓冲，不会因为这里丢字。
                out = java.util.Arrays.copyOfRange(
                        out, out.length - MAX_PARTIAL_SAMPLES, out.length);
            }
            return trimSilence(out);
        }
    }

    private float[] snapshotLocked() {
        if (len <= 0) return null;
        float[] out = new float[len];
        System.arraycopy(buf, 0, out, 0, len);
        return out;
    }

    public synchronized boolean isSpeaking() {
        return speaking;
    }

    /**
     * 当前这句已经说了多久（ms）。未说话时返回 0。
     * 上层用它判断“接近句尾”，从而不再起新的增量预览。
     */
    public long speakingDurationMs() {
        long start = speakingStartAtMs;
        if (start == 0L) return 0L;
        return android.os.SystemClock.elapsedRealtime() - start;
    }

    /** 一句话结束后复位，准备下一句 */
    public synchronized void reset() {
        len = 0;
        speaking = false;
        vad.reset();
    }

    public synchronized void release() {
        try {
            vad.release();
        } catch (Throwable t) {
            Log.w(TAG, "VAD 释放异常", t);
        }
    }

    private void append(float[] frame) {
        if (len + frame.length > buf.length) {
            int cap = buf.length;
            while (cap < len + frame.length) cap *= 2;
            float[] nb = new float[cap];
            System.arraycopy(buf, 0, nb, 0, len);
            buf = nb;
        }
        System.arraycopy(frame, 0, buf, len, frame.length);
        len += frame.length;
    }

    /** 静音期只保留最后 {@link #SILENCE_KEEP}：起说检测有滞后，留这段前缀是为了补字头 */
    private void trimLeadingSilence() {
        int keep = Math.min(len, SILENCE_KEEP);
        if (keep == len) return;
        int drop = len - keep;
        System.arraycopy(buf, drop, buf, 0, keep);
        len = keep;
    }

    /**
     * 裁掉段首尾的静音边距，只留 {@link #TRIM_HEAD_KEEP} 与 {@link #TRIM_TAIL_KEEP}。
     *
     * <p>判停提前到 0.32s 之后，缓冲里仍然带着 0.6s 段首前缀和最多 0.32s 段尾静音：
     * 它们对「不丢字头」是必要的，但送进识别器只会白花时间（Whisper 尤其明显），
     * 而喂给 Whisper 的静音尾还容易诱发它编造内容。所以裁在送识别之前的这一段做。
     *
     * <p>阈值按<b>整段峰值的相对值</b>算，所以很轻的声音不会被裁成空段；找不到任何
     * 有声块时原样返回，宁多不少。
     *
     * @return 裁剪后的数组；无可裁内容时返回入参本身（不额外分配）
     */
    private static float[] trimSilence(float[] x) {
        if (x == null || x.length < TRIM_BLOCK * 6) return x;
        float peak = 0f;
        for (float v : x) {
            float a = Math.abs(v);
            if (a > peak) peak = a;
        }
        if (peak < TRIM_ABS_FLOOR) return x;             // 几乎全静音，别动
        float thr = Math.max(peak * TRIM_REL, TRIM_ABS_FLOOR);

        int firstVoiced = -1;
        for (int i = 0; i + TRIM_BLOCK <= x.length; i += TRIM_BLOCK) {
            if (blockMax(x, i) >= thr) { firstVoiced = i; break; }
        }
        if (firstVoiced < 0) return x;                   // 判不出有声点：保留原段
        int lastVoicedEnd = firstVoiced + TRIM_BLOCK;
        for (int i = x.length - TRIM_BLOCK; i > firstVoiced; i -= TRIM_BLOCK) {
            if (blockMax(x, i) >= thr) { lastVoicedEnd = i + TRIM_BLOCK; break; }
        }

        int head = Math.max(0, firstVoiced - TRIM_HEAD_KEEP);
        int tail = Math.min(x.length, lastVoicedEnd + TRIM_TAIL_KEEP);
        if (head == 0 && tail == x.length) return x;     // 本来就没带边距，省一次复制
        return java.util.Arrays.copyOfRange(x, head, tail);
    }

    /** 一个判定块（10ms）内的峰值 */
    private static float blockMax(float[] x, int from) {
        float m = 0f;
        for (int i = from; i < from + TRIM_BLOCK && i < x.length; i++) {
            float a = Math.abs(x[i]);
            if (a > m) m = a;
        }
        return m;
    }
}
