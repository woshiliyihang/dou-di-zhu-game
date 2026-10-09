package com.example.vtrans;

import android.os.Bundle;
import android.widget.RadioGroup;

import androidx.appcompat.app.AppCompatActivity;

import com.example.vtrans.util.Prefs;

public class SettingsActivity extends AppCompatActivity {

    private Prefs prefs;

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
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
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
}
