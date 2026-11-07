# SoundBridge — Live Student Demonstration Guide (`DEMO_GUIDE.md`)

This guide provides a step-by-step script for demonstrating all **11 required product flows and Android course topics** in a classroom or grading session.

---

## Quick Pre-Demo Checklist

### Testable on a Single Phone or Android Emulator (Offline Ready)
- [x] **Local / Demo Mode Authentication Banner & Entry** (`LoginActivity`)
- [x] **Foreground Monitoring & Custom `SoundLevelMeterView`** (`MainActivity` + `SoundMonitorForegroundService`)
- [x] **Built-In On-Device Sound Classification** (Doorbell, Knock, Alarm, Appliance Beep via real microphone or built-in 16 kHz acoustic waveform generator buttons)
- [x] **Custom Sound Recording, `ACTION_OPEN_DOCUMENT` Audio Import, Preview & Cosine-Similarity Matching** (`SoundLibraryActivity`)
- [x] **Background Foreground Notification, Deep-Link Tap-Through & Vibration** (`SoundMonitorForegroundService` -> `AlertDetailActivity`)
- [x] **Alert History `RecyclerView`, Filtering, Delete, Clear All & `ContentProvider` Query** (`HistoryActivity` + `SoundBridgeContentProvider`)
- [x] **Implicit Share Intent (`Intent.ACTION_SEND`)** (`AlertDetailActivity`)
- [x] **System `BroadcastReceiver` Events** (`ACTION_BATTERY_LOW`, `ACTION_BATTERY_OKAY`, `ACTION_AIRPLANE_MODE_CHANGED`)
- [x] **Offline Self-Test Parser for `org.json` and `XmlPullParser`** (`ApiDemoActivity`)
- [x] **Screen Rotation (`portrait` <-> `landscape`) with State Preservation** (`MainActivity`)

### Requires Internet / Firebase Credentials / Second Physical Phone
- [ ] **Live Google Sign-In + Cloud Firestore + Realtime Database Sync**: Requires `app/google-services.json`, debug SHA-1 registered in Firebase Console, and internet connectivity.
- [ ] **Live HTTPS GET JSON & XML Network Requests**: Requires internet connectivity in `ApiDemoActivity`.
- [ ] **Live Bluetooth Classic RFCOMM Companion Delivery**: Requires **two physical Android phones** paired in Android Settings with SoundBridge installed on both.

---

## Step-by-Step Live Demonstration Script (11 Steps)

### Step 1: First Launch, Dark Mode & Authentication (`LoginActivity`)
1. Launch **SoundBridge** (notice the adaptive SoundBridge launcher icon on the phone home screen).
2. Tap the top-right **Moon / Theme** icon to demonstrate instant **Light / Dark Mode** switching.
3. Sign in or continue offline:
   - Tap **Continue with Google** to open the native Google account chooser, authenticate with Firebase Auth, create `users/{uid}` in Cloud Firestore, and set `presence/{uid}/{deviceId}` online in Realtime Database.
   - Or tap **Continue without an account** for immediate offline on-device sound detection.

### Step 2: Start Listening & Observe Custom `SoundLevelMeterView` (`MainActivity`)
1. On the clean Home Dashboard, point out the **Paused** badge on the **Sound Detection** card.
2. Tap **Start Listening** and grant `RECORD_AUDIO` (and `POST_NOTIFICATIONS` on Android 13+).
3. Show that `SoundMonitorForegroundService` starts immediately, the badge turns green (**Listening**), and the custom `SoundLevelMeterView` renders the 20-bar smooth sound-wave meter and room sound level in both Light and Dark mode.

### Step 3: Default On-Device Sound Recognition (No Calibration Required)
1. Produce a real sound near the microphone (knock on the desk, ring a doorbell/alarm) **or** open **Settings** -> scroll to **Sound Check & Diagnostics** at the bottom -> tap **Doorbell**, **Knock**, **Alarm**, or **Chime** to play a live 16 kHz waveform through `AudioTrack` and classify it on-device.
2. Return to the Home Dashboard and point out the **Recent Alert** card displaying the clean sound title, category icon, relative timestamp, and match percentage.

### Step 4: Custom Sound Recognition (`SoundLibraryActivity`)
1. Tap **Custom Sounds** on the Dashboard.
2. Enter a name such as `Kitchen Timer`.
3. Tap **Record** (captures 1.5 seconds of 16 kHz mono PCM on a background thread) **or** tap **Import** (`Intent.ACTION_OPEN_DOCUMENT` + `ContentResolver`).
4. Tap **Play** to preview the clip, then tap **Save Custom Sound**. Use **+ Recording** on the saved card to add 2–3 varied recordings for highest accuracy.

### Step 5: Background Listening, Foreground Notification & Deep Link (`SoundMonitorForegroundService`)
1. With listening active, press the Android **Home** button so SoundBridge runs in the background.
2. Pull down the Android notification shade to show **SoundBridge is listening** (**Open** / **Pause**).
3. Trigger a sound and tap the high-priority alert notification to open **`AlertDetailActivity`** directly.

### Step 6: Alert History `RecyclerView`, `ContentProvider` & Cloud Sync (`HistoryActivity`)
1. From the Dashboard, tap **Alert History**.
2. Demonstrate the `RecyclerView` (`AlertHistoryAdapter`), filter chips (**All Alerts**, **Household & Safety**, **Custom Sounds**), item deletion, and **Clear All**.
3. To verify the private `SoundBridgeContentProvider` (`content://com.soundbridge.app.provider/alerts`), open **Settings** -> **Sound Check & Diagnostics** -> tap **Storage Check**.

### Step 7: Implicit Share Intent (`AlertDetailActivity`)
1. Tap any alert card to open `AlertDetailActivity`.
2. Tap **Mark as Seen** for tactile confirmation, and tap **Share Alert** (or the top-right share icon) to open Android's share sheet (`Intent.ACTION_SEND`).

### Step 8: `BroadcastReceiver` System Events (`SystemStateBroadcastReceiver`)
1. On the Dashboard, note that no cluttering system banner is shown during normal operation.
2. Toggle **Airplane Mode** in Android Quick Settings (or simulate low battery via `adb shell cmd battery set level 10`): a clean warning banner appears automatically on the Dashboard while local sound detection continues uninterrupted.

### Step 9: Service Connectivity Diagnostics (`ApiDemoActivity`)
1. Open **Settings** -> **Sound Check & Diagnostics** -> tap **Connection Check**.
2. Tap **Check JSON Service**, **Check XML Service**, or **Run Offline Diagnostics** to verify `org.json` and `XmlPullParser` off the main thread.

### Step 10: Companion Phone Bluetooth Alerts (`SettingsActivity`)
1. Pair **Phone A** and **Phone B** in Android Bluetooth Settings.
2. On **Phone B**, open **Settings** -> **Companion Phone (Bluetooth)** -> tap **Receive Alerts on This Phone**.
3. On **Phone A**, tap **Choose Phone**, pick Phone B, and tap **Send Test** (or trigger any real sound alert).

### Step 11: Screen Rotation & State Preservation (`MainActivity`)
1. On `MainActivity`, rotate the device/emulator between **Portrait** and **Landscape**.
2. Show that Android loads the dedicated two-column landscape layout (`res/layout-land/activity_main.xml`), `SoundLevelMeterView` resizes cleanly in `onMeasure`/`onDraw`, and the active monitoring state and latest alert are preserved across configuration changes.

