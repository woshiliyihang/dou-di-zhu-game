package com.example.vtrans.audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import java.util.Locale;
import java.util.Objects;

/**
 * 麦克风采集：16kHz / 单声道 / PCM16，20ms 一帧回调。
 *
 * <p>「语音前置处理」在这层做掉：
 * <ol>
 *   <li>按 {@code audioMode} 选音源（语音识别 / 通话）。通话源更可能走手机硬件
 *       AEC/NS/AGC 链路，识别源更干净适合自带 DSP。</li>
 *   <li>尽力挂载系统 AEC/NS/AGC（Android media 框架），哪个失败哪个就由软件补。</li>
 *   <li>软件链 {@link VoicePreprocessor}（高通/降噪门/AGC/限幅）负责兜底，
 *       保证任何设备拿到的都是响度稳定、底噪被压、不削顶的信号。</li>
 * </ol>
 *
 * <p>audioMode 取值见 {@link com.example.vtrans.util.Prefs}：
 * auto / system / software / off。
 *
 * <p>其他实测注意点：
 * <ul>
 *   <li>用 VOICE_RECOGNITION 音源时很多机型不给系统效果，这是正常的，软件会补。</li>
 *   <li>拿不到 16kHz 就按设备支持的最小缓冲重开，别直接崩。</li>
 *   <li>缓冲区数组复用，避免 20ms 一次分配把 GC 吵醒。</li>
 * </ul>
 */
public final class AudioCapture {

    private static final String TAG = "AudioCapture";
    public static final int SAMPLE_RATE = 16000;
    /** 20ms 一帧：VAD 的窗口是 32ms，20ms 足够喂饱且延迟低 */
    private static final int FRAME_SAMPLES = 320;
    /** 每 250 帧(约 5 秒)打一条输入电平日志，方便 adb logcat 判断增益有没有问题 */
    private static final int LOG_EVERY_FRAMES = 250;
    /** AudioRecord.ERROR_DEAD_OBJECT 是 API 24+ 才有，手写常量兼容低版本 */
    private static final int ERROR_DEAD_OBJECT = -6;

    public interface Sink {
        void onFrame(float[] samples, int len);

        void onError(Throwable t);
    }

    private final Sink sink;
    private final String mode;
    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;
    /** 已进入停止流程：此后 start() 一律拒绝，防止 stop/start 竞态下麦克风泄漏 */
    private volatile boolean stopped;

    private SystemAudioEffects fx;
    private VoicePreprocessor pre;
    private String summary = "";
    private int frameCount;
    private int micBoostDb;
    private float micGain = 1.0f;

    public AudioCapture(Context context, String audioMode, Sink sink) {
        this.sink = sink;
        this.mode = audioMode == null ? "auto" : audioMode;
    }

    /**
     * 收音预增益（dB，取 0/6/12）。必须在 {@link #start()} 之前调用。
     * <p>目的：手机放远/免提时把电平先抬到 VAD 与 AGC 的触发区间之上，
     * 扩大有效拾音半径。后续软件 AGC 仍会自适应，不会因为预增益放大底噪。
     */
    public void setMicBoostDb(int db) {
        this.micBoostDb = db;
        this.micGain = (float) Math.pow(10.0, Math.max(0, Math.min(30, db)) / 20.0);
    }

    /** @return 是否成功开始采集 */
    @SuppressLint("MissingPermission")
    public synchronized boolean start() {
        if (stopped) {
            Log.w(TAG, "start() 被拒绝：已进入停止流程");
            return false;
        }
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            minBuf = SAMPLE_RATE * 2;
        }
        // 留 4 倍余量：系统卡顿时不至于 underrun
        int bufSize = Math.max(minBuf * 4, FRAME_SAMPLES * 4);

        int source = sourceOf(mode);
        try {
            record = new AudioRecord(source,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);
        } catch (Throwable t) {
            Log.e(TAG, "AudioRecord 创建失败 source=" + source, t);
            sink.onError(t);
            return false;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Throwable t = new IllegalStateException(
                    "AudioRecord 未初始化（采样率/音源不被支持？source=" + source + "）");
            Log.e(TAG, Objects.requireNonNull(t.getMessage()));
            record.release();
            record = null;
            sink.onError(t);
            return false;
        }

        if (running) {
            // 理论上不会发生（服务侧每次 new 一个实例），但防重复 start 覆盖泄漏。
            Log.w(TAG, "start() 重复调用：先停旧实例");
            releaseQuietly();
        }
        try {
            setupProcessing();
            summary = summary + " | 收音增益 +" + micBoostDb + "dB";
            Log.i(TAG, summary);
            running = true;
            thread = new Thread(this::loop, "vtrans-audio");
            thread.setDaemon(true);
            thread.start();
            return true;
        } catch (Throwable t) {
            // setup 阶段抛异常（音效 attach/软件链初始化失败等）：清理已建资源，
            // 别让 record/fx/pre 悬着。
            Log.e(TAG, "采集启动初始化失败", t);
            releaseQuietly();
            return false;
        }
    }

    /** start() 阶段失败的清理：不碰 stopped 标记，允许后续重试。 */
    private void releaseQuietly() {
        running = false;
        Thread t = thread;
        if (t != null && t.isAlive()) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        if (pre != null) {
            pre.reset();
            pre = null;
        }
        if (fx != null) {
            fx.release();
            fx = null;
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

    /**
     * 按模式决定：音源、挂哪些系统效果、软件链开哪些段。
     * 系统缺哪项，软件就补哪项，保证"一定有处理"。
     */
    private void setupProcessing() {
        boolean wantFx = "auto".equals(mode) || "system".equals(mode);
        int sessionId = record.getAudioSessionId();
        fx = SystemAudioEffects.attach(sessionId, wantFx, wantFx, wantFx);

        if ("off".equals(mode)) {
            summary = "off(原始信号，无任何处理)";
            return;
        }
        boolean softwareNs = "software".equals(mode) || (wantFx && !fx.nsOn);
        boolean softwareAgc = "software".equals(mode) || (wantFx && !fx.agcOn);
        pre = new VoicePreprocessor(true, softwareNs, softwareAgc);

        StringBuilder sb = new StringBuilder();
        sb.append("mode=").append(mode)
                .append(" 音源=").append(sourceOf(mode) == MediaRecorder.AudioSource.VOICE_COMMUNICATION
                        ? "通话" : "语音识别")
                .append(" | 系统AEC=").append(fx.aecOn ? "开" : "关")
                .append(" 系统NS=").append(fx.nsOn ? "开" : "关")
                .append(" 系统AGC=").append(fx.agcOn ? "开" : "关")
                .append(" | 软件补: HPF=开 NS=").append(softwareNs ? "开" : "关")
                .append(" AGC=").append(softwareAgc ? "开" : "关");
        if (wantFx && !fx.aecOn) {
            sb.append(" | 系统 AEC 未能启用：外放播报译文时可能被本机麦克风拾取，建议插耳机播报"
                    + "（或把录音处理改成「系统链路」强制通话音源）");
        }
        summary = sb.toString();
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] pcm = new short[FRAME_SAMPLES];
        float[] frame = new float[FRAME_SAMPLES];
        // 快照引用：stop() 在另一线程可能把 record 置 null / release，
        // 全程用局部引用避免「读到已释放对象」这类未定义行为。
        AudioRecord rec = record;
        if (rec == null) return; // 启动失败路径已回报过错误

        try {
            rec.startRecording();
            // stop() 可能先于 startRecording() 到达（竞态窗口）：立刻让出。
            // 别只 return：此刻 rec 已处于录制态，必须补一次 stop 再退出，
            // 否则在外部 stop() 尚未 release 前麦克风一直开着。
            if (!running) {
                try {
                    rec.stop();
                } catch (Throwable ignored) {
                    // release 时兜底
                }
                return;
            }
        } catch (Throwable t) {
            Log.e(TAG, "startRecording 失败", t);
            sink.onError(t);
            return;
        }

        try {
            int fill = 0; // 一帧没读满前先攒着，避免把半帧残料喂给 VAD
            while (running) {
                int n = rec.read(pcm, fill, FRAME_SAMPLES - fill);
                if (n <= 0) {
                    // 主动 stop() 导致的 read 中断不算错误，静默退出
                    if (!running) break;
                    if (n == AudioRecord.ERROR_INVALID_OPERATION
                            || n == AudioRecord.ERROR_BAD_VALUE
                            || n == ERROR_DEAD_OBJECT) {
                        throw new IllegalStateException("AudioRecord.read 返回 " + n);
                    }
                    // n == 0 / 未知负码：别空转烧 CPU，歇 2ms 再试
                    try {
                        //noinspection BusyWait
                        Thread.sleep(2);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                fill += n;
                if (fill < FRAME_SAMPLES) continue; // 短读，继续攒到整帧

                fill = 0;
                if (micGain == 1.0f) {
                    for (int i = 0; i < FRAME_SAMPLES; i++) {
                        frame[i] = pcm[i] * (1.0f / 32768.0f);
                    }
                } else {
                    // 远场预增益：先把人声抬进 VAD/AGC 的触发区间，再用限幅防爆
                    for (int i = 0; i < FRAME_SAMPLES; i++) {
                        frame[i] = pcm[i] * (1.0f / 32768.0f) * micGain;
                    }
                }
                if (pre != null) pre.process(frame, FRAME_SAMPLES);
                sink.onFrame(frame, FRAME_SAMPLES);

                if (++frameCount % LOG_EVERY_FRAMES == 0 && pre != null) {
                    Log.i(TAG, String.format(Locale.ROOT,
                            "输入峰值 %.0fdBFS 输出峰值 %.0fdBFS AGC %.1fdB 门控%s",
                            20 * Math.log10(Math.max(pre.inputPeak(), 1e-6)),
                            20 * Math.log10(Math.max(pre.outputPeak(), 1e-6)),
                            pre.agcGainDb(), pre.gateOpen() ? "开" : "关"));
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "录音线程异常", t);
            if (running) sink.onError(t); // 已在 stop() 中则不重复上报
        } finally {
            try {
                rec.stop();
            } catch (Throwable ignored) {
                // 未进入录制状态 / 已 release 时会抛，无妨
            }
        }
    }

    public synchronized void stop() {
        stopped = true;
        running = false;
        AudioRecord r = record;
        // 先解除阻塞 read()：interrupt 对 AudioRecord.read 无效，只能从另一线程 stop。
        if (r != null) {
            try {
                r.stop();
            } catch (Throwable ignored) {
                // 还没 startRecording 时会抛，无妨
            }
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) {
                // 正常 path 下 read 已被上面的 stop() 解除阻塞，线程应在瞬间退出；
                // 若真卡死说明底层有异常，记下来便于排查（下面照常释放 record）。
                Log.w(TAG, "录音线程 2s 未退出，强制继续释放");
            }
            thread = null;
        }
        if (pre != null) {
            pre.reset();
            pre = null;
        }
        if (fx != null) {
            fx.release();
            fx = null;
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

    /** 本次采集实际生效的处理方案（日志/自检用）。 */
    public String summary() {
        return summary;
    }

    private static int sourceOf(String mode) {
        // 通话源最可能吃到手机硬件 AEC/NS/AGC 链路；识别源更"原始"，适合自带 DSP。
        return "system".equals(mode)
                ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                : MediaRecorder.AudioSource.VOICE_RECOGNITION;
    }
}
