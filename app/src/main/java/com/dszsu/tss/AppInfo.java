package com.dszsu.tss;

public class AppInfo {
    private final String label;
    private final String packageName;
    private final boolean isInScope;
    private boolean hasConfig;
    private final boolean isSystemCritical;

    public AppInfo(String label, String packageName, boolean isInScope, boolean hasConfig,
                   boolean isSystemCritical) {
        this.label = label;
        this.packageName = packageName;
        this.isInScope = isInScope;
        this.hasConfig = hasConfig;
        this.isSystemCritical = isSystemCritical;
    }

    public String getLabel() { return label; }
    public String getPackageName() { return packageName; }
    public boolean isInScope() { return isInScope; }
    public boolean isShowConfig() { return hasConfig; }
    public void setHasConfig(boolean hasConfig) { this.hasConfig = hasConfig; }
    public boolean isSystemCritical() { return isSystemCritical; }
}