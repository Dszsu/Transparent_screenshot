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

    // 系统关键进程
    private static final Set<String> SYSTEM_CRITICAL = new HashSet<>();
    private static volatile AppListRepository instance;

    static {
        SYSTEM_CRITICAL.add("android");
        SYSTEM_CRITICAL.add("system");
        SYSTEM_CRITICAL.add("com.android.systemui");
        SYSTEM_CRITICAL.add("oplus");
    }

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
                // 作用域
                List<String> rawScope = service != null ? service.getScope() : null;
                Set<String> scope = new HashSet<>();
                if (rawScope != null) for (String s : rawScope) scope.add(s.toLowerCase());

                // 全量应用
                List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                List<AppInfo> apps = new ArrayList<>(installed.size());
                Set<String> seenPackages = new HashSet<>();
                for (ApplicationInfo app : installed) {
                    String pkg = app.packageName;
                    String lowerPkg = pkg.toLowerCase();
                    seenPackages.add(lowerPkg);
                    boolean inScope = scope.contains(lowerPkg);
                    boolean critical = isSystemCritical(lowerPkg);
                    apps.add(new AppInfo(
                            app.loadLabel(pm).toString(),
                            pkg,
                            inScope,
                            false,
                            critical
                    ));
                }

                // 添加作用域中未出现的系统
                for (String scopePkg : scope) {
                    String lower = scopePkg.toLowerCase();
                    if (!seenPackages.contains(lower) && isSystemCritical(lower)) {
                        if ("system".equals(lower)) {
                            String label = "系统框架";
                            try {
                                ApplicationInfo androidApp = pm.getApplicationInfo("android", 0);
                                label = androidApp.loadLabel(pm).toString();
                            } catch (PackageManager.NameNotFoundException ignored) {
                            }
                            apps.add(new AppInfo(label, lower, true, false, true));
                        } else {
                            apps.add(new AppInfo(lower, lower, true, false, true));
                        }
                    }
                }

                apps.sort(Comparator.comparing(a -> a.getLabel().toLowerCase()));
                allApps = apps;

                // 批量查询已配置状态
                Set<String> configured = new HashSet<>();
                if (service != null) {
                    for (AppInfo info : apps) {
                        try {
                            SharedPreferences prefs = service.getRemotePreferences(info.getPackageName().toLowerCase());
                            if (!prefs.getAll().isEmpty()) {
                                configured.add(info.getPackageName().toLowerCase());
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }

                // 更新标记
                for (AppInfo info : apps) {
                    if (configured.contains(info.getPackageName().toLowerCase())) {
                        info.setHasConfig(true);
                    }
                }

                // 过滤（作用域内 或 已配置）
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

    public List<AppInfo> filterApps(String searchQuery) {
        return filterApps(allApps, searchQuery);
    }

    private List<AppInfo> filterApps(List<AppInfo> apps, String search) {
        List<AppInfo> result = new ArrayList<>();
        String lowerSearch = search.toLowerCase().trim();
        for (AppInfo info : apps) {
            if (!info.isInScope() && !info.isShowConfig()) continue;
            if (lowerSearch.isEmpty() ||
                    info.getLabel().toLowerCase().contains(lowerSearch) ||
                    info.getPackageName().toLowerCase().contains(lowerSearch)) {
                result.add(info);
            }
        }
        return result;
    }

    private boolean isSystemCritical(String lowerPkg) {
        if (SYSTEM_CRITICAL.contains(lowerPkg)) return true;
        for (String critical : SYSTEM_CRITICAL) {
            if (lowerPkg.startsWith(critical + ".")) return true;
        }
        return false;
    }

    private void notifyLoading(OnDataRefreshListener listener, boolean loading) {
        if (listener != null) mainHandler.post(() -> listener.onLoadingStateChanged(loading));
    }

    public interface OnDataRefreshListener {
        void onRefreshComplete(List<AppInfo> filteredList);

        void onLoadingStateChanged(boolean isLoading);
    }
}