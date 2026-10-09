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
import com.example.vtrans.pipeline.StreamingAsr;
import com.example.vtrans.pipeline.VadSegmenter;
import com.example.vtrans.util.Prefs;
import com.example.vtrans.util.Stats;
import com.example.vtrans.util.WaveReader;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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

    /** 广播：实时电平（约 240ms 一次），给主界面电平条与「声音太小」提示用 */
    public static final String ACTION_LEVEL = "com.example.vtrans.LEVEL";
    public static final String EXTRA_DBFS = "dbfs";         // 送识别那一路的峰值
    public static final String EXTRA_AGC_DB = "agc_db";     // 当前软件 AGC 增益

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
    /**
     * 当前翻译引擎是语对专用模型（只认英→中）。它没有语言码体系，喂中文进去
     * 不会报错、只会很自信地输出一串胡话，所以调用方要靠它把中文句子拦下来。
     */
    private volatile boolean pairMt;
    private TtsSpeaker tts;

    /**
     * 流式识别引擎。非 null 时它就是主识别器：音频只喂 {@link #feedStreaming}，
     * VAD/SenseVoice 那一路不再喂数据——它只在流式模型缺失或加载失败时才接手。
     */
    private StreamingAsr streaming;
    private ExecutorService streamExec;

    private ExecutorService asrExec;
    private ExecutorService mtExec;
    private ScheduledExecutorService partialTimer;
    private final AtomicBoolean partialBusy = new AtomicBoolean(false);
    private final AtomicInteger seq = new AtomicInteger(0);

    /**
     * 定稿优先：&gt;0 表示有定稿段正在识别或在 asrExec 上排队。增量预览和定稿共用
     * 同一条识别队列，而预览只是灰色底稿——说完话的那一刻必须让路给定稿，否则越到
     * 长句尾拍预览越慢，用户就会感到「明明说完了却还在转圈」。
     */
    private final AtomicInteger finalQueued = new AtomicInteger(0);

    /**
     * 残句长到这个程度就立刻翻，别再等拼接：等拼接换回来的是句子更连贯，
     * 代价是用户干等着，速度优先时不划算。
     */
    private static final int TAIL_KEEP_MIN_CHARS = 4;
    /** 攒着的碎句最多等多久。原来 1.2s，实测那 1.2s 是全链路里最没道理的一段 */
    private static final long TAIL_FLUSH_DELAY_MS = 400;
    private volatile long lastSegmentAtMs;
    private volatile String lastSegLang;

    private final SentenceSplitter splitter = new SentenceSplitter();
    private final Stats asrStats = new Stats(8);
    private final Stats mtStats = new Stats(8);

    /**
     * 引擎还在加载时抵达的语音段。点「开始翻译」到 NLLB/SenseVoice 装好有 1~5 秒，
     * 而用户往往就在这段时间开口 —— 所以先开录音、把这段里的段攒着，引擎就绪后按序补跑。
     * 只在录音线程写入、asrExec 读取/排空，统一用 {@code pendingLock}。
     */
    private static final int MAX_PENDING_SEGMENTS = 6;
    private final Object pendingLock = new Object();
    private final ArrayDeque<float[]> pendingSegments = new ArrayDeque<>();
    /** 就绪前抵达的音频帧（每帧 20ms），攒够 12s 才丢最早的：首启还要多赶一次解包 */
    private static final int MAX_PENDING_FRAMES = 600;
    private final ArrayDeque<float[]> pendingFrames = new ArrayDeque<>();
    /** 本轮打算用流式识别（模型存在但还在解包/加载）：帧先攒着，不要交 VAD 处理 */
    private volatile boolean streamingIntended;
    /** 解码跟不上实时时的丢帧阈值：落后 0.5s 继续攒只会让延迟无限增长 */
    private static final int STREAM_MAX_BACKLOG = 25;
    private final AtomicInteger streamBacklog = new AtomicInteger(0);
    /** 因积压丢掉的帧数（诊断用） */
    private final AtomicInteger streamDrops = new AtomicInteger(0);
    /** 录音线程收到的帧计数（只在该线程读写，不需同步） */
    private int audioFrames;
    /** 引擎就绪且 warmup 完成才置真；此前提交的段一律入队而不是丢弃 */
    private volatile boolean enginesReady;

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
    /** 翻译线程数的下限：1 线程翻 600M 每句要好几秒，那不是省电那是卡 */
    private static final int MIN_MT_THREADS = 2;
    /** 绝对门槛：慢到用户能感觉到才降级，别拿「比冷机头几句慢」当理由 */
    private static final long DEGRADE_FLOOR_MS = 900;
    /** 连续这么多句明显快于基线，就把降掉的档位升回去 */
    private static final int RECOVER_STREAK = 5;
    private int fastStreak;

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
        // 新会话重新建基线：上一轮的降级档位和延迟基线留给这一轮没有任何意义
        // （引擎是新的，机器温度也是新的），否则会出现「上次热过、这次一上来就是低档」。
        degradeLevel.set(0);
        latencyBaselineMs = 0;
        fastStreak = 0;
        asrStats.reset();
        mtStats.reset();
        splitter.reset();
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

    /**
     * 起录音 + 加载模型。
     *
     * <p><b>顺序很关键</b>：先建 VAD（只有 208KB，几十毫秒）并把麦克风升起来，
     * 再慢慢装 NLLB(622MB) / SenseVoice(237MB)。老顺序是先装模型再开录音，
     * 中间那 1~5 秒里根本没在采集，用户点完按钮本能就开口，第一句整段丢失。
     * 现在这段时间里的语音段进了 {@link #pendingSegments}，引擎就绪后按序补跑。
     */
    private void initAndRun() {
        try {
            // shutdown() 可能先于本任务真正执行到（asrExec 是单线程，等它开始前 running 已被置 false）
            if (!running) return;

            String provider = providerOfAsr();

            broadcastStatus("loading", "正在聆听（模型后台加载中）…");
            updateNotification("模型加载中…");
            startCapture(provider);

            if (!running) return;
            loadEngines(provider);

            if (!running) return;
            warmUpEngines();
            enginesReady = true;
            drainPendingSegments();

            broadcastStatus("running", "已开始（provider=" + provider + "）");
            updateNotification("正在聆听…");
        } catch (Throwable t) {
            Log.e(TAG, "管线启动失败", t);
            // 服务已进入停止流程时不必再打扰 UI
            if (!running) return;
            broadcastStatus("error", t.getMessage());
            updateNotification("启动失败：" + t.getMessage());
            stopSelf();
        }
    }

    /**
     * 首次推理预热：ONNX session 第一次 run 要图优化 + 分配内存，实测比后续慢很多倍，
     * 不预热的话「第一句」明显最慢、也最容易掉字。拿随包的 bench_zh.wav 跑一遍，结果丢掉。
     * <p>跑在 asrExec 上（就是它自己），不占录音线程；MT 预热排到 mtExec 队尾，
     * 不阻塞后面的真活。失败只记日志，绝不影响启动。
     */
    private void warmUpEngines() {
        long t0 = System.currentTimeMillis();
        try {
            float[] wav = WaveReader.read(this, "bench_zh.wav");
            // 取前 2 秒就够：目的是走一遍图，不是验质量
            int n = Math.min(wav.length, 2 * VadSegmenter.SAMPLE_RATE);
            if (n < VadSegmenter.SAMPLE_RATE / 2) return;
            float[] probe = Arrays.copyOfRange(wav, 0, n);
            AsrEngine a = asr;
            if (a != null && a.hasSenseVoice()) {
                a.transcribe(AsrEngine.Which.SENSEVOICE, probe);
            }
        } catch (Throwable t) {
            Log.i(TAG, "ASR 预热跳过（不影响使用）: " + t.getMessage());
        }
        final MtEngine m = mt;
        if (m != null) {
            try {
                mtExec.execute(() -> {
                    try {
                        m.translate("你好。", "zho_Hans", prefs.targetLang());
                    } catch (Throwable ignored) {
                        // 预热失败无所谓
                    }
                });
            } catch (Throwable ignored) {
                // mtExec 已关闭，服务在停
            }
        }
        Log.i(TAG, "引擎预热完成，耗时 " + (System.currentTimeMillis() - t0) + "ms");
    }

    /** 把模型加载期间攒下的段按原顺序交给识别，保证上屏顺序与实际说话顺序一致。 */
    private void drainPendingSegments() {
        float[] seg;
        while ((seg = pollPending()) != null) {
            submitFinal(seg);
        }
    }

    private float[] pollPending() {
        synchronized (pendingLock) {
            return pendingSegments.pollFirst();
        }
    }

    private void holdPendingSegment(float[] samples) {
        synchronized (pendingLock) {
            // 引擎迟迟装不好时不能无限攒（一段最长 20s 音频），丢最早的，保最近的
            if (pendingSegments.size() >= MAX_PENDING_SEGMENTS) {
                pendingSegments.pollFirst();
            }
            pendingSegments.addLast(samples);
        }
        Log.i(TAG, "模型仍在加载，本段音频已排队等待识别");
    }

    private void loadEngines(String provider) {
        int threads = prefs.mtThreads();
        File mtDir = models.mtDir();
        mt = MtEngine.create(mtDir.getAbsolutePath(), threads, prefs.beamForTier());
        if (mt == null && !mtDir.equals(models.nllbDir())) {
            // 语对专用模型（OPUS-MT）没有 NLLB 那套语言码体系，得新版 native 才认。
            // 旧 libvtrans-mt.so 只找 sentencepiece.bpe.model，读它就返回 null ——
            // 这时退回 NLLB：照常能用，只是每 token 仍是 45ms 那个量级。
            Log.w(TAG, "翻译模型 " + mtDir.getName() + " 加载失败（libvtrans-mt.so 该重编了），退回 NLLB");
            mtDir = models.nllbDir();
            mt = MtEngine.create(mtDir.getAbsolutePath(), threads, prefs.beamForTier());
        }
        if (mt == null) {
            // mt_engine 的 lastError 没走 JNI 暴露，native 侧的原因看不到，
            // 所以把模型目录打出来：少文件 / 文件不全是最常见的原因
            Log.e(TAG, "翻译模型加载失败，目录内容：" + describeDir(mtDir));
            throw new IllegalStateException("翻译模型加载失败（" + mtDir + "）");
        }
        Log.i(TAG, "翻译模型: " + mtDir.getName() + "，" + threads + " 线程");
        pairMt = !mtDir.equals(models.nllbDir());
        asr = AsrEngine.create(models, provider, Math.min(2, threads), prefs.sourceLang());

        // 提前把 Whisper 载好：高精档、或用户明确选了非中/粤语种时这句一定会用到。
        // auto + 均衡档的中文用户不预载 —— 375MB 模型白占内存还拖慢启动。
        String src = prefs.sourceLang();
        boolean eagerWhisper = Prefs.TIER_QUALITY.equals(prefs.tier())
                || (!"auto".equals(src) && !isZhish(src));
        if (eagerWhisper && models.isReady(ModelsManifest.WHISPER) && !asr.hasWhisper()) {
            asr.switchWhisperLang(models, provider, Math.min(2, threads), src);
        }

        // 流式优先：装了英文流式模型就把它当主识别器。provider 一律给 cpu：
        // chunk 化的 zipformer 在 NNAPI/XNNPACK 上算子回退很多，并不比 cpu 稳。
        // 预热必须在把 streaming 发布出去之前做：那一刻起音频帧才会开始并发改这个流。
        if (streamingIntended) {
            unpackIfNeeded(ModelsManifest.ZIPFORMER_EN);
            StreamingAsr s = StreamingAsr.create(models, "cpu", Math.min(2, threads));
            if (s == null) {
                streamingIntended = false;
                Log.w(TAG, "流式模型存在但加载失败，本轮退回 SenseVoice");
            } else {
                warmUpStreaming(s);
                streamExec = Executors.newSingleThreadExecutor(
                        r -> new Thread(r, "vtrans-stream"));
                // 先把加载期间攒下的帧按序入队，再发布 streaming：反过来写就会
                // 出现新帧排在旧帧前面，流式解码按到达顺序累计特征，顺序乱了字就乱。
                drainPendingFrames(s);
                streaming = s;
            }
        }
    }

    /**
     * 模型已经随 APK 装进手机，但还要从 APK 拷到可读写的目录才能加载。
     * 这一步原来要人去设置页点「解包」——说话前先做一次手动配置没道理，
     * 所以引擎加载前顺手拷掉（70MB 约 1 秒，期间音频帧已经攒在 pendingFrames 里）。
     */
    private void unpackIfNeeded(ModelsManifest.Model m) {
        if (models.isReady(m)) return;
        long t0 = System.currentTimeMillis();
        try {
            models.ensure(m, null);
            Log.i(TAG, m.label + " 已自动解包，耗时 "
                    + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable t) {
            // 空间不够 / 拷贝中断都不能把整条链路带崩：没它照样能翻译，只是回到旧时序
            Log.w(TAG, m.label + " 自动解包失败，本轮不用它: " + t.getMessage());
        }
    }

    /** 拿随包的英文音频过一遍图：sherpa 首次 run 要做图优化与内存分配，明显更慢 */
    private void warmUpStreaming(StreamingAsr s) {
        long t0 = System.currentTimeMillis();
        try {
            float[] wav = WaveReader.read(this, "bench_en.wav");
            s.warmUp(wav, 320);
            Log.i(TAG, "流式预热完成，耗时 " + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable t) {
            Log.i(TAG, "流式预热跳过（不影响使用）: " + t.getMessage());
        }
    }

    /** 增量文本上屏节流：文字没变不广播，变了也最多每 250ms 一次（只 streamExec 线程读写） */
    private static final long PARTIAL_MIN_BROADCAST_MS = 250;
    private String lastStreamPartial = "";
    private long lastStreamPartialAtMs;

    /**
     * 把一帧音频交给流式解码线程。
     *
     * <p>录音线程只做拷贝与排队：一次 decode 要几毫秒到十几毫秒，压在 20ms 一帧
     * 的节拍上会把音频线程拖慢，接下来就是系统丢帧——那是整条链路最难查的故障。
     */
    private void feedStreaming(float[] frame, int len) {
        final StreamingAsr s = streaming;
        if (s == null) return;
        // AudioCapture 的帧缓冲是复用的，交给另一条线程前必须自己取一份
        queueFrame(s, Arrays.copyOf(frame, len));
    }

    private void queueFrame(final StreamingAsr engine, final float[] copy) {
        if (streamBacklog.get() > STREAM_MAX_BACKLOG) {
            // 本类日志全走同一条线程、内容完全一致，刷屏会被 logcat 的 chatty
            // 折叠掉（上一轮就是这样：实际丢了几百帧，文件里只留下 4 行）。
            // 所以改成每 50 帧报一次累计数。
            int n = streamDrops.incrementAndGet();
            if (n % 50 == 1) {
                Log.w(TAG, "流式队列积压，丢弃本帧（累计丢 " + n + " 帧，backlog="
                        + streamBacklog.get() + "）");
            }
            return;
        }
        streamBacklog.incrementAndGet();
        try {
            streamExec.execute(() -> {
                streamBacklog.decrementAndGet();
                if (!running) return;
                String sentence = engine.feed(copy);
                if (sentence != null) {
                    lastStreamPartial = "";
                    onStreamSentence(sentence);
                    return;
                }
                if (!engine.takePartialDirty()) return;
                String p = engine.partialText();
                long now = System.currentTimeMillis();
                if (p.isEmpty() || p.equals(lastStreamPartial)
                        || now - lastStreamPartialAtMs < PARTIAL_MIN_BROADCAST_MS) {
                    return;
                }
                lastStreamPartial = p;
                lastStreamPartialAtMs = now;
                broadcast("partial", p, streamLang(), 0);
            });
        } catch (Throwable t) {
            streamBacklog.decrementAndGet();
        }
    }

    /** 流式方向的源语言：设置里锁死了就用设置值，auto 那么本轮就是英文 */
    private String streamLang() {
        String setting = prefs.sourceLang();
        if (setting != null && !"auto".equals(setting)) return setting;
        return "eng_Latn";
    }

    /**
     * 流式的一句说完。注意这里<b>没有识别耗时</b>：那句话是跟着话音一点点点缀出来的，
     * endpoint 命中的时候已经算完了，体感延迟从此只剩「判停确认 + 翻译」。
     */
    private void onStreamSentence(String text) {
        String srcLang = streamLang();
        // 语种按书写系统再判一次：万一用户中途改口说中文，detectLang 会给出 zho，
        // 交给 NLLB 的编码才对得上（流式英文模型硬把中文念成空白时也走这里）。
        String detected = MtEngine.detectLang(text);
        if ("auto".equals(prefs.sourceLang()) && detected != null) srcLang = detected;

        Log.i(TAG, String.format(Locale.ROOT, "定稿(流式): %s", abbrev(text)));
        broadcast("final", text, srcLang, 0);
        enqueueTranslation(splitter.push(text), srcLang);
        handleTail(srcLang);
    }

    private void holdPendingFrame(float[] frame, int len) {
        synchronized (pendingLock) {
            if (pendingFrames.size() >= MAX_PENDING_FRAMES) pendingFrames.pollFirst();
            pendingFrames.addLast(Arrays.copyOf(frame, len));
        }
    }

    /** 引擎就绪：把加载期间攒下的帧按原顺序补喂，首句不丢也不走错路 */
    private void drainPendingFrames(StreamingAsr engine) {
        float[] f;
        while ((f = pollPendingFrame()) != null) {
            queueFrame(engine, f);
        }
    }

    private float[] pollPendingFrame() {
        synchronized (pendingLock) {
            return pendingFrames.pollFirst();
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
        // 流式模型在不在，录音一开始就该知道：引擎还要加载 1~5s（首启还要多一次解包），
        // 这段时间抵达的帧必须攒起来而不是交给 VAD，否则第一句会走错那条路子。
        // 只查 isReady 不够：刚装完 APK 时模型在包里还没拷出来，那时候也该走流式。
        streamingIntended = models.isReady(ModelsManifest.ZIPFORMER_EN)
                || models.hasAsset(ModelsManifest.ZIPFORMER_EN);
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

        capture = new AudioCapture(this, effectiveAudioMode(), new AudioCapture.Sink() {
            @Override
            public void onFrame(float[] vadFrame, float[] asrFrame, int len) {
                // 心跳：每 100 帧报一次。这一行能直接分清三件事——帧到底有没有
                // 进来、一帧实际多长、以及它是递给流式还是攒进 pending。
                if ((++audioFrames % 100) == 0) {
                    Log.i(TAG, "帧#" + audioFrames + " len=" + len
                            + "（" + (len * 1000L / 16000) + "ms） streaming="
                            + (streaming != null) + " intended=" + streamingIntended
                            + " backlog=" + streamBacklog.get());
                }
                if (streaming != null) {
                    feedStreaming(asrFrame, len);
                    return;
                }
                if (streamingIntended) {
                    // 流式引擎还在路上：帧先存着，就绪后按原顺序补喂，第一句不丢
                    holdPendingFrame(asrFrame, len);
                    return;
                }
                // 门控路只喂 VAD，无门控路进识别缓冲 —— 两者在 VadSegmenter 里分流
                if (vad != null) vad.feed(vadFrame, asrFrame);
            }

            @Override
            public void onLevel(double outDbfs, double agcDb) {
                broadcastLevel(outDbfs, agcDb);
            }

            @Override
            public void onError(Throwable t) {
                Log.e(TAG, "录音出错", t);
                broadcastStatus("error", "录音出错：" + t.getMessage());
                stopSelf();
            }
        });

        capture.setMicBoostDb(prefs.micBoostDb());
        capture.setCaptureRateHz(prefs.captureRateHz());
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

    /**
     * 实际使用的录音处理方案。
     *
     * <p><b>硬件回声消除优先</b>：系统 {@link android.media.audiofx.AcousticEchoCanceler}
     * 基本只在通话音源（VOICE_COMMUNICATION）上才会被 ROM 真正启用，这是压制
     * 「TTS 外放播报 → 被本机麦克风拾取 → 再识别再翻译」回环的关键。
     *
     * <p>但通话音源会带上一整套通信优化（可能是窄带/强压缩），对远场识别不友好，
     * 所以只在真的存在外放回声源时才切过去：即用户关掉了「仅插入耳机时播报译文」。
     * 默认（仅耳机播报）扬声器不发声，没有回声源，继续用识别音源，识别质量不受影响。
     */
    private String effectiveAudioMode() {
        String mode = prefs.audioMode();
        if (Prefs.AUDIO_AUTO.equals(mode) && !prefs.ttsHeadsetOnly()) {
            return Prefs.AUDIO_SYSTEM;
        }
        return mode;
    }

    /** 增量预览：只在均衡档做，且上一轮跑完才发下一轮 */
    private void maybePartial() {
        if (!running || !prefs.partialsEnabled() || vad == null) return;
        // 模型还没装好就别抢 asrExec（那段时间在排队的是攒下来的真音频）
        if (!enginesReady) return;
        // 流式引擎在跑：它自己就提供增量文本，这套定时整段重跑既多余又会抢核
        if (streaming != null) return;
        // 有定稿在跑或排队：这一拍预览直接跳过。预览晚一拍没人看得出，
        // 定稿晚一拍就是「说完话干等」。
        if (finalQueued.get() > 0) return;
        if (!vad.isSpeaking() || !partialBusy.compareAndSet(false, true)) return;
        float[] samples = vad.snapshot();
        if (samples == null) { // 短于 VadSegmenter 的最小可认长度（含前缀）就没什么可认的
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
        // 流式引擎在跑：句边界由 endpoint 判，这一路不再产生段（正常走不到这里，
        // 留着是防 shutdown/restart 交错时两个引擎同时上屏同一句）
        if (streaming != null) return;
        // 模型还在加载：攒起来，等 drainPendingSegments() 按序补跑，而不是把这句话丢掉
        if (!enginesReady) {
            holdPendingSegment(samples);
            return;
        }
        // 判停时刻：用来算「排了多久队」，这是说完话之后最先吃掉的隐形时间
        final long cutAt = System.currentTimeMillis();
        lastSegmentAtMs = cutAt;
        // 新一段来了，先前攒着的碎句由这一段一起处理，兜底任务不用再跑
        mainHandler.removeCallbacks(tailFlushGuard);
        finalQueued.incrementAndGet();
        try {
            asrExec.execute(() -> {
                if (!running) { finalQueued.decrementAndGet(); return; } // 关闭流程已开始
                // 任务执行时 shutdown 可能已把字段置空并排队了 release——
                // 先取局部引用；本任务在 release 之前执行完，引擎仍有效。
                AsrEngine engine = asr;
                if (engine == null) { finalQueued.decrementAndGet(); return; }
                long t0 = System.currentTimeMillis();
                long queueMs = t0 - cutAt;
                // finally 里 flush 时也要用同一个语种，"auto" 不能直接喂给 NLLB。
                // 初值一定要是 null：没判出语种就干脆不翻，别让还没赋过值的
                // 中文句子被当成英文送进去，输出看起来像“识别错了”。
                String srcLang = null;
                long svMs = 0;
                long whMs = 0;
                try {
                String srcSetting = prefs.sourceLang();
                // 归一化前的峰值：transcribe() 会原地改 samples，事后再量就不准了
                final float segPeak = peakOf(samples);
                long tSv0 = System.currentTimeMillis();
                String text = engine.hasSenseVoice()
                        ? engine.transcribe(AsrEngine.Which.SENSEVOICE, samples)
                        : null;
                svMs = System.currentTimeMillis() - tSv0;

                // SenseVoice 的 auto 语种标签不可信（实测每个语种都返回 <|yue|>），
                // 所以语种一律按"识别出来的文字用了哪种书写系统"来定。
                String detected = text == null ? null : MtEngine.detectLang(text);
                srcLang = "auto".equals(srcSetting)
                        ? (detected == null ? "eng_Latn" : detected) : srcSetting;

                // Whisper 只在该用的时候用，且按语种懒加载（375MB 不常驻内存）。
                // looksMissed：整段够长（≥1.2s，段首尾静音已被 VadSegmenter 裁掉）
                // 却几乎没出字，这种时候值得花 6~10 倍时间用 Whisper 重跑一次
                // （Whisper 对低电平更宽容）。
                // segPeak 那道门不能省：Whisper 有个出名的毛病——给它接近静音的音频，
                // 它会凭空编出一句话来，宁可少说也别上屏假字。
                boolean looksMissed = samples.length >= (long) (1.2 * VadSegmenter.SAMPLE_RATE)
                        && segPeak > MISSED_MIN_PEAK
                        && (text == null || text.trim().length() <= 2);
                boolean wantWhisper = shouldUseWhisper(detected, srcSetting)
                        || text == null      // SenseVoice 失手时的兜底
                        || looksMissed;      // 轻声/远场没认出来
                if (wantWhisper && models.isReady(ModelsManifest.WHISPER)) {
                    long tWh0 = System.currentTimeMillis();
                    engine.switchWhisperLang(models, providerOfAsr(), providerThreads(), srcLang);
                    String better = engine.transcribe(AsrEngine.Which.WHISPER, samples);
                    whMs = System.currentTimeMillis() - tWh0;
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
                // 逐句链路日志：延迟到底卡在排队、SenseVoice、Whisper 还是翻译，一眼能看出来
                Log.i(TAG, String.format(Locale.ROOT,
                                "定稿: 音频%.2fs 排队%dms SenseVoice%dms Whisper%dms",
                                samples.length / (float) VadSegmenter.SAMPLE_RATE,
                                queueMs, svMs, whMs));
                broadcast("final", text, srcLang, asrMs);

                // 一次定稿切出多句时合并成一次解码调用（见 enqueueTranslation）
                enqueueTranslation(splitter.push(text), srcLang);
            } catch (Throwable t) {
                Log.e(TAG, "最终识别失败", t);
            } finally {
                finalQueued.decrementAndGet();
                handleTail(srcLang);
            }
            });
        } catch (Throwable t) {
            // executor 已 shutdown：忽略排队失败
            finalQueued.decrementAndGet();
            Log.w(TAG, "最终识别任务提交失败", t);
        }
    }

    /**
     * 残句处理（整段与流式两条路共用）：纯语气词直接丢，够长就马上翻，
     * 太短只等 0.4s——速度优先，宁可句子拼得短一点，也别让用户干等。
     */
    private void handleTail(String srcLang) {
        if (srcLang == null) return;
        lastSegLang = srcLang;
        String tailText = splitter.pendingText().trim();
        if (!tailText.isEmpty() && isPureFiller(tailText)) {
            splitter.dropPending();
            return;
        }
        int tail = splitter.pendingLength();
        if (tail >= TAIL_KEEP_MIN_CHARS) {
            flushSplitter(srcLang);
        } else if (tail > 0) {
            mainHandler.postDelayed(tailFlushGuard, TAIL_FLUSH_DELAY_MS);
        }
    }

    /** 攒着的碎句兜底：到点还没被下一段接走就强制吐给 MT，保证译文一定会出现 */
    private final Runnable tailFlushGuard = this::maybeFlushTail;

    private void maybeFlushTail() {
        if (!running || splitter.pendingLength() == 0) return;
        // 这一会儿又有新段进来了（新段自己会重新安排兜底），再等一小轮
        if (System.currentTimeMillis() - lastSegmentAtMs < TAIL_FLUSH_DELAY_MS - 300) {
            mainHandler.postDelayed(tailFlushGuard, 300);
            return;
        }
        String lang = lastSegLang;
        if (lang == null) return;
        try {
            // 与识别、切句同一线程执行，避免 push() 和 flush() 并发操作 splitter
            asrExec.execute(() -> flushSplitter(lang));
        } catch (Throwable ignored) {
            // executor 已关闭：服务在停，残句不用管
        }
    }

    /** 把切句器里攒下的残句全部送去翻译（识别线程调用） */
    private void flushSplitter(String lang) {
        enqueueTranslation(splitter.flush(), lang);
    }

    /** 低于这个峰值（≈ -40dBFS）的定稿段当作“基本只有底噪”，不值得花 Whisper 重跑 */
    private static final float MISSED_MIN_PEAK = 0.01f;

    private static float peakOf(float[] x) {
        if (x == null) return 0f;
        float peak = 0f;
        for (float v : x) {
            float a = Math.abs(v);
            if (a > peak) peak = a;
        }
        return peak;
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

    /**
     * 把一批句子交给翻译线程。
     *
     * <p>多句合并成一次 {@code translateBatch}：CTranslate2 的批量解码把若干次
     * GEMV 合成一次 GEMM，两句的总时间远小于「串行翻两遍」（实测每句 ~0.85s，
     * 批下来接近 1.1s 而不是 1.7s）。单句仍走原路径，不为凑批让首句多等。
     *
     * <p>纯语气词在这一步再筛一次：识别结果里的「嗯。」「oh.」带终止符，
     * 会直接从 push() 出句，绕过 submitFinal 末尾那道残句过滤。
     */
    private void enqueueTranslation(List<String> sentences, String srcLang) {
        if (sentences == null || sentences.isEmpty()) return;
        final MtEngine engine = mt;
        if (engine == null) return;
        if (pairMt && isZhish(srcLang)) {
            // 语对专用模型只认英→中：中文喂进去不报错，只会产出看着像模像样的胡话。
            // 宁可把原文当译文上屏（中途改口说中文、或误检成中文时就是这种情形）。
            for (String s : sentences) {
                if (s != null && !s.trim().isEmpty()) broadcast("translation", s, srcLang, 0);
            }
            return;
        }
        List<String> batch = null;
        for (String s : sentences) {
            if (s == null || s.trim().isEmpty() || isPureFiller(s)) continue;
            if (batch == null) batch = new ArrayList<>(sentences.size());
            batch.add(s);
        }
        if (batch == null) return;
        final List<String> todo = batch;
        final String[] texts = todo.toArray(new String[0]);
        try {
            mtExec.execute(() -> {
                long t0 = System.currentTimeMillis();
                String[] outs = null;
                try {
                    outs = texts.length == 1
                            ? new String[]{engine.translate(texts[0], srcLang, prefs.targetLang())}
                            : engine.translateBatch(texts, srcLang, prefs.targetLang());
                } catch (Throwable t) {
                    Log.e(TAG, "翻译失败", t);
                }
                long ms = System.currentTimeMillis() - t0;
                // 批量时按句摊平再进统计：PerfGuard 的基线要表示"每句成本"才有意义
                mtStats.add(ms / texts.length);
                // 翻译这一环的耗时（含排队）单独记一条：它和识别是分开的两个线程
                Log.i(TAG, String.format(Locale.ROOT, "译文: %dms %d句%d字 level=%d ← %s",
                        ms, texts.length, srcChars(todo), degradeLevel.get(),
                        abbrev(texts[0])));
                if (outs == null) return;
                for (int i = 0; i < outs.length && i < texts.length; i++) {
                    String out = outs[i];
                    if (out == null || out.trim().isEmpty()) continue;
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

    private static String abbrev(String s) {
        if (s == null) return "";
        return s.length() > 18 ? s.substring(0, 18) + "…" : s;
    }

    private static int srcChars(List<String> sentences) {
        int n = 0;
        for (String s : sentences) n += s.length();
        return n;
    }

    /**
     * 是不是纯语气词。先去标点再比长度：「嗯。」「oh.」这种带终止符的碎片会
     * 直接从切句器出句，绕不过这里。只认「短到不承载信息」的片段——汉字一到
     * 两个、拉丁词只认表里那几个，再长就当内容有值，宁翻不误删。
     */
    private static boolean isPureFiller(String s) {
        if (s == null) return false;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        String word = sb.toString();
        if (word.isEmpty() || word.length() > 3) return false;
        if (FILLER_LATIN.contains(word)) return true;
        if (word.length() > 2) return false;
        for (int i = 0; i < word.length(); i++) {
            if (FILLER_ZH.indexOf(word.charAt(i)) < 0) return false;
        }
        return true;
    }

    private static final java.util.Set<String> FILLER_LATIN = new java.util.HashSet<>(
            Arrays.asList("uh", "um", "oh", "ah", "er", "mm", "eh", "hmm"));
    private static final String FILLER_ZH = "嗯哦啊呃呢哈呀噢唔嘞嘛哎咳唔";

    /** 译文语音播报；是否出声由「仅耳机播报」开关决定（见 TtsSpeaker） */
    private void speakTranslation(String text, String targetLang) {
        TtsSpeaker t = tts;
        if (t != null) t.speak(text, targetLang, prefs.ttsHeadsetOnly());
    }

    /**
     * PerfGuard：骁龙 888 发热降频很凶，持续降频时减少并发能止损一点。
     *
     * <p>三条纪律是老实现缺的，也是「越用越顿」的直接原因：
     * <ol>
     *   <li><b>要有绝对门槛</b>：冷机头几句特别快，基线被钉在 200ms 之后，正常
     *       六七百毫秒也算「超基线 2.5 倍」，一上来就被降档。</li>
     *   <li><b>要有下限</b>：降到 1 线程时 NLLB-600M 每句要好几秒，「省电」变成「卡」，
     *       与同传的初衷相反，所以最低只到 {@link #MIN_MT_THREADS}。</li>
     *   <li><b>要能回升</b>：机器凉下来之后必须把档位升回去，否则一次偶发抖动就永久锁死。</li>
     * </ol>
     * <p>只在 mtExec 单线程上调用，fastStreak/latencyBaselineMs 不需要额外同步。
     */
    private void guardPerformance() {
        if (mt == null || mtStats.count() < 5) return;
        long avg = mtStats.avgMs();
        if (latencyBaselineMs == 0) {
            latencyBaselineMs = Math.max(300, avg);
            return;
        }
        long base = latencyBaselineMs;
        int level = degradeLevel.get();
        if (avg > base * 2.5 && avg > DEGRADE_FLOOR_MS) {
            fastStreak = 0;
            if (level == 0 && prefs.mtThreads() > MIN_MT_THREADS) {
                mt.setThreads(MIN_MT_THREADS);
                degradeLevel.set(1);
                latencyBaselineMs = avg;
                updateNotification("设备发热，翻译已降到 " + MIN_MT_THREADS + " 线程");
                Log.i(TAG, String.format(Locale.ROOT,
                        "PerfGuard 降级: avg=%dms 基线=%dms → %d 线程", avg, base, MIN_MT_THREADS));
            } else if (level <= 1 && prefs.beamForTier() > 1) {
                mt.setBeam(1);
                degradeLevel.set(2);
                latencyBaselineMs = avg;
                updateNotification("设备发热，解码宽度已降到 beam=1");
                Log.i(TAG, String.format(Locale.ROOT,
                        "PerfGuard 降级: avg=%dms 基线=%dms → beam=1", avg, base));
            }
        } else if (level > 0 && avg < base * 1.3) {
            if (++fastStreak >= RECOVER_STREAK) {
                fastStreak = 0;
                mt.setThreads(prefs.mtThreads());
                mt.setBeam(prefs.beamForTier());
                degradeLevel.set(0);
                latencyBaselineMs = Math.max(300, avg);
                Log.i(TAG, String.format(Locale.ROOT,
                        "PerfGuard 回升: avg=%dms → %d 线程 / beam=%d",
                        avg, prefs.mtThreads(), prefs.beamForTier()));
            }
        } else {
            fastStreak = 0;
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

    /**
     * 电平广播：约 240ms 一次。不碰会话快照（电平不是会话内容），
     * 也不走通知防抖，UI 没在前台时它就是个被丢掉的本地广播，成本可忽略。
     */
    private void broadcastLevel(double dbfs, double agcDb) {
        if (!running) return;
        sendBroadcast(new Intent(ACTION_LEVEL)
                .setPackage(getPackageName())
                .putExtra(EXTRA_DBFS, (float) dbfs)
                .putExtra(EXTRA_AGC_DB, (float) agcDb));
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
        enginesReady = false;
        synchronized (pendingLock) {
            pendingSegments.clear(); // 旧会话攒的段对新会话没有意义
        }
        // 碎句兜底与"定稿优先"计数都要归零：否则残句会在服务停止后又被翻一次，
        // 而 finalQueued 卡在正值会让下一轮会话的增量预览永远被跳过。
        mainHandler.removeCallbacks(tailFlushGuard);
        finalQueued.set(0);
        lastSegLang = null;
        lastSegmentAtMs = 0;

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
        // 2.5) 流式引擎：先把字段置空（录音线程据此停止投递新帧），排干 streamExec
        //      队列后再释放 native 流——顺序错了就是 use-after-free。
        StreamingAsr oldStreaming = streaming;
        streaming = null;
        streamingIntended = false;
        streamBacklog.set(0);
        synchronized (pendingLock) {
            pendingFrames.clear();
            pendingSegments.clear();
        }
        if (streamExec != null) {
            streamExec.shutdown();
            awaitTermination(streamExec, 800);
            streamExec = null;
        }
        if (oldStreaming != null) oldStreaming.release();

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
