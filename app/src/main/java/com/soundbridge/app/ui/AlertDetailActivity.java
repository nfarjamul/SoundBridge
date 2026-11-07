package com.soundbridge.app.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.soundbridge.app.R;
import com.soundbridge.app.data.SoundBridgeRepository;
import com.soundbridge.app.databinding.ActivityAlertDetailBinding;
import com.soundbridge.app.model.AlertEvent;
import com.soundbridge.app.util.VibrationHelper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Clean, high-visibility Alert Detail screen.
 * Opened from a notification tap or from Recent Alert / Alert History.
 */
public class AlertDetailActivity extends AppCompatActivity {

    public static final String EXTRA_ALERT_ID = "extra_alert_id";
    public static final String EXTRA_ALERT_LABEL = "extra_alert_label";
    public static final String EXTRA_ALERT_RAW_LABEL = "extra_alert_raw_label";
    public static final String EXTRA_ALERT_CATEGORY = "extra_alert_category";
    public static final String EXTRA_ALERT_SOURCE = "extra_alert_source";
    public static final String EXTRA_ALERT_CONFIDENCE = "extra_alert_confidence";
    public static final String EXTRA_ALERT_TIMESTAMP = "extra_alert_timestamp";
    public static final String EXTRA_ALERT_DELIVERY = "extra_alert_delivery";

    private ActivityAlertDetailBinding binding;
    private SoundBridgeRepository repository;
    private AlertEvent alertEvent;
    private final SimpleDateFormat fullDateFormat = new SimpleDateFormat("EEEE, MMM d • h:mm a", Locale.US);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAlertDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = SoundBridgeRepository.getInstance(this);

        binding.btnBackAlertDetail.setOnClickListener(v -> finish());
        binding.btnTopShareAlert.setOnClickListener(v -> {
            if (alertEvent != null) {
                shareAlertViaImplicitIntent();
            }
        });

        final String alertId = getIntent().getStringExtra(EXTRA_ALERT_ID);
        if (alertId == null) {
            finish();
            return;
        }

        if (getIntent().hasExtra(EXTRA_ALERT_LABEL)) {
            alertEvent = new AlertEvent(
                    alertId,
                    getIntent().getStringExtra(EXTRA_ALERT_LABEL),
                    getIntent().getStringExtra(EXTRA_ALERT_RAW_LABEL),
                    getIntent().getStringExtra(EXTRA_ALERT_CATEGORY),
                    getIntent().getStringExtra(EXTRA_ALERT_SOURCE),
                    getIntent().getFloatExtra(EXTRA_ALERT_CONFIDENCE, 0.85f),
                    getIntent().getLongExtra(EXTRA_ALERT_TIMESTAMP, System.currentTimeMillis()),
                    "local_device",
                    false,
                    getIntent().getStringExtra(EXTRA_ALERT_DELIVERY),
                    AlertEvent.SYNC_PENDING
            );
            bindAlertDetails();
        }

        repository.loadAlertByIdAsync(alertId, loaded -> {
            if (loaded != null) {
                alertEvent = loaded;
                bindAlertDetails();
            } else if (alertEvent == null) {
                finish();
            }
        });

        binding.btnAcknowledgeAlert.setOnClickListener(v -> {
            if (alertEvent != null) {
                alertEvent.setAcknowledged(true);
                repository.updateAlert(alertEvent);
                VibrationHelper.triggerAlertVibration(this, alertEvent.getCategory());
                bindAlertDetails();
                Toast.makeText(this, "Marked as seen", Toast.LENGTH_SHORT).show();
            }
        });

        binding.btnShareAlertImplicitIntent.setOnClickListener(v -> {
            if (alertEvent != null) {
                shareAlertViaImplicitIntent();
            }
        });

        binding.btnDeleteDetailAlert.setOnClickListener(v -> {
            if (alertEvent != null) {
                repository.deleteAlertAsync(alertEvent.getId(), () -> {
                    Toast.makeText(AlertDetailActivity.this, "Removed from history", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        });
    }

    private void bindAlertDetails() {
        if (alertEvent == null) return;

        binding.tvDetailSoundLabel.setText(alertEvent.getDisplayTitle());
        binding.tvDetailTimestamp.setText(fullDateFormat.format(new Date(alertEvent.getTimestampMs())));
        binding.tvDetailConfidenceLarge.setText(alertEvent.getConfidencePercent() + "%");
        binding.tvDetailSourceCategory.setText(String.format(Locale.US, "%s • %s",
                alertEvent.getReadableCategory(), alertEvent.getReadableSource()));
        binding.tvDetailDeliveryAndSync.setText(String.format(Locale.US, "%s • %s",
                alertEvent.getReadableDeliveryStatus(), alertEvent.getReadableSyncStatus()));

        int bannerBgRes;
        int accentRes;
        int iconRes;
        String tagText;
        if (AlertEvent.CATEGORY_ALARM.equals(alertEvent.getCategory())) {
            bannerBgRes = R.color.alert_urgent_bg;
            accentRes = R.color.alert_urgent_red;
            iconRes = R.drawable.ic_alarm_urgent;
            tagText = "URGENT SAFETY ALERT";
        } else if (AlertEvent.CATEGORY_KNOCK.equals(alertEvent.getCategory())) {
            bannerBgRes = R.color.alert_warning_bg;
            accentRes = R.color.alert_warning_amber;
            iconRes = R.drawable.ic_knock;
            tagText = "DOOR KNOCK DETECTED";
        } else if (AlertEvent.CATEGORY_APPLIANCE_BEEP.equals(alertEvent.getCategory())) {
            bannerBgRes = R.color.status_monitoring_bg;
            accentRes = R.color.accent_teal;
            iconRes = R.drawable.ic_appliance_beep;
            tagText = "APPLIANCE CHIME";
        } else if (alertEvent.isCustom()) {
            bannerBgRes = R.color.status_monitoring_bg;
            accentRes = R.color.accent_teal;
            iconRes = R.drawable.ic_custom_wave;
            tagText = "CUSTOM HOME SOUND";
        } else {
            bannerBgRes = R.color.alert_info_bg;
            accentRes = R.color.primary_blue;
            iconRes = R.drawable.ic_doorbell;
            tagText = "DOORBELL DETECTED";
        }

        int accentColor = ContextCompat.getColor(this, accentRes);
        binding.cardVisualAlertBanner.setCardBackgroundColor(ContextCompat.getColor(this, bannerBgRes));
        binding.tvDetailSeverityTag.setText(tagText);
        binding.tvDetailSeverityTag.setTextColor(accentColor);
        binding.ivAlertVisualIcon.setImageResource(iconRes);
        ImageViewCompat.setImageTintList(binding.ivAlertVisualIcon, ColorStateList.valueOf(accentColor));

        if (AlertEvent.CATEGORY_APPLIANCE_BEEP.equals(alertEvent.getCategory())) {
            binding.tvDetailCategoryExplanation.setText(
                    "Tip: For an appliance with a unique tone (like a microwave or washer), you can record 2–3 clips in Custom Sounds for dedicated alerts.");
        } else if (alertEvent.isCustom()) {
            binding.tvDetailCategoryExplanation.setText(
                    "Matched against your saved custom sound profile privately on this phone.");
        } else {
            binding.tvDetailCategoryExplanation.setText(
                    "Recognized privately on your phone without saving or uploading audio.");
        }

        binding.btnAcknowledgeAlert.setEnabled(!alertEvent.isAcknowledged());
        binding.btnAcknowledgeAlert.setText(alertEvent.isAcknowledged() ? "Seen ✓" : "Mark as Seen");
    }

    private void shareAlertViaImplicitIntent() {
        String shareBody = String.format(Locale.US,
                "SoundBridge Alert: %s\nDetected: %s\nConfidence: %d%% (%s)",
                alertEvent.getDisplayTitle(),
                fullDateFormat.format(new Date(alertEvent.getTimestampMs())),
                alertEvent.getConfidencePercent(),
                alertEvent.getReadableCategory());

        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("text/plain");
        sendIntent.putExtra(Intent.EXTRA_SUBJECT, "SoundBridge Alert: " + alertEvent.getDisplayTitle());
        sendIntent.putExtra(Intent.EXTRA_TEXT, shareBody);

        startActivity(Intent.createChooser(sendIntent, "Share Alert"));
    }
}
