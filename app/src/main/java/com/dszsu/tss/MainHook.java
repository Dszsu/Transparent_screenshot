package com.dszsu.tss;

import android.os.Build;
import android.view.WindowManager;

import java.util.IdentityHashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "SpeechAssistSecure";

    private static final Map<Object, Boolean> secureApplied =
            new IdentityHashMap<>();

    private static final Map<Object, Object> surfaceCache =
            new IdentityHashMap<>();

    
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (pkg == null) return;
        if (pkg.startsWith("android") || pkg.startsWith("com.android.systemui")) return;

        try {
            hookViewRootImpl(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook error in " + pkg + ": " + t);
        }
    }

    private void hookViewRootImpl(final XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> vriClass =
                XposedHelpers.findClass("android.view.ViewRootImpl", lpparam.classLoader);

        XposedBridge.hookAllMethods(vriClass, "relayoutWindow", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                handleDimming(param.thisObject);
            }
        });

        XposedBridge.hookAllMethods(vriClass, "performTraversals", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                handleSecureOnce(param.thisObject, lpparam.classLoader);
            }
        });
    }

    private void handleDimming(Object vri) {
        try {
            WindowManager.LayoutParams lp =
                    (WindowManager.LayoutParams) XposedHelpers.getObjectField(vri, "mWindowAttributes");
            if (lp != null && (lp.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                lp.dimAmount = 0f;
            }
        } catch (Throwable ignored) {}
    }

    private void handleSecureOnce(Object vri, ClassLoader cl) {
        try {
            Object sc = resolveSurface(vri);
            if (sc == null) return;

            synchronized (secureApplied) {
                if (secureApplied.containsKey(sc)) return;
                secureApplied.put(sc, Boolean.TRUE);
            }

            if (!(boolean) XposedHelpers.callMethod(sc, "isValid")) return;

            Class<?> txnClass =
                    XposedHelpers.findClass("android.view.SurfaceControl$Transaction", cl);
            Object txn = XposedHelpers.newInstance(txnClass);

            boolean applied = false;

            try {
                XposedHelpers.callMethod(txn, "setSkipScreenshot", sc, true);
                applied = true;
            } catch (Throwable ignored) {}

            if (!applied && Build.VERSION.SDK_INT < 33) {
                try {
                    XposedHelpers.callMethod(txn, "setSecure", sc, true);
                } catch (Throwable ignored) {}
            }

            XposedHelpers.callMethod(txn, "apply");

        } catch (Throwable ignored) {}
    }

    private Object resolveSurface(Object vri) {
        synchronized (surfaceCache) {
            Object cached = surfaceCache.get(vri);
            if (cached != null) return cached;
        }

        Object sc = null;
        String[] fields = {
                "mSurfaceControl",
                "mLeash",
                "mSurfaceControlLocked",
                "mSurface"
        };

        for (String f : fields) {
            try {
                sc = XposedHelpers.getObjectField(vri, f);
                if (sc != null) break;
            } catch (Throwable ignored) {}
        }

        if (sc != null) {
            synchronized (surfaceCache) {
                surfaceCache.put(vri, sc);
            }
        }
        return sc;
    }
}
