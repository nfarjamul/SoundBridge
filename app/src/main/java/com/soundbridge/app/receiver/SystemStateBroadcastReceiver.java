package com.soundbridge.app.receiver;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.provider.Settings;

import com.soundbridge.app.bluetooth.BluetoothCompanionManager;
import com.soundbridge.app.data.SoundBridgeRepository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BroadcastReceiver observing system-level events required by course topics:
 * - Intent.ACTION_BATTERY_LOW & Intent.ACTION_BATTERY_OKAY
 * - Intent.ACTION_AIRPLANE_MODE_CHANGED
 * - BluetoothAdapter.ACTION_STATE_CHANGED
 *
 * Logs diagnostic events to SQLite + Firestore users/{uid}/systemEvents/{eventId},
 * adjusts low-battery inference throttling, and updates Bluetooth/monitoring status banners.
 */
public class SystemStateBroadcastReceiver extends BroadcastReceiver {

    public interface SystemStateListener {
        void onSystemStateChanged(boolean lowBattery, boolean airplaneModeOn, int batteryPct, String lastEventBanner);
    }

    private static final List<SystemStateListener> listeners = new CopyOnWriteArrayList<>();
    private static volatile boolean lowBatteryState = false;
    private static volatile boolean airplaneModeState = false;
    private static volatile int currentBatteryPct = 100;
    private static volatile String lastSystemBanner = "";

    public static void addListener(SystemStateListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(SystemStateListener listener) {
        listeners.remove(listener);
    }

    public static boolean isLowBatteryState() {
        return lowBatteryState;
    }

    public static boolean isAirplaneModeState() {
        return airplaneModeState;
    }

    public static int getCurrentBatteryPct() {
        return currentBatteryPct;
    }

    public static String getLastSystemBanner() {
        return lastSystemBanner;
    }

    public static IntentFilter createIntentFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_LOW);
        filter.addAction(Intent.ACTION_BATTERY_OKAY);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        return filter;
    }

    public static void refreshInitialSystemState(Context context) {
        if (context == null) return;
        try {
            airplaneModeState = Settings.Global.getInt(
                    context.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0
            ) != 0;
        } catch (Exception ignored) {
        }
        try {
            Intent batt = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batt != null) {
                int level = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    currentBatteryPct = Math.round((level * 100f) / scale);
                    lowBatteryState = currentBatteryPct <= 15;
                }
            }
        } catch (Exception ignored) {
        }
        updateBannerText(null);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || intent.getAction() == null) {
            return;
        }
        refreshInitialSystemState(context);
        String action = intent.getAction();
        SoundBridgeRepository repo = SoundBridgeRepository.getInstance(context);

        if (Intent.ACTION_BATTERY_LOW.equals(action)) {
            lowBatteryState = true;
            String msg = "Low battery (" + currentBatteryPct + "%) • Power-saving listening active";
            updateBannerText(msg);
            repo.recordSystemEvent("LOW_BATTERY", msg, currentBatteryPct, airplaneModeState);
        } else if (Intent.ACTION_BATTERY_OKAY.equals(action)) {
            lowBatteryState = false;
            updateBannerText(null);
            repo.recordSystemEvent("BATTERY_OKAY", "Battery level normal (" + currentBatteryPct + "%)", currentBatteryPct, airplaneModeState);
        } else if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
            boolean state = intent.getBooleanExtra("state", airplaneModeState);
            airplaneModeState = state;
            String msg;
            if (state) {
                BluetoothCompanionManager.getInstance(context).stopCompanionReceiver();
                msg = "Airplane mode is on • Sound alerts remain active on this phone";
                updateBannerText(msg);
            } else {
                msg = "Back online • Syncing recent alerts";
                repo.retryPendingAlertsSync();
                updateBannerText(null);
            }
            repo.recordSystemEvent("AIRPLANE_MODE", msg, currentBatteryPct, airplaneModeState);
        } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int btState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (btState == BluetoothAdapter.STATE_OFF || btState == BluetoothAdapter.STATE_TURNING_OFF) {
                BluetoothCompanionManager.getInstance(context).stopCompanionReceiver();
                repo.recordSystemEvent("BLUETOOTH_OFF", "Bluetooth turned off", currentBatteryPct, airplaneModeState);
                updateBannerText(null);
            } else if (btState == BluetoothAdapter.STATE_ON) {
                repo.recordSystemEvent("BLUETOOTH_ON", "Bluetooth turned on", currentBatteryPct, airplaneModeState);
                updateBannerText(null);
            }
        }
    }

    /**
     * Allows testing/demonstrating receiver behavior on emulators or devices during classroom presentation
     * as well as from real OS broadcasts.
     */
    public static void notifyStateChanged(String customBanner) {
        updateBannerText(customBanner);
    }

    private static void updateBannerText(String explicitMessage) {
        if (explicitMessage != null && !explicitMessage.isEmpty()) {
            lastSystemBanner = explicitMessage;
        } else if (lowBatteryState) {
            lastSystemBanner = "Low battery (" + currentBatteryPct + "%) • Power-saving listening active";
        } else if (airplaneModeState) {
            lastSystemBanner = "Airplane mode is on • Sound alerts remain active on this phone";
        } else {
            lastSystemBanner = "";
        }
        for (SystemStateListener l : listeners) {
            l.onSystemStateChanged(lowBatteryState, airplaneModeState, currentBatteryPct, lastSystemBanner);
        }
    }
}

