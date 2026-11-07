package com.soundbridge.app.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Canonical sound alert record shared across UI, local SQLite cache, ContentProvider,
 * Cloud Firestore history, Realtime Database live state, and Bluetooth RFCOMM companion payloads.
 *
 * PRIVACY GUARANTEE:
 * localClipPath is stored strictly in local SQLite for administrator review on the capturing device
 * and is NEVER included in Firestore, Realtime Database, or Bluetooth payloads.
 */
public class AlertEvent {
    public static final String SOURCE_BUILT_IN = "BUILT_IN";
    public static final String SOURCE_CUSTOM = "CUSTOM";
    public static final String SOURCE_GLOBAL_CALIBRATED = "GLOBAL_CALIBRATED";

    public static final String CATEGORY_DOORBELL = "DOORBELL";
    public static final String CATEGORY_KNOCK = "KNOCK";
    public static final String CATEGORY_ALARM = "ALARM";
    public static final String CATEGORY_APPLIANCE_BEEP = "APPLIANCE_BEEP";
    public static final String CATEGORY_CUSTOM = "CUSTOM";

    public static final String DELIVERY_LOCAL_ONLY = "LOCAL_ONLY";
    public static final String DELIVERY_BT_DELIVERED = "BT_DELIVERED";
    public static final String DELIVERY_BT_FAILED = "BT_FAILED";
    public static final String DELIVERY_COMPANION_RECEIVED = "COMPANION_RECEIVED";

    public static final String SYNC_PENDING = "PENDING";
    public static final String SYNC_SYNCED = "SYNCED";
    public static final String SYNC_LOCAL_DEMO = "LOCAL_DEMO";
    public static final String SYNC_FAILED = "FAILED";

    private final String id;
    private String label;
    private final String rawModelLabel;
    private String category;
    private String source;
    private final float confidence;
    private final String originalPrediction;
    private final float originalConfidence;
    private String appliedCalibrationId;
    private final long timestampMs;
    private final String deviceId;
    private boolean acknowledged;
    private String deliveryStatus;
    private String syncStatus;

    // Strictly local-only fields for Admin Sound Calibration review (never uploaded to Firebase)
    private String localClipPath;
    private boolean calibratedByAdmin;

    public AlertEvent(
            String id,
            String label,
            String rawModelLabel,
            String category,
            String source,
            float confidence,
            long timestampMs,
            String deviceId,
            boolean acknowledged,
            String deliveryStatus,
            String syncStatus
    ) {
        this(
                id,
                label,
                rawModelLabel,
                category,
                source,
                confidence,
                rawModelLabel != null ? rawModelLabel : label,
                confidence,
                null,
                timestampMs,
                deviceId,
                acknowledged,
                deliveryStatus,
                syncStatus,
                null,
                false
        );
    }

    public AlertEvent(
            String id,
            String label,
            String rawModelLabel,
            String category,
            String source,
            float confidence,
            String originalPrediction,
            float originalConfidence,
            String appliedCalibrationId,
            long timestampMs,
            String deviceId,
            boolean acknowledged,
            String deliveryStatus,
            String syncStatus,
            String localClipPath,
            boolean calibratedByAdmin
    ) {
        this.id = (id == null || id.trim().isEmpty()) ? generateStableId(category, timestampMs, deviceId) : id;
        this.label = label != null ? label : "Unknown Sound";
        this.rawModelLabel = rawModelLabel != null ? rawModelLabel : this.label;
        this.category = category != null ? category : CATEGORY_ALARM;
        this.source = source != null ? source : SOURCE_BUILT_IN;
        this.confidence = Math.max(0f, Math.min(1f, confidence));
        this.originalPrediction = (originalPrediction != null && !originalPrediction.trim().isEmpty())
                ? originalPrediction.trim()
                : this.rawModelLabel;
        this.originalConfidence = Math.max(0f, Math.min(1f, originalConfidence > 0f ? originalConfidence : this.confidence));
        this.appliedCalibrationId = appliedCalibrationId != null ? appliedCalibrationId.trim() : "";
        this.timestampMs = timestampMs;
        this.deviceId = deviceId != null ? deviceId : "local_device";
        this.acknowledged = acknowledged;
        this.deliveryStatus = deliveryStatus != null ? deliveryStatus : DELIVERY_LOCAL_ONLY;
        this.syncStatus = syncStatus != null ? syncStatus : SYNC_PENDING;
        this.localClipPath = localClipPath != null ? localClipPath.trim() : "";
        this.calibratedByAdmin = calibratedByAdmin;
    }

    /**
     * Generates a deterministic, collision-resistant ID so offline retries never duplicate Firestore records.
     */
    public static String generateStableId(String category, long timestampMs, String deviceId) {
        String safeCat = category != null ? category.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "") : "snd";
        String safeDev = deviceId != null ? deviceId.replaceAll("[^a-zA-Z0-9]", "") : "dev";
        if (safeDev.length() > 8) {
            safeDev = safeDev.substring(0, 8);
        }
        String suffix = UUID.nameUUIDFromBytes((safeCat + ":" + timestampMs + ":" + safeDev).getBytes()).toString().substring(0, 8);
        return String.format(Locale.US, "evt_%d_%s_%s", timestampMs, safeCat, suffix);
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        if (label != null && !label.trim().isEmpty()) {
            this.label = label.trim();
        }
    }

    public String getRawModelLabel() {
        return rawModelLabel;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        if (category != null && !category.trim().isEmpty()) {
            this.category = category.trim();
        }
    }

    public String getSource() {
        return source;
    }

    public String getRecognitionMethod() {
        return source;
    }

    public void setSource(String source) {
        if (source != null && !source.trim().isEmpty()) {
            this.source = source.trim();
        }
    }

    public float getConfidence() {
        return confidence;
    }

    public int getConfidencePercent() {
        return Math.round(confidence * 100f);
    }

    public String getOriginalPrediction() {
        return originalPrediction;
    }

    public float getOriginalConfidence() {
        return originalConfidence;
    }

    public int getOriginalConfidencePercent() {
        return Math.round(originalConfidence * 100f);
    }

    public String getAppliedCalibrationId() {
        return appliedCalibrationId;
    }

    public void setAppliedCalibrationId(String appliedCalibrationId) {
        this.appliedCalibrationId = appliedCalibrationId != null ? appliedCalibrationId.trim() : "";
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public boolean isAcknowledged() {
        return acknowledged;
    }

    public void setAcknowledged(boolean acknowledged) {
        this.acknowledged = acknowledged;
    }

    public String getDeliveryStatus() {
        return deliveryStatus;
    }

    public void setDeliveryStatus(String deliveryStatus) {
        this.deliveryStatus = deliveryStatus;
    }

    public String getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(String syncStatus) {
        this.syncStatus = syncStatus;
    }

    public String getLocalClipPath() {
        return localClipPath;
    }

    public void setLocalClipPath(String localClipPath) {
        this.localClipPath = localClipPath != null ? localClipPath.trim() : "";
    }

    public boolean hasLocalClip() {
        return localClipPath != null && !localClipPath.trim().isEmpty();
    }

    public boolean isCalibratedByAdmin() {
        return calibratedByAdmin;
    }

    public void setCalibratedByAdmin(boolean calibratedByAdmin) {
        this.calibratedByAdmin = calibratedByAdmin;
    }

    public boolean isCustom() {
        return SOURCE_CUSTOM.equalsIgnoreCase(source);
    }

    public boolean isGlobalCalibrated() {
        return SOURCE_GLOBAL_CALIBRATED.equalsIgnoreCase(source);
    }

    /**
     * Clean, human-friendly sound title without raw AudioSet parentheses when built-in.
     */
    public String getDisplayTitle() {
        if (isCustom() || isGlobalCalibrated()) {
            return label;
        }
        int parenIdx = label.indexOf(" (");
        if (parenIdx > 0) {
            return label.substring(0, parenIdx).trim();
        }
        return label;
    }

    public String getCleanOriginalPredictionTitle() {
        if (originalPrediction == null || originalPrediction.trim().isEmpty()) {
            return getDisplayTitle();
        }
        int parenIdx = originalPrediction.indexOf(" (");
        if (parenIdx > 0) {
            return originalPrediction.substring(0, parenIdx).trim();
        }
        return originalPrediction;
    }

    public String getReadableCategory() {
        if (CATEGORY_DOORBELL.equals(category)) return "Doorbell";
        if (CATEGORY_KNOCK.equals(category)) return "Door Knock";
        if (CATEGORY_ALARM.equals(category)) return "Safety Alarm";
        if (CATEGORY_APPLIANCE_BEEP.equals(category)) return "Appliance Chime";
        if (CATEGORY_CUSTOM.equals(category) || isCustom()) return "Custom Sound";
        if (category != null && !category.trim().isEmpty()) {
            String[] parts = category.trim().toLowerCase(Locale.US).split("_+");
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                if (!p.isEmpty()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
                }
            }
            if (sb.length() > 0) return sb.toString();
        }
        return "Household Sound";
    }

    public String getReadableSource() {
        if (isGlobalCalibrated()) return "Calibrated Recognition";
        if (isCustom()) return "Custom Sound";
        return "Built-in Detection";
    }

    public String getReadableDeliveryStatus() {
        if (DELIVERY_BT_DELIVERED.equals(deliveryStatus)) return "Sent to companion phone";
        if (DELIVERY_COMPANION_RECEIVED.equals(deliveryStatus)) return "Received from companion phone";
        if (DELIVERY_BT_FAILED.equals(deliveryStatus)) return "Companion phone unreachable";
        return "This phone";
    }

    public String getReadableSyncStatus() {
        if (SYNC_SYNCED.equals(syncStatus)) return "Backed up to cloud";
        if (SYNC_PENDING.equals(syncStatus)) return "Saved on device";
        if (SYNC_FAILED.equals(syncStatus)) return "Saved offline";
        return "On this device";
    }

    /**
     * Serializes the alert into the versioned Bluetooth RFCOMM JSON message format.
     * Excludes device-local audio clip paths.
     */
    public String toBluetoothPayloadJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("schemaVersion", 1);
        obj.put("type", "SOUNDBRIDGE_ALERT");
        obj.put("eventId", id);
        obj.put("label", label);
        obj.put("rawModelLabel", rawModelLabel);
        obj.put("category", category);
        obj.put("source", source);
        obj.put("confidence", (double) Math.round(confidence * 1000f) / 1000.0);
        obj.put("originalPrediction", originalPrediction);
        obj.put("originalConfidence", (double) Math.round(originalConfidence * 1000f) / 1000.0);
        if (appliedCalibrationId != null && !appliedCalibrationId.isEmpty()) {
            obj.put("appliedCalibrationId", appliedCalibrationId);
        }
        obj.put("timestamp", timestampMs);
        obj.put("deviceId", deviceId);
        return obj.toString();
    }

    public static AlertEvent fromBluetoothPayloadJson(String jsonStr) throws JSONException {
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            throw new JSONException("Empty Bluetooth payload");
        }
        JSONObject obj = new JSONObject(jsonStr);
        int version = obj.optInt("schemaVersion", 1);
        if (version < 1) {
            throw new JSONException("Unsupported schemaVersion: " + version);
        }
        String eventId = obj.getString("eventId");
        String label = obj.getString("label");
        String rawModelLabel = obj.optString("rawModelLabel", label);
        String category = obj.optString("category", CATEGORY_ALARM);
        String source = obj.optString("source", SOURCE_BUILT_IN);
        float confidence = (float) obj.optDouble("confidence", 0.0);
        String origPred = obj.optString("originalPrediction", rawModelLabel);
        float origConf = (float) obj.optDouble("originalConfidence", confidence);
        String appliedCalId = obj.optString("appliedCalibrationId", "");
        long timestamp = obj.getLong("timestamp");
        String senderDevice = obj.optString("deviceId", "companion_sender");
        if (eventId.trim().isEmpty() || label.trim().isEmpty() || timestamp <= 0L) {
            throw new JSONException("Malformed required fields in Bluetooth alert payload");
        }
        return new AlertEvent(
                eventId,
                label,
                rawModelLabel,
                category,
                source,
                confidence,
                origPred,
                origConf,
                appliedCalId,
                timestamp,
                senderDevice,
                false,
                DELIVERY_COMPANION_RECEIVED,
                SYNC_PENDING,
                null,
                false
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AlertEvent)) return false;
        AlertEvent that = (AlertEvent) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
