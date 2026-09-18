package com.dszsu.tss;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.dszsu.tss.databinding.ItemAppBinding;

import io.github.libxposed.service.XposedService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {

    private static final String VIRTUAL_SYSTEM = "system";
    private static final String ANDROID_PACKAGE = "android";

    private final List<AppInfo> list = new ArrayList<>();
    private final OnItemClickListener listener;
    private final AppIconLoader iconLoader = AppIconLoader.get();
    private final CardBackgrounds backgrounds;
    private final ExecutorService diffExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PackageManager packageManager;
    private final Drawable defaultIcon;
    private XposedService service;
    private long submitGeneration = 0L;

    public AppAdapter(Context context, PackageManager pm, Drawable defaultIcon,
                      OnItemClickListener listener) {
        this.packageManager = pm;
        this.defaultIcon = defaultIcon;
        this.listener = listener;
        this.backgrounds = new CardBackgrounds(context);
        setHasStableIds(true);
    }

    public void setService(XposedService service) {
        this.service = service;
    }

    public void setData(List<AppInfo> newList) {
        final List<AppInfo> incoming = newList == null ? Collections.emptyList() : newList;
        final long generation = ++submitGeneration;
        if (list.isEmpty()) {
            list.addAll(incoming);
            notifyDataSetChanged();
            return;
        }
        final List<AppInfo> base = new ArrayList<>(list);
        diffExecutor.execute(() -> {
            final DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return base.size();
                }

                @Override
                public int getNewListSize() {
                    return incoming.size();
                }

                @Override
                public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                    return base.get(oldItemPosition).getNormalizedPackageName()
                            .equals(incoming.get(newItemPosition).getNormalizedPackageName());
                }

                @Override
                public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                    AppInfo o = base.get(oldItemPosition);
                    AppInfo n = incoming.get(newItemPosition);

                    return o.getNormalizedPackageName().equals(n.getNormalizedPackageName())
                            && stringEquals(o.getLabel(), n.getLabel())
                            && o.isInScope() == n.isInScope()
                            && o.hasConfig() == n.hasConfig()
                            && o.isSystemCritical() == n.isSystemCritical()
                            && o.isSystemUIEnhanced() == n.isSystemUIEnhanced()
                            && visualRole(oldItemPosition, base.size())
                                    == visualRole(newItemPosition, incoming.size());
                }
            });
            mainHandler.post(() -> {
                if (generation != submitGeneration) return;
                list.clear();
                list.addAll(incoming);
                result.dispatchUpdatesTo(this);
            });
        });
    }

    private static int visualRole(int position, int count) {
        if (count <= 1) return 0;
        if (position == 0) return 1;
        if (position == count - 1) return 2;
        return 3;
    }

    private static boolean stringEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
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
        int color = ThemeUtils.textColor(holder.itemView.getContext());

        if (VIRTUAL_SYSTEM.equals(app.getPackageName())) {
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

        holder.binding.getRoot().setBackground(backgrounds.get(position, getItemCount()));

        bindIcon(holder, app);
        bindHookSwitch(app, holder);

        holder.binding.getRoot().setOnClickListener(v -> {
            if (app.isSystemCritical()) return;
            if (listener != null) listener.onItemClick(app);
        });
    }

    private void bindIcon(ViewHolder holder, AppInfo app) {

        String rawPkg = app.getPackageName();
        final String iconKey = VIRTUAL_SYSTEM.equals(rawPkg) ? ANDROID_PACKAGE : rawPkg;
        holder.iconKey = iconKey;
        holder.binding.ivIcon.setImageDrawable(defaultIcon);
        iconLoader.load(packageManager, iconKey, icon -> {
            if (icon != null && iconKey.equals(holder.iconKey)) {
                holder.binding.ivIcon.setImageDrawable(icon);
            }
        });
    }

    private void bindHookSwitch(AppInfo app, ViewHolder holder) {

        String pkg = app.getNormalizedPackageName();
        boolean special = VIRTUAL_SYSTEM.equals(app.getPackageName()) || app.isSystemCritical();
        holder.binding.switchHook.setVisibility(special ? View.GONE : View.VISIBLE);
        holder.binding.switchHook.setOnCheckedChangeListener(null);
        if (special) return;
        boolean disabled = false;
        if (service != null && !pkg.isEmpty()) {
            try {
                disabled = service.getRemotePreferences(pkg).contains("disable_hook");
            } catch (Throwable ignored) {

                disabled = false;
            }
        }
        holder.binding.switchHook.setChecked(!disabled);
        holder.binding.switchHook.setOnCheckedChangeListener((v, checked) -> {
            if (service == null || pkg.isEmpty()) return;
            try {
                if (checked) {
                    service.getRemotePreferences(pkg).edit().remove("disable_hook").apply();
                } else {
                    service.getRemotePreferences(pkg).edit().putBoolean("disable_hook", true).apply();
                }
            } catch (Throwable ignored) {
            }
        });
    }

    @Override
    public long getItemId(int position) {
        return list.get(position).getNormalizedPackageName().hashCode();
    }

    @Override
    public int getItemCount() {
        return list.size();
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        holder.binding.switchHook.setOnCheckedChangeListener(null);
        holder.binding.ivIcon.setImageDrawable(null);
        holder.iconKey = null;
    }

    public interface OnItemClickListener {
        void onItemClick(AppInfo app);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final ItemAppBinding binding;
        String iconKey;

        ViewHolder(ItemAppBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
