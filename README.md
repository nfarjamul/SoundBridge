# SoundBridge — Assistive Environmental Sound Awareness for Android

**SoundBridge** is a native Java/XML Android application designed for deaf and hard-of-hearing users. It monitors the local acoustic environment on-device at 16 kHz, identifies critical household and safety sounds (**Doorbells**, **Door Knocks**, **Smoke/Fire Alarms**, **Appliance Beeps**, and **User-Enrolled Custom Sounds**), and delivers immediate high-contrast visual alerts, category-specific tactile vibration patterns, background foreground-service notifications, Cloud Firestore/Realtime Database synchronization, and Bluetooth Classic RFCOMM companion phone alerts.

---

## 1. Toolchain & Version Pinning Rationale

Before writing code, the local Windows build environment was inspected and the project was pinned to mutually compatible versions that open, sync, and build directly in the installed IDE without requiring any IDE or JDK upgrade:

| Component | Pinned Version / Path | Rationale |
|---|---|---|
| **Android Studio IDE** | **Flamingo \| 2022.2.1 Canary 1** (`AI-222.3739.54.2221.9043926`) | Installed in `C:\Program Files\Android\Android Studio`. |
| **Java / JDK** | **Java 11 (`11.0.15` JetBrains Runtime)** | Bundled with Flamingo at `C:\Program Files\Android\Android Studio\jbr`; `sourceCompatibility` and `targetCompatibility` set to `JavaVersion.VERSION_11`. |
| **Gradle Wrapper** | **Gradle `7.6.3`** (`gradle-7.6.3-bin.zip`) | Compatible with JDK 11 and AGP 7.4.x; cached locally. |
| **Android Gradle Plugin** | **AGP `7.4.2`** (`com.android.application`) | Stable AGP 7.4 line supported by Android Studio Flamingo without requiring JDK 17 or AGP 8.x upgrades. |
| **Google Services Plugin** | **`4.3.15`** (`com.google.gms.google-services`) | Applied conditionally in `app/build.gradle` when `app/google-services.json` is present so clean clones build immediately before Firebase credentials are added. |
| **SDK Versions** | `compileSdk 34`, `minSdk 26`, `targetSdk 34` | `android-34` is installed in `C:\Users\Mypc\AppData\Local\Android\Sdk\platforms\android-34`. `android.suppressUnsupportedCompileSdk=34` is set in `gradle.properties` for clean Flamingo sync. |
| **Language & UI** | **100% Java + XML Layouts + View Binding** | Zero Kotlin files, zero Kotlin DSL, zero Jetpack Compose. |

---

## 2. Architecture & Package Structure

All source code lives under `app/src/main/java/com/soundbridge/app/` with clear separation of concerns:

- **`ui/`**:
  - `LoginActivity`: Welcome screen, Firebase configuration diagnostics, Google Sign-In backed by Firebase Authentication (`accounts:signInWithIdp` / Play Services), and clearly marked Local/Demo Mode when `google-services.json` is absent.
  - `MainActivity`: Primary dashboard synchronized strictly with `SoundMonitorForegroundService`, runtime permission handling (`RECORD_AUDIO`, `POST_NOTIFICATIONS`), live `SoundLevelMeterView`, latest alert banner, Realtime Database live presence observer, dynamic `SystemStateBroadcastReceiver` registration, and live 16 kHz acoustic waveform test buttons. Has dedicated portrait (`layout/activity_main.xml`) and landscape (`layout-land/activity_main.xml`) layouts with `onSaveInstanceState` preservation.
  - `SoundLevelMeterView`: Custom `View` subclass (`onMeasure` + zero-allocation `onDraw`) rendering a 16-bar rolling RMS envelope history, status pulse halo, active candidate classification, and accessibility `contentDescription` updates.
  - `HistoryActivity` + `AlertHistoryAdapter`: `RecyclerView` + custom `ViewHolder` (`item_alert_history.xml`) with All / Built-in / Custom filtering, empty state, item deletion, Clear All confirmation dialog, and a `Query via ContentProvider` action demonstrating `ContentResolver.query()`.
  - `SoundLibraryActivity` + `CustomSoundAdapter`: Guided 1.5-second 16 kHz microphone recording on a background worker thread, audio file import via `Intent.ACTION_OPEN_DOCUMENT` + `ContentResolver`, `AudioTrack` preview playback, 64-D acoustic embedding extraction, multi-sample enrollment, rename, enable/disable, and delete.
  - `SettingsActivity`: Confidence threshold slider, cooldown window slider, category toggles, vibration toggles, low-battery inference throttle toggle, Bluetooth Classic RFCOMM paired phone selector & Companion Receiver toggle, Sign Out, and Delete Account & Cloud Data.
  - `AlertDetailActivity`: High-visibility alert screen opened from notifications or history rows; supports alert acknowledgment, deletion, and sharing via `Intent.ACTION_SEND` (`Intent.createChooser`).
  - `ApiDemoActivity`: Isolated HTTPS GET client (`HttpDemoClient`) demonstrating background JSON parsing (`org.json.JSONObject`/`JSONArray`) and XML parsing (`org.xmlpull.v1.XmlPullParser`) plus an offline self-test mode.
- **`audio/`**:
  - `AudioFeatureExtractor`: Computes RMS amplitude, 64-D L2-normalized log-mel + temporal envelope + harmonicity embeddings from 16 kHz mono PCM windows (`15600` samples = `0.975s`), and cosine similarity.
  - `SoundDetectionGate`: Maps official AudioSet/YAMNet labels to SoundBridge categories, applies exponential moving average smoothing (`alpha = 0.65`) across consecutive windows, evaluates custom sound cosine similarity, resolves competing built-in vs. custom matches, and enforces confidence thresholds, cooldown windows, and duplicate suppression.
  - `SoundClassifierEngine`: Loads `assets/yamnet_label_list.txt` (521 AudioSet classes) and executes TensorFlow Lite inference via `org.tensorflow.lite.Interpreter` (`assets/yamnet.tflite` or bundled `assets/soundbridge_yamnet.tflite`).
- **`service/`**:
  - `SoundMonitorForegroundService`: User-started `foregroundServiceType="microphone"` service with ongoing notification (`Open` and `Stop Monitoring` actions), guarded single `AudioRecord` capture loop on a dedicated background thread, immediate buffer zeroing after inference, high-priority alert notifications with `PendingIntent` deep links to `AlertDetailActivity`, and automatic Bluetooth companion forwarding.
- **`receiver/`**:
  - `SystemStateBroadcastReceiver`: Handles `Intent.ACTION_BATTERY_LOW`, `Intent.ACTION_BATTERY_OKAY`, `Intent.ACTION_AIRPLANE_MODE_CHANGED`, and `BluetoothAdapter.ACTION_STATE_CHANGED`. Logs system events to SQLite and Firestore and throttles inference rate during low battery.
- **`bluetooth/`**:
  - `BluetoothCompanionManager`: Bluetooth Classic RFCOMM client and server (`UUID: 8f4e2a10-5b9c-4e1a-9d3c-7b6a5e4f3c2d`) using bonded devices, newline-delimited versioned JSON payloads (`AlertEvent.toBluetoothPayloadJson()`), and explicit `ACK:<eventId>` verification before marking `BT_DELIVERED`.
- **`data/` & `firebase/` & `model/` & `util/`**:
  - `AlertDatabaseHelper` (SQLite), `SoundBridgeContentProvider` (`content://com.soundbridge.app.provider/alerts`, `exported="false"`), `SoundBridgeRepository`, `AppPreferences`, `FirebaseManager`, `AlertEvent`, `CustomSoundProfile`, `SystemEventRecord`, `VibrationHelper`, `WavAudioUtil`, `HttpDemoClient`.

---

## 3. Firebase Setup Instructions (Auth, Firestore, Realtime Database)

SoundBridge integrates **all three** required Firebase services in [`FirebaseManager.java`](file:///c:/Users/Mypc/AndroidStudioProjects/SoundBridge/app/src/main/java/com/soundbridge/app/firebase/FirebaseManager.java):
1. **Firebase Authentication** (Google Sign-In provider)
2. **Cloud Firestore** (durable user profile, settings, custom sound metadata, alert history, and system events)
3. **Firebase Realtime Database** (low-latency live presence `presence/{uid}/{deviceId}` and latest alert state `liveAlerts/{uid}/{deviceId}`)

### Step-by-Step Firebase Console Configuration
1. Open the [Firebase Console](https://console.firebase.google.com/) and create a project (e.g., `soundbridge-assistive`).
2. Add an Android app with package name **`com.soundbridge.app`**.
3. Register your debug signing certificate **SHA-1** and **SHA-256** fingerprints (required for Google Sign-In):
   ```powershell
   & "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -list -v -alias androiddebugkey -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android -keypass android
   ```
4. In **Build -> Authentication -> Sign-in method**, enable **Google** and configure the support email.
5. In **Build -> Firestore Database**, create a database and publish the UID-scoped rules from [`firebase/firestore.rules`](file:///c:/Users/Mypc/AndroidStudioProjects/SoundBridge/firebase/firestore.rules).
6. In **Build -> Realtime Database**, create a database and publish the UID-scoped rules from [`firebase/database.rules.json`](file:///c:/Users/Mypc/AndroidStudioProjects/SoundBridge/firebase/database.rules.json).
7. Download **`google-services.json`** from Project Settings and place it at:
   ```text
   app/google-services.json
   ```
8. Sync/rebuild the project. When `app/google-services.json` is absent, SoundBridge automatically displays a diagnostic setup banner on `LoginActivity` and offers **Local / Demo Mode** without pretending cloud features are active.

---

## 4. On-Device Sound Model & Custom Sound Recognition

### Model Attribution & Assets
- **Ontology & Label Map**: [`app/src/main/assets/yamnet_label_list.txt`](file:///c:/Users/Mypc/AndroidStudioProjects/SoundBridge/app/src/main/assets/yamnet_label_list.txt) — Official 521-class AudioSet ontology used by TensorFlow Hub YAMNet (Apache License 2.0, Copyright Google LLC).
- **Bundled TFLite Classifier**: [`app/src/main/assets/soundbridge_yamnet.tflite`](file:///c:/Users/Mypc/AndroidStudioProjects/SoundBridge/app/src/main/assets/soundbridge_yamnet.tflite) — FlatBuffer `TFL3` model mapping 64-D 16 kHz log-mel/temporal-envelope features to the 521 AudioSet classes via `org.tensorflow.lite.Interpreter`.
- **Full Raw-Waveform YAMNet Asset (Optional Upgrade)**: Run [`scripts/download_yamnet.ps1`](file:///c:/Users/Mypc/AndroidStudioProjects/SoundBridge/scripts/download_yamnet.ps1) when internet access is available to download the full ~4 MB TensorFlow Hub `yamnet.tflite` (`input: float32[15600]` @ 16 kHz mono, `output: float32[1, 521]`) into `app/src/main/assets/yamnet.tflite`. `SoundClassifierEngine` automatically detects and prioritizes `yamnet.tflite` when present.

### Built-In Category Mapping & Best-Effort Disclosure
- **Doorbell**: Maps AudioSet classes `Doorbell`, `Ding-dong`, `Tubular bells`, `Chime`, `Bell`, `Telephone bell ringing`.
- **Knock**: Maps `Knock`, `Tap`, `Wood block`, `Thump, thud`, `Door`, `Slam`.
- **Alarm**: Maps `Smoke detector, smoke alarm`, `Fire alarm`, `Alarm`, `Alarm clock`, `Siren`, `Civil defense siren`, `Car alarm`.
- **Appliance Beep (Best-Effort Category)**: Because AudioSet does not train separate classes for every brand of household washer/dryer/oven, SoundBridge maps `Beep, bleep`, `Buzzer`, `Microwave oven`, and `Ping` to **Appliance Beep** and explicitly discloses in the UI that users should enroll their specific appliance in **Custom Sounds** for higher precision.

### Custom Sound Recognition (64-D Acoustic Embeddings + Cosine Similarity)
- Recording a custom sound does **not** merely store a text label. `AudioFeatureExtractor.extractEmbedding64D()` computes a 64-dimensional L2-normalized feature vector from the 16 kHz mono PCM clip (32 log-spaced Goertzel spectral bands from 120 Hz–7400 Hz, 16 temporal envelope sub-frames capturing rhythmic cadence, 8 envelope attack/decay/duty-cycle features, and 8 harmonicity/centroid descriptors).
- During live monitoring, `SoundDetectionGate.findBestCustomSoundMatch()` computes **cosine similarity** against all enabled `CustomSoundProfile` examples.
- **Competing Match Rule**: High-confidence life-safety `ALARM` detections (`>= 0.78`) take priority unless a custom match is near-exact (`>= 0.92`); otherwise, a user's enabled custom sound exceeding its similarity threshold (`0.82` default) wins over generic built-in categories.
- **Privacy**: Raw custom WAV recordings and full embeddings are stored locally in app-private storage (`getFilesDir()/custom_sounds`) and are never uploaded by default.

---

## 5. Permissions Declared

| Permission | Purpose |
|---|---|
| `RECORD_AUDIO` | Required to capture 16 kHz mono PCM audio for on-device sound classification and custom sound recording. |
| `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_MICROPHONE` | Required to continue microphone monitoring via `SoundMonitorForegroundService` when the user leaves the app. |
| `POST_NOTIFICATIONS` | Required on Android 13+ (API 33+) to display high-priority visual/vibration sound alert notifications. |
| `VIBRATE` | Triggers distinct tactile vibration waveforms for alarms, doorbells, knocks, and beeps. |
| `BLUETOOTH_CONNECT` & `BLUETOOTH_SCAN` (`neverForLocation`) / legacy `BLUETOOTH` (`maxSdkVersion=30`) | Connects to a user-selected bonded Android phone over Bluetooth Classic RFCOMM to deliver companion alerts. |
| `INTERNET` & `ACCESS_NETWORK_STATE` | Used for Firebase Auth/Firestore/Realtime Database sync and `ApiDemoActivity`. Core sound detection works 100% offline. |

---

## 6. Honest Android Platform Limitations

1. **User-Started Foreground Monitoring Only**: Android prohibits silent background microphone launch from boot or broadcast receivers. Monitoring must be started explicitly by the user from `MainActivity`.
2. **Force-Stop, Reboot, and OEM Battery Killers**: Monitoring stops if the user force-stops SoundBridge in Android Settings, revokes microphone permission, powers off the phone, or if aggressive OEM battery management terminates foreground services.
3. **Single-Sample Custom Sound Reliability**: Enrolling only 1 sample of a custom sound may be sensitive to background noise or distance; SoundBridge visibly recommends recording **2–3 varied examples**.
4. **Local Sample Storage**: Custom sound WAV files stay on the recording phone and do not automatically transfer to a new device.

---

## 7. Building and Running Tests

From the project root (`c:\Users\Mypc\AndroidStudioProjects\SoundBridge`):

```powershell
# Build debug APK
.\gradlew.bat assembleDebug

# Run JUnit 4 local unit tests
.\gradlew.bat testDebugUnitTest

# Run Android Lint checks
.\gradlew.bat lintDebug
```

