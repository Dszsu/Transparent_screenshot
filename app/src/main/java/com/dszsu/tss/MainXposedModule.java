package com.dszsu.tss;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.SharedPreferences;
import android.util.Log;
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

    private final Set<String> enabledFeatures = Collections.synchronizedSet(new HashSet<>());
    private volatile String windowTitle = null;

    private static volatile boolean sHooksInstalled = false;
    private static final Object sLock = new Object();

    private static volatile Field sSurfaceControlField = null;
    private static final String[] SC_CANDIDATE_FIELDS = { "mSurfaceControl", "mSurface", "mLeash", "mSurfaceControlLocked" };
    private static volatile Constructor<?> sTxnConstructor = null;
    private static volatile Method sTxnSetSkipScreenshot = null;
    private static volatile Method sTxnSetSkipScreenshotLegacy = null;
    private static volatile Method sTxnSetSecure = null;
    private static volatile Method sTxnApply = null;
    private static volatile Method sTxnClose = null;
    private static volatile Class<?> sScClass = null;
    private static volatile Method sScIsValid = null;
    private static volatile boolean sCacheReady = false;

    private final Set<Object> secureApplied = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String pkg = param.getPackageName();
        log(Log.INFO, TAG, "Module injected into " + pkg);

        loadConfig(pkg);

        if (!sHooksInstalled) {
            synchronized (sLock) {
                if (!sHooksInstalled) {
                    try {
                        initReflectionCache(param.getClassLoader());
                        installWindowManagerHook(param);

                        if (enabledFeatures.contains("enable_skip_screenshot")) {
                            installAntiScreenshotHook(param);
                            log(Log.INFO, TAG, "Anti-screenshot hook installed (explicitly enabled)");
                        } else {
                            log(Log.INFO, TAG, "Anti-screenshot hook NOT installed");
                        }

                        if (enabledFeatures.contains("hide_recent_card")) {
                            installHideRecentsHook();
                        }

                        sHooksInstalled = true;
                        log(Log.INFO, TAG, "All hooks initialized for process");
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "Failed to install hooks: " + t.getMessage());
                    }
                }
            }
        }
    }

    private void loadConfig(String packageName) {
        synchronized (enabledFeatures) {
            enabledFeatures.clear();
            windowTitle = null;
        }
        try {
            SharedPreferences prefs = getRemotePreferences(packageName.toLowerCase());
            if (prefs.getAll().isEmpty()) {
                log(Log.INFO, TAG, "[" + packageName + "] No remote config");
                return;
            }

            Set<String> features = new HashSet<>();
            if (prefs.contains("enable_skip_screenshot")) features.add("enable_skip_screenshot");
            if (prefs.contains("FLAG_DIM_BEHIND_0")) features.add("FLAG_DIM_BEHIND_0");
            if (prefs.contains("window_no_focus")) features.add("window_no_focus");
            if (prefs.contains("magic_flags")) features.add("magic_flags");
            if (prefs.contains("hide_recent_card")) features.add("hide_recent_card");

            String titleValue = prefs.getString("window_title", null);
            String finalTitle = null;
            boolean titleFromGlobal = false;
            if (titleValue != null) {
                if (titleValue.equals("$global")) {
                    SharedPreferences globalPrefs = getRemotePreferences("global");
                    finalTitle = globalPrefs.getString("title", null);
                    titleFromGlobal = true;
                } else {
                    finalTitle = titleValue;
                }
            }

            synchronized (enabledFeatures) {
                enabledFeatures.addAll(features);
                windowTitle = (finalTitle != null && !finalTitle.isEmpty()) ? finalTitle : null;
            }

            // 输出配置日志（框架日志）
            StringBuilder sb = new StringBuilder("[" + packageName + "] Config: features=" + features);
            if (windowTitle != null) {
                sb.append(", title=").append(windowTitle);
                if (titleFromGlobal) sb.append(" (from global)");
            } else if (titleValue != null) {
                sb.append(", title disabled (global empty)");
            } else {
                sb.append(", title not set");
            }
            log(Log.INFO, TAG, sb.toString());

        } catch (Throwable t) {
            log(Log.ERROR, TAG, "[" + packageName + "] Config load error: " + t.getMessage());
        }
    }

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
                    log(Log.WARN, TAG, "Failed to hook " + name + ": " + t.getMessage());
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
        if (enabledFeatures.contains("window_no_focus")) {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        }
        if (enabledFeatures.contains("magic_flags")) {
            lp.flags |= 0x2000 | 0x100000 | 0x40000 | 0x20000 | 0x1028;
        }
        if (windowTitle != null) {
            try {
                lp.setTitle(windowTitle);
            } catch (Throwable ignored) {}
        }
    }

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
                    log(Log.WARN, TAG, "Failed to hook " + name + " for anti-screenshot: " + t.getMessage());
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
                try { sTxnSetSkipScreenshot.invoke(txn, sc, true); applied = true; } catch (Throwable t) {
                    log(Log.WARN, TAG, "setSkipScreenshot(SurfaceControl, boolean) failed: " + t.getMessage());
                }
            }
            if (!applied && sTxnSetSkipScreenshotLegacy != null) {
                try { sTxnSetSkipScreenshotLegacy.invoke(txn, true); applied = true; } catch (Throwable t) {
                    log(Log.WARN, TAG, "setSkipScreenshot(boolean) failed: " + t.getMessage());
                }
            }
            if (!applied && sTxnSetSecure != null) {
                try { sTxnSetSecure.invoke(txn, sc, true); applied = true; } catch (Throwable t) {
                    log(Log.WARN, TAG, "setSecure failed: " + t.getMessage());
                }
            }

            if (applied) {
                sTxnApply.invoke(txn);
                secureApplied.add(vri);
            } else {
                log(Log.WARN, TAG, "No usable method to set skip screenshot");
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Transaction error: " + t.getMessage());
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
            if (sc != null && sScClass.isInstance(sc) && isSurfaceValid(sc)) {
                return sc;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isSurfaceValid(Object sc) {
        if (sScIsValid == null) return false;
        try {
            return Boolean.TRUE.equals(sScIsValid.invoke(sc));
        } catch (Throwable e) {
            return false;
        }
    }

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
                    } catch (Throwable t) {
                        log(Log.WARN, TAG, "Hide recents failed: " + t.getMessage());
                    }
                    return null;
                });
    }
}