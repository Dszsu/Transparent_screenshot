package com.dszsu.tss;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.dszsu.tss.databinding.ActivityScopeBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;

public class ScopeManageActivity extends AppCompatActivity implements App.ServiceListener,
        AppListRepository.OnDataRefreshListener {

    private static final String VIRTUAL_SYSTEM = "system";

    private final Set<String> scopePackages = new HashSet<>();
    private final List<AppInfo> allFilteredApps = new ArrayList<>();

    private ActivityScopeBinding binding;
    private XposedService service;
    private ToggleAppListAdapter adapter;
    private String currentQuery = "";

    private boolean showSystemApps = false;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRetry = () -> ensureDataLoaded();
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = () -> applyFilter(currentQuery);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityScopeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        binding.rvApps.setLayoutManager(new LinearLayoutManager(this));

        setupSearchView();
        App.addListener(this);
    }

    private void setupSearchView() {
        binding.searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                currentQuery = (query != null) ? query : "";
                searchHandler.removeCallbacks(searchRunnable);
                applyFilter(currentQuery);
                hideKeyboard();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                currentQuery = (newText != null) ? newText : "";
                searchHandler.removeCallbacks(searchRunnable);
                searchHandler.postDelayed(searchRunnable, 200);
                return true;
            }
        });

        binding.searchView.setOnCloseListener(() -> {
            currentQuery = "";
            searchHandler.removeCallbacks(searchRunnable);
            binding.searchView.setQuery("", false);
            applyFilter("");
            hideKeyboard();
            return false;
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_system_hide, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem showSystemItem = menu.findItem(R.id.action_show_system);
        if (showSystemItem != null) {
            showSystemItem.setChecked(showSystemApps);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_show_system) {
            showSystemApps = !item.isChecked();
            item.setChecked(showSystemApps);
            rebuildAppList();
            return true;
        }
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onServiceChanged(XposedService svc) {
        service = svc;
        if (svc != null) {
            loadScopePackages();
            ensureDataLoaded();
        } else {
            scopePackages.clear();
            allFilteredApps.clear();
            refreshHandler.removeCallbacks(refreshRetry);
            if (adapter != null) adapter.submitList(allFilteredApps, scopePackages);
        }
    }

    private void ensureDataLoaded() {
        if (service == null || isFinishing()) return;
        if (!allFilteredApps.isEmpty()) return;
        AppListRepository repo = AppListRepository.getInstance();
        if (!repo.getAllApps().isEmpty()) {
            rebuildAppList();
            return;
        }
        repo.refreshData(service, getPackageManager(), this);
        refreshHandler.removeCallbacks(refreshRetry);
        refreshHandler.postDelayed(refreshRetry, 500);
    }

    @Override
    public void onRefreshComplete(List<AppInfo> filteredList) {
        if (service == null || isFinishing()) return;
        refreshHandler.removeCallbacks(refreshRetry);
        rebuildAppList();
    }

    @Override
    public void onLoadingStateChanged(boolean isLoading) {
    }

    private void hideKeyboard() {
        if (binding.searchView.getWindowToken() != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(binding.searchView.getWindowToken(), 0);
            }
        }
    }

    private static String normalizePackageName(String packageName) {
        return packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
    }

    private static String normalizeQuery(String query) {
        return query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
    }

    private void loadScopePackages() {
        scopePackages.clear();
        if (service == null) return;
        List<String> raw = service.getScope();
        for (String p : raw) {
            if (p != null && !p.isEmpty()) {
                scopePackages.add(normalizePackageName(p));
            }
        }
        scopePackages.remove(VIRTUAL_SYSTEM);
    }

    private void rebuildAppList() {
        List<AppInfo> allApps = AppListRepository.getInstance().getAllApps();
        allFilteredApps.clear();
        for (AppInfo app : allApps) {
            String lowerPkg = normalizePackageName(app.getPackageName());
            if (VIRTUAL_SYSTEM.equals(lowerPkg)) continue;

            if (!showSystemApps && app.isSystemApp()
                    && !scopePackages.contains(lowerPkg)) continue;
            allFilteredApps.add(app);
        }

        if (adapter == null) {
            adapter = new ToggleAppListAdapter(
                    this,
                    getPackageManager(),
                    getPackageManager().getDefaultActivityIcon(),
                    this::onToggle
            );
            binding.rvApps.setAdapter(adapter);
        }
        applyFilter(currentQuery);
    }

    private void applyFilter(String query) {
        if (adapter == null) return;
        String lowerQuery = normalizeQuery(query);

        List<AppInfo> enabled = new ArrayList<>();
        List<AppInfo> disabled = new ArrayList<>();
        for (AppInfo app : allFilteredApps) {
            if (!lowerQuery.isEmpty()
                    && !app.getNormalizedLabel().contains(lowerQuery)
                    && !app.getNormalizedPackageName().contains(lowerQuery)) {
                continue;
            }
            if (scopePackages.contains(app.getNormalizedPackageName())) {
                enabled.add(app);
            } else {
                disabled.add(app);
            }
        }

        enabled.sort((a, b) -> a.getLabel().compareToIgnoreCase(b.getLabel()));
        disabled.sort((a, b) -> a.getLabel().compareToIgnoreCase(b.getLabel()));

        List<AppInfo> finalList = new ArrayList<>(enabled.size() + disabled.size());
        finalList.addAll(enabled);
        finalList.addAll(disabled);
        adapter.submitList(finalList, scopePackages);
    }

    private void onToggle(String packageName, boolean enabled) {
        if (service == null) return;
        String lowerPkg = normalizePackageName(packageName);
        if (enabled) {
            if (scopePackages.contains(lowerPkg)) return;
            service.requestScope(Collections.singletonList(lowerPkg),
                    new XposedService.OnScopeEventListener() {
                        @Override
                        public void onScopeRequestApproved(@NonNull List<String> approved) {
                            runOnUiThread(() -> {
                                loadScopePackages();
                                applyFilter(currentQuery);
                                Toast.makeText(ScopeManageActivity.this,
                                        getString(R.string.scope_added, packageName),
                                        Toast.LENGTH_SHORT).show();
                            });
                        }

                        @Override
                        public void onScopeRequestFailed(@NonNull String message) {
                            runOnUiThread(() -> {
                                loadScopePackages();
                                applyFilter(currentQuery);
                                adapter.refreshItem(packageName);
                                Toast.makeText(ScopeManageActivity.this,
                                        getString(R.string.scope_request_failed, message),
                                        Toast.LENGTH_LONG).show();
                            });
                        }
                    });
        } else {
            if (!scopePackages.contains(lowerPkg)) return;

            triggerUninstallHotReload(service, lowerPkg);
            service.removeScope(Collections.singletonList(lowerPkg));
            scopePackages.remove(lowerPkg);
            applyFilter(currentQuery);
            Toast.makeText(this, getString(R.string.scope_removed, packageName),
                    Toast.LENGTH_SHORT).show();
        }
    }

    public static void triggerUninstallHotReload(XposedService service, String pkg) {
        if (service == null) return;
        try {

            for (HookedTarget t : service.getRunningTargets()) {
                String pn = t.getProcessName();
                if (pn == null) continue;

                if (pn.equals(pkg) || pn.startsWith(pkg + ":")) {
                    Bundle extras = new Bundle();
                    extras.putBoolean("uninstall_hook", true);
                    service.hotReloadModule(t, extras, (target, result) ->
                            android.util.Log.i("TSS", "uninstall hot reload: "
                                    + target.getProcessName() + " -> " + result.status()));
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("TSS", "triggerUninstallHotReload failed: " + t);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        refreshHandler.removeCallbacks(refreshRetry);
        searchHandler.removeCallbacks(searchRunnable);
        App.removeListener(this);
    }
}
