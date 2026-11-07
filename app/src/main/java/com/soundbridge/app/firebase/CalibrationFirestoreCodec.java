package com.soundbridge.app.firebase;

import com.soundbridge.app.model.CalibrationAuditRecord;
import com.soundbridge.app.model.CustomSoundProfile;
import com.soundbridge.app.model.GlobalCalibrationProfile;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Pure-Java converter between calibration models and Cloud Firestore REST "fields" maps.
 *
 * This is the ONLY place that decides what a global calibration profile looks like in Firestore.
 * It intentionally serializes numerical embeddings + metadata only — never audio samples,
 * WAV bytes, local file paths, emails, or display names.
 */
public final class CalibrationFirestoreCodec {
    public static final String COLLECTION_CALIBRATIONS = "globalSoundCalibrations";
    public static final String COLLECTION_AUDIT = "calibrationAuditLogs";
    public static final String COLLECTION_ADMINS = "adminUsers";
    public static final String FIELD_SERVER_UPDATED_AT = "serverUpdatedAt";

    private CalibrationFirestoreCodec() {
    }

    public static JSONObject profileToFields(GlobalCalibrationProfile p) throws JSONException {
        JSONObject f = new JSONObject();
        f.put("calibrationId", str(p.getCalibrationId()));
        f.put("canonicalLabel", str(p.getCanonicalLabel()));
        f.put("category", str(p.getCategory()));
        f.put("originalModelPrediction", str(p.getOriginalModelPrediction()));
        f.put("embeddingsJson", str(p.isDeleted() ? "[]" : p.serializeEmbeddingsJson()));
        f.put("exampleCount", integer(p.isDeleted() ? 0 : p.getExampleCount()));
        f.put("embeddingModelId", str(p.getEmbeddingModelId()));
        f.put("embeddingModelVersion", str(p.getEmbeddingModelVersion()));
        f.put("sampleRateHz", integer(p.getSampleRateHz()));
        f.put("windowSamples", integer(p.getWindowSamples()));
        f.put("similarityThreshold", dbl(p.getSimilarityThreshold()));
        f.put("createdAtMs", integer(p.getCreatedAtMs()));
        f.put("updatedAtMs", integer(p.getUpdatedAtMs()));
        f.put("adminUid", str(p.getAdminUid()));
        f.put("profileVersion", integer(p.getProfileVersion()));
        f.put("active", bool(p.isActive()));
        f.put("deleted", bool(p.isDeleted()));
        return f;
    }

    public static GlobalCalibrationProfile fieldsToProfile(JSONObject f) throws JSONException {
        if (f == null) return null;
        String id = getStr(f, "calibrationId", "");
        if (id.isEmpty()) return null;
        List<float[]> embeddings = CustomSoundProfile.deserializeEmbeddingsJson(getStr(f, "embeddingsJson", "[]"));
        return new GlobalCalibrationProfile(
                id,
                getStr(f, "canonicalLabel", "Calibrated Sound"),
                getStr(f, "category", ""),
                getStr(f, "originalModelPrediction", "Unknown"),
                embeddings,
                getStr(f, "embeddingModelId", GlobalCalibrationProfile.EMBEDDING_MODEL_ID),
                getStr(f, "embeddingModelVersion", ""),
                (int) getLong(f, "sampleRateHz", 16000),
                (int) getLong(f, "windowSamples", 15600),
                (float) getDouble(f, "similarityThreshold", GlobalCalibrationProfile.DEFAULT_SIMILARITY_THRESHOLD),
                getLong(f, "createdAtMs", 0L),
                getLong(f, "updatedAtMs", 0L),
                getStr(f, "adminUid", ""),
                "Admin",
                (int) getLong(f, "profileVersion", 1),
                getBool(f, "active", true),
                getBool(f, "deleted", false)
        );
    }

    public static JSONObject auditToFields(CalibrationAuditRecord r, String adminEmail) throws JSONException {
        JSONObject f = new JSONObject();
        f.put("auditId", str(r.getAuditId()));
        f.put("calibrationId", str(r.getCalibrationId()));
        f.put("actionType", str(r.getActionType()));
        f.put("previousLabel", str(r.getPreviousLabel()));
        f.put("newLabel", str(r.getNewLabel()));
        f.put("adminUid", str(r.getAdminUid()));
        f.put("adminEmail", str(adminEmail != null ? adminEmail : ""));
        f.put("timestampMs", integer(r.getTimestampMs()));
        f.put("summary", str(r.getSummary()));
        return f;
    }

    public static CalibrationAuditRecord fieldsToAudit(JSONObject f) {
        if (f == null) return null;
        String email = getStr(f, "adminEmail", "");
        return new CalibrationAuditRecord(
                getStr(f, "auditId", ""),
                getStr(f, "calibrationId", ""),
                getStr(f, "actionType", CalibrationAuditRecord.ACTION_UPDATE),
                getStr(f, "previousLabel", ""),
                getStr(f, "newLabel", ""),
                getStr(f, "adminUid", ""),
                email.isEmpty() ? "Administrator" : email,
                getLong(f, "timestampMs", 0L),
                getStr(f, "summary", "")
        );
    }

    // --- Timestamp helpers (RFC 3339) ---

    public static String toRfc3339(long epochMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(Math.max(0L, epochMs)));
    }

    /**
     * Parses Firestore timestamps like "2026-11-07T12:00:00.123456Z" or "2026-11-07T12:00:00Z" to epoch ms.
     */
    public static long parseRfc3339ToMs(String ts) {
        if (ts == null || ts.length() < 20) return 0L;
        try {
            String base = ts.substring(0, 19);
            int millis = 0;
            int dot = ts.indexOf('.', 19);
            if (dot == 19) {
                int end = dot + 1;
                while (end < ts.length() && Character.isDigit(ts.charAt(end))) end++;
                String frac = (ts.substring(dot + 1, end) + "000").substring(0, 3);
                millis = Integer.parseInt(frac);
            }
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = sdf.parse(base);
            return d != null ? d.getTime() + millis : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    // --- Firestore typed value helpers ---

    static JSONObject str(String v) throws JSONException {
        return new JSONObject().put("stringValue", v != null ? v : "");
    }

    static JSONObject integer(long v) throws JSONException {
        return new JSONObject().put("integerValue", String.valueOf(v));
    }

    static JSONObject dbl(double v) throws JSONException {
        return new JSONObject().put("doubleValue", v);
    }

    static JSONObject bool(boolean v) throws JSONException {
        return new JSONObject().put("booleanValue", v);
    }

    public static String getStr(JSONObject fields, String key, String def) {
        JSONObject v = fields.optJSONObject(key);
        if (v == null) return def;
        if (v.has("stringValue")) return v.optString("stringValue", def);
        return def;
    }

    public static long getLong(JSONObject fields, String key, long def) {
        JSONObject v = fields.optJSONObject(key);
        if (v == null) return def;
        try {
            if (v.has("integerValue")) return Long.parseLong(v.getString("integerValue"));
            if (v.has("doubleValue")) return (long) v.getDouble("doubleValue");
            if (v.has("timestampValue")) return parseRfc3339ToMs(v.getString("timestampValue"));
        } catch (Exception ignored) {
        }
        return def;
    }

    public static double getDouble(JSONObject fields, String key, double def) {
        JSONObject v = fields.optJSONObject(key);
        if (v == null) return def;
        try {
            if (v.has("doubleValue")) return v.getDouble("doubleValue");
            if (v.has("integerValue")) return Long.parseLong(v.getString("integerValue"));
        } catch (Exception ignored) {
        }
        return def;
    }

    public static boolean getBool(JSONObject fields, String key, boolean def) {
        JSONObject v = fields.optJSONObject(key);
        if (v == null || !v.has("booleanValue")) return def;
        return v.optBoolean("booleanValue", def);
    }
}

