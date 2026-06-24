package com.dszsu.tss;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;
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
    private SwitchCompat switchSystemUIEnhancement;
    private TextView textSystemHideInfo;
    private TextView textSystemUIInfo;
    private TextView textSystemHideLabel;
    private TextView textSystemUILabel;
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
        switchSystemUIEnhancement = findViewById(R.id.switch_system_ui_enhancement);
        textSystemHideInfo = findViewById(R.id.text_system_hide_info);
        textSystemUIInfo = findViewById(R.id.text_system_ui_info);
        textSystemHideLabel = findViewById(R.id.text_system_hide_label);
        textSystemUILabel = findViewById(R.id.text_system_ui_label);

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

        SharedPreferences sysPrefs = service.getRemotePreferences("system_hide");
        boolean hasPackages = sysPrefs.contains("packages");
        boolean inSystemScope = service.getScope().contains("system");
        switchSystemHide.setChecked(hasPackages);

        textSystemHideInfo.setText(R.string.system_hide_warning);
        String hideLabel = getString(R.string.system_hide_master);
        if (hasPackages && !inSystemScope) {
            hideLabel += " " + getString(R.string.not_in_scope_suffix).trim();
        }
        textSystemHideLabel.setText(hideLabel);

        boolean sysUIEnabled = sysPrefs.contains("system_ui_enhancement_enabled");
        boolean sysUIInScope = service.getScope().contains("com.android.systemui");
        switchSystemUIEnhancement.setChecked(sysUIEnabled);

        textSystemUIInfo.setText(R.string.system_ui_enhancement_warning);
        String uiLabel = getString(R.string.system_ui_enhancement);
        if (sysUIEnabled && !sysUIInScope) {
            uiLabel += " " + getString(R.string.not_in_scope_suffix).trim();
        }
        textSystemUILabel.setText(uiLabel);

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
                SharedPreferences prefs = service.getRemotePreferences("system_hide");
                if (!prefs.contains("packages")) {
                    prefs.edit().putStringSet("packages", new HashSet<>()).apply();
                }
                boolean alreadyInScope = service.getScope().contains("system");
                if (!alreadyInScope) {
                    service.requestScope(Collections.singletonList("system"),
                            new XposedService.OnScopeEventListener() {
                                @Override
                                public void onScopeRequestApproved(@NonNull List<String> approved) {
                                    runOnUiThread(() -> {
                                        loadConfig();
                                        Toast.makeText(SettingsActivity.this,
                                                R.string.system_hide_enabled, Toast.LENGTH_LONG).show();
                                    });
                                }

                                @Override
                                public void onScopeRequestFailed(@NonNull String message) {
                                    runOnUiThread(() -> {
                                        loadConfig();
                                        Toast.makeText(SettingsActivity.this,
                                                getString(R.string.scope_request_failed, message), Toast.LENGTH_LONG).show();
                                    });
                                }
                            });
                } else {
                    Toast.makeText(SettingsActivity.this,
                            R.string.system_hide_enabled, Toast.LENGTH_LONG).show();
                }
            } else {
                service.removeScope(Collections.singletonList("system"));
                service.getRemotePreferences("system_hide").edit().remove("packages").apply();
                Toast.makeText(SettingsActivity.this, R.string.system_hide_disabled, Toast.LENGTH_LONG).show();
            }
        });

        switchSystemUIEnhancement.setOnCheckedChangeListener((v, checked) -> {
            if (loading) return;
            if (service == null) {
                switchSystemUIEnhancement.setChecked(!checked);
                return;
            }
            if (checked) {
                service.getRemotePreferences("system_hide").edit()
                        .putBoolean("system_ui_enhancement_enabled", true).apply();
                boolean alreadyInScope = service.getScope().contains("com.android.systemui");
                if (!alreadyInScope) {
                    service.requestScope(Collections.singletonList("com.android.systemui"),
                            new XposedService.OnScopeEventListener() {
                                @Override
                                public void onScopeRequestApproved(@NonNull List<String> approved) {
                                    runOnUiThread(() -> {
                                        loadConfig();
                                        Toast.makeText(SettingsActivity.this,
                                                R.string.system_ui_enhancement_enabled, Toast.LENGTH_LONG).show();
                                    });
                                }

                                @Override
                                public void onScopeRequestFailed(@NonNull String message) {
                                    runOnUiThread(() -> {
                                        loadConfig();
                                        Toast.makeText(SettingsActivity.this,
                                                getString(R.string.scope_request_failed, message), Toast.LENGTH_LONG).show();
                                    });
                                }
                            });
                } else {
                    Toast.makeText(SettingsActivity.this,
                            R.string.system_ui_enhancement_enabled, Toast.LENGTH_LONG).show();
                }
            } else {
                service.removeScope(Collections.singletonList("com.android.systemui"));
                service.getRemotePreferences("system_hide").edit()
                        .remove("system_ui_enhancement_enabled").apply();
                loadConfig();
                Toast.makeText(SettingsActivity.this,
                        R.string.system_ui_enhancement_disabled, Toast.LENGTH_LONG).show();
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