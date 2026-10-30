package com.cleanmyphone.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class AppGridAdapter extends RecyclerView.Adapter<AppGridAdapter.ViewHolder> {
    private final Context context;
    private final List<AppModel> fullList = new ArrayList<>();
    private final List<AppModel> displayList = new ArrayList<>();

    public AppGridAdapter(Context context) {
        this.context = context;
    }

    public void setApps(List<AppModel> apps) {
        fullList.clear();
        fullList.addAll(apps);
        displayList.clear();
        displayList.addAll(apps);
        notifyDataSetChanged();
    }

    public void filter(String query) {
        displayList.clear();
        if (query == null || query.trim().isEmpty()) {
            displayList.addAll(fullList);
        } else {
            String lower = query.toLowerCase().trim();
            for (AppModel app : fullList) {
                if (app.getLabel().toLowerCase().contains(lower) || app.getPackageName().toLowerCase().contains(lower)) {
                    displayList.add(app);
                }
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_app, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppModel app = displayList.get(position);
        holder.tvName.setText(app.getLabel());
        holder.ivIcon.setImageDrawable(app.getIcon());

        holder.itemView.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                intent.setComponent(new ComponentName(app.getPackageName(), app.getActivityName()));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                context.startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(context, "Could not open " + app.getLabel(), Toast.LENGTH_SHORT).show();
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + app.getPackageName()));
                context.startActivity(intent);
            } catch (Exception ignored) {
            }
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return displayList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivIcon;
        final TextView tvName;

        ViewHolder(View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivAppIcon);
            tvName = itemView.findViewById(R.id.tvAppName);
        }
    }
}
