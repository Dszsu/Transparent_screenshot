package com.dszsu.tss;

import java.util.Locale;

public class AppInfo {
    private final String label;
    private final String normalizedLabel;
    private final String packageName;
    private final String normalizedPackageName;
    private final boolean isInScope;
    private final boolean isSystemApp;
    private boolean hasConfig;
    private boolean isSystemCritical;

    public AppInfo(String label, String packageName, boolean isInScope, boolean hasConfig,
                   boolean isSystemCritical, boolean isSystemApp) {
        this.label = label;
        this.normalizedLabel = label == null ? "" : label.toLowerCase(Locale.ROOT);
        this.packageName = packageName;
        this.normalizedPackageName = packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
        this.isInScope = isInScope;
        this.hasConfig = hasConfig;
        this.isSystemCritical = isSystemCritical;
        this.isSystemApp = isSystemApp;
    }

    public String getLabel() {
        return label;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getNormalizedLabel() {
        return normalizedLabel;
    }

    public String getNormalizedPackageName() {
        return normalizedPackageName;
    }

    public boolean isInScope() {
        return isInScope;
    }

    public boolean hasConfig() {
        return hasConfig;
    }

    public void setHasConfig(boolean hasConfig) {
        this.hasConfig = hasConfig;
    }

    public boolean isSystemCritical() {
        return isSystemCritical;
    }

    public void setSystemCritical(boolean systemCritical) {
        this.isSystemCritical = systemCritical;
    }

    public boolean isSystemApp() {
        return isSystemApp;
    }
}