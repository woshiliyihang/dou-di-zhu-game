package com.example.vtrans.audio;

import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

/**
 * 系统音效（AEC / NS / AGC）的统一挂载与能力检测。
 *
 * <p>为什么单独抽一层：
 * <ul>
 *   <li>AudioCapture 和设置页都要用（设置页静态检测设备能力、采集页按会话挂载）。</li>
 *   <li>这类 effect 的可用性在真机上差异很大：有的返回可用但 create 直接抛异常，
 *       有的能 create 但 setEnabled 失败。这里统一「全都要试、失败就降级并记录」。</li>
 *   <li>Android 12 起 {@link AutomaticGainControl} 已废弃、多数机器 isAvailable()=false，
 *       所以「增益」主要靠软件 AGC 兜底，系统 AGC 只是锦上添花。</li>
 * </ul>
 *
 * <p>注意：真正有没有效果取决于具体 ROM/音源。挂载结果与音源有关，
 * 例如 K40 Pro 上通话音源(VOICE_COMMUNICATION)最可能走硬件 AEC/NS 链路，
 * 语音识别音源(VOICE_RECOGNITION)常被系统关掉这些效果。
 */
public final class SystemAudioEffects {

    private static final String TAG = "AudioFx";

    private final AcousticEchoCanceler aec;
    private final NoiseSuppressor ns;
    private final AutomaticGainControl agc;
    public final boolean aecOn;
    public final boolean nsOn;
    public final boolean agcOn;

    private SystemAudioEffects(AcousticEchoCanceler aec, boolean aecOn,
                               NoiseSuppressor ns, boolean nsOn,
                               AutomaticGainControl agc, boolean agcOn) {
        this.aec = aec;
        this.aecOn = aecOn;
        this.ns = ns;
        this.nsOn = nsOn;
        this.agc = agc;
        this.agcOn = agcOn;
    }

    /** 系统能力检测（设置页用）。不依赖录音会话。 */
    public static boolean aecAvailable() {
        try {
            return AcousticEchoCanceler.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean nsAvailable() {
        try {
            return NoiseSuppressor.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    public static boolean agcAvailable() {
        try {
            return AutomaticGainControl.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 给某个 AudioRecord 会话挂载想要的效果。逐个 try/catch，
     * 任何失败都不影响录音主流程。want* 全 false 时返回一个"什么都没挂"的实例。
     */
    public static SystemAudioEffects attach(int sessionId,
                                            boolean wantAec, boolean wantNs, boolean wantAgc) {
        AcousticEchoCanceler aec = null;
        boolean aecOn = false;
        NoiseSuppressor ns = null;
        boolean nsOn = false;
        AutomaticGainControl agc = null;
        boolean agcOn = false;

        if (wantAec && aecAvailable() && sessionId != 0) {
            try {
                aec = AcousticEchoCanceler.create(sessionId);
                if (aec != null) {
                    aecOn = aec.setEnabled(true) == 0 && aec.getEnabled();
                }
            } catch (Throwable t) {
                Log.w(TAG, "AEC 挂载失败（会话 " + sessionId + "）", t);
                aec = null;
            }
        }
        if (wantNs && nsAvailable() && sessionId != 0) {
            try {
                ns = NoiseSuppressor.create(sessionId);
                if (ns != null) {
                    nsOn = ns.setEnabled(true) == 0 && ns.getEnabled();
                }
            } catch (Throwable t) {
                Log.w(TAG, "NS 挂载失败（会话 " + sessionId + "）", t);
                ns = null;
            }
        }
        if (wantAgc && agcAvailable() && sessionId != 0) {
            try {
                agc = AutomaticGainControl.create(sessionId);
                if (agc != null) {
                    agcOn = agc.setEnabled(true) == 0 && agc.getEnabled();
                }
            } catch (Throwable t) {
                Log.w(TAG, "AGC 挂载失败（会话 " + sessionId + "）", t);
                agc = null;
            }
        }

        if (aec != null || ns != null || agc != null) {
            Log.i(TAG, "音效挂载: AEC=" + (aecOn ? "开" : (aec != null ? "关" : "无"))
                    + " NS=" + (nsOn ? "开" : (ns != null ? "关" : "无"))
                    + " AGC=" + (agcOn ? "开" : (agc != null ? "关" : "无")));
        }
        return new SystemAudioEffects(aec, aecOn, ns, nsOn, agc, agcOn);
    }

    /** 能力一行文案（设置页展示用）。 */
    public static String capabilityLine() {
        return "回声消除" + (aecAvailable() ? "支持" : "不支持")
                + " · 降噪" + (nsAvailable() ? "支持" : "不支持")
                + " · 自动增益" + (agcAvailable() ? "支持" : "不支持");
    }

    public void release() {
        releaseQuiet(aec);
        releaseQuiet(ns);
        releaseQuiet(agc);
    }

    private static void releaseQuiet(AudioEffect effect) {
        if (effect == null) return;
        try {
            effect.release();
        } catch (Throwable ignored) {
            // 释放失败无所谓
        }
    }
}
