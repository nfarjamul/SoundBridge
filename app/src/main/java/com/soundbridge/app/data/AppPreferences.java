package com.soundbridge.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;

import java.util.UUID;

/**
 * Persists user settings, sensitivity thresholds, category filters, Bluetooth companion address,
 * and authentication/local-demo mode flags.
 */
public class AppPreferences {
    private static final String PREF_NAME = "soundbridge_prefs";

    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_SENSITIVITY_THRESHOLD = "sensitivity_threshold";
    private static final String KEY_COOLDOWN_MS = "cooldown_ms";
    private static final String KEY_ENABLE_DOORBELL = "enable_doorbell";
    private static final String KEY_ENABLE_KNOCK = "enable_knock";
    private static final String KEY_ENABLE_ALARM = "enable_alarm";
    private static final String KEY_ENABLE_APPLIANCE = "enable_appliance";
    private static final String KEY_VIBRATION_ENABLED = "vibration_enabled";
    private static final String KEY_STRONG_VIBRATION = "strong_vibration";
    private static final String KEY_CONSENT_CUSTOM_DESCRIPTOR_SYNC = "consent_custom_descriptor_sync";
    private static final String KEY_LOCAL_DEMO_MODE = "local_demo_mode";
    private static final String KEY_AUTH_UID = "auth_uid";
    private static final String KEY_AUTH_EMAIL = "auth_email";
    private static final String KEY_AUTH_NAME = "auth_name";
    private static final String KEY_AUTH_ID_TOKEN = "auth_id_token";
    private static final String KEY_BT_COMPANION_ADDRESS = "bt_companion_address";
    private static final String KEY_BT_COMPANION_NAME = "bt_companion_name";
    private static final String KEY_BT_LAST_DELIVERY_STATUS = "bt_last_delivery_status";
    private static final String KEY_LOW_POWER_THROTTLE = "low_power_throttle";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_AUTH_REFRESH_TOKEN = "auth_refresh_token";
    private static final String KEY_AUTH_TOKEN_EXPIRY_MS = "auth_token_expiry_ms";
    private static final String KEY_ADMIN_VERIFIED_UID = "admin_verified_uid";
    private static final String KEY_ADMIN_VERIFIED_AT_MS = "admin_verified_at_ms";
    private static final String KEY_CALIBRATION_LAST_SYNC_MS = "calibration_last_sync_ms";
    private static final String KEY_CALIBRATION_LAST_SYNC_OK_AT = "calibration_last_sync_ok_at";

    private final SharedPreferences prefs;

    public AppPreferences(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public String getDeviceId() {
        String id = prefs.getString(KEY_DEVICE_ID, null);
        if (id == null || id.isEmpty()) {
            id = "sb_" + UUID.randomUUID().toString().substring(0, 8);
            prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    /**
     * Confidence threshold in [0.20, 0.85]. Lower value = higher sensitivity. Default 0.42f.
     */
    public float getConfidenceThreshold() {
        return prefs.getFloat(KEY_SENSITIVITY_THRESHOLD, 0.42f);
    }

    public void setConfidenceThreshold(float threshold) {
        float clamped = Math.max(0.20f, Math.min(0.85f, threshold));
        prefs.edit().putFloat(KEY_SENSITIVITY_THRESHOLD, clamped).apply();
    }

    public long getCooldownMs() {
        return prefs.getLong(KEY_COOLDOWN_MS, 4000L);
    }

    public void setCooldownMs(long cooldownMs) {
        prefs.edit().putLong(KEY_COOLDOWN_MS, Math.max(1500L, Math.min(15000L, cooldownMs))).apply();
    }

    public boolean isDoorbellEnabled() {
        return prefs.getBoolean(KEY_ENABLE_DOORBELL, true);
    }

    public void setDoorbellEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLE_DOORBELL, enabled).apply();
    }

    public boolean isKnockEnabled() {
        return prefs.getBoolean(KEY_ENABLE_KNOCK, true);
    }

    public void setKnockEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLE_KNOCK, enabled).apply();
    }

    public boolean isAlarmEnabled() {
        return prefs.getBoolean(KEY_ENABLE_ALARM, true);
    }

    public void setAlarmEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLE_ALARM, enabled).apply();
    }

    public boolean isApplianceBeepEnabled() {
        return prefs.getBoolean(KEY_ENABLE_APPLIANCE, true);
    }

    public void setApplianceBeepEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLE_APPLIANCE, enabled).apply();
    }

    public boolean isVibrationEnabled() {
        return prefs.getBoolean(KEY_VIBRATION_ENABLED, true);
    }

    public void setVibrationEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, enabled).apply();
    }

    public boolean isStrongVibration() {
        return prefs.getBoolean(KEY_STRONG_VIBRATION, true);
    }

    public void setStrongVibration(boolean strong) {
        prefs.edit().putBoolean(KEY_STRONG_VIBRATION, strong).apply();
    }

    public boolean isCustomDescriptorSyncConsented() {
        return prefs.getBoolean(KEY_CONSENT_CUSTOM_DESCRIPTOR_SYNC, false);
    }

    public void setCustomDescriptorSyncConsented(boolean consented) {
        prefs.edit().putBoolean(KEY_CONSENT_CUSTOM_DESCRIPTOR_SYNC, consented).apply();
    }

    public boolean isLocalDemoMode() {
        return prefs.getBoolean(KEY_LOCAL_DEMO_MODE, false);
    }

    public void setLocalDemoMode(boolean demoMode) {
        prefs.edit().putBoolean(KEY_LOCAL_DEMO_MODE, demoMode).apply();
    }

    public void saveAuthenticatedUser(String uid, String email, String displayName, String idToken) {
        prefs.edit()
                .putString(KEY_AUTH_UID, uid)
                .putString(KEY_AUTH_EMAIL, email)
                .putString(KEY_AUTH_NAME, displayName)
                .putString(KEY_AUTH_ID_TOKEN, idToken)
                .putBoolean(KEY_LOCAL_DEMO_MODE, false)
                .apply();
    }

    public void clearAuthSession() {
        prefs.edit()
                .remove(KEY_AUTH_UID)
                .remove(KEY_AUTH_EMAIL)
                .remove(KEY_AUTH_NAME)
                .remove(KEY_AUTH_ID_TOKEN)
                .remove(KEY_AUTH_REFRESH_TOKEN)
                .remove(KEY_AUTH_TOKEN_EXPIRY_MS)
                .remove(KEY_ADMIN_VERIFIED_UID)
                .remove(KEY_ADMIN_VERIFIED_AT_MS)
                .putBoolean(KEY_LOCAL_DEMO_MODE, false)
                .apply();
    }

    // --- Token refresh support (Firebase ID tokens expire after 1 hour) ---

    public void saveAuthTokens(String idToken, String refreshToken, long expiresInSec) {
        SharedPreferences.Editor ed = prefs.edit();
        if (idToken != null && !idToken.isEmpty()) {
            ed.putString(KEY_AUTH_ID_TOKEN, idToken);
        }
        if (refreshToken != null && !refreshToken.isEmpty()) {
            ed.putString(KEY_AUTH_REFRESH_TOKEN, refreshToken);
        }
        long ttl = expiresInSec > 0 ? expiresInSec : 3600L;
        ed.putLong(KEY_AUTH_TOKEN_EXPIRY_MS, System.currentTimeMillis() + ttl * 1000L);
        ed.apply();
    }

    public String getAuthRefreshToken() {
        return prefs.getString(KEY_AUTH_REFRESH_TOKEN, null);
    }

    public long getAuthTokenExpiryMs() {
        return prefs.getLong(KEY_AUTH_TOKEN_EXPIRY_MS, 0L);
    }

    // --- Admin verification cache (UI hint only; every privileged action is re-verified server-side) ---

    public void setAdminVerifiedHint(String uid, boolean isAdmin) {
        SharedPreferences.Editor ed = prefs.edit();
        if (isAdmin && uid != null) {
            ed.putString(KEY_ADMIN_VERIFIED_UID, uid).putLong(KEY_ADMIN_VERIFIED_AT_MS, System.currentTimeMillis());
        } else {
            ed.remove(KEY_ADMIN_VERIFIED_UID).remove(KEY_ADMIN_VERIFIED_AT_MS);
        }
        ed.apply();
    }

    /**
     * Returns true only if a recent (< 6h) server-verified admin check succeeded for the current UID.
     * Used solely to decide whether to capture local admin review clips; never grants write access.
     */
    public boolean isAdminVerifiedHintFor(String uid) {
        if (uid == null) return false;
        String cached = prefs.getString(KEY_ADMIN_VERIFIED_UID, null);
        long at = prefs.getLong(KEY_ADMIN_VERIFIED_AT_MS, 0L);
        return uid.equals(cached) && (System.currentTimeMillis() - at) < 6L * 3600L * 1000L;
    }

    // --- Global calibration incremental sync cursor ---

    public long getCalibrationLastSyncMs() {
        return prefs.getLong(KEY_CALIBRATION_LAST_SYNC_MS, 0L);
    }

    public void setCalibrationLastSyncMs(long updatedAtCursorMs) {
        prefs.edit()
                .putLong(KEY_CALIBRATION_LAST_SYNC_MS, Math.max(0L, updatedAtCursorMs))
                .putLong(KEY_CALIBRATION_LAST_SYNC_OK_AT, System.currentTimeMillis())
                .apply();
    }

    public long getCalibrationLastSuccessfulSyncAt() {
        return prefs.getLong(KEY_CALIBRATION_LAST_SYNC_OK_AT, 0L);
    }

    // --- Optional Firebase Anonymous Auth session used only to READ global calibrations without an account ---

    public void saveAnonymousCalibrationSession(String idToken, String refreshToken, long expiresInSec) {
        prefs.edit()
                .putString("anon_cal_id_token", idToken)
                .putString("anon_cal_refresh_token", refreshToken)
                .putLong("anon_cal_expiry_ms", System.currentTimeMillis() + Math.max(60L, expiresInSec) * 1000L)
                .apply();
    }

    public String getAnonymousCalibrationIdToken() {
        return prefs.getString("anon_cal_id_token", null);
    }

    public String getAnonymousCalibrationRefreshToken() {
        return prefs.getString("anon_cal_refresh_token", null);
    }

    public long getAnonymousCalibrationExpiryMs() {
        return prefs.getLong("anon_cal_expiry_ms", 0L);
    }

    public String getAuthUid() {
        return prefs.getString(KEY_AUTH_UID, null);
    }

    public String getAuthEmail() {
        return prefs.getString(KEY_AUTH_EMAIL, null);
    }

    public String getAuthDisplayName() {
        return prefs.getString(KEY_AUTH_NAME, null);
    }

    public String getAuthIdToken() {
        return prefs.getString(KEY_AUTH_ID_TOKEN, null);
    }

    public String getBtCompanionAddress() {
        return prefs.getString(KEY_BT_COMPANION_ADDRESS, null);
    }

    public String getBtCompanionName() {
        return prefs.getString(KEY_BT_COMPANION_NAME, null);
    }

    public void setBtCompanionDevice(String name, String address) {
        prefs.edit()
                .putString(KEY_BT_COMPANION_NAME, name)
                .putString(KEY_BT_COMPANION_ADDRESS, address)
                .apply();
    }

    public String getBtLastDeliveryStatus() {
        return prefs.getString(KEY_BT_LAST_DELIVERY_STATUS, "No alerts sent yet");
    }

    public void setBtLastDeliveryStatus(String status) {
        prefs.edit().putString(KEY_BT_LAST_DELIVERY_STATUS, status).apply();
    }

    public boolean isLowPowerThrottleEnabled() {
        return prefs.getBoolean(KEY_LOW_POWER_THROTTLE, true);
    }

    public void setLowPowerThrottleEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_LOW_POWER_THROTTLE, enabled).apply();
    }

    public int getThemeMode() {
        return prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public void setThemeMode(int mode) {
        prefs.edit().putInt(KEY_THEME_MODE, mode).apply();
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    public void applySavedThemeMode() {
        AppCompatDelegate.setDefaultNightMode(getThemeMode());
    }

    public boolean isCurrentlyDark(Context context) {
        int nightFlags = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    public void toggleDarkLightMode(Context context) {
        int nextMode = isCurrentlyDark(context)
                ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_YES;
        setThemeMode(nextMode);
    }
}

