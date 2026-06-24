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
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dszsu.tss.databinding.ActivitySystemHideBinding;
import com.dszsu.tss.databinding.ItemSystemHideBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import io.github.libxposed.service.XposedService;

public class SystemHideActivity extends AppCompatActivity implements App.ServiceListener {

    private static final Set<String> EXCLUDE_PACKAGES = Set.of(
            "system", "android", "com.android.systemui", "oplus");

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

        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false);
        animator.setMoveDuration(250);
        animator.setAddDuration(180);
        animator.setRemoveDuration(180);
        binding.rvApps.setItemAnimator(animator);

        setupSearchView();
        App.addListener(this);
    }

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
                applyFilter(currentQuery);
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

    private void loadHiddenPackages() {
        if (service == null) return;
        SharedPreferences prefs = service.getRemotePreferences("system_hide");
        hiddenPackages.clear();
        Set<String> raw = prefs.getStringSet("packages", new HashSet<>());
        for (String p : raw) {
            if (p != null && !p.isEmpty()) {
                hiddenPackages.add(normalizePackageName(p));
            }
        }
    }

    private void rebuildAppList() {
        List<AppInfo> allApps = AppListRepository.getInstance().getAllApps();
        allFilteredApps.clear();

        for (AppInfo app : allApps) {
            String lowerPkg = normalizePackageName(app.getPackageName());
            boolean enabled = hiddenPackages.contains(lowerPkg);

            if (!enabled) {
                if (EXCLUDE_PACKAGES.contains(lowerPkg) || isSubPackageOfExcluded(lowerPkg))
                    continue;
                if (!showSystemApps && app.isSystemApp()) continue;
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
            if (lowerPkg.startsWith(excluded + ".")) return true;
        }
        return false;
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
            if (hiddenPackages.contains(normalizePackageName(app.getPackageName()))) {
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
        String lowerPkg = normalizePackageName(packageName);
        if (enabled) {
            hiddenPackages.add(lowerPkg);
        } else {
            hiddenPackages.remove(lowerPkg);
        }
        service.getRemotePreferences("system_hide").edit()
                .putStringSet("packages", new HashSet<>(hiddenPackages))
                .apply();
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

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            AppInfo app = currentList.get(position);
            holder.b.tvLabel.setText(app.getLabel());
            holder.b.tvPackage.setText(app.getPackageName());

            holder.b.switchEnabled.setOnCheckedChangeListener(null);
            holder.b.switchEnabled.setChecked(selected.contains(normalizePackageName(app.getPackageName())));
            holder.b.switchEnabled.setOnCheckedChangeListener((v, checked) ->
                    listener.onToggle(app.getPackageName(), checked));

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

        SystemHideAdapter(PackageManager pm, Drawable defaultIcon, OnToggleListener listener) {
            this.packageManager = pm;
            this.defaultIcon = defaultIcon;
            this.listener = listener;
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            return currentList.get(position).getPackageName().hashCode();
        }

        void submitList(List<AppInfo> newList, Set<String> newSelected) {
            List<AppInfo> oldList = new ArrayList<>(currentList);
            Set<String> oldSelected = new HashSet<>(this.selected);
            DiffUtil.DiffResult diff = DiffUtil.calculateDiff(
                    new AppDiffCallback(oldList, newList, oldSelected, newSelected), true);
            this.selected = new HashSet<>(newSelected);
            currentList.clear();
            currentList.addAll(newList);
            diff.dispatchUpdatesTo(this);
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

        private static class AppDiffCallback extends DiffUtil.Callback {
            private final List<AppInfo> oldList, newList;
            private final Set<String> oldSelected, newSelected;

            AppDiffCallback(List<AppInfo> oldList, List<AppInfo> newList,
                            Set<String> oldSelected, Set<String> newSelected) {
                this.oldList = oldList;
                this.newList = newList;
                this.oldSelected = oldSelected;
                this.newSelected = newSelected;
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
            public boolean areItemsTheSame(int oldPos, int newPos) {
                return oldList.get(oldPos).getPackageName()
                        .equals(newList.get(newPos).getPackageName());
            }

            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                AppInfo o = oldList.get(oldPos), n = newList.get(newPos);
                return o.getLabel().equals(n.getLabel())
                        && oldSelected.contains(normalizePackageName(o.getPackageName()))
                        == newSelected.contains(normalizePackageName(n.getPackageName()));
            }
        }
    }
}