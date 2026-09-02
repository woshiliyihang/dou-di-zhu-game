package com.example.vtrans;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.example.vtrans.audio.AudioCapture;
import com.example.vtrans.model.ModelManager;
import com.example.vtrans.pipeline.AsrEngine;
import com.example.vtrans.pipeline.MtEngine;
import com.example.vtrans.pipeline.SentenceSplitter;
import com.example.vtrans.pipeline.VadSegmenter;
import com.example.vtrans.util.Prefs;
import com.example.vtrans.util.Stats;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 前台服务，持有整条翻译管线：
 *
 * <pre>
 * AudioCapture(20ms 帧) → VadSegmenter → AsrEngine(SenseVoice/Whisper)
 *      → SentenceSplitter → MtEngine(NLLB int8, JNI) → 广播给 UI
 * </pre>
 *
 * <p>为什么放服务里：翻译要在息屏、切后台时继续，Activity 被回收不能影响链路。
 */
public class TranslateService extends Service {

    private static final String TAG = "TranslateService";

    public static final String ACTION_START = "com.example.vtrans.START";
    public static final String ACTION_STOP = "com.example.vtrans.STOP";

    /** 广播：新结果。extras 见 putResult 里的 key */
    public static final String ACTION_RESULT = "com.example.vtrans.RESULT";
    public static final String ACTION_STATUS = "com.example.vtrans.STATUS";

    public static final String EXTRA_KIND = "kind";       // partial / final / translation
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_SEQ = "seq";         // 递增序号，UI 用来决定替换还是追加
    public static final String EXTRA_LANG = "lang";       // 该文本的源语言（NLLB 码）
    public static final String EXTRA_LATENCY = "latency"; // ms

    public static final String EXTRA_STATE = "state";     // loading / running / error
    public static final String EXTRA_MESSAGE = "message";

    private static final int NOTI_ID = 1001;
    private static final String CHANNEL_ID = "vtrans_running";

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private ModelManager models;

    private AudioCapture capture;
    private VadSegmenter vad;
    private AsrEngine asr;
    private MtEngine mt;

    private ExecutorService asrExec;
    private ExecutorService mtExec;
    private ScheduledExecutorService partialTimer;
    private final AtomicBoolean partialBusy = new AtomicBoolean(false);
    private final AtomicInteger seq = new AtomicInteger(0);

    private final SentenceSplitter splitter = new SentenceSplitter();
    private final Stats asrStats = new Stats(8);
    private final Stats mtStats = new Stats(8);

    private PowerManager.WakeLock wakeLock;
    private volatile boolean running;
    private volatile String statusText = "准备中";

    /** PerfGuard：连续降级计数，避免每隔几句就抖一次线程数 */
    private final AtomicInteger degradeLevel = new AtomicInteger(0);
    private long latencyBaselineMs = 0;

    public class LocalBinder extends Binder {
        public TranslateService getService() {
            return TranslateService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        models = new ModelManager(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;

        running = true;
        startAsForeground("正在加载模型…");

        asrExec = Executors.newSingleThreadExecutor(r -> new Thread(r, "vtrans-asr"));
        mtExec = Executors.newSingleThreadExecutor(r -> new Thread(r, "vtrans-mt"));
        partialTimer = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "vtrans-timer"));

        asrExec.execute(this::initAndRun);
        return START_STICKY;
    }

    /** 加载模型 + 起录音。放在 asrExec 上串行执行，避免和识别抢线程。 */
    private void initAndRun() {
        try {
            broadcastStatus("loading", "正在加载模型…");
            updateNotification("正在加载模型…");

            String provider = providerOfAsr();
            loadEngines(provider);

            if (!running) return;

            broadcastStatus("running", "已开始（provider=" + provider + "）");
            updateNotification("正在聆听…");
            startCapture(provider);
        } catch (Throwable t) {
            Log.e(TAG, "管线启动失败", t);
            broadcastStatus("error", t.getMessage());
            updateNotification("启动失败：" + t.getMessage());
            stopSelf();
        }
    }

    private void loadEngines(String provider) {
        int threads = prefs.mtThreads();
        mt = MtEngine.create(models.nllbDir().getAbsolutePath(), threads, prefs.beamForTier());
        if (mt == null) {
            throw new IllegalStateException("NLLB 模型加载失败（" + models.nllbDir() + "）");
        }
        asr = AsrEngine.create(models, provider, Math.min(2, threads), prefs.sourceLang());
    }

    private void startCapture(String provider) {
        vad = new VadSegmenter(models.vadFile().getAbsolutePath(), 1, "cpu",
                new VadSegmenter.Callback() {
                    @Override
                    public void onSegmentEnd(float[] samples, int len) {
                        submitFinal(samples);
                    }

                    @Override
                    public void onSpeechStateChanged(boolean speaking) {
                        // 增量预览的节奏由定时器统一控制，这里只更新通知文案
                        if (speaking) updateNotification("正在聆听…");
                    }
                });

        capture = new AudioCapture(this, new AudioCapture.Sink() {
            @Override
            public void onFrame(float[] samples, int len) {
                if (vad != null) vad.feed(samples);
            }

            @Override
            public void onError(Throwable t) {
                Log.e(TAG, "录音出错", t);
                broadcastStatus("error", "录音出错：" + t.getMessage());
                stopSelf();
            }
        });

        acquireWakeLock();
        if (!capture.start()) {
            throw new IllegalStateException("无法启动录音");
        }

        int interval = prefs.partialIntervalMs();
        partialTimer.scheduleAtFixedRate(this::maybePartial, interval, interval,
                TimeUnit.MILLISECONDS);
    }

    /** 增量预览：只在均衡档做，且上一轮跑完才发下一轮 */
    private void maybePartial() {
        if (!running || !prefs.partialsEnabled() || vad == null) return;
        if (!vad.isSpeaking() || !partialBusy.compareAndSet(false, true)) return;
        float[] samples = vad.snapshot();
        if (samples == null || samples.length < 8000) { // 短于 0.5s 没什么可认的
            partialBusy.set(false);
            return;
        }
        asrExec.execute(() -> {
            try {
                String text = asr.transcribe(AsrEngine.Which.SENSEVOICE, samples);
                if (text != null && !text.isEmpty()) {
                    broadcast("partial", text, MtEngine.detectLang(text), 0);
                }
            } catch (Throwable t) {
                Log.w(TAG, "增量识别失败", t);
            } finally {
                partialBusy.set(false);
            }
        });
    }

    /** 一句话说完：最终识别 + 切句 + 翻译 */
    private void submitFinal(float[] samples) {
        asrExec.execute(() -> {
            long t0 = System.currentTimeMillis();
            // finally 里 flush 时也要用同一个语种，"auto" 不能直接喂给 NLLB
            String srcLang = "eng_Latn";
            try {
                String srcSetting = prefs.sourceLang();
                String text = asr.hasSenseVoice()
                        ? asr.transcribe(AsrEngine.Which.SENSEVOICE, samples)
                        : null;

                // SenseVoice 的 auto 语种标签不可信（实测每个语种都返回 <|yue|>），
                // 所以语种一律按"识别出来的文字用了哪种书写系统"来定。
                String detected = text == null ? null : MtEngine.detectLang(text);
                srcLang = "auto".equals(srcSetting)
                        ? (detected == null ? "eng_Latn" : detected) : srcSetting;

                if (text == null && asr.hasWhisper()) {
                    // 没有 SenseVoice 时只能靠 Whisper，此时用用户的语种设置
                    asr.switchWhisperLang(models, providerOfAsr(), providerThreads(), srcLang);
                    text = asr.transcribe(AsrEngine.Which.WHISPER, samples);
                } else if (needWhisper(detected, srcSetting) && asr.hasWhisper()) {
                    asr.switchWhisperLang(models, providerOfAsr(), providerThreads(), srcLang);
                    String better = asr.transcribe(AsrEngine.Which.WHISPER, samples);
                    if (better != null && !better.isEmpty()) text = better;
                }

                if (text == null || text.trim().isEmpty()) return;

                long asrMs = System.currentTimeMillis() - t0;
                asrStats.add(asrMs);
                broadcast("final", text, srcLang, asrMs);

                List<String> sentences = splitter.push(text);
                if (sentences != null) {
                    for (String s : sentences) {
                        enqueueTranslation(s, srcLang);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "最终识别失败", t);
            } finally {
                // VAD 可能把停顿当成句内停顿，这里补一次 flush 保证不漏字
                List<String> rest = splitter.flush();
                if (rest != null) {
                    for (String s : rest) {
                        enqueueTranslation(s, srcLang);
                    }
                }
            }
        });
    }

    private String providerOfAsr() {
        String setting = prefs.provider();
        if (!"auto".equals(setting)) return setting;
        String benched = prefs.benchedProvider();
        return benched == null || benched.isEmpty() ? "cpu" : benched;
    }

    private int providerThreads() {
        return Math.min(2, prefs.mtThreads());
    }

    /**
     * 是否值得用 Whisper 再跑一遍：
     * 高精档一律重跑；均衡档只在发现"不是中文"时重跑（SenseVoice 的日韩英基本不可用）。
     */
    private boolean needWhisper(String detected, String srcSetting) {
        if (asr == null || !asr.hasWhisper()) return false;
        if (detected == null) return false;
        if (Prefs.TIER_QUALITY.equals(prefs.tier())) return true;
        if (!"auto".equals(srcSetting)) return true; // 用户显式指定了非 auto 语种：一律走 Whisper
        return !detected.startsWith("zho") && !detected.startsWith("yue");
    }

    private void enqueueTranslation(String sentence, String srcLang) {
        mtExec.execute(() -> {
            long t0 = System.currentTimeMillis();
            String out = null;
            try {
                if (mt != null) {
                    out = mt.translate(sentence, srcLang, prefs.targetLang());
                }
            } catch (Throwable t) {
                Log.e(TAG, "翻译失败", t);
            }
            long ms = System.currentTimeMillis() - t0;
            mtStats.add(ms);
            if (out != null && !out.trim().isEmpty()) {
                broadcast("translation", out, prefs.targetLang(), ms);
            }
            guardPerformance();
            updateStats();
        });
    }

    /**
     * PerfGuard：骁龙 888 发热降频很凶。用前若干句的延迟建立基线，
     * 之后若滚动均值持续超过基线 2.5 倍，就逐级降线程/降 beam。
     */
    private void guardPerformance() {
        if (mt == null || mtStats.count() < 5) return;
        long avg = mtStats.avgMs();
        if (latencyBaselineMs == 0) {
            latencyBaselineMs = Math.max(200, avg);
            return;
        }
        if (avg > latencyBaselineMs * 2.5) {
            int level = degradeLevel.incrementAndGet();
            if (level == 1) {
                mt.setThreads(2);
                updateNotification("设备发热，已降到 2 线程");
            } else if (level == 2) {
                mt.setThreads(1);
                mt.setBeam(1);
                updateNotification("设备发热，已降到 1 线程 / beam=1");
            }
            latencyBaselineMs = avg; // 重新建立基线，避免反复降级
        }
    }

    private void updateStats() {
        long avg = mtStats.avgMs();
        if (avg > 0) {
            statusText = String.format(Locale.getDefault(), "聆听中 · 翻译 %dms", avg);
            updateNotification(statusText);
        }
    }

    private void broadcast(String kind, String text, String lang, long latencyMs) {
        Intent i = new Intent(ACTION_RESULT)
                .setPackage(getPackageName())
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_SEQ, seq.incrementAndGet())
                .putExtra(EXTRA_LANG, lang)
                .putExtra(EXTRA_LATENCY, latencyMs);
        sendBroadcast(i);
    }

    private void broadcastStatus(String state, String message) {
        statusText = message;
        sendBroadcast(new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_MESSAGE, message));
    }

    private void startAsForeground(String text) {
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, NOTI_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            ServiceCompat.startForeground(this, NOTI_ID, n, 0);
        }
    }

    private void updateNotification(String text) {
        statusText = text;
        mainHandler.post(() -> {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTI_ID, buildNotification(text));
        });
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, TranslateService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("离线实时翻译")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_pause, "停止", stopPi)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "翻译运行中", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("翻译进行时的常驻通知");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    @SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vtrans:pipeline");
        wakeLock.acquire(4 * 60 * 60 * 1000L); // 上限 4 小时，防止忘记释放
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Throwable ignored) {
                // ignore
            }
        }
        wakeLock = null;
    }

    private void shutdown() {
        running = false;
        if (capture != null) capture.stop();
        capture = null;
        if (partialTimer != null) partialTimer.shutdownNow();
        partialTimer = null;
        if (asr != null) asr.release();
        asr = null;
        if (vad != null) vad.release();
        vad = null;
        if (mt != null) mt.destroy();
        mt = null;
        if (asrExec != null) asrExec.shutdownNow();
        if (mtExec != null) mtExec.shutdownNow();
        splitter.reset();
        releaseWakeLock();
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    /** 当前状态文案，Activity 绑定后可以直接读 */
    public String currentStatus() {
        return statusText;
    }

    public long avgTranslateMs() {
        return mtStats.avgMs();
    }

    public long avgAsrMs() {
        return asrStats.avgMs();
    }
}
