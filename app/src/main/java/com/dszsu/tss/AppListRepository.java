package com.dszsu.tss;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.service.XposedService;

public class AppListRepository {

    private static final Set<String> SYSTEM_CRITICAL = new HashSet<>();
    static {
        SYSTEM_CRITICAL.add("android");
        SYSTEM_CRITICAL.add("com.android.systemui");
        SYSTEM_CRITICAL.add("oplus");
    }

    private static volatile AppListRepository instance;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile List<AppInfo> allApps = Collections.emptyList();
    private volatile boolean isLoading = false;

    public static AppListRepository getInstance() {
        if (instance == null) {
            synchronized (AppListRepository.class) {
                if (instance == null) instance = new AppListRepository();
            }
        }
        return instance;
    }

    public void refreshData(XposedService service, PackageManager pm, OnDataRefreshListener listener) {
        if (isLoading) return;
        isLoading = true;
        notifyLoading(listener, true);
        executor.execute(() -> {
            try {
                List<String> rawScope = service != null ? service.getScope() : null;
                Set<String> scope = new HashSet<>();
                if (rawScope != null) for (String s : rawScope) scope.add(s.toLowerCase());

                List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                List<AppInfo> apps = new ArrayList<>(installed.size());
                Set<String> seen = new HashSet<>();
                for (ApplicationInfo app : installed) {
                    String pkg = app.packageName;
                    String lower = pkg.toLowerCase();
                    seen.add(lower);
                    boolean inScope = scope.contains(lower);
                    boolean critical = isSystemCritical(lower);
                    boolean isSys = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    apps.add(new AppInfo(app.loadLabel(pm).toString(), pkg, inScope, false, critical, isSys));
                }

                if (scope.contains("system") && !seen.contains("system")) {
                    apps.add(new AppInfo("", "system", true, false, false, false));
                }

                apps.sort(Comparator.comparing(a -> a.getLabel().toLowerCase()));
                allApps = apps;

                Set<String> configured = new HashSet<>();
                if (service != null) {
                    for (AppInfo a : apps) {
                        try {
                            SharedPreferences p = service.getRemotePreferences(a.getPackageName().toLowerCase());
                            if (!p.getAll().isEmpty())
                                configured.add(a.getPackageName().toLowerCase());
                        } catch (Throwable ignored) {
                        }
                    }
                }
                for (AppInfo a : apps) {
                    if (configured.contains(a.getPackageName().toLowerCase())) a.setHasConfig(true);
                }

                List<AppInfo> filtered = filterApps(apps, "");
                mainHandler.post(() -> {
                    isLoading = false;
                    notifyLoading(listener, false);
                    listener.onRefreshComplete(filtered);
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    isLoading = false;
                    notifyLoading(listener, false);
                    listener.onRefreshComplete(Collections.emptyList());
                });
            }
        });
    }

    public List<AppInfo> getAllApps() {
        return new ArrayList<>(allApps);
    }

    public List<AppInfo> filterApps(String query) {
        return filterApps(allApps, query);
    }

    private List<AppInfo> filterApps(List<AppInfo> source, String search) {
        List<AppInfo> result = new ArrayList<>();
        String lower = search.toLowerCase().trim();
        for (AppInfo a : source) {
            if (!a.isInScope() && !a.isShowConfig()) continue;
            if (lower.isEmpty() || a.getLabel().toLowerCase().contains(lower) || a.getPackageName().toLowerCase().contains(lower))
                result.add(a);
        }
        return result;
    }

    private boolean isSystemCritical(String lower) {
        if (SYSTEM_CRITICAL.contains(lower)) return true;
        for (String c : SYSTEM_CRITICAL) if (lower.startsWith(c + ".")) return true;
        return false;
    }

    private void notifyLoading(OnDataRefreshListener l, boolean loading) {
        if (l != null) mainHandler.post(() -> l.onLoadingStateChanged(loading));
    }

    public interface OnDataRefreshListener {
        void onRefreshComplete(List<AppInfo> filteredList);
        void onLoadingStateChanged(boolean isLoading);
    }
}