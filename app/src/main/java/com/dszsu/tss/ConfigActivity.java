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

import java.util.Locale;

import io.github.libxposed.service.XposedService;

public class ConfigActivity extends AppCompatActivity implements App.ServiceListener {

    private String packageName;
    private XposedService service;
    private static final String GLOBAL_GROUP = "global";
    private EditText editCustomTitle;
    private Spinner spinnerTitleMode;
    private View layoutTitlePicker;
    private static final String KEY_GLOBAL_TITLE = "title";
    private boolean loading = false;
    private String prefsGroup;
    private SwitchCompat switchDisableSkipScreenshot, switchDimBehind, switchShowWallpaper,
            switchMagicFlags, switchNofocusOnly, switchHideRecentCard, switchWindowTitle;
    private TextView textGlobalHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        packageName = getIntent().getStringExtra("packageName");
        if (packageName == null) {
            finish();
            return;
        }
        prefsGroup = packageName.toLowerCase(Locale.ROOT);

        bindViews();
        setTopBarInfo();
        App.addListener(this);
    }

    private void bindViews() {
        switchDisableSkipScreenshot = findViewById(R.id.switch_disable_skip_screenshot);
        switchDimBehind = findViewById(R.id.switch_dim_behind);
        switchShowWallpaper = findViewById(R.id.switch_show_wallpaper);
        switchMagicFlags = findViewById(R.id.switch_magic_flags);
        switchNofocusOnly = findViewById(R.id.switch_nofocus_only);
        switchHideRecentCard = findViewById(R.id.switch_hide_recent_card);
        switchWindowTitle = findViewById(R.id.switch_window_title);
        editCustomTitle = findViewById(R.id.edit_custom_title);
        spinnerTitleMode = findViewById(R.id.spinner_title_mode);
        layoutTitlePicker = findViewById(R.id.layout_title_picker);
        textGlobalHint = findViewById(R.id.text_global_hint);

        String[] titleModes = {getString(R.string.title_mode_global), getString(R.string.title_mode_custom)};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, titleModes);
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
        SharedPreferences prefs = service.getRemotePreferences(prefsGroup);
        switchDisableSkipScreenshot.setChecked(prefs.contains("enable_skip_screenshot"));
        switchDimBehind.setChecked(prefs.contains("FLAG_DIM_BEHIND_0"));
        switchShowWallpaper.setChecked(prefs.contains("show_wallpaper"));
        switchMagicFlags.setChecked(prefs.contains("magic_flags"));
        switchNofocusOnly.setChecked(prefs.contains("nofocus_only"));
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
            if (!loading) savePref("enable_skip_screenshot", checked);
        });
        switchDimBehind.setOnCheckedChangeListener((v, checked) -> {
            if (!loading) savePref("FLAG_DIM_BEHIND_0", checked);
        });
        switchShowWallpaper.setOnCheckedChangeListener((v, checked) -> {
            if (!loading) savePref("show_wallpaper", checked);
        });
        switchMagicFlags.setOnCheckedChangeListener((v, checked) -> {
            if (!loading) savePref("magic_flags", checked);
        });
        switchNofocusOnly.setOnCheckedChangeListener((v, checked) -> {
            if (!loading) savePref("nofocus_only", checked);
        });
        switchHideRecentCard.setOnCheckedChangeListener((v, checked) -> {
            if (!loading) savePref("hide_recent_card", checked);
        });

        switchWindowTitle.setOnCheckedChangeListener((v, checked) -> {
            setTitlePickerEnabled(checked);
            if (loading) return;
            if (checked) {
                savePref("$global");
                spinnerTitleMode.setSelection(0, false);
                editCustomTitle.setVisibility(View.GONE);
                updateGlobalHint();
            } else {
                savePref(null);
            }
        });

        spinnerTitleMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (loading) return;
                if (position == 0) {
                    editCustomTitle.setVisibility(View.GONE);
                    savePref("$global");
                    updateGlobalHint();
                } else {
                    editCustomTitle.setVisibility(View.VISIBLE);
                    editCustomTitle.requestFocus();
                    textGlobalHint.setVisibility(View.GONE);
                    savePref(editCustomTitle.getText().toString().trim());
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
                    savePref(s.toString().trim());
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

    private void savePref(String stringValue) {
        if (service == null) return;
        SharedPreferences.Editor editor = service.getRemotePreferences(prefsGroup).edit();
        if (stringValue == null) {
            editor.remove("window_title");
        } else {
            editor.putString("window_title", stringValue);
        }
        editor.apply();
    }

    private void savePref(String key, boolean enable) {
        if (service == null) return;
        SharedPreferences.Editor editor = service.getRemotePreferences(prefsGroup).edit();
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
    protected void onDestroy() {
        super.onDestroy();
        App.removeListener(this);
    }
}