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
 * <p>线程模型：音频线程调用 {@link #feed}，ASR 线程调用 {@link #snapshot}，
 * 所有共享状态用同一把锁保护。
 */
public final class VadSegmenter {

    private static final String TAG = "VadSegmenter";
    public static final int SAMPLE_RATE = 16000;

    /** 连续说超过这个时长就硬切，避免一口气说话把显存/内存吃满 */
    private static final int MAX_SPEECH_SAMPLES = 20 * SAMPLE_RATE;

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

    public VadSegmenter(String vadModelPath, int numThreads, String provider,
                        Callback callback) {
        this.callback = callback;

        VadModelConfig cfg = new VadModelConfig();
        cfg.getSileroVadModelConfig().setModel(vadModelPath);
        cfg.getSileroVadModelConfig().setThreshold(0.5f);
        cfg.getSileroVadModelConfig().setMinSilenceDuration(0.35f);
        cfg.getSileroVadModelConfig().setMinSpeechDuration(0.25f);
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

    /** 音频线程调用。frame 为 16kHz 单声道归一化到 [-1,1] 的采样。 */
    public void feed(float[] frame) {
        float[] finished = null;
        int finishedLen = 0;
        boolean stateChanged = false;
        boolean newState = false;

        synchronized (this) {
            append(frame);
            try {
                vad.acceptWaveform(frame);
            } catch (Throwable t) {
                Log.e(TAG, "VAD 推理异常", t);
                return;
            }

            if (!vad.empty()) {
                SpeechSegment seg = vad.front();
                vad.pop();
                finished = seg.getSamples();
                finishedLen = finished.length;
                // 已经拿到切好的段，缓冲可以清了
                len = 0;
            } else if (len >= MAX_SPEECH_SAMPLES) {
                // 超长硬切：VAD 迟迟不吐就用缓冲里的内容强行成段
                finished = snapshotLocked();
                finishedLen = finished.length;
                len = 0;
                vad.reset();
            }

            boolean detected = vad.isSpeechDetected();
            if (detected != speaking) {
                speaking = detected;
                stateChanged = true;
                newState = detected;
            }
            if (!detected && len > 0 && vad.empty()) {
                // 起说前的静音前缀不该堆在缓冲里
                trimLeadingSilence();
            }
        }

        if (stateChanged && callback != null) callback.onSpeechStateChanged(newState);
        if (finished != null && finishedLen > 0 && callback != null) {
            callback.onSegmentEnd(finished, finishedLen);
        }
    }

    /** ASR 线程调用：拿"从起说点到现在"的采样副本去做增量识别。 */
    public float[] snapshot() {
        synchronized (this) {
            return snapshotLocked();
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

    /** 缓冲里只保留最后 0.3s：起说检测有几十毫秒滞后，留一点前缀避免吞字 */
    private void trimLeadingSilence() {
        int keep = Math.min(len, SAMPLE_RATE / 3);
        if (keep == len) return;
        int drop = len - keep;
        System.arraycopy(buf, drop, buf, 0, keep);
        len = keep;
    }
}
