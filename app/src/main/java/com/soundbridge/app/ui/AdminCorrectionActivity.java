package com.soundbridge.app.ui;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.chip.Chip;
import com.soundbridge.app.R;
import com.soundbridge.app.data.AlertDatabaseHelper;
import com.soundbridge.app.data.AppPreferences;
import com.soundbridge.app.data.SoundBridgeRepository;
import com.soundbridge.app.databinding.ActivityAdminCorrectionBinding;
import com.soundbridge.app.firebase.FirebaseManager;
import com.soundbridge.app.model.AlertEvent;
import com.soundbridge.app.model.GlobalCalibrationProfile;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Interface allowing an authorized administrator to listen to the ~2-second recorded event,
 * review the original model prediction, select or enter a corrected canonical sound label,
 * adjust the acoustic matching similarity threshold, and publish the global calibration profile.
 */
public class AdminCorrectionActivity extends AppCompatActivity {

    private ActivityAdminCorrectionBinding binding;
    private AlertEvent currentAlert;
    private MediaPlayer mediaPlayer;
    private boolean isPlaying = false;
    private AppPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new AppPreferences(this);
        binding = ActivityAdminCorrectionBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        verifyAdminAccess();

        String alertId = getIntent().getStringExtra(AdminDashboardActivity.EXTRA_ALERT_ID);
        if (alertId == null) {
            Toast.makeText(this, "Alert identifier missing", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        currentAlert = AlertDatabaseHelper.getInstance(this).getAlertById(alertId);
        if (currentAlert == null) {
            Toast.makeText(this, "Alert not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupUI();
    }

    private void verifyAdminAccess() {
        FirebaseManager fb = FirebaseManager.getInstance(this);
        String uid = fb.getCurrentUserId();
        if (uid == null) {
            rejectNonAdmin("Authentication required.");
            return;
        }
        fb.verifyAdminAsync((isAuthorized, message) -> {
            if (!isFinishing() && !isDestroyed() && !isAuthorized) {
                com.soundbridge.app.data.AppPreferences prefs = new com.soundbridge.app.data.AppPreferences(this);
                if (prefs.isAdminVerifiedHintFor(uid)) {
                    return;
                }
                rejectNonAdmin("Access Denied: Administrator role required.");
            }
        });
    }

    private void rejectNonAdmin(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    private void setupUI() {
        binding.btnBack.setOnClickListener(v -> finish());

        // Card 1: Original Detection
        binding.tvOriginalSoundName.setText(currentAlert.getDisplayTitle());
        SimpleDateFormat sdf = new SimpleDateFormat("MMM d, yyyy • h:mm:ss a", Locale.US);
        String sourceStr = currentAlert.isGlobalCalibrated() ? "Global Calibrated"
                : (currentAlert.isCustom() ? "Custom Sound" : "Pretrained General Model");
        binding.tvDetectionDetails.setText(String.format(Locale.US,
                "Confidence: %d%% • Source: %s • %s",
                currentAlert.getConfidencePercent(),
                sourceStr,
                sdf.format(new Date(currentAlert.getTimestampMs()))));

        // Card 2: Audio Verification
        final String clipPath = currentAlert.getLocalClipPath();
        final boolean hasClip = clipPath != null && new File(clipPath).exists();

        if (hasClip) {
            binding.btnPlayStopClip.setEnabled(true);
            binding.btnPlayStopClip.setOnClickListener(v -> toggleAudioPlayback(clipPath));
        } else {
            binding.btnPlayStopClip.setEnabled(false);
            binding.tvAudioClipLabel.setText("No local recording found");
            binding.tvAudioClipDuration.setText("Audio clip was not captured or was pruned");
        }

        // Card 3: Category chips
        setupCategoryChips();

        // Threshold slider
        binding.sliderThreshold.addOnChangeListener((slider, value, fromUser) -> {
            binding.tvThresholdValue.setText(String.format(Locale.US, "%.2f", value));
        });

        // Publish button
        binding.btnPublishCorrection.setOnClickListener(v -> promptPublishConfirmation());
    }

    private void setupCategoryChips() {
        binding.chipDoorbell.setOnClickListener(v -> binding.etCorrectedLabel.setText("Doorbell"));
        binding.chipApplianceBeep.setOnClickListener(v -> binding.etCorrectedLabel.setText("Appliance Beep"));
        binding.chipKnock.setOnClickListener(v -> binding.etCorrectedLabel.setText("Door Knock"));
        binding.chipAlarm.setOnClickListener(v -> binding.etCorrectedLabel.setText("Smoke Alarm / Siren"));
        binding.chipDogBark.setOnClickListener(v -> binding.etCorrectedLabel.setText("Dog Bark"));
        binding.chipCustom.setOnClickListener(v -> {
            binding.etCorrectedLabel.setText("");
            binding.etCorrectedLabel.requestFocus();
        });

        // Default suggestion: if current is Appliance Beep, suggest Doorbell; else keep current or empty
        if (AlertEvent.CATEGORY_APPLIANCE_BEEP.equalsIgnoreCase(currentAlert.getCategory())
                || "Appliance Beep".equalsIgnoreCase(currentAlert.getLabel())) {
            binding.chipDoorbell.setChecked(true);
            binding.etCorrectedLabel.setText("Doorbell");
        } else if (AlertEvent.CATEGORY_DOORBELL.equalsIgnoreCase(currentAlert.getCategory())) {
            binding.chipDoorbell.setChecked(true);
            binding.etCorrectedLabel.setText("Doorbell");
        }
    }

    private void toggleAudioPlayback(String clipPath) {
        if (isPlaying) {
            stopAudio();
        } else {
            try {
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setDataSource(clipPath);
                mediaPlayer.prepare();
                mediaPlayer.setOnCompletionListener(mp -> stopAudio());
                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    stopAudio();
                    return true;
                });
                mediaPlayer.start();
                isPlaying = true;
                binding.btnPlayStopClip.setImageResource(R.drawable.ic_stop);
                binding.tvAudioClipLabel.setText("Playing 2.0s capture...");
            } catch (Exception e) {
                Toast.makeText(this, "Could not play audio: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                stopAudio();
            }
        }
    }

    private void stopAudio() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {
            }
            mediaPlayer = null;
        }
        isPlaying = false;
        binding.btnPlayStopClip.setImageResource(R.drawable.ic_play);
        binding.tvAudioClipLabel.setText("2.0s Local Event Capture");
    }

    private void promptPublishConfirmation() {
        final String correctedLabel = binding.etCorrectedLabel.getText() != null
                ? binding.etCorrectedLabel.getText().toString().trim()
                : "";

        if (correctedLabel.isEmpty()) {
            binding.tilCorrectedLabel.setError("Please enter a corrected canonical sound label");
            return;
        }
        binding.tilCorrectedLabel.setError(null);

        final float threshold = binding.sliderThreshold.getValue();
        final String notes = binding.etNotes.getText() != null
                ? binding.etNotes.getText().toString().trim()
                : "";

        new AlertDialog.Builder(this)
                .setTitle("Confirm Global Calibration")
                .setMessage("Are you sure you want to correct '" + currentAlert.getDisplayTitle()
                        + "' to '" + correctedLabel + "' globally?\n\n"
                        + "A 64-dimensional acoustic pattern will be extracted and published to Cloud Firestore. "
                        + "All SoundBridge devices will synchronize this calibration profile.")
                .setPositiveButton("Confirm & Publish", (dialog, which) -> {
                    executePublish(correctedLabel, threshold, notes);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void executePublish(String correctedLabel, float threshold, String notes) {
        binding.btnPublishCorrection.setEnabled(false);
        binding.pbPublishing.setVisibility(View.VISIBLE);

        SoundBridgeRepository repo = SoundBridgeRepository.getInstance(this);
        repo.publishGlobalCorrectionAsync(
                currentAlert,
                correctedLabel,
                null,
                threshold,
                null,
                (success, message) -> {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        binding.pbPublishing.setVisibility(View.GONE);
                        binding.btnPublishCorrection.setEnabled(true);
                        if (success) {
                            Toast.makeText(AdminCorrectionActivity.this,
                                    "Calibration profile published globally!", Toast.LENGTH_LONG).show();
                            finish();
                        } else {
                            Toast.makeText(AdminCorrectionActivity.this,
                                    "Publication failed: " + message, Toast.LENGTH_LONG).show();
                        }
                    });
                }
        );
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAudio();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAudio();
    }
}
