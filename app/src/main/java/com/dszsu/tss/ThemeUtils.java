package com.dszsu.tss;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

public final class ThemeUtils {

    private ThemeUtils() {
    }

    public static int cardColor(Context context) {
        return com.google.android.material.color.MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorSurfaceContainerHigh,
                0xFFFFFFFF);
    }

    public static int textColor(Context context) {
        return com.google.android.material.color.MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorOnSurface,
                0xFF1D1B20);
    }
}
