package com.dszsu.tss;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.dszsu.tss.databinding.ItemAppBinding;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {

    private final AsyncListDiffer<AppInfo> differ = new AsyncListDiffer<>(this, DIFF_CALLBACK);
    private final OnItemClickListener listener;
    private final LruCache<String, Drawable> iconCache = new LruCache<>(50);
    private final Executor executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PackageManager packageManager;
    private final Drawable defaultIcon;

    private static final Set<String> VIRTUAL_SYSTEM_PACKAGES = new HashSet<>();
    static {
        VIRTUAL_SYSTEM_PACKAGES.add("system");
    }

    public interface OnItemClickListener {
        void onItemClick(AppInfo app);
    }

    public AppAdapter(PackageManager pm, Drawable defaultIcon, OnItemClickListener listener) {
        this.packageManager = pm;
        this.defaultIcon = defaultIcon;
        this.listener = listener;
    }

    public void submitList(List<AppInfo> list) {
        differ.submitList(list);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAppBinding binding = ItemAppBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppInfo app = differ.getCurrentList().get(position);
        holder.binding.tvPackage.setText(app.getPackageName());

        // 获取主题默认文字颜色
        TypedValue typedValue = new TypedValue();
        holder.itemView.getContext().getTheme().resolveAttribute(android.R.attr.textColorPrimary, typedValue, true);
        int defaultTextColor = typedValue.data;

        String displayName = app.getLabel();

        if (app.isSystemCritical()) {
            displayName = app.getLabel() + "（必须取消作用域）";
            holder.binding.tvLabel.setText(displayName);
            holder.binding.tvLabel.setTextColor(android.graphics.Color.RED);
            loadCriticalIcon(app, holder);
        } else if (!app.isInScope() && app.isShowConfig()) {
            displayName = app.getLabel() + "（不在作用域中）";
            holder.binding.tvLabel.setText(displayName);
            holder.binding.tvLabel.setTextColor(android.graphics.Color.GREEN);
            loadNormalIcon(app, holder);
        } else {
            holder.binding.tvLabel.setText(displayName);
            holder.binding.tvLabel.setTextColor(defaultTextColor);
            loadNormalIcon(app, holder);
        }

        holder.binding.getRoot().setOnClickListener(v -> {
            if (app.isSystemCritical()) return;
            if (listener != null) listener.onItemClick(app);
        });
    }

    private void loadCriticalIcon(AppInfo app, ViewHolder holder) {
        String pkg = app.getPackageName().toLowerCase();
        String iconSourcePkg = VIRTUAL_SYSTEM_PACKAGES.contains(pkg) ? "android" : pkg;
        Drawable cached = iconCache.get(iconSourcePkg);
        if (cached != null) {
            holder.binding.ivIcon.setImageDrawable(cached);
        } else {
            holder.binding.ivIcon.setImageDrawable(defaultIcon);
            executor.execute(() -> {
                try {
                    Drawable icon = packageManager.getApplicationIcon(iconSourcePkg);
                    iconCache.put(iconSourcePkg, icon);
                    String currentPkg = holder.binding.tvPackage.getText().toString().toLowerCase();
                    if (iconSourcePkg.equals(currentPkg) || (VIRTUAL_SYSTEM_PACKAGES.contains(currentPkg) && "android".equals(iconSourcePkg))) {
                        mainHandler.post(() -> holder.binding.ivIcon.setImageDrawable(icon));
                    }
                } catch (PackageManager.NameNotFoundException ignore) {
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
            holder.binding.ivIcon.setImageDrawable(defaultIcon);
            executor.execute(() -> {
                try {
                    Drawable icon = packageManager.getApplicationIcon(pkg);
                    iconCache.put(pkg, icon);
                    if (pkg.equals(holder.binding.tvPackage.getText().toString())) {
                        mainHandler.post(() -> holder.binding.ivIcon.setImageDrawable(icon));
                    }
                } catch (PackageManager.NameNotFoundException ignore) {
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return differ.getCurrentList().size();
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        holder.binding.ivIcon.setImageDrawable(null);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final ItemAppBinding binding;
        ViewHolder(ItemAppBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static final DiffUtil.ItemCallback<AppInfo> DIFF_CALLBACK = new DiffUtil.ItemCallback<AppInfo>() {
        @Override
        public boolean areItemsTheSame(@NonNull AppInfo oldItem, @NonNull AppInfo newItem) {
            return oldItem.getPackageName().equals(newItem.getPackageName());
        }

        @Override
        public boolean areContentsTheSame(@NonNull AppInfo oldItem, @NonNull AppInfo newItem) {
            return oldItem.getLabel().equals(newItem.getLabel())
                    && oldItem.isInScope() == newItem.isInScope()
                    && oldItem.isShowConfig() == newItem.isShowConfig()
                    && oldItem.isSystemCritical() == newItem.isSystemCritical();
        }
    };
}