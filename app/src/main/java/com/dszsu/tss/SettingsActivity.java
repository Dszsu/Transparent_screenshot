package com.dszsu.tss;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.AutoCompleteTextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import io.github.libxposed.service.XposedService;

public class SettingsActivity extends AppCompatActivity implements App.ServiceListener {

    private XposedService service;
    private AutoCompleteTextView spinnerBrand;
    private int brandIndex = 0;
    private String[] brandLabels;
    private MaterialSwitch switchSystemHide;
    private MaterialSwitch switchSystemUIEnhancement;
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
                R.layout.item_dropdown, brandLabels);
        spinnerBrand.setAdapter(adapter);
        this.brandLabels = brandLabels;

        applySegmentedBackground();

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
        spinnerBrand.setText(brandLabels[pos], false);
        brandIndex = pos;

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
        spinnerBrand.setOnItemClickListener((parent, view, position, id) -> {
            if (loading) return;
            brandIndex = position;
            saveGlobalTitle(BRAND_VALUES[position]);
        });

        switchSystemHide.setOnCheckedChangeListener((v, checked) -> {
            if (loading) return;
            if (service == null) {
                switchSystemHide.setChecked(!checked);
                return;
            }
            if (checked) {
                boolean alreadyInScope = service.getScope().contains("system");
                if (!alreadyInScope) {
                    service.requestScope(Collections.singletonList("system"),
                            new XposedService.OnScopeEventListener() {
                                @Override
                                public void onScopeRequestApproved(@NonNull List<String> approved) {
                                    runOnUiThread(() -> {
                                        SharedPreferences prefs = service.getRemotePreferences("system_hide");
                                        if (!prefs.contains("packages")) {
                                            prefs.edit().putStringSet("packages", new HashSet<>()).apply();
                                        }
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
                    SharedPreferences prefs = service.getRemotePreferences("system_hide");
                    if (!prefs.contains("packages")) {
                        prefs.edit().putStringSet("packages", new HashSet<>()).apply();
                    }
                    Toast.makeText(SettingsActivity.this,
                            R.string.system_hide_enabled, Toast.LENGTH_LONG).show();
                }
            } else {

                ScopeManageActivity.triggerUninstallHotReload(service, "system");
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
                boolean alreadyInScope = service.getScope().contains("com.android.systemui");
                if (!alreadyInScope) {
                    service.requestScope(Collections.singletonList("com.android.systemui"),
                            new XposedService.OnScopeEventListener() {
                                @Override
                                public void onScopeRequestApproved(@NonNull List<String> approved) {
                                    runOnUiThread(() -> {
                                        service.getRemotePreferences("system_hide").edit()
                                                .putBoolean("system_ui_enhancement_enabled", true).apply();
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
                    service.getRemotePreferences("system_hide").edit()
                            .putBoolean("system_ui_enhancement_enabled", true).apply();
                    Toast.makeText(SettingsActivity.this,
                            R.string.system_ui_enhancement_enabled, Toast.LENGTH_LONG).show();
                }
            } else {

                ScopeManageActivity.triggerUninstallHotReload(service, "com.android.systemui");
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

    private void applySegmentedBackground() {
        android.view.ViewGroup group = findViewById(R.id.settings_group);
        if (group == null) return;
        int n = group.getChildCount();
        float density = getResources().getDisplayMetrics().density;
        float r16 = 16f * density, r4 = 4f * density;
        for (int i = 0; i < n; i++) {
            android.view.View v = group.getChildAt(i);
            float tl, tr, br, bl;
            if (i == 0) {
                tl = r16; tr = r16; br = r4; bl = r4;
            } else if (i == n - 1) {
                tl = r4; tr = r4; br = r16; bl = r16;
            } else {
                tl = r4; tr = r4; br = r4; bl = r4;
            }
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(ThemeUtils.cardColor(this));
            g.setCornerRadii(new float[]{tl, tl, tr, tr, br, br, bl, bl});
            v.setBackground(g);
        }
    }
}
