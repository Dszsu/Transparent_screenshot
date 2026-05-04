package com.dszsu.tss;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.dszsu.tss.databinding.ActivityMainBinding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private AppAdapter adapter;
    private final List<AppInfo> allApps = new ArrayList<>();
    // 默认隐藏系统应用（只显示第三方应用）
    private boolean showSystem = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 状态栏白色 + 深色图标
        getWindow().setStatusBarColor(android.graphics.Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
                getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        setSupportActionBar(binding.toolbar);

        binding.rvApps.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppAdapter(allApps, app -> {
            Intent intent = new Intent(MainActivity.this, ConfigActivity.class);
            intent.putExtra("packageName", app.getPackageName());
            startActivity(intent);
        });
        binding.rvApps.setAdapter(adapter);

        loadApps();
        setupSearchView();
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        allApps.clear();
        for (ApplicationInfo app : apps) {
            boolean isSystem = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            // showSystem=true 时显示所有，false 时只显示非系统应用
            if (!showSystem && isSystem) continue;

            allApps.add(new AppInfo(
                    app.loadLabel(pm).toString(),
                    app.packageName,
                    app.loadIcon(pm)
            ));
        }
        allApps.sort(Comparator.comparing(a -> a.getLabel().toLowerCase()));
        adapter.updateList(allApps);
    }

    private void setupSearchView() {
        binding.searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) { return false; }

            @Override
            public boolean onQueryTextChange(String newText) {
                filterApps(newText != null ? newText : "");
                return true;
            }
        });
    }

    private void filterApps(String query) {
        List<AppInfo> filtered;
        if (query.trim().isEmpty()) {
            filtered = new ArrayList<>(allApps);
        } else {
            String lowerQuery = query.toLowerCase();
            filtered = new ArrayList<>();
            for (AppInfo app : allApps) {
                if (app.getLabel().toLowerCase().contains(lowerQuery) ||
                        app.getPackageName().toLowerCase().contains(lowerQuery)) {
                    filtered.add(app);
                }
            }
        }
        adapter.updateList(filtered);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_toggle_system) {
            showSystem = !showSystem;
            // 动态修改菜单文字
            item.setTitle(showSystem ? "隐藏系统应用" : "显示系统应用");
            loadApps();                     // 重新加载（按新过滤条件）
            filterApps(binding.searchView.getQuery().toString()); // 保持搜索过滤
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}