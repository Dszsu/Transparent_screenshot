package com.dszsu.tss;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.dszsu.tss.databinding.ActivityMainBinding;

import java.util.Collections;
import java.util.List;

import io.github.libxposed.service.XposedService;

public class MainActivity extends AppCompatActivity implements App.ServiceListener,
        AppListRepository.OnDataRefreshListener {

    private ActivityMainBinding binding;
    private AppAdapter adapter;
    private XposedService service;
    private String currentSearch = "";
    private boolean systemHideEnabled = false;

    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = () -> applyFilter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        adapter = new AppAdapter(this, getPackageManager(), getPackageManager().getDefaultActivityIcon(), app -> {
            if (app.isSystemCritical()) return;
            if ("system".equals(app.getPackageName())) {
                startActivity(new Intent(MainActivity.this, SystemHideActivity.class));
            } else {
                Intent intent = new Intent(MainActivity.this, ConfigActivity.class);
                intent.putExtra("packageName", app.getPackageName());
                startActivity(intent);
            }
        });

        binding.rvApps.setLayoutManager(new LinearLayoutManager(this));
        binding.rvApps.setAdapter(adapter);
        binding.rvApps.setVisibility(View.GONE);

        binding.nestedScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY,
                                       int oldScrollX, int oldScrollY) {
                if (scrollY - oldScrollY > 8) {
                    binding.fabAddScope.shrink();
                } else if (oldScrollY - scrollY > 8) {
                    binding.fabAddScope.extend();
                }
            }
        });

        binding.swipeRefresh.setOnRefreshListener(() -> {
            if (service != null) {
                AppListRepository.getInstance().refreshData(service, getPackageManager(), this);
            } else {
                binding.swipeRefresh.setRefreshing(false);
                Toast.makeText(this, R.string.framework_not_connected, Toast.LENGTH_SHORT).show();
            }
        });

        binding.fabAddScope.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, ScopeManageActivity.class)));

        binding.searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }
            @Override
            public boolean onQueryTextChange(String newText) {
                currentSearch = newText == null ? "" : newText;
                searchHandler.removeCallbacks(searchRunnable);
                searchHandler.postDelayed(searchRunnable, 200);
                return true;
            }
        });

        App.addListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (service != null) {
            loadSystemHideEnabled();
            AppListRepository.getInstance().refreshData(service, getPackageManager(), this);
        }
    }

    @Override
    public void onServiceChanged(XposedService svc) {
        service = svc;
        if (svc != null) {
            adapter.setService(svc);
            loadSystemHideEnabled();
            AppListRepository.getInstance().refreshData(svc, getPackageManager(), this);
        } else {
            systemHideEnabled = false;
            adapter.setService(null);
            adapter.setData(Collections.emptyList());
            binding.swipeRefresh.setRefreshing(false);
            binding.rvApps.setVisibility(View.GONE);
        }
    }

    @Override
    public void onRefreshComplete(List<AppInfo> filteredList) {
        for (AppInfo app : filteredList) {
            if ("system".equals(app.getPackageName())) {
                app.setSystemCritical(!systemHideEnabled);
                break;
            }
        }
        adapter.setData(filteredList);
        binding.swipeRefresh.setRefreshing(false);
        showAppList(filteredList);
    }

    @Override
    public void onLoadingStateChanged(boolean isLoading) {
        binding.swipeRefresh.setRefreshing(isLoading);
    }

    private void loadSystemHideEnabled() {
        if (service == null) {
            systemHideEnabled = false;
            return;
        }
        try {
            SharedPreferences sysPrefs = service.getRemotePreferences("system_hide");
            systemHideEnabled = sysPrefs.contains("packages");
        } catch (Throwable t) {
            systemHideEnabled = false;
        }
    }

    private void applyFilter() {
        List<AppInfo> filtered = AppListRepository.getInstance().filterApps(currentSearch);
        for (AppInfo app : filtered) {
            if ("system".equals(app.getPackageName())) {
                app.setSystemCritical(!systemHideEnabled);
                break;
            }
        }
        adapter.setData(filtered);
        showAppList(filtered);
    }

    private void showAppList(List<AppInfo> filtered) {
        if (filtered.isEmpty()) {
            binding.rvApps.setVisibility(View.GONE);
            return;
        }

        binding.rvApps.setVisibility(View.VISIBLE);
        binding.rvApps.post(() -> {
            if (binding.rvApps.getVisibility() == View.VISIBLE) {
                binding.rvApps.requestLayout();
            }
        });
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
        searchHandler.removeCallbacks(searchRunnable);
        App.removeListener(this);
    }
}
