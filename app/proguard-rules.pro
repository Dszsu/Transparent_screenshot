-optimizationpasses 5
-dontobfuscate  
-allowaccessmodification
-mergeinterfacesaggressively

-keep class de.robv.android.xposed.** { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }
-keep class * implements de.robv.android.xposed.IXposedHookInitPackageResources { *; }

-keep class com.dszsu.mou.MainHook { *; }
-keepclassmembers class com.dszsu.mou.MainHook {
    public *;
}

-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
-keepclassmembers class * extends android.content.Context {
    public void *(android.view.View);
    public void *(android.content.Intent);
}

-keepclassmembers class **.R$* {
    public static <fields>;
}

-keepclassmembers class android.view.ViewRootImpl {
    java.lang.Object mSurfaceControl;
}
-keepclassmembers class android.view.SurfaceControl {
    boolean isValid();
}

-keepattributes Signature
-keepattributes InnerClasses
-keepattributes *Annotation*

-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application
