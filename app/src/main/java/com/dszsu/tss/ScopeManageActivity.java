package com.dszsu.tss;

import android.content.pm.PackageManager;
import android.annotation.SuppressLint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dszsu.tss.databinding.ActivityScopeBinding;
import com.dszsu.tss.databinding.ItemSystemHideBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import io.github.libxposed.service.XposedService;

public class ScopeManageActivity extends AppCompatActivity implements App.ServiceListener,
        AppListRepository.OnDataRefreshListener {

    private static final String VIRTUAL_SYSTEM = "system";

    private final Set<String> scopePackages = new HashSet<>();
    private final List<AppInfo> allFilteredApps = new ArrayList<>();

    private ActivityScopeBinding binding;
    private XposedService service;
    private ScopeAdapter adapter;
    private String currentQuery = "";

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRetry = () -> ensureDataLoaded();

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
        binding.rvApps.setItemAnimator(null);

        setupSearchView();
        App.addListener(this);
    }

    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = () -> applyFilter(currentQuery);

    private void setupSearchView() {
        binding.searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
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
            binding.searchView.setQuery("", false);
            applyFilter("");
            hideKeyboard();
            return false;
        });
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

    /**
     * 应用列表由主界面异步加载。若进入本界面时快照尚未就绪
     * （或刷新正被其他监听者占用），主动触发一次刷新并监听完成回调；
     * 若刷新被 isLoading 跳过，则延迟重试直到数据就绪。
     */
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
            allFilteredApps.add(app);
        }

        if (adapter == null) {
            adapter = new ScopeAdapter(
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
                    && !app.getLabel().toLowerCase(Locale.ROOT).contains(lowerQuery)
                    && !normalizePackageName(app.getPackageName()).contains(lowerQuery)) {
                continue;
            }
            if (scopePackages.contains(normalizePackageName(app.getPackageName()))) {
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
            service.removeScope(Collections.singletonList(lowerPkg));
            scopePackages.remove(lowerPkg);
            applyFilter(currentQuery);
            Toast.makeText(this, getString(R.string.scope_removed, packageName),
                    Toast.LENGTH_SHORT).show();
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
    protected void onDestroy() {
        super.onDestroy();
        refreshHandler.removeCallbacks(refreshRetry);
        App.removeListener(this);
    }

    private static class ScopeAdapter extends RecyclerView.Adapter<ScopeAdapter.VH> {

        private final PackageManager packageManager;
        private final Drawable defaultIcon;
        private final OnToggleListener listener;
        private final LruCache<String, Drawable> iconCache = new LruCache<>(50);
        private final Executor executor = Executors.newSingleThreadExecutor();
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final List<AppInfo> currentList = new ArrayList<>();
        private Set<String> selected = new HashSet<>();

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            AppInfo app = currentList.get(position);
            holder.b.tvLabel.setText(app.getLabel());
            holder.b.tvPackage.setText(app.getPackageName());

            holder.b.switchEnabled.setOnCheckedChangeListener(null);
            holder.b.switchEnabled.setChecked(selected.contains(normalizePackageName(app.getPackageName())));
            holder.b.switchEnabled.setOnCheckedChangeListener((v, checked) ->
                    listener.onToggle(app.getPackageName(), checked));

            // 分段胶囊：首项顶部/末项底部大圆角，其余小圆角
            float density = holder.itemView.getResources().getDisplayMetrics().density;
            float outer = 16f * density;
            float inner = 4f * density;
            boolean first = position == 0;
            boolean last = position == getItemCount() - 1;
            float tl = first ? outer : inner;
            float tr = first ? outer : inner;
            float br = last ? outer : inner;
            float bl = last ? outer : inner;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(com.google.android.material.color.MaterialColors.getColor(
                    holder.itemView.getContext(),
                    com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFFECE6F0));
            bg.setCornerRadii(new float[]{tl, tl, tr, tr, br, br, bl, bl});
            holder.b.getRoot().setBackground(bg);

            String pkg = app.getPackageName();
            Drawable cached = iconCache.get(pkg);
            if (cached != null) {
                holder.b.ivIcon.setImageDrawable(cached);
            } else {
                holder.b.ivIcon.setImageDrawable(defaultIcon);
                executor.execute(() -> {
                    try {
                        Drawable icon = packageManager.getApplicationIcon(pkg);
                        iconCache.put(pkg, icon);
                        if (pkg.equals(holder.b.tvPackage.getText().toString())) {
                            mainHandler.post(() -> holder.b.ivIcon.setImageDrawable(icon));
                        }
                    } catch (PackageManager.NameNotFoundException ignored) {
                    }
                });
            }
        }

        ScopeAdapter(PackageManager pm, Drawable defaultIcon, OnToggleListener listener) {
            this.packageManager = pm;
            this.defaultIcon = defaultIcon;
            this.listener = listener;
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            return currentList.get(position).getPackageName().hashCode();
        }

        @SuppressLint("NotifyDataSetChanged")
        void submitList(List<AppInfo> newList, Set<String> newSelected) {
            this.selected = new HashSet<>(newSelected);
            currentList.clear();
            currentList.addAll(newList);
            notifyDataSetChanged();
        }

        void refreshItem(String packageName) {
            String target = normalizePackageName(packageName);
            for (int i = 0; i < currentList.size(); i++) {
                if (normalizePackageName(currentList.get(i).getPackageName()).equals(target)) {
                    notifyItemChanged(i);
                    return;
                }
            }
        }

        interface OnToggleListener {
            void onToggle(String packageName, boolean enabled);
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemSystemHideBinding b = ItemSystemHideBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new VH(b);
        }

        @Override
        public int getItemCount() {
            return currentList.size();
        }

        @Override
        public void onViewRecycled(@NonNull VH holder) {
            super.onViewRecycled(holder);
            holder.b.switchEnabled.setOnCheckedChangeListener(null);
            holder.b.ivIcon.setImageDrawable(null);
        }

        static class VH extends RecyclerView.ViewHolder {
            ItemSystemHideBinding b;
            VH(ItemSystemHideBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }

    }
}
