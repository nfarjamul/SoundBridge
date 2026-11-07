package com.soundbridge.app.util;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import com.soundbridge.app.data.AppPreferences;
import com.soundbridge.app.model.AlertEvent;

/**
 * Provides distinct, assistive tactile vibration patterns for deaf and hard-of-hearing users.
 */
public final class VibrationHelper {
    private VibrationHelper() {
    }

    public static void triggerAlertVibration(Context context, String category) {
        if (context == null) return;
        AppPreferences prefs = new AppPreferences(context);
        if (!prefs.isVibrationEnabled()) {
            return;
        }
        Vibrator vibrator = getVibrator(context);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }

        boolean strong = prefs.isStrongVibration();
        long[] timings;
        int[] amplitudes;

        if (AlertEvent.CATEGORY_ALARM.equals(category)) {
            // Urgent rapid triple pulse for Smoke/Fire/Emergency Alarms
            timings = new long[]{0, 450, 150, 450, 150, 650};
            amplitudes = strong ? new int[]{0, 255, 0, 255, 0, 255} : new int[]{0, 180, 0, 180, 0, 200};
        } else if (AlertEvent.CATEGORY_DOORBELL.equals(category)) {
            // Ding-dong two-beat cadence
            timings = new long[]{0, 300, 180, 450};
            amplitudes = strong ? new int[]{0, 230, 0, 255} : new int[]{0, 160, 0, 180};
        } else if (AlertEvent.CATEGORY_KNOCK.equals(category)) {
            // Three crisp knock pulses
            timings = new long[]{0, 160, 110, 160, 110, 200};
            amplitudes = strong ? new int[]{0, 240, 0, 240, 0, 255} : new int[]{0, 170, 0, 170, 0, 190};
        } else {
            // Appliance beep or custom sound pattern
            timings = new long[]{0, 250, 140, 250};
            amplitudes = strong ? new int[]{0, 220, 0, 220} : new int[]{0, 150, 0, 150};
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (vibrator.hasAmplitudeControl()) {
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1));
                } else {
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, -1));
                }
            } else {
                //noinspection deprecation
                vibrator.vibrate(timings, -1);
            }
        } catch (Exception ignored) {
        }
    }

    private static Vibrator getVibrator(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm != null ? vm.getDefaultVibrator() : null;
        } else {
            //noinspection deprecation
            return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
    }
}

