package com.example.vtrans;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.vtrans.audio.AudioCapture;
import com.example.vtrans.util.Prefs;

public class SettingsActivity extends AppCompatActivity {

    private Prefs prefs;
    private TextView tvLiveNoise;
    private final Handler noiseHandler = new Handler(Looper.getMainLooper());
    private static final long NOISE_POLL_MS = 1500L;

    private final Runnable noisePoller = new Runnable() {
        @Override
        public void run() {
            renderNoise();
            noiseHandler.postDelayed(this, NOISE_POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        prefs = new Prefs(this);
        RadioGroup rgMicGain = findViewById(R.id.rgMicGain);
        rgMicGain.check(micBoostRadioId(prefs.micBoostDb()));
        rgMicGain.setOnCheckedChangeListener((group, checkedId) ->
                prefs.setMicBoostDb(micBoostDbOf(checkedId)));

        RadioGroup rgAgc = findViewById(R.id.rgAgcPolicy);
        rgAgc.check(agcRadioId(prefs.agcPolicy()));
        rgAgc.setOnCheckedChangeListener((group, checkedId) ->
                prefs.setAgcPolicy(agcPolicyOf(checkedId)));

        tvLiveNoise = findViewById(R.id.tvLiveNoise);
    }

    @Override
    protected void onResume() {
        super.onResume();
        noiseHandler.post(noisePoller);
    }

    @Override
    protected void onPause() {
        super.onPause();
        noiseHandler.removeCallbacks(noisePoller);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /**
     * 读 AudioCapture 里 [gain-chain] 周期日志刷新出来的静态快照；
     * NaN 表示服务没运行或还没到第一个 5s 窗口。
     */
    private void renderNoise() {
        double nf = AudioCapture.lastNoiseFloorDb;
        double snr = AudioCapture.lastSnrDb;
        double agc = AudioCapture.lastAgcGainDb;
        if (Double.isNaN(nf)) {
            tvLiveNoise.setText(R.string.live_noise_idle);
            return;
        }
        tvLiveNoise.setText(String.format(
                "噪声底 %.1f dBFS\n信噪比 %.1f dB\nAGC 当前 %+.1f dB",
                nf, Double.isNaN(snr) ? 0.0 : snr,
                Double.isNaN(agc) ? 0.0 : agc));
    }

    private static int micBoostRadioId(int db) {
        if (db >= 18) return R.id.rbMicVeryFar;
        if (db >= 12) return R.id.rbMicFar;
        if (db >= 6) return R.id.rbMicBoost;
        return R.id.rbMicNormal;
    }

    private static int micBoostDbOf(int radioId) {
        if (radioId == R.id.rbMicVeryFar) return 18;
        if (radioId == R.id.rbMicFar) return 12;
        if (radioId == R.id.rbMicBoost) return 6;
        return 0;
    }

    private static int agcRadioId(String policy) {
        if (Prefs.AGC_FORCE_ON.equals(policy)) return R.id.rbAgcForceOn;
        if (Prefs.AGC_FORCE_OFF.equals(policy)) return R.id.rbAgcForceOff;
        return R.id.rbAgcAuto;
    }

    private static String agcPolicyOf(int radioId) {
        if (radioId == R.id.rbAgcForceOn) return Prefs.AGC_FORCE_ON;
        if (radioId == R.id.rbAgcForceOff) return Prefs.AGC_FORCE_OFF;
        return Prefs.AGC_AUTO;
    }
}
