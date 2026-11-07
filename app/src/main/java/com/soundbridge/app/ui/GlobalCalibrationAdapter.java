package com.soundbridge.app.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.soundbridge.app.databinding.ItemGlobalCalibrationBinding;
import com.soundbridge.app.model.GlobalCalibrationProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for managing active and inactive global sound calibrations.
 */
public class GlobalCalibrationAdapter extends RecyclerView.Adapter<GlobalCalibrationAdapter.CalibrationViewHolder> {

    public interface OnCalibrationActionListener {
        void onToggleActive(GlobalCalibrationProfile profile, boolean active);
        void onStrengthen(GlobalCalibrationProfile profile);
        void onEdit(GlobalCalibrationProfile profile);
        void onDelete(GlobalCalibrationProfile profile);
    }

    private final List<GlobalCalibrationProfile> fullList = new ArrayList<>();
    private final List<GlobalCalibrationProfile> displayedList = new ArrayList<>();
    private final OnCalibrationActionListener listener;
    private String currentQuery = "";
    private int statusFilter = 0; // 0 = All, 1 = Active, 2 = Inactive

    public GlobalCalibrationAdapter(OnCalibrationActionListener listener) {
        this.listener = listener;
    }

    public void setCalibrations(List<GlobalCalibrationProfile> profiles) {
        fullList.clear();
        if (profiles != null) {
            fullList.addAll(profiles);
        }
        applyFilters();
    }

    public void setFilterStatus(int status) {
        this.statusFilter = status;
        applyFilters();
    }

    public void setSearchQuery(String query) {
        this.currentQuery = query == null ? "" : query.trim().toLowerCase(Locale.US);
        applyFilters();
    }

    private void applyFilters() {
        displayedList.clear();
        for (GlobalCalibrationProfile p : fullList) {
            if (statusFilter == 1 && !p.isActive()) continue;
            if (statusFilter == 2 && p.isActive()) continue;

            if (!currentQuery.isEmpty()) {
                boolean matchCanonical = p.getCanonicalLabel() != null
                        && p.getCanonicalLabel().toLowerCase(Locale.US).contains(currentQuery);
                boolean matchOriginal = p.getOriginalPrediction() != null
                        && p.getOriginalPrediction().toLowerCase(Locale.US).contains(currentQuery);
                if (!matchCanonical && !matchOriginal) {
                    continue;
                }
            }
            displayedList.add(p);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CalibrationViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemGlobalCalibrationBinding binding = ItemGlobalCalibrationBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        );
        return new CalibrationViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull CalibrationViewHolder holder, int position) {
        holder.bind(displayedList.get(position));
    }

    @Override
    public int getItemCount() {
        return displayedList.size();
    }

    class CalibrationViewHolder extends RecyclerView.ViewHolder {
        private final ItemGlobalCalibrationBinding binding;

        CalibrationViewHolder(ItemGlobalCalibrationBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(final GlobalCalibrationProfile profile) {
            binding.tvCanonicalLabel.setText(profile.getCanonicalLabel());
            String orig = profile.getOriginalPrediction();
            if (orig != null && !orig.isEmpty()) {
                binding.tvOriginalLabel.setText("Original: " + orig);
            } else {
                binding.tvOriginalLabel.setText("Direct Calibration");
            }

            int count = profile.getExemplarCount();
            binding.tvExemplarCount.setText(count == 1 ? "1 exemplar" : count + " exemplars");
            binding.tvThreshold.setText(String.format(Locale.US, "Threshold: %.2f", profile.getSimilarityThreshold()));

            // Active Switch
            binding.switchActive.setOnCheckedChangeListener(null);
            binding.switchActive.setChecked(profile.isActive());
            binding.switchActive.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (listener != null) {
                    listener.onToggleActive(profile, isChecked);
                }
            });

            binding.btnStrengthen.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onStrengthen(profile);
                }
            });

            binding.btnEdit.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onEdit(profile);
                }
            });

            binding.btnDelete.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDelete(profile);
                }
            });
        }
    }
}

