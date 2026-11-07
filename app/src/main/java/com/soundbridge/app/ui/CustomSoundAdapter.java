package com.soundbridge.app.ui;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.soundbridge.app.databinding.ItemCustomSoundBinding;
import com.soundbridge.app.model.CustomSoundProfile;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView Adapter for saved CustomSoundProfiles with clean, friendly descriptions.
 */
public class CustomSoundAdapter extends RecyclerView.Adapter<CustomSoundAdapter.CustomSoundViewHolder> {

    public interface OnCustomSoundActionListener {
        void onToggleEnabled(CustomSoundProfile profile, boolean enabled);
        void onAddMoreSample(CustomSoundProfile profile);
        void onPreviewSample(CustomSoundProfile profile);
        void onRenameSound(CustomSoundProfile profile);
        void onDeleteSound(CustomSoundProfile profile);
    }

    private final List<CustomSoundProfile> items = new ArrayList<>();
    private final OnCustomSoundActionListener listener;
    private final SimpleDateFormat df = new SimpleDateFormat("MMM d", Locale.US);

    public CustomSoundAdapter(OnCustomSoundActionListener listener) {
        this.listener = listener;
    }

    public void submitList(List<CustomSoundProfile> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CustomSoundViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemCustomSoundBinding binding = ItemCustomSoundBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        );
        return new CustomSoundViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull CustomSoundViewHolder holder, int position) {
        holder.bind(items.get(position), listener, df);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class CustomSoundViewHolder extends RecyclerView.ViewHolder {
        private final ItemCustomSoundBinding binding;

        CustomSoundViewHolder(ItemCustomSoundBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(final CustomSoundProfile profile, final OnCustomSoundActionListener listener, SimpleDateFormat df) {
            binding.tvCustomSoundName.setText(profile.getName());
            int count = profile.getSampleCount();
            String sampleSummary = count < 2
                    ? "1 recording • Add 1–2 more for best accuracy"
                    : count + " recordings • High accuracy";
            String stateLabel = profile.isEnabled() ? "Active" : "Paused";
            binding.tvCustomSoundMeta.setText(String.format(
                    Locale.US,
                    "%s • %s (%s)",
                    stateLabel,
                    sampleSummary,
                    df.format(new Date(profile.getCreatedAtMs()))
            ));

            binding.switchCustomSoundEnabled.setOnCheckedChangeListener(null);
            binding.switchCustomSoundEnabled.setChecked(profile.isEnabled());
            binding.switchCustomSoundEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (listener != null) listener.onToggleEnabled(profile, isChecked);
            });

            binding.btnAddSample.setOnClickListener(v -> {
                if (listener != null) listener.onAddMoreSample(profile);
            });
            binding.btnPreviewCustomSound.setOnClickListener(v -> {
                if (listener != null) listener.onPreviewSample(profile);
            });
            binding.btnRenameCustomSound.setOnClickListener(v -> {
                if (listener != null) listener.onRenameSound(profile);
            });
            binding.btnDeleteCustomSound.setOnClickListener(v -> {
                if (listener != null) listener.onDeleteSound(profile);
            });
        }
    }
}
