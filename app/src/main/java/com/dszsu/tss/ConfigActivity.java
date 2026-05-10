package com.dszsu.tss;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import io.github.libxposed.service.XposedService;

public class ConfigActivity extends AppCompatActivity implements App.ServiceListener {

    private String packageName;
    private XposedService service;
    private SwitchCompat switchDisableSkipScreenshot, switchDimBehind, switchNoFocus,
            switchMagicFlags, switchHideRecentCard, switchWindowTitle;
    private EditText editCustomTitle;
    private Spinner spinnerTitleMode;
    private View layoutTitlePicker;
    private TextView textGlobalHint;
    private boolean loading = false;

    private static final String GLOBAL_GROUP = "global";
    private static final String KEY_GLOBAL_TITLE = "title";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        packageName = getIntent().getStringExtra("packageName");
        if (packageName == null) { finish(); return; }

        bindViews();
        setTopBarInfo();
        App.addListener(this);
    }

    private void bindViews() {
        switchDisableSkipScreenshot = findViewById(R.id.switch_disable_skip_screenshot);
        switchDimBehind = findViewById(R.id.switch_dim_behind);
        switchNoFocus = findViewById(R.id.switch_no_focus);
        switchMagicFlags = findViewById(R.id.switch_magic_flags);
        switchHideRecentCard = findViewById(R.id.switch_hide_recent_card);
        switchWindowTitle = findViewById(R.id.switch_window_title);
        editCustomTitle = findViewById(R.id.edit_custom_title);
        spinnerTitleMode = findViewById(R.id.spinner_title_mode);
        layoutTitlePicker = findViewById(R.id.layout_title_picker);
        textGlobalHint = findViewById(R.id.text_global_hint);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"全局", "自定义"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTitleMode.setAdapter(adapter);
    }

    private void setTopBarInfo() {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            Drawable icon = appInfo.loadIcon(pm);
            String label = appInfo.loadLabel(pm).toString();
            ImageView ivIcon = findViewById(R.id.iv_app_icon);
            TextView tvLabel = findViewById(R.id.tv_app_label);
            TextView tvPackage = findViewById(R.id.tv_app_package);
            ivIcon.setImageDrawable(icon);
            tvLabel.setText(label);
            tvPackage.setText(packageName);
        } catch (PackageManager.NameNotFoundException e) {
            finish();
        }
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
        SharedPreferences prefs = service.getRemotePreferences(packageName.toLowerCase());
        switchDisableSkipScreenshot.setChecked(prefs.contains("enable_skip_screenshot"));
        switchDimBehind.setChecked(prefs.contains("FLAG_DIM_BEHIND_0"));
        switchNoFocus.setChecked(prefs.contains("window_no_focus"));
        switchMagicFlags.setChecked(prefs.contains("magic_flags"));
        switchHideRecentCard.setChecked(prefs.contains("hide_recent_card"));

        boolean titleEnabled = prefs.contains("window_title");
        String savedTitle = prefs.getString("window_title", "");
        switchWindowTitle.setChecked(titleEnabled);
        setTitlePickerEnabled(titleEnabled);

        if (titleEnabled) {
            if (savedTitle.equals("$global")) {
                spinnerTitleMode.setSelection(0, false);
                editCustomTitle.setVisibility(View.GONE);
                updateGlobalHint();
            } else {
                spinnerTitleMode.setSelection(1, false);
                editCustomTitle.setVisibility(View.VISIBLE);
                editCustomTitle.setText(savedTitle);
                textGlobalHint.setVisibility(View.GONE);
            }
        } else {
            spinnerTitleMode.setSelection(0, false);
            editCustomTitle.setVisibility(View.GONE);
            textGlobalHint.setVisibility(View.GONE);
        }

        loading = false;
    }

    private void updateGlobalHint() {
        if (service == null) return;
        String globalTitle = service.getRemotePreferences(GLOBAL_GROUP).getString(KEY_GLOBAL_TITLE, "");
        if (globalTitle.isEmpty()) {
            textGlobalHint.setText("未设置全局标题，请前往“设置”界面设置品牌");
        } else {
            textGlobalHint.setText("当前全局标题：" + globalTitle);
        }
        textGlobalHint.setVisibility(View.VISIBLE);
    }

    private void setTitlePickerEnabled(boolean enabled) {
        layoutTitlePicker.setEnabled(enabled);
        layoutTitlePicker.setAlpha(enabled ? 1.0f : 0.5f);
        spinnerTitleMode.setEnabled(enabled);
        editCustomTitle.setEnabled(enabled);
        if (!enabled) {
            editCustomTitle.setVisibility(View.GONE);
            textGlobalHint.setVisibility(View.GONE);
        }
    }

    private void setupListeners() {
        switchDisableSkipScreenshot.setOnCheckedChangeListener((v, checked) -> {
            if (!loading) saveToggle("enable_skip_screenshot", checked);
        });
        switchDimBehind.setOnCheckedChangeListener((v, checked) -> {
            if (!loading) saveToggle("FLAG_DIM_BEHIND_0", checked);
        });
        switchNoFocus.setOnCheckedChangeListener((v, checked) -> {
            if (!loading) saveToggle("window_no_focus", checked);
        });
        switchMagicFlags.setOnCheckedChangeListener((v, checked) -> {
            if (!loading) saveToggle("magic_flags", checked);
        });
        switchHideRecentCard.setOnCheckedChangeListener((v, checked) -> {
            if (!loading) saveToggle("hide_recent_card", checked);
        });

        switchWindowTitle.setOnCheckedChangeListener((v, checked) -> {
            setTitlePickerEnabled(checked);
            if (loading) return;
            if (checked) {
                saveWindowTitle("$global");
                spinnerTitleMode.setSelection(0, false);
                editCustomTitle.setVisibility(View.GONE);
                updateGlobalHint();
            } else {
                saveWindowTitle(null);
            }
        });

        spinnerTitleMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (loading) return;
                if (position == 0) {
                    editCustomTitle.setVisibility(View.GONE);
                    saveWindowTitle("$global");
                    updateGlobalHint();
                } else {
                    editCustomTitle.setVisibility(View.VISIBLE);
                    editCustomTitle.requestFocus();
                    String custom = editCustomTitle.getText().toString().trim();
                    if (custom.isEmpty()) {
                        spinnerTitleMode.setSelection(0, false);
                        Toast.makeText(ConfigActivity.this, "自定义标题不能为空，已切回全局", Toast.LENGTH_SHORT).show();
                        saveWindowTitle("$global");
                        updateGlobalHint();
                    } else {
                        saveWindowTitle(custom);
                        textGlobalHint.setVisibility(View.GONE);
                    }
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        editCustomTitle.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && spinnerTitleMode.getSelectedItemPosition() == 1 && switchWindowTitle.isChecked()) {
                String custom = editCustomTitle.getText().toString().trim();
                if (custom.isEmpty()) {
                    spinnerTitleMode.setSelection(0, false);
                    saveWindowTitle("$global");
                    updateGlobalHint();
                } else {
                    saveWindowTitle(custom);
                }
            }
        });

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    private void saveWindowTitle(String value) {
        if (service == null) return;
        SharedPreferences.Editor editor = service.getRemotePreferences(packageName.toLowerCase()).edit();
        if (value == null) {
            editor.remove("window_title");
        } else {
            editor.putString("window_title", value);
        }
        editor.apply();
    }

    private void saveToggle(String key, boolean enable) {
        if (service == null) return;
        SharedPreferences.Editor editor = service.getRemotePreferences(packageName.toLowerCase()).edit();
        if (enable) {
            editor.putBoolean(key, true);
        } else {
            editor.remove(key);
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
    protected void onPause() {
        super.onPause();
        if (service != null && switchWindowTitle.isChecked()
                && spinnerTitleMode.getSelectedItemPosition() == 1) {
            String custom = editCustomTitle.getText().toString().trim();
            if (!custom.isEmpty()) {
                saveWindowTitle(custom);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        App.removeListener(this);
    }
}