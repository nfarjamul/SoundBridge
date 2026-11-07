package com.soundbridge.app;

import android.app.Application;

import com.soundbridge.app.data.AppPreferences;
import com.soundbridge.app.receiver.SystemStateBroadcastReceiver;
import com.soundbridge.app.service.SoundMonitorForegroundService;

/**
 * SoundBridge Application class.
 * Applies persisted Day/Night theme preference, initializes notification channels,
 * and synchronizes initial battery/airplane system flags.
 */
public class SoundBridgeApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        new AppPreferences(this).applySavedThemeMode();
        SoundMonitorForegroundService.ensureNotificationChannels(this);
        SystemStateBroadcastReceiver.refreshInitialSystemState(this);
    }
}
