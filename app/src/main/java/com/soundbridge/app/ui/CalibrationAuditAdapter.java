package com.soundbridge.app.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.soundbridge.app.R;
import com.soundbridge.app.databinding.ItemCalibrationAuditBinding;
import com.soundbridge.app.model.CalibrationAuditRecord;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for displaying immutable calibration audit log entries.
 */
public class CalibrationAuditAdapter extends RecyclerView.Adapter<CalibrationAuditAdapter.AuditViewHolder> {

    private final List<CalibrationAuditRecord> records = new ArrayList<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US);

    public void setRecords(List<CalibrationAuditRecord> newRecords) {
        records.clear();
        if (newRecords != null) {
            records.addAll(newRecords);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AuditViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemCalibrationAuditBinding binding = ItemCalibrationAuditBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        );
        return new AuditViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull AuditViewHolder holder, int position) {
        holder.bind(records.get(position));
    }

    @Override
    public int getItemCount() {
        return records.size();
    }

    class AuditViewHolder extends RecyclerView.ViewHolder {
        private final ItemCalibrationAuditBinding binding;

        AuditViewHolder(ItemCalibrationAuditBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(CalibrationAuditRecord record) {
            Context ctx = binding.getRoot().getContext();

            binding.tvAuditAction.setText(record.getAction());
            String action = record.getAction() != null ? record.getAction().toUpperCase(Locale.US) : "";
            if (action.contains("DELETE") || action.contains("DEACTIVATE")) {
                binding.tvAuditAction.setTextColor(ContextCompat.getColor(ctx, R.color.alert_urgent_red));
            } else if (action.contains("PUBLISH") || action.contains("CREATE")) {
                binding.tvAuditAction.setTextColor(ContextCompat.getColor(ctx, R.color.status_monitoring_active));
            } else {
                binding.tvAuditAction.setTextColor(ContextCompat.getColor(ctx, R.color.primary_blue));
            }

            String target = record.getCanonicalLabel() != null ? record.getCanonicalLabel() : "Calibration";
            if (record.getCalibrationId() != null) {
                target += " (" + record.getCalibrationId() + ")";
            }
            binding.tvAuditTarget.setText(target);

            binding.tvAuditTimestamp.setText(dateFormat.format(new Date(record.getTimestampMs())));

            String admin = record.getAdminUid() != null ? record.getAdminUid() : "Admin";
            String reason = record.getReason() != null && !record.getReason().isEmpty()
                    ? record.getReason() : "No notes provided";
            binding.tvAuditAdminAndReason.setText("Admin: " + admin + "\nNotes: " + reason);
        }
    }
}

