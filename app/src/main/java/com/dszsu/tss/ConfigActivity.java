package com.dszsu.tss;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import io.github.libxposed.service.XposedService;

public class ConfigActivity extends AppCompatActivity implements App.ServiceListener {

    private static final String GLOBAL_GROUP = "global";
    private static final String KEY_GLOBAL_TITLE = "title";
    private String packageName;
    private XposedService service;
    private SwitchCompat switchDisableSkipScreenshot, switchDimBehind,
            switchMagicFlags, switchHideRecentCard, switchWindowTitle;
    private EditText editCustomTitle;
    private Spinner spinnerTitleMode;
    private View layoutTitlePicker;
    private TextView textGlobalHint, textWebViewHint;
    private boolean loading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        packageName = getIntent().getStringExtra("packageName");
        if (packageName == null) {
            finish();
            return;
        }

        bindViews();
        setTopBarInfo();
        App.addListener(this);
    }

    private void bindViews() {
        switchDisableSkipScreenshot = findViewById(R.id.switch_disable_skip_screenshot);
        switchDimBehind = findViewById(R.id.switch_dim_behind);
        switchMagicFlags = findViewById(R.id.switch_magic_flags);
        switchHideRecentCard = findViewById(R.id.switch_hide_recent_card);
        switchWindowTitle = findViewById(R.id.switch_window_title);
        editCustomTitle = findViewById(R.id.edit_custom_title);
        spinnerTitleMode = findViewById(R.id.spinner_title_mode);
        layoutTitlePicker = findViewById(R.id.layout_title_picker);
        textGlobalHint = findViewById(R.id.text_global_hint);
        textWebViewHint = findViewById(R.id.text_webview_hint);

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

        if (textWebViewHint != null) {
            textWebViewHint.setVisibility(titleEnabled ? View.VISIBLE : View.GONE);
        }

        loading = false;
    }

    private void updateGlobalHint() {
        if (service == null) return;
        String globalTitle = service.getRemotePreferences(GLOBAL_GROUP).getString(KEY_GLOBAL_TITLE, "");
        if (globalTitle.isEmpty()) {
            textGlobalHint.setText(getString(R.string.global_title_not_set));
        } else {
            textGlobalHint.setText(getString(R.string.current_global_title, globalTitle));
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
        switchMagicFlags.setOnCheckedChangeListener((v, checked) -> {
            if (!loading) saveToggle("magic_flags", checked);
        });
        switchHideRecentCard.setOnCheckedChangeListener((v, checked) -> {
            if (!loading) saveToggle("hide_recent_card", checked);
        });

        switchWindowTitle.setOnCheckedChangeListener((v, checked) -> {
            setTitlePickerEnabled(checked);
            if (textWebViewHint != null) {
                textWebViewHint.setVisibility(checked ? View.VISIBLE : View.GONE);
            }
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
                    textGlobalHint.setVisibility(View.GONE);
                    saveWindowTitle(editCustomTitle.getText().toString().trim());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        editCustomTitle.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (loading) return;
                if (spinnerTitleMode.getSelectedItemPosition() == 1 && switchWindowTitle.isChecked()) {
                    saveWindowTitle(s.toString().trim());
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        App.removeListener(this);
    }
}