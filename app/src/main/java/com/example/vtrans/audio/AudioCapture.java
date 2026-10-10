package com.example.vtrans.audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import com.example.vtrans.util.Prefs;

import java.util.Locale;

/**
 * 麦克风采集：单声道 / PCM16，20ms 一帧回调。默认 16kHz，可选 48kHz 采集后软件抽取到 16k。
 *
 * <p>「语音前置处理」在这层做掉：
 * <ol>
 *   <li>按 {@code audioMode} 选音源（语音识别 / 通话）。通话源更可能走手机硬件
 *       AEC/NS/AGC 链路，识别源更干净适合自带 DSP。</li>
 *   <li>尽力挂载系统 AEC/NS/AGC（Android media 框架），哪个失败哪个就由软件补。</li>
 *   <li>软件链 {@link VoicePreprocessor}（高通/降噪门/AGC/限幅）负责兜底，
 *       并且<strong>一次处理产出两路</strong>：带门控的那路喂 VAD（切句稳），
 *       不带门控的那路喂 ASR（不丢轻声）。</li>
 * </ol>
 *
 * <p>audioMode 取值见 {@link com.example.vtrans.util.Prefs}：auto / system / software / off。
 *
 * <p>其他实测注意点：
 * <ul>
 *   <li>用 VOICE_RECOGNITION 音源时很多机型不给系统效果，这是正常的，软件会补。</li>
 *   <li>系统效果报「已启用」不等于真在干活，所以另有电平兜底：持续过低则强制开软件 AGC。</li>
 *   <li>拿不到 16kHz 就按设备支持的最小缓冲重开，别直接崩。</li>
 *   <li>缓冲区数组复用，避免 20ms 一次分配把 GC 吵醒。</li>
 * </ul>
 */
public final class AudioCapture {

    private static final String TAG = "AudioCapture";
    /** 送 VAD / ASR 的采样率，恒定 16k（两个模型都只吃 16k） */
    public static final int SAMPLE_RATE = 16000;
    /** 20ms 一帧：VAD 的窗口是 32ms，20ms 足够喂饱且延迟低 */
    private static final int FRAME_SAMPLES = 320;
    /** 高采样率采集档：部分 ROM 在 16k 直通通路上会限带/强降噪，48k 原始通路信息更多 */
    private static final int HIGH_RATE = 48000;
    private static final int DECIMATE = 3;                // 48k → 16k
    private static final int HIGH_FRAME_SAMPLES = FRAME_SAMPLES * DECIMATE;
    /** 每 250 帧(约 5 秒)打一条电平日志 + 做一次 AGC 兜底判定 */
    private static final int LOG_EVERY_FRAMES = 250;
    /** 电平上报节奏：12 帧 ≈ 240ms，给 UI 电平条用（广播频率要克制） */
    private static final int LEVEL_EVERY_FRAMES = 12;
    /** 送识别的峰值连续低于这个电平（≈-24dBFS）两个窗口 → 强制开软件 AGC */
    private static final double AGC_RESCUE_PEAK = 0.06;
    /**
     * SNR 兜底阈值：实际声包与噪声底的信噪比长期低于此值（dB）就不应该拉 AGC，
     * 因为此时麦克风里基本没人在说话。相比单看峰值，SNR 能避免
     * “环境很吵但峰值也高”→ AGC 把噪声也放的更高→ VAD 误判的链式反应。
     */
    private static final double AGC_RESCUE_SNR_DB = 15.0;
    /** AudioRecord.ERROR_DEAD_OBJECT 是 API 24+ 才有，手写常量兼容低版本 */
    private static final int ERROR_DEAD_OBJECT = -6;

    // ---------- 周期间快照（给设置页实时噪声底用，同进程下直接读静态字段） ----------
    /** 最近一次 [gain-chain] 日志的噪声底 dBFS；NaN 表示尚未采样。 */
    public static volatile double lastNoiseFloorDb = Double.NaN;
    /** 最近一次 [gain-chain] 日志的 SNR dB。 */
    public static volatile double lastSnrDb = Double.NaN;
    /** 最近一次 [gain-chain] 日志的 AGC 当前增益 dB。 */
    public static volatile double lastAgcGainDb = Double.NaN;

    public interface Sink {
        /**
         * 一帧就绪。两路信号同源但处理不同，<b>数组归本类所有，不得持有</b>
         * （下一帧会原地复用），需要留下的自己拷贝。
         *
         * @param vadFrame 带门控的一路，只用来喂 VAD 判定
         * @param asrFrame 无门控、响度完整的一路，进识别缓冲
         */
        void onFrame(float[] vadFrame, float[] asrFrame, int len);

        /**
         * 周期电平回调（约 240ms 一次），UI 电平条与「声音过小」提示用。
         *
         * @param outDbfs 送 ASR 那一路的峰值（dBFS）
         * @param agcDb   当前软件 AGC 增益（dB）
         */
        void onLevel(double outDbfs, double agcDb);

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
    /** 录音线程逐帧读、stop() 另一线程置空，且电平兜底会重建它，所以 volatile */
    private volatile VoicePreprocessor pre;
    private String summary = "";
    private int frameCount;
    private int micBoostDb;
    private float micGain = 1.0f;
    private int captureRate = SAMPLE_RATE;
    /**
     * AGC 策略：auto / force-on / force-off。
     * <ul>
     *   <li>auto：保留现有“系统 AGC 不行时软件兜底”行为</li>
     *   <li>force-on：无论系统报什么，软件 AGC 强制启用</li>
     *   <li>force-off：禁用软件 AGC 兼禁用兜底；防止噪声环境里 AGC 把噪声越放越大</li>
     * </ul>
     */
    private volatile String agcPolicy = Prefs.AGC_AUTO;

    // 软件链各段开关：AGC 电平兜底时要按原样重建处理链，只把 AGC 那一段打开
    private boolean softHpf = true;
    private boolean softNs;
    private boolean softAgc;
    private boolean agcRescued;
    private int quietWindows;
    private double windowMaxOut;

    public AudioCapture(Context context, String audioMode, Sink sink) {
        this.sink = sink;
        this.mode = audioMode == null ? "auto" : audioMode;
    }

    /**
     * 收音预增益（dB，取 0/6/12/18）。必须在 {@link #start()} 之前调用。
     * <p>目的：手机放远/免提时把电平先抬到 VAD 与 AGC 的触发区间之上，
     * 扩大有效拾音半径。噪声底现在会先标定，所以预增益不再会被门限一起拉高。
     */
    public void setMicBoostDb(int db) {
        this.micBoostDb = db;
        this.micGain = (float) Math.pow(10.0, Math.max(0, Math.min(30, db)) / 20.0);
    }

    /** 设置 AGC 策略；{@link Prefs#AGC_AUTO}/{@link Prefs#AGC_FORCE_ON}/{@link Prefs#AGC_FORCE_OFF}。 */
    public void setAgcPolicy(String policy) {
        this.agcPolicy = policy == null ? Prefs.AGC_AUTO : policy;
    }

    /** 将 sherpa VAD 的“现在是不是在说话”回传给处理链，AGC 只在说话时拉高。 */
    public void setVadSpeaking(boolean speaking) {
        VoicePreprocessor p = pre;
        if (p != null) p.setVadActive(speaking);
    }

    /** 采集采样率：16000（默认）或 48000。必须在 {@link #start()} 之前调用。 */
    public void setCaptureRateHz(int hz) {
        this.captureRate = (hz == HIGH_RATE) ? HIGH_RATE : SAMPLE_RATE;
    }

    /** @return 是否成功开始采集 */
    @SuppressLint("MissingPermission")
    public synchronized boolean start() {
        if (stopped) {
            Log.w(TAG, "start() 被拒绝：已进入停止流程");
            return false;
        }
        if (openAt(captureRate)) return true;
        if (captureRate == SAMPLE_RATE) return false;
        // 48kHz 不是所有机型都给，这不是致命错误，退回 16kHz 再来一次
        Log.w(TAG, "高采样率采集不可用（" + captureRate + "Hz），退回 16kHz");
        captureRate = SAMPLE_RATE;
        return openAt(SAMPLE_RATE);
    }

    @SuppressLint("MissingPermission")
    private boolean openAt(int rate) {
        int inFrame = (rate == HIGH_RATE) ? HIGH_FRAME_SAMPLES : FRAME_SAMPLES;
        int minBuf = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            minBuf = rate * 2;
        }
        // 留 4 倍余量：系统卡顿时不至于 underrun
        int bufSize = Math.max(minBuf * 4, inFrame * 4);

        int source = sourceOf(mode);
        try {
            record = new AudioRecord(source,
                    rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);
        } catch (Throwable t) {
            Log.e(TAG, "AudioRecord 创建失败 source=" + source + " rate=" + rate, t);
            if (rate == SAMPLE_RATE) sink.onError(t);
            return false;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord 未初始化：rate=" + rate + " source=" + source);
            try {
                record.release();
            } catch (Throwable ignored) {
                // ignore
            }
            record = null;
            if (rate == SAMPLE_RATE) {
                sink.onError(new IllegalStateException(
                        "AudioRecord 未初始化（采样率/音源不被支持？rate=" + rate
                                + " source=" + source + "）"));
            }
            return false;
        }

        if (running) {
            // 理论上不会发生（服务侧每次 new 一个实例），但防重复 start 覆盖泄漏。
            Log.w(TAG, "start() 重复调用：先停旧实例");
            releaseQuietly();
        }
        try {
            captureRate = rate;
            setupProcessing();
            summary = summary + " | 收音增益 +" + micBoostDb + "dB | 采集 " + rate + "Hz";
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
        // AGC 策略优先于默认推导：force-on 强制开，force-off 强制关
        if (Prefs.AGC_FORCE_ON.equals(agcPolicy)) {
            softwareAgc = true;
        } else if (Prefs.AGC_FORCE_OFF.equals(agcPolicy)) {
            softwareAgc = false;
        }
        softHpf = true;
        softNs = softwareNs;
        softAgc = softwareAgc;
        agcRescued = false;
        quietWindows = 0;
        windowMaxOut = 0;
        pre = new VoicePreprocessor(softHpf, softwareNs, softwareAgc);

        StringBuilder sb = new StringBuilder();
        sb.append("mode=").append(mode)
                .append(" 音源=").append(sourceOf(mode) == MediaRecorder.AudioSource.VOICE_COMMUNICATION
                        ? "通话" : "语音识别")
                .append(" | 系统AEC=").append(fx.aecOn ? "开" : "关")
                .append(" 系统NS=").append(fx.nsOn ? "开" : "关")
                .append(" 系统AGC=").append(fx.agcOn ? "开" : "关")
                .append(" | 软件补: HPF=开 NS=").append(softwareNs ? "开" : "关")
                .append(" AGC=").append(softwareAgc ? "开" : "关")
                .append("(policy=").append(agcPolicy).append(")")
                .append(" | 识别路不加门控（门控只走 VAD 路）");
        if (wantFx && !fx.aecOn) {
            sb.append(" | 系统 AEC 未能启用：外放播报译文时可能被本机麦克风拾取，建议插耳机播报"
                    + "（或把录音处理改成「系统链路」强制通话音源）");
        }
        summary = sb.toString();
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        final int inFrame = (captureRate == HIGH_RATE) ? HIGH_FRAME_SAMPLES : FRAME_SAMPLES;
        short[] pcm = new short[inFrame];
        float[] frame = new float[FRAME_SAMPLES];      // VAD 路（原地读写）
        float[] asrFrame = new float[FRAME_SAMPLES];   // ASR 路（纯输出）
        // 48k 采集时的中转缓冲；16k 时为 null，不浪费内存
        float[] hi = (captureRate == HIGH_RATE) ? new float[inFrame] : null;
        final float scale = micGain / 32768.0f;
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
            int levelFrames = 0;
            double levelMax = 0;   // 240ms 窗口的峰值（上报 UI）
            double inLevelMax = 0; // 同上，但是预增益前的输入峰值（日志用）
            while (running) {
                int n = rec.read(pcm, fill, inFrame - fill);
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
                if (fill < inFrame) continue; // 短读，继续攒到整帧

                fill = 0;
                if (hi != null) {
                    for (int i = 0; i < inFrame; i++) hi[i] = pcm[i] * scale;
                    // 48k → 16k：不重叠的 3 抽 1 平均。960 = 3×320 整除，帧边界天然对齐，
                    // 不需要跨帧状态。8kHz 处约 -3dB，对语音足够；这么做的理由见字段注释。
                    for (int i = 0; i < FRAME_SAMPLES; i++) {
                        int b = i * DECIMATE;
                        frame[i] = (hi[b] + hi[b + 1] + hi[b + 2]) * (1.0f / 3.0f);
                    }
                } else {
                    for (int i = 0; i < FRAME_SAMPLES; i++) frame[i] = pcm[i] * scale;
                }

                VoicePreprocessor p = pre; // 局部快照：电平兜底可能在另一处把它换掉
                double outPeak;
                if (p != null) {
                    p.process(frame, asrFrame, FRAME_SAMPLES);
                    outPeak = p.outputPeak();
                    if (p.inputPeak() > inLevelMax) inLevelMax = p.inputPeak();
                } else {
                    // 处理链关掉：两路给同一份原始信号
                    System.arraycopy(frame, 0, asrFrame, 0, FRAME_SAMPLES);
                    double ax = 0;
                    for (int i = 0; i < FRAME_SAMPLES; i++) {
                        double v = Math.abs(frame[i]);
                        if (v > ax) ax = v;
                    }
                    outPeak = ax;
                    inLevelMax = ax;
                }
                sink.onFrame(frame, asrFrame, FRAME_SAMPLES);

                if (outPeak > levelMax) levelMax = outPeak;
                if (outPeak > windowMaxOut) windowMaxOut = outPeak;

                if (++levelFrames >= LEVEL_EVERY_FRAMES) {
                    levelFrames = 0;
                    sink.onLevel(dbfs(levelMax), p == null ? 0 : p.agcGainDb());
                    levelMax = 0;
                }

                if (++frameCount % LOG_EVERY_FRAMES == 0) {
                    maybeForceSoftwareAgc();
                    // 分阶段日志：一行信息不够定位噪声链问题；拆成
                    // [gain-chain] 前缀方便 grep，并把 noiseFloor/snr/limit 都报出来。
                    VoicePreprocessor snap = pre;   // pre 可能被兜底重建，重新取一次
                    int hits = snap == null ? 0 : snap.consumeLimitHits();
                    double nf = snap == null ? -100.0 : snap.noiseFloorDb();
                    double snr = snap == null ? 0.0 : snap.snrDb();
                    double ag = snap == null ? 0.0 : snap.agcGainDb();
                    lastNoiseFloorDb = nf;
                    lastSnrDb = snr;
                    lastAgcGainDb = ag;
                    Log.i(TAG, String.format(Locale.ROOT,
                            "[gain-chain] in=%.1fdBFS out=%.1fdBFS noise=%.1fdBFS snr=%.1fdB "
                                    + "agc=%.1fdB gate=%s limit_hits=%d",
                            dbfs(inLevelMax), dbfs(outPeak),
                            nf, snr, ag,
                            snap == null ? "na" : (snap.gateOpen() ? "open" : "shut"),
                            hits));
                    inLevelMax = 0;
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

    private static double dbfs(double peak) {
        return 20 * Math.log10(Math.max(peak, 1e-6));
    }

    /**
     * 电平兜底：系统 AGC 报「已启用」并不等于真在抬增益（高通平台上识别音源
     * 常见只挂名字不出力）。旧实现只看峰值：环境很吵时峰值高→不兜底，但
     * 实际信噪比很低，VAD 被噪声骗得根本不会判停。
     *
     * <p>现在同时看两个量：
     * <ul>
     *   <li>峰值 < -24dBFS → 信号太轻，需要兜底（传统场景）</li>
     *   <li>信噪比 < 15dB → 噪声与信号同幅，拉 AGC 只会把噪声也拉高；此时不兜底</li>
     * </ul>
     * 另外：{@code agcPolicy=force-off} 直接短路；{@code force-on} 时不管三七二十一
     * 重建处理链并强制开软件 AGC。
     */
    private void maybeForceSoftwareAgc() {
        double w = windowMaxOut;
        windowMaxOut = 0;
        VoicePreprocessor p = pre;
        if (p == null) return;

        if (Prefs.AGC_FORCE_OFF.equals(agcPolicy)) {
            return;  // 用户强制关，直接不做兜底
        }
        if (Prefs.AGC_FORCE_ON.equals(agcPolicy)) {
            if (!p.agcEnabled()) {
                pre = new VoicePreprocessor(softHpf, softNs, true);
                pre.setVadActive(p.vadActiveSnapshot());
                summary = summary + " | policy=force-on，已重建链启用软件 AGC";
                Log.i(TAG, "[gain-chain] force-on 重建处理链，软件 AGC 启用");
            }
            return;
        }

        if (agcRescued || p.agcEnabled()) return;
        double snr = p.snrDb();
        boolean tooQuiet = w < AGC_RESCUE_PEAK;
        boolean snrOk = snr >= AGC_RESCUE_SNR_DB;
        if (!tooQuiet || !snrOk) {
            // 峰值够高 → 不需要兜底；信噪比低 → 兜底也没用，不启
            quietWindows = 0;
            return;
        }
        if (++quietWindows < 2) return;
        agcRescued = true;
        VoicePreprocessor old = pre;
        pre = new VoicePreprocessor(softHpf, softNs, true);
        pre.setVadActive(old.vadActiveSnapshot());
        summary = summary + " | 电平过低且 SNR 健康，已强制启用软件 AGC 兜底";
        Log.i(TAG, String.format(Locale.ROOT,
                "[gain-chain] 兜底启动：送识别峰值 %.0fdBFS SNR %.1fdB（系统 AGC 很可能未生效）",
                dbfs(w), snr));
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
