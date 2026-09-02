package com.example.vtrans;

import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.vtrans.model.ModelManager;
import com.example.vtrans.model.ModelsManifest;
import com.example.vtrans.pipeline.AsrEngine;
import com.example.vtrans.util.Prefs;
import com.example.vtrans.util.WaveReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设置页：目标语言 / 源语言 / 档位 / 加速后端 / 线程 / beam / 模型管理 / 基准测试。
 *
 * <p>「跑一次基准测试」同时是真机自检：它用内置的两段语音（中/英）分别跑三个后端，
 * 既比出最快的 provider，也把识别文本打出来让人确认 NPU 路径没把结果跑歪。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";

    private Prefs prefs;
    private ModelManager models;
    private ExecutorService exec;

    private TextView tvThreads;
    private Button btnBench;
    private LinearLayout modelList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        prefs = new Prefs(this);
        models = new ModelManager(this);
        exec = Executors.newSingleThreadExecutor(r -> new Thread(r, "vtrans-settings"));

        RadioGroup rgTarget = findViewById(R.id.rgTarget);
        Spinner spSource = findViewById(R.id.spSource);
        RadioGroup rgTier = findViewById(R.id.rgTier);
        Spinner spProvider = findViewById(R.id.spProvider);
        Spinner spBeam = findViewById(R.id.spBeam);
        SeekBar sbThreads = findViewById(R.id.sbThreads);
        tvThreads = findViewById(R.id.tvThreads);
        btnBench = findViewById(R.id.btnBench);
        modelList = findViewById(R.id.modelList);

        // ---- 目标语言 ----
        rgTarget.check("en".equals(prefs.targetLang()) ? R.id.rbTargetEn : R.id.rbTargetZh);
        rgTarget.setOnCheckedChangeListener((g, id) ->
                prefs.setTargetLang(id == R.id.rbTargetEn ? "en" : "zh"));

        // ---- 源语言 ----
        String[] values = getResources().getStringArray(R.array.source_lang_values);
        int srcIdx = indexOf(values, prefs.sourceLang());
        spSource.setSelection(Math.max(0, srcIdx));
        spSource.setOnItemSelectedListener(new SimpleSelect(pos -> {
            if (pos < values.length) prefs.setSourceLang(values[pos]);
        }));

        // ---- 档位 ----
        rgTier.check(Prefs.TIER_QUALITY.equals(prefs.tier())
                ? R.id.rbTierQuality : R.id.rbTierBalanced);
        rgTier.setOnCheckedChangeListener((g, id) -> {
            prefs.setTier(id == R.id.rbTierQuality ? Prefs.TIER_QUALITY : Prefs.TIER_BALANCED);
            spBeam.setSelection(Prefs.TIER_QUALITY.equals(prefs.tier()) ? 1 : 0);
        });

        // ---- provider ----
        String[] providers = getResources().getStringArray(R.array.provider_values);
        spProvider.setSelection(Math.max(0, indexOf(providers, prefs.provider())));
        spProvider.setOnItemSelectedListener(new SimpleSelect(pos -> {
            if (pos < providers.length) prefs.setProvider(providers[pos]);
        }));

        // ---- beam ----
        spBeam.setSelection(prefs.mtBeam() >= 4 ? 1 : 0);
        spBeam.setOnItemSelectedListener(new SimpleSelect(
                pos -> prefs.setMtBeam(pos == 1 ? 4 : 1)));

        // ---- 线程数 ----
        sbThreads.setMax(4);
        sbThreads.setProgress(Math.min(4, prefs.mtThreads()));
        updateThreadsLabel(prefs.mtThreads());
        sbThreads.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = Math.max(1, progress);
                prefs.setMtThreads(v);
                updateThreadsLabel(v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // no-op
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // no-op
            }
        });

        btnBench.setOnClickListener(v -> runBenchmark());
        buildModelRows();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        if (exec != null) exec.shutdownNow();
        super.onDestroy();
    }

    private void updateThreadsLabel(int v) {
        tvThreads.setText(String.format(Locale.getDefault(),
                "%d 线程（共 %d 核）", v, Runtime.getRuntime().availableProcessors()));
    }

    // ---------------- 模型管理 ----------------

    private void buildModelRows() {
        modelList.removeAllViews();
        for (ModelsManifest.Model m : ModelsManifest.ALL) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 8, 0, 8);

            TextView info = new TextView(this);
            boolean ready = models.isReady(m);
            info.setText(String.format(Locale.getDefault(), "%s\n%s%s",
                    m.label,
                    ready ? getString(R.string.model_ready)
                          : getString(R.string.model_missing),
                    m.required ? "" : "（可选）"));
            info.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
            info.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button action = new Button(this, null,
                    android.R.attr.buttonBarButtonStyle);
            action.setText(ready ? getString(R.string.model_delete)
                    : getString(R.string.model_download));
            action.setOnClickListener(v -> {
                if (models.isReady(m)) {
                    models.delete(m);
                    buildModelRows();
                } else {
                    downloadOne(m, action);
                }
            });

            row.addView(info);
            row.addView(action);
            modelList.addView(row);
        }
    }

    private void downloadOne(ModelsManifest.Model m, Button action) {
        action.setEnabled(false);
        exec.execute(() -> {
            try {
                models.ensure(m, new ModelManager.Progress() {
                    @Override
                    public void onProgress(long done, long total, String stage) {
                        runOnUiThread(() -> action.setText(stage));
                    }

                    @Override
                    public boolean isCancelled() {
                        return isFinishing() || isDestroyed();
                    }
                });
                runOnUiThread(this::buildModelRows);
            } catch (Exception e) {
                Log.e(TAG, "下载失败 " + m.id, e);
                runOnUiThread(() -> {
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    buildModelRows();
                });
            }
        });
    }

    // ---------------- 基准测试 / 真机自检 ----------------

    private void runBenchmark() {
        btnBench.setEnabled(false);
        btnBench.setText("正在测试…");
        exec.execute(() -> {
            StringBuilder report = new StringBuilder();
            try {
                float[] zh = WaveReader.read(this, "bench_zh.wav");
                float[] en = WaveReader.read(this, "bench_en.wav");
                String[] providers = {"nnapi", "xnnpack", "cpu"};
                long best = Long.MAX_VALUE;
                String bestProvider = "cpu";

                for (String p : providers) {
                    AsrEngine engine = null;
                    try {
                        long t0 = System.currentTimeMillis();
                        engine = AsrEngine.create(models, p, 2, "auto");
                        long loadMs = System.currentTimeMillis() - t0;

                        long bestRun = Long.MAX_VALUE;
                        String text = null;
                        for (int i = 0; i < 2; i++) {
                            long s = System.currentTimeMillis();
                            text = engine.transcribe(AsrEngine.Which.SENSEVOICE, zh);
                            long d = System.currentTimeMillis() - s;
                            bestRun = Math.min(bestRun, d);
                        }
                        String enText = engine.transcribe(AsrEngine.Which.SENSEVOICE, en);
                        report.append(String.format(Locale.getDefault(),
                                "%s: 加载 %dms, 中文 5.6s 音频 %dms\n  中文: %s\n  英文: %s\n",
                                p, loadMs, bestRun, text, enText));
                        if (bestRun > 0 && bestRun < best) {
                            best = bestRun;
                            bestProvider = p;
                        }
                    } catch (Throwable t) {
                        report.append(p).append(": 不可用（")
                                .append(t.getMessage()).append("）\n");
                    } finally {
                        if (engine != null) engine.release();
                    }
                }

                prefs.setBenchedProvider(bestProvider);
                report.append("\n已选中最快后端: ").append(bestProvider);
                final String finalReport = report.toString();
                runOnUiThread(() -> showReport(finalReport));
            } catch (IOException e) {
                final String msg = "基准测试失败: " + e.getMessage();
                runOnUiThread(() -> showReport(msg));
            } finally {
                runOnUiThread(() -> {
                    btnBench.setEnabled(true);
                    btnBench.setText(R.string.settings_bench);
                });
            }
        });
    }

    private void showReport(String text) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("基准测试 / 自检")
                .setMessage(text)
                .setPositiveButton("好", null)
                .show();
    }

    private static int indexOf(String[] arr, String v) {
        List<String> list = new ArrayList<>();
        for (String s : arr) list.add(s);
        return list.indexOf(v);
    }

    /** Spinner 的监听器只要 onItemSelected，这里省掉另外两个回调的样板代码 */
    private static final class SimpleSelect implements
            android.widget.AdapterView.OnItemSelectedListener {
        private final Listener l;

        interface Listener {
            void onPicked(int position);
        }

        SimpleSelect(Listener l) {
            this.l = l;
        }

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                   int position, long id) {
            l.onPicked(position);
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
            // no-op
        }
    }
}
