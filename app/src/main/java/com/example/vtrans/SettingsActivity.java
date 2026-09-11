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
import androidx.appcompat.widget.SwitchCompat;

import com.example.vtrans.audio.SystemAudioEffects;
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
    private TextView tvAudioDiag;
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
        RadioGroup rgAudio = findViewById(R.id.rgAudio);
        tvAudioDiag = findViewById(R.id.tvAudioDiag);
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

        // ---- 录音处理方案 ----
        rgAudio.check(modeRadioId(prefs.audioMode()));
        rgAudio.setOnCheckedChangeListener((g, id) -> {
            String mode = audioModeOf(id);
            prefs.setAudioMode(mode);
            updateAudioDiag(mode);
        });

        // ---- 收音增益（远场） ----
        RadioGroup rgMicGain = findViewById(R.id.rgMicGain);
        rgMicGain.check(micBoostRadioId(prefs.micBoostDb()));
        rgMicGain.setOnCheckedChangeListener((g, id) -> {
            prefs.setMicBoostDb(micBoostDbOf(id));
            updateAudioDiag(prefs.audioMode());
        });
        updateAudioDiag(prefs.audioMode());

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

        // ---- 译文语音播报（仅耳机 / 任意设备） ----
        SwitchCompat swTts = findViewById(R.id.swTtsHeadsetOnly);
        swTts.setChecked(prefs.ttsHeadsetOnly());
        swTts.setOnCheckedChangeListener((b, checked) -> {
            prefs.setTtsHeadsetOnly(checked);
            // 该开关会影响 auto 档的音源选择（外放播报 → 切通话音源启用硬件 AEC），刷新提示
            updateAudioDiag(prefs.audioMode());
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

    // ---------------- 录音处理（回声/降噪/增益） ----------------

    private void updateAudioDiag(String mode) {
        String hint;
        switch (mode == null ? Prefs.AUDIO_AUTO : mode) {
            case Prefs.AUDIO_SYSTEM:
                hint = "强制通话音源：手机自带 AEC/降噪/增益最可能生效（骁龙机型），缺项软件自动补。";
                break;
            case Prefs.AUDIO_SOFTWARE:
                hint = "不挂系统效果，全部内置算法：90Hz 高通去低频、降噪门控、自动增益、软限幅。";
                break;
            case Prefs.AUDIO_OFF:
                hint = "不做任何处理，原始信号直接送识别。";
                break;
            case Prefs.AUDIO_AUTO:
            default:
                hint = "识别音源 + 系统 AEC/NS/AGC，缺项用软件（高通+门控+AGC）补；"
                        + "关掉「仅耳机播报」后会改走通话音源，让硬件回声消除生效。";
                break;
        }
        String boost = boostLabel(prefs.micBoostDb());
        // auto 档在「会外放播报」时自动切通话音源（硬件回声消除优先），这里把决定显式告诉用户
        boolean autoToCall = !prefs.ttsHeadsetOnly()
                && (mode == null || Prefs.AUDIO_AUTO.equals(mode));
        tvAudioDiag.setText("设备能力： " + SystemAudioEffects.capabilityLine()
                + "\n当前： " + hint
                + (autoToCall && !SystemAudioEffects.aecAvailable()
                        ? "\n回声消除：本机不支持，外放播报可能自激，建议插耳机" : "")
                + (autoToCall && SystemAudioEffects.aecAvailable()
                        ? "\n回声消除：已因外放播报切到通话音源（AEC 优先）" : "")
                + "\n收音增益： " + boost
                + "\n改动下次「开始翻译」生效；实际挂载见 logcat 标签 AudioCapture");
    }

    private static String boostLabel(int db) {
        if (db >= 12) return "+12dB 远场（预抬电平，让远场人声进入 VAD/AGC 触发区间）";
        if (db >= 6) return "+6dB 增强（1~2 米）";
        return "0dB 标准（贴近使用）";
    }

    private static int micBoostRadioId(int db) {
        if (db >= 12) return R.id.rbMicFar;
        if (db >= 6) return R.id.rbMicBoost;
        return R.id.rbMicNormal;
    }

    private static int micBoostDbOf(int radioId) {
        if (radioId == R.id.rbMicFar) return 12;
        if (radioId == R.id.rbMicBoost) return 6;
        return 0;
    }

    private static int modeRadioId(String mode) {
        if (Prefs.AUDIO_SYSTEM.equals(mode)) return R.id.rbAudioSystem;
        if (Prefs.AUDIO_SOFTWARE.equals(mode)) return R.id.rbAudioSoftware;
        if (Prefs.AUDIO_OFF.equals(mode)) return R.id.rbAudioOff;
        return R.id.rbAudioAuto;
    }

    private static String audioModeOf(int radioId) {
        if (radioId == R.id.rbAudioSystem) return Prefs.AUDIO_SYSTEM;
        if (radioId == R.id.rbAudioSoftware) return Prefs.AUDIO_SOFTWARE;
        if (radioId == R.id.rbAudioOff) return Prefs.AUDIO_OFF;
        return Prefs.AUDIO_AUTO;
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
                    : getString(R.string.model_unpack));
            action.setOnClickListener(v -> {
                // 服务运行中模型文件正被引擎引用（部分后端 mmap 文件），此时删除/覆盖
                // 可能让正在识别的 native 层直接崩溃，务必先停止翻译再操作。
                if (TranslateService.isRunning()) {
                    Toast.makeText(this, R.string.stop_service_first, Toast.LENGTH_LONG).show();
                    return;
                }
                if (models.isReady(m)) {
                    confirmDelete(m, action);
                } else {
                    unpackOne(m, action);
                }
            });

            row.addView(info);
            row.addView(action);
            modelList.addView(row);
        }
    }

    /** 删除几百 MB 模型前二次确认，防止误删后需要重新解包。 */
    private void confirmDelete(ModelsManifest.Model m, Button action) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.model_delete_title)
                .setMessage(getString(R.string.model_delete_confirm, m.label))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        models.delete(m);
                    } catch (Exception e) {
                        Log.e(TAG, "删除失败 " + m.id, e);
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    buildModelRows();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void unpackOne(ModelsManifest.Model m, Button action) {
        action.setEnabled(false);
        exec.execute(() -> {
            try {
                models.ensure(m, new ModelManager.Progress() {
                    @Override
                    public void onProgress(long done, long total, String stage) {
                        long pct = total <= 0 ? 0 : done * 100 / total;
                        runOnUiThread(() ->
                                action.setText(getString(R.string.model_unpacking, pct)));
                    }

                    @Override
                    public boolean isCancelled() {
                        return isFinishing() || isDestroyed();
                    }
                });
                runOnUiThread(this::buildModelRows);
            } catch (Exception e) {
                Log.e(TAG, "解包失败 " + m.id, e);
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
