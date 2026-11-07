package com.soundbridge.app.data;

import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;

import com.soundbridge.app.audio.AudioFeatureExtractor;
import com.soundbridge.app.firebase.FirebaseManager;
import com.soundbridge.app.model.AlertEvent;
import com.soundbridge.app.model.CalibrationAuditRecord;
import com.soundbridge.app.model.CustomSoundProfile;
import com.soundbridge.app.model.GlobalCalibrationProfile;
import com.soundbridge.app.model.SystemEventRecord;
import com.soundbridge.app.util.WavAudioUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single repository coordinating UI state, SQLite local cache, ContentProvider notifications,
 * app-private custom sound storage, and asynchronous Cloud Firestore / Realtime Database synchronization.
 */
public class SoundBridgeRepository {
    private static SoundBridgeRepository instance;

    private final Context appContext;
    private final AlertDatabaseHelper dbHelper;
    private final AppPreferences prefs;
    private final FirebaseManager firebaseManager;
    private final ExecutorService bgExecutor;
    private final Handler mainHandler;
    private final List<AlertListener> alertListeners = new CopyOnWriteArrayList<>();

    public interface AlertListener {
        void onAlertRecorded(AlertEvent alert);
        void onAlertsChanged();
    }

    public interface DataCallback<T> {
        void onResult(T data);
    }

    private SoundBridgeRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.dbHelper = new AlertDatabaseHelper(appContext);
        this.prefs = new AppPreferences(appContext);
        this.firebaseManager = FirebaseManager.getInstance(appContext);
        this.bgExecutor = Executors.newFixedThreadPool(2);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized SoundBridgeRepository getInstance(Context context) {
        if (instance == null) {
            instance = new SoundBridgeRepository(context);
        }
        return instance;
    }

    public void addAlertListener(AlertListener listener) {
        if (listener != null && !alertListeners.contains(listener)) {
            alertListeners.add(listener);
        }
    }

    public void removeAlertListener(AlertListener listener) {
        alertListeners.remove(listener);
    }

    /**
     * Persists a newly detected or companion-received alert locally first, notifies ContentResolver
     * and UI listeners, and asynchronously syncs to Cloud Firestore & Realtime Database if signed in.
     */
    public void recordAlert(final AlertEvent alert) {
        if (alert == null) return;
        bgExecutor.execute(() -> {
            if (!firebaseManager.isSignedIn()) {
                alert.setSyncStatus(prefs.isLocalDemoMode() ? AlertEvent.SYNC_LOCAL_DEMO : AlertEvent.SYNC_PENDING);
            } else {
                alert.setSyncStatus(AlertEvent.SYNC_PENDING);
            }
            dbHelper.upsertAlert(alert);
            notifyContentProviderChanged();

            mainHandler.post(() -> {
                for (AlertListener l : alertListeners) {
                    l.onAlertRecorded(alert);
                    l.onAlertsChanged();
                }
            });

            if (firebaseManager.isSignedIn()) {
                firebaseManager.syncAlertToCloud(alert, (success, message) -> {
                    bgExecutor.execute(() -> {
                        alert.setSyncStatus(success ? AlertEvent.SYNC_SYNCED : AlertEvent.SYNC_FAILED);
                        dbHelper.upsertAlert(alert);
                        notifyContentProviderChanged();
                        mainHandler.post(() -> {
                            for (AlertListener l : alertListeners) {
                                l.onAlertsChanged();
                            }
                        });
                    });
                });
            }
        });
    }

    /**
     * Updates an existing alert (e.g., acknowledged state or Bluetooth delivery status) and re-syncs.
     */
    public void updateAlert(final AlertEvent alert) {
        if (alert == null) return;
        bgExecutor.execute(() -> {
            dbHelper.upsertAlert(alert);
            notifyContentProviderChanged();
            if (firebaseManager.isSignedIn()) {
                firebaseManager.syncAlertToCloud(alert, null);
            }
            mainHandler.post(() -> {
                for (AlertListener l : alertListeners) {
                    l.onAlertsChanged();
                }
            });
        });
    }

    public void loadAlertsAsync(final String sourceFilter, final DataCallback<List<AlertEvent>> callback) {
        bgExecutor.execute(() -> {
            final List<AlertEvent> list = dbHelper.queryAlerts(sourceFilter);
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(list));
            }
        });
    }

    public void loadAlertByIdAsync(final String eventId, final DataCallback<AlertEvent> callback) {
        bgExecutor.execute(() -> {
            final AlertEvent alert = dbHelper.getAlertById(eventId);
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(alert));
            }
        });
    }

    /**
     * Queries alerts via ContentResolver against the app-owned SoundBridgeContentProvider.
     */
    public void queryAlertsViaContentProviderAsync(final DataCallback<List<AlertEvent>> callback) {
        bgExecutor.execute(() -> {
            List<AlertEvent> results = new ArrayList<>();
            try (Cursor c = appContext.getContentResolver().query(
                    SoundBridgeContentProvider.CONTENT_URI_ALERTS,
                    null,
                    null,
                    null,
                    AlertDatabaseHelper.COL_TIMESTAMP + " DESC"
            )) {
                while (c != null && c.moveToNext()) {
                    results.add(AlertDatabaseHelper.cursorToAlert(c));
                }
            } catch (Exception ignored) {
            }
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(results));
            }
        });
    }

    public void deleteAlertAsync(final String eventId, final Runnable onDone) {
        bgExecutor.execute(() -> {
            AlertEvent existing = dbHelper.getAlertById(eventId);
            if (existing != null) deleteClipFile(existing.getLocalClipPath());
            dbHelper.deleteAlert(eventId);
            notifyContentProviderChanged();
            firebaseManager.deleteAlertFromFirestore(eventId);
            mainHandler.post(() -> {
                for (AlertListener l : alertListeners) {
                    l.onAlertsChanged();
                }
                if (onDone != null) onDone.run();
            });
        });
    }

    public void clearAllAlertsAsync(final Runnable onDone) {
        bgExecutor.execute(() -> {
            List<AlertEvent> existing = dbHelper.queryAlerts("ALL");
            for (AlertEvent a : existing) {
                deleteClipFile(a.getLocalClipPath());
            }
            dbHelper.clearAllAlerts();
            notifyContentProviderChanged();
            if (firebaseManager.isSignedIn()) {
                for (AlertEvent a : existing) {
                    firebaseManager.deleteAlertFromFirestore(a.getId());
                }
            }
            mainHandler.post(() -> {
                for (AlertListener l : alertListeners) {
                    l.onAlertsChanged();
                }
                if (onDone != null) onDone.run();
            });
        });
    }

    /**
     * Retries syncing any pending/failed offline alerts to Firestore without creating duplicates
     * (thanks to deterministic event IDs).
     */
    public void retryPendingAlertsSync() {
        if (!firebaseManager.isSignedIn()) return;
        bgExecutor.execute(() -> {
            List<AlertEvent> pending = dbHelper.getPendingSyncAlerts();
            for (AlertEvent alert : pending) {
                boolean ok = firebaseManager.writeAlertToFirestoreSync(
                        firebaseManager.getCurrentUid(),
                        prefs.getAuthIdToken(),
                        alert
                );
                if (ok) {
                    alert.setSyncStatus(AlertEvent.SYNC_SYNCED);
                    dbHelper.upsertAlert(alert);
                }
            }
            if (!pending.isEmpty()) {
                notifyContentProviderChanged();
                mainHandler.post(() -> {
                    for (AlertListener l : alertListeners) {
                        l.onAlertsChanged();
                    }
                });
            }
        });
    }

    // Custom Sounds Management

    public List<CustomSoundProfile> getCustomSoundsSync() {
        return dbHelper.getAllCustomSounds();
    }

    public void loadCustomSoundsAsync(final DataCallback<List<CustomSoundProfile>> callback) {
        bgExecutor.execute(() -> {
            final List<CustomSoundProfile> list = dbHelper.getAllCustomSounds();
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(list));
            }
        });
    }

    public void saveCustomSoundAsync(final CustomSoundProfile profile, final Runnable onDone) {
        bgExecutor.execute(() -> {
            dbHelper.upsertCustomSound(profile);
            firebaseManager.syncCustomSoundMetadataToFirestore(profile);
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    public void deleteCustomSoundAsync(final CustomSoundProfile profile, final Runnable onDone) {
        if (profile == null) return;
        bgExecutor.execute(() -> {
            for (String path : profile.getSampleFilePaths()) {
                if (path != null && !path.isEmpty()) {
                    File f = new File(path);
                    if (f.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
            dbHelper.deleteCustomSound(profile.getId());
            firebaseManager.deleteCustomSoundFromFirestore(profile.getId());
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    public File getCustomSoundsDirectory() {
        File dir = new File(appContext.getFilesDir(), "custom_sounds");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    // System Events Logging

    public void recordSystemEvent(String type, String description, int batteryPct, boolean airplaneMode) {
        final SystemEventRecord record = new SystemEventRecord(
                null,
                type,
                description,
                System.currentTimeMillis(),
                batteryPct,
                airplaneMode
        );
        bgExecutor.execute(() -> {
            dbHelper.insertSystemEvent(record);
            firebaseManager.syncSystemEventToFirestore(record);
        });
    }

    public void loadRecentSystemEventsAsync(final int limit, final DataCallback<List<SystemEventRecord>> callback) {
        bgExecutor.execute(() -> {
            final List<SystemEventRecord> list = dbHelper.getRecentSystemEvents(limit);
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(list));
            }
        });
    }

    private void notifyContentProviderChanged() {
        try {
            appContext.getContentResolver().notifyChange(SoundBridgeContentProvider.CONTENT_URI_ALERTS, null);
        } catch (Exception ignored) {
        }
    }

    // =====================================================================================
    // Global Sound Calibration (admin-published acoustic reference profiles)
    // =====================================================================================

    public static final int MAX_ADMIN_CLIPS = 120;
    private volatile List<GlobalCalibrationProfile> activeCalibrationCache = null;
    private volatile boolean calibrationSyncInFlight = false;

    /**
     * Active, engine-compatible calibration profiles from the offline SQLite cache (memoized).
     * Safe to call from the audio inference thread.
     */
    public List<GlobalCalibrationProfile> getActiveGlobalCalibrationsSync() {
        List<GlobalCalibrationProfile> cached = activeCalibrationCache;
        if (cached != null) return cached;
        List<GlobalCalibrationProfile> loaded = new ArrayList<>();
        for (GlobalCalibrationProfile p : dbHelper.getGlobalCalibrations(true)) {
            if (p.isCompatibleWithCurrentEngine()) loaded.add(p);
        }
        List<GlobalCalibrationProfile> immutable = Collections.unmodifiableList(loaded);
        activeCalibrationCache = immutable;
        return immutable;
    }

    public List<GlobalCalibrationProfile> getAllGlobalCalibrationsSync() {
        return dbHelper.getGlobalCalibrations(false);
    }

    private void invalidateCalibrationCache() {
        activeCalibrationCache = null;
    }

    public void loadGlobalCalibrationsAsync(final boolean activeOnly, final DataCallback<List<GlobalCalibrationProfile>> callback) {
        bgExecutor.execute(() -> {
            final List<GlobalCalibrationProfile> list = dbHelper.getGlobalCalibrations(activeOnly);
            if (callback != null) mainHandler.post(() -> callback.onResult(list));
        });
    }

    /**
     * Incrementally synchronizes global calibration profiles from Cloud Firestore into the offline cache.
     * Applies version-aware conflict resolution (higher profileVersion / newer updatedAt wins),
     * removes deleted tombstones, and keeps deactivated profiles cached but inactive.
     */
    public void syncGlobalCalibrationsAsync(final FirebaseManager.CompletionCallback callback) {
        if (!firebaseManager.isFirebaseConfigured() || calibrationSyncInFlight) {
            if (callback != null) mainHandler.post(() -> callback.onComplete(false, "Sync unavailable"));
            return;
        }
        calibrationSyncInFlight = true;
        bgExecutor.execute(() -> {
            try {
                FirebaseManager.CalibrationSyncResult result =
                        firebaseManager.fetchCalibrationChangesSync(prefs.getCalibrationLastSyncMs());
                if (result.success) {
                    applyRemoteCalibrationChanges(result.changedProfiles);
                    prefs.setCalibrationLastSyncMs(result.newCursorMs);
                }
                if (callback != null) {
                    mainHandler.post(() -> callback.onComplete(result.success, result.message));
                }
            } finally {
                calibrationSyncInFlight = false;
            }
        });
    }

    /**
     * Merges remote calibration changes into the local cache (package-visible for tests via the DB helper).
     */
    void applyRemoteCalibrationChanges(List<GlobalCalibrationProfile> changed) {
        if (changed == null || changed.isEmpty()) return;
        for (GlobalCalibrationProfile remote : changed) {
            if (remote.isDeleted()) {
                dbHelper.deleteGlobalCalibration(remote.getCalibrationId());
                continue;
            }
            GlobalCalibrationProfile local = dbHelper.getGlobalCalibrationById(remote.getCalibrationId());
            if (shouldAcceptRemoteCalibration(local, remote)) {
                dbHelper.upsertGlobalCalibration(remote);
            }
        }
        invalidateCalibrationCache();
    }

    public static boolean shouldAcceptRemoteCalibration(GlobalCalibrationProfile local, GlobalCalibrationProfile remote) {
        if (remote == null) return false;
        if (local == null) return true;
        if (remote.getProfileVersion() != local.getProfileVersion()) {
            return remote.getProfileVersion() > local.getProfileVersion();
        }
        return remote.getUpdatedAtMs() >= local.getUpdatedAtMs();
    }

    public void loadCalibrationAuditAsync(final int limit, final DataCallback<List<CalibrationAuditRecord>> callback) {
        bgExecutor.execute(() -> {
            List<CalibrationAuditRecord> remote = firebaseManager.fetchRecentAuditLogsSync(limit);
            for (CalibrationAuditRecord r : remote) {
                dbHelper.insertCalibrationAuditRecord(r);
            }
            final List<CalibrationAuditRecord> merged = dbHelper.getRecentCalibrationAuditRecords(limit);
            if (callback != null) mainHandler.post(() -> callback.onResult(merged));
        });
    }

    /**
     * Admin-only: publishes a global correction derived from the alert's locally stored 2-second clip.
     * - Re-verifies administrator privileges with Firebase before any write.
     * - Extracts 64-D acoustic embeddings using the live-inference preprocessing pipeline.
     * - Creates a new profile, or strengthens an existing one (existingCalibrationId) with new examples.
     * - Uploads ONLY embeddings + metadata. The WAV clip never leaves the device.
     */
    public void publishGlobalCorrectionAsync(
            final AlertEvent alert,
            final String canonicalLabel,
            final String category,
            final float similarityThreshold,
            final String existingCalibrationId,
            final FirebaseManager.CompletionCallback callback
    ) {
        bgExecutor.execute(() -> {
            String error = null;
            GlobalCalibrationProfile profile = null;
            CalibrationAuditRecord audit = null;
            try {
                if (alert == null || !alert.hasLocalClip()) {
                    error = "This detection has no local recording to learn from.";
                } else if (canonicalLabel == null || canonicalLabel.trim().isEmpty()) {
                    error = "Enter the correct sound name.";
                } else {
                    File clip = new File(alert.getLocalClipPath());
                    if (!clip.exists()) {
                        error = "The local recording was removed from this device.";
                    } else {
                        float[] samples = WavAudioUtil.readWavFileToNormalizedFloats(clip);
                        List<float[]> embeddings = AudioFeatureExtractor.extractCalibrationEmbeddingsFromClip(samples);
                        Arrays.fill(samples, 0f);
                        if (embeddings.isEmpty()) {
                            error = "The recording is too quiet to learn a sound pattern from.";
                        } else {
                            long now = System.currentTimeMillis();
                            String uid = firebaseManager.getCurrentUid();
                            String adminName = prefs.getAuthDisplayName();
                            GlobalCalibrationProfile existing = (existingCalibrationId != null && !existingCalibrationId.isEmpty())
                                    ? dbHelper.getGlobalCalibrationById(existingCalibrationId)
                                    : null;
                            if (existing != null) {
                                String prevLabel = existing.getCanonicalLabel();
                                existing.addReferenceEmbeddings(embeddings);
                                existing.setActive(true);
                                existing.incrementVersion(now, uid, adminName);
                                profile = existing;
                                audit = new CalibrationAuditRecord(null, existing.getCalibrationId(),
                                        CalibrationAuditRecord.ACTION_STRENGTHEN, prevLabel, existing.getCanonicalLabel(),
                                        uid, prefs.getAuthEmail(), now,
                                        "Added recording originally predicted as \"" + alert.getCleanOriginalPredictionTitle() + "\"");
                            } else {
                                String resolvedCategory = (category != null && !category.trim().isEmpty())
                                        ? category
                                        : GlobalCalibrationProfile.inferCanonicalCategoryFromLabel(canonicalLabel);
                                profile = new GlobalCalibrationProfile(
                                        null,
                                        canonicalLabel.trim(),
                                        resolvedCategory,
                                        alert.getCleanOriginalPredictionTitle(),
                                        embeddings,
                                        GlobalCalibrationProfile.EMBEDDING_MODEL_ID,
                                        GlobalCalibrationProfile.CURRENT_EMBEDDING_VERSION,
                                        AudioFeatureExtractor.SAMPLE_RATE,
                                        AudioFeatureExtractor.WINDOW_SAMPLES,
                                        similarityThreshold,
                                        now,
                                        now,
                                        uid,
                                        adminName,
                                        1,
                                        true
                                );
                                audit = new CalibrationAuditRecord(null, profile.getCalibrationId(),
                                        CalibrationAuditRecord.ACTION_CREATE, alert.getCleanOriginalPredictionTitle(),
                                        profile.getCanonicalLabel(), uid, prefs.getAuthEmail(), now,
                                        String.format(Locale.US, "%d reference pattern(s), threshold %d%%",
                                                profile.getExampleCount(), Math.round(profile.getSimilarityThreshold() * 100f)));
                            }
                            error = firebaseManager.commitCalibrationWithAuditSync(profile, audit);
                        }
                    }
                }
            } catch (Exception e) {
                error = "Could not read the local recording.";
            }

            if (error == null && profile != null) {
                dbHelper.upsertGlobalCalibration(profile);
                dbHelper.insertCalibrationAuditRecord(audit);
                alert.setCalibratedByAdmin(true);
                alert.setAppliedCalibrationId(profile.getCalibrationId());
                dbHelper.upsertAlert(alert);
                invalidateCalibrationCache();
                notifyContentProviderChanged();
            }
            final String finalError = error;
            final String label = profile != null ? profile.getCanonicalLabel() : "";
            mainHandler.post(() -> {
                for (AlertListener l : alertListeners) l.onAlertsChanged();
                if (callback != null) {
                    callback.onComplete(finalError == null, finalError == null
                            ? "\"" + label + "\" published to all SoundBridge devices"
                            : finalError);
                }
            });
        });
    }

    /**
     * Admin-only management actions on a published profile: UPDATE (label/threshold), DEACTIVATE,
     * REACTIVATE, DELETE (tombstone so every device removes it on next sync). Always audited.
     */
    public void manageGlobalCalibrationAsync(
            final GlobalCalibrationProfile profile,
            final String actionType,
            final String newLabel,
            final float newThreshold,
            final FirebaseManager.CompletionCallback callback
    ) {
        bgExecutor.execute(() -> {
            long now = System.currentTimeMillis();
            String uid = firebaseManager.getCurrentUid();
            String adminName = prefs.getAuthDisplayName();
            String prevLabel = profile.getCanonicalLabel();
            String summary;

            if (CalibrationAuditRecord.ACTION_DEACTIVATE.equals(actionType)) {
                profile.setActive(false);
                summary = "Stopped applying on all devices";
            } else if (CalibrationAuditRecord.ACTION_REACTIVATE.equals(actionType)) {
                profile.setActive(true);
                summary = "Resumed applying on all devices";
            } else if (CalibrationAuditRecord.ACTION_DELETE.equals(actionType)) {
                summary = "Removed from global registry";
            } else {
                if (newLabel != null && !newLabel.trim().isEmpty()) {
                    profile.setCanonicalLabel(newLabel);
                    profile.setCategory(GlobalCalibrationProfile.inferCanonicalCategoryFromLabel(newLabel));
                }
                if (newThreshold > 0f) profile.setSimilarityThreshold(newThreshold);
                summary = String.format(Locale.US, "Threshold %d%%", Math.round(profile.getSimilarityThreshold() * 100f));
            }

            if (CalibrationAuditRecord.ACTION_DELETE.equals(actionType)) {
                profile.markDeleted(now, uid, adminName);
            } else {
                profile.incrementVersion(now, uid, adminName);
            }

            CalibrationAuditRecord audit = new CalibrationAuditRecord(null, profile.getCalibrationId(), actionType,
                    prevLabel, profile.isDeleted() ? "" : profile.getCanonicalLabel(), uid, prefs.getAuthEmail(), now, summary);
            String error = firebaseManager.commitCalibrationWithAuditSync(profile, audit);
            if (error == null) {
                dbHelper.upsertGlobalCalibration(profile); // deletes locally when tombstoned
                dbHelper.insertCalibrationAuditRecord(audit);
                invalidateCalibrationCache();
            }
            mainHandler.post(() -> {
                if (callback != null) callback.onComplete(error == null, error == null ? audit.getReadableAction() : error);
            });
        });
    }

    // --- Local admin review clips (app-private storage, never uploaded) ---

    public File getAdminRecordingsDirectory() {
        File dir = new File(appContext.getFilesDir(), "admin_recordings");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Deterministic app-private path for an alert's admin review clip.
     */
    public String buildAdminClipPath(String alertId) {
        String safe = alertId != null ? alertId.replaceAll("[^a-zA-Z0-9_]", "") : String.valueOf(System.currentTimeMillis());
        return new File(getAdminRecordingsDirectory(), "admin_clip_" + safe + ".wav").getAbsolutePath();
    }

    /**
     * Writes a ~2 s 16 kHz mono clip to the alert's pre-assigned local path (see buildAdminClipPath).
     * On I/O failure, the clip link is removed so the UI never shows an unplayable recording.
     */
    public void writeAdminClipAsync(final AlertEvent alert, final float[] clipSamples) {
        if (alert == null || !alert.hasLocalClip() || clipSamples == null || clipSamples.length == 0) return;
        final String path = alert.getLocalClipPath();
        bgExecutor.execute(() -> {
            File out = new File(path);
            boolean ok = false;
            try {
                WavAudioUtil.writeMono16BitWav(out, WavAudioUtil.floatsToPcm16(clipSamples), AudioFeatureExtractor.SAMPLE_RATE);
                ok = true;
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            } finally {
                Arrays.fill(clipSamples, 0f);
            }
            if (!ok) {
                alert.setLocalClipPath("");
                dbHelper.clearAlertClipPath(alert.getId());
            } else {
                pruneAdminClips();
            }
            notifyContentProviderChanged();
            mainHandler.post(() -> {
                for (AlertListener l : alertListeners) l.onAlertsChanged();
            });
        });
    }

    private static void deleteClipFile(String path) {
        if (path == null || path.trim().isEmpty()) return;
        File f = new File(path);
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /**
     * Keeps local review storage bounded: deletes the oldest clips beyond MAX_ADMIN_CLIPS
     * and orphaned files no longer referenced by any alert.
     */
    private void pruneAdminClips() {
        File[] files = getAdminRecordingsDirectory().listFiles();
        if (files == null) return;
        java.util.Set<String> referenced = new java.util.HashSet<>();
        for (AlertEvent a : dbHelper.queryAlerts("ALL")) {
            if (a.hasLocalClip()) referenced.add(new File(a.getLocalClipPath()).getAbsolutePath());
        }
        List<File> live = new ArrayList<>();
        for (File f : files) {
            if (!referenced.contains(f.getAbsolutePath())) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } else {
                live.add(f);
            }
        }
        if (live.size() > MAX_ADMIN_CLIPS) {
            Collections.sort(live, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
            for (int i = 0; i < live.size() - MAX_ADMIN_CLIPS; i++) {
                //noinspection ResultOfMethodCallIgnored
                live.get(i).delete();
            }
        }
    }
}

