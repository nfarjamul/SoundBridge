package com.soundbridge.app.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Immutable audit trail entry tracking administrative modifications to global sound calibrations
 * (CREATE, STRENGTHEN_EXAMPLES, UPDATE, DEACTIVATE, REACTIVATE, DELETE).
 */
public class CalibrationAuditRecord {
    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_STRENGTHEN = "ADD_EXAMPLE";
    public static final String ACTION_UPDATE = "UPDATE";
    public static final String ACTION_DEACTIVATE = "DEACTIVATE";
    public static final String ACTION_REACTIVATE = "REACTIVATE";
    public static final String ACTION_DELETE = "DELETE";

    private final String auditId;
    private final String calibrationId;
    private final String actionType;
    private final String previousLabel;
    private final String newLabel;
    private final String adminUid;
    private final String adminDisplayName;
    private final long timestampMs;
    private final String summary;

    public CalibrationAuditRecord(
            String auditId,
            String calibrationId,
            String actionType,
            String previousLabel,
            String newLabel,
            String adminUid,
            String adminDisplayName,
            long timestampMs,
            String summary
    ) {
        this.auditId = (auditId == null || auditId.trim().isEmpty())
                ? "aud_" + timestampMs + "_" + UUID.randomUUID().toString().substring(0, 6)
                : auditId.trim();
        this.calibrationId = calibrationId != null ? calibrationId : "";
        this.actionType = actionType != null ? actionType : ACTION_UPDATE;
        this.previousLabel = previousLabel != null ? previousLabel : "";
        this.newLabel = newLabel != null ? newLabel : "";
        this.adminUid = adminUid != null ? adminUid : "";
        this.adminDisplayName = (adminDisplayName != null && !adminDisplayName.trim().isEmpty())
                ? adminDisplayName.trim()
                : "Administrator";
        this.timestampMs = timestampMs > 0L ? timestampMs : System.currentTimeMillis();
        this.summary = summary != null ? summary : "";
    }

    public String getAuditId() {
        return auditId;
    }

    public String getCalibrationId() {
        return calibrationId;
    }

    public String getActionType() {
        return actionType;
    }

    public String getAction() {
        return actionType;
    }

    public String getPreviousLabel() {
        return previousLabel;
    }

    public String getNewLabel() {
        return newLabel;
    }

    public String getCanonicalLabel() {
        return newLabel;
    }

    public String getAdminUid() {
        return adminUid;
    }

    public String getAdminDisplayName() {
        return adminDisplayName;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public String getSummary() {
        return summary;
    }

    public String getReason() {
        return summary;
    }

    public String getReadableAction() {
        if (ACTION_CREATE.equals(actionType)) return "Published global correction";
        if (ACTION_STRENGTHEN.equals(actionType)) return "Added reference recording";
        if (ACTION_DEACTIVATE.equals(actionType)) return "Deactivated calibration";
        if (ACTION_REACTIVATE.equals(actionType)) return "Reactivated calibration";
        if (ACTION_DELETE.equals(actionType)) return "Removed calibration";
        return "Updated calibration";
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("auditId", auditId);
        obj.put("calibrationId", calibrationId);
        obj.put("actionType", actionType);
        obj.put("previousLabel", previousLabel);
        obj.put("newLabel", newLabel);
        obj.put("adminUid", adminUid);
        obj.put("adminDisplayName", adminDisplayName);
        obj.put("timestampMs", timestampMs);
        obj.put("summary", summary);
        return obj;
    }
}

