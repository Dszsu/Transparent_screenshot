package com.dszsu.tss;

import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.dszsu.tss.databinding.ItemAppBinding;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {

    private static final Set<String> VIRTUAL_SYSTEM_PACKAGES = Collections.singleton("system");

    private List<AppInfo> list = Collections.emptyList();
    private final OnItemClickListener listener;
    private final LruCache<String, Drawable> iconCache = new LruCache<>(50);
    private final Executor executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PackageManager packageManager;
    private final Drawable defaultIcon;

    public AppAdapter(PackageManager pm, Drawable defaultIcon, OnItemClickListener listener) {
        this.packageManager = pm;
        this.defaultIcon = defaultIcon;
        this.listener = listener;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setData(List<AppInfo> newList) {
        list = newList == null ? Collections.emptyList() : newList;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAppBinding binding = ItemAppBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppInfo app = list.get(position);
        holder.binding.tvPackage.setText(app.getPackageName());

        String displayName = app.getLabel();
        String suffix = null;
        int color = ContextCompat.getColor(holder.itemView.getContext(), R.color.m3_on_surface);

        if ("system".equals(app.getPackageName())) {
            displayName = holder.itemView.getContext().getString(R.string.system_framework_label);
            if (app.isSystemCritical()) {
                suffix = holder.itemView.getContext().getString(R.string.feature_disabled_suffix);
                color = Color.RED;
            } else {
                if (app.isSystemUIEnhanced()) {
                    displayName += " " + holder.itemView.getContext().getString(R.string.system_ui_enhancement_label);
                }
                color = Color.BLUE;
            }
        } else if (app.isSystemCritical()) {
            suffix = holder.itemView.getContext().getString(R.string.must_remove_scope_suffix);
            color = Color.RED;
        } else if (!app.isInScope() && app.hasConfig()) {
            suffix = holder.itemView.getContext().getString(R.string.not_in_scope_suffix);
            color = Color.GREEN;
        }

        if (suffix != null) {
            holder.binding.tvLabel.setText(displayName + suffix);
        } else {
            holder.binding.tvLabel.setText(displayName);
        }
        holder.binding.tvLabel.setTextColor(color);

        // 分段胶囊：首项顶部/末项底部大圆角，其余小圆角（KernelSU Segmented 风格）
        float density = holder.itemView.getResources().getDisplayMetrics().density;
        float outer = 16f * density;
        float inner = 4f * density;
        boolean first = position == 0;
        boolean last = position == getItemCount() - 1;
        float tl = first ? outer : inner;
        float tr = first ? outer : inner;
        float br = last ? outer : inner;
        float bl = last ? outer : inner;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(com.google.android.material.color.MaterialColors.getColor(
                holder.itemView.getContext(),
                com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFFECE6F0));
        bg.setCornerRadii(new float[]{tl, tl, tr, tr, br, br, bl, bl});
        holder.binding.getRoot().setBackground(bg);

        if (app.isSystemCritical()) {
            loadCriticalIcon(app, holder);
        } else {
            loadNormalIcon(app, holder);
        }

        holder.binding.getRoot().setOnClickListener(v -> {
            if (app.isSystemCritical()) return;
            if (listener != null) listener.onItemClick(app);
        });
    }

    private void loadCriticalIcon(AppInfo app, ViewHolder holder) {
        String pkg = app.getPackageName().toLowerCase(Locale.ROOT);
        String iconSourcePkg = VIRTUAL_SYSTEM_PACKAGES.contains(pkg) ? "android" : pkg;
        Drawable cached = iconCache.get(iconSourcePkg);
        if (cached != null) {
            holder.binding.ivIcon.setImageDrawable(cached);
        } else {
            final String targetPkg = iconSourcePkg;
            final String currentPkg = holder.binding.tvPackage.getText().toString().toLowerCase(Locale.ROOT);
            final boolean expectAndroid = VIRTUAL_SYSTEM_PACKAGES.contains(currentPkg) && "android".equals(targetPkg);
            holder.binding.ivIcon.setImageDrawable(defaultIcon);
            executor.execute(() -> {
                try {
                    Drawable icon = packageManager.getApplicationIcon(targetPkg);
                    iconCache.put(targetPkg, icon);
                    if (targetPkg.equals(currentPkg) || expectAndroid) {
                        mainHandler.post(() -> holder.binding.ivIcon.setImageDrawable(icon));
                    }
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            });
        }
    }

    private void loadNormalIcon(AppInfo app, ViewHolder holder) {
        String pkg = app.getPackageName();
        Drawable cached = iconCache.get(pkg);
        if (cached != null) {
            holder.binding.ivIcon.setImageDrawable(cached);
        } else {
            final String targetPkg = pkg;
            final String currentPkg = holder.binding.tvPackage.getText().toString();
            holder.binding.ivIcon.setImageDrawable(defaultIcon);
            executor.execute(() -> {
                try {
                    Drawable icon = packageManager.getApplicationIcon(targetPkg);
                    iconCache.put(targetPkg, icon);
                    if (targetPkg.equals(currentPkg)) {
                        mainHandler.post(() -> holder.binding.ivIcon.setImageDrawable(icon));
                    }
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return list.size();
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        holder.binding.ivIcon.setImageDrawable(null);
    }

    public interface OnItemClickListener {
        void onItemClick(AppInfo app);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final ItemAppBinding binding;

        ViewHolder(ItemAppBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}