package com.soundbridge.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.soundbridge.app.R;
import com.soundbridge.app.data.AppPreferences;
import com.soundbridge.app.data.SoundBridgeRepository;
import com.soundbridge.app.databinding.ActivityMainBinding;
import com.soundbridge.app.firebase.FirebaseManager;
import com.soundbridge.app.model.AlertEvent;
import com.soundbridge.app.receiver.SystemStateBroadcastReceiver;
import com.soundbridge.app.service.SoundMonitorForegroundService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Primary Dashboard Activity for SoundBridge.
 * Provides a calm, consumer-grade interface for starting/pausing real-time sound listening,
 * inspecting the latest detected household sound, switching Light/Dark mode, and navigating
 * to Alert History, Custom Sounds, and Preferences.
 */
public class MainActivity extends AppCompatActivity implements
        SoundMonitorForegroundService.MonitorStateListener,
        SystemStateBroadcastReceiver.SystemStateListener {

    private static final String KEY_LATEST_ALERT_ID = "state_latest_alert_id";

    private ActivityMainBinding binding;
    private SoundBridgeRepository repository;
    private FirebaseManager firebaseManager;
    private AppPreferences prefs;
    private SystemStateBroadcastReceiver systemReceiver;
    private AlertEvent currentLatestAlert;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("MMM d, h:mm a", Locale.US);

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean micGranted = result.get(Manifest.permission.RECORD_AUDIO);
                if (Boolean.TRUE.equals(micGranted) || hasRecordAudioPermission()) {
                    startMonitoringServiceNow();
                } else {
                    showPermissionSettingsDialog(
                            "Microphone Access Needed",
                            "SoundBridge uses your microphone to recognize doorbells, knocks, and alarms right on your phone. Audio is never recorded or uploaded."
                    );
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = SoundBridgeRepository.getInstance(this);
        firebaseManager = FirebaseManager.getInstance(this);
        prefs = new AppPreferences(this);

        if (!firebaseManager.isSignedIn() && !prefs.isLocalDemoMode()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setupClickListeners();

        if (savedInstanceState != null) {
            String savedAlertId = savedInstanceState.getString(KEY_LATEST_ALERT_ID);
            if (savedAlertId != null) {
                repository.loadAlertByIdAsync(savedAlertId, alert -> {
                    if (alert != null) {
                        currentLatestAlert = alert;
                        updateLatestAlertCard(alert, false);
                    }
                });
            }
        }

        if (firebaseManager.isSignedIn()) {
            repository.retryPendingAlertsSync();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        SoundMonitorForegroundService.addListener(this);
        SystemStateBroadcastReceiver.addListener(this);

        systemReceiver = new SystemStateBroadcastReceiver();
        ContextCompat.registerReceiver(
                this,
                systemReceiver,
                SystemStateBroadcastReceiver.createIntentFilter(),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );

        if (firebaseManager.isSignedIn()) {
            // Keep Realtime Database presence synchronized silently in the background
            firebaseManager.startObservingRealtimeState((online, monitoringActive, latestAlertSummary) -> {
            });
        }

        refreshDashboardState();
    }

    @Override
    protected void onStop() {
        super.onStop();
        SoundMonitorForegroundService.removeListener(this);
        SystemStateBroadcastReceiver.removeListener(this);
        if (systemReceiver != null) {
            try {
                unregisterReceiver(systemReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            systemReceiver = null;
        }
        firebaseManager.stopObservingRealtimeState();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentLatestAlert != null) {
            outState.putString(KEY_LATEST_ALERT_ID, currentLatestAlert.getId());
        }
    }

    private void setupClickListeners() {
        binding.btnToggleTheme.setOnClickListener(v -> prefs.toggleDarkLightMode(this));
        binding.btnHeaderSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        binding.btnToggleMonitoring.setOnClickListener(v -> {
            if (SoundMonitorForegroundService.isServiceRunning()) {
                Intent stopIntent = new Intent(this, SoundMonitorForegroundService.class);
                stopIntent.setAction(SoundMonitorForegroundService.ACTION_STOP_MONITORING);
                startService(stopIntent);
                applyMonitoringVisualState(false);
            } else {
                requestPermissionsAndStartMonitoring();
            }
        });

        binding.cardLatestAlert.setOnClickListener(v -> {
            if (currentLatestAlert != null) {
                Intent intent = new Intent(this, AlertDetailActivity.class);
                intent.putExtra(AlertDetailActivity.EXTRA_ALERT_ID, currentLatestAlert.getId());
                startActivity(intent);
            }
        });

        binding.btnNavHistory.setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
        binding.btnNavCustomSounds.setOnClickListener(v ->
                startActivity(new Intent(this, SoundLibraryActivity.class)));
        binding.btnNavSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        if (binding.cardAdminConsole != null) {
            binding.cardAdminConsole.setOnClickListener(v ->
                    startActivity(new Intent(this, AdminDashboardActivity.class)));
        }
    }

    private void requestPermissionsAndStartMonitoring() {
        if (hasRecordAudioPermission() && hasNotificationPermissionIfRequired()) {
            startMonitoringServiceNow();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
            });
        } else {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.RECORD_AUDIO
            });
        }
    }

    private boolean hasRecordAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermissionIfRequired() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void startMonitoringServiceNow() {
        Intent startIntent = new Intent(this, SoundMonitorForegroundService.class);
        startIntent.setAction(SoundMonitorForegroundService.ACTION_START_MONITORING);
        ContextCompat.startForegroundService(this, startIntent);
        applyMonitoringVisualState(true);
    }

    private void applyMonitoringVisualState(boolean running) {
        if (running) {
            binding.tvMonitoringStateHeader.setText(R.string.status_monitoring_active);
            binding.tvMonitoringStateHeader.setTextColor(ContextCompat.getColor(this, R.color.status_monitoring_active));
            binding.tvMonitoringStateHeader.setBackgroundTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_monitoring_bg)));
            binding.tvMonitoringSubStatus.setText("Watching for doorbells, knocks, alarms, and your custom sounds.");
            binding.btnToggleMonitoring.setText(R.string.btn_stop_monitoring);
            binding.btnToggleMonitoring.setIconResource(R.drawable.ic_stop);
            binding.soundLevelMeter.setMonitoringState(true, "Listening to your surroundings");
        } else {
            binding.tvMonitoringStateHeader.setText(R.string.status_monitoring_stopped);
            binding.tvMonitoringStateHeader.setTextColor(ContextCompat.getColor(this, R.color.status_monitoring_stopped));
            binding.tvMonitoringStateHeader.setBackgroundTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_stopped_bg)));
            binding.tvMonitoringSubStatus.setText("Alerts are paused. Start listening when you want SoundBridge active.");
            binding.btnToggleMonitoring.setText(R.string.btn_start_monitoring);
            binding.btnToggleMonitoring.setIconResource(R.drawable.ic_mic);
            binding.soundLevelMeter.setMonitoringState(false, "Ready to listen");
        }
    }

    private void showPermissionSettingsDialog(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                })
                .setNegativeButton("Not Now", null)
                .show();
    }

    private void refreshDashboardState() {
        if (firebaseManager.isSignedIn()) {
            String displayName = prefs.getAuthDisplayName();
            if (!TextUtils.isEmpty(displayName)) {
                binding.tvAccountModeBanner.setText("Hi, " + displayName);
            } else {
                binding.tvAccountModeBanner.setText("Cloud backup active");
            }
        } else {
            binding.tvAccountModeBanner.setText(R.string.tagline);
        }

        applyMonitoringVisualState(SoundMonitorForegroundService.isServiceRunning());
        updateSystemWarningBanner(SystemStateBroadcastReceiver.getLastSystemBanner());

        // Check and toggle Admin Console Card
        if (binding.cardAdminConsole != null) {
            String currentUid = firebaseManager.getCurrentUserId();
            if (currentUid != null) {
                boolean cachedAdmin = prefs.isAdminVerifiedHintFor(currentUid);
                binding.cardAdminConsole.setVisibility(cachedAdmin ? View.VISIBLE : View.GONE);
                firebaseManager.verifyAdminAsync((isAuthorized, message) -> {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || binding == null || binding.cardAdminConsole == null) return;
                        binding.cardAdminConsole.setVisibility(isAuthorized ? View.VISIBLE : View.GONE);
                    });
                });
            } else {
                binding.cardAdminConsole.setVisibility(View.GONE);
            }
        }

        repository.loadAlertsAsync("ALL", list -> {
            if (list != null && !list.isEmpty()) {
                currentLatestAlert = list.get(0);
                updateLatestAlertCard(currentLatestAlert, false);
            } else {
                currentLatestAlert = null;
                updateLatestAlertCard(null, false);
            }
        });
    }

    private void updateSystemWarningBanner(String banner) {
        if (TextUtils.isEmpty(banner)) {
            binding.tvSystemReceiverBanner.setVisibility(View.GONE);
        } else {
            binding.tvSystemReceiverBanner.setText(banner);
            binding.tvSystemReceiverBanner.setVisibility(View.VISIBLE);
        }
    }

    private void updateLatestAlertCard(@Nullable AlertEvent alert, boolean flashVisualAlert) {
        if (alert == null) {
            binding.tvLatestAlertTitle.setText(R.string.no_recent_alerts);
            binding.tvLatestAlertMeta.setText(R.string.no_recent_alerts_sub);
            binding.ivLatestAlertIcon.setImageResource(R.drawable.ic_doorbell);
            ImageViewCompat.setImageTintList(
                    binding.ivLatestAlertIcon,
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_muted))
            );
            binding.ivLatestAlertChevron.setVisibility(View.GONE);
            binding.cardLatestAlert.setCardBackgroundColor(ContextCompat.getColor(this, R.color.bg_card));
            return;
        }

        binding.tvLatestAlertTitle.setText(alert.getDisplayTitle());
        CharSequence relTime = DateUtils.getRelativeTimeSpanString(
                alert.getTimestampMs(),
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
        );
        String timeStr = relTime != null ? relTime.toString() : timeFormat.format(new Date(alert.getTimestampMs()));
        binding.tvLatestAlertMeta.setText(String.format(Locale.US, "%s • %d%% match",
                timeStr, alert.getConfidencePercent()));
        binding.ivLatestAlertChevron.setVisibility(View.VISIBLE);

        int iconRes;
        int tintColorRes;
        if (AlertEvent.CATEGORY_ALARM.equals(alert.getCategory())) {
            iconRes = R.drawable.ic_alarm_urgent;
            tintColorRes = R.color.alert_urgent_red;
        } else if (AlertEvent.CATEGORY_KNOCK.equals(alert.getCategory())) {
            iconRes = R.drawable.ic_knock;
            tintColorRes = R.color.alert_warning_amber;
        } else if (AlertEvent.CATEGORY_APPLIANCE_BEEP.equals(alert.getCategory())) {
            iconRes = R.drawable.ic_appliance_beep;
            tintColorRes = R.color.accent_teal;
        } else if (alert.isCustom()) {
            iconRes = R.drawable.ic_custom_wave;
            tintColorRes = R.color.accent_teal;
        } else {
            iconRes = R.drawable.ic_doorbell;
            tintColorRes = R.color.primary_blue;
        }
        binding.ivLatestAlertIcon.setImageResource(iconRes);
        ImageViewCompat.setImageTintList(
                binding.ivLatestAlertIcon,
                ColorStateList.valueOf(ContextCompat.getColor(this, tintColorRes))
        );

        if (flashVisualAlert) {
            int highlightBg = AlertEvent.CATEGORY_ALARM.equals(alert.getCategory())
                    ? R.color.alert_urgent_bg
                    : R.color.alert_info_bg;
            binding.cardLatestAlert.setCardBackgroundColor(ContextCompat.getColor(this, highlightBg));
        } else {
            binding.cardLatestAlert.setCardBackgroundColor(ContextCompat.getColor(this, R.color.bg_card));
        }
    }

    // --- SoundMonitorForegroundService.MonitorStateListener ---

    @Override
    public void onMonitorStateChanged(boolean running, String statusText) {
        applyMonitoringVisualState(running);
        if (!running && !TextUtils.isEmpty(statusText) && statusText.toLowerCase(Locale.US).contains("unavailable")) {
            binding.tvMonitoringSubStatus.setText(statusText);
        }
    }

    @Override
    public void onLiveAudioFrame(float rmsLevel, String topCandidateLabel, float confidence) {
        binding.soundLevelMeter.updateAudioLevel(rmsLevel, topCandidateLabel, confidence, false);
    }

    @Override
    public void onSoundAlertDetected(AlertEvent alert) {
        currentLatestAlert = alert;
        binding.soundLevelMeter.updateAudioLevel(0.85f, alert.getDisplayTitle(), alert.getConfidence(), true);
        updateLatestAlertCard(alert, true);
    }

    // --- SystemStateBroadcastReceiver.SystemStateListener ---

    @Override
    public void onSystemStateChanged(boolean lowBattery, boolean airplaneModeOn, int batteryPct, String lastEventBanner) {
        runOnUiThread(() -> updateSystemWarningBanner(lastEventBanner));
    }
}
