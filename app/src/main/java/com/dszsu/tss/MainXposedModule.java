package com.dszsu.tss;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

@SuppressWarnings({"FieldCanBeLocal"})
public class MainXposedModule extends XposedModule {

    private static final String TAG = "TransScreenshot";
    private static final String SYSTEM_HIDE_GROUP = "system_hide";


    private static final int FLAG_STEALTH_OVERLAY =
            0x00000010   // FLAG_NOT_FOCUSABLE
                    | 0x00000200 // FLAG_NOT_TOUCH_MODAL
                    | 0x00040000;// FLAG_WATCH_OUTSIDE_TOUCH

    private static volatile boolean sAppHooksInstalled = false;
    private static volatile boolean sSystemHooksInstalled = false;
    private final Object appLock = new Object();
    private static volatile boolean sAppCacheReady = false;
    private static volatile Field sSurfaceControlField;
    private static volatile Class<?> sScClass;
    private static volatile Method sScIsValid;
    private static volatile Constructor<?> sTxnConstructor;

    private volatile boolean systemHideEnabled = false;
    private volatile Set<String> systemHiddenPackages = Collections.emptySet();
    private static volatile Method sTxnSetSkipScreenshot;
    private static volatile Method sTxnSetSkipScreenshotLegacy;
    private static volatile Method sTxnSetSecure;
    private static volatile Method sTxnApply;
    private static volatile Method sTxnClose;
    private final Object systemLock = new Object();
    private final Set<String> enabledFeatures = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, String> processNameCache = new ConcurrentHashMap<>();
    private final Map<String, SharedPreferences.OnSharedPreferenceChangeListener> appPrefsListeners
            = new ConcurrentHashMap<>();
    private final WeakHashMap<Object, Boolean> windowHideCache = new WeakHashMap<>();
    private final Set<Object> systemSecureApplied = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Object> taskSecureApplied = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Object> secureApplied = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Activity> recentsExcluded = Collections.newSetFromMap(new WeakHashMap<>());
    private volatile String windowTitle = null;
    private int systemTxnMethodType = 0;
    private volatile int cacheVersion = 0;
    private final SharedPreferences.OnSharedPreferenceChangeListener systemPrefsListener =
            (prefs, key) -> {
                if (!"packages".equals(key)) return;
                loadSystemHiddenPackages(prefs);
                //noinspection NonAtomicOperationOnVolatileField
                cacheVersion++;
                log(Log.INFO, TAG, "System hide packages updated");
            };

    private Class<?> windowStateClass;
    private int localCacheVersion = -1;
    private Class<?> windowStateAnimatorClass;
    private Class<?> windowSurfaceControllerClass;
    private Class<?> transactionClass;
    private Class<?> surfaceControlClass;
    @Nullable
    private Class<?> taskClass;
    @Nullable
    private Field windowStateAttrsField;

    private Method getOwningPackageMethod;
    private Method getWindowTagMethod;
    @Nullable
    private Field layoutParamsPackageNameField;
    @Nullable
    private Method getTaskMethod;
    @Nullable
    private Method taskGetSurfaceControlMethod;
    private Field animatorWinField;
    @Nullable
    private Field animatorSurfaceControllerField;
    @Nullable
    private Field surfaceControllerSurfaceField;
    @Nullable
    private Field winStateAnimatorField;
    @Nullable
    private Field windowStateScField;
    private Constructor<?> systemTxnConstructor;
    @Nullable
    private Method systemTxnSetSkipScreenshot;
    @Nullable
    private Method systemTxnSetSecure;
    private Method systemTxnApply;
    private Method systemTxnClose;

    private static Method findMethodInHierarchy(Class<?> cls, String name, Class<?>... paramTypes)
            throws NoSuchMethodException {
        if (cls == null) throw new NullPointerException("cls must not be null");
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(cls.getName() + "#" + name);
    }

    private static Field findFieldInHierarchy(Class<?> cls, String name) throws NoSuchFieldException {
        if (cls == null) throw new NullPointerException("cls must not be null");
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(cls.getName() + "#" + name);
    }

    @Override
    public void onSystemServerStarting(
            @NonNull XposedModuleInterface.SystemServerStartingParam param) {
        super.onSystemServerStarting(param);
        try {
            SharedPreferences sysPrefs = getRemotePreferences(SYSTEM_HIDE_GROUP);
            loadSystemHiddenPackages(sysPrefs);
            sysPrefs.registerOnSharedPreferenceChangeListener(systemPrefsListener);
            installSystemHooks(param.getClassLoader());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to init system hooks: " + t);
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String configPackage = resolveConfigPackage(param.getPackageName());
        loadConfig(configPackage);

        if (!sAppHooksInstalled) {
            synchronized (appLock) {
                if (!sAppHooksInstalled) {
                    try {
                        initAppReflection(param.getClassLoader());
                        if (needsLayoutParamChanges()) {
                            installWindowManagerHook(param);
                        }
                        if (enabledFeatures.contains("enable_skip_screenshot")) {
                            installAntiScreenshotHook(param);
                        }
                        if (enabledFeatures.contains("hide_recent_card")) {
                            installHideRecentsHook();
                        }
                        sAppHooksInstalled = true;
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "App hook init failed: " + t);
                    }
                }
            }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private String getProcessName() {
        try {
            @SuppressLint("PrivateApi") Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getDeclaredMethod("currentActivityThread").invoke(null);
            return (String) atClass.getDeclaredMethod("getProcessName").invoke(at);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String resolveConfigPackage(String fallbackPkg) {
        String processName = getProcessName();
        String key = processName != null ? processName : fallbackPkg;
        return processNameCache.computeIfAbsent(key, k -> {
            int idx = k.indexOf(':');
            return idx > 0 ? k.substring(0, idx) : k;
        });
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
            String resolvedTitle = null;
            if ("$global".equals(titleValue)) {
                resolvedTitle = globalPrefs.getString("title", null);
            } else if (titleValue != null && !titleValue.isEmpty()) {
                resolvedTitle = titleValue;
            }

            synchronized (enabledFeatures) {
                enabledFeatures.clear();
                enabledFeatures.addAll(features);
            }
            windowTitle = resolvedTitle;

            String lowerPkg = configPackage.toLowerCase(Locale.ROOT);
            if (!appPrefsListeners.containsKey(lowerPkg)) {
                SharedPreferences.OnSharedPreferenceChangeListener listener =
                        (p, key) -> loadConfig(configPackage);
                prefs.registerOnSharedPreferenceChangeListener(listener);
                appPrefsListeners.put(lowerPkg, listener);
            }

            log(Log.INFO, TAG, "Config[" + configPackage + "] features=" + features
                    + " title=" + resolvedTitle);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Config load failed [" + configPackage + "]: " + t);
        }
    }

    private boolean needsLayoutParamChanges() {
        return windowTitle != null
                || enabledFeatures.contains("FLAG_DIM_BEHIND_0")
                || enabledFeatures.contains("show_wallpaper")
                || enabledFeatures.contains("magic_flags");
    }

    @SuppressLint("PrivateApi")
    private void installSystemHooks(ClassLoader cl) throws Exception {
        if (sSystemHooksInstalled) return;
        synchronized (systemLock) {
            if (sSystemHooksInstalled) return;

            initSystemReflection(cl);

            installCreateSurfaceHook();
            if (methodExists(windowStateAnimatorClass, "performShowLocked")) {
                installShowFallbackHook();
            }
            if (windowStateScField != null
                    && methodExists(windowStateClass,
                    "prepareWindowToDisplayDuringRelayout", boolean.class)) {
                installHighVersionHook();
            }
            installDestroySurfaceHook();

            sSystemHooksInstalled = true;
            log(Log.INFO, TAG, "System hooks installed, txnType=" + systemTxnMethodType);
        }
    }

    @SuppressLint("PrivateApi")
    private void initSystemReflection(ClassLoader cl) throws Exception {
        windowStateClass = Class.forName("com.android.server.wm.WindowState", false, cl);
        windowStateAnimatorClass = Class.forName("com.android.server.wm.WindowStateAnimator", false, cl);

        try {
            windowSurfaceControllerClass = Class.forName("com.android.server.wm.WindowSurfaceController", false, cl);
        } catch (ClassNotFoundException e) {
            log(Log.WARN, TAG, "WindowSurfaceController not found");
            windowSurfaceControllerClass = null;
        }

        surfaceControlClass = Class.forName("android.view.SurfaceControl", false, cl);
        transactionClass = Class.forName("android.view.SurfaceControl$Transaction", false, cl);

        try {
            taskClass = Class.forName("com.android.server.wm.Task", false, cl);
            getTaskMethod = findMethodInHierarchy(windowStateClass, "getTask");
            getTaskMethod.setAccessible(true);
            taskGetSurfaceControlMethod = findMethodInHierarchy(taskClass, "getSurfaceControl");
            taskGetSurfaceControlMethod.setAccessible(true);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Task reflection failed: " + t.getMessage());
            taskClass = null;
            getTaskMethod = null;
            taskGetSurfaceControlMethod = null;
        }

        try {
            getOwningPackageMethod = findMethodInHierarchy(windowStateClass, "getOwningPackage");
            getOwningPackageMethod.setAccessible(true);
        } catch (Throwable ignored) {
            getOwningPackageMethod = null;
        }

        getWindowTagMethod = findMethodInHierarchy(windowStateClass, "getWindowTag");
        getWindowTagMethod.setAccessible(true);

        animatorWinField = findFieldInHierarchy(windowStateAnimatorClass, "mWin");
        animatorWinField.setAccessible(true);

        try {
            animatorSurfaceControllerField =
                    findFieldInHierarchy(windowStateAnimatorClass, "mSurfaceController");
            animatorSurfaceControllerField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            log(Log.WARN, TAG, "mSurfaceController not found in WindowStateAnimator");
            animatorSurfaceControllerField = null;
        }

        if (windowSurfaceControllerClass != null) {
            try {
                surfaceControllerSurfaceField =
                        findFieldInHierarchy(windowSurfaceControllerClass, "mSurfaceControl");
                surfaceControllerSurfaceField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                log(Log.WARN, TAG, "mSurfaceControl not found in WindowSurfaceController");
                surfaceControllerSurfaceField = null;
            }
        } else {
            surfaceControllerSurfaceField = null;
        }

        try {
            winStateAnimatorField = findFieldInHierarchy(windowStateClass, "mWinAnimator");
            winStateAnimatorField.setAccessible(true);
        } catch (Throwable ignored) {
            winStateAnimatorField = null;
        }

        try {
            windowStateScField = findFieldInHierarchy(windowStateClass, "mSurfaceControl");
            windowStateScField.setAccessible(true);
        } catch (Throwable ignored) {
            windowStateScField = null;
        }

        try {
            windowStateAttrsField = findFieldInHierarchy(windowStateClass, "mAttrs");
            windowStateAttrsField.setAccessible(true);
            layoutParamsPackageNameField = WindowManager.LayoutParams.class.getField("packageName");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Direct field access for package name unavailable: " + t.getMessage());
            windowStateAttrsField = null;
            layoutParamsPackageNameField = null;
        }

        systemTxnConstructor = transactionClass.getDeclaredConstructor();
        systemTxnConstructor.setAccessible(true);
        systemTxnApply = transactionClass.getDeclaredMethod("apply");
        systemTxnApply.setAccessible(true);
        systemTxnClose = transactionClass.getDeclaredMethod("close");
        systemTxnClose.setAccessible(true);

        try {
            systemTxnSetSkipScreenshot = transactionClass.getDeclaredMethod(
                    "setSkipScreenshot", surfaceControlClass, boolean.class);
            systemTxnSetSkipScreenshot.setAccessible(true);
            systemTxnMethodType = 1;
        } catch (Throwable ignored) {
        }

        try {
            systemTxnSetSecure = transactionClass.getDeclaredMethod(
                    "setSecure", surfaceControlClass, boolean.class);
            systemTxnSetSecure.setAccessible(true);
            if (systemTxnMethodType == 0) systemTxnMethodType = 2;
        } catch (Throwable ignored) {
        }

        if (systemTxnMethodType == 0) {
            log(Log.WARN, TAG, "Neither setSkipScreenshot nor setSecure found");
        }
    }

    private void installCreateSurfaceHook() throws Exception {
        Method m = findMethodInHierarchy(windowStateAnimatorClass, "createSurfaceLocked");
        hook(m)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    applySkipScreenshotIfNeeded(chain.getThisObject());
                    return result;
                });
    }

    private void installShowFallbackHook() throws Exception {
        Method m = findMethodInHierarchy(windowStateAnimatorClass, "performShowLocked");
        hook(m)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    applySkipScreenshotIfNeeded(chain.getThisObject());
                    return result;
                });
    }

    private void installHighVersionHook() {
        try {
            Method m = findMethodInHierarchy(windowStateClass,
                    "prepareWindowToDisplayDuringRelayout", boolean.class);
            hook(m)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object winState = chain.getThisObject();
                        if (shouldHideWindow(winState)) {
                            try {
                                Object sc = null;
                                if (windowStateScField != null) {
                                    sc = windowStateScField.get(winState);
                                }
                                if (sc != null) applySkipScreenshot(sc);
                            } catch (Throwable ignored) {
                            }
                            applySkipScreenshotToTask(winState);
                        }
                        return result;
                    });
        } catch (NoSuchMethodException ignored) {
        }
    }

    private void installDestroySurfaceHook() {
        try {
            Method m = findMethodInHierarchy(windowStateAnimatorClass, "destroySurfaceLocked");
            hook(m)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object animator = chain.getThisObject();
                        Object sc = null;
                        Object winState = null;
                        try {
                            if (animatorSurfaceControllerField != null) {
                                Object ctrl = animatorSurfaceControllerField.get(animator);
                                if (ctrl != null && surfaceControllerSurfaceField != null) {
                                    sc = surfaceControllerSurfaceField.get(ctrl);
                                }
                            }
                            winState = animatorWinField.get(animator);
                        } catch (Throwable ignored) {
                        }

                        Object result = chain.proceed();

                        if (sc != null) systemSecureApplied.remove(sc);
                        if (winState != null) windowHideCache.remove(winState);
                        return result;
                    });
        } catch (Throwable ignored) {
        }
    }

    private void loadSystemHiddenPackages(SharedPreferences prefs) {
        if (!prefs.contains("packages")) {
            systemHideEnabled = false;
            systemHiddenPackages = Collections.emptySet();
            return;
        }
        Set<String> raw = prefs.getStringSet("packages", Collections.emptySet());
        Set<String> lower = new HashSet<>(raw.size() * 2);
        for (String p : raw) {
            if (p != null && !p.isEmpty()) lower.add(p.toLowerCase(Locale.ROOT));
        }
        systemHiddenPackages = Collections.unmodifiableSet(lower);
        systemHideEnabled = true;
    }

    private void applySkipScreenshotIfNeeded(Object animator) {
        if (!systemHideEnabled || animator == null) return;
        try {
            Object winState = animatorWinField.get(animator);
            if (winState == null || !shouldHideWindow(winState)) return;

            if (animatorSurfaceControllerField != null) {
                Object ctrl = animatorSurfaceControllerField.get(animator);
                if (ctrl != null) {
                    Object sc = null;
                    if (surfaceControllerSurfaceField != null) {
                        sc = surfaceControllerSurfaceField.get(ctrl);
                    }
                    if (sc == null && windowStateScField != null) {
                        sc = windowStateScField.get(winState);
                    }
                    if (sc != null) applySkipScreenshot(sc);
                }
            } else if (windowStateScField != null) {
                Object sc = windowStateScField.get(winState);
                if (sc != null) applySkipScreenshot(sc);
            }

            applySkipScreenshotToTask(winState);
        } catch (Throwable ignored) {
        }
    }

    private void applySkipScreenshotToTask(Object winState) {
        if (getTaskMethod == null || taskGetSurfaceControlMethod == null) return;
        try {
            Object task = getTaskMethod.invoke(winState);
            if (task == null || !taskSecureApplied.add(task)) return;
            Object taskSc = taskGetSurfaceControlMethod.invoke(task);
            if (taskSc != null) applySkipScreenshot(taskSc);
        } catch (Throwable ignored) {
        }
    }

    private void applySkipScreenshot(Object sc) {
        if (systemTxnMethodType == 0) return;
        if (!systemSecureApplied.add(sc)) return;

        Object txn = null;
        boolean success = false;
        try {
            txn = systemTxnConstructor.newInstance();
            if (systemTxnMethodType == 1) {
                if (systemTxnSetSkipScreenshot != null) {
                    systemTxnSetSkipScreenshot.invoke(txn, sc, true);
                }
            } else {
                if (systemTxnSetSecure != null) {
                    systemTxnSetSecure.invoke(txn, sc, true);
                }
            }
            systemTxnApply.invoke(txn);
            success = true;
        } catch (Throwable t) {
            if (systemTxnMethodType == 1 && systemTxnSetSecure != null) {
                log(Log.WARN, TAG, "setSkipScreenshot failed, falling back to setSecure");
                systemTxnMethodType = 2;
                try {
                    if (txn != null) {
                        systemTxnClose.invoke(txn);
                        txn = null;
                    }
                    txn = systemTxnConstructor.newInstance();
                    systemTxnSetSecure.invoke(txn, sc, true);
                    systemTxnApply.invoke(txn);
                    success = true;
                } catch (Throwable t2) {
                    log(Log.ERROR, TAG, "setSecure also failed: " + t2.getMessage());
                }
            } else {
                log(Log.ERROR, TAG, "applySkipScreenshot failed: " + t.getMessage());
            }
        } finally {
            if (txn != null) try {
                systemTxnClose.invoke(txn);
            } catch (Throwable ignored) {
            }
        }
        if (!success) systemSecureApplied.remove(sc);
    }

    private boolean shouldHideWindow(Object winState) {
        if (!systemHideEnabled || winState == null) return false;

        if (localCacheVersion != cacheVersion) {
            windowHideCache.clear();
            systemSecureApplied.clear();
            taskSecureApplied.clear();
            localCacheVersion = cacheVersion;
        }

        Boolean cached = windowHideCache.get(winState);
        if (cached != null) return cached;

        boolean hide = false;
        try {
            String pkg = null;

            if (windowStateAttrsField != null && layoutParamsPackageNameField != null) {
                try {
                    Object attrs = windowStateAttrsField.get(winState);
                    if (attrs != null) {
                        pkg = (String) layoutParamsPackageNameField.get(attrs);
                    }
                } catch (Throwable ignored) {
                }
            }

            if (pkg == null && getOwningPackageMethod != null) {
                pkg = (String) getOwningPackageMethod.invoke(winState);
            }

            if (pkg != null && systemHiddenPackages.contains(pkg.toLowerCase(Locale.ROOT))) {
                hide = true;
            } else {
                Object tag = getWindowTagMethod.invoke(winState);
                if (tag instanceof CharSequence
                        && "FlexibleTaskMenu".contentEquals((CharSequence) tag)) {
                    hide = true;
                }
            }
        } catch (Throwable ignored) {
        }

        windowHideCache.put(winState, hide);
        return hide;
    }

    @SuppressLint("PrivateApi")
    private void initAppReflection(ClassLoader cl) throws Exception {
        if (sAppCacheReady) return;
        synchronized (appLock) {
            if (sAppCacheReady) return;

            Class<?> vriClass = Class.forName("android.view.ViewRootImpl", false, cl);
            for (String name : new String[]{
                    "mSurfaceControl", "mSurface", "mLeash", "mSurfaceControlLocked"}) {
                try {
                    Field f = vriClass.getDeclaredField(name);
                    f.setAccessible(true);
                    sSurfaceControlField = f;
                    log(Log.DEBUG, TAG, "VRI SC field: " + name);
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (sSurfaceControlField == null) {
                log(Log.WARN, TAG, "No SurfaceControl field found in ViewRootImpl");
            }

            sScClass = Class.forName("android.view.SurfaceControl", false, cl);
            sScIsValid = sScClass.getDeclaredMethod("isValid");
            sScIsValid.setAccessible(true);

            Class<?> txnClass = Class.forName(
                    "android.view.SurfaceControl$Transaction", false, cl);
            sTxnConstructor = txnClass.getDeclaredConstructor();
            sTxnConstructor.setAccessible(true);
            sTxnApply = txnClass.getDeclaredMethod("apply");
            sTxnApply.setAccessible(true);
            sTxnClose = txnClass.getDeclaredMethod("close");
            sTxnClose.setAccessible(true);

            try {
                sTxnSetSkipScreenshot = txnClass.getDeclaredMethod(
                        "setSkipScreenshot", sScClass, boolean.class);
                sTxnSetSkipScreenshot.setAccessible(true);
            } catch (Throwable ignored) {
            }
            try {
                sTxnSetSkipScreenshotLegacy = txnClass.getDeclaredMethod(
                        "setSkipScreenshot", boolean.class);
                sTxnSetSkipScreenshotLegacy.setAccessible(true);
            } catch (Throwable ignored) {
            }
            try {
                sTxnSetSecure = txnClass.getDeclaredMethod(
                        "setSecure", sScClass, boolean.class);
                sTxnSetSecure.setAccessible(true);
            } catch (Throwable ignored) {
            }

            sAppCacheReady = true;
        }
    }

    @SuppressLint("PrivateApi")
    private void installWindowManagerHook(PackageReadyParam param) throws Exception {
        Class<?> wmg = Class.forName(
                "android.view.WindowManagerGlobal", false, param.getClassLoader());
        for (Method method : wmg.getDeclaredMethods()) {
            String name = method.getName();
            if (!"addView".equals(name) && !"updateViewLayout".equals(name)) continue;
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
        }
    }

    @SuppressLint("WrongConstant")
    private void modifyLayoutParams(WindowManager.LayoutParams lp) {
        if (enabledFeatures.contains("FLAG_DIM_BEHIND_0")) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            lp.dimAmount = 0f;
        }
        if (enabledFeatures.contains("show_wallpaper")) {
            lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
        }
        if (enabledFeatures.contains("magic_flags")) {
            lp.flags |= FLAG_STEALTH_OVERLAY;
        }
        if (windowTitle != null) {
            try {
                lp.setTitle(windowTitle);
            } catch (Throwable ignored) {
            }
        }
    }

    @SuppressLint("PrivateApi")
    private void installAntiScreenshotHook(PackageReadyParam param) throws Exception {
        Class<?> vriClass = Class.forName(
                "android.view.ViewRootImpl", false, param.getClassLoader());
        for (Method m : vriClass.getDeclaredMethods()) {
            final String name = m.getName();
            if (!"setView".equals(name) && !"relayoutWindow".equals(name)) continue;

            hook(m)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!enabledFeatures.contains("enable_skip_screenshot")) {
                            return chain.proceed();
                        }
                        Object vri = chain.getThisObject();
                        if ("setView".equals(name)) {
                            chain.proceed();
                            applySecure(vri);
                            return null;
                        } else { // relayoutWindow
                            secureApplied.remove(vri);
                            Object result = chain.proceed();
                            applySecure(vri);
                            return result;
                        }
                    });
        }
    }

    private void applySecure(Object vri) {
        if (!sAppCacheReady || secureApplied.contains(vri)) return;
        Object sc = getValidSurface(vri);
        if (sc == null) return;

        Object txn = null;
        try {
            txn = sTxnConstructor.newInstance();
            boolean applied = false;
            if (sTxnSetSkipScreenshot != null) {
                try {
                    sTxnSetSkipScreenshot.invoke(txn, sc, true);
                    applied = true;
                } catch (Throwable ignored) {
                }
            }
            if (!applied && sTxnSetSkipScreenshotLegacy != null) {
                try {
                    sTxnSetSkipScreenshotLegacy.invoke(txn, true);
                    applied = true;
                } catch (Throwable ignored) {
                }
            }
            if (!applied && sTxnSetSecure != null) {
                try {
                    sTxnSetSecure.invoke(txn, sc, true);
                    applied = true;
                } catch (Throwable ignored) {
                }
            }
            if (applied) {
                sTxnApply.invoke(txn);
                secureApplied.add(vri);
            }
        } catch (Throwable ignored) {
        } finally {
            if (txn != null) try {
                sTxnClose.invoke(txn);
            } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void installHideRecentsHook() throws Exception {
        hook(Activity.class.getDeclaredMethod("onCreate", Bundle.class))
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    chain.proceed();
                    if (!enabledFeatures.contains("hide_recent_card")) return null;
                    Activity act = (Activity) chain.getThisObject();
                    if (!recentsExcluded.add(act)) return null;
                    try {
                        ActivityManager am = (ActivityManager)
                                act.getSystemService(Activity.ACTIVITY_SERVICE);
                        if (am == null) return null;
                        int myTaskId = act.getTaskId();
                        for (ActivityManager.AppTask task : am.getAppTasks()) {
                            if (task.getTaskInfo().taskId == myTaskId) {
                                task.setExcludeFromRecents(true);
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return null;
                });
    }

    private boolean methodExists(Class<?> cls, String name, Class<?>... params) {
        try {
            findMethodInHierarchy(cls, name, params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}