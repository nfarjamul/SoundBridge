package com.soundbridge.app.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.soundbridge.app.R;
import com.soundbridge.app.audio.SoundClassifierEngine;
import com.soundbridge.app.bluetooth.BluetoothCompanionManager;
import com.soundbridge.app.data.AlertDatabaseHelper;
import com.soundbridge.app.data.AppPreferences;
import com.soundbridge.app.data.SoundBridgeContentProvider;
import com.soundbridge.app.data.SoundBridgeRepository;
import com.soundbridge.app.databinding.ActivitySettingsBinding;
import com.soundbridge.app.firebase.FirebaseManager;
import com.soundbridge.app.model.AlertEvent;
import com.soundbridge.app.model.CustomSoundProfile;
import com.soundbridge.app.service.SoundMonitorForegroundService;
import com.soundbridge.app.util.VibrationHelper;
import com.soundbridge.app.util.WavAudioUtil;

import java.util.List;
import java.util.Locale;

/**
 * Preferences, Dark Mode Appearance, Companion Phone (Bluetooth), Account Management,
 * and tucked-away Sound Check & Diagnostics.
 */
public class SettingsActivity extends AppCompatActivity implements BluetoothCompanionManager.CompanionStateListener {

    private ActivitySettingsBinding binding;
    private AppPreferences prefs;
    private FirebaseManager firebaseManager;
    private SoundBridgeRepository repository;
    private BluetoothCompanionManager bluetoothManager;

    private boolean pendingStartReceiverAfterPerm = false;

    private final ActivityResultLauncher<String[]> btPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (bluetoothManager.hasBluetoothConnectPermission()) {
                    if (pendingStartReceiverAfterPerm) {
                        pendingStartReceiverAfterPerm = false;
                        bluetoothManager.startCompanionReceiver();
                        refreshBluetoothUI();
                    } else {
                        showPairedDevicesDialog();
                    }
                } else {
                    Toast.makeText(this, "Bluetooth permission is needed to pair with a companion phone.", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs = new AppPreferences(this);
        firebaseManager = FirebaseManager.getInstance(this);
        repository = SoundBridgeRepository.getInstance(this);
        bluetoothManager = BluetoothCompanionManager.getInstance(this);

        binding.btnBackSettings.setOnClickListener(v -> finish());

        initThemeControls();
        initSettingsControls();
        initBluetoothControls();
        initAccountControls();
        initDiagnosticsControls();
        initAdminControls();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bluetoothManager.addListener(this);
        refreshBluetoothUI();
    }

    @Override
    protected void onStop() {
        super.onStop();
        bluetoothManager.removeListener(this);
        if (firebaseManager.isSignedIn()) {
            firebaseManager.syncUserSettingsToFirestore();
        }
    }

    private void initThemeControls() {
        int savedMode = prefs.getThemeMode();
        if (savedMode == AppCompatDelegate.MODE_NIGHT_NO) {
            binding.chipThemeLight.setChecked(true);
        } else if (savedMode == AppCompatDelegate.MODE_NIGHT_YES) {
            binding.chipThemeDark.setChecked(true);
        } else {
            binding.chipThemeSystem.setChecked(true);
        }

        binding.chipThemeSystem.setOnClickListener(v ->
                prefs.setThemeMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
        binding.chipThemeLight.setOnClickListener(v ->
                prefs.setThemeMode(AppCompatDelegate.MODE_NIGHT_NO));
        binding.chipThemeDark.setOnClickListener(v ->
                prefs.setThemeMode(AppCompatDelegate.MODE_NIGHT_YES));
    }

    private void initSettingsControls() {
        int thresholdPct = Math.round(prefs.getConfidenceThreshold() * 100f);
        binding.seekSensitivity.setProgress(Math.max(20, Math.min(85, thresholdPct)));
        binding.tvSensitivityLabel.setText(String.format(Locale.US, "Required Match Confidence: %d%%", thresholdPct));

        binding.seekSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int clamped = Math.max(20, Math.min(85, progress));
                binding.tvSensitivityLabel.setText(String.format(Locale.US, "Required Match Confidence: %d%%", clamped));
                if (fromUser) {
                    prefs.setConfidenceThreshold(clamped / 100f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        int cooldownSec = (int) (prefs.getCooldownMs() / 1000L);
        binding.seekCooldown.setProgress(cooldownSec);
        binding.tvCooldownLabel.setText(String.format(Locale.US, "Pause Between Repeat Alerts: %ds", cooldownSec));

        binding.seekCooldown.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int clamped = Math.max(2, progress);
                binding.tvCooldownLabel.setText(String.format(Locale.US, "Pause Between Repeat Alerts: %ds", clamped));
                if (fromUser) {
                    prefs.setCooldownMs(clamped * 1000L);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        binding.switchCatDoorbell.setChecked(prefs.isDoorbellEnabled());
        binding.switchCatDoorbell.setOnCheckedChangeListener((btn, checked) -> prefs.setDoorbellEnabled(checked));

        binding.switchCatKnock.setChecked(prefs.isKnockEnabled());
        binding.switchCatKnock.setOnCheckedChangeListener((btn, checked) -> prefs.setKnockEnabled(checked));

        binding.switchCatAlarm.setChecked(prefs.isAlarmEnabled());
        binding.switchCatAlarm.setOnCheckedChangeListener((btn, checked) -> prefs.setAlarmEnabled(checked));

        binding.switchCatBeep.setChecked(prefs.isApplianceBeepEnabled());
        binding.switchCatBeep.setOnCheckedChangeListener((btn, checked) -> prefs.setApplianceBeepEnabled(checked));

        binding.switchVibration.setChecked(prefs.isVibrationEnabled());
        binding.switchVibration.setOnCheckedChangeListener((btn, checked) -> prefs.setVibrationEnabled(checked));

        binding.switchStrongVibration.setChecked(prefs.isStrongVibration());
        binding.switchStrongVibration.setOnCheckedChangeListener((btn, checked) -> prefs.setStrongVibration(checked));

        binding.switchLowBatteryReduce.setChecked(prefs.isLowPowerThrottleEnabled());
        binding.switchLowBatteryReduce.setOnCheckedChangeListener((btn, checked) -> prefs.setLowPowerThrottleEnabled(checked));

        binding.switchCustomDescriptorSync.setChecked(prefs.isCustomDescriptorSyncConsented());
        binding.switchCustomDescriptorSync.setOnCheckedChangeListener((btn, checked) -> prefs.setCustomDescriptorSyncConsented(checked));
    }

    private void initBluetoothControls() {
        binding.btnSelectPairedDevice.setOnClickListener(v -> {
            pendingStartReceiverAfterPerm = false;
            if (bluetoothManager.hasBluetoothConnectPermission()) {
                showPairedDevicesDialog();
            } else {
                requestBtPermissions();
            }
        });

        binding.btnToggleCompanionReceiver.setOnClickListener(v -> {
            if (bluetoothManager.isReceiverRunning()) {
                bluetoothManager.stopCompanionReceiver();
                refreshBluetoothUI();
            } else {
                pendingStartReceiverAfterPerm = true;
                if (bluetoothManager.hasBluetoothConnectPermission()) {
                    bluetoothManager.startCompanionReceiver();
                    refreshBluetoothUI();
                } else {
                    requestBtPermissions();
                }
            }
        });

        binding.btnTestBtSend.setOnClickListener(v -> {
            String addr = prefs.getBtCompanionAddress();
            if (TextUtils.isEmpty(addr)) {
                Toast.makeText(this, "Choose a paired phone first.", Toast.LENGTH_SHORT).show();
                return;
            }
            AlertEvent testAlert = new AlertEvent(
                    null,
                    "Doorbell",
                    "Doorbell",
                    AlertEvent.CATEGORY_DOORBELL,
                    AlertEvent.SOURCE_BUILT_IN,
                    0.94f,
                    System.currentTimeMillis(),
                    prefs.getDeviceId(),
                    false,
                    AlertEvent.DELIVERY_LOCAL_ONLY,
                    AlertEvent.SYNC_PENDING
            );
            repository.recordAlert(testAlert);
            bluetoothManager.sendAlertToCompanionAsync(testAlert, (delivered, statusMessage) ->
                    Toast.makeText(SettingsActivity.this,
                            delivered ? "Test alert delivered to companion phone!" : "Could not reach companion phone.",
                            Toast.LENGTH_SHORT).show());
        });
    }

    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btPermissionLauncher.launch(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            });
        }
    }

    private void showPairedDevicesDialog() {
        if (!bluetoothManager.isBluetoothEnabled()) {
            Toast.makeText(this, "Turn on Bluetooth in your phone's settings first.", Toast.LENGTH_LONG).show();
            return;
        }

        List<BluetoothCompanionManager.BondedDeviceItem> bonded = bluetoothManager.getBondedDevices();
        if (bonded.isEmpty()) {
            Toast.makeText(this, "No paired phones found. Pair the two phones in Android Bluetooth settings first.", Toast.LENGTH_LONG).show();
            return;
        }

        String[] items = new String[bonded.size() + 1];
        for (int i = 0; i < bonded.size(); i++) {
            items[i] = bonded.get(i).name;
        }
        items[bonded.size()] = "Disconnect Companion Phone";

        new AlertDialog.Builder(this)
                .setTitle("Choose Companion Phone")
                .setItems(items, (dialog, which) -> {
                    if (which == bonded.size()) {
                        prefs.setBtCompanionDevice("", "");
                    } else {
                        BluetoothCompanionManager.BondedDeviceItem chosen = bonded.get(which);
                        prefs.setBtCompanionDevice(chosen.name, chosen.address);
                    }
                    refreshBluetoothUI();
                })
                .show();
    }

    private void refreshBluetoothUI() {
        String name = prefs.getBtCompanionName();
        String addr = prefs.getBtCompanionAddress();
        if (TextUtils.isEmpty(addr)) {
            binding.tvSelectedBtDevice.setText("Companion phone: Not selected");
        } else {
            binding.tvSelectedBtDevice.setText("Companion phone: " + (!TextUtils.isEmpty(name) ? name : "Paired device"));
        }

        if (bluetoothManager.isReceiverRunning()) {
            binding.btnToggleCompanionReceiver.setText("Stop Receiving Alerts on This Phone");
            binding.tvBtStatus.setText("Ready to receive alerts from your other phone");
        } else {
            binding.btnToggleCompanionReceiver.setText("Receive Alerts on This Phone");
            binding.tvBtStatus.setText("Idle • Turn on above if this is your secondary phone");
        }
    }

    private void initAccountControls() {
        if (firebaseManager.isSignedIn()) {
            String email = firebaseManager.getCurrentUserEmail();
            binding.tvAccountSummary.setText("Signed in as " + (!TextUtils.isEmpty(email) ? email : "Google Account"));
            binding.btnSignOut.setText(R.string.sign_out);
        } else {
            binding.tvAccountSummary.setText("Using SoundBridge without an account. Sign in anytime to back up alert history.");
            binding.btnSignOut.setText("Sign In");
        }

        binding.btnSignOut.setOnClickListener(v -> {
            stopMonitoringServiceIfRunning();
            bluetoothManager.stopCompanionReceiver();
            firebaseManager.signOut(() -> {
                Intent intent = new Intent(SettingsActivity.this, LoginActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
        });

        binding.btnDeleteAccountData.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Delete Account & Data?")
                    .setMessage("This permanently removes your alert history, custom sounds, and cloud backup.")
                    .setPositiveButton("Delete", (dialog, which) -> {
                        stopMonitoringServiceIfRunning();
                        bluetoothManager.stopCompanionReceiver();
                        repository.clearAllAlertsAsync(null);
                        for (CustomSoundProfile p : repository.getCustomSoundsSync()) {
                            repository.deleteCustomSoundAsync(p, null);
                        }
                        if (firebaseManager.isSignedIn()) {
                            firebaseManager.deleteAccountAndCloudData((success, message) -> {
                                Toast.makeText(SettingsActivity.this, "Account and data deleted", Toast.LENGTH_SHORT).show();
                                Intent intent = new Intent(SettingsActivity.this, LoginActivity.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finish();
                            });
                        } else {
                            prefs.clearAuthSession();
                            Toast.makeText(this, "All local data cleared", Toast.LENGTH_SHORT).show();
                            Intent intent = new Intent(this, LoginActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            startActivity(intent);
                            finish();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private void initDiagnosticsControls() {
        binding.btnDiagDoorbell.setOnClickListener(v -> runSoundCheck(AlertEvent.CATEGORY_DOORBELL, "Doorbell"));
        binding.btnDiagKnock.setOnClickListener(v -> runSoundCheck(AlertEvent.CATEGORY_KNOCK, "Door Knock"));
        binding.btnDiagAlarm.setOnClickListener(v -> runSoundCheck(AlertEvent.CATEGORY_ALARM, "Safety Alarm"));
        binding.btnDiagBeep.setOnClickListener(v -> runSoundCheck(AlertEvent.CATEGORY_APPLIANCE_BEEP, "Appliance Chime"));

        binding.btnVerifyLocalDb.setOnClickListener(v -> runStorageHealthCheck());
        binding.btnOpenConnectionCheck.setOnClickListener(v ->
                startActivity(new Intent(this, ApiDemoActivity.class)));
    }

    private void initAdminControls() {
        // Keep the card visible so administrators can verify their role and copy their Firebase UID
        binding.cardAdminSettingsConsole.setVisibility(View.VISIBLE);

        final String uid = firebaseManager.getCurrentUserId();
        final String email = firebaseManager.getCurrentUserEmail();

        if (TextUtils.isEmpty(uid)) {
            binding.tvAdminUidInfo.setText("Firebase UID: Not signed in (Sign in with Google above first)");
            binding.tvAdminStatusSubtitle.setText("Sign-in required");
            binding.tvAdminStatusSubtitle.setTextColor(ContextCompat.getColor(this, R.color.alert_urgent_red));
            binding.btnOpenAdminDashboard.setOnClickListener(v ->
                    Toast.makeText(this, "Please sign in with Google to use admin tools", Toast.LENGTH_SHORT).show());
            return;
        }

        binding.tvAdminUidInfo.setText("Firebase UID: " + uid + " (Tap to copy)");
        binding.tvAdminUidInfo.setOnClickListener(v -> copyUidToClipboard(uid));

        binding.tvAdminStatusSubtitle.setText("Checking permissions in Firestore...");
        binding.tvAdminStatusSubtitle.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));

        firebaseManager.verifyAdminAsync((isAuthorized, message) -> {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                if (isAuthorized) {
                    binding.tvAdminStatusSubtitle.setText("Authorized Administrator ✓");
                    binding.tvAdminStatusSubtitle.setTextColor(ContextCompat.getColor(this, R.color.status_monitoring_active));
                    binding.btnOpenAdminDashboard.setEnabled(true);
                    binding.btnOpenAdminDashboard.setOnClickListener(v ->
                            startActivity(new Intent(this, AdminDashboardActivity.class)));
                } else {
                    binding.tvAdminStatusSubtitle.setText("Not authorized: " + (message != null ? message : "Pending setup"));
                    binding.tvAdminStatusSubtitle.setTextColor(ContextCompat.getColor(this, R.color.alert_urgent_red));
                    binding.btnOpenAdminDashboard.setOnClickListener(v -> showAdminSetupHelpDialog(uid, email, message));
                }
            });
        });
    }

    private void copyUidToClipboard(String uid) {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            android.content.ClipData clip = android.content.ClipData.newPlainText("Firebase UID", uid);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "UID copied to clipboard: " + uid, Toast.LENGTH_SHORT).show();
        }
    }

    private void showAdminSetupHelpDialog(String uid, String email, String reason) {
        new AlertDialog.Builder(this)
                .setTitle("Administrator Setup Required")
                .setMessage("Your account is not registered as an administrator in Firestore yet.\n\n"
                        + "Your UID:\n" + uid + "\n\n"
                        + "Diagnostic status:\n" + (reason != null ? reason : "Record not found in adminUsers collection") + "\n\n"
                        + "How to grant access in Firebase Console:\n"
                        + "1. Go to Firestore Database > adminUsers collection\n"
                        + "2. Click 'Add document'\n"
                        + "3. Document ID: Paste your UID\n"
                        + "4. Field: isAdmin (boolean) = true\n"
                        + "5. Field: email (string) = " + (!TextUtils.isEmpty(email) ? email : "your email"))
                .setPositiveButton("Copy UID", (dialog, which) -> copyUidToClipboard(uid))
                .setNegativeButton("Close", null)
                .show();
    }

    private void runSoundCheck(final String category, final String friendlyName) {
        if (SoundMonitorForegroundService.isServiceRunning()) {
            Intent testIntent = new Intent(this, SoundMonitorForegroundService.class);
            testIntent.setAction(SoundMonitorForegroundService.ACTION_INJECT_TEST_SOUND);
            testIntent.putExtra(SoundMonitorForegroundService.EXTRA_TEST_CATEGORY, category);
            startService(testIntent);
            Toast.makeText(this, "Testing " + friendlyName + " alert...", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Playing " + friendlyName + " check...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            float[] waveform = SoundClassifierEngine.generateTestSoundWaveform(category);
            WavAudioUtil.playMono16kPreview(WavAudioUtil.floatsToPcm16(waveform));
            SoundClassifierEngine engine = new SoundClassifierEngine(getApplicationContext());
            engine.classifyWindow(waveform, repository.getCustomSoundsSync());
            SoundClassifierEngine.InferenceOutcome outcome =
                    engine.classifyWindow(waveform, repository.getCustomSoundsSync());
            engine.close();

            AlertEvent alert = outcome.triggeredAlert;
            if (alert != null) {
                repository.recordAlert(alert);
                VibrationHelper.triggerAlertVibration(getApplicationContext(), alert.getCategory());
            }
            runOnUiThread(() -> {
                if (alert != null) {
                    Toast.makeText(SettingsActivity.this,
                            friendlyName + " detected (" + alert.getConfidencePercent() + "% match)",
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(SettingsActivity.this,
                            friendlyName + " sound played (enable category above to trigger alerts)",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }, "SoundBridge-SoundCheck").start();
    }

    private void runStorageHealthCheck() {
        try (Cursor cursor = getContentResolver().query(
                SoundBridgeContentProvider.CONTENT_URI_ALERTS,
                null,
                null,
                null,
                AlertDatabaseHelper.COL_TIMESTAMP + " DESC"
        )) {
            int alertCount = cursor != null ? cursor.getCount() : 0;
            int customCount = repository.getCustomSoundsSync().size();
            new AlertDialog.Builder(this)
                    .setTitle("Storage Check")
                    .setMessage(String.format(Locale.US,
                            "Local database is healthy.\n\n• Saved alerts: %d\n• Custom sound profiles: %d",
                            alertCount, customCount))
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "Unable to verify storage.", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopMonitoringServiceIfRunning() {
        if (SoundMonitorForegroundService.isServiceRunning()) {
            Intent stopIntent = new Intent(this, SoundMonitorForegroundService.class);
            stopIntent.setAction(SoundMonitorForegroundService.ACTION_STOP_MONITORING);
            startService(stopIntent);
        }
    }

    // --- BluetoothCompanionManager.CompanionStateListener ---

    @Override
    public void onCompanionStateChanged(boolean receiverActive, String connectionStatus, String lastDeliveryStatus) {
        refreshBluetoothUI();
    }

    @Override
    public void onCompanionAlertReceived(AlertEvent alert) {
        refreshBluetoothUI();
        Toast.makeText(this, "Companion Alert: " + alert.getDisplayTitle(), Toast.LENGTH_LONG).show();
    }
}
