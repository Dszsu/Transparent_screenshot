package com.dszsu.tss;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import io.github.libxposed.service.XposedService;

public class SettingsActivity extends AppCompatActivity implements App.ServiceListener {

    private XposedService service;
    private Spinner spinnerBrand;
    private SwitchCompat switchSystemHide;
    private boolean loading = false;

    private static final String[] BRAND_VALUES = {
            "",
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
        switchSystemHide = findViewById(R.id.switch_system_hide_master);

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

        // 品牌选择
        SharedPreferences globalPrefs = service.getRemotePreferences("global");
        String savedTitle = globalPrefs.getString("title", "");
        int pos = 0;
        if (!savedTitle.isEmpty()) {
            for (int i = 1; i < BRAND_VALUES.length; i++) {
                if (BRAND_VALUES[i].equals(savedTitle)) {
                    pos = i;
                    break;
                }
            }
        }
        spinnerBrand.setSelection(pos, false);

        // 系统层隐藏开关：同时检查 packages 键和作用域
        SharedPreferences sysPrefs = service.getRemotePreferences("system_hide");
        boolean hasPackages = sysPrefs.contains("packages");
        boolean inScope = service.getScope().contains("system");
        switchSystemHide.setChecked(hasPackages && inScope);

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

        switchSystemHide.setOnCheckedChangeListener((v, checked) -> {
            if (loading) return;
            if (service == null) {
                switchSystemHide.setChecked(!checked);
                return;
            }
            if (checked) {
                // 开启操作
                boolean alreadyInScope = service.getScope().contains("system");
                if (alreadyInScope) {
                    // 作用域已有 system，直接写入 packages 并提示
                    SharedPreferences prefs = service.getRemotePreferences("system_hide");
                    if (!prefs.contains("packages")) {
                        prefs.edit().putStringSet("packages", new HashSet<>()).apply();
                    }
                    Toast.makeText(SettingsActivity.this,
                            R.string.system_hide_enabled, Toast.LENGTH_LONG).show();
                } else {
                    // 请求作用域
                    service.requestScope(Collections.singletonList("system"), new XposedService.OnScopeEventListener() {
                        @Override
                        public void onScopeRequestApproved(@NonNull List<String> approved) {
                            // 异步回调中只写入数据，不触发 UI 刷新（避免 Animator 线程异常）
                            SharedPreferences prefs = service.getRemotePreferences("system_hide");
                            if (!prefs.contains("packages")) {
                                prefs.edit().putStringSet("packages", new HashSet<>()).apply();
                            }
                            // 在主线程显示成功提示
                            runOnUiThread(() -> Toast.makeText(SettingsActivity.this,
                                    R.string.system_hide_enabled, Toast.LENGTH_LONG).show());
                        }

                        @Override
                        public void onScopeRequestFailed(@NonNull String message) {
                            runOnUiThread(() -> {
                                switchSystemHide.setChecked(false);
                                Toast.makeText(SettingsActivity.this,
                                        getString(R.string.scope_request_failed, message), Toast.LENGTH_LONG).show();
                            });
                        }
                    });
                }
            } else {
                // 关闭操作：直接在主线程执行，移除作用域并删除 packages
                service.removeScope(Collections.singletonList("system"));
                service.getRemotePreferences("system_hide").edit().remove("packages").apply();
                Toast.makeText(SettingsActivity.this, R.string.system_hide_disabled, Toast.LENGTH_LONG).show();
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