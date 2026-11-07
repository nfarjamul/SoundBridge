package com.soundbridge.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.soundbridge.app.R;
import com.soundbridge.app.data.AlertDatabaseHelper;
import com.soundbridge.app.data.AppPreferences;
import com.soundbridge.app.databinding.ActivityAdminDashboardBinding;
import com.soundbridge.app.firebase.FirebaseManager;
import com.soundbridge.app.model.AlertEvent;
import com.soundbridge.app.service.SoundMonitorForegroundService;

import java.util.List;
import java.util.Locale;

/**
 * Dedicated Admin Dashboard for real-time sound monitoring, short-clip review,
 * and entry into global acoustic calibration.
 * Guarded by server-side Firebase custom claims and adminUsers verification.
 */
public class AdminDashboardActivity extends AppCompatActivity implements SoundMonitorForegroundService.MonitorStateListener {

    public static final String EXTRA_ALERT_ID = "extra_alert_id";

    private ActivityAdminDashboardBinding binding;
    private AdminAlertReviewAdapter adapter;
    private AppPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new AppPreferences(this);
        binding = ActivityAdminDashboardBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Secure server-side role verification
        verifyAdminAccess();

        setupViews();
        setupRecyclerView();
    }

    private void verifyAdminAccess() {
        FirebaseManager fb = FirebaseManager.getInstance(this);
        String uid = fb.getCurrentUserId();
        if (uid == null) {
            rejectNonAdmin("Authentication required. Please log in with an administrator account.");
            return;
        }

        // Perform server-validated check
        fb.verifyAdminAsync((isAuthorized, message) -> {
            if (!isFinishing() && !isDestroyed()) {
                if (!isAuthorized) {
                    // If previously verified on the main/settings screen, don't kick out on transient latency
                    if (prefs.isAdminVerifiedHintFor(uid)) {
                        return;
                    }
                    rejectNonAdmin("Access Denied: This account is not an authorized SoundBridge administrator.");
                } else {
                    prefs.setAdminVerifiedHint(uid, true);
                }
            }
        });
    }

    private void rejectNonAdmin(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    private void setupViews() {
        binding.btnBack.setOnClickListener(v -> finish());

        binding.btnManageCalibrations.setOnClickListener(v -> {
            Intent intent = new Intent(this, AdminCalibrationManageActivity.class);
            startActivity(intent);
        });

        // Filter chips setup
        binding.chipFilterAll.setOnClickListener(v -> adapter.applyFilter(AdminAlertReviewAdapter.Filter.ALL));
        binding.chipFilterUncorrected.setOnClickListener(v -> adapter.applyFilter(AdminAlertReviewAdapter.Filter.UNCORRECTED));
        binding.chipFilterCalibrated.setOnClickListener(v -> adapter.applyFilter(AdminAlertReviewAdapter.Filter.CALIBRATED));
    }

    private void setupRecyclerView() {
        adapter = new AdminAlertReviewAdapter(alert -> {
            Intent intent = new Intent(this, AdminCorrectionActivity.class);
            intent.putExtra(EXTRA_ALERT_ID, alert.getId());
            startActivity(intent);
        });

        binding.rvAdminAlerts.setLayoutManager(new LinearLayoutManager(this));
        binding.rvAdminAlerts.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SoundMonitorForegroundService.addListener(this);
        updateLiveMonitorState(SoundMonitorForegroundService.isServiceRunning(), "Monitoring");
        loadAlerts();
    }

    @Override
    protected void onPause() {
        super.onPause();
        SoundMonitorForegroundService.removeListener(this);
        if (adapter != null) {
            adapter.stopActivePlayback();
        }
    }

    private void loadAlerts() {
        List<AlertEvent> alerts = AlertDatabaseHelper.getInstance(this).getAllAlerts();
        adapter.setAlerts(alerts);

        int count = alerts.size();
        binding.tvAlertCount.setText(count == 1 ? "1 detection" : count + " detections");

        if (count == 0) {
            binding.layoutEmptyAlerts.setVisibility(View.VISIBLE);
            binding.rvAdminAlerts.setVisibility(View.GONE);
        } else {
            binding.layoutEmptyAlerts.setVisibility(View.GONE);
            binding.rvAdminAlerts.setVisibility(View.VISIBLE);
        }
    }

    private void updateLiveMonitorState(boolean running, String statusText) {
        if (running) {
            binding.tvLiveStatusPill.setText("Active");
            binding.tvLiveStatusPill.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.status_monitoring_bg));
            binding.tvLiveStatusPill.setTextColor(ContextCompat.getColor(this, R.color.status_monitoring_active));
        } else {
            binding.tvLiveStatusPill.setText("Stopped");
            binding.tvLiveStatusPill.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.status_stopped_bg));
            binding.tvLiveStatusPill.setTextColor(ContextCompat.getColor(this, R.color.status_monitoring_stopped));
            binding.tvLivePredictedName.setText("Monitoring Stopped");
            binding.tvLiveRecognitionDetails.setText("Start monitoring from the main screen");
            binding.liveAudioMeter.setProgress(0);
        }
    }

    @Override
    public void onMonitorStateChanged(boolean running, String statusText) {
        runOnUiThread(() -> updateLiveMonitorState(running, statusText));
    }

    @Override
    public void onLiveAudioFrame(float rmsLevel, String topCandidateLabel, float confidence) {
        runOnUiThread(() -> {
            if (binding == null) return;
            binding.tvLivePredictedName.setText(topCandidateLabel);
            int meterProgress = (int) Math.min(100f, rmsLevel * 1200f);
            binding.liveAudioMeter.setProgress(meterProgress);

            if (confidence > 0f) {
                binding.tvLiveRecognitionDetails.setText(String.format(Locale.US,
                        "Confidence: %.0f%% • Live inference stream", confidence * 100f));
            } else {
                binding.tvLiveRecognitionDetails.setText("Listening to surroundings...");
            }
        });
    }

    @Override
    public void onSoundAlertDetected(AlertEvent alert) {
        runOnUiThread(this::loadAlerts);
    }
}

