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
import androidx.appcompat.widget.SwitchCompat;

import io.github.libxposed.service.XposedService;

public class SettingsActivity extends AppCompatActivity implements App.ServiceListener {

    private static final String[] BRAND_LABELS = {
            "OPPO/一加/真我", "小米/红米", "三星", "华为EMUI", "Vivo", "魅族"
    };
    private static final String[] BRAND_VALUES = {
            "com.oplus.screenrecorder.FloatView", "com.miui.screenrecorder",
            "com.samsung.android.app.screenrecorder", "ScreenRecoderTimer",
            "screen_record_menu", "SysScreenRecorder"
    };
    private XposedService service;
    private Spinner spinnerBrand;
    private SwitchCompat switchWebviewShowWallpaper;
    private boolean loading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        spinnerBrand = findViewById(R.id.spinner_brand);
        switchWebviewShowWallpaper = findViewById(R.id.switch_webview_show_wallpaper);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, BRAND_LABELS);
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
        int pos = 0;
        for (int i = 0; i < BRAND_VALUES.length; i++) {
            if (BRAND_VALUES[i].equals(savedTitle)) {
                pos = i;
                break;
            }
        }
        spinnerBrand.setSelection(pos, false);

        // WebView 壁纸开关
        switchWebviewShowWallpaper.setChecked(globalPrefs.getBoolean("webview_show_wallpaper", false));
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

        switchWebviewShowWallpaper.setOnCheckedChangeListener((v, isChecked) -> {
            if (loading) return;
            if (service != null) {
                service.getRemotePreferences("global")
                        .edit()
                        .putBoolean("webview_show_wallpaper", isChecked)
                        .apply();
            }
        });
    }

    private void saveGlobalTitle(String title) {
        if (service == null) return;
        service.getRemotePreferences("global").edit().putString("title", title).apply();
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