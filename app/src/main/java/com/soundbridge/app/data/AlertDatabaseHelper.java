package com.soundbridge.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.soundbridge.app.model.AlertEvent;
import com.soundbridge.app.model.CalibrationAuditRecord;
import com.soundbridge.app.model.CustomSoundProfile;
import com.soundbridge.app.model.GlobalCalibrationProfile;
import com.soundbridge.app.model.SystemEventRecord;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Local SQLite database backing offline alert history, custom sound metadata/embeddings,
 * globally synchronized sound calibration profiles, administrative audit trail,
 * diagnostic system events, and the app-owned read-only SoundBridgeContentProvider.
 */
public class AlertDatabaseHelper extends SQLiteOpenHelper {
    public static final String DB_NAME = "soundbridge_local.db";
    public static final int DB_VERSION = 2;

    public static final String TABLE_ALERTS = "alerts";
    public static final String COL_ID = "_id";
    public static final String COL_EVENT_ID = "event_id";
    public static final String COL_LABEL = "label";
    public static final String COL_RAW_LABEL = "raw_model_label";
    public static final String COL_CATEGORY = "category";
    public static final String COL_SOURCE = "source";
    public static final String COL_CONFIDENCE = "confidence";
    public static final String COL_ORIGINAL_PREDICTION = "original_prediction";
    public static final String COL_ORIGINAL_CONFIDENCE = "original_confidence";
    public static final String COL_APPLIED_CALIBRATION_ID = "applied_calibration_id";
    public static final String COL_TIMESTAMP = "timestamp_ms";
    public static final String COL_DEVICE_ID = "device_id";
    public static final String COL_ACKNOWLEDGED = "acknowledged";
    public static final String COL_DELIVERY_STATUS = "delivery_status";
    public static final String COL_SYNC_STATUS = "sync_status";
    public static final String COL_LOCAL_CLIP_PATH = "local_clip_path";
    public static final String COL_IS_CALIBRATED = "is_calibrated";

    public static final String TABLE_CUSTOM_SOUNDS = "custom_sounds";
    public static final String COL_CS_ID = "sound_id";
    public static final String COL_CS_NAME = "name";
    public static final String COL_CS_CREATED = "created_at_ms";
    public static final String COL_CS_ENABLED = "enabled";
    public static final String COL_CS_THRESHOLD = "matching_threshold";
    public static final String COL_CS_MODEL_VER = "model_version";
    public static final String COL_CS_EMBEDDINGS_JSON = "embeddings_json";
    public static final String COL_CS_PATHS_JSON = "sample_paths_json";

    public static final String TABLE_GLOBAL_CALIBRATIONS = "global_calibrations";
    public static final String COL_GC_ID = "calibration_id";
    public static final String COL_GC_CANONICAL_LABEL = "canonical_label";
    public static final String COL_GC_CATEGORY = "category";
    public static final String COL_GC_ORIG_PREDICTION = "original_prediction";
    public static final String COL_GC_EMBEDDINGS_JSON = "embeddings_json";
    public static final String COL_GC_MODEL_ID = "model_id";
    public static final String COL_GC_MODEL_VER = "model_version";
    public static final String COL_GC_SAMPLE_RATE = "sample_rate_hz";
    public static final String COL_GC_WINDOW_SAMPLES = "window_samples";
    public static final String COL_GC_THRESHOLD = "similarity_threshold";
    public static final String COL_GC_CREATED = "created_at_ms";
    public static final String COL_GC_UPDATED = "updated_at_ms";
    public static final String COL_GC_ADMIN_UID = "admin_uid";
    public static final String COL_GC_ADMIN_NAME = "admin_name";
    public static final String COL_GC_PROFILE_VER = "profile_version";
    public static final String COL_GC_ACTIVE = "active";

    public static final String TABLE_CALIBRATION_AUDIT = "calibration_audit_logs";
    public static final String COL_AUD_ID = "audit_id";
    public static final String COL_AUD_CAL_ID = "calibration_id";
    public static final String COL_AUD_ACTION = "action_type";
    public static final String COL_AUD_PREV_LABEL = "previous_label";
    public static final String COL_AUD_NEW_LABEL = "new_label";
    public static final String COL_AUD_ADMIN_UID = "admin_uid";
    public static final String COL_AUD_ADMIN_NAME = "admin_name";
    public static final String COL_AUD_TIME = "timestamp_ms";
    public static final String COL_AUD_SUMMARY = "summary";

    public static final String TABLE_SYSTEM_EVENTS = "system_events";
    public static final String COL_SYS_ID = "sys_event_id";
    public static final String COL_SYS_TYPE = "event_type";
    public static final String COL_SYS_DESC = "description";
    public static final String COL_SYS_TIME = "timestamp_ms";
    public static final String COL_SYS_BATTERY = "battery_pct";
    public static final String COL_SYS_AIRPLANE = "airplane_mode";

    private static volatile AlertDatabaseHelper sInstance;

    public static AlertDatabaseHelper getInstance(Context context) {
        if (sInstance == null) {
            synchronized (AlertDatabaseHelper.class) {
                if (sInstance == null) {
                    sInstance = new AlertDatabaseHelper(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    public AlertDatabaseHelper(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_ALERTS + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_EVENT_ID + " TEXT UNIQUE NOT NULL, "
                + COL_LABEL + " TEXT NOT NULL, "
                + COL_RAW_LABEL + " TEXT NOT NULL, "
                + COL_CATEGORY + " TEXT NOT NULL, "
                + COL_SOURCE + " TEXT NOT NULL, "
                + COL_CONFIDENCE + " REAL NOT NULL, "
                + COL_ORIGINAL_PREDICTION + " TEXT NOT NULL DEFAULT '', "
                + COL_ORIGINAL_CONFIDENCE + " REAL NOT NULL DEFAULT 0, "
                + COL_APPLIED_CALIBRATION_ID + " TEXT NOT NULL DEFAULT '', "
                + COL_TIMESTAMP + " INTEGER NOT NULL, "
                + COL_DEVICE_ID + " TEXT NOT NULL, "
                + COL_ACKNOWLEDGED + " INTEGER NOT NULL DEFAULT 0, "
                + COL_DELIVERY_STATUS + " TEXT NOT NULL, "
                + COL_SYNC_STATUS + " TEXT NOT NULL, "
                + COL_LOCAL_CLIP_PATH + " TEXT NOT NULL DEFAULT '', "
                + COL_IS_CALIBRATED + " INTEGER NOT NULL DEFAULT 0"
                + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_alerts_time ON " + TABLE_ALERTS + "(" + COL_TIMESTAMP + " DESC)");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_CUSTOM_SOUNDS + " ("
                + COL_CS_ID + " TEXT PRIMARY KEY NOT NULL, "
                + COL_CS_NAME + " TEXT NOT NULL, "
                + COL_CS_CREATED + " INTEGER NOT NULL, "
                + COL_CS_ENABLED + " INTEGER NOT NULL DEFAULT 1, "
                + COL_CS_THRESHOLD + " REAL NOT NULL, "
                + COL_CS_MODEL_VER + " TEXT NOT NULL, "
                + COL_CS_EMBEDDINGS_JSON + " TEXT NOT NULL, "
                + COL_CS_PATHS_JSON + " TEXT NOT NULL"
                + ")");

        createCalibrationTables(db);

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_SYSTEM_EVENTS + " ("
                + COL_SYS_ID + " TEXT PRIMARY KEY NOT NULL, "
                + COL_SYS_TYPE + " TEXT NOT NULL, "
                + COL_SYS_DESC + " TEXT NOT NULL, "
                + COL_SYS_TIME + " INTEGER NOT NULL, "
                + COL_SYS_BATTERY + " INTEGER NOT NULL, "
                + COL_SYS_AIRPLANE + " INTEGER NOT NULL"
                + ")");
    }

    private void createCalibrationTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_GLOBAL_CALIBRATIONS + " ("
                + COL_GC_ID + " TEXT PRIMARY KEY NOT NULL, "
                + COL_GC_CANONICAL_LABEL + " TEXT NOT NULL, "
                + COL_GC_CATEGORY + " TEXT NOT NULL, "
                + COL_GC_ORIG_PREDICTION + " TEXT NOT NULL, "
                + COL_GC_EMBEDDINGS_JSON + " TEXT NOT NULL, "
                + COL_GC_MODEL_ID + " TEXT NOT NULL, "
                + COL_GC_MODEL_VER + " TEXT NOT NULL, "
                + COL_GC_SAMPLE_RATE + " INTEGER NOT NULL DEFAULT 16000, "
                + COL_GC_WINDOW_SAMPLES + " INTEGER NOT NULL DEFAULT 15600, "
                + COL_GC_THRESHOLD + " REAL NOT NULL, "
                + COL_GC_CREATED + " INTEGER NOT NULL, "
                + COL_GC_UPDATED + " INTEGER NOT NULL, "
                + COL_GC_ADMIN_UID + " TEXT NOT NULL DEFAULT '', "
                + COL_GC_ADMIN_NAME + " TEXT NOT NULL DEFAULT '', "
                + COL_GC_PROFILE_VER + " INTEGER NOT NULL DEFAULT 1, "
                + COL_GC_ACTIVE + " INTEGER NOT NULL DEFAULT 1"
                + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gc_active ON " + TABLE_GLOBAL_CALIBRATIONS + "(" + COL_GC_ACTIVE + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gc_updated ON " + TABLE_GLOBAL_CALIBRATIONS + "(" + COL_GC_UPDATED + " DESC)");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_CALIBRATION_AUDIT + " ("
                + COL_AUD_ID + " TEXT PRIMARY KEY NOT NULL, "
                + COL_AUD_CAL_ID + " TEXT NOT NULL, "
                + COL_AUD_ACTION + " TEXT NOT NULL, "
                + COL_AUD_PREV_LABEL + " TEXT NOT NULL DEFAULT '', "
                + COL_AUD_NEW_LABEL + " TEXT NOT NULL DEFAULT '', "
                + COL_AUD_ADMIN_UID + " TEXT NOT NULL DEFAULT '', "
                + COL_AUD_ADMIN_NAME + " TEXT NOT NULL DEFAULT '', "
                + COL_AUD_TIME + " INTEGER NOT NULL, "
                + COL_AUD_SUMMARY + " TEXT NOT NULL DEFAULT ''"
                + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_aud_time ON " + TABLE_CALIBRATION_AUDIT + "(" + COL_AUD_TIME + " DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            addColumnSafely(db, TABLE_ALERTS, COL_ORIGINAL_PREDICTION, "TEXT NOT NULL DEFAULT ''");
            addColumnSafely(db, TABLE_ALERTS, COL_ORIGINAL_CONFIDENCE, "REAL NOT NULL DEFAULT 0");
            addColumnSafely(db, TABLE_ALERTS, COL_APPLIED_CALIBRATION_ID, "TEXT NOT NULL DEFAULT ''");
            addColumnSafely(db, TABLE_ALERTS, COL_LOCAL_CLIP_PATH, "TEXT NOT NULL DEFAULT ''");
            addColumnSafely(db, TABLE_ALERTS, COL_IS_CALIBRATED, "INTEGER NOT NULL DEFAULT 0");
            createCalibrationTables(db);
        }
        onCreate(db);
    }

    private void addColumnSafely(SQLiteDatabase db, String table, String column, String definition) {
        try {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (Exception ignored) {
            // Column already exists
        }
    }

    // --- Alerts CRUD ---

    public synchronized void upsertAlert(AlertEvent alert) {
        SQLiteDatabase db = getWritableDatabase();
        // Preserve device-local review fields if the incoming object (e.g., a stale in-memory copy) lacks them
        if (!alert.hasLocalClip() || !alert.isCalibratedByAdmin()) {
            try (Cursor c = db.query(TABLE_ALERTS, new String[]{COL_LOCAL_CLIP_PATH, COL_IS_CALIBRATED, COL_APPLIED_CALIBRATION_ID},
                    COL_EVENT_ID + " = ?", new String[]{alert.getId()}, null, null, null, "1")) {
                if (c != null && c.moveToFirst()) {
                    String existingClip = c.getString(0);
                    if (!alert.hasLocalClip() && existingClip != null && !existingClip.isEmpty()) {
                        alert.setLocalClipPath(existingClip);
                    }
                    if (!alert.isCalibratedByAdmin() && c.getInt(1) == 1) {
                        alert.setCalibratedByAdmin(true);
                        String calId = c.getString(2);
                        if ((alert.getAppliedCalibrationId() == null || alert.getAppliedCalibrationId().isEmpty()) && calId != null) {
                            alert.setAppliedCalibrationId(calId);
                        }
                    }
                }
            }
        }
        ContentValues cv = new ContentValues();
        cv.put(COL_EVENT_ID, alert.getId());
        cv.put(COL_LABEL, alert.getLabel());
        cv.put(COL_RAW_LABEL, alert.getRawModelLabel());
        cv.put(COL_CATEGORY, alert.getCategory());
        cv.put(COL_SOURCE, alert.getSource());
        cv.put(COL_CONFIDENCE, alert.getConfidence());
        cv.put(COL_ORIGINAL_PREDICTION, alert.getOriginalPrediction());
        cv.put(COL_ORIGINAL_CONFIDENCE, alert.getOriginalConfidence());
        cv.put(COL_APPLIED_CALIBRATION_ID, alert.getAppliedCalibrationId());
        cv.put(COL_TIMESTAMP, alert.getTimestampMs());
        cv.put(COL_DEVICE_ID, alert.getDeviceId());
        cv.put(COL_ACKNOWLEDGED, alert.isAcknowledged() ? 1 : 0);
        cv.put(COL_DELIVERY_STATUS, alert.getDeliveryStatus());
        cv.put(COL_SYNC_STATUS, alert.getSyncStatus());
        cv.put(COL_LOCAL_CLIP_PATH, alert.getLocalClipPath());
        cv.put(COL_IS_CALIBRATED, alert.isCalibratedByAdmin() ? 1 : 0);
        db.insertWithOnConflict(TABLE_ALERTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized AlertEvent getAlertById(String eventId) {
        if (eventId == null) return null;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(
                TABLE_ALERTS,
                null,
                COL_EVENT_ID + " = ?",
                new String[]{eventId},
                null,
                null,
                null,
                "1"
        )) {
            if (c != null && c.moveToFirst()) {
                return cursorToAlert(c);
            }
        }
        return null;
    }

    public synchronized List<AlertEvent> getAllAlerts() {
        return queryAlerts(null);
    }

    public synchronized List<AlertEvent> queryAlerts(String sourceFilter) {
        List<AlertEvent> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        String selection = null;
        String[] selectionArgs = null;
        if (sourceFilter != null && !sourceFilter.isEmpty() && !"ALL".equalsIgnoreCase(sourceFilter)) {
            if ("UNCORRECTED".equalsIgnoreCase(sourceFilter)) {
                selection = COL_IS_CALIBRATED + " = 0";
            } else if ("CORRECTED".equalsIgnoreCase(sourceFilter)) {
                selection = COL_IS_CALIBRATED + " = 1 OR " + COL_SOURCE + " = ?";
                selectionArgs = new String[]{AlertEvent.SOURCE_GLOBAL_CALIBRATED};
            } else {
                selection = COL_SOURCE + " = ?";
                selectionArgs = new String[]{sourceFilter};
            }
        }
        try (Cursor c = db.query(
                TABLE_ALERTS,
                null,
                selection,
                selectionArgs,
                null,
                null,
                COL_TIMESTAMP + " DESC"
        )) {
            while (c != null && c.moveToNext()) {
                list.add(cursorToAlert(c));
            }
        }
        return list;
    }

    public synchronized List<AlertEvent> getPendingSyncAlerts() {
        List<AlertEvent> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(
                TABLE_ALERTS,
                null,
                COL_SYNC_STATUS + " IN (?, ?)",
                new String[]{AlertEvent.SYNC_PENDING, AlertEvent.SYNC_FAILED},
                null,
                null,
                COL_TIMESTAMP + " ASC"
        )) {
            while (c != null && c.moveToNext()) {
                list.add(cursorToAlert(c));
            }
        }
        return list;
    }

    public synchronized void clearAlertClipPath(String eventId) {
        if (eventId == null) return;
        ContentValues cv = new ContentValues();
        cv.put(COL_LOCAL_CLIP_PATH, "");
        getWritableDatabase().update(TABLE_ALERTS, cv, COL_EVENT_ID + " = ?", new String[]{eventId});
    }

    public synchronized void deleteAlert(String eventId) {
        if (eventId == null) return;
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_ALERTS, COL_EVENT_ID + " = ?", new String[]{eventId});
    }

    public synchronized void clearAllAlerts() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_ALERTS, null, null);
    }

    // --- Custom Sounds CRUD ---

    public synchronized void upsertCustomSound(CustomSoundProfile profile) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_CS_ID, profile.getId());
        cv.put(COL_CS_NAME, profile.getName());
        cv.put(COL_CS_CREATED, profile.getCreatedAtMs());
        cv.put(COL_CS_ENABLED, profile.isEnabled() ? 1 : 0);
        cv.put(COL_CS_THRESHOLD, profile.getMatchingThreshold());
        cv.put(COL_CS_MODEL_VER, profile.getModelVersion());
        try {
            cv.put(COL_CS_EMBEDDINGS_JSON, profile.serializeEmbeddingsJson());
        } catch (JSONException e) {
            cv.put(COL_CS_EMBEDDINGS_JSON, "[]");
        }
        cv.put(COL_CS_PATHS_JSON, profile.serializeSamplePathsJson());
        db.insertWithOnConflict(TABLE_CUSTOM_SOUNDS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized List<CustomSoundProfile> getAllCustomSounds() {
        List<CustomSoundProfile> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(TABLE_CUSTOM_SOUNDS, null, null, null, null, null, COL_CS_CREATED + " DESC")) {
            while (c != null && c.moveToNext()) {
                try {
                    String id = c.getString(c.getColumnIndexOrThrow(COL_CS_ID));
                    String name = c.getString(c.getColumnIndexOrThrow(COL_CS_NAME));
                    long created = c.getLong(c.getColumnIndexOrThrow(COL_CS_CREATED));
                    boolean enabled = c.getInt(c.getColumnIndexOrThrow(COL_CS_ENABLED)) == 1;
                    float threshold = c.getFloat(c.getColumnIndexOrThrow(COL_CS_THRESHOLD));
                    String modelVer = c.getString(c.getColumnIndexOrThrow(COL_CS_MODEL_VER));
                    String embJson = c.getString(c.getColumnIndexOrThrow(COL_CS_EMBEDDINGS_JSON));
                    String pathsJson = c.getString(c.getColumnIndexOrThrow(COL_CS_PATHS_JSON));
                    list.add(new CustomSoundProfile(
                            id,
                            name,
                            created,
                            enabled,
                            threshold,
                            modelVer,
                            CustomSoundProfile.deserializeEmbeddingsJson(embJson),
                            CustomSoundProfile.deserializeSamplePathsJson(pathsJson)
                    ));
                } catch (Exception ignored) {
                }
            }
        }
        return list;
    }

    public synchronized void deleteCustomSound(String soundId) {
        if (soundId == null) return;
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_CUSTOM_SOUNDS, COL_CS_ID + " = ?", new String[]{soundId});
    }

    // --- Global Calibrations CRUD ---

    public synchronized void upsertGlobalCalibration(GlobalCalibrationProfile profile) {
        if (profile == null) return;
        if (profile.isDeleted()) {
            deleteGlobalCalibration(profile.getCalibrationId());
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_GC_ID, profile.getCalibrationId());
        cv.put(COL_GC_CANONICAL_LABEL, profile.getCanonicalLabel());
        cv.put(COL_GC_CATEGORY, profile.getCategory());
        cv.put(COL_GC_ORIG_PREDICTION, profile.getOriginalModelPrediction());
        try {
            cv.put(COL_GC_EMBEDDINGS_JSON, profile.serializeEmbeddingsJson());
        } catch (JSONException e) {
            cv.put(COL_GC_EMBEDDINGS_JSON, "[]");
        }
        cv.put(COL_GC_MODEL_ID, profile.getEmbeddingModelId());
        cv.put(COL_GC_MODEL_VER, profile.getEmbeddingModelVersion());
        cv.put(COL_GC_SAMPLE_RATE, profile.getSampleRateHz());
        cv.put(COL_GC_WINDOW_SAMPLES, profile.getWindowSamples());
        cv.put(COL_GC_THRESHOLD, profile.getSimilarityThreshold());
        cv.put(COL_GC_CREATED, profile.getCreatedAtMs());
        cv.put(COL_GC_UPDATED, profile.getUpdatedAtMs());
        cv.put(COL_GC_ADMIN_UID, profile.getAdminUid());
        cv.put(COL_GC_ADMIN_NAME, profile.getAdminDisplayName());
        cv.put(COL_GC_PROFILE_VER, profile.getProfileVersion());
        cv.put(COL_GC_ACTIVE, profile.isActive() ? 1 : 0);
        db.insertWithOnConflict(TABLE_GLOBAL_CALIBRATIONS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized GlobalCalibrationProfile getGlobalCalibrationById(String calibrationId) {
        if (calibrationId == null || calibrationId.trim().isEmpty()) return null;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(
                TABLE_GLOBAL_CALIBRATIONS,
                null,
                COL_GC_ID + " = ?",
                new String[]{calibrationId},
                null,
                null,
                null,
                "1"
        )) {
            if (c != null && c.moveToFirst()) {
                return cursorToGlobalCalibration(c);
            }
        }
        return null;
    }

    public synchronized List<GlobalCalibrationProfile> getGlobalCalibrations(boolean activeOnly) {
        List<GlobalCalibrationProfile> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        String selection = activeOnly ? (COL_GC_ACTIVE + " = 1") : null;
        try (Cursor c = db.query(
                TABLE_GLOBAL_CALIBRATIONS,
                null,
                selection,
                null,
                null,
                null,
                COL_GC_UPDATED + " DESC"
        )) {
            while (c != null && c.moveToNext()) {
                GlobalCalibrationProfile p = cursorToGlobalCalibration(c);
                if (p != null) {
                    list.add(p);
                }
            }
        }
        return list;
    }

    public synchronized void deleteGlobalCalibration(String calibrationId) {
        if (calibrationId == null) return;
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_GLOBAL_CALIBRATIONS, COL_GC_ID + " = ?", new String[]{calibrationId});
    }

    private GlobalCalibrationProfile cursorToGlobalCalibration(Cursor c) {
        try {
            String id = c.getString(c.getColumnIndexOrThrow(COL_GC_ID));
            String canonicalLabel = c.getString(c.getColumnIndexOrThrow(COL_GC_CANONICAL_LABEL));
            String category = c.getString(c.getColumnIndexOrThrow(COL_GC_CATEGORY));
            String origPred = c.getString(c.getColumnIndexOrThrow(COL_GC_ORIG_PREDICTION));
            String embJson = c.getString(c.getColumnIndexOrThrow(COL_GC_EMBEDDINGS_JSON));
            String modelId = c.getString(c.getColumnIndexOrThrow(COL_GC_MODEL_ID));
            String modelVer = c.getString(c.getColumnIndexOrThrow(COL_GC_MODEL_VER));
            int sr = c.getInt(c.getColumnIndexOrThrow(COL_GC_SAMPLE_RATE));
            int win = c.getInt(c.getColumnIndexOrThrow(COL_GC_WINDOW_SAMPLES));
            float threshold = c.getFloat(c.getColumnIndexOrThrow(COL_GC_THRESHOLD));
            long created = c.getLong(c.getColumnIndexOrThrow(COL_GC_CREATED));
            long updated = c.getLong(c.getColumnIndexOrThrow(COL_GC_UPDATED));
            String adminUid = c.getString(c.getColumnIndexOrThrow(COL_GC_ADMIN_UID));
            String adminName = c.getString(c.getColumnIndexOrThrow(COL_GC_ADMIN_NAME));
            int profileVer = c.getInt(c.getColumnIndexOrThrow(COL_GC_PROFILE_VER));
            boolean active = c.getInt(c.getColumnIndexOrThrow(COL_GC_ACTIVE)) == 1;
            return new GlobalCalibrationProfile(
                    id,
                    canonicalLabel,
                    category,
                    origPred,
                    CustomSoundProfile.deserializeEmbeddingsJson(embJson),
                    modelId,
                    modelVer,
                    sr,
                    win,
                    threshold,
                    created,
                    updated,
                    adminUid,
                    adminName,
                    profileVer,
                    active,
                    false
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    // --- Calibration Audit Trail CRUD ---

    public synchronized void insertCalibrationAuditRecord(CalibrationAuditRecord record) {
        if (record == null) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_AUD_ID, record.getAuditId());
        cv.put(COL_AUD_CAL_ID, record.getCalibrationId());
        cv.put(COL_AUD_ACTION, record.getActionType());
        cv.put(COL_AUD_PREV_LABEL, record.getPreviousLabel());
        cv.put(COL_AUD_NEW_LABEL, record.getNewLabel());
        cv.put(COL_AUD_ADMIN_UID, record.getAdminUid());
        cv.put(COL_AUD_ADMIN_NAME, record.getAdminDisplayName());
        cv.put(COL_AUD_TIME, record.getTimestampMs());
        cv.put(COL_AUD_SUMMARY, record.getSummary());
        db.insertWithOnConflict(TABLE_CALIBRATION_AUDIT, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized List<CalibrationAuditRecord> getRecentCalibrationAuditRecords(int limit) {
        List<CalibrationAuditRecord> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(
                TABLE_CALIBRATION_AUDIT,
                null,
                null,
                null,
                null,
                null,
                COL_AUD_TIME + " DESC",
                String.valueOf(Math.max(1, limit))
        )) {
            while (c != null && c.moveToNext()) {
                list.add(new CalibrationAuditRecord(
                        c.getString(c.getColumnIndexOrThrow(COL_AUD_ID)),
                        c.getString(c.getColumnIndexOrThrow(COL_AUD_CAL_ID)),
                        c.getString(c.getColumnIndexOrThrow(COL_AUD_ACTION)),
                        c.getString(c.getColumnIndexOrThrow(COL_AUD_PREV_LABEL)),
                        c.getString(c.getColumnIndexOrThrow(COL_AUD_NEW_LABEL)),
                        c.getString(c.getColumnIndexOrThrow(COL_AUD_ADMIN_UID)),
                        c.getString(c.getColumnIndexOrThrow(COL_AUD_ADMIN_NAME)),
                        c.getLong(c.getColumnIndexOrThrow(COL_AUD_TIME)),
                        c.getString(c.getColumnIndexOrThrow(COL_AUD_SUMMARY))
                ));
            }
        }
        return list;
    }

    public synchronized List<CalibrationAuditRecord> getAllCalibrationAuditRecords() {
        return getRecentCalibrationAuditRecords(100);
    }

    // --- System Events CRUD ---

    public synchronized void insertSystemEvent(SystemEventRecord event) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_SYS_ID, event.getId());
        cv.put(COL_SYS_TYPE, event.getEventType());
        cv.put(COL_SYS_DESC, event.getDescription());
        cv.put(COL_SYS_TIME, event.getTimestampMs());
        cv.put(COL_SYS_BATTERY, event.getBatteryPercent());
        cv.put(COL_SYS_AIRPLANE, event.isAirplaneMode() ? 1 : 0);
        db.insertWithOnConflict(TABLE_SYSTEM_EVENTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized List<SystemEventRecord> getRecentSystemEvents(int limit) {
        List<SystemEventRecord> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(
                TABLE_SYSTEM_EVENTS,
                null,
                null,
                null,
                null,
                null,
                COL_SYS_TIME + " DESC",
                String.valueOf(Math.max(1, limit))
        )) {
            while (c != null && c.moveToNext()) {
                list.add(new SystemEventRecord(
                        c.getString(c.getColumnIndexOrThrow(COL_SYS_ID)),
                        c.getString(c.getColumnIndexOrThrow(COL_SYS_TYPE)),
                        c.getString(c.getColumnIndexOrThrow(COL_SYS_DESC)),
                        c.getLong(c.getColumnIndexOrThrow(COL_SYS_TIME)),
                        c.getInt(c.getColumnIndexOrThrow(COL_SYS_BATTERY)),
                        c.getInt(c.getColumnIndexOrThrow(COL_SYS_AIRPLANE)) == 1
                ));
            }
        }
        return list;
    }

    public static AlertEvent cursorToAlert(Cursor c) {
        String eventId = c.getString(c.getColumnIndexOrThrow(COL_EVENT_ID));
        String label = c.getString(c.getColumnIndexOrThrow(COL_LABEL));
        String rawLabel = c.getString(c.getColumnIndexOrThrow(COL_RAW_LABEL));
        String category = c.getString(c.getColumnIndexOrThrow(COL_CATEGORY));
        String source = c.getString(c.getColumnIndexOrThrow(COL_SOURCE));
        float confidence = c.getFloat(c.getColumnIndexOrThrow(COL_CONFIDENCE));

        int idxOrigPred = c.getColumnIndex(COL_ORIGINAL_PREDICTION);
        String origPred = idxOrigPred >= 0 ? c.getString(idxOrigPred) : rawLabel;

        int idxOrigConf = c.getColumnIndex(COL_ORIGINAL_CONFIDENCE);
        float origConf = idxOrigConf >= 0 ? c.getFloat(idxOrigConf) : confidence;

        int idxCalId = c.getColumnIndex(COL_APPLIED_CALIBRATION_ID);
        String calId = idxCalId >= 0 ? c.getString(idxCalId) : "";

        long timestamp = c.getLong(c.getColumnIndexOrThrow(COL_TIMESTAMP));
        String deviceId = c.getString(c.getColumnIndexOrThrow(COL_DEVICE_ID));
        boolean acknowledged = c.getInt(c.getColumnIndexOrThrow(COL_ACKNOWLEDGED)) == 1;
        String delivery = c.getString(c.getColumnIndexOrThrow(COL_DELIVERY_STATUS));
        String sync = c.getString(c.getColumnIndexOrThrow(COL_SYNC_STATUS));

        int idxClipPath = c.getColumnIndex(COL_LOCAL_CLIP_PATH);
        String clipPath = idxClipPath >= 0 ? c.getString(idxClipPath) : "";

        int idxCalibrated = c.getColumnIndex(COL_IS_CALIBRATED);
        boolean calibrated = idxCalibrated >= 0 && c.getInt(idxCalibrated) == 1;

        return new AlertEvent(
                eventId,
                label,
                rawLabel,
                category,
                source,
                confidence,
                origPred,
                origConf,
                calId,
                timestamp,
                deviceId,
                acknowledged,
                delivery,
                sync,
                clipPath,
                calibrated
        );
    }
}
