
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-dontpreverify
-repackageclasses



-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*


-keep class com.dszsu.tss.MainXposedModule {
    public <init>();
    *;
}


-keep class com.dszsu.tss.App {
    public <init>();
    *;
}


-keep class com.dszsu.tss.MainActivity { *; }
-keep class com.dszsu.tss.ConfigActivity { *; }


-keep class com.dszsu.tss.AppAdapter { *; }
-keep class com.dszsu.tss.AppAdapter$ViewHolder { *; }


-keep class com.dszsu.tss.AppInfo { *; }


-keep class * implements androidx.viewbinding.ViewBinding {
    *;
}


-keep class * extends io.github.libxposed.api.XposedModule {
    <init>();
    *;
}


-keep class io.github.libxposed.service.XposedService { *; }
-keep class io.github.libxposed.service.XposedServiceHelper { *; }



-optimizations !class/merging/*,!code/inlining/*
-keepclassmembers,allowoptimization class * {
    @android.webkit.JavascriptInterface <methods>;
}


-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}