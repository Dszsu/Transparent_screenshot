package com.dszsu.tss;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

@SuppressWarnings("FieldCanBeLocal")
public class MainXposedModule extends XposedModule {

    private static final String TAG = "TransScreenshot";
    private static final String SYSTEM_HIDE_GROUP = "system_hide";

    private static volatile boolean sSystemHooksInstalled = false;
    private static volatile boolean sWindowManagerHookInstalled = false;
    private static volatile boolean sAntiScreenshotHookInstalled = false;
    private static volatile boolean sHideRecentsHookInstalled = false;
    private static volatile Class<?> sSystemSurfaceControlClass = null;
    private static volatile boolean sAppReflectionReady = false;
    private static volatile Class<?> sAppSurfaceControlClass = null;
    private static volatile Field sAppSurfaceControlField = null;
    private static volatile Method sAppSurfaceControlIsValid = null;
    private static volatile Constructor<?> sAppTxnConstructor = null;
    private static volatile Method sAppTxnSetSkipScreenshot = null;
    private static volatile Method sAppTxnSetSkipScreenshotLegacy = null;
    private static volatile Method sAppTxnSetSecure = null;
    private static volatile Method sAppTxnApply = null;
    private static volatile Method sAppTxnClose = null;
    private final Object systemLock = new Object();
    private final Object appLock = new Object();
    private final Object configLock = new Object();
    private final Map<String, String> processConfigCache = new ConcurrentHashMap<>();
    private final Object windowCacheLock = new Object();
    private final WeakHashMap<Object, Boolean> windowHideCache = new WeakHashMap<>();
    private volatile ProcessConfig currentConfig = ProcessConfig.EMPTY;
    private volatile String activeConfigPackage = null;
    private volatile ClassLoader currentAppClassLoader = null;
    private final SharedPreferences.OnSharedPreferenceChangeListener appPrefsListener =
            (prefs, key) -> reloadCurrentConfig();
    private volatile boolean systemHideEnabled = false;
    private volatile Set<String> systemHiddenPackages = Collections.emptySet();
    private final SharedPreferences.OnSharedPreferenceChangeListener systemPrefsListener =
            (prefs, key) -> {
                if ("packages".equals(key)) {
                    loadSystemHiddenPackages(prefs);
                    clearWindowHideCache();
                    log(Log.INFO, TAG, "System hide packages updated");
                }
            };
    private Class<?> windowStateClass;
    private Class<?> transactionClass;
    private Class<?> taskClass;
    private Method getOwningPackageMethod;
    private Method getWindowTagMethod;
    private Method getTaskMethod;
    private Method getTaskSurfaceControlMethod;
    private Field mWindowStateSurfaceControlField;

    private final Set<Object> secureApplied = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>())
    );
    private Method setSkipScreenshotMethod;

    private static Method findMethodInHierarchy(Class<?> cls, String name, Class<?>... paramTypes)
            throws NoSuchMethodException {
        Class<?> current = cls;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(cls.getName() + "." + name);
    }

    private static Field findFieldInHierarchy(Class<?> cls, String name) throws NoSuchFieldException {
        Class<?> current = cls;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(cls.getName() + "." + name);
    }

    @Override
    public void onSystemServerStarting(@NonNull XposedModuleInterface.SystemServerStartingParam param) {
        super.onSystemServerStarting(param);
        try {
            SharedPreferences sysPrefs = getRemotePreferences(SYSTEM_HIDE_GROUP);
            loadSystemHiddenPackages(sysPrefs);
            sysPrefs.registerOnSharedPreferenceChangeListener(systemPrefsListener);
            installSystemHooks(param.getClassLoader());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "System-server init error: " + t.getMessage());
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String pkg = param.getPackageName();
        String configPackage = resolveConfigPackage(pkg);
        log(Log.INFO, TAG, "Injected into " + pkg + ", config key: " + configPackage);

        activeConfigPackage = configPackage;
        currentAppClassLoader = param.getClassLoader();

        synchronized (configLock) {
            loadConfig(configPackage);
        }

        registerAppConfigListeners(configPackage);
        ensureAppHooksInstalled(currentAppClassLoader);
    }

    @SuppressLint({"DiscouragedPrivateApi", "PrivateApi"})
    private String getProcessName() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getDeclaredMethod("currentActivityThread").invoke(null);
            return (String) atClass.getDeclaredMethod("getProcessName").invoke(at);
        } catch (Throwable t) {
            return null;
        }
    }

    private String resolveConfigPackage(String fallbackPkg) {
        String processName = getProcessName();
        String key = processName != null ? processName : fallbackPkg;
        return processConfigCache.computeIfAbsent(key, k -> {
            int idx = k.indexOf(':');
            return idx > 0 ? k.substring(0, idx) : k;
        });
    }

    private void registerAppConfigListeners(String configPackage) {
        try {
            SharedPreferences globalPrefs = getRemotePreferences("global");
            SharedPreferences pkgPrefs = getRemotePreferences(configPackage.toLowerCase(Locale.ROOT));
            globalPrefs.registerOnSharedPreferenceChangeListener(appPrefsListener);
            pkgPrefs.registerOnSharedPreferenceChangeListener(appPrefsListener);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Failed to register app prefs listeners: " + t.getMessage());
        }
    }

    private void reloadCurrentConfig() {
        String pkg = activeConfigPackage;
        if (pkg == null) return;
        synchronized (configLock) {
            loadConfig(pkg);
            ensureAppHooksInstalled(currentAppClassLoader);
        }
    }

    private void loadConfig(String configPackage) {
        try {
            SharedPreferences globalPrefs = getRemotePreferences("global");
            SharedPreferences prefs = getRemotePreferences(configPackage.toLowerCase(Locale.ROOT));

            Set<String> features = new HashSet<>();
            if (prefs.contains("enable_skip_screenshot")) features.add("enable_skip_screenshot");
            if (prefs.contains("FLAG_DIM_BEHIND_0")) features.add("FLAG_DIM_BEHIND_0");
            if (prefs.contains("show_wallpaper")) features.add("show_wallpaper");
            if (prefs.contains("magic_flags")) features.add("magic_flags");
            if (prefs.contains("hide_recent_card")) features.add("hide_recent_card");

            String titleValue = prefs.getString("window_title", null);
            String finalTitle = null;
            if ("$global".equals(titleValue)) {
                finalTitle = globalPrefs.getString("title", null);
            } else if (titleValue != null) {
                finalTitle = titleValue;
            }

            currentConfig = new ProcessConfig(
                    features,
                    (finalTitle != null && !finalTitle.isEmpty()) ? finalTitle : null
            );
            log(Log.INFO, TAG, "[" + configPackage + "] features=" + features + ", title=" + currentConfig.windowTitle);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Config load error: " + t.getMessage());
            currentConfig = ProcessConfig.EMPTY;
        }
    }

    private void ensureAppHooksInstalled(ClassLoader cl) {
        ProcessConfig config = currentConfig;
        try {
            boolean needWM = needsWindowManagerHook(config);
            boolean needAS = config.features.contains("enable_skip_screenshot");
            boolean needHR = config.features.contains("hide_recent_card");

            if (needWM && !sWindowManagerHookInstalled) {
                synchronized (appLock) {
                    if (needsWindowManagerHook(currentConfig) && !sWindowManagerHookInstalled) {
                        initAppReflection(cl);
                        installWindowManagerHook(cl);
                        sWindowManagerHookInstalled = true;
                        log(Log.INFO, TAG, "WindowManager hook installed");
                    }
                }
            }

            if (needAS && !sAntiScreenshotHookInstalled) {
                synchronized (appLock) {
                    if (currentConfig.features.contains("enable_skip_screenshot") && !sAntiScreenshotHookInstalled) {
                        initAppReflection(cl);
                        installAntiScreenshotHook(cl);
                        sAntiScreenshotHookInstalled = true;
                        log(Log.INFO, TAG, "Anti-screenshot hook installed");
                    }
                }
            }

            if (needHR && !sHideRecentsHookInstalled) {
                synchronized (appLock) {
                    if (currentConfig.features.contains("hide_recent_card") && !sHideRecentsHookInstalled) {
                        installHideRecentsHook();
                        sHideRecentsHookInstalled = true;
                        log(Log.INFO, TAG, "Hide-recents hook installed");
                    }
                }
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to install app hooks: " + t.getMessage());
        }
    }

    private boolean needsWindowManagerHook(ProcessConfig config) {
        return config.windowTitle != null
                || config.features.contains("FLAG_DIM_BEHIND_0")
                || config.features.contains("show_wallpaper")
                || config.features.contains("magic_flags");
    }

    @SuppressLint("PrivateApi")
    private void installSystemHooks(ClassLoader sysCl) throws Exception {
        if (sSystemHooksInstalled) return;
        synchronized (systemLock) {
            if (sSystemHooksInstalled) return;
            initSystemReflection(sysCl);

            Method updateSurfacePos = windowStateClass.getDeclaredMethod("updateSurfacePosition", transactionClass);
            hook(updateSurfacePos)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (!systemHideEnabled) return result;
                        Object winState = chain.getThisObject();
                        if (winState == null) return result;
                        if (!checkWindowForHide(winState)) return result;

                        Object transaction = chain.getArgs().get(0);
                        if (transaction == null) return result;

                        try {
                            if (mWindowStateSurfaceControlField != null) {
                                Object sc = mWindowStateSurfaceControlField.get(winState);
                                if (sc != null) {
                                    setSkipScreenshotMethod.invoke(transaction, sc, true);
                                }
                            }
                            Object task = getTaskMethod.invoke(winState);
                            if (task != null && getTaskSurfaceControlMethod != null) {
                                Object taskSurface = getTaskSurfaceControlMethod.invoke(task);
                                if (taskSurface != null) {
                                    setSkipScreenshotMethod.invoke(transaction, taskSurface, true);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return result;
                    });

            sSystemHooksInstalled = true;
            log(Log.INFO, TAG, "System hooks installed successfully");
        }
    }

    @SuppressLint("PrivateApi")
    private void initSystemReflection(ClassLoader cl) throws Exception {
        windowStateClass = Class.forName("com.android.server.wm.WindowState", false, cl);
        sSystemSurfaceControlClass = Class.forName("android.view.SurfaceControl", false, cl);
        transactionClass = Class.forName("android.view.SurfaceControl$Transaction", false, cl);
        taskClass = Class.forName("com.android.server.wm.Task", false, cl);

        getOwningPackageMethod = findMethodInHierarchy(windowStateClass, "getOwningPackage");
        getOwningPackageMethod.setAccessible(true);

        getWindowTagMethod = findMethodInHierarchy(windowStateClass, "getWindowTag");
        getWindowTagMethod.setAccessible(true);

        getTaskMethod = findMethodInHierarchy(windowStateClass, "getTask");
        getTaskMethod.setAccessible(true);

        getTaskSurfaceControlMethod = findMethodInHierarchy(taskClass, "getSurfaceControl");
        getTaskSurfaceControlMethod.setAccessible(true);

        try {
            mWindowStateSurfaceControlField = findFieldInHierarchy(windowStateClass, "mSurfaceControl");
            mWindowStateSurfaceControlField.setAccessible(true);
        } catch (Throwable ignored) {
            mWindowStateSurfaceControlField = null;
        }

        setSkipScreenshotMethod = transactionClass.getMethod("setSkipScreenshot", sSystemSurfaceControlClass, boolean.class);
        setSkipScreenshotMethod.setAccessible(true);
    }

    private void loadSystemHiddenPackages(SharedPreferences prefs) {
        // 根据 packages 键是否存在判断启用
        if (!prefs.contains("packages")) {
            systemHideEnabled = false;
            systemHiddenPackages = Collections.emptySet();
            return;
        }

        systemHideEnabled = true;
        Set<String> pkgs = prefs.getStringSet("packages", new HashSet<>());
        if (pkgs.isEmpty()) {
            systemHiddenPackages = Collections.emptySet();
            return;
        }

        Set<String> lowerPkgs = new HashSet<>();
        for (String p : pkgs) {
            if (p != null && !p.isEmpty()) {
                lowerPkgs.add(p.toLowerCase(Locale.ROOT));
            }
        }
        systemHiddenPackages = Collections.unmodifiableSet(lowerPkgs);
    }

    private void clearWindowHideCache() {
        synchronized (windowCacheLock) {
            windowHideCache.clear();
        }
    }

    private boolean checkWindowForHide(Object winState) {
        if (!systemHideEnabled) return false;
        Set<String> hidden = systemHiddenPackages;
        if (hidden.isEmpty() || winState == null) return false;

        Boolean cached;
        synchronized (windowCacheLock) {
            cached = windowHideCache.get(winState);
        }
        if (cached != null) return cached;

        boolean hide = false;
        try {
            String pkg = (String) getOwningPackageMethod.invoke(winState);
            if (pkg != null && hidden.contains(pkg.toLowerCase(Locale.ROOT))) {
                hide = true;
            } else {
                Object tag = getWindowTagMethod.invoke(winState);
                if (tag instanceof CharSequence) {
                    hide = "FlexibleTaskMenu".contentEquals((CharSequence) tag);
                }
            }
        } catch (Throwable ignored) {
        }

        synchronized (windowCacheLock) {
            windowHideCache.put(winState, hide);
        }
        return hide;
    }

    @SuppressLint("PrivateApi")
    private void initAppReflection(ClassLoader cl) throws Exception {
        if (sAppReflectionReady) return;
        synchronized (appLock) {
            if (sAppReflectionReady) return;

            Class<?> vriClass = Class.forName("android.view.ViewRootImpl", false, cl);
            String[] candidates = {"mSurfaceControl", "mSurface", "mLeash", "mSurfaceControlLocked"};
            for (String fieldName : candidates) {
                try {
                    Field f = vriClass.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    sAppSurfaceControlField = f;
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }

            sAppSurfaceControlClass = Class.forName("android.view.SurfaceControl", false, cl);
            sAppSurfaceControlIsValid = sAppSurfaceControlClass.getDeclaredMethod("isValid");
            sAppSurfaceControlIsValid.setAccessible(true);

            Class<?> txnClass = Class.forName("android.view.SurfaceControl$Transaction", false, cl);
            sAppTxnConstructor = txnClass.getDeclaredConstructor();
            sAppTxnConstructor.setAccessible(true);
            sAppTxnApply = txnClass.getDeclaredMethod("apply");
            sAppTxnApply.setAccessible(true);
            sAppTxnClose = txnClass.getDeclaredMethod("close");
            sAppTxnClose.setAccessible(true);

            try {
                sAppTxnSetSkipScreenshot = txnClass.getDeclaredMethod("setSkipScreenshot", sAppSurfaceControlClass, boolean.class);
                sAppTxnSetSkipScreenshot.setAccessible(true);
            } catch (Throwable ignored) {
                sAppTxnSetSkipScreenshot = null;
            }
            try {
                sAppTxnSetSkipScreenshotLegacy = txnClass.getDeclaredMethod("setSkipScreenshot", boolean.class);
                sAppTxnSetSkipScreenshotLegacy.setAccessible(true);
            } catch (Throwable ignored) {
                sAppTxnSetSkipScreenshotLegacy = null;
            }
            try {
                sAppTxnSetSecure = txnClass.getDeclaredMethod("setSecure", sAppSurfaceControlClass, boolean.class);
                sAppTxnSetSecure.setAccessible(true);
            } catch (Throwable ignored) {
                sAppTxnSetSecure = null;
            }

            sAppReflectionReady = true;
        }
    }

    @SuppressLint("PrivateApi")
    private void installWindowManagerHook(ClassLoader cl) throws Exception {
        Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal", false, cl);
        for (Method method : wmgClass.getDeclaredMethods()) {
            final String name = Objects.requireNonNull(method).getName();
            if (!"addView".equals(name) && !"updateViewLayout".equals(name)) continue;
            try {
                hook(method)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            ProcessConfig config = currentConfig;
                            if (!needsWindowManagerHook(config)) return chain.proceed();
                            for (Object arg : chain.getArgs()) {
                                if (arg instanceof WindowManager.LayoutParams) {
                                    modifyLayoutParams((WindowManager.LayoutParams) arg, config);
                                    break;
                                }
                            }
                            return chain.proceed();
                        });
            } catch (Throwable t) {
                log(Log.WARN, TAG, "Failed to hook " + name + ": " + t.getMessage());
            }
        }
    }

    @SuppressLint("WrongConstant")
    private void modifyLayoutParams(WindowManager.LayoutParams lp, ProcessConfig config) {
        if (config.features.contains("FLAG_DIM_BEHIND_0")) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            lp.dimAmount = 0f;
        }
        if (config.features.contains("show_wallpaper")) {
            lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
        }
        if (config.features.contains("magic_flags")) {
            int magicFlags = 0x40000 | 0x200 | 0x20 | 0x8 | 0x20000;
            lp.flags |= magicFlags;
        }
        if (config.windowTitle != null) {
            try {
                lp.setTitle(config.windowTitle);
            } catch (Throwable ignored) {
            }
        }
    }

    @SuppressLint("PrivateApi")
    private void installAntiScreenshotHook(ClassLoader cl) throws Exception {
        Class<?> vriClass = Class.forName("android.view.ViewRootImpl", false, cl);
        for (Method method : vriClass.getDeclaredMethods()) {
            final String name = Objects.requireNonNull(method).getName();
            if (!"setView".equals(name) && !"relayoutWindow".equals(name) && !"performTraversals".equals(name))
                continue;
            hook(method)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        ProcessConfig config = currentConfig;
                        if (!config.features.contains("enable_skip_screenshot"))
                            return chain.proceed();
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
        }
    }

    private void applySecure(Object vri) {
        ProcessConfig config = currentConfig;
        if (!config.features.contains("enable_skip_screenshot")) return;
        if (!sAppReflectionReady || vri == null || secureApplied.contains(vri)) return;

        Object sc = getValidSurface(vri);
        if (sc == null) return;

        Object txn = null;
        try {
            txn = sAppTxnConstructor.newInstance();
            boolean applied = false;
            if (sAppTxnSetSkipScreenshot != null) {
                try {
                    sAppTxnSetSkipScreenshot.invoke(txn, sc, true);
                    applied = true;
                } catch (Throwable ignored) {
                }
            }
            if (!applied && sAppTxnSetSkipScreenshotLegacy != null) {
                try {
                    sAppTxnSetSkipScreenshotLegacy.invoke(txn, true);
                    applied = true;
                } catch (Throwable ignored) {
                }
            }
            if (!applied && sAppTxnSetSecure != null) {
                try {
                    sAppTxnSetSecure.invoke(txn, sc, true);
                    applied = true;
                } catch (Throwable ignored) {
                }
            }
            if (applied) {
                sAppTxnApply.invoke(txn);
                secureApplied.add(vri);
            }
        } catch (Throwable ignored) {
        } finally {
            if (txn != null) {
                try {
                    sAppTxnClose.invoke(txn);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private Object getValidSurface(Object vri) {
        if (sAppSurfaceControlField == null || sAppSurfaceControlClass == null) return null;
        try {
            Object sc = sAppSurfaceControlField.get(vri);
            if (sc != null && sAppSurfaceControlClass.isInstance(sc) && Boolean.TRUE.equals(sAppSurfaceControlIsValid.invoke(sc))) {
                return sc;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void installHideRecentsHook() throws Exception {
        Class<?> activityClass = Activity.class;
        Method onCreate = activityClass.getDeclaredMethod("onCreate", Bundle.class);
        hook(onCreate)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    ProcessConfig config = currentConfig;
                    if (!config.features.contains("hide_recent_card")) return result;

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
                    } catch (Throwable ignored) {
                    }
                    return result;
                });
    }

    private static final class ProcessConfig {
        static final ProcessConfig EMPTY = new ProcessConfig(Collections.emptySet(), null);
        final Set<String> features;
        final String windowTitle;

        ProcessConfig(Set<String> features, String windowTitle) {
            this.features = Collections.unmodifiableSet(features);
            this.windowTitle = windowTitle;
        }
    }
}