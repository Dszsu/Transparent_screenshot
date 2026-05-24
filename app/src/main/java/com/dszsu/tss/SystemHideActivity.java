package com.dszsu.tss;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dszsu.tss.databinding.ActivitySystemHideBinding;
import com.dszsu.tss.databinding.ItemSystemHideBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import io.github.libxposed.service.XposedService;

public class SystemHideActivity extends AppCompatActivity
        implements App.ServiceListener {

    private static final Set<String> EXCLUDE_PACKAGES = new HashSet<>();

    static {
        EXCLUDE_PACKAGES.add("system");
        EXCLUDE_PACKAGES.add("android");
        EXCLUDE_PACKAGES.add("com.android.systemui");
        EXCLUDE_PACKAGES.add("oplus");
    }

    private final Set<String> hiddenPackages = new HashSet<>();
    private final List<AppInfo> allFilteredApps = new ArrayList<>();
    private ActivitySystemHideBinding binding;
    private XposedService service;
    private SystemHideAdapter adapter;
    private boolean showSystemApps = false;
    private String currentQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySystemHideBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        binding.rvApps.setLayoutManager(new LinearLayoutManager(this));

        binding.searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                currentQuery = newText != null ? newText : "";
                applyFilter(currentQuery);
                return true;
            }
        });

        binding.searchView.setOnCloseListener(() -> {
            currentQuery = "";
            applyFilter("");
            binding.searchView.clearFocus();
            hideKeyboard();
            return false;
        });

        App.addListener(this);
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
            loadHiddenPackages();
            rebuildAppList();
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(binding.searchView.getWindowToken(), 0);
        }
    }

    private void loadHiddenPackages() {
        if (service == null) return;
        SharedPreferences prefs = service.getRemotePreferences("system_hide");
        hiddenPackages.clear();
        hiddenPackages.addAll(prefs.getStringSet("packages", new HashSet<>()));
    }

    private void rebuildAppList() {
        List<AppInfo> allApps = AppListRepository.getInstance().getAllApps();
        allFilteredApps.clear();

        for (AppInfo app : allApps) {
            String pkg = app.getPackageName().toLowerCase();
            if (EXCLUDE_PACKAGES.contains(pkg) || isSubPackageOfExcluded(pkg)) {
                continue;
            }

            // 使用 isSystemApp 代替跨进程查询，大幅提升性能
            if (!showSystemApps && app.isSystemApp()) {
                continue;
            }

            allFilteredApps.add(app);
        }

        if (adapter == null) {
            adapter = new SystemHideAdapter(
                    getPackageManager(),
                    getPackageManager().getDefaultActivityIcon(),
                    this::onToggle
            );
            binding.rvApps.setAdapter(adapter);
        }

        applyFilter(currentQuery);
    }

    private boolean isSubPackageOfExcluded(String lowerPkg) {
        for (String excluded : EXCLUDE_PACKAGES) {
            if (lowerPkg.startsWith(excluded + ".")) {
                return true;
            }
        }
        return false;
    }

    private void applyFilter(String query) {
        if (adapter == null) return;

        List<AppInfo> enabled = new ArrayList<>();
        List<AppInfo> disabled = new ArrayList<>();
        String lowerQuery = query != null ? query.toLowerCase().trim() : "";

        for (AppInfo app : allFilteredApps) {
            boolean match;
            if (lowerQuery.isEmpty()) {
                match = true;
            } else {
                match = app.getLabel().toLowerCase().contains(lowerQuery)
                        || app.getPackageName().toLowerCase().contains(lowerQuery);
            }

            if (!match) continue;

            if (hiddenPackages.contains(app.getPackageName())) {
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

        adapter.submitList(finalList, hiddenPackages);
    }

    private void onToggle(String packageName, boolean enabled) {
        if (service == null) return;

        if (enabled) {
            hiddenPackages.add(packageName);
        } else {
            hiddenPackages.remove(packageName);
        }

        SharedPreferences.Editor editor = service.getRemotePreferences("system_hide").edit();
        editor.putStringSet("packages", new HashSet<>(hiddenPackages));
        editor.apply();

        binding.searchView.clearFocus();
        hideKeyboard();
        applyFilter(currentQuery);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        App.removeListener(this);
    }


    private static class SystemHideAdapter extends RecyclerView.Adapter<SystemHideAdapter.VH> {

        private final PackageManager packageManager;
        private final Drawable defaultIcon;
        private final OnToggleListener listener;
        private final LruCache<String, Drawable> iconCache = new LruCache<>(50);
        private final Executor executor = Executors.newSingleThreadExecutor();
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final List<AppInfo> currentList = new ArrayList<>();
        private Set<String> selected = new HashSet<>();

        SystemHideAdapter(PackageManager pm, Drawable defaultIcon, OnToggleListener listener) {
            this.packageManager = pm;
            this.defaultIcon = defaultIcon;
            this.listener = listener;
        }

        void submitList(List<AppInfo> newList, Set<String> newSelected) {
            List<AppInfo> oldList = new ArrayList<>(currentList);
            this.selected = new HashSet<>(newSelected);

            DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new AppDiffCallback(oldList, newList), false);
            currentList.clear();
            currentList.addAll(newList);
            diff.dispatchUpdatesTo(this);
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemSystemHideBinding b = ItemSystemHideBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new VH(b);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            AppInfo app = currentList.get(position);
            holder.b.tvLabel.setText(app.getLabel());
            holder.b.tvPackage.setText(app.getPackageName());

            holder.b.switchEnabled.setOnCheckedChangeListener(null);
            holder.b.switchEnabled.setChecked(selected.contains(app.getPackageName()));
            holder.b.switchEnabled.setOnCheckedChangeListener(
                    (v, checked) -> listener.onToggle(app.getPackageName(), checked));

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

        interface OnToggleListener {
            void onToggle(String packageName, boolean enabled);
        }

        static class VH extends RecyclerView.ViewHolder {
            ItemSystemHideBinding b;

            VH(ItemSystemHideBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }

        private static class AppDiffCallback extends DiffUtil.Callback {
            private final List<AppInfo> oldList;
            private final List<AppInfo> newList;

            AppDiffCallback(List<AppInfo> oldList, List<AppInfo> newList) {
                this.oldList = oldList;
                this.newList = newList;
            }

            @Override
            public int getOldListSize() {
                return oldList.size();
            }

            @Override
            public int getNewListSize() {
                return newList.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return oldList.get(oldItemPosition).getPackageName()
                        .equals(newList.get(newItemPosition).getPackageName());
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                AppInfo oldItem = oldList.get(oldItemPosition);
                AppInfo newItem = newList.get(newItemPosition);
                return oldItem.getLabel().equals(newItem.getLabel());
            }
        }
    }
}