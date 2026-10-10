package com.example.vtrans.audio;

/**
 * 软件语音前置处理链（16kHz / 单声道 / float[-1,1]，20ms 一帧 320 点）。
 *
 * <p>当系统音效（AEC/NS/AGC）缺失或用户选了「纯软件」方案时使用。<b>一次分析、两路输出</b>：
 * <pre>
 *   高通(90Hz) → 包络/噪声底分析 ┬─→ 门控 → AGC → 软限幅  → 送 VAD 的那一路
 *                                └────────→ AGC → 软限幅  → 送 ASR 的那一路
 * </pre>
 *
 * <p><b>为什么必须分两路</b>：降噪门控对「切句」有利（停顿期把底噪压掉，VAD 不容易被
 * 噪声误触发），但对「识别」有害 —— 轻声说话的包络本来就只比噪声底高几 dB，
 * 门一关就把整句压成 1/4 音量送进模型，结果是彻底认不出。VAD 需要干净，
 * ASR 需要完整，这两个诉求互相矛盾，只能分路各取所需。
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>零额外延迟</b>：全部因果、逐采样处理，不给实时链路加一帧缓冲。</li>
 *   <li><b>零分配</b>：process() 内不 new 对象、不调库，避免 GC 打扰音频线程。</li>
 *   <li><b>噪声底无条件双向跟踪</b>：降快升慢，且开机先做一段标定。老实现只在
 *       env 低于门限时才更新噪声底，真实底噪一旦高于初始值就永远卡死在初值上。</li>
 *   <li><b>AGC 不依赖门控状态</b>：只看「包络是否高于噪声底若干倍」。若像老实现那样
 *       要求 active 才抬增益，而 active 又要求信号够响，两者互等 → 轻声永远进不去。</li>
 * </ul>
 *
 * <p>说明：软件降噪仍是「门控 + 停顿期压低」级别的轻量处理。真正的宽频降噪/去混响/
 * 回声消除（需要参考信号）依赖系统 AEC/NS 链路，或后续把 SpeexDSP / WebRTC APM /
 * GTCRN 这类神经降噪编进 native 层。本类保证的是：没有系统效果时管线依然能拿到
 * 响度一致、不被自己压死的语音信号。
 */
public final class VoicePreprocessor {

    private static final int RATE = 16000;

    // ---------- 高通滤波器：2 阶 Butterworth，fc=90Hz（RBJ 公式），Direct Form II Transposed ----------
    private static final double HP_FREQ = 90.0;
    private static final double HP_Q = 0.7071;
    private final double b0, b1, b2, a1, a2;
    private double s1, s2;

    // ---------- 短时包络（对 |x| 的开关一阶平滑） ----------
    private static final double ENV_ATT = 1.0 - Math.exp(-1.0 / (0.004 * RATE)); // 起音 4ms
    private static final double ENV_REL = 1.0 - Math.exp(-1.0 / (0.150 * RATE)); // 释放 150ms

    // ---------- 噪声底 ----------
    private static final double NOISE_FLOOR_INIT = 5e-4;   // 保守初值，随后由标定期修正
    private static final double NOISE_FLOOR_MIN = 1e-5;    // -100dBFS，电子底噪量级
    private static final double NOISE_FLOOR_MAX = 0.05;    // -26dBFS，异常场景下别把门限推到天上
    private static final double NF_FALL = 0.02;            // 向下跟随 ~3ms（环境变安静要马上跟上）
    private static final double NF_RISE = 5e-5;            // 向上跟随 ~1.2s（环境变吵慢慢抬，且只在非说话期）

    /** 启动标定窗口：期间门全开、AGC 冻结在 1，只让噪声底快速收敛到真实电平 */
    private static final int CALIB_SAMPLES = (int) (0.4 * RATE);
    private static final double NF_CALIB = 2e-3;           // 标定期双向快速跟随（时间常数 ~0.03s）

    // ---------- 门控（只作用于送 VAD 的那一路） ----------
    private static final double ABS_GATE_FLOOR = 2e-5;     // 绝对门限下限
    private static final double GATE_RATIO = 2.2;          // 门限 = 噪声底 × 2.2（原来 4 倍，轻声必被关死）
    private static final double GATE_ENTER = 1.12;         // 迟滞：超过门限 1.12× 判「说话开始」（原 1.35）
    private static final double GATE_EXIT = 0.6;           // 低于门限 0.6× 才判「说话结束」
    private static final int HANG = (int) (250 * RATE / 1000.0); // 挂尾 250ms，句间不抖

    private static final double GATE_OPEN = 1.0 - Math.exp(-1.0 / (0.003 * RATE));  // 开门 3ms（原 6ms，抢字头）
    private static final double GATE_CLOSE = 1.0 - Math.exp(-1.0 / (0.150 * RATE)); // 关门 150ms
    /**
     * 门全关时的泄漏量。原来 0.02(-34dB) 作用在<b>送识别</b>的信号上，等于把轻声整句抹掉；
     * 现在这一路只喂 VAD，且提到 0.25(-12dB)，只做「停顿期别太吵」，静音判定交给 VAD。
     */
    private static final double GATE_LEAK = 0.25;

    // ---------- AGC（两路共用同一个增益，只抬不压） ----------
    private static final double AGC_TARGET_ABS = 0.06;     // 目标平均绝对值 ≈ -24dBFS（原 -28 偏轻）
    private static final double AGC_MAX_GAIN = 12.0;       // ≈ +21.5dB（原 +18dB 对远场不够）
    private static final double AGC_RAISE = 1.0 - Math.exp(-1.0 / (0.012 * RATE)); // 起 12ms（原 30ms 会吃掉句首）
    private static final double AGC_FALL = 1.0 - Math.exp(-1.0 / (0.400 * RATE));  // 落 400ms
    /** AGC 判「像语音」只看信噪比，<b>不看门控的 active</b> —— 依赖 active 是老实现的死锁点 */
    private static final double AGC_SNR_RATIO = 2.5;

    // ---------- 软限幅 ----------
    private static final double LIMIT_SOFT = 0.85;          // 拐点
    private static final double LIMIT_TOP = 0.99;           // 渐近上限

    // ---------- 开关 ----------
    private final boolean doHpf;
    private final boolean doNs;
    private final boolean doAgc;

    // ---------- 状态 ----------
    private double env;             // 短时包络
    private double noiseFloor;      // 自适应噪声底
    private boolean active;         // 门控是否判定为在说话（只影响送 VAD 的那一路）
    private int hang;               // 挂尾计数器
    private double gateGain = 1.0;
    private double agcGain = 1.0;
    private int calib;              // 剩余标定采样数
    /**
     * VAD 反馈回路：sherpa-onnx 的 Silero 模型判定比本类的包络可靠得多，
     * 将它的结果传进来，AGC 只在它认为「正在说话」时才拉高。避免
     * 环境低到中等强度的持续噪声（空调 / 键盘）让包络跨过 noiseFloor×2.5 就能把
     * AGC 一路抬到 +21dB，然后反过来骗 VAD。
     */
    private volatile boolean vadActive;

    // ---------- 观测值（供日志/状态用，音频线程读写） ----------
    private double inPeak;
    private double outPeak;         // 送 ASR 那一路的峰值（电平表/诊断看这个才有意义）
    private int limitHits;          // 本次窗口内限幅拐点被跨越的次数

    public VoicePreprocessor(boolean hpf, boolean ns, boolean agc) {
        this.doHpf = hpf;
        this.doNs = ns;
        this.doAgc = agc;

        double w0 = 2 * Math.PI * HP_FREQ / RATE;
        double cosw = Math.cos(w0);
        double sinw = Math.sin(w0);
        double alpha = sinw / (2 * HP_Q);
        double B0 = (1 + cosw) / 2;
        double B1 = -(1 + cosw);
        double B2 = (1 + cosw) / 2;
        double A0 = 1 + alpha;
        double A1 = -2 * cosw;
        double A2 = 1 - alpha;
        b0 = B0 / A0;
        b1 = B1 / A0;
        b2 = B2 / A0;
        a1 = A1 / A0;
        a2 = A2 / A0;
    }

    /**
     * 处理一帧：一次分析，同时产出两路信号。
     *
     * @param vadIo   原地读写的 VAD 路（高通 + 门控 + AGC + 限幅）
     * @param asrOut  长度为 len 的输出缓冲，写入 ASR 路（高通 + AGC + 限幅，<b>无门控</b>）
     * @param len     一般 = 320
     */
    public void process(float[] vadIo, float[] asrOut, int len) {
        inPeak = 0;
        outPeak = 0;
        int hitsThisFrame = 0;
        for (int i = 0; i < len; i++) {
            double x = vadIo[i];
            if (doHpf) x = hpf(x);

            boolean calibrating = calib > 0;
            if (calibrating) calib--;

            double ax = Math.abs(x);
            if (ax > inPeak) inPeak = ax;
            env += (ax - env) * (ax >= env ? ENV_ATT : ENV_REL);

            // ---------- 噪声底：标定期快速双向收敛；之后降快升慢 ----------
            // 老实现把这个更新挂在「env 低于门限」的条件里，真实底噪一旦高于初值
            // 就永远不进分支，噪声底冻在 1e-4 → AGC 把底噪当语音抬 8 倍。
            if (calibrating) {
                noiseFloor += (env - noiseFloor) * NF_CALIB;
            } else if (!active) {
                noiseFloor += (env - noiseFloor) * (env < noiseFloor ? NF_FALL : NF_RISE);
            }
            if (noiseFloor < NOISE_FLOOR_MIN) noiseFloor = NOISE_FLOOR_MIN;
            if (noiseFloor > NOISE_FLOOR_MAX) noiseFloor = NOISE_FLOOR_MAX;

            // ---------- 活跃判定（带迟滞 + 挂尾） ----------
            if (!calibrating) {
                double thr = Math.max(noiseFloor * GATE_RATIO, ABS_GATE_FLOOR);
                if (active) {
                    if (env < thr * GATE_EXIT) {
                        if (--hang <= 0) active = false;
                    } else {
                        hang = HANG;
                    }
                } else if (env > thr * GATE_ENTER) {
                    active = true;
                    hang = HANG;
                }
            }

            // ---------- AGC：SNR 目测 + VAD 反馈 + 不依赖包络门控 ----------
            // 标定期冻结在 1； Vad 说没说话时 target=1，防止把噪声越放越大。
            if (doAgc) {
                if (calibrating || !vadActive) {
                    // VAD 反馈回路：不在说话→逐逐逐回到 1.0（采用 AGC_FALL 释放常数）
                    double c = 1.0 > agcGain ? AGC_FALL : AGC_FALL;
                    agcGain += (1.0 - agcGain) * c;
                } else {
                    double target = 1.0;
                    if (env > noiseFloor * AGC_SNR_RATIO && env < AGC_TARGET_ABS) {
                        target = Math.min(AGC_MAX_GAIN, AGC_TARGET_ABS / Math.max(env, 1e-6));
                    }
                    double c = target > agcGain ? AGC_RAISE : AGC_FALL;
                    agcGain += (target - agcGain) * c;
                }
            } else {
                agcGain = 1.0;
            }
            double agc = doAgc ? agcGain : 1.0;

            // ---------- ASR 路：不过门控 ----------
            double asrY = limiter(x * agc);
            double aasr = Math.abs(asrY);
            if (aasr > outPeak) outPeak = aasr;
            if (Math.abs(x * agc) > LIMIT_SOFT) hitsThisFrame++;
            asrOut[i] = (float) asrY;

            // ---------- VAD 路：额外乘门控增益，压停顿期底噪 ----------
            if (doNs) {
                double gTarget = (calibrating || active) ? 1.0 : GATE_LEAK;
                double c = gTarget > gateGain ? GATE_OPEN : GATE_CLOSE;
                gateGain += (gTarget - gateGain) * c;
            } else {
                gateGain = 1.0;
            }
            vadIo[i] = (float) limiter(x * gateGain * agc);
        }
        limitHits += hitsThisFrame;
    }

    /** 新一句 / 开始录音时重置，避免把上一段的状态带进来。 */
    public void reset() {
        s1 = s2 = 0;
        env = 0;
        noiseFloor = NOISE_FLOOR_INIT;
        active = false;
        hang = 0;
        gateGain = 1.0;
        agcGain = 1.0;
        calib = CALIB_SAMPLES;
        inPeak = 0;
        outPeak = 0;
        limitHits = 0;
    }

    private double hpf(double x) {
        // Direct Form II Transposed（系数已按 a0 归一）
        double y = b0 * x + s1;
        s1 = b1 * x - a1 * y + s2;
        s2 = b2 * x - a2 * y;
        return y;
    }

    /** 连续、光滑的峰值限幅：>0.85 的部分渐近压到 0.99，避免削顶方波。 */
    private double limiter(double v) {
        if (v > LIMIT_SOFT) {
            double t = v - LIMIT_SOFT;
            return LIMIT_SOFT + (LIMIT_TOP - LIMIT_SOFT) * t / (1.0 + t);
        }
        if (v < -LIMIT_SOFT) {
            double t = -v - LIMIT_SOFT;
            return -(LIMIT_SOFT + (LIMIT_TOP - LIMIT_SOFT) * t / (1.0 + t));
        }
        return v;
    }

    /** 最近一帧输入峰值（0~1，高通后）。 */
    public double inputPeak() {
        return inPeak;
    }

    /** 最近一帧送 ASR 那一路的峰值（0~1）。 */
    public double outputPeak() {
        return outPeak;
    }

    /** 当前 AGC 增益(dB)，≈+21.5dB 封顶。 */
    public double agcGainDb() {
        return 20 * Math.log10(Math.max(agcGain, 1e-6));
    }

    /** 软件 AGC 是否开着（电平兜底要用）。 */
    public boolean agcEnabled() {
        return doAgc;
    }

    /** 门控是否全开（只看 VAD 路）。 */
    public boolean gateOpen() {
        return gateGain > 0.9;
    }

    /** 自适应噪声底（dBFS），诊断用。 */
    public double noiseFloorDb() {
        return 20 * Math.log10(Math.max(noiseFloor, 1e-9));
    }

    /**
     * 外部（sherpa-onnx VAD）反馈当前是不是在说话。
     * AGC 只在 true 时拉高，false 时逐逐回到 1.0，防止把环境噪声越放越大。
     */
    public void setVadActive(boolean active) {
        this.vadActive = active;
    }

    /** 当前 VAD 反馈缓存；重建处理链时拷给新实例，避免一瞬丢失上下文。 */
    public boolean vadActiveSnapshot() {
        return vadActive;
    }

    /** 当前短时包络相对噪声底的信噪比（dB），诊断用。 */
    public double snrDb() {
        return 20 * Math.log10(Math.max(env, 1e-9)
                / Math.max(noiseFloor, 1e-9));
    }

    /** 当前软限幅拐点被跨越的采样数（自上次 {@link #consumeLimitHits()} 以采累计）。 */
    public int limitHits() {
        return limitHits;
    }

    /** 读后归零，给周期日志用。 */
    public int consumeLimitHits() {
        int h = limitHits;
        limitHits = 0;
        return h;
    }
}
