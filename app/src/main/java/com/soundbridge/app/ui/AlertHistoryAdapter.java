package com.soundbridge.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.soundbridge.app.R;
import com.soundbridge.app.databinding.ItemAlertHistoryBinding;
import com.soundbridge.app.model.AlertEvent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Day/Night theme-aware RecyclerView Adapter for the Alert History timeline.
 */
public class AlertHistoryAdapter extends RecyclerView.Adapter<AlertHistoryAdapter.AlertViewHolder> {

    public interface OnAlertClickListener {
        void onAlertClick(AlertEvent alert);
        void onAlertDeleteClick(AlertEvent alert);
    }

    private final List<AlertEvent> items = new ArrayList<>();
    private final OnAlertClickListener listener;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US);

    public AlertHistoryAdapter(OnAlertClickListener listener) {
        this.listener = listener;
    }

    public void submitList(List<AlertEvent> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AlertViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAlertHistoryBinding binding = ItemAlertHistoryBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        );
        return new AlertViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull AlertViewHolder holder, int position) {
        holder.bind(items.get(position), listener, dateFormat);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class AlertViewHolder extends RecyclerView.ViewHolder {
        private final ItemAlertHistoryBinding binding;

        AlertViewHolder(ItemAlertHistoryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(final AlertEvent alert, final OnAlertClickListener listener, SimpleDateFormat df) {
            Context ctx = binding.getRoot().getContext();

            binding.tvAlertLabel.setText(alert.getDisplayTitle());
            binding.tvAlertTimestamp.setText(df.format(new Date(alert.getTimestampMs())));
            binding.tvAlertConfidence.setText(String.format(Locale.US, "%d%% match", alert.getConfidencePercent()));
            binding.tvAlertSourceBadge.setText("• " + alert.getReadableCategory());

            int iconRes;
            int colorRes;
            if (AlertEvent.CATEGORY_ALARM.equals(alert.getCategory())) {
                iconRes = R.drawable.ic_alarm_urgent;
                colorRes = R.color.alert_urgent_red;
            } else if (AlertEvent.CATEGORY_KNOCK.equals(alert.getCategory())) {
                iconRes = R.drawable.ic_knock;
                colorRes = R.color.alert_warning_amber;
            } else if (AlertEvent.CATEGORY_APPLIANCE_BEEP.equals(alert.getCategory())) {
                iconRes = R.drawable.ic_appliance_beep;
                colorRes = R.color.accent_teal;
            } else if (alert.isCustom()) {
                iconRes = R.drawable.ic_custom_wave;
                colorRes = R.color.accent_teal;
            } else {
                iconRes = R.drawable.ic_doorbell;
                colorRes = R.color.primary_blue;
            }

            int resolvedColor = ContextCompat.getColor(ctx, colorRes);
            binding.ivItemCategoryIcon.setImageResource(iconRes);
            ImageViewCompat.setImageTintList(
                    binding.ivItemCategoryIcon,
                    ColorStateList.valueOf(resolvedColor)
            );
            binding.tvAlertConfidence.setTextColor(resolvedColor);

            binding.getRoot().setOnClickListener(v -> {
                if (listener != null) listener.onAlertClick(alert);
            });
            binding.btnDeleteAlertItem.setOnClickListener(v -> {
                if (listener != null) listener.onAlertDeleteClick(alert);
            });
        }
    }
}
