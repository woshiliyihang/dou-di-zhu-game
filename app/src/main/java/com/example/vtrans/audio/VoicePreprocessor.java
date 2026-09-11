package com.example.vtrans.audio;

/**
 * 软件语音前置处理链（16kHz / 单声道 / float[-1,1]，20ms 一帧 320 点，原地处理）。
 *
 * <p>当系统音效（AEC/NS/AGC）缺失或用户选了「纯软件」方案时使用，按顺序处理：
 * <pre>
 *   90Hz 高通(去直流/去低频嗡声) → 降噪门控(带挂尾，压停顿期底噪)
 *       → 自动增益 AGC(升增益为主，安静语音拉起来) → 软限幅(防爆音)
 * </pre>
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>零额外延迟</b>：全部因果、逐采样处理，不给实时链路加一帧缓冲。</li>
 *   <li><b>零分配</b>：process() 内不 new 对象、不调库，避免 GC 打扰音频线程。</li>
 *   <li><b>保守</b>：AGC 只做「升增益」，不主动压缩大声语音；限幅只拦峰值。</li>
 *   <li>AGC/门控的门限都基于实测噪声底自适应，安静环境不放大底噪、大声环境不误杀。</li>
 * </ul>
 *
 * <p>说明：软件降噪是「门控 + 停顿期压低」级别的轻量降噪。真正的宽频降噪/去混响/
 * 回声消除（需要参考信号）依赖系统 AEC/NS 链路，或后续把 SpeexDSP / WebRTC APM
 * 编进 native 层（见 HANDOVER 的 native 目录）。本类保证的是：没有系统效果时
 * 管线依然能拿到干净、响度一致、不削顶的语音，而不是输出毛刺。
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

    // ---------- 噪声底 / 门控 ----------
    private static final double NOISE_FLOOR_INIT = 1e-4;   // 初始噪声底 ~ -80dBFS
    private static final double ABS_GATE_FLOOR = 4e-5;     // 绝对门限下限（低于它就全是电子底噪）
    private static final double GATE_RATIO = 4.0;          // 门限 = 噪声底 × 4
    private static final double GATE_ENTER = 1.35;         // 超过门限 1.35× 判「说话开始」（迟滞）
    private static final double GATE_EXIT = 0.5;           // 低于门限一半才可能判「说话结束」
    private static final int HANG = (int) (150 * RATE / 1000.0); // 挂尾 150ms，句间不抖
    private static final double FLOOR_DOWN = 0.01;         // 噪声底向下跟随时常数（~几 ms）
    private static final double FLOOR_UP = 2e-5;           // 噪声底缓慢回升速率（环境噪声变化）

    private static final double GATE_OPEN = 1.0 - Math.exp(-1.0 / (0.006 * RATE));  // 开门 6ms
    private static final double GATE_CLOSE = 1.0 - Math.exp(-1.0 / (0.120 * RATE)); // 关门 120ms
    private static final float GATE_LEAK = 0.02f;           // 门全关时的泄漏（不静音死，防咔哒）

    // ---------- AGC（升增益为主） ----------
    private static final double AGC_TARGET_ABS = 0.04;      // 目标平均绝对值 ≈ -28dBFS
    private static final double AGC_MAX_GAIN = 8.0;         // 最大 +18dB，防止把底噪抬上天
    private static final double AGC_RAISE = 1.0 - Math.exp(-1.0 / (0.030 * RATE)); // 起 30ms
    private static final double AGC_FALL = 1.0 - Math.exp(-1.0 / (0.250 * RATE));  // 落 250ms

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
    private boolean active;         // 是否判定为在说话
    private int hang;               // 挂尾计数器
    private double gateGain = 1.0;
    private double agcGain = 1.0;

    // ---------- 观测值（供日志/状态用，音频线程读写） ----------
    private double inPeak;
    private double outPeak;

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

    /** 处理一帧。len 一般=320。返回前原地写回。 */
    public void process(float[] io, int len) {
        inPeak = 0;
        outPeak = 0;
        for (int i = 0; i < len; i++) {
            double x = io[i];

            if (doHpf) x = hpf(x);

            double ax = Math.abs(x);
            if (ax > inPeak) inPeak = ax;
            env += (ax - env) * (ax >= env ? ENV_ATT : ENV_REL);

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
            } else if (env < thr) {
                // 静音区：噪声底向下跟（快）、缓慢回升（慢），保持对环境自适
                noiseFloor += (env - noiseFloor) * (env < noiseFloor ? FLOOR_DOWN : FLOOR_UP);
            }

            // 门控增益（压停顿期底噪；说话/刚说完用挂尾保持全开）
            if (doNs) {
                double target = active ? 1.0 : GATE_LEAK;
                double c = target > gateGain ? GATE_OPEN : GATE_CLOSE;
                gateGain += (target - gateGain) * c;
            } else {
                gateGain = 1.0;
            }

            // AGC：只把「够不到目标响度的语音」抬上来，底噪区/大声区回到 1
            if (doAgc) {
                double target = 1.0;
                boolean speechLike = active && env > noiseFloor * 2.0;
                if (speechLike && env < AGC_TARGET_ABS) {
                    target = Math.min(AGC_MAX_GAIN, AGC_TARGET_ABS / Math.max(env, 1e-6));
                }
                double c = target > agcGain ? AGC_RAISE : AGC_FALL;
                agcGain += (target - agcGain) * c;
            } else {
                agcGain = 1.0;
            }

            double y = x * gateGain * agcGain;
            y = limiter(y);
            double ay = Math.abs(y);
            if (ay > outPeak) outPeak = ay;
            io[i] = (float) y;
        }
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
        inPeak = 0;
        outPeak = 0;
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

    /** 最近一帧输入峰值（0~1）。 */
    public double inputPeak() {
        return inPeak;
    }

    /** 最近一帧输出峰值（0~1）。 */
    public double outputPeak() {
        return outPeak;
    }

    /** 当前 AGC 增益(dB)，≈+18dB 封顶。 */
    public double agcGainDb() {
        return 20 * Math.log10(Math.max(agcGain, 1e-6));
    }

    /** 当前门控是否全开。 */
    public boolean gateOpen() {
        return gateGain > 0.9;
    }
}
