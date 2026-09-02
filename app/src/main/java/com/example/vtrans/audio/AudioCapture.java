package com.example.vtrans.audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;
import android.util.Log;

/**
 * 麦克风采集：16kHz / 单声道 / PCM16，20ms 一帧回调。
 *
 * <p>几个实测要注意的点：
 * <ul>
 *   <li>用 VOICE_RECOGNITION 音源：系统会自行挂 AEC/NS，比自己再挂一层稳。</li>
 *   <li>拿不到 16kHz 就按设备支持的最小缓冲重开，别直接崩。</li>
 *   <li>缓冲区数组复用，避免 20ms 一次分配把 GC 吵醒。</li>
 * </ul>
 */
public final class AudioCapture {

    private static final String TAG = "AudioCapture";
    public static final int SAMPLE_RATE = 16000;
    /** 20ms 一帧：VAD 的窗口是 32ms，20ms 足够喂饱且延迟低 */
    private static final int FRAME_SAMPLES = 320;

    public interface Sink {
        void onFrame(float[] samples, int len);

        void onError(Throwable t);
    }

    private final Sink sink;
    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;

    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;

    public AudioCapture(Context context, Sink sink) {
        this.sink = sink;
    }

    /** @return 是否成功开始采集 */
    @SuppressLint("MissingPermission")
    public boolean start() {
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            minBuf = SAMPLE_RATE * 2;
        }
        // 留 4 倍余量：系统卡顿时不至于 underrun
        int bufSize = Math.max(minBuf * 4, FRAME_SAMPLES * 4);

        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);
        } catch (Throwable t) {
            Log.e(TAG, "AudioRecord 创建失败", t);
            sink.onError(t);
            return false;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Throwable t = new IllegalStateException("AudioRecord 未初始化（采样率不被支持？）");
            Log.e(TAG, t.getMessage());
            record.release();
            record = null;
            sink.onError(t);
            return false;
        }

        // 能挂就挂，挂不上不影响主流程
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(record.getAudioSessionId());
                if (aec != null) aec.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(record.getAudioSessionId());
                if (ns != null) ns.setEnabled(true);
            }
        } catch (Throwable t) {
            Log.w(TAG, "挂载 AEC/NS 失败，继续运行", t);
        }

        running = true;
        thread = new Thread(this::loop, "vtrans-audio");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] pcm = new short[FRAME_SAMPLES];
        float[] frame = new float[FRAME_SAMPLES];

        try {
            record.startRecording();
        } catch (Throwable t) {
            Log.e(TAG, "startRecording 失败", t);
            sink.onError(t);
            return;
        }

        try {
            while (running) {
                int n = record.read(pcm, 0, FRAME_SAMPLES);
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION
                            || n == AudioRecord.ERROR_BAD_VALUE) {
                        throw new IllegalStateException("AudioRecord.read 返回 " + n);
                    }
                    continue;
                }
                for (int i = 0; i < n; i++) {
                    frame[i] = pcm[i] * (1.0f / 32768.0f);
                }
                sink.onFrame(frame, n);
            }
        } catch (Throwable t) {
            Log.e(TAG, "录音线程异常", t);
            sink.onError(t);
        } finally {
            try {
                record.stop();
            } catch (Throwable ignored) {
                // stop 失败无所谓，后面还要 release
            }
        }
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        if (aec != null) {
            try {
                aec.release();
            } catch (Throwable ignored) {
                // ignore
            }
            aec = null;
        }
        if (ns != null) {
            try {
                ns.release();
            } catch (Throwable ignored) {
                // ignore
            }
            ns = null;
        }
        if (record != null) {
            try {
                record.release();
            } catch (Throwable ignored) {
                // ignore
            }
            record = null;
        }
    }
}
