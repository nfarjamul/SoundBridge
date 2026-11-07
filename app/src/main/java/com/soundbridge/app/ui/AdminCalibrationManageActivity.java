package com.soundbridge.app.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.soundbridge.app.R;
import com.soundbridge.app.data.AlertDatabaseHelper;
import com.soundbridge.app.data.SoundBridgeRepository;
import com.soundbridge.app.databinding.ActivityAdminCalibrationsBinding;
import com.soundbridge.app.databinding.DialogCalibrationAuditBinding;
import com.soundbridge.app.firebase.FirebaseManager;
import com.soundbridge.app.model.AlertEvent;
import com.soundbridge.app.model.CalibrationAuditRecord;
import com.soundbridge.app.model.GlobalCalibrationProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Admin screen to view, search, activate/deactivate, strengthen, edit, and delete
 * synchronized global sound calibration profiles, as well as view audit logs.
 */
public class AdminCalibrationManageActivity extends AppCompatActivity
        implements GlobalCalibrationAdapter.OnCalibrationActionListener {

    private ActivityAdminCalibrationsBinding binding;
    private GlobalCalibrationAdapter adapter;
    private SoundBridgeRepository repository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAdminCalibrationsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        repository = SoundBridgeRepository.getInstance(this);

        verifyAdminAccess();
        setupViews();
        setupRecyclerView();
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

    private void setupViews() {
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnViewAuditLogs.setOnClickListener(v -> showAuditLogsBottomSheet());

        binding.chipAll.setOnClickListener(v -> adapter.setFilterStatus(0));
        binding.chipActive.setOnClickListener(v -> adapter.setFilterStatus(1));
        binding.chipInactive.setOnClickListener(v -> adapter.setFilterStatus(2));

        binding.etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.setSearchQuery(s != null ? s.toString() : "");
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void setupRecyclerView() {
        adapter = new GlobalCalibrationAdapter(this);
        binding.rvCalibrations.setLayoutManager(new LinearLayoutManager(this));
        binding.rvCalibrations.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadCalibrations();
    }

    private void loadCalibrations() {
        List<GlobalCalibrationProfile> list = repository.getAllGlobalCalibrationsSync();
        adapter.setCalibrations(list);

        int count = list.size();
        binding.tvCalibrationCount.setText(count == 1 ? "1 profile active/synced" : count + " profiles active/synced");

        if (count == 0) {
            binding.layoutEmptyCalibrations.setVisibility(View.VISIBLE);
            binding.rvCalibrations.setVisibility(View.GONE);
        } else {
            binding.layoutEmptyCalibrations.setVisibility(View.GONE);
            binding.rvCalibrations.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onToggleActive(GlobalCalibrationProfile profile, boolean active) {
        String action = active ? CalibrationAuditRecord.ACTION_REACTIVATE : CalibrationAuditRecord.ACTION_DEACTIVATE;
        repository.manageGlobalCalibrationAsync(profile, action, null, 0f, (success, message) -> {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!success) {
                    Toast.makeText(this, "Failed to update status: " + message, Toast.LENGTH_SHORT).show();
                }
                loadCalibrations();
            });
        });
    }

    @Override
    public void onStrengthen(GlobalCalibrationProfile profile) {
        List<AlertEvent> alerts = AlertDatabaseHelper.getInstance(this).getAllAlerts();
        List<AlertEvent> candidateAlerts = new ArrayList<>();
        List<String> displayNames = new ArrayList<>();

        for (AlertEvent a : alerts) {
            if (a.hasLocalClip()) {
                candidateAlerts.add(a);
                displayNames.add(a.getDisplayTitle() + " (" + a.getConfidencePercent() + "% match)");
            }
        }

        if (candidateAlerts.isEmpty()) {
            Toast.makeText(this, "No alert recordings available on device to strengthen this profile.", Toast.LENGTH_LONG).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Strengthen: " + profile.getCanonicalLabel())
                .setItems(displayNames.toArray(new String[0]), (dialog, which) -> {
                    AlertEvent chosen = candidateAlerts.get(which);
                    repository.publishGlobalCorrectionAsync(
                            chosen,
                            profile.getCanonicalLabel(),
                            profile.getCategory(),
                            profile.getSimilarityThreshold(),
                            profile.getCalibrationId(),
                            (success, message) -> {
                                runOnUiThread(() -> {
                                    if (isFinishing() || isDestroyed()) return;
                                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                                    loadCalibrations();
                                });
                            }
                    );
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onEdit(GlobalCalibrationProfile profile) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_calibration_audit, null);
        // Build edit dialog with custom view or EditText
        final EditText etLabel = new EditText(this);
        etLabel.setText(profile.getCanonicalLabel());
        etLabel.setHint("Canonical Label");

        new AlertDialog.Builder(this)
                .setTitle("Edit Calibration Label")
                .setView(etLabel)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newLabel = etLabel.getText().toString().trim();
                    if (!newLabel.isEmpty()) {
                        repository.manageGlobalCalibrationAsync(profile, CalibrationAuditRecord.ACTION_UPDATE,
                                newLabel, profile.getSimilarityThreshold(), (success, message) -> {
                                    runOnUiThread(() -> {
                                        if (isFinishing() || isDestroyed()) return;
                                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                                        loadCalibrations();
                                    });
                                });
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onDelete(GlobalCalibrationProfile profile) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Calibration")
                .setMessage("Are you sure you want to delete '" + profile.getCanonicalLabel() + "'?\n\n"
                        + "This tombstone will be published globally and removed from all SoundBridge devices on their next sync.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    repository.manageGlobalCalibrationAsync(profile, CalibrationAuditRecord.ACTION_DELETE,
                            null, 0f, (success, message) -> {
                                runOnUiThread(() -> {
                                    if (isFinishing() || isDestroyed()) return;
                                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                                    loadCalibrations();
                                });
                            });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAuditLogsBottomSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        DialogCalibrationAuditBinding b = DialogCalibrationAuditBinding.inflate(getLayoutInflater());
        dialog.setContentView(b.getRoot());

        CalibrationAuditAdapter auditAdapter = new CalibrationAuditAdapter();
        b.rvAuditList.setLayoutManager(new LinearLayoutManager(this));
        b.rvAuditList.setAdapter(auditAdapter);

        // Load cached audit logs first
        List<CalibrationAuditRecord> localLogs = AlertDatabaseHelper.getInstance(this).getAllCalibrationAuditRecords();
        if (!localLogs.isEmpty()) {
            b.pbAuditLoading.setVisibility(View.GONE);
            auditAdapter.setRecords(localLogs);
        }

        // Fetch latest audit logs in background
        new Thread(() -> {
            List<CalibrationAuditRecord> remote = FirebaseManager.getInstance(this).fetchRecentAuditLogsSync(50);
            runOnUiThread(() -> {
                b.pbAuditLoading.setVisibility(View.GONE);
                if (remote != null && !remote.isEmpty()) {
                    auditAdapter.setRecords(remote);
                    b.tvEmptyAudit.setVisibility(View.GONE);
                } else if (localLogs.isEmpty()) {
                    b.tvEmptyAudit.setVisibility(View.VISIBLE);
                }
            });
        }).start();

        dialog.show();
    }
}

