package com.dszsu.tss;

import android.graphics.drawable.Drawable;

public class AppInfo {
    private final String label;
    private final String packageName;
    private Drawable icon;
    private boolean isInScope;
    private boolean hasConfig;
    private boolean isSystemCritical;

    public AppInfo(String label, String packageName, Drawable icon, boolean isInScope, boolean hasConfig,
                   boolean isSystemCritical) {
        this.label = label;
        this.packageName = packageName;
        this.icon = icon;
        this.isInScope = isInScope;
        this.hasConfig = hasConfig;
        this.isSystemCritical = isSystemCritical;
    }

    public String getLabel() { return label; }
    public String getPackageName() { return packageName; }
    public Drawable getIcon() { return icon; }
    public boolean isInScope() { return isInScope; }
    public boolean isShowConfig() { return hasConfig; }
    public void setHasConfig(boolean hasConfig) { this.hasConfig = hasConfig; }
    public void setIcon(Drawable icon) { this.icon = icon; }
    public boolean isSystemCritical() { return isSystemCritical; }
}