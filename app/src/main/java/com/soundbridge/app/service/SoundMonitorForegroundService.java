package com.soundbridge.app.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.soundbridge.app.R;
import com.soundbridge.app.audio.AudioFeatureExtractor;
import com.soundbridge.app.audio.SoundClassifierEngine;
import com.soundbridge.app.bluetooth.BluetoothCompanionManager;
import com.soundbridge.app.data.AppPreferences;
import com.soundbridge.app.data.SoundBridgeRepository;
import com.soundbridge.app.firebase.FirebaseManager;
import com.soundbridge.app.model.AlertEvent;
import com.soundbridge.app.model.CustomSoundProfile;
import com.soundbridge.app.model.GlobalCalibrationProfile;
import com.soundbridge.app.receiver.SystemStateBroadcastReceiver;
import com.soundbridge.app.ui.AlertDetailActivity;
import com.soundbridge.app.ui.MainActivity;
import com.soundbridge.app.util.VibrationHelper;
import com.soundbridge.app.util.WavAudioUtil;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * User-started Foreground Microphone Service for real-time on-device environmental sound detection.
 *
 * Android reality enforced:
 * - Started only from a visible Activity after RECORD_AUDIO is granted.
 * - Immediately promotes to foreground with an unmistakable ongoing notification.
 * - Captures 16 kHz mono PCM via a single guarded AudioRecord instance on a background worker thread.
 * - Runs on-device YAMNet/AudioSet + Custom Embedding inference and immediately zeroes/discards audio buffers.
 * - Updates Firebase Realtime Database presence and posts high-visibility notifications with deep-link tap-through.
 */
public class SoundMonitorForegroundService extends Service {
    public static final String ACTION_START_MONITORING = "com.soundbridge.app.action.START_MONITORING";
    public static final String ACTION_STOP_MONITORING = "com.soundbridge.app.action.STOP_MONITORING";
    public static final String ACTION_INJECT_TEST_SOUND = "com.soundbridge.app.action.INJECT_TEST_SOUND";
    public static final String EXTRA_TEST_CATEGORY = "extra_test_category";

    public static final String CHANNEL_ID_MONITOR = "soundbridge_monitor_channel";
    public static final String CHANNEL_ID_ALERT = "soundbridge_alert_channel";
    private static final int NOTIF_ID_FOREGROUND = 1001;
    private static final int NOTIF_ID_ALERT_BASE = 2000;

    public interface MonitorStateListener {
        void onMonitorStateChanged(boolean running, String statusText);
        void onLiveAudioFrame(float rmsLevel, String topCandidateLabel, float confidence);
        void onSoundAlertDetected(AlertEvent alert);
    }

    private static volatile boolean serviceRunning = false;
    private static final List<MonitorStateListener> listeners = new CopyOnWriteArrayList<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean captureLoopActive = false;
    private Thread captureThread;
    private SoundClassifierEngine classifierEngine;
    private AppPreferences prefs;
    private SystemStateBroadcastReceiver systemReceiver;
    private volatile String pendingTestCategory = null;

    private final Object adminClipLock = new Object();
    private final float[] adminCircularBuffer = new float[AudioFeatureExtractor.ADMIN_CLIP_SAMPLES];
    private int adminBufferHead = 0;

    public static boolean isServiceRunning() {
        return serviceRunning;
    }

    public static void addListener(MonitorStateListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(MonitorStateListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new AppPreferences(this);
        ensureNotificationChannels(this);
        classifierEngine = new SoundClassifierEngine(this);
        systemReceiver = new SystemStateBroadcastReceiver();
        ContextCompat.registerReceiver(
                this,
                systemReceiver,
                SystemStateBroadcastReceiver.createIntentFilter(),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START_MONITORING;

        if (ACTION_STOP_MONITORING.equals(action)) {
            stopMonitoringCleanly("Monitoring stopped by user");
            return START_NOT_STICKY;
        }

        if (ACTION_INJECT_TEST_SOUND.equals(action) && intent != null) {
            String cat = intent.getStringExtra(EXTRA_TEST_CATEGORY);
            if (cat != null && serviceRunning) {
                pendingTestCategory = cat;
                return START_NOT_STICKY;
            }
        }

        // Verify RECORD_AUDIO permission before starting microphone capture
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            stopMonitoringCleanly("Microphone permission denied — cannot start monitoring");
            return START_NOT_STICKY;
        }

        // Promote to foreground immediately
        Notification ongoingNotif = buildOngoingMonitoringNotification(getString(R.string.notif_monitoring_text));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID_FOREGROUND, ongoingNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID_FOREGROUND, ongoingNotif);
            }
        } catch (Exception e) {
            stopMonitoringCleanly("OS blocked foreground microphone service start: " + e.getMessage());
            return START_NOT_STICKY;
        }

        if (!captureLoopActive) {
            startAudioCaptureThread();
        }
        if (intent != null && intent.hasExtra(EXTRA_TEST_CATEGORY)) {
            pendingTestCategory = intent.getStringExtra(EXTRA_TEST_CATEGORY);
        }

        // Opportunistically synchronize global calibration profiles on start
        SoundBridgeRepository.getInstance(this).syncGlobalCalibrationsAsync(null);

        return START_NOT_STICKY;
    }

    private synchronized void startAudioCaptureThread() {
        if (captureLoopActive) {
            return;
        }
        captureLoopActive = true;
        serviceRunning = true;
        notifyMonitorState(true, getString(R.string.notif_monitoring_text));
        FirebaseManager.getInstance(this).updatePresenceInRealtimeDatabase(
                true,
                true,
                SystemStateBroadcastReceiver.getCurrentBatteryPct()
        );
        SoundBridgeRepository.getInstance(this).recordSystemEvent(
                "SERVICE_START",
                "Foreground microphone monitoring started",
                SystemStateBroadcastReceiver.getCurrentBatteryPct(),
                SystemStateBroadcastReceiver.isAirplaneModeState()
        );

        captureThread = new Thread(this::runMicrophoneInferenceLoop, "SoundBridgeAudioCaptureThread");
        captureThread.setPriority(Thread.NORM_PRIORITY + 1);
        captureThread.start();
    }

    private void runMicrophoneInferenceLoop() {
        final int sampleRate = AudioFeatureExtractor.SAMPLE_RATE;
        final int windowSamples = AudioFeatureExtractor.WINDOW_SAMPLES; // 15600 samples (~0.975s)
        final int hopSamples = windowSamples / 2; // 50% overlap (~0.48s update rate)

        int minBufferBytes = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBufferBytes <= 0) {
            minBufferBytes = sampleRate * 2;
        }
        int recordBufferBytes = Math.max(minBufferBytes * 2, windowSamples * 2);

        AudioRecord recorder = null;
        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    recordBufferBytes
            );
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                notifyMonitorStateOnMain(false, "Microphone unavailable or in use by another app");
                stopSelf();
                return;
            }
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                notifyMonitorStateOnMain(false, "Microphone recording could not start (contention)");
                stopSelf();
                return;
            }

            short[] readChunk = new short[hopSamples];
            float[] rollingWindow = new float[windowSamples];
            boolean windowPrimed = false;

            while (captureLoopActive && !Thread.currentThread().isInterrupted()) {
                // Check if user triggered a live acoustic test sound for classroom demo
                String injectedCategory = pendingTestCategory;
                if (injectedCategory != null) {
                    pendingTestCategory = null;
                    float[] synthWave = SoundClassifierEngine.generateTestSoundWaveform(injectedCategory);
                    WavAudioUtil.playMono16kPreview(WavAudioUtil.floatsToPcm16(synthWave));
                    synchronized (adminClipLock) {
                        for (float f : synthWave) {
                            adminCircularBuffer[adminBufferHead] = f;
                            adminBufferHead = (adminBufferHead + 1) % adminCircularBuffer.length;
                        }
                    }
                    // Run two consecutive windows through the classifier so temporal smoothing triggers authentically
                    processInferenceWindow(synthWave);
                    processInferenceWindow(synthWave);
                    Arrays.fill(synthWave, 0f);
                    continue;
                }

                int shortsRead = 0;
                while (shortsRead < hopSamples && captureLoopActive) {
                    int r = recorder.read(readChunk, shortsRead, hopSamples - shortsRead);
                    if (r > 0) {
                        shortsRead += r;
                    } else {
                        break;
                    }
                }
                if (!captureLoopActive || shortsRead <= 0) {
                    continue;
                }

                // Shift rolling window by hopSamples and append new normalized samples
                System.arraycopy(rollingWindow, hopSamples, rollingWindow, 0, windowSamples - hopSamples);
                int destOffset = windowSamples - hopSamples;
                synchronized (adminClipLock) {
                    for (int i = 0; i < hopSamples; i++) {
                        float sample = (i < shortsRead) ? (readChunk[i] / 32768.0f) : 0f;
                        rollingWindow[destOffset + i] = sample;
                        adminCircularBuffer[adminBufferHead] = sample;
                        adminBufferHead = (adminBufferHead + 1) % adminCircularBuffer.length;
                    }
                }
                // Immediately zero out raw short chunk after copy
                Arrays.fill(readChunk, (short) 0);

                if (!windowPrimed) {
                    windowPrimed = true;
                    continue;
                }

                processInferenceWindow(rollingWindow);

                // Low-battery response: if battery is low and throttle enabled, pause briefly between hops
                if (SystemStateBroadcastReceiver.isLowBatteryState() && prefs.isLowPowerThrottleEnabled()) {
                    try {
                        Thread.sleep(350L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            // Zero rolling buffer on exit so no raw ambient audio remains in memory
            Arrays.fill(rollingWindow, 0f);
            synchronized (adminClipLock) {
                Arrays.fill(adminCircularBuffer, 0f);
            }
        } catch (SecurityException se) {
            notifyMonitorStateOnMain(false, "Microphone permission revoked while monitoring");
        } catch (Throwable t) {
            notifyMonitorStateOnMain(false, "Audio capture stopped: " + t.getMessage());
        } finally {
            if (recorder != null) {
                try {
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop();
                    }
                } catch (Exception ignored) {
                }
                try {
                    recorder.release();
                } catch (Exception ignored) {
                }
            }
            captureLoopActive = false;
            serviceRunning = false;
        }
    }

    private void processInferenceWindow(float[] windowSamples) {
        List<CustomSoundProfile> customProfiles = SoundBridgeRepository.getInstance(this).getCustomSoundsSync();
        List<GlobalCalibrationProfile> globalCalibrations = SoundBridgeRepository.getInstance(this).getActiveGlobalCalibrationsSync();
        final SoundClassifierEngine.InferenceOutcome outcome =
                classifierEngine.classifyWindow(windowSamples, customProfiles, globalCalibrations);

        final String displayLabel;
        final float displayConfidence;
        if (outcome.activeCandidate != null) {
            displayLabel = outcome.activeCandidate.label;
            displayConfidence = outcome.activeCandidate.confidence;
        } else {
            displayLabel = outcome.rmsLevel < 0.012f
                    ? "Quiet surroundings"
                    : "Listening to your surroundings";
            displayConfidence = 0f;
        }

        mainHandler.post(() -> {
            for (MonitorStateListener l : listeners) {
                l.onLiveAudioFrame(outcome.rmsLevel, displayLabel, displayConfidence);
            }
        });

        if (outcome.triggeredAlert != null) {
            handleConfirmedAlert(outcome.triggeredAlert);
        }
    }

    private void handleConfirmedAlert(final AlertEvent alert) {
        // Admin audio clip capture: If current user is an admin, save 2-second pre-event audio snapshot locally
        String currentUid = FirebaseManager.getInstance(this).getCurrentUserId();
        boolean isAdmin = currentUid != null && prefs.isAdminVerifiedHintFor(currentUid);
        if (isAdmin) {
            float[] clipSnapshot = new float[AudioFeatureExtractor.ADMIN_CLIP_SAMPLES];
            synchronized (adminClipLock) {
                for (int i = 0; i < AudioFeatureExtractor.ADMIN_CLIP_SAMPLES; i++) {
                    int srcIdx = (adminBufferHead + i) % AudioFeatureExtractor.ADMIN_CLIP_SAMPLES;
                    clipSnapshot[i] = adminCircularBuffer[srcIdx];
                }
            }
            String clipPath = SoundBridgeRepository.getInstance(this).buildAdminClipPath(alert.getId());
            alert.setLocalClipPath(clipPath);
            SoundBridgeRepository.getInstance(this).writeAdminClipAsync(alert, clipSnapshot);
        }

        // 1. Persist locally and queue Firestore + RTDB sync
        SoundBridgeRepository.getInstance(this).recordAlert(alert);

        // 2. Vibrate with category-specific tactile pattern
        VibrationHelper.triggerAlertVibration(this, alert.getCategory());

        // 3. Post high-priority visual notification with deep link to AlertDetailActivity
        postDetectedSoundNotification(alert);

        // 4. Forward over Bluetooth RFCOMM if a paired companion phone is configured
        String companionAddr = prefs.getBtCompanionAddress();
        if (companionAddr != null && !companionAddr.trim().isEmpty()) {
            BluetoothCompanionManager.getInstance(this).sendAlertToCompanionAsync(alert, null);
        }

        // 5. Notify live in-app UI listeners
        mainHandler.post(() -> {
            for (MonitorStateListener l : listeners) {
                l.onSoundAlertDetected(alert);
            }
        });
    }

    private void stopMonitoringCleanly(String reason) {
        captureLoopActive = false;
        serviceRunning = false;
        if (captureThread != null) {
            captureThread.interrupt();
            captureThread = null;
        }
        FirebaseManager.getInstance(this).updatePresenceInRealtimeDatabase(
                true,
                false,
                SystemStateBroadcastReceiver.getCurrentBatteryPct()
        );
        SoundBridgeRepository.getInstance(this).recordSystemEvent(
                "SERVICE_STOP",
                reason,
                SystemStateBroadcastReceiver.getCurrentBatteryPct(),
                SystemStateBroadcastReceiver.isAirplaneModeState()
        );
        notifyMonitorState(false, reason);
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        captureLoopActive = false;
        serviceRunning = false;
        if (captureThread != null) {
            captureThread.interrupt();
            captureThread = null;
        }
        if (systemReceiver != null) {
            try {
                unregisterReceiver(systemReceiver);
            } catch (Exception ignored) {
            }
            systemReceiver = null;
        }
        if (classifierEngine != null) {
            classifierEngine.close();
        }
        notifyMonitorState(false, getString(R.string.status_monitoring_stopped));
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void ensureNotificationChannels(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            NotificationChannel monitorChannel = new NotificationChannel(
                    CHANNEL_ID_MONITOR,
                    context.getString(R.string.notif_channel_monitor_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            monitorChannel.setDescription(context.getString(R.string.notif_channel_monitor_desc));
            nm.createNotificationChannel(monitorChannel);

            NotificationChannel alertChannel = new NotificationChannel(
                    CHANNEL_ID_ALERT,
                    context.getString(R.string.notif_channel_alert_name),
                    NotificationManager.IMPORTANCE_HIGH
            );
            alertChannel.setDescription(context.getString(R.string.notif_channel_alert_desc));
            alertChannel.enableVibration(true);
            nm.createNotificationChannel(alertChannel);
        }
    }

    private Notification buildOngoingMonitoringNotification(String contentText) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                this,
                101,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, SoundMonitorForegroundService.class);
        stopIntent.setAction(ACTION_STOP_MONITORING);
        PendingIntent stopPending = PendingIntent.getService(
                this,
                102,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID_MONITOR)
                .setSmallIcon(R.drawable.ic_sound_wave)
                .setContentTitle(getString(R.string.notif_monitoring_title))
                .setContentText(contentText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openPending)
                .addAction(0, getString(R.string.notif_action_open), openPending)
                .addAction(0, getString(R.string.notif_action_stop), stopPending)
                .build();
    }

    public void postDetectedSoundNotification(AlertEvent alert) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent detailIntent = new Intent(this, AlertDetailActivity.class);
        detailIntent.putExtra(AlertDetailActivity.EXTRA_ALERT_ID, alert.getId());
        detailIntent.putExtra(AlertDetailActivity.EXTRA_ALERT_LABEL, alert.getLabel());
        detailIntent.putExtra(AlertDetailActivity.EXTRA_ALERT_RAW_LABEL, alert.getRawModelLabel());
        detailIntent.putExtra(AlertDetailActivity.EXTRA_ALERT_CATEGORY, alert.getCategory());
        detailIntent.putExtra(AlertDetailActivity.EXTRA_ALERT_SOURCE, alert.getSource());
        detailIntent.putExtra(AlertDetailActivity.EXTRA_ALERT_CONFIDENCE, alert.getConfidence());
        detailIntent.putExtra(AlertDetailActivity.EXTRA_ALERT_TIMESTAMP, alert.getTimestampMs());
        detailIntent.putExtra(AlertDetailActivity.EXTRA_ALERT_DELIVERY, alert.getDeliveryStatus());
        detailIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int reqCode = Math.abs(alert.getId().hashCode() % 10000) + NOTIF_ID_ALERT_BASE;
        PendingIntent detailPending = PendingIntent.getActivity(
                this,
                reqCode,
                detailIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, SoundMonitorForegroundService.class);
        stopIntent.setAction(ACTION_STOP_MONITORING);
        PendingIntent stopPending = PendingIntent.getService(
                this,
                103,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String title = alert.getDisplayTitle();
        String body = String.format(
                Locale.US,
                "%s • %d%% match — Tap to view details",
                alert.getReadableCategory(),
                alert.getConfidencePercent()
        );

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
                .setSmallIcon(R.drawable.ic_alert_bell)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(detailPending)
                .addAction(0, getString(R.string.notif_action_open), detailPending)
                .addAction(0, getString(R.string.notif_action_stop), stopPending)
                .build();

        nm.notify(reqCode, notif);
    }

    private void notifyMonitorStateOnMain(final boolean running, final String text) {
        mainHandler.post(() -> notifyMonitorState(running, text));
    }

    private void notifyMonitorState(boolean running, String text) {
        for (MonitorStateListener l : listeners) {
            l.onMonitorStateChanged(running, text);
        }
    }
}

