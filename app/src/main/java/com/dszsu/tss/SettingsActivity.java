package com.dszsu.tss;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import io.github.libxposed.service.XposedService;

public class SettingsActivity extends AppCompatActivity implements App.ServiceListener {

    private XposedService service;
    private Spinner spinnerBrand;
    private boolean loading = false;

    // 品牌值（空字符串对应未选择）
    private static final String[] BRAND_VALUES = {
            "",   // 未选择
            "com.oplus.screenrecorder.FloatView",
            "com.miui.screenrecorder",
            "com.samsung.android.app.screenrecorder",
            "ScreenRecoderTimer",
            "screen_record_menu",
            "SysScreenRecorder"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        spinnerBrand = findViewById(R.id.spinner_brand);

        String[] brandLabels = getResources().getStringArray(R.array.brand_labels);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, brandLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerBrand.setAdapter(adapter);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        App.addListener(this);
    }

    @Override
    public void onServiceChanged(XposedService svc) {
        service = svc;
        if (svc != null) {
            loadConfig();
            setupListeners();
        }
    }

    private void loadConfig() {
        if (service == null) return;
        loading = true;
        SharedPreferences globalPrefs = service.getRemotePreferences("global");

        String savedTitle = globalPrefs.getString("title", "");
        int pos = 0; // 默认为“未选择”
        if (!savedTitle.isEmpty()) {
            for (int i = 1; i < BRAND_VALUES.length; i++) {
                if (BRAND_VALUES[i].equals(savedTitle)) {
                    pos = i;
                    break;
                }
            }
        }
        spinnerBrand.setSelection(pos, false);
        loading = false;
    }

    private void setupListeners() {
        spinnerBrand.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (loading) return;
                saveGlobalTitle(BRAND_VALUES[position]);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void saveGlobalTitle(String title) {
        if (service == null) return;
        SharedPreferences.Editor editor = service.getRemotePreferences("global").edit();
        if (title.isEmpty()) {
            editor.remove("title");
        } else {
            editor.putString("title", title);
        }
        editor.apply();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        App.removeListener(this);
    }
}