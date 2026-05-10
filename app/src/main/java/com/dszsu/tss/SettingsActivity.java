package com.dszsu.tss;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import io.github.libxposed.service.XposedService;

public class SettingsActivity extends AppCompatActivity implements App.ServiceListener {

    private XposedService service;
    private Spinner spinnerBrand;
    private EditText editCustom;
    private boolean loading = false;

    private static final String[] BRAND_LABELS = {
            "OPPO/一加/真我", "小米/红米", "三星", "华为EMUI", "Vivo", "魅族", "自定义"
    };
    private static final String[] BRAND_VALUES = {
            "com.oplus.screenrecorder.FloatView", "com.miui.screenrecorder",
            "com.samsung.android.app.screenrecorder", "ScreenRecoderTimer",
            "screen_record_menu", "SysScreenRecorder", ""
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // 状态栏白色
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(android.graphics.Color.WHITE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getWindow().getDecorView().setSystemUiVisibility(
                        getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        }

        spinnerBrand = findViewById(R.id.spinner_brand);
        editCustom = findViewById(R.id.edit_custom);

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
        int pos = -1;
        for (int i = 0; i < BRAND_VALUES.length - 1; i++) {
            if (BRAND_VALUES[i].equals(savedTitle)) {
                pos = i;
                break;
            }
        }
        if (pos >= 0) {
            spinnerBrand.setSelection(pos, false);
            editCustom.setVisibility(View.GONE);
        } else {
            spinnerBrand.setSelection(BRAND_VALUES.length - 1, false);
            editCustom.setVisibility(View.VISIBLE);
            editCustom.setText(savedTitle);
        }
        loading = false;
    }

    private void setupListeners() {
        spinnerBrand.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (loading) return;
                if (position == BRAND_VALUES.length - 1) {
                    editCustom.setVisibility(View.VISIBLE);
                    editCustom.requestFocus();
                } else {
                    editCustom.setVisibility(View.GONE);
                    saveGlobalTitle(BRAND_VALUES[position]);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        editCustom.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && spinnerBrand.getSelectedItemPosition() == BRAND_VALUES.length - 1) {
                saveGlobalTitle(editCustom.getText().toString().trim());
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