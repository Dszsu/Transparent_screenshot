package com.dszsu.tss;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable.ConstantState;

public final class CardBackgrounds {

    public static final int ROLE_SINGLE = 0;
    public static final int ROLE_FIRST = 1;
    public static final int ROLE_MIDDLE = 2;
    public static final int ROLE_LAST = 3;

    private final ConstantState[] states = new ConstantState[4];

    public CardBackgrounds(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        float outer = 16f * density;
        float inner = 4f * density;
        int color = ThemeUtils.cardColor(context);
        states[ROLE_SINGLE] = build(color, outer, outer, outer, outer);
        states[ROLE_FIRST] = build(color, outer, outer, inner, inner);
        states[ROLE_MIDDLE] = build(color, inner, inner, inner, inner);
        states[ROLE_LAST] = build(color, inner, inner, outer, outer);
    }

    private static ConstantState build(int color, float tl, float tr, float br, float bl) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadii(new float[]{tl, tl, tr, tr, br, br, bl, bl});
        return drawable.getConstantState();
    }

    public Drawable get(int position, int itemCount) {
        int role;
        if (itemCount <= 1) {
            role = ROLE_SINGLE;
        } else if (position == 0) {
            role = ROLE_FIRST;
        } else if (position == itemCount - 1) {
            role = ROLE_LAST;
        } else {
            role = ROLE_MIDDLE;
        }
        ConstantState state = states[role];
        return state != null ? state.newDrawable() : null;
    }
}
