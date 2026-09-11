package com.example.vtrans;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.vtrans.model.ModelManager;
import com.example.vtrans.model.ModelsManifest;
import com.example.vtrans.util.Prefs;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主界面：只有「开始翻译 / 结束翻译」两个主按钮，上面是原文和译文。
 *
 * <p>原文分成两段渲染：已经定稿的用正常色，正在识别中的用灰色，
 * 这样用户能一眼看出哪部分还会变。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_PERM = 100;

    private TextView tvStatus;
    private TextView tvSource;
    private TextView tvTarget;
    private Button btnStart;
    private Button btnStop;
    private ProgressBar progress;

    private ModelManager models;
    private ExecutorService ioExec;
    private final AtomicBoolean unpacking = new AtomicBoolean(false);

    private final StringBuilder committedSource = new StringBuilder();
    private String partialSource = "";
    private final StringBuilder target = new StringBuilder();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (TranslateService.ACTION_RESULT.equals(intent.getAction())) {
                onResult(intent.getStringExtra(TranslateService.EXTRA_KIND),
                        intent.getStringExtra(TranslateService.EXTRA_TEXT),
                        intent.getLongExtra(TranslateService.EXTRA_LATENCY, 0));
            } else if (TranslateService.ACTION_STATUS.equals(intent.getAction())) {
                String state = intent.getStringExtra(TranslateService.EXTRA_STATE);
                String msg = intent.getStringExtra(TranslateService.EXTRA_MESSAGE);
                setStatus(msg);
                if ("error".equals(state)) {
                    setRunning(false);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        models = new ModelManager(this);
        ioExec = Executors.newSingleThreadExecutor(r -> new Thread(r, "vtrans-io"));

        tvStatus = findViewById(R.id.tvStatus);
        tvSource = findViewById(R.id.tvSource);
        tvTarget = findViewById(R.id.tvTarget);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        progress = findViewById(R.id.progress);
        Button btnSettings = findViewById(R.id.btnSettings);

        btnStart.setOnClickListener(v -> onStartClicked());
        btnStop.setOnClickListener(v -> onStopClicked());
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        registerResultReceiver();
        refreshModelState();
    }

    @Override
    protected void onStart() {
        super.onStart();
        boolean serviceOn = TranslateService.isRunning();
        // Activity 被系统回收重建时，本地三个 buffer 是空的；若服务还活着，
        // 从进程级快照把「已翻完的历史」补回来，而不是只等以后的增量广播。
        // 只有当本地确实没内容时才补，避免覆盖一直在前台正常累计的最新 UI。
        if (serviceOn && committedSource.length() == 0 && target.length() == 0) {
            TranslateService.SessionSnapshot snap = TranslateService.snapshot();
            if (snap.source.length() > 0 || snap.target.length() > 0 || snap.partial.length() > 0) {
                committedSource.append(snap.source);
                partialSource = snap.partial;
                target.append(snap.target);
                render();
            }
        }
        // 每次回前台都用当前状态刷新按钮与状态栏，避免「服务在跑但 UI 停在可点开始」。
        setRunning(serviceOn);
        if (serviceOn) {
            setStatus(getString(R.string.status_running));
        }
    }

    /**
     * 广播注册放在 onCreate/onDestroy（而不是 onStart/onStop）：
     * 切后台或去设置页时仍继续累计翻译结果，回前台不丢内容。
     * 用 registerReceiver 时包 try/catch 兜底重复注册/泄漏的极端路径。
     */
    private void registerResultReceiver() {
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(TranslateService.ACTION_RESULT);
            f.addAction(TranslateService.ACTION_STATUS);
            androidx.core.content.ContextCompat.registerReceiver(
                    this, receiver, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Exception e) {
            Log.w(TAG, "广播注册失败", e);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // 没注册过就算了
        }
        if (ioExec != null) ioExec.shutdownNow();
        super.onDestroy();
    }

    private void onStartClicked() {
        if (!hasPermissions()) {
            requestPermissions();
            return;
        }
        if (!models.allRequiredReady()) {
            startUnpack();
            return;
        }
        startTranslate();
    }

    private void onStopClicked() {
        stopService(new Intent(this, TranslateService.class)
                .setAction(TranslateService.ACTION_STOP));
        setRunning(false);
        setStatus(getString(R.string.status_stopped));
    }

    private void startTranslate() {
        committedSource.setLength(0);
        partialSource = "";
        target.setLength(0);
        render();

        // minSdk 28 > O，直接走 startForegroundService
        Intent i = new Intent(this, TranslateService.class)
                .setAction(TranslateService.ACTION_START);
        startForegroundService(i);
        setRunning(true);
        setStatus(getString(R.string.status_loading));
    }

    private boolean hasPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return false;
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS}, REQ_PERM);
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERM) return;
        for (int g : grantResults) {
            if (g != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.need_permission, Toast.LENGTH_LONG).show();
                return;
            }
        }
        onStartClicked();
    }

    // ---------------- 模型解包（模型随 APK 打包，首次启动拷到手机） ----------------

    private void refreshModelState() {
        if (models.allRequiredReady()) {
            setStatus(getString(R.string.status_idle));
            return;
        }
        List<String> lost = models.missingAssets();
        if (!lost.isEmpty()) {
            // 打包漏了文件：装了 APK 也没用，直接说清楚，别让人一直等进度条
            setStatus(getString(R.string.models_missing_in_apk, lost.toString()));
            return;
        }
        String size = ModelManager.humanSize(
                models.bytesToUnpack(models.missingRequired()));
        setStatus(getString(R.string.need_models, size));
    }

    private void startUnpack() {
        if (!unpacking.compareAndSet(false, true)) return;
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);

        ioExec.execute(() -> {
            try {
                List<ModelsManifest.Model> missing = models.missingRequired();
                final long totalBytes = models.bytesToUnpack(missing);
                long[] done = {0};

                for (ModelsManifest.Model m : missing) {
                    final long base = done[0];
                    // 必须先算：解包完 bytesToUnpack 就变 0 了
                    final long modelBytes = Math.max(models.bytesToUnpack(
                            java.util.Collections.singletonList(m)), 0);
                    models.ensure(m, new ModelManager.Progress() {
                        @Override
                        public void onProgress(long partDone, long partTotal, String stage) {
                            runOnUiThread(() -> {
                                long all = base + Math.max(partDone, 0);
                                int pct = totalBytes <= 0 ? 0
                                        : (int) (all * 100 / totalBytes);
                                progress.setProgress(Math.min(100, Math.max(0, pct)));
                                setStatus(getString(R.string.unpacking, pct,
                                        ModelManager.humanSize(all),
                                        ModelManager.humanSize(totalBytes)));
                            });
                        }

                        @Override
                        public boolean isCancelled() {
                            return isFinishing() || isDestroyed();
                        }
                    });
                    done[0] = base + modelBytes;
                }
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    setStatus(getString(R.string.unpack_done));
                    unpacking.set(false);
                    startTranslate();
                });
            } catch (IOException e) {
                Log.e(TAG, "解包模型失败", e);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    setStatus(getString(R.string.unpack_failed, e.getMessage()));
                    unpacking.set(false);
                });
            } catch (Throwable t) {
                Log.e(TAG, "解包模型异常", t);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    unpacking.set(false);
                });
            }
        });
    }

    // ---------------- 结果渲染 ----------------

    private void onResult(String kind, String text, long latencyMs) {
        if (text == null || text.isEmpty()) return;
        switch (kind == null ? "" : kind) {
            case "partial":
                partialSource = text;
                break;
            case "final":
                if (committedSource.length() > 0) committedSource.append('\n');
                committedSource.append(text);
                partialSource = "";
                break;
            case "translation":
                if (target.length() > 0) target.append('\n');
                target.append(text);
                setStatus(getString(R.string.status_running) + " · " + latencyMs + "ms");
                break;
            default:
                break;
        }
        render();
    }

    /** 已定稿的原文用正常色，增量中的用灰色 */
    private void render() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int start;
        sb.append(committedSource);
        start = sb.length();
        if (!partialSource.isEmpty()) {
            if (start > 0) sb.append('\n');
            start = sb.length();
            sb.append(partialSource);
            sb.setSpan(new ForegroundColorSpan(
                            ContextCompat.getColor(this, R.color.text_partial)),
                    start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvSource.setText(sb);
        tvTarget.setText(target.toString());

        // 跟随滚动，保证最新内容可见
        findViewById(R.id.scrollSource).post(() ->
                ((android.widget.ScrollView) findViewById(R.id.scrollSource))
                        .fullScroll(View.FOCUS_DOWN));
        findViewById(R.id.scrollTarget).post(() ->
                ((android.widget.ScrollView) findViewById(R.id.scrollTarget))
                        .fullScroll(View.FOCUS_DOWN));
    }

    private void setStatus(String s) {
        runOnUiThread(() -> tvStatus.setText(s));
    }

    private void setRunning(boolean running) {
        runOnUiThread(() -> {
            btnStart.setEnabled(!running);
            btnStop.setEnabled(running);
        });
    }
}
