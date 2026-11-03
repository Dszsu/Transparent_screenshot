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
import java.util.Locale;
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

    private static String appInfoPackageName(String packageName) {
        return packageName == null ? "" : packageName.toLowerCase(Locale.ENGLISH);
    }

    /**
     * 若某应用的配置只剩 disable_hook 一条，删除它的整个配置组并返回 true。
     *
     * <p>前提是该应用已不在作用域中：不在作用域本来就不会被 hook，
     * “关闭 hook”这条记录已无意义，只会让该应用凭空出现在主界面列表里。
     * 只要还开着任何其它配置，整组配置原样保留。
     */
    public static boolean deleteConfigIfOnlyDisableHook(XposedService service, String normalizedPkg) {
        if (service == null || normalizedPkg == null || normalizedPkg.isEmpty()) return false;
        if ("system".equals(normalizedPkg)) return false;
        try {
            SharedPreferences prefs = service.getRemotePreferences(normalizedPkg);
            if (prefs == null) return false;
            java.util.Map<String, ?> all = prefs.getAll();
            if (all.size() == 1 && all.containsKey("disable_hook")) {
                service.deleteRemotePreferences(normalizedPkg);
                android.util.Log.i("TSS-AppList",
                        "cleanup disable_hook-only config of out-of-scope app: " + normalizedPkg);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public List<AppInfo> getAllApps() {
        return new ArrayList<>(allApps);
    }

    public List<AppInfo> filterApps(String query) {
        return filterApps(allApps, query);
    }

    public void refreshData(XposedService service, PackageManager pm, OnDataRefreshListener listener) {
        if (isLoading) return;
        isLoading = true;
        notifyLoading(listener, true);
        executor.execute(() -> {
            try {
                List<String> rawScope = service != null ? service.getScope() : null;
                Set<String> scope = new HashSet<>();
                if (rawScope != null) for (String s : rawScope) scope.add(appInfoPackageName(s));

                List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                List<AppInfo> apps = new ArrayList<>(installed.size());
                Set<String> seen = new HashSet<>();
                for (ApplicationInfo app : installed) {
                    String pkg = app.packageName;
                    String lower = appInfoPackageName(pkg);
                    seen.add(lower);
                    boolean inScope = scope.contains(lower);
                    boolean critical = isSystemCritical(lower);
                    boolean isSys = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    if ("com.android.systemui".equals(lower)) continue;
                    apps.add(new AppInfo(app.loadLabel(pm).toString(), pkg, inScope, false, critical, isSys));
                }

                if (scope.contains("system") && !seen.contains("system")) {
                    AppInfo sysEntry = new AppInfo("", "system", true, false, false, false);
                    try {

                        SharedPreferences sp = service.getRemotePreferences("system_hide");

                        if (sp != null) {
                            sysEntry.setSystemUIEnhanced(sp.contains("system_ui_enhancement_enabled"));
                        }
                    } catch (Throwable ignored) {
                    }
                    apps.add(sysEntry);
                }

                // 界面打开时顺带清理已卸载应用的残留配置（须在配置检测之前，
                // 这样清理结果能直接反映到本次列表上）
                cleanupOrphanConfigs(service, pm, scope, seen);

                // 已不在作用域、且配置只剩“关闭 hook”一条的已安装应用：
                // 删掉这条无意义的配置，配置检测时它就不会再被算作“有配置”
                // 而出现在列表中
                if (service != null) {
                    for (AppInfo a : apps) {
                        if (!a.isInScope()) {
                            deleteConfigIfOnlyDisableHook(service, a.getNormalizedPackageName());
                        }
                    }
                }

                apps.sort(Comparator.comparing(a -> a.getLabel().toLowerCase(Locale.ENGLISH)));
                allApps = apps;

                Set<String> configured = new HashSet<>();
                if (service != null) {
                    for (AppInfo a : apps) {
                        try {
                            SharedPreferences p = service.getRemotePreferences(a.getNormalizedPackageName());
                            if (!p.getAll().isEmpty())
                                configured.add(a.getNormalizedPackageName());
                        } catch (Throwable ignored) {
                        }
                    }
                }
                for (AppInfo a : apps) {
                    if (configured.contains(a.getNormalizedPackageName())) a.setHasConfig(true);
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

    /**
     * 清理已卸载应用的残留配置。
     *
     * <p>候选包名只能来自可枚举的来源（作用域列表与 system_hide 的 packages 集合）：
     * libxposed service 没有枚举全部远程配置组的接口，无法反查所有配置组。
     * 候选包不在已安装列表中时，还不能直接认定已卸载——先校验应用列表的
     * 可见性是否可信（获取应用列表权限被收回/包可见性受限时枚举结果不完整），
     * 再逐个用包名定向查询复核；两步都确认查不到，才视为应用已卸载并删除：
     * 该包的整个远程配置组、system_hide 集合中的条目、以及残留的作用域条目。
     * 虚拟包名（system）与系统关键包永不参与清理。
     */
    private void cleanupOrphanConfigs(XposedService service, PackageManager pm,
                                      Set<String> scope, Set<String> installed) {
        if (service == null || pm == null) return;
        try {
            // 第一步：校验枚举结果可信。android 与 SystemUI 在任何设备上都必然
            // 已安装，若连它们都枚举不到（或总数明显过少），说明应用列表权限
            // 有问题，此时一律不删，避免把“看不见”误判为“已卸载”。
            if (installed.size() < 20
                    || !installed.contains("android")
                    || !installed.contains("com.android.systemui")) {
                return;
            }

            // 候选：作用域 + system_hide 隐藏集合
            Set<String> candidates = new HashSet<>(scope);
            SharedPreferences sysPrefs = null;
            Set<String> hidden = new HashSet<>();
            try {
                sysPrefs = service.getRemotePreferences("system_hide");
                Set<String> raw = sysPrefs.getStringSet("packages", Collections.emptySet());
                if (raw != null) {
                    for (String p : raw) {
                        String n = appInfoPackageName(p);
                        if (!n.isEmpty()) {
                            hidden.add(n);
                            candidates.add(n);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // 第二步：逐个定向复核，确认确实查不到这个包
            List<String> orphans = new ArrayList<>();
            for (String pkg : candidates) {
                if (pkg.isEmpty() || "system".equals(pkg)) continue;
                if (isSystemCritical(pkg)) continue;
                if (installed.contains(pkg)) continue;
                try {
                    pm.getApplicationInfo(pkg, 0);
                    // 定向查询能查到，说明应用还在（只是没出现在枚举列表里），不删
                } catch (PackageManager.NameNotFoundException e) {
                    orphans.add(pkg);
                } catch (Throwable ignored) {
                    // 其它异常一律保守处理：不删
                }
            }
            if (orphans.isEmpty()) return;

            boolean hiddenChanged = false;
            List<String> scopeOrphans = new ArrayList<>();
            for (String pkg : orphans) {
                // 删除该应用的整个远程配置组（无配置时 delete 也无副作用，
                // 但先判空可避免对从未配置过的包做无意义调用）
                try {
                    SharedPreferences p = service.getRemotePreferences(pkg);
                    if (p != null && !p.getAll().isEmpty()) {
                        service.deleteRemotePreferences(pkg);
                    }
                } catch (Throwable ignored) {
                }
                if (hidden.remove(pkg)) hiddenChanged = true;
                if (scope.contains(pkg)) scopeOrphans.add(pkg);
                android.util.Log.i("TSS-AppList", "cleanup config of uninstalled app: " + pkg);
            }
            if (hiddenChanged && sysPrefs != null) {
                try {
                    sysPrefs.edit().putStringSet("packages", new HashSet<>(hidden)).apply();
                } catch (Throwable ignored) {
                }
            }
            if (!scopeOrphans.isEmpty()) {
                try {
                    service.removeScope(scopeOrphans);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
            // 清理是附带动作，任何失败都不能影响应用列表本身的加载
        }
    }

    private List<AppInfo> filterApps(List<AppInfo> source, String search) {
        List<AppInfo> result = new ArrayList<>();
        String lower = search.toLowerCase(Locale.ENGLISH).trim();
        for (AppInfo a : source) {
            if (!a.isInScope() && !a.hasConfig()) continue;
            if (lower.isEmpty() || a.getNormalizedLabel().contains(lower) || a.getNormalizedPackageName().contains(lower))
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
