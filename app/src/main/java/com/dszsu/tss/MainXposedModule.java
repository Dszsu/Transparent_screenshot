package com.dszsu.tss;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.SharedPreferences;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public class MainXposedModule extends XposedModule {

    private static final String TAG = "TransScreenshot";

    // 当前进程配置
    private final Set<String> enabledFeatures = Collections.synchronizedSet(new HashSet<>());
    private volatile String windowTitle = null;

    // Hook 去重（进程级）
    private static volatile boolean sHooksInstalled = false;
    private static final Object sLock = new Object();

    // 反射缓存（防截屏用）
    private static volatile Field sSurfaceControlField = null;
    private static final String[] SC_CANDIDATE_FIELDS = {
            "mSurfaceControl", "mSurface", "mLeash", "mSurfaceControlLocked"
    };
    private static volatile Constructor<?> sTxnConstructor = null;
    private static volatile Method sTxnSetSkipScreenshot = null;
    private static volatile Method sTxnSetSkipScreenshotLegacy = null;
    private static volatile Method sTxnSetSecure = null;
    private static volatile Method sTxnApply = null;
    private static volatile Method sTxnClose = null;
    private static volatile Class<?> sScClass = null;
    private static volatile Method sScIsValid = null;
    private static volatile boolean sCacheReady = false;

    // 已应用防截屏的 ViewRootImpl 集合
    private final Set<Object> secureApplied = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>())
    );

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String pkg = param.getPackageName();
        log(android.util.Log.INFO, TAG, "Module injected into " + pkg);

        loadConfig(pkg);

        if (!sHooksInstalled) {
            synchronized (sLock) {
                if (!sHooksInstalled) {
                    try {
                        initReflectionCache(param.getClassLoader());
                        installWindowManagerHook(param);

                        if (enabledFeatures.contains("enable_skip_screenshot")) {
                            installAntiScreenshotHook(param);
                            log(android.util.Log.INFO, TAG, "Anti-screenshot hook installed (explicitly enabled)");
                        } else {
                            log(android.util.Log.INFO, TAG, "Anti-screenshot hook NOT installed");
                        }

                        if (enabledFeatures.contains("hide_recent_card")) {
                            installHideRecentsHook();
                        }

                        sHooksInstalled = true;
                        log(android.util.Log.INFO, TAG, "All hooks initialized for process");
                    } catch (Throwable t) {
                        log(android.util.Log.ERROR, TAG, "Failed to install hooks: " + t.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 加载当前包名的配置，包含 WebView 标题继承与壁纸标志合并处理
     */
    private void loadConfig(String packageName) {
        synchronized (enabledFeatures) {
            enabledFeatures.clear();
            windowTitle = null;
        }
        try {
            SharedPreferences globalPrefs = getRemotePreferences("global");
            String webviewPkg = globalPrefs.getString("webview_package", null);
            boolean isWebView = (packageName.equals(webviewPkg));

            SharedPreferences prefs = getRemotePreferences(packageName.toLowerCase());

            // 无本地配置的情况
            if (prefs.getAll().isEmpty()) {
                log(android.util.Log.INFO, TAG, "[" + packageName + "] No local config");
                if (isWebView) {
                    // WebView 只继承全局标题和壁纸，不添加其他功能
                    applyWebViewDefaults(globalPrefs);
                }
                return;
            }

            // 读取本地功能开关
            Set<String> features = new HashSet<>();
            if (prefs.contains("enable_skip_screenshot")) features.add("enable_skip_screenshot");
            if (prefs.contains("FLAG_DIM_BEHIND_0")) features.add("FLAG_DIM_BEHIND_0");
            if (prefs.contains("magic_flags")) features.add("magic_flags");
            if (prefs.contains("hide_recent_card")) features.add("hide_recent_card");

            // 窗口标题处理（简化 null 检查）
            String titleValue = prefs.getString("window_title", null);
            String finalTitle = null;
            boolean titleFromGlobal = false;
            if ("$global".equals(titleValue)) {
                finalTitle = globalPrefs.getString("title", null);
                titleFromGlobal = true;
            } else if (titleValue != null) {
                finalTitle = titleValue;
            }

            // WebView 壁纸标志（仅 WebView 进程）
            if (isWebView && globalPrefs.getBoolean("webview_show_wallpaper", false)) {
                features.add("show_wallpaper_webview");
            }

            // WebView 标题自动补全：若无本地标题且未跟随全局，则尝试使用全局标题
            if (isWebView && finalTitle == null) {
                finalTitle = globalPrefs.getString("title", null);
                titleFromGlobal = true;
            }

            synchronized (enabledFeatures) {
                enabledFeatures.addAll(features);
                windowTitle = (finalTitle != null && !finalTitle.isEmpty()) ? finalTitle : null;
            }

            // 日志输出配置
            log(android.util.Log.INFO, TAG, "[" + packageName + "] Config: features=" + features +
                    ", title=" + windowTitle + (titleFromGlobal ? " (from global)" : ""));

        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "[" + packageName + "] Config load error: " + t.getMessage());
        }
    }

    /**
     * 为 WebView 进程设置默认标题和壁纸标志（无本地配置时）
     */
    private void applyWebViewDefaults(SharedPreferences globalPrefs) {
        String title = globalPrefs.getString("title", null);
        if (title != null && !title.isEmpty()) {
            windowTitle = title;
        }
        if (globalPrefs.getBoolean("webview_show_wallpaper", false)) {
            enabledFeatures.add("show_wallpaper_webview");
        }
        log(android.util.Log.INFO, TAG, "WebView defaults applied: title=" + windowTitle +
                ", wallpaper=" + enabledFeatures.contains("show_wallpaper_webview"));
    }

    // ==================== 反射缓存 ====================
    @SuppressLint("PrivateApi")
    private void initReflectionCache(ClassLoader cl) throws Exception {
        if (sCacheReady) return;
        synchronized (sLock) {
            if (sCacheReady) return;

            Class<?> vriClass = Class.forName("android.view.ViewRootImpl", false, cl);
            for (String fieldName : SC_CANDIDATE_FIELDS) {
                try {
                    Field f = vriClass.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    sSurfaceControlField = f;
                    break;
                } catch (NoSuchFieldException ignored) {}
            }

            sScClass = Class.forName("android.view.SurfaceControl", false, cl);
            sScIsValid = sScClass.getDeclaredMethod("isValid");
            sScIsValid.setAccessible(true);

            Class<?> txnClass = Class.forName("android.view.SurfaceControl$Transaction", false, cl);
            sTxnConstructor = txnClass.getDeclaredConstructor();
            sTxnConstructor.setAccessible(true);
            sTxnApply = txnClass.getDeclaredMethod("apply");
            sTxnApply.setAccessible(true);
            sTxnClose = txnClass.getDeclaredMethod("close");
            sTxnClose.setAccessible(true);

            try { sTxnSetSkipScreenshot = txnClass.getDeclaredMethod("setSkipScreenshot", sScClass, boolean.class); sTxnSetSkipScreenshot.setAccessible(true); } catch (Throwable ignored) {}
            try { sTxnSetSkipScreenshotLegacy = txnClass.getDeclaredMethod("setSkipScreenshot", boolean.class); sTxnSetSkipScreenshotLegacy.setAccessible(true); } catch (Throwable ignored) {}
            try { sTxnSetSecure = txnClass.getDeclaredMethod("setSecure", sScClass, boolean.class); sTxnSetSecure.setAccessible(true); } catch (Throwable ignored) {}

            sCacheReady = true;
        }
    }

    // ==================== 窗口属性修改 ====================
    @SuppressLint("PrivateApi")
    private void installWindowManagerHook(PackageReadyParam param) throws Exception {
        ClassLoader cl = param.getClassLoader();
        Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal", false, cl);

        for (Method method : wmgClass.getDeclaredMethods()) {
            String name = method.getName();
            if ("addView".equals(name) || "updateViewLayout".equals(name)) {
                try {
                    hook(method)
                            .setPriority(XposedInterface.PRIORITY_DEFAULT)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                for (Object arg : chain.getArgs()) {
                                    if (arg instanceof WindowManager.LayoutParams) {
                                        modifyLayoutParams((WindowManager.LayoutParams) arg);
                                        break;
                                    }
                                }
                                return chain.proceed();
                            });
                } catch (Throwable t) {
                    log(android.util.Log.WARN, TAG, "Failed to hook " + name + ": " + t.getMessage());
                }
            }
        }
    }

    @SuppressLint("WrongConstant")
    private void modifyLayoutParams(WindowManager.LayoutParams lp) {
        if (enabledFeatures.contains("FLAG_DIM_BEHIND_0")) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            lp.dimAmount = 0f;
        }
        if (enabledFeatures.contains("magic_flags")) {
            int magicFlags = 0x40000 | 0x200 | 0x20 | 0x8 | 0x20000;
            lp.flags |= magicFlags;
        }
        // 仅 WebView 进程且全局开关开启时生效
        if (enabledFeatures.contains("show_wallpaper_webview")) {
            lp.flags |= 0x100000; // FLAG_SHOW_WALLPAPER
        }
        if (windowTitle != null) {
            try {
                lp.setTitle(windowTitle);
            } catch (Throwable ignored) {}
        }
    }

    // ==================== 防截屏 ====================
    @SuppressLint("PrivateApi")
    private void installAntiScreenshotHook(PackageReadyParam param) throws Exception {
        ClassLoader cl = param.getClassLoader();
        Class<?> vriClass = Class.forName("android.view.ViewRootImpl", false, cl);

        for (Method m : vriClass.getDeclaredMethods()) {
            String name = m.getName();
            if ("setView".equals(name) || "relayoutWindow".equals(name) || "performTraversals".equals(name)) {
                try {
                    hook(m).setPriority(XposedInterface.PRIORITY_DEFAULT)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                if ("setView".equals(name)) {
                                    chain.proceed();
                                    applySecure(chain.getThisObject());
                                    return null;
                                } else if ("relayoutWindow".equals(name)) {
                                    secureApplied.remove(chain.getThisObject());
                                    Object result = chain.proceed();
                                    applySecure(chain.getThisObject());
                                    return result;
                                } else {
                                    applySecure(chain.getThisObject());
                                    return chain.proceed();
                                }
                            });
                } catch (Throwable t) {
                    log(android.util.Log.WARN, TAG, "Failed to hook " + name + " for anti-screenshot: " + t.getMessage());
                }
            }
        }
    }

    private void applySecure(Object vri) {
        if (!sCacheReady || secureApplied.contains(vri)) return;

        Object sc = getValidSurface(vri);
        if (sc == null) return;

        Object txn = null;
        try {
            txn = sTxnConstructor.newInstance();
            boolean applied = false;
            if (sTxnSetSkipScreenshot != null) {
                try { sTxnSetSkipScreenshot.invoke(txn, sc, true); applied = true; } catch (Throwable ignored) {}
            }
            if (!applied && sTxnSetSkipScreenshotLegacy != null) {
                try { sTxnSetSkipScreenshotLegacy.invoke(txn, true); applied = true; } catch (Throwable ignored) {}
            }
            if (!applied && sTxnSetSecure != null) {
                try { sTxnSetSecure.invoke(txn, sc, true); applied = true; } catch (Throwable ignored) {}
            }

            if (applied) {
                sTxnApply.invoke(txn);
                secureApplied.add(vri);
            }
        } catch (Throwable ignored) {
        } finally {
            if (txn != null) {
                try { sTxnClose.invoke(txn); } catch (Throwable ignored) {}
            }
        }
    }

    private Object getValidSurface(Object vri) {
        if (sSurfaceControlField == null || sScClass == null) return null;
        try {
            Object sc = sSurfaceControlField.get(vri);
            if (sc != null && sScClass.isInstance(sc) && Boolean.TRUE.equals(sScIsValid.invoke(sc))) {
                return sc;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ==================== 隐藏后台卡片 ====================
    private void installHideRecentsHook() throws Exception {
        Class<?> activityClass = Activity.class;
        Method onCreate = activityClass.getDeclaredMethod("onCreate", android.os.Bundle.class);

        hook(onCreate)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    chain.proceed();
                    Activity activity = (Activity) chain.getThisObject();
                    try {
                        ActivityManager am = (ActivityManager) activity.getSystemService(Activity.ACTIVITY_SERVICE);
                        if (am != null) {
                            for (ActivityManager.AppTask task : am.getAppTasks()) {
                                if (task.getTaskInfo().taskId == activity.getTaskId()) {
                                    task.setExcludeFromRecents(true);
                                    break;
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                    return null;
                });
    }
}