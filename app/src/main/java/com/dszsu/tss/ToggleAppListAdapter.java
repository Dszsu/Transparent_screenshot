package com.dszsu.tss;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.dszsu.tss.databinding.ItemSystemHideBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ToggleAppListAdapter extends RecyclerView.Adapter<ToggleAppListAdapter.VH> {

    private final PackageManager packageManager;
    private final Drawable defaultIcon;
    private final OnToggleListener listener;
    private final AppIconLoader iconLoader = AppIconLoader.get();
    private final CardBackgrounds backgrounds;

    private final ExecutorService diffExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<AppInfo> currentList = new ArrayList<>();
    private Set<String> selected = new HashSet<>();
    private long submitGeneration = 0L;

    ToggleAppListAdapter(Context context, PackageManager pm, Drawable defaultIcon,
                         OnToggleListener listener) {
        this.packageManager = pm;
        this.defaultIcon = defaultIcon;
        this.listener = listener;
        this.backgrounds = new CardBackgrounds(context);
        setHasStableIds(true);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemSystemHideBinding b = ItemSystemHideBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        AppInfo app = currentList.get(position);
        String normalizedPkg = app.getNormalizedPackageName();
        holder.b.tvLabel.setText(app.getLabel());
        holder.b.tvPackage.setText(app.getPackageName());

        holder.b.switchEnabled.setOnCheckedChangeListener(null);
        holder.b.switchEnabled.setChecked(selected.contains(normalizedPkg));
        holder.b.switchEnabled.setOnCheckedChangeListener(
                (v, checked) -> listener.onToggle(app.getPackageName(), checked));

        holder.b.getRoot().setBackground(backgrounds.get(position, getItemCount()));
        bindIcon(holder, app.getPackageName());
    }

    private void bindIcon(VH holder, String pkg) {
        holder.b.ivIcon.setImageDrawable(defaultIcon);
        iconLoader.load(packageManager, pkg, icon -> {

            if (icon != null && pkg.equals(holder.b.tvPackage.getText().toString())) {
                holder.b.ivIcon.setImageDrawable(icon);
            }
        });
    }

    @Override
    public long getItemId(int position) {
        return currentList.get(position).getNormalizedPackageName().hashCode();
    }

    @Override
    public int getItemCount() {
        return currentList.size();
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        holder.b.switchEnabled.setOnCheckedChangeListener(null);
        holder.b.ivIcon.setImageDrawable(null);
    }

    void submitList(@NonNull List<AppInfo> newList, @NonNull Set<String> newSelected) {
        final long generation = ++submitGeneration;
        if (currentList.isEmpty()) {
            currentList.clear();
            currentList.addAll(newList);
            this.selected = new HashSet<>(newSelected);
            notifyDataSetChanged();
            return;
        }
        final List<AppInfo> base = new ArrayList<>(currentList);
        diffExecutor.execute(() -> {
            final DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return base.size();
                }

                @Override
                public int getNewListSize() {
                    return newList.size();
                }

                @Override
                public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                    return base.get(oldItemPosition).getNormalizedPackageName()
                            .equals(newList.get(newItemPosition).getNormalizedPackageName());
                }

                @Override
                public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                    AppInfo o = base.get(oldItemPosition);
                    AppInfo n = newList.get(newItemPosition);

                    return o.getNormalizedPackageName().equals(n.getNormalizedPackageName())
                            && o.getLabel().equals(n.getLabel())
                            && visualRole(oldItemPosition, base.size())
                                    == visualRole(newItemPosition, newList.size());
                }
            });
            mainHandler.post(() -> {
                if (generation != submitGeneration) return;
                currentList.clear();
                currentList.addAll(newList);
                selected = new HashSet<>(newSelected);
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

    @SuppressLint("NotifyDataSetChanged")
    void refreshItem(String packageName) {
        String target = packageName == null ? "" : packageName.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < currentList.size(); i++) {
            if (currentList.get(i).getNormalizedPackageName().equals(target)) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    interface OnToggleListener {
        void onToggle(String packageName, boolean enabled);
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemSystemHideBinding b;

        VH(ItemSystemHideBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
