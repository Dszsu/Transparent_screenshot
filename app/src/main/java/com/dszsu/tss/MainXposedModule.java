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
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public class MainXposedModule extends XposedModule {

    //日志tag↓
    //private static final String TAG = "TransScreenshot";

    // Hook 去重（进程级）
    private static volatile boolean sHooksInstalled = false;
    // 反射缓存（防截屏）
    private static volatile Field sSurfaceControlField = null;

    // 配置标志位（无锁）
    private volatile boolean featureFlagDimBehind = false;
    private volatile boolean featureShowWallpaper = false;
    private volatile boolean featureMagicFlags = false;
    private volatile boolean featureEnableSkipScreenshot = false;
    private volatile boolean featureHideRecents = false;

    private static final Object sLock = new Object();
    private volatile String windowTitle = null;
    private volatile boolean needModifyLayout = false;
    private String lastLoadedPackage = null;

    // [已废弃] 进程名缓存，避免失败时反复反射（改为包名匹配后不再需要）
    // private volatile String cachedProcessName = null;
    // private volatile boolean processNameResolved = false;

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

    // 缓存 onCreate 方法
    private static volatile Method sActivityOnCreateMethod = null;

    private final Set<Object> secureApplied = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>())
    );
    private final Set<Activity> excludedActivities = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>())
    );

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String pkg = param.getPackageName();
        // log(android.util.Log.INFO, TAG, "Module injected into " + pkg);

        // [修改] 直接使用包名作为配置键，解决多进程应用部分界面不生效的问题
        // 旧逻辑（按进程名）已注释，如需回退请见下方说明
        // String configPackage = getProcessName();
        // if (configPackage == null) configPackage = pkg;
        String configPackage = pkg;  // 现在强制使用包名
        loadConfig(configPackage);

        if (!sHooksInstalled) {
            synchronized (sLock) {
                if (!sHooksInstalled) {
                    try {
                        initReflectionCache(param.getClassLoader());
                        installWindowManagerHook(param);

                        if (featureEnableSkipScreenshot) {
                            installAntiScreenshotHook(param);
                        }

                        if (featureHideRecents) {
                            installHideRecentsHook();
                        }

                        sHooksInstalled = true;
                        // log(android.util.Log.INFO, TAG, "All hooks initialized");
                    } catch (Throwable t) {
                        // log(android.util.Log.ERROR, TAG, "Failed to install hooks: " + t.getMessage());
                    }
                }
            }
        }
    }

    // [已废弃] 该方法在改为包名匹配后不再使用，保留以便回退
    // @SuppressLint("DiscouragedPrivateApi")
    // private String getProcessName() {
    //     if (processNameResolved) {
    //         return cachedProcessName;
    //     }
    //     processNameResolved = true; // 只尝试一次，避免反复反射
    //     try {
    //         @SuppressLint("PrivateApi") Class<?> atClass = Class.forName("android.app.ActivityThread");
    //         @SuppressLint("DiscouragedPrivateApi") Object at = atClass.getDeclaredMethod("currentActivityThread").invoke(null);
    //         cachedProcessName = (String) atClass.getDeclaredMethod("getProcessName").invoke(at);
    //         return cachedProcessName;
    //     } catch (Throwable t) {
    //         return null; // cachedProcessName 保持 null
    //     }
    // }

    private void loadConfig(String configPackage) {
        if (configPackage != null && configPackage.equals(lastLoadedPackage)) {
            return;
        }
        lastLoadedPackage = configPackage;

        // 重置标志
        featureFlagDimBehind = false;
        featureShowWallpaper = false;
        featureMagicFlags = false;
        featureEnableSkipScreenshot = false;
        featureHideRecents = false;
        windowTitle = null;
        needModifyLayout = false;

        try {
            SharedPreferences globalPrefs = getRemotePreferences("global");
            SharedPreferences prefs = getRemotePreferences(configPackage.toLowerCase());

            if (prefs.getAll().isEmpty()) {
                // log(android.util.Log.INFO, TAG, "[" + configPackage + "] No config");
                return;
            }

            if (prefs.contains("enable_skip_screenshot")) featureEnableSkipScreenshot = true;
            if (prefs.contains("FLAG_DIM_BEHIND_0")) featureFlagDimBehind = true;
            if (prefs.contains("show_wallpaper")) featureShowWallpaper = true;
            if (prefs.contains("magic_flags")) featureMagicFlags = true;
            if (prefs.contains("hide_recent_card")) featureHideRecents = true;

            String titleValue = prefs.getString("window_title", null);
            String finalTitle = null;
            if ("$global".equals(titleValue)) {
                if (globalPrefs != null) {
                    finalTitle = globalPrefs.getString("title", null);
                }
            } else if (titleValue != null) {
                finalTitle = titleValue;
            }

            windowTitle = (finalTitle != null && !finalTitle.isEmpty()) ? finalTitle : null;
            needModifyLayout = featureFlagDimBehind || featureShowWallpaper || featureMagicFlags || windowTitle != null;

            // log(android.util.Log.INFO, TAG, "[" + configPackage + "] features: " +
            //         "skipScreenshot=" + featureEnableSkipScreenshot +
            //         ", dimBehind=" + featureFlagDimBehind +
            //         ", wallpaper=" + featureShowWallpaper +
            //         ", magic=" + featureMagicFlags +
            //         ", hideRecents=" + featureHideRecents +
            //         ", title=" + windowTitle);

        } catch (Throwable t) {
            // log(android.util.Log.ERROR, TAG, "Config load error: " + t.getMessage());
        }
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
                } catch (NoSuchFieldException ignored) {
                }
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

            try {
                sTxnSetSkipScreenshot = txnClass.getDeclaredMethod("setSkipScreenshot", sScClass, boolean.class);
                sTxnSetSkipScreenshot.setAccessible(true);
            } catch (Throwable ignored) {
            }
            try {
                sTxnSetSkipScreenshotLegacy = txnClass.getDeclaredMethod("setSkipScreenshot", boolean.class);
                sTxnSetSkipScreenshotLegacy.setAccessible(true);
            } catch (Throwable ignored) {
            }
            try {
                sTxnSetSecure = txnClass.getDeclaredMethod("setSecure", sScClass, boolean.class);
                sTxnSetSecure.setAccessible(true);
            } catch (Throwable ignored) {
            }

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
                                if (needModifyLayout) {
                                    for (Object arg : chain.getArgs()) {
                                        if (arg instanceof WindowManager.LayoutParams) {
                                            modifyLayoutParams((WindowManager.LayoutParams) arg);
                                            break;
                                        }
                                    }
                                }
                                return chain.proceed();
                            });
                } catch (Throwable t) {
                    // log(android.util.Log.WARN, TAG, "Failed to hook " + name + ": " + t.getMessage());
                }
            }
        }
    }

    @SuppressLint("WrongConstant")
    private void modifyLayoutParams(WindowManager.LayoutParams lp) {
        if (featureFlagDimBehind) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            lp.dimAmount = 0f;
        }
        if (featureShowWallpaper) {
            lp.flags |= 0x100000;
        }
        if (featureMagicFlags) {
            lp.flags |= (0x40000 | 0x200 | 0x20 | 0x8 | 0x20000);
        }
        if (windowTitle != null) {
            try {
                lp.setTitle(windowTitle);
            } catch (Throwable ignored) {
            }
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
                    if ("setView".equals(name)) {
                        hook(m).setPriority(XposedInterface.PRIORITY_DEFAULT)
                                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                                .intercept(chain -> {
                                    chain.proceed();
                                    applySecure(chain.getThisObject());
                                    return null;
                                });
                    } else if ("relayoutWindow".equals(name)) {
                        hook(m).setPriority(XposedInterface.PRIORITY_DEFAULT)
                                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                                .intercept(chain -> {
                                    secureApplied.remove(chain.getThisObject());
                                    Object result = chain.proceed();
                                    applySecure(chain.getThisObject());
                                    return result;
                                });
                    } else { // performTraversals
                        hook(m).setPriority(XposedInterface.PRIORITY_DEFAULT)
                                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                                .intercept(chain -> {
                                    applySecure(chain.getThisObject());
                                    return chain.proceed();
                                });
                    }
                } catch (Throwable t) {
                    // log(android.util.Log.WARN, TAG, "Failed to hook " + name + " for anti-screenshot: " + t.getMessage());
                }
            }
        }
    }

    private void applySecure(Object vri) {
        if (!sCacheReady) return;

        if (!secureApplied.add(vri)) {
            return; // 已处理
        }

        Object sc = getValidSurface(vri);
        if (sc == null) {
            secureApplied.remove(vri);
            return;
        }

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
            } else {
                secureApplied.remove(vri);
            }
        } catch (Throwable ignored) {
            secureApplied.remove(vri);
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
        if (sActivityOnCreateMethod == null) {
            synchronized (sLock) {
                if (sActivityOnCreateMethod == null) {
                    sActivityOnCreateMethod = Activity.class.getDeclaredMethod("onCreate", android.os.Bundle.class);
                }
            }
        }

        hook(sActivityOnCreateMethod)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    chain.proceed();
                    Activity activity = (Activity) chain.getThisObject();
                    if (excludedActivities.add(activity)) {
                        try {
                            ActivityManager am = (ActivityManager) activity.getSystemService(Activity.ACTIVITY_SERVICE);
                            if (am != null) {
                                java.util.List<ActivityManager.AppTask> tasks = am.getAppTasks();
                                if (tasks != null) {
                                    for (ActivityManager.AppTask task : tasks) {
                                        if (task.getTaskInfo().taskId == activity.getTaskId()) {
                                            task.setExcludeFromRecents(true);
                                            break;
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                    return null;
                });
    }
}
