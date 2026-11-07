package com.soundbridge.app.ui;

import android.content.Context;
import android.media.MediaPlayer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.soundbridge.app.R;
import com.soundbridge.app.databinding.ItemAdminAlertReviewBinding;
import com.soundbridge.app.model.AlertEvent;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView Adapter for administrator review and calibration of detected sound alerts.
 * Supports inline playback of locally captured 2.0-second 16 kHz audio clips.
 */
public class AdminAlertReviewAdapter extends RecyclerView.Adapter<AdminAlertReviewAdapter.ReviewViewHolder> {

    public interface OnAlertReviewListener {
        void onCalibrateClick(AlertEvent alert);
    }

    private final List<AlertEvent> fullList = new ArrayList<>();
    private final List<AlertEvent> displayedList = new ArrayList<>();
    private final OnAlertReviewListener listener;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy • h:mm:ss a", Locale.US);

    private MediaPlayer activePlayer = null;
    private int playingPosition = -1;

    public AdminAlertReviewAdapter(OnAlertReviewListener listener) {
        this.listener = listener;
    }

    public void setAlerts(List<AlertEvent> alerts) {
        fullList.clear();
        if (alerts != null) {
            fullList.addAll(alerts);
        }
        applyFilter(currentFilter);
    }

    public enum Filter {
        ALL,
        UNCORRECTED,
        CALIBRATED
    }

    private Filter currentFilter = Filter.ALL;

    public void applyFilter(Filter filter) {
        this.currentFilter = filter;
        stopActivePlayback();
        displayedList.clear();
        for (AlertEvent a : fullList) {
            boolean isCalibrated = a.isCalibratedByAdmin() || a.getAppliedCalibrationId() != null;
            if (filter == Filter.UNCORRECTED && isCalibrated) {
                continue;
            }
            if (filter == Filter.CALIBRATED && !isCalibrated) {
                continue;
            }
            displayedList.add(a);
        }
        notifyDataSetChanged();
    }

    public void stopActivePlayback() {
        if (activePlayer != null) {
            try {
                if (activePlayer.isPlaying()) {
                    activePlayer.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                activePlayer.release();
            } catch (Exception ignored) {
            }
            activePlayer = null;
        }
        int prev = playingPosition;
        playingPosition = -1;
        if (prev >= 0 && prev < displayedList.size()) {
            notifyItemChanged(prev);
        }
    }

    @NonNull
    @Override
    public ReviewViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAdminAlertReviewBinding binding = ItemAdminAlertReviewBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        );
        return new ReviewViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ReviewViewHolder holder, int position) {
        holder.bind(displayedList.get(position), position);
    }

    @Override
    public int getItemCount() {
        return displayedList.size();
    }

    class ReviewViewHolder extends RecyclerView.ViewHolder {
        private final ItemAdminAlertReviewBinding binding;

        ReviewViewHolder(ItemAdminAlertReviewBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(final AlertEvent alert, final int position) {
            Context ctx = binding.getRoot().getContext();

            binding.tvAlertName.setText(alert.getDisplayTitle());
            binding.tvAlertTimestamp.setText("Detected: " + dateFormat.format(new Date(alert.getTimestampMs())));

            String sourceStr = "Built-in Model";
            if (alert.isGlobalCalibrated()) {
                sourceStr = "Global Calibrated";
            } else if (alert.isCustom()) {
                sourceStr = "Custom Acoustic Profile";
            }
            binding.tvAlertDetails.setText(String.format(Locale.US, "Confidence: %d%% • %s", alert.getConfidencePercent(), sourceStr));

            boolean isCalibrated = alert.isCalibratedByAdmin() || alert.getAppliedCalibrationId() != null;
            if (isCalibrated) {
                binding.tvCalibratedBadge.setVisibility(View.VISIBLE);
                binding.tvCalibratedBadge.setText("Calibrated");
                binding.tvCalibratedBadge.setBackgroundTintList(ContextCompat.getColorStateList(ctx, R.color.alert_info_bg));
                binding.tvCalibratedBadge.setTextColor(ContextCompat.getColor(ctx, R.color.alert_info_blue));

                if (alert.getOriginalPrediction() != null && !alert.getOriginalPrediction().isEmpty()) {
                    binding.tvOriginalPrediction.setVisibility(View.VISIBLE);
                    binding.tvOriginalPrediction.setText("Original Prediction: " + alert.getOriginalPrediction()
                            + String.format(Locale.US, " (%.0f%%)", alert.getOriginalConfidence() * 100f));
                } else {
                    binding.tvOriginalPrediction.setVisibility(View.GONE);
                }
            } else {
                binding.tvCalibratedBadge.setVisibility(View.VISIBLE);
                binding.tvCalibratedBadge.setText("Uncalibrated");
                binding.tvCalibratedBadge.setBackgroundTintList(ContextCompat.getColorStateList(ctx, R.color.bg_subtle));
                binding.tvCalibratedBadge.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
                binding.tvOriginalPrediction.setVisibility(View.GONE);
            }

            // Category Icon
            int iconRes = R.drawable.ic_sound_wave;
            int colorRes = R.color.primary_blue;
            if (AlertEvent.CATEGORY_ALARM.equals(alert.getCategory())) {
                iconRes = R.drawable.ic_alarm_urgent;
                colorRes = R.color.alert_urgent_red;
            } else if (AlertEvent.CATEGORY_KNOCK.equals(alert.getCategory())) {
                iconRes = R.drawable.ic_knock;
                colorRes = R.color.alert_warning_amber;
            } else if (AlertEvent.CATEGORY_APPLIANCE_BEEP.equals(alert.getCategory())) {
                iconRes = R.drawable.ic_appliance_beep;
                colorRes = R.color.accent_teal;
            } else if (AlertEvent.CATEGORY_DOORBELL.equals(alert.getCategory())) {
                iconRes = R.drawable.ic_doorbell;
                colorRes = R.color.primary_blue;
            }
            binding.ivAlertCategoryIcon.setImageResource(iconRes);
            binding.ivAlertCategoryIcon.setColorFilter(ContextCompat.getColor(ctx, colorRes));

            // Audio Playback section
            final String clipPath = alert.getLocalClipPath();
            final boolean hasClip = clipPath != null && new File(clipPath).exists();
            final boolean isPlaying = (playingPosition == position);

            if (hasClip) {
                binding.layoutAudioSection.setVisibility(View.VISIBLE);
                binding.btnPlayAudio.setEnabled(true);
                if (isPlaying) {
                    binding.btnPlayAudio.setImageResource(R.drawable.ic_stop);
                    binding.tvAudioStatus.setText("Playing audio capture...");
                    binding.progressAudio.setVisibility(View.VISIBLE);
                    binding.progressAudio.setIndeterminate(true);
                } else {
                    binding.btnPlayAudio.setImageResource(R.drawable.ic_play);
                    binding.tvAudioStatus.setText("Play 2.0s audio clip");
                    binding.progressAudio.setVisibility(View.GONE);
                }

                binding.btnPlayAudio.setOnClickListener(v -> {
                    if (playingPosition == position) {
                        stopActivePlayback();
                    } else {
                        startPlayback(clipPath, position);
                    }
                });
            } else {
                binding.layoutAudioSection.setVisibility(View.VISIBLE);
                binding.btnPlayAudio.setEnabled(false);
                binding.btnPlayAudio.setImageResource(R.drawable.ic_play);
                binding.tvAudioStatus.setText("No local recording saved for this alert");
                binding.progressAudio.setVisibility(View.GONE);
                binding.btnPlayAudio.setOnClickListener(null);
            }

            // Calibrate Action
            binding.btnCalibrateAlert.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onCalibrateClick(alert);
                }
            });
        }

        private void startPlayback(String path, int pos) {
            stopActivePlayback();
            try {
                activePlayer = new MediaPlayer();
                activePlayer.setDataSource(path);
                activePlayer.prepare();
                activePlayer.setOnCompletionListener(mp -> {
                    stopActivePlayback();
                });
                activePlayer.setOnErrorListener((mp, what, extra) -> {
                    stopActivePlayback();
                    return true;
                });
                activePlayer.start();
                playingPosition = pos;
                notifyItemChanged(pos);
            } catch (Exception e) {
                stopActivePlayback();
            }
        }
    }
}

