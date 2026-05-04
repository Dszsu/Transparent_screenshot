package com.dszsu.tss;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.dszsu.tss.databinding.ItemAppBinding;  // 如果使用 ViewBinding
import java.util.List;

public class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {

    // 如果未使用 ViewBinding，可以导入 item_app.xml 的控件 id 并通过 findViewById 绑定

    private List<AppInfo> apps;
    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(AppInfo app);
    }

    public AppAdapter(List<AppInfo> apps, OnItemClickListener listener) {
        this.apps = apps;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // 使用 ViewBinding
        ItemAppBinding binding = ItemAppBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppInfo app = apps.get(position);
        holder.binding.ivIcon.setImageDrawable(app.getIcon());
        holder.binding.tvLabel.setText(app.getLabel());
        holder.binding.tvPackage.setText(app.getPackageName());
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(app));
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updateList(List<AppInfo> newList) {
        this.apps = newList;
        notifyDataSetChanged();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ItemAppBinding binding;  // 如果不用 ViewBinding，这里替换为具体的控件字段

        ViewHolder(ItemAppBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}