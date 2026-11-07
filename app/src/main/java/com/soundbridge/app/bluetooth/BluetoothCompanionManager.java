package com.soundbridge.app.bluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.soundbridge.app.data.AppPreferences;
import com.soundbridge.app.data.SoundBridgeRepository;
import com.soundbridge.app.model.AlertEvent;
import com.soundbridge.app.util.VibrationHelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Implements Bluetooth Classic RFCOMM companion alert delivery between two paired Android phones
 * running SoundBridge.
 *
 * Fixed service UUID: 8f4e2a10-5b9c-4e1a-9d3c-7b6a5e4f3c2d
 * Protocol: Versioned JSON line (AlertEvent.toBluetoothPayloadJson) followed by "ACK:<eventId>" confirmation.
 * Never claims successful delivery unless the RFCOMM socket write AND ACK read succeed.
 */
public class BluetoothCompanionManager {
    public static final String SERVICE_NAME = "SoundBridgeCompanion";
    public static final UUID SOUNDBRIDGE_UUID = UUID.fromString("8f4e2a10-5b9c-4e1a-9d3c-7b6a5e4f3c2d");

    private static BluetoothCompanionManager instance;

    private final Context appContext;
    private final AppPreferences prefs;
    private final ExecutorService btExecutor;
    private final Handler mainHandler;
    private final List<CompanionStateListener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean receiverRunning = false;
    private BluetoothServerSocket serverSocket;
    private Thread receiverThread;
    private String currentConnectionStatus = "Disconnected";

    public interface CompanionStateListener {
        void onCompanionStateChanged(boolean receiverActive, String connectionStatus, String lastDeliveryStatus);
        void onCompanionAlertReceived(AlertEvent alert);
    }

    public interface DeliveryCallback {
        void onDeliveryResult(boolean delivered, String statusMessage);
    }

    public static class BondedDeviceItem {
        public final String name;
        public final String address;

        public BondedDeviceItem(String name, String address) {
            this.name = name != null ? name : "Paired Device";
            this.address = address != null ? address : "";
        }

        @Override
        public String toString() {
            return name + " (" + address + ")";
        }
    }

    private BluetoothCompanionManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = new AppPreferences(appContext);
        this.btExecutor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized BluetoothCompanionManager getInstance(Context context) {
        if (instance == null) {
            instance = new BluetoothCompanionManager(context);
        }
        return instance;
    }

    public void addListener(CompanionStateListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(CompanionStateListener listener) {
        listeners.remove(listener);
    }

    public boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public boolean isBluetoothEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }

    @SuppressLint("MissingPermission")
    public List<BondedDeviceItem> getBondedDevices() {
        List<BondedDeviceItem> result = new ArrayList<>();
        if (!hasBluetoothConnectPermission()) {
            return result;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return result;
        }
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    result.add(new BondedDeviceItem(d.getName(), d.getAddress()));
                }
            }
        } catch (SecurityException ignored) {
        }
        return result;
    }

    public boolean isReceiverRunning() {
        return receiverRunning;
    }

    public String getCurrentConnectionStatus() {
        return currentConnectionStatus;
    }

    /**
     * Explicitly starts the RFCOMM Companion Receiver server loop on the second phone.
     */
    @SuppressLint("MissingPermission")
    public synchronized void startCompanionReceiver() {
        if (receiverRunning) {
            return;
        }
        if (!hasBluetoothConnectPermission()) {
            updateState(false, "Bluetooth permission denied", prefs.getBtLastDeliveryStatus());
            return;
        }
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            updateState(false, "Bluetooth is disabled", prefs.getBtLastDeliveryStatus());
            return;
        }

        receiverRunning = true;
        updateState(true, "Companion Receiver Listening (RFCOMM UUID " + SOUNDBRIDGE_UUID.toString().substring(0, 8) + "...)", prefs.getBtLastDeliveryStatus());

        receiverThread = new Thread(() -> {
            while (receiverRunning) {
                BluetoothSocket clientSocket = null;
                try {
                    serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SOUNDBRIDGE_UUID);
                    clientSocket = serverSocket.accept();
                    if (clientSocket != null) {
                        handleIncomingCompanionSocket(clientSocket);
                    }
                } catch (IOException e) {
                    if (receiverRunning) {
                        updateState(true, "Receiver waiting for connection...", prefs.getBtLastDeliveryStatus());
                        try {
                            Thread.sleep(1500L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (SecurityException se) {
                    receiverRunning = false;
                    updateState(false, "Bluetooth permission revoked", prefs.getBtLastDeliveryStatus());
                    break;
                } finally {
                    closeQuietly(clientSocket);
                    closeServerQuietly();
                }
            }
            receiverRunning = false;
            updateState(false, "Companion Receiver Stopped", prefs.getBtLastDeliveryStatus());
        }, "SoundBridgeBtReceiverThread");
        receiverThread.start();
    }

    private void handleIncomingCompanionSocket(BluetoothSocket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        String line = reader.readLine();
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        try {
            final AlertEvent receivedAlert = AlertEvent.fromBluetoothPayloadJson(line);
            writer.println("ACK:" + receivedAlert.getId());
            writer.flush();

            String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            String statusMsg = "Received '" + receivedAlert.getLabel() + "' at " + timeStr;
            prefs.setBtLastDeliveryStatus(statusMsg);
            updateState(receiverRunning, "Companion Connected — Alert Received", statusMsg);

            SoundBridgeRepository.getInstance(appContext).recordAlert(receivedAlert);
            VibrationHelper.triggerAlertVibration(appContext, receivedAlert.getCategory());

            mainHandler.post(() -> {
                for (CompanionStateListener l : listeners) {
                    l.onCompanionAlertReceived(receivedAlert);
                }
            });
        } catch (Exception e) {
            writer.println("ERR:MALFORMED_PAYLOAD");
            writer.flush();
            updateState(receiverRunning, "Ignored malformed Bluetooth message", prefs.getBtLastDeliveryStatus());
        }
    }

    public synchronized void stopCompanionReceiver() {
        receiverRunning = false;
        closeServerQuietly();
        if (receiverThread != null) {
            receiverThread.interrupt();
            receiverThread = null;
        }
        updateState(false, "Companion Receiver Stopped", prefs.getBtLastDeliveryStatus());
    }

    /**
     * Sends a detected AlertEvent over Bluetooth Classic RFCOMM to the configured bonded companion phone.
     * Verifies ACK before marking DELIVERY_BT_DELIVERED.
     */
    @SuppressLint("MissingPermission")
    public void sendAlertToCompanionAsync(final AlertEvent alert, final DeliveryCallback callback) {
        if (alert == null) return;
        final String targetAddress = prefs.getBtCompanionAddress();
        if (targetAddress == null || targetAddress.trim().isEmpty()) {
            if (callback != null) {
                mainHandler.post(() -> callback.onDeliveryResult(false, "No paired companion device selected"));
            }
            return;
        }
        if (!hasBluetoothConnectPermission()) {
            markFailedAndNotify(alert, "Bluetooth permission not granted", callback);
            return;
        }
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            markFailedAndNotify(alert, "Bluetooth is turned off", callback);
            return;
        }

        btExecutor.execute(() -> {
            BluetoothSocket socket = null;
            try {
                BluetoothDevice device = adapter.getRemoteDevice(targetAddress);
                socket = device.createRfcommSocketToServiceRecord(SOUNDBRIDGE_UUID);
                socket.connect();

                PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                String payload = alert.toBluetoothPayloadJson();
                writer.println(payload);
                writer.flush();

                String ackLine = reader.readLine();
                if (ackLine != null && ackLine.trim().equals("ACK:" + alert.getId())) {
                    alert.setDeliveryStatus(AlertEvent.DELIVERY_BT_DELIVERED);
                    SoundBridgeRepository.getInstance(appContext).updateAlert(alert);
                    String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
                    String msg = "Delivered '" + alert.getLabel() + "' to " + prefs.getBtCompanionName() + " at " + timeStr;
                    prefs.setBtLastDeliveryStatus(msg);
                    updateState(receiverRunning, "Connected to " + prefs.getBtCompanionName(), msg);
                    if (callback != null) {
                        mainHandler.post(() -> callback.onDeliveryResult(true, msg));
                    }
                } else {
                    markFailedAndNotify(alert, "Companion did not acknowledge alert (" + ackLine + ")", callback);
                }
            } catch (Exception e) {
                markFailedAndNotify(alert, "BT RFCOMM send failed: " + e.getMessage(), callback);
            } finally {
                closeQuietly(socket);
            }
        });
    }

    private void markFailedAndNotify(AlertEvent alert, final String reason, final DeliveryCallback callback) {
        alert.setDeliveryStatus(AlertEvent.DELIVERY_BT_FAILED);
        SoundBridgeRepository.getInstance(appContext).updateAlert(alert);
        prefs.setBtLastDeliveryStatus(reason);
        updateState(receiverRunning, "Delivery failed", reason);
        if (callback != null) {
            mainHandler.post(() -> callback.onDeliveryResult(false, reason));
        }
    }

    private void updateState(final boolean active, final String connStatus, final String lastDelivery) {
        this.currentConnectionStatus = connStatus;
        mainHandler.post(() -> {
            for (CompanionStateListener l : listeners) {
                l.onCompanionStateChanged(active, connStatus, lastDelivery);
            }
        });
    }

    private void closeServerQuietly() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
    }

    private static void closeQuietly(BluetoothSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}

