package com.dszsu.tss;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import java.util.List;

public class ConfigActivity extends AppCompatActivity {

    private String packageName;
    private JsonConfigManager configManager;
    private SwitchCompat switchWindowTitle;
    private android.widget.EditText editTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        // 设置状态栏白色 + 深色图标（与主界面统一）
        getWindow().setStatusBarColor(android.graphics.Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
                getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        packageName = getIntent().getStringExtra("packageName");
        if (packageName == null) {
            finish();
            return;
        }

        configManager = new JsonConfigManager(this);

        // 顶部信息栏
        setTopBarInfo();

        // 绑定控件
        SwitchCompat switchDisableSkipScreenshot = findViewById(R.id.switch_disable_skip_screenshot);
        SwitchCompat switchDimBehind = findViewById(R.id.switch_dim_behind);
        SwitchCompat switchNoFocus = findViewById(R.id.switch_no_focus);
        SwitchCompat switchMagicFlags = findViewById(R.id.switch_magic_flags);
        SwitchCompat switchHideRecentCard = findViewById(R.id.switch_hide_recent_card);
        switchWindowTitle = findViewById(R.id.switch_window_title);
        editTitle = findViewById(R.id.edit_window_title);

        // 初始化开关状态
        List<String> enabled = configManager.getEnabledFeatures(packageName);
        switchDisableSkipScreenshot.setChecked(enabled.contains("disable_skip_screenshot"));
        switchDimBehind.setChecked(enabled.contains("FLAG_DIM_BEHIND_0"));
        switchNoFocus.setChecked(enabled.contains("window_no_focus"));
        switchMagicFlags.setChecked(enabled.contains("magic_flags"));
        switchHideRecentCard.setChecked(enabled.contains("hide_recent_card"));

        String savedTitle = configManager.getWindowTitle(packageName);
        boolean hasTitle = !savedTitle.isEmpty();
        switchWindowTitle.setChecked(hasTitle);
        editTitle.setText(savedTitle);
        editTitle.setEnabled(hasTitle);

        // 开关监听
        switchDisableSkipScreenshot.setOnCheckedChangeListener((buttonView, isChecked) ->
                configManager.setFeatureEnabled(packageName, "disable_skip_screenshot", isChecked));
        switchDimBehind.setOnCheckedChangeListener((buttonView, isChecked) ->
                configManager.setFeatureEnabled(packageName, "FLAG_DIM_BEHIND_0", isChecked));
        switchNoFocus.setOnCheckedChangeListener((buttonView, isChecked) ->
                configManager.setFeatureEnabled(packageName, "window_no_focus", isChecked));
        switchMagicFlags.setOnCheckedChangeListener((buttonView, isChecked) ->
                configManager.setFeatureEnabled(packageName, "magic_flags", isChecked));
        switchHideRecentCard.setOnCheckedChangeListener((buttonView, isChecked) ->
                configManager.setFeatureEnabled(packageName, "hide_recent_card", isChecked));

        // 标题开关与输入框联动
        switchWindowTitle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            editTitle.setEnabled(isChecked);
            if (isChecked) {
                String currentText = editTitle.getText().toString().trim();
                if (!currentText.isEmpty()) {
                    configManager.setWindowTitle(packageName, currentText);
                } else {
                    configManager.setWindowTitle(packageName, ""); // 稍后输入
                }
            } else {
                editTitle.setText("");
                configManager.setWindowTitle(packageName, "");
            }
        });

        // 输入框失去焦点保存
        editTitle.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && switchWindowTitle.isChecked()) {
                String title = editTitle.getText().toString().trim();
                configManager.setWindowTitle(packageName, title);
            }
        });

        // 启用左上角返回箭头
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    private void setTopBarInfo() {
        PackageManager pm = getPackageManager();
        try {
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
            finish(); // 应用不存在时退出
        }
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
        // 离开时确保保存标题
        if (switchWindowTitle.isChecked()) {
            String title = editTitle.getText().toString().trim();
            configManager.setWindowTitle(packageName, title);
        }
    }
}