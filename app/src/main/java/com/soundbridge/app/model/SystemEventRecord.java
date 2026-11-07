package com.soundbridge.app.model;

import java.util.Locale;
import java.util.UUID;

/**
 * Diagnostic system event record (low battery, airplane mode, service lifecycle, permission state).
 * Stored separately from user-facing sound alerts in SQLite and Firestore users/{uid}/systemEvents/{eventId}.
 */
public class SystemEventRecord {
    private final String id;
    private final String eventType;
    private final String description;
    private final long timestampMs;
    private final int batteryPercent;
    private final boolean airplaneMode;

    public SystemEventRecord(
            String id,
            String eventType,
            String description,
            long timestampMs,
            int batteryPercent,
            boolean airplaneMode
    ) {
        this.id = (id == null || id.isEmpty())
                ? String.format(Locale.US, "sys_%d_%s", timestampMs, UUID.randomUUID().toString().substring(0, 6))
                : id;
        this.eventType = eventType != null ? eventType : "SYSTEM";
        this.description = description != null ? description : "";
        this.timestampMs = timestampMs;
        this.batteryPercent = batteryPercent;
        this.airplaneMode = airplaneMode;
    }

    public String getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getDescription() {
        return description;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public int getBatteryPercent() {
        return batteryPercent;
    }

    public boolean isAirplaneMode() {
        return airplaneMode;
    }
}

