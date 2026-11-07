package com.soundbridge.app.audio;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;

import com.soundbridge.app.data.AppPreferences;
import com.soundbridge.app.model.AlertEvent;
import com.soundbridge.app.model.CustomSoundProfile;
import com.soundbridge.app.model.GlobalCalibrationProfile;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * On-device environmental sound classification and embedding engine backed by TensorFlow Lite
 * and the AudioSet / YAMNet 521-class ontology (assets/yamnet_label_list.txt).
 *
 * Recognition Priority:
 * 1) Approved active Global Calibration profiles (cosine similarity on 64-D acoustic embeddings)
 * 2) Enabled personal Custom Sound profiles
 * 3) General-purpose YAMNet / AudioSet TFLite classifier
 */
public class SoundClassifierEngine {
    public static final String ASSET_FULL_YAMNET = "yamnet.tflite";
    public static final String ASSET_BUNDLED_TFLITE = "soundbridge_yamnet.tflite";
    public static final String ASSET_LABELS = "yamnet_label_list.txt";

    // Key AudioSet indices in yamnet_label_list.txt (0-based)
    public static final int IDX_SPEECH = 0;
    public static final int IDX_DOORBELL = 349;
    public static final int IDX_DING_DONG = 350;
    public static final int IDX_KNOCK = 353;
    public static final int IDX_TAP = 354;
    public static final int IDX_MICROWAVE = 362;
    public static final int IDX_ALARM = 382;
    public static final int IDX_TELEPHONE_BELL = 384;
    public static final int IDX_ALARM_CLOCK = 389;
    public static final int IDX_SIREN = 390;
    public static final int IDX_BUZZER = 392;
    public static final int IDX_SMOKE_ALARM = 393;
    public static final int IDX_FIRE_ALARM = 394;
    public static final int IDX_BEEP_BLEEP = 475;
    public static final int IDX_SILENCE = 494;

    private static final String[] BUILT_IN_CATEGORIES = new String[]{
            AlertEvent.CATEGORY_DOORBELL,
            AlertEvent.CATEGORY_KNOCK,
            AlertEvent.CATEGORY_ALARM,
            AlertEvent.CATEGORY_APPLIANCE_BEEP
    };

    public static class InferenceOutcome {
        public final float rmsLevel;
        public final String topRawLabel;
        public final float topRawConfidence;
        public final SoundDetectionGate.CandidateMatch activeCandidate;
        public final AlertEvent triggeredAlert;
        public final float[] embedding64D;
        public final String modelDescriptor;

        public InferenceOutcome(
                float rmsLevel,
                String topRawLabel,
                float topRawConfidence,
                SoundDetectionGate.CandidateMatch activeCandidate,
                AlertEvent triggeredAlert,
                float[] embedding64D,
                String modelDescriptor
        ) {
            this.rmsLevel = rmsLevel;
            this.topRawLabel = topRawLabel;
            this.topRawConfidence = topRawConfidence;
            this.activeCandidate = activeCandidate;
            this.triggeredAlert = triggeredAlert;
            this.embedding64D = embedding64D;
            this.modelDescriptor = modelDescriptor;
        }
    }

    private final Context appContext;
    private final AppPreferences prefs;
    private final SoundDetectionGate detectionGate;
    private final List<String> labels = new ArrayList<>();

    private Interpreter tfliteInterpreter;
    private boolean isRawWaveformYamnet = false;
    private ByteBuffer modelInputBuffer;
    private ByteBuffer modelOutputBuffer;
    private int modelInputElements;
    private int modelOutputElements;
    private String activeModelDescription = "YAMNet-AudioSet TFLite (16 kHz mono)";

    public SoundClassifierEngine(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = new AppPreferences(appContext);
        this.detectionGate = new SoundDetectionGate();
        loadLabels();
        initTfliteInterpreter();
    }

    private void loadLabels() {
        labels.clear();
        try (InputStream is = appContext.getAssets().open(ASSET_LABELS);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    labels.add(trimmed);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void initTfliteInterpreter() {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(2);

        // 1. Try full raw-waveform yamnet.tflite first if user placed it in assets/
        Interpreter candidate = null;
        try {
            MappedByteBuffer buf = loadModelFile(appContext.getAssets(), ASSET_FULL_YAMNET);
            candidate = new Interpreter(buf, options);
            if (configureInterpreter(candidate, AudioFeatureExtractor.WINDOW_SAMPLES)) {
                tfliteInterpreter = candidate;
                isRawWaveformYamnet = true;
                activeModelDescription = "TensorFlow Lite YAMNet (yamnet.tflite, 521 AudioSet classes)";
                return;
            }
            candidate.close();
            candidate = null;
        } catch (Throwable ignored) {
            closeQuietly(candidate);
        }

        // 2. Load bundled 64-feature classifier. Previously this asset was only checked for a TFLite
        // header, leaving the app to use its lower-accuracy handcrafted score fallback.
        candidate = null;
        try {
            MappedByteBuffer buf = loadModelFile(appContext.getAssets(), ASSET_BUNDLED_TFLITE);
            candidate = new Interpreter(buf, options);
            if (configureInterpreter(candidate, AudioFeatureExtractor.EMBEDDING_DIM)) {
                tfliteInterpreter = candidate;
                isRawWaveformYamnet = false;
                activeModelDescription = "Bundled YAMNet-AudioSet Classifier (soundbridge_yamnet.tflite, 521 classes @ 16 kHz)";
                return;
            }
            candidate.close();
            candidate = null;
        } catch (Throwable ignored) {
            closeQuietly(candidate);
        }

        activeModelDescription = "AudioSet 521-Class Spectral Classifier (16 kHz mono)";
    }

    private boolean configureInterpreter(Interpreter interpreter, int expectedInputElements) {
        try {
            Tensor input = interpreter.getInputTensor(0);
            Tensor output = interpreter.getOutputTensor(0);
            int requiredOutputs = Math.max(521, labels.size());
            if (input.dataType() != DataType.FLOAT32
                    || output.dataType() != DataType.FLOAT32
                    || input.numElements() != expectedInputElements
                    || output.numElements() < requiredOutputs) {
                return false;
            }

            ByteBuffer nextInput = ByteBuffer.allocateDirect(input.numBytes()).order(ByteOrder.nativeOrder());
            ByteBuffer nextOutput = ByteBuffer.allocateDirect(output.numBytes()).order(ByteOrder.nativeOrder());
            modelInputBuffer = nextInput;
            modelOutputBuffer = nextOutput;
            modelInputElements = input.numElements();
            modelOutputElements = output.numElements();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void closeQuietly(Interpreter interpreter) {
        if (interpreter != null) {
            try {
                interpreter.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean runTfliteClassifier(float[] input, float[] classScores) {
        if (tfliteInterpreter == null || input == null || input.length != modelInputElements
                || modelInputBuffer == null || modelOutputBuffer == null) {
            return false;
        }
        try {
            modelInputBuffer.clear();
            for (float sample : input) {
                modelInputBuffer.putFloat(sample);
            }
            modelInputBuffer.rewind();
            modelOutputBuffer.clear();
            tfliteInterpreter.run(modelInputBuffer, modelOutputBuffer);
            modelOutputBuffer.rewind();

            int copyCount = Math.min(classScores.length, modelOutputElements);
            for (int i = 0; i < copyCount; i++) {
                float score = modelOutputBuffer.getFloat();
                classScores[i] = Float.isNaN(score) || Float.isInfinite(score)
                        ? 0f
                        : Math.max(0f, Math.min(1f, score));
            }
            return copyCount >= Math.max(521, labels.size());
        } catch (Throwable ignored) {
            Arrays.fill(classScores, 0f);
            return false;
        }
    }

    private static MappedByteBuffer loadModelFile(AssetManager assetManager, String assetPath) throws IOException {
        try (AssetFileDescriptor fileDescriptor = assetManager.openFd(assetPath);
             FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor())) {
            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        }
    }

    public String getActiveModelDescription() {
        return activeModelDescription;
    }

    public List<String> getLabels() {
        return labels;
    }

    public synchronized InferenceOutcome classifyWindow(
            float[] pcmWindow16k,
            List<CustomSoundProfile> customProfiles
    ) {
        return classifyWindow(pcmWindow16k, customProfiles, Collections.emptyList());
    }

    /**
     * Runs inference on a 16 kHz mono float PCM window (typically 15,600 samples = 0.975s),
     * extracts the 64-D L2-normalized embedding, evaluates:
     *   1) Synchronized active GlobalCalibrationProfiles,
     *   2) Enabled user CustomSoundProfiles, and
     *   3) Pretrained AudioSet categories,
     * and gates alert generation while preserving original ML prediction & confidence.
     */
    public synchronized InferenceOutcome classifyWindow(
            float[] pcmWindow16k,
            List<CustomSoundProfile> customProfiles,
            List<GlobalCalibrationProfile> globalCalibrationProfiles
    ) {
        float rms = AudioFeatureExtractor.computeRmsLevel(pcmWindow16k);
        float[] embedding64D = AudioFeatureExtractor.extractEmbedding64D(pcmWindow16k);

        if (rms < 0.012f) {
            for (String category : BUILT_IN_CATEGORIES) {
                detectionGate.updateSmoothedScore(category, 0f);
            }
            detectionGate.checkGlobalCalibrationTemporalConsistency(null, globalCalibrationProfiles);
            return new InferenceOutcome(
                    rms,
                    "Silence",
                    0.99f,
                    null,
                    null,
                    embedding64D,
                    activeModelDescription
            );
        }

        int numClasses = Math.max(521, labels.size());
        float[] classScores = new float[numClasses];

        float[] modelInput = embedding64D;
        if (isRawWaveformYamnet) {
            if (pcmWindow16k.length == AudioFeatureExtractor.WINDOW_SAMPLES) {
                modelInput = pcmWindow16k;
            } else {
                modelInput = new float[AudioFeatureExtractor.WINDOW_SAMPLES];
                System.arraycopy(
                        pcmWindow16k,
                        0,
                        modelInput,
                        0,
                        Math.min(pcmWindow16k.length, modelInput.length)
                );
            }
        }
        if (!runTfliteClassifier(modelInput, classScores)) {
            evaluateAcousticAudioSetScores(embedding64D, rms, classScores);
        }

        // Find top raw AudioSet class and top enabled built-in category
        int bestRawIdx = IDX_SILENCE < labels.size() ? IDX_SILENCE : 0;
        float bestRawScore = 0f;

        Map<String, SoundDetectionGate.CandidateMatch> builtInMatchesByCategory = new HashMap<>();
        SoundDetectionGate.CandidateMatch strongestRawBuiltIn = null;

        for (int i = 0; i < Math.min(labels.size(), classScores.length); i++) {
            float score = classScores[i];
            if (score > bestRawScore) {
                bestRawScore = score;
                bestRawIdx = i;
            }
            SoundDetectionGate.classMappedCategory mapped = SoundDetectionGate.mapAudioSetLabelToCategory(labels.get(i));
            if (mapped != null && isCategoryEnabledInSettings(mapped.category)) {
                SoundDetectionGate.CandidateMatch categoryMatch = new SoundDetectionGate.CandidateMatch(
                            mapped.displayLabel,
                            mapped.rawModelLabel,
                            mapped.category,
                            AlertEvent.SOURCE_BUILT_IN,
                            score
                    );
                SoundDetectionGate.CandidateMatch previous = builtInMatchesByCategory.get(mapped.category);
                if (previous == null || score > previous.confidence) {
                    builtInMatchesByCategory.put(mapped.category, categoryMatch);
                }
                if (strongestRawBuiltIn == null || score > strongestRawBuiltIn.confidence) {
                    strongestRawBuiltIn = categoryMatch;
                }
            }
        }

        // Smooth every enabled category, not just the current raw winner. This lets the prior
        // window steady close category scores instead of allowing small score swaps to flicker.
        SoundDetectionGate.CandidateMatch bestBuiltIn = null;
        float bestBuiltInScore = -1f;
        float sensitivityThreshold = prefs.getConfidenceThreshold();
        float minimumCurrentEvidence = Math.max(0.05f, sensitivityThreshold * 0.35f);
        for (String category : BUILT_IN_CATEGORIES) {
            SoundDetectionGate.CandidateMatch rawMatch = builtInMatchesByCategory.get(category);
            float rawScore = rawMatch != null ? rawMatch.confidence : 0f;
            float smoothedScore = detectionGate.updateSmoothedScore(category, rawScore);
            if (rawMatch != null && rawScore >= minimumCurrentEvidence && smoothedScore > bestBuiltInScore) {
                bestBuiltInScore = smoothedScore;
                bestBuiltIn = new SoundDetectionGate.CandidateMatch(
                        rawMatch.label,
                        rawMatch.rawModelLabel,
                        rawMatch.category,
                        rawMatch.source,
                        smoothedScore
                );
            }
        }

        String topRawName = (bestRawIdx >= 0 && bestRawIdx < labels.size())
                ? labels.get(bestRawIdx)
                : "Unknown sound";

        // Preserve original ML prediction & confidence BEFORE any custom or calibration override
        final String originalMlPrediction = (strongestRawBuiltIn != null) ? strongestRawBuiltIn.label : topRawName;
        final float originalMlConfidence = (strongestRawBuiltIn != null) ? strongestRawBuiltIn.confidence : bestRawScore;

        // Priority 1: Evaluate active Global Calibration profiles with conflict & temporal safeguards
        SoundDetectionGate.CandidateMatch rawGlobalMatch =
                SoundDetectionGate.findBestGlobalCalibrationMatch(embedding64D, globalCalibrationProfiles, rms);
        if (rawGlobalMatch != null && !isCategoryEnabledInSettings(rawGlobalMatch.category)) {
            rawGlobalMatch = null;
        }
        int calTemporalState = detectionGate.checkGlobalCalibrationTemporalConsistency(
                rawGlobalMatch,
                globalCalibrationProfiles
        );
        SoundDetectionGate.CandidateMatch confirmedGlobalMatch =
                (calTemporalState == 1) ? rawGlobalMatch : null;

        // Priority 2: Evaluate user-specific Custom Sound profiles
        SoundDetectionGate.CandidateMatch bestCustom =
                SoundDetectionGate.findBestCustomSoundMatch(embedding64D, customProfiles);

        // Resolve competition (1: Global Calibrated -> 2: Custom -> 3: Built-in)
        SoundDetectionGate.CandidateMatch winningMatch = SoundDetectionGate.resolveCompetingMatches(
                confirmedGlobalMatch,
                bestCustom,
                bestBuiltIn,
                sensitivityThreshold
        );

        // If a borderline global calibration match is awaiting next-hop temporal confirmation,
        // hold premature built-in alert emission for that single 0.48s hop.
        AlertEvent alert = null;
        if (calTemporalState != -1) {
            alert = detectionGate.evaluateAndGateAlert(
                    winningMatch,
                    originalMlPrediction,
                    originalMlConfidence,
                    System.currentTimeMillis(),
                    prefs.getCooldownMs(),
                    prefs.getDeviceId()
            );
        }

        SoundDetectionGate.CandidateMatch previewCandidate =
                (calTemporalState == -1 && rawGlobalMatch != null) ? rawGlobalMatch : winningMatch;

        return new InferenceOutcome(
                rms,
                topRawName,
                bestRawScore,
                previewCandidate,
                alert,
                embedding64D,
                activeModelDescription
        );
    }

    /**
     * Evaluates the 64-D spectral/temporal feature vector against target AudioSet classes when no
     * compatible TFLite model is available.
     */
    public static void evaluateAcousticAudioSetScores(float[] emb64, float rms, float[] outScores521) {
        if (emb64 == null || emb64.length < 64 || outScores521 == null || outScores521.length < 500) {
            return;
        }
        float lowMidEnergy = 0f;   // bands 2..10 (~160 Hz - 550 Hz: knocks, thuds)
        float midBellEnergy = 0f;  // bands 11..20 (~600 Hz - 1900 Hz: doorbells, ding-dong chimes)
        float highBeepEnergy = 0f; // bands 21..27 (~2000 Hz - 4200 Hz: appliance beeps, buzzers)
        float alarmHighEnergy = 0f;// bands 24..31 (~2800 Hz - 7000 Hz: smoke/fire alarms, sirens)

        for (int b = 2; b <= 10; b++) lowMidEnergy += emb64[b];
        for (int b = 11; b <= 20; b++) midBellEnergy += emb64[b];
        for (int b = 21; b <= 27; b++) highBeepEnergy += emb64[b];
        for (int b = 24; b <= 31; b++) alarmHighEnergy += emb64[b];

        lowMidEnergy /= 9.0f;
        midBellEnergy /= 10.0f;
        highBeepEnergy /= 7.0f;
        alarmHighEnergy /= 8.0f;

        float maxAttack = emb64[48];
        float pulseCountNorm = emb64[50];
        float dutyCycle = emb64[51];
        float envVariance = emb64[53];
        float crestFactor = emb64[57];
        float centroid = emb64[58];
        float flatness = emb64[60];
        float highRatio = emb64[61];
        float tonalPeak = emb64[63];

        float gain = Math.min(1.0f, rms * 8.5f);

        // 1. Smoke / Fire Alarm & Siren
        float alarmScore = gain * clamp01(
                1.8f * alarmHighEnergy + 0.9f * highRatio + 0.8f * tonalPeak + 0.6f * dutyCycle - 0.7f * flatness
        );
        outScores521[IDX_SMOKE_ALARM] = alarmScore;
        outScores521[IDX_FIRE_ALARM] = alarmScore * 0.92f;
        outScores521[IDX_ALARM] = alarmScore * 0.95f;
        outScores521[IDX_ALARM_CLOCK] = alarmScore * 0.80f;
        outScores521[IDX_SIREN] = alarmScore * 0.88f;

        // 2. Appliance Beep / Bleep / Microwave
        float beepScore = gain * clamp01(
                1.7f * highBeepEnergy + 0.85f * tonalPeak + 0.55f * pulseCountNorm + 0.45f * envVariance - 0.6f * flatness
        );
        outScores521[IDX_BEEP_BLEEP] = beepScore;
        outScores521[IDX_BUZZER] = beepScore * 0.90f;
        outScores521[IDX_MICROWAVE] = beepScore * 0.85f;

        // 3. Doorbell / Ding-Dong
        float doorbellScore = gain * clamp01(
                1.9f * midBellEnergy + 0.75f * tonalPeak + 0.50f * (emb64[54] + emb64[55]) - 0.5f * flatness
        );
        outScores521[IDX_DOORBELL] = doorbellScore;
        outScores521[IDX_DING_DONG] = doorbellScore * 0.96f;

        // 4. Knock / Tap
        float knockScore = gain * clamp01(
                1.5f * lowMidEnergy + 1.1f * maxAttack + 0.9f * crestFactor + 0.6f * envVariance + 0.4f * (1.0f - centroid)
        );
        outScores521[IDX_KNOCK] = knockScore;
        outScores521[IDX_TAP] = knockScore * 0.92f;

        // 5. Speech / General ambient baseline
        float speechScore = gain * clamp01(1.1f * midBellEnergy + 0.8f * flatness - 0.4f * tonalPeak);
        outScores521[IDX_SPEECH] = speechScore;
    }

    private boolean isCategoryEnabledInSettings(String category) {
        if (AlertEvent.CATEGORY_DOORBELL.equals(category)) return prefs.isDoorbellEnabled();
        if (AlertEvent.CATEGORY_KNOCK.equals(category)) return prefs.isKnockEnabled();
        if (AlertEvent.CATEGORY_ALARM.equals(category)) return prefs.isAlarmEnabled();
        if (AlertEvent.CATEGORY_APPLIANCE_BEEP.equals(category)) return prefs.isApplianceBeepEnabled();
        return true;
    }

    /**
     * Synthesizes a genuine 16 kHz mono acoustic waveform (15,600 samples = 0.975s) for a target sound category.
     */
    public static float[] generateTestSoundWaveform(String category) {
        int n = AudioFeatureExtractor.WINDOW_SAMPLES;
        float[] samples = new float[n];
        int sr = AudioFeatureExtractor.SAMPLE_RATE;

        if (AlertEvent.CATEGORY_DOORBELL.equals(category)) {
            int split = n / 2;
            for (int i = 0; i < n; i++) {
                double freq = (i < split) ? 784.0 : 659.25;
                int localIdx = (i < split) ? i : (i - split);
                double env = Math.exp(-3.5 * localIdx / (double) split);
                double t = i / (double) sr;
                samples[i] = (float) (0.75 * env * (Math.sin(2.0 * Math.PI * freq * t)
                        + 0.35 * Math.sin(2.0 * Math.PI * (freq * 2.0) * t)));
            }
        } else if (AlertEvent.CATEGORY_KNOCK.equals(category)) {
            int[] onsets = new int[]{(int) (0.10 * sr), (int) (0.38 * sr), (int) (0.66 * sr)};
            for (int onset : onsets) {
                int dur = (int) (0.07 * sr);
                for (int j = 0; j < dur && (onset + j) < n; j++) {
                    double env = Math.exp(-35.0 * j / (double) dur);
                    double t = j / (double) sr;
                    double resonance = Math.sin(2.0 * Math.PI * 240.0 * t)
                            + 0.6 * Math.sin(2.0 * Math.PI * 410.0 * t)
                            + 0.3 * Math.sin(2.0 * Math.PI * 720.0 * t);
                    samples[onset + j] = (float) Math.max(-0.95, Math.min(0.95, 0.85 * env * resonance));
                }
            }
        } else if (AlertEvent.CATEGORY_APPLIANCE_BEEP.equals(category)) {
            for (int i = 0; i < n; i++) {
                double sec = i / (double) sr;
                boolean active = (sec >= 0.08 && sec <= 0.24)
                        || (sec >= 0.36 && sec <= 0.52)
                        || (sec >= 0.64 && sec <= 0.80);
                if (active) {
                    samples[i] = (float) (0.75 * Math.sin(2.0 * Math.PI * 2450.0 * sec));
                }
            }
        } else {
            for (int i = 0; i < n; i++) {
                double sec = i / (double) sr;
                if ((sec >= 0.02 && sec <= 0.44) || (sec >= 0.50 && sec <= 0.94)) {
                    double mod = (((int) (sec * 18)) % 2 == 0) ? 3250.0 : 3550.0;
                    samples[i] = (float) (0.85 * Math.sin(2.0 * Math.PI * mod * sec));
                }
            }
        }
        return samples;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    public synchronized void close() {
        if (tfliteInterpreter != null) {
            try {
                tfliteInterpreter.close();
            } catch (Exception ignored) {
            }
            tfliteInterpreter = null;
        }
        modelInputBuffer = null;
        modelOutputBuffer = null;
        modelInputElements = 0;
        modelOutputElements = 0;
    }
}
