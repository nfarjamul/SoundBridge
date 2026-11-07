package com.soundbridge.app.audio;

import com.soundbridge.app.model.AlertEvent;
import com.soundbridge.app.model.CustomSoundProfile;
import com.soundbridge.app.model.GlobalCalibrationProfile;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure Java decision gate for environmental sound classification:
 * - Evaluates administrator-approved GlobalCalibrationProfiles using cosine similarity, configurable
 *   thresholds, conflict/ambiguity margin safeguards, and temporal consistency checks (Priority 1).
 * - Evaluates user-recorded CustomSoundProfiles (Priority 2).
 * - Maps verified AudioSet/YAMNet class names to SoundBridge categories (Priority 3).
 * - Smooths probabilities across consecutive inference windows to suppress single-frame transient noise.
 * - Enforces confidence thresholds, cooldown windows, and duplicate suppression while recording
 *   original ML prediction, original confidence, recognition method, and applied calibration ID.
 */
public class SoundDetectionGate {

    /**
     * Minimum RMS amplitude required before global calibration matching is even attempted.
     * Prevents silence and quiet ambient noise from spuriously matching calibration profiles.
     * At 16 kHz mono float [-1, 1], 0.025 represents a clearly audible sound well above
     * background noise. Without this, L2-normalized embeddings of quiet noise can produce
     * high cosine similarity with any stored profile.
     */
    public static final float CALIBRATION_MIN_RMS = 0.025f;

    /**
     * Minimum required similarity separation when two active GlobalCalibrationProfiles with DIFFERENT
     * canonical labels both satisfy their similarity thresholds. Prevents uncertain overrides when
     * conflicting profiles exist.
     */
    public static final float CALIBRATION_AMBIGUITY_MARGIN = 0.03f;

    public static class classMappedCategory {
        public final String category;
        public final String displayLabel;
        public final String rawModelLabel;

        public classMappedCategory(String category, String displayLabel, String rawModelLabel) {
            this.category = category;
            this.displayLabel = displayLabel;
            this.rawModelLabel = rawModelLabel;
        }
    }

    public static class CandidateMatch {
        public final String label;
        public final String rawModelLabel;
        public final String category;
        public final String source;
        public final float confidence;
        public final String appliedCalibrationId;

        public CandidateMatch(String label, String rawModelLabel, String category, String source, float confidence) {
            this(label, rawModelLabel, category, source, confidence, "");
        }

        public CandidateMatch(
                String label,
                String rawModelLabel,
                String category,
                String source,
                float confidence,
                String appliedCalibrationId
        ) {
            this.label = label;
            this.rawModelLabel = rawModelLabel;
            this.category = category;
            this.source = source;
            this.confidence = confidence;
            this.appliedCalibrationId = appliedCalibrationId != null ? appliedCalibrationId : "";
        }
    }

    private final Map<String, Float> smoothedCategoryScores = new HashMap<>();
    private final Map<String, Long> lastAlertTimestampByKey = new HashMap<>();
    private long lastGlobalAlertTimestampMs = 0L;

    // Temporal consistency state for borderline global calibration matches
    private String lastCalibrationLabelKey = null;
    private float lastCalibrationSimilarity = 0f;

    /**
     * Maps an official AudioSet / YAMNet label from yamnet_label_list.txt to a defensible SoundBridge category,
     * or returns null ("Unknown sound" / non-target ambient class such as Speech, Music, Silence).
     */
    public static classMappedCategory mapAudioSetLabelToCategory(String rawLabel) {
        if (rawLabel == null || rawLabel.trim().isEmpty()) {
            return null;
        }
        String normalized = rawLabel.trim().toLowerCase(Locale.US);

        // 1. Doorbell & Entry Chimes
        if (normalized.equals("doorbell")
                || normalized.equals("ding-dong")
                || normalized.equals("tubular bells")
                || normalized.equals("chime")
                || normalized.equals("bell")
                || normalized.equals("jingle bell")
                || normalized.equals("telephone bell ringing")
                || normalized.equals("ringtone")) {
            return new classMappedCategory(
                    AlertEvent.CATEGORY_DOORBELL,
                    "Doorbell (" + rawLabel.trim() + ")",
                    rawLabel.trim()
            );
        }

        // 2. Door Knock & Sharp Impacts
        if (normalized.equals("knock")
                || normalized.equals("tap")
                || normalized.equals("wood block")
                || normalized.equals("thump, thud")
                || normalized.equals("thunk")
                || normalized.equals("door")
                || normalized.equals("slam")) {
            return new classMappedCategory(
                    AlertEvent.CATEGORY_KNOCK,
                    "Knock (" + rawLabel.trim() + ")",
                    rawLabel.trim()
            );
        }

        // 3. Smoke, Fire & Emergency Alarms
        if (normalized.equals("smoke detector, smoke alarm")
                || normalized.equals("fire alarm")
                || normalized.equals("alarm")
                || normalized.equals("alarm clock")
                || normalized.equals("siren")
                || normalized.equals("civil defense siren")
                || normalized.equals("police car (siren)")
                || normalized.equals("ambulance (siren)")
                || normalized.equals("fire engine, fire truck (siren)")
                || normalized.equals("car alarm")) {
            return new classMappedCategory(
                    AlertEvent.CATEGORY_ALARM,
                    "Alarm (" + rawLabel.trim() + ")",
                    rawLabel.trim()
            );
        }

        // 4. Appliance Beeps (Defensible AudioSet mapping: Beep, bleep / Buzzer / Microwave oven)
        if (normalized.equals("beep, bleep")
                || normalized.equals("buzzer")
                || normalized.equals("microwave oven")
                || normalized.equals("ping")
                || normalized.equals("ding")
                || normalized.equals("reversing beeps")
                || normalized.equals("sine wave")
                || normalized.equals("chirp tone")) {
            return new classMappedCategory(
                    AlertEvent.CATEGORY_APPLIANCE_BEEP,
                    "Appliance Beep (" + rawLabel.trim() + ")",
                    rawLabel.trim()
            );
        }

        // Non-target sound (Speech, Music, Silence, Traffic, etc.) -> Unknown / No alert
        return null;
    }

    /**
     * Smooths consecutive window probabilities using an exponential moving average (alpha = 0.50)
     * so category changes need consistent evidence while sustained sounds remain responsive.
     */
    public synchronized float updateSmoothedScore(String key, float currentWindowScore) {
        Float prev = smoothedCategoryScores.get(key);
        float smoothed;
        if (prev == null) {
            smoothed = currentWindowScore * 0.80f;
        } else {
            smoothed = 0.50f * currentWindowScore + 0.50f * prev;
        }
        smoothedCategoryScores.put(key, smoothed);
        return smoothed;
    }

    public synchronized void decayUnusedScores(String activeKey) {
        for (Map.Entry<String, Float> entry : smoothedCategoryScores.entrySet()) {
            if (!entry.getKey().equals(activeKey)) {
                entry.setValue(entry.getValue() * 0.50f);
            }
        }
    }

    /**
     * Evaluates a live 64-D acoustic embedding against active synchronized GlobalCalibrationProfiles.
     *
     * Safeguards enforced:
     * 0) Minimum RMS energy check — silent/quiet frames are rejected before comparison.
     * 1) Active status & compatible embedding model version check.
     * 2) Profile-specific similarity threshold check (weak matches rejected).
     * 3) Multi-profile conflict / ambiguity margin check across distinct canonical labels:
     *    if two conflicting canonical labels both exceed threshold within CALIBRATION_AMBIGUITY_MARGIN,
     *    returns null so the normal classifier/custom logic handles it safely without guessing.
     */
    public static CandidateMatch findBestGlobalCalibrationMatch(
            float[] liveEmbedding,
            List<GlobalCalibrationProfile> globalProfiles,
            float rmsLevel
    ) {
        // Hard gate: do not even attempt calibration matching on quiet/silent audio
        if (rmsLevel < CALIBRATION_MIN_RMS) {
            return null;
        }
        if (liveEmbedding == null || liveEmbedding.length != AudioFeatureExtractor.EMBEDDING_DIM
                || globalProfiles == null || globalProfiles.isEmpty()) {
            return null;
        }

        GlobalCalibrationProfile bestProfile = null;
        float bestScore = 0f;

        // Track best score per canonical label so multiple profiles for the SAME label reinforce rather than conflict
        Map<String, Float> bestScoreByLabelKey = new HashMap<>();

        for (GlobalCalibrationProfile profile : globalProfiles) {
            if (profile == null || !profile.isActive() || !profile.isCompatibleWithCurrentEngine()) {
                continue;
            }
            float sim = profile.evaluateSimilarity(liveEmbedding);
            if (sim < profile.getSimilarityThreshold()) {
                continue;
            }

            String labelKey = profile.getCanonicalLabel().trim().toLowerCase(Locale.US);
            Float prevForLabel = bestScoreByLabelKey.get(labelKey);
            if (prevForLabel == null || sim > prevForLabel) {
                bestScoreByLabelKey.put(labelKey, sim);
            }

            if (sim > bestScore) {
                bestScore = sim;
                bestProfile = profile;
            }
        }

        if (bestProfile == null) {
            return null;
        }

        // Check for conflicting canonical labels with nearly identical similarity
        String winningKey = bestProfile.getCanonicalLabel().trim().toLowerCase(Locale.US);
        float runnerUpConflictingScore = 0f;
        for (Map.Entry<String, Float> entry : bestScoreByLabelKey.entrySet()) {
            if (!entry.getKey().equals(winningKey) && entry.getValue() > runnerUpConflictingScore) {
                runnerUpConflictingScore = entry.getValue();
            }
        }

        if (runnerUpConflictingScore > 0f && (bestScore - runnerUpConflictingScore) < CALIBRATION_AMBIGUITY_MARGIN) {
            // Uncertain / conflicting calibration match — fall back rather than making an unreliable override
            return null;
        }

        return new CandidateMatch(
                bestProfile.getCanonicalLabel(),
                bestProfile.getOriginalModelPrediction(),
                bestProfile.getCategory(),
                AlertEvent.SOURCE_GLOBAL_CALIBRATED,
                bestScore,
                bestProfile.getCalibrationId()
        );
    }

    /**
     * Backward-compatible overload without RMS — treats as sufficient energy.
     * @deprecated Use the 3-arg version with rmsLevel for proper silence rejection.
     */
    @Deprecated
    public static CandidateMatch findBestGlobalCalibrationMatch(
            float[] liveEmbedding,
            List<GlobalCalibrationProfile> globalProfiles
    ) {
        return findBestGlobalCalibrationMatch(liveEmbedding, globalProfiles, 1.0f);
    }

    /**
     * Verifies temporal consistency for global calibration matches across consecutive inference hops:
     * - Strong unambiguous matches (>= threshold + 0.06 or >= 0.92) confirm immediately.
     * - A consecutive-hop match (same label on previous hop with decent similarity) confirms immediately.
     * - Borderline matches (just above threshold on a single isolated hop) require confirmation on the next hop
     *   while holding premature uncalibrated alerts for that single 0.48s hop.
     * - When no match arrives, similarity state resets aggressively to prevent stale state from
     *   auto-confirming unrelated future matches.
     *
     * @return 1 if confirmed now, 0 if no calibration match, -1 if holding 1 hop for temporal verification.
     */
    public synchronized int checkGlobalCalibrationTemporalConsistency(
            CandidateMatch globalCandidate,
            List<GlobalCalibrationProfile> profiles
    ) {
        if (globalCandidate == null) {
            // Aggressive decay: a single non-matching hop resets the temporal state entirely
            lastCalibrationLabelKey = null;
            lastCalibrationSimilarity = 0f;
            return 0;
        }

        float profileThreshold = GlobalCalibrationProfile.DEFAULT_SIMILARITY_THRESHOLD;
        if (profiles != null) {
            for (GlobalCalibrationProfile p : profiles) {
                if (p != null && p.getCalibrationId().equals(globalCandidate.appliedCalibrationId)) {
                    profileThreshold = p.getSimilarityThreshold();
                    break;
                }
            }
        }

        String key = globalCandidate.label.trim().toLowerCase(Locale.US);

        // Consecutive-hop match: same label on previous hop AND previous similarity was strong enough
        boolean consecutiveMatch = key.equals(lastCalibrationLabelKey)
                && lastCalibrationSimilarity >= profileThreshold;

        // Strong direct match: well above threshold so temporal confirmation is unnecessary
        boolean strongDirectMatch = globalCandidate.confidence >= Math.min(0.95f, profileThreshold + 0.06f);

        lastCalibrationLabelKey = key;
        lastCalibrationSimilarity = globalCandidate.confidence;

        if (consecutiveMatch || strongDirectMatch) {
            return 1; // Confirmed
        }
        return -1; // Borderline first hop: wait one overlapping hop (~0.48s) to confirm persistence
    }

    /**
     * Evaluates a live 64-D embedding against all enabled user-defined CustomSoundProfiles
     * using cosine similarity.
     */
    public static CandidateMatch findBestCustomSoundMatch(float[] liveEmbedding, List<CustomSoundProfile> profiles) {
        if (liveEmbedding == null || liveEmbedding.length == 0 || profiles == null || profiles.isEmpty()) {
            return null;
        }
        CustomSoundProfile bestProfile = null;
        float bestScore = 0f;

        for (CustomSoundProfile profile : profiles) {
            if (profile == null || !profile.isEnabled() || profile.getExampleEmbeddings().isEmpty()) {
                continue;
            }
            float maxSim = 0f;
            int strongMatches = 0;
            for (float[] storedEmb : profile.getExampleEmbeddings()) {
                float sim = AudioFeatureExtractor.cosineSimilarity(liveEmbedding, storedEmb);
                if (sim > maxSim) {
                    maxSim = sim;
                }
                if (sim >= profile.getMatchingThreshold() - 0.04f) {
                    strongMatches++;
                }
            }
            // Slight consistency bonus (up to +0.025) when multiple stored examples agree
            if (maxSim >= profile.getMatchingThreshold() && strongMatches >= 2) {
                maxSim = Math.min(0.99f, maxSim + 0.025f);
            }
            if (maxSim >= profile.getMatchingThreshold() && maxSim > bestScore) {
                bestScore = maxSim;
                bestProfile = profile;
            }
        }

        if (bestProfile == null) {
            return null;
        }
        return new CandidateMatch(
                bestProfile.getName(),
                "Custom Example Similarity (" + bestProfile.getSampleCount() + " samples)",
                AlertEvent.CATEGORY_CUSTOM,
                AlertEvent.SOURCE_CUSTOM,
                bestScore
        );
    }

    /**
     * Full 3-tier recognition priority resolution (Section 7):
     * 1) Approved active Global Calibration profile match (SOURCE_GLOBAL_CALIBRATED)
     * 2) User-specific Custom Sound profile match (SOURCE_CUSTOM)
     * 3) Pretrained general-purpose Built-in sound classifier (SOURCE_BUILT_IN)
     *
     * Preserves life-safety Smoke/Fire/Siren priority if an unmistakable alarm (>= 0.84) occurs
     * while a non-alarm calibration is only moderate (< 0.91).
     */
    public static CandidateMatch resolveCompetingMatches(
            CandidateMatch globalCalibrationMatch,
            CandidateMatch customMatch,
            CandidateMatch builtInMatch,
            float builtInSensitivityThreshold
    ) {
        boolean validBuiltIn = builtInMatch != null && builtInMatch.confidence >= builtInSensitivityThreshold;

        if (globalCalibrationMatch != null) {
            if (validBuiltIn
                    && AlertEvent.CATEGORY_ALARM.equals(builtInMatch.category)
                    && !AlertEvent.CATEGORY_ALARM.equals(globalCalibrationMatch.category)
                    && builtInMatch.confidence >= 0.84f
                    && globalCalibrationMatch.confidence < 0.91f) {
                return builtInMatch;
            }
            return globalCalibrationMatch;
        }

        return resolveCompetingMatches(builtInMatch, customMatch, builtInSensitivityThreshold);
    }

    /**
     * Two-tier resolution preserved for backward compatibility:
     * 1. Life-safety Alarms (Smoke/Fire/Siren) with high confidence (>= 0.78) take priority unless
     *    the custom match is near-exact (>= 0.92).
     * 2. Otherwise, an enabled Custom Sound that exceeds its similarity threshold wins over generic
     *    built-in categories.
     * 3. If no custom sound matches, the built-in match is selected if it exceeds sensitivityThreshold.
     */
    public static CandidateMatch resolveCompetingMatches(
            CandidateMatch builtInMatch,
            CandidateMatch customMatch,
            float builtInSensitivityThreshold
    ) {
        boolean validBuiltIn = builtInMatch != null && builtInMatch.confidence >= builtInSensitivityThreshold;
        boolean validCustom = customMatch != null; // Already checked against profile.getMatchingThreshold()

        if (validBuiltIn && validCustom) {
            if (AlertEvent.CATEGORY_ALARM.equals(builtInMatch.category)
                    && builtInMatch.confidence >= 0.78f
                    && customMatch.confidence < 0.92f) {
                return builtInMatch;
            }
            return customMatch;
        }
        if (validCustom) {
            return customMatch;
        }
        if (validBuiltIn) {
            return builtInMatch;
        }
        return null;
    }

    /**
     * Checks cooldown and duplicate suppression rules. Returns an AlertEvent if allowed, or null if suppressed.
     */
    public synchronized AlertEvent evaluateAndGateAlert(
            CandidateMatch match,
            long nowMs,
            long cooldownMs,
            String deviceId
    ) {
        return evaluateAndGateAlert(
                match,
                match != null ? match.rawModelLabel : null,
                match != null ? match.confidence : 0f,
                nowMs,
                cooldownMs,
                deviceId
        );
    }

    /**
     * Checks cooldown and duplicate suppression rules and records original ML prediction,
     * original confidence, final label, recognition method, and applied calibration ID.
     */
    public synchronized AlertEvent evaluateAndGateAlert(
            CandidateMatch match,
            String originalMlPrediction,
            float originalMlConfidence,
            long nowMs,
            long cooldownMs,
            String deviceId
    ) {
        if (match == null) {
            return null;
        }
        String suppressionKey = match.source + ":" + match.label;
        Long lastSameKeyTime = lastAlertTimestampByKey.get(suppressionKey);
        if (lastSameKeyTime != null && (nowMs - lastSameKeyTime) < cooldownMs) {
            return null; // Duplicate suppression within cooldown window
        }
        // Short minimum global gap (1.5s) to prevent burst spam across adjacent categories
        if (lastGlobalAlertTimestampMs > 0L && (nowMs - lastGlobalAlertTimestampMs) < Math.min(1500L, cooldownMs / 2)) {
            return null;
        }

        lastAlertTimestampByKey.put(suppressionKey, nowMs);
        lastGlobalAlertTimestampMs = nowMs;

        String stableId = AlertEvent.generateStableId(match.category, nowMs, deviceId);
        String recordedOrigPred = (originalMlPrediction != null && !originalMlPrediction.trim().isEmpty())
                ? originalMlPrediction.trim()
                : match.rawModelLabel;
        float recordedOrigConf = originalMlConfidence > 0f ? originalMlConfidence : match.confidence;

        return new AlertEvent(
                stableId,
                match.label,
                match.rawModelLabel,
                match.category,
                match.source,
                match.confidence,
                recordedOrigPred,
                recordedOrigConf,
                match.appliedCalibrationId,
                nowMs,
                deviceId,
                false,
                AlertEvent.DELIVERY_LOCAL_ONLY,
                AlertEvent.SYNC_PENDING,
                null,
                false
        );
    }

    public synchronized void reset() {
        smoothedCategoryScores.clear();
        lastAlertTimestampByKey.clear();
        lastGlobalAlertTimestampMs = 0L;
        lastCalibrationLabelKey = null;
        lastCalibrationSimilarity = 0f;
    }
}
