package com.example.vtrans.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 译文语音播报（TTS）。
 *
 * <p>是否出声由「仅耳机播报」开关（{@code Prefs.ttsHeadsetOnly}）决定：
 * <ul>
 *   <li>开启（默认）：只有检测到有线/蓝牙/USB 耳机时才朗读。外放会被本机麦克风
 *       拾取，形成「翻译 → 外放 → 再识别 → 再翻译」的回环自激，耳机可彻底避免。</li>
 *   <li>关闭：无论是否插耳机都朗读（外放场景用户自担回环风险）。</li>
 * </ul>
 *
 * <p>线程模型：{@link #speak} 由翻译线程调用，{@link #release()} 由服务停止线程调用；
 * 共享状态由 {@code lock} 保护，TextToSpeech 的回调在主线程。
 */
public final class TtsSpeaker {

    private static final String TAG = "TtsSpeaker";

    private final Context appContext;
    private final TextToSpeech tts;
    private final Object lock = new Object();
    private final AtomicInteger utteranceSeq = new AtomicInteger(0);

    private volatile boolean ready;
    private volatile boolean released;

    /** TTS 引擎初始化要几百毫秒，这期间来的译文先存一句，onInit 后补播 */
    private String pendingText;
    private Locale pendingLocale;
    private Locale currentLocale;

    public TtsSpeaker(Context context) {
        this.appContext = context.getApplicationContext();
        this.tts = new TextToSpeech(appContext, this::onInit);
    }

    private void onInit(int status) {
        if (released) return;
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS 初始化失败 status=" + status);
            return;
        }
        ready = true;
        String toSpeak = null;
        synchronized (lock) {
            if (pendingText != null) {
                toSpeak = pendingText;
                pendingText = null;
                applyLocaleLocked(pendingLocale);
                pendingLocale = null;
            }
        }
        if (toSpeak != null) speakNow(toSpeak);
    }

    /**
     * 播报一句译文。
     *
     * @param text        译文文本
     * @param targetLang  译文语种（zh / en），决定朗读口音
     * @param headsetOnly 是否「仅耳机时播报」
     */
    public void speak(String text, String targetLang, boolean headsetOnly) {
        if (text == null || text.trim().isEmpty() || released) return;
        if (headsetOnly && !isHeadsetOn(appContext)) {
            Log.i(TAG, "未检测到耳机，跳过播报: " + text);
            return;
        }
        Locale locale = localeOf(targetLang);
        synchronized (lock) {
            if (!ready) {
                // 引擎还没就绪：暂存最新一句，就绪后补播（旧的一句直接丢弃，避免堆积）
                pendingText = text;
                pendingLocale = locale;
                Log.i(TAG, "TTS 尚未就绪，暂存待播");
                return;
            }
            applyLocaleLocked(locale);
        }
        speakNow(text);
    }

    private void speakNow(String text) {
        try {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null,
                    "vtrans-" + utteranceSeq.incrementAndGet());
        } catch (Throwable t) {
            Log.w(TAG, "TTS 播报失败", t);
        }
    }

    /** 朗读口音随译文语种切换，避免中英混读；只有变化时才真正 setLanguage */
    private void applyLocaleLocked(Locale locale) {
        if (locale == null || locale.equals(currentLocale)) return;
        try {
            int r = tts.setLanguage(locale);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "系统 TTS 不支持语言: " + locale);
                return;
            }
            currentLocale = locale;
        } catch (Throwable t) {
            Log.w(TAG, "TTS 设置语言失败", t);
        }
    }

    private static Locale localeOf(String targetLang) {
        return "en".equals(targetLang) ? Locale.US : Locale.SIMPLIFIED_CHINESE;
    }

    /**
     * 当前是否插着耳机（有线耳机/耳麦、蓝牙 A2DP/SCO、USB 耳机都算）。
     * {@code getDevices} 无需任何权限，也不受 Android 11 包可见性限制。
     */
    public static boolean isHeadsetOn(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        AudioDeviceInfo[] outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if (outputs == null) return false;
        for (AudioDeviceInfo d : outputs) {
            if (isHeadsetType(d.getType())) return true;
        }
        return false;
    }

    private static boolean isHeadsetType(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return true;
            default:
                return false;
        }
    }

    /** 服务停止时调用：停止朗读并释放引擎 */
    public void release() {
        released = true;
        ready = false;
        synchronized (lock) {
            pendingText = null;
            pendingLocale = null;
        }
        try {
            tts.stop();
        } catch (Throwable ignored) {
            // 引擎未就绪时 stop 可能抛，无妨
        }
        try {
            tts.shutdown();
        } catch (Throwable t) {
            Log.w(TAG, "TTS 释放异常", t);
        }
    }
}
