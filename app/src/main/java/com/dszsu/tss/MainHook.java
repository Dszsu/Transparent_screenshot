package com.dszsu.tss;

import android.view.WindowManager;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "SpeechAssistSecure";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (pkg == null) return;
        
        if (pkg.startsWith("android") || pkg.startsWith("com.android.systemui")) return;

        XposedBridge.log(TAG + ": Hooking package: " + pkg);

        try {
            hookViewRootImpl(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook Error for " + pkg + ": " + t);
        }
    }

    private void hookViewRootImpl(final XC_LoadPackage.LoadPackageParam lpparam) {
        final Class<?> vriClass = XposedHelpers.findClass("android.view.ViewRootImpl", lpparam.classLoader);

        
        XposedBridge.hookAllMethods(vriClass, "relayoutWindow", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                
                handleDimming(param.thisObject);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                
                handleSecure(param.thisObject, lpparam.classLoader);
            }
        });

        
        XposedBridge.hookAllMethods(vriClass, "performTraversals", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                
                handleDimming(param.thisObject);
                handleSecure(param.thisObject, lpparam.classLoader);
            }
        });
    }

    
    private void handleDimming(Object vri) {
        try {
            
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) XposedHelpers.getObjectField(vri, "mWindowAttributes");
            if (lp != null && (lp.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                lp.dimAmount = 0f;
            }
        } catch (Throwable ignored) {}
    }

    
    private void handleSecure(Object vri, ClassLoader classLoader) {
        try {
            Object sc = findSurfaceControl(vri);
            if (sc == null || !(boolean)XposedHelpers.callMethod(sc, "isValid")) return;

            
            Class<?> txnClass = XposedHelpers.findClass("android.view.SurfaceControl$Transaction", classLoader);
            Object txn = XposedHelpers.newInstance(txnClass);

            
            try {
                
                XposedHelpers.callMethod(txn, "setSkipScreenshot", sc, true);
            } catch (Throwable t1) {
                try {
                    
                    XposedHelpers.callMethod(txn, "setSkipScreenshot", true);
                } catch (Throwable t2) {
                    try {
                        
                        XposedHelpers.callMethod(txn, "setSecure", sc, true);
                    } catch (Throwable ignored) {}
                }
            }

            
            XposedHelpers.callMethod(txn, "apply");

        } catch (Throwable t) {
            
        }
    }

    
    private Object findSurfaceControl(Object vri) {
        String[] fields = {"mSurfaceControl", "mLeash", "mSurfaceControlLocked", "mSurface"};
        for (String field : fields) {
            try {
                Object sc = XposedHelpers.getObjectField(vri, field);
                if (sc != null) return sc;
            } catch (Throwable ignored) {}
        }
        return null;
    }
}

