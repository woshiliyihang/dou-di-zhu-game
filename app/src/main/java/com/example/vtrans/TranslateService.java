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
import com.example.vtrans.audio.TtsSpeaker;
import com.example.vtrans.model.ModelManager;
import com.example.vtrans.model.ModelsManifest;
import com.example.vtrans.pipeline.AsrEngine;
import com.example.vtrans.pipeline.MtEngine;
import com.example.vtrans.pipeline.SentenceSplitter;
import com.example.vtrans.pipeline.VadSegmenter;
import com.example.vtrans.util.Prefs;
import com.example.vtrans.util.Stats;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
    private TtsSpeaker tts;

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

    /** 启动(stopCapture)/停止(shutdown)同一把锁：杜绝创建与销毁交错 */
    private final Object lifecycleLock = new Object();
    private boolean shuttingDown;

    /**
     * 进程级运行态。前台服务可能比 MainActivity 活得久（Activity 被回收/重启），
     * UI 重建后靠它恢复按钮与状态栏，而不是只依赖广播事件。
     */
    private static volatile boolean sRunning;

    public static boolean isRunning() {
        return sRunning;
    }

    /**
     * 会话结果快照（进程级静态）。服务与 MainActivity 同进程：进程活着，服务与
     * 快照都在；进程死了会话本来就断了。Activity 被系统回收重建后靠它把已翻译的
     * 文本一次补回，而不是只依赖将来才到的增量广播。所有写都发生在 broadcast()，
     * 与 Activity 的 onResult() 使用同一拼接规则，避免两处逻辑漂移。
     */
    private static final Object SNAP_LOCK = new Object();
    private static final StringBuilder snapSource = new StringBuilder();
    private static final StringBuilder snapTarget = new StringBuilder();
    private static String snapPartial = "";

    public static class SessionSnapshot {
        public final String source;
        public final String partial;
        public final String target;

        SessionSnapshot(String source, String partial, String target) {
            this.source = source;
            this.partial = partial;
            this.target = target;
        }
    }

    /** 供 Activity 重建时取回整段会话。 */
    public static SessionSnapshot snapshot() {
        synchronized (SNAP_LOCK) {
            return new SessionSnapshot(snapSource.toString(), snapPartial, snapTarget.toString());
        }
    }

    /** 新一轮会话开始时清空历史快照（防止上一会话内容串台）。 */
    private static void clearSnapshot() {
        synchronized (SNAP_LOCK) {
            snapSource.setLength(0);
            snapPartial = "";
            snapTarget.setLength(0);
        }
    }

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
        // 译文语音播报：常驻初始化，是否真的出声由「仅耳机播报」开关决定
        tts = new TtsSpeaker(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        synchronized (lifecycleLock) {
            if (shuttingDown) return START_NOT_STICKY; // stopSelf 已排队，拒绝重入
        }
        if (running) return START_STICKY;

        running = true;
        sRunning = true;
        clearSnapshot(); // 新会话：历史文本清空，防止 UI 重建读到上一轮的残影
        // startForeground 可能因「后台启动 FGS 被系统拒绝」「通知被用户禁用」抛异常：
        // START_STICKY 重启（intent 为 null）、权限被拒等场景都会走到这里，不兜底会崩。
        try {
            startAsForeground("正在加载模型…");
        } catch (Throwable t) {
            Log.e(TAG, "无法进入前台（可能被系统禁止后台启动 FGS）", t);
            running = false;
            sRunning = false;
            stopSelf();
            return START_NOT_STICKY;
        }

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
            // shutdown() 可能先于本任务真正执行到（asrExec 是单线程，等它开始前 running 已被置 false）
            if (!running) return;

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
            // 服务已进入停止流程时不必再打扰 UI
            if (!running) return;
            broadcastStatus("error", t.getMessage());
            updateNotification("启动失败：" + t.getMessage());
            stopSelf();
        }
    }

    private void loadEngines(String provider) {
        int threads = prefs.mtThreads();
        mt = MtEngine.create(models.nllbDir().getAbsolutePath(), threads, prefs.beamForTier());
        if (mt == null) {
            // mt_engine 的 lastError 没走 JNI 暴露，native 侧的原因看不到，
            // 所以把模型目录打出来：少文件 / 文件不全是最常见的原因
            Log.e(TAG, "NLLB 加载失败，目录内容：" + describeDir(models.nllbDir()));
            throw new IllegalStateException("NLLB 模型加载失败（" + models.nllbDir() + "）");
        }
        asr = AsrEngine.create(models, provider, Math.min(2, threads), prefs.sourceLang());

        // 提前把 Whisper 载好：高精档、或用户明确选了非中/粤语种时这句一定会用到。
        // auto + 均衡档的中文用户不预载 —— 375MB 模型白占内存还拖慢启动。
        String src = prefs.sourceLang();
        boolean eagerWhisper = Prefs.TIER_QUALITY.equals(prefs.tier())
                || (!"auto".equals(src) && !isZhish(src));
        if (eagerWhisper && models.isReady(ModelsManifest.WHISPER) && !asr.hasWhisper()) {
            asr.switchWhisperLang(models, provider, Math.min(2, threads), src);
        }
    }

    /** 列出目录里的文件与大小，排查"模型到底解包全了没有" */
    private static String describeDir(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return "<目录不存在或不可读>";
        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(f.getName()).append('=').append(f.length());
        }
        return "[" + sb + "]";
    }

    private void startCapture(String provider) {
        // 与 shutdown 共用 lifecycleLock：若服务已开始关闭，直接放弃创建，
        // 避免 shutdown 释放完 vad/capture 后这里又 new 出来造成泄漏。
        synchronized (lifecycleLock) {
            if (shuttingDown) return;
            startCaptureLocked(provider);
        }
    }

    private void startCaptureLocked(String provider) {
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

        capture = new AudioCapture(this, prefs.audioMode(), new AudioCapture.Sink() {
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

        capture.setMicBoostDb(prefs.micBoostDb());
        acquireWakeLock();
        if (!capture.start()) {
            throw new IllegalStateException("无法启动录音");
        }
        Log.i(TAG, "录音处理方案：" + capture.summary());

        // 增量预览只在高精档以外才有意义；否则定时器每拍进来就 return，纯空转。
        if (prefs.partialsEnabled()) {
            int interval = prefs.partialIntervalMs();
            partialTimer.scheduleAtFixedRate(this::maybePartial, interval, interval,
                    TimeUnit.MILLISECONDS);
        }
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
        // 捕获本地引用：submit 到 asrExec 的瞬间 shutdown 可能已把字段清掉
        final AsrEngine engine = asr;
        if (engine == null) {
            partialBusy.set(false);
            return;
        }
        try {
            asrExec.execute(() -> {
                try {
                    // shutdown 与任务执行之间可能交错，识别要容忍 running 翻转
                    String text = engine.transcribe(AsrEngine.Which.SENSEVOICE, samples);
                    if (text != null && !text.isEmpty()) {
                        broadcast("partial", text, MtEngine.detectLang(text), 0);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "增量识别失败", t);
                } finally {
                    partialBusy.set(false);
                }
            });
        } catch (Throwable t) {
            // executor 已 shutdown / 已满等情况：放弃本轮，复位忙标志
            partialBusy.set(false);
        }
    }

    /** 一句话说完：最终识别 + 切句 + 翻译 */
    private void submitFinal(float[] samples) {
        try {
            asrExec.execute(() -> {
                if (!running) return; // 关闭流程已开始，不再处理旧音频段
                // 任务执行时 shutdown 可能已把字段置空并排队了 release——
                // 先取局部引用；本任务在 release 之前执行完，引擎仍有效。
                AsrEngine engine = asr;
                if (engine == null) return;
                long t0 = System.currentTimeMillis();
                // finally 里 flush 时也要用同一个语种，"auto" 不能直接喂给 NLLB
                String srcLang = "eng_Latn";
                try {
                String srcSetting = prefs.sourceLang();
                String text = engine.hasSenseVoice()
                        ? engine.transcribe(AsrEngine.Which.SENSEVOICE, samples)
                        : null;

                // SenseVoice 的 auto 语种标签不可信（实测每个语种都返回 <|yue|>），
                // 所以语种一律按"识别出来的文字用了哪种书写系统"来定。
                String detected = text == null ? null : MtEngine.detectLang(text);
                srcLang = "auto".equals(srcSetting)
                        ? (detected == null ? "eng_Latn" : detected) : srcSetting;

                // Whisper 只在该用的时候用，且按语种懒加载（375MB 不常驻内存）。
                boolean wantWhisper = shouldUseWhisper(detected, srcSetting)
                        || text == null; // SenseVoice 失手时的兜底
                if (wantWhisper && models.isReady(ModelsManifest.WHISPER)) {
                    engine.switchWhisperLang(models, providerOfAsr(), providerThreads(), srcLang);
                    String better = engine.transcribe(AsrEngine.Which.WHISPER, samples);
                    if (better != null && !better.isEmpty()) {
                        text = better;
                        // 以 Whisper 结果为准重判一次语种（兜底路径下 SenseVoice 没输出可判）
                        detected = MtEngine.detectLang(better);
                        srcLang = "auto".equals(srcSetting)
                                ? (detected == null ? "eng_Latn" : detected) : srcSetting;
                    }
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
        } catch (Throwable t) {
            // executor 已 shutdown：忽略排队失败
            Log.w(TAG, "最终识别任务提交失败", t);
        }
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
     * 是否值得用 Whisper 再跑一遍（模型可用性由调用方判断，这里只回答"值不值得"）：
     * 高精档一律重跑；均衡档只在 SenseVoice 不可靠的语言上重跑。
     * <p>注意：SenseVoice 的中文/粤语又快又准，用户显式选了中文也不该被拖慢 6~10 倍。
     */
    private boolean shouldUseWhisper(String detected, String srcSetting) {
        if (asr == null) return false;
        if (Prefs.TIER_QUALITY.equals(prefs.tier())) return true;
        if (!"auto".equals(srcSetting)) return !isZhish(srcSetting);
        if (detected == null) return false;
        return !detected.startsWith("zho") && !detected.startsWith("yue");
    }

    /** 中/粤（含 zh_/zho/cmn 等写法）→ 交给 SenseVoice 就够 */
    private static boolean isZhish(String lang) {
        String v = lang == null ? "" : lang.toLowerCase(Locale.ROOT);
        return v.equals("zh") || v.equals("yue")
                || v.startsWith("zh_") || v.startsWith("yue")
                || v.startsWith("zho") || v.startsWith("cmn");
    }

    private void enqueueTranslation(String sentence, String srcLang) {
        final MtEngine engine = mt;
        if (engine == null) return;
        try {
            mtExec.execute(() -> {
                long t0 = System.currentTimeMillis();
                String out = null;
                try {
                    out = engine.translate(sentence, srcLang, prefs.targetLang());
                } catch (Throwable t) {
                    Log.e(TAG, "翻译失败", t);
                }
                long ms = System.currentTimeMillis() - t0;
                mtStats.add(ms);
                if (out != null && !out.trim().isEmpty()) {
                    broadcast("translation", out, prefs.targetLang(), ms);
                    speakTranslation(out, prefs.targetLang());
                }
                guardPerformance();
                updateStats();
            });
        } catch (Throwable t) {
            // executor 已 shutdown：忽略
            Log.w(TAG, "翻译任务提交失败", t);
        }
    }

    /** 译文语音播报；是否出声由「仅耳机播报」开关决定（见 TtsSpeaker） */
    private void speakTranslation(String text, String targetLang) {
        TtsSpeaker t = tts;
        if (t != null) t.speak(text, targetLang, prefs.ttsHeadsetOnly());
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
        // 与 MainActivity.onResult() 同步维护镜像快照（同规则拼接）
        synchronized (SNAP_LOCK) {
            if ("partial".equals(kind)) {
                snapPartial = text;
            } else if ("final".equals(kind)) {
                if (snapSource.length() > 0) snapSource.append('\n');
                snapSource.append(text);
                snapPartial = "";
            } else if ("translation".equals(kind)) {
                if (snapTarget.length() > 0) snapTarget.append('\n');
                snapTarget.append(text);
            }
        }
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

    /** 通知刷新防抖：翻译高峰时每秒可能有几条结果，逐条 notify 纯属浪费。 */
    private final Runnable notifier = this::pushNotification;
    private volatile String pendingText;

    private void updateNotification(String text) {
        statusText = text;
        pendingText = text;
        mainHandler.removeCallbacks(notifier);
        mainHandler.postDelayed(notifier, 250);
    }

    private void pushNotification() {
        // 服务可能在这 250ms 内已停止：通知已被系统移除，此时再 notify
        // 会重新弹一条 ongoing 通知且永远停不掉，所以必须检查运行态。
        if (pendingText == null || !running) return;
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTI_ID, buildNotification(pendingText));
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
        synchronized (lifecycleLock) {
            if (shuttingDown) return; // onDestroy 只来一次，但 stopSelf 可能被多线程触发
            shuttingDown = true;
        }
        running = false;

        // 1) 停音频 → 停定时器 → 停 VAD。整段与 startCaptureLocked 共用 lifecycleLock，
        //    保证不会出现「shutdown 先跑，startCapture 后把 vad/capture/partialTimer
        //    建出来」的泄漏或 NPE。capture.stop() 会 join 音频线程，VAD 生产者随之退出。
        synchronized (lifecycleLock) {
            if (capture != null) {
                capture.stop();
                capture = null;
            }
            if (partialTimer != null) {
                // 正在执行的那一拍可能正调 vad.isSpeaking()/snapshot()，
                // 等它结束再 release，避免 native 竞态。
                partialTimer.shutdownNow();
                awaitTermination(partialTimer, 500);
                partialTimer = null;
            }
            if (vad != null) {
                vad.release();
                vad = null;
            }
        }

        // 3) ASR / MT 引擎释放：不在这里直接 release（可能还有任务在 native 解码）。
        //    关键点：**立刻把字段置空、取好局部引用**，释放动作才排到各单线程队列队尾。
        //    这样即使服务迅速重启、字段被新引擎占用，旧引擎也只被自己的释放任务释放，
        //    不会出现「排队任务执行时读到新引擎引用而误释放」。
        AsrEngine oldAsr = asr;
        asr = null;
        if (asrExec != null) {
            try {
                asrExec.execute(() -> {
                    if (oldAsr != null) oldAsr.release();
                });
            } catch (RejectedExecutionException ignored) {
                // executor 已关闭：直接释放
                if (oldAsr != null) oldAsr.release();
            }
            asrExec.shutdown();
            asrExec = null;
        } else if (oldAsr != null) {
            oldAsr.release();
        }

        MtEngine oldMt = mt;
        mt = null;
        if (mtExec != null) {
            try {
                mtExec.execute(() -> {
                    if (oldMt != null) oldMt.destroy();
                });
            } catch (RejectedExecutionException ignored) {
                if (oldMt != null) oldMt.destroy();
            }
            mtExec.shutdown();
            mtExec = null;
        } else if (oldMt != null) {
            oldMt.destroy();
        }

        // 取消尚未触发的通知刷新，别让服务停了还弹一条 ongoing 通知
        mainHandler.removeCallbacks(notifier);
        pendingText = null;

        // 停掉语音播报并释放 TTS 引擎
        if (tts != null) {
            tts.release();
            tts = null;
        }

        splitter.reset();
        releaseWakeLock();
        sRunning = false;
    }

    private static void awaitTermination(java.util.concurrent.ExecutorService ex, long ms) {
        try {
            ex.awaitTermination(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
