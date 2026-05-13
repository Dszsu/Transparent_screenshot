package com.dszsu.tss;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.dszsu.tss.databinding.ActivityMainBinding;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.service.XposedService;

public class MainActivity extends AppCompatActivity implements App.ServiceListener,
        AppListRepository.OnDataRefreshListener {

    private ActivityMainBinding binding;
    private AppAdapter adapter;
    private XposedService service;
    private String currentSearch = "";

    private static final Set<String> SYSTEM_CRITICAL_PACKAGES = new HashSet<>(Arrays.asList(
            "android", "system", "com.android.systemui", "oplus", "com.oplus", "com.coloros", "com.oppo", "com.oneplus"
    ));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        adapter = new AppAdapter(getPackageManager(), getPackageManager().getDefaultActivityIcon(), app -> {
            if (app.isSystemCritical()) return;
            Intent intent = new Intent(MainActivity.this, ConfigActivity.class);
            intent.putExtra("packageName", app.getPackageName());
            startActivity(intent);
        });

        binding.rvApps.setLayoutManager(new LinearLayoutManager(this));
        binding.rvApps.setAdapter(adapter);

        binding.swipeRefresh.setOnRefreshListener(() -> {
            if (service != null) {
                AppListRepository.getInstance().refreshData(service, getPackageManager(), this);
            } else {
                binding.swipeRefresh.setRefreshing(false);
                Toast.makeText(this, "框架未连接，无法刷新", Toast.LENGTH_SHORT).show();
            }
        });

        binding.searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) { return false; }
            @Override
            public boolean onQueryTextChange(String newText) {
                currentSearch = newText;
                applyFilter();
                return true;
            }
        });

        App.addListener(this);
    }

    @Override
    public void onServiceChanged(XposedService svc) {
        service = svc;
        if (svc != null) {
            AppListRepository.getInstance().refreshData(svc, getPackageManager(), this);
            checkScopeWarning(svc.getScope());
            detectAndSaveWebViewPackage(svc);
        }
    }

    /**
     * 检测系统当前 WebView 包名，保存到 global 配置（不请求作用域）
     */
    private void detectAndSaveWebViewPackage(XposedService svc) {
        String webviewPkg = null;
        try {
            PackageInfo pi = WebView.getCurrentWebViewPackage();
            if (pi != null && pi.packageName != null) {
                webviewPkg = pi.packageName;
            }
        } catch (Throwable ignored) {}
        if (webviewPkg == null) {
            try {
                Class<?> factoryClass = Class.forName("android.webkit.WebViewFactory");
                Method method = factoryClass.getMethod("getLoadedPackageInfo");
                PackageInfo pi = (PackageInfo) method.invoke(null);
                if (pi != null && pi.packageName != null) {
                    webviewPkg = pi.packageName;
                }
            } catch (Throwable ignored) {}
        }
        if (webviewPkg == null) {
            try {
                Class<?> spClass = Class.forName("android.os.SystemProperties");
                Method get = spClass.getMethod("get", String.class, String.class);
                String pkg = (String) get.invoke(null, "persist.sys.webview.packagename", "");
                if (!pkg.isEmpty()) {
                    webviewPkg = pkg;
                }
            } catch (Throwable ignored) {}
        }

        if (webviewPkg != null) {
            svc.getRemotePreferences("global").edit().putString("webview_package", webviewPkg).apply();
            Log.i("TransScreenshot", "WebView package saved: " + webviewPkg);
        } else {
            Log.w("TransScreenshot", "Unable to detect WebView package");
        }
    }

    // --- 以下方法保持不变 ---
    @Override
    public void onRefreshComplete(List<AppInfo> filteredList) {
        adapter.submitList(filteredList);
        binding.swipeRefresh.setRefreshing(false);
        showEmptyHint(filteredList.isEmpty());
        if (service != null) checkScopeWarning(service.getScope());
    }

    @Override
    public void onLoadingStateChanged(boolean isLoading) {
        binding.swipeRefresh.setRefreshing(isLoading);
    }

    private void applyFilter() {
        List<AppInfo> filtered = AppListRepository.getInstance().filterApps(currentSearch);
        adapter.submitList(filtered);
        showEmptyHint(filtered.isEmpty());
    }

    private void showEmptyHint(boolean empty) {
        binding.emptyHint.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void checkScopeWarning(List<String> scopeList) {
        if (scopeList == null) {
            binding.warningHint.setVisibility(View.GONE);
            return;
        }
        boolean dangerous = false;
        for (String scopePkg : scopeList) {
            String lower = scopePkg.toLowerCase();
            for (String critical : SYSTEM_CRITICAL_PACKAGES) {
                if (lower.equals(critical) || lower.startsWith(critical + ".")) {
                    dangerous = true;
                    break;
                }
            }
            if (dangerous) break;
        }
        binding.warningHint.setVisibility(dangerous ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (item.getItemId() == R.id.action_open_homepage) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Dszsu/Transparent_screenshot/")));
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