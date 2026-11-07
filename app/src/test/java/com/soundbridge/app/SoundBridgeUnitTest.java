package com.soundbridge.app;

import com.soundbridge.app.audio.AudioFeatureExtractor;
import com.soundbridge.app.audio.SoundClassifierEngine;
import com.soundbridge.app.audio.SoundDetectionGate;
import com.soundbridge.app.firebase.AdminClaimsParser;
import com.soundbridge.app.firebase.CalibrationFirestoreCodec;
import com.soundbridge.app.model.AlertEvent;
import com.soundbridge.app.model.CalibrationAuditRecord;
import com.soundbridge.app.model.CustomSoundProfile;
import com.soundbridge.app.model.GlobalCalibrationProfile;
import com.soundbridge.app.util.HttpDemoClient;
import com.soundbridge.app.util.WavAudioUtil;

import org.json.JSONException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Automated JUnit 4 unit tests covering core SoundBridge logic:
 * 1) AudioSet/YAMNet label mapping and non-target rejection
 * 2) Consecutive-window smoothing, confidence threshold, cooldown, and duplicate suppression
 * 3) Competing match resolution (Built-in vs. Custom Sound vs. Life-Safety Alarm)
 * 4) 64-D acoustic embedding extraction and cosine similarity
 * 5) CustomSoundProfile multi-sample similarity & centroid descriptor calculation
 * 6) Deterministic AlertEvent ID deduplication and Bluetooth RFCOMM JSON serialization/validation
 * 7) JSON (org.json) and XML (XmlPullParser) payload parsing
 */
public class SoundBridgeUnitTest {

    @Test
    public void testAudioSetLabelMappingAndUnknownRejection() {
        SoundDetectionGate.classMappedCategory doorbell =
                SoundDetectionGate.mapAudioSetLabelToCategory("Ding-dong");
        assertNotNull(doorbell);
        assertEquals(AlertEvent.CATEGORY_DOORBELL, doorbell.category);

        SoundDetectionGate.classMappedCategory knock =
                SoundDetectionGate.mapAudioSetLabelToCategory("Knock");
        assertNotNull(knock);
        assertEquals(AlertEvent.CATEGORY_KNOCK, knock.category);

        SoundDetectionGate.classMappedCategory alarm =
                SoundDetectionGate.mapAudioSetLabelToCategory("Smoke detector, smoke alarm");
        assertNotNull(alarm);
        assertEquals(AlertEvent.CATEGORY_ALARM, alarm.category);

        SoundDetectionGate.classMappedCategory beep =
                SoundDetectionGate.mapAudioSetLabelToCategory("Beep, bleep");
        assertNotNull(beep);
        assertEquals(AlertEvent.CATEGORY_APPLIANCE_BEEP, beep.category);

        // Non-target ambient classes must return null ("Unknown sound" / no alert)
        assertNull(SoundDetectionGate.mapAudioSetLabelToCategory("Speech"));
        assertNull(SoundDetectionGate.mapAudioSetLabelToCategory("Music"));
        assertNull(SoundDetectionGate.mapAudioSetLabelToCategory("Silence"));
    }

    @Test
    public void testSmoothingThresholdCooldownAndDuplicateSuppression() {
        SoundDetectionGate gate = new SoundDetectionGate();

        // First window is dampened (0.80 * 0.70 = 0.56)
        float w1 = gate.updateSmoothedScore(AlertEvent.CATEGORY_DOORBELL, 0.70f);
        assertEquals(0.56f, w1, 0.01f);

        // Second sustained window rises (0.65 * 0.80 + 0.35 * 0.56 = 0.716)
        float w2 = gate.updateSmoothedScore(AlertEvent.CATEGORY_DOORBELL, 0.80f);
        assertTrue(w2 > w1);

        SoundDetectionGate.CandidateMatch match = new SoundDetectionGate.CandidateMatch(
                "Doorbell (Ding-dong)",
                "Ding-dong",
                AlertEvent.CATEGORY_DOORBELL,
                AlertEvent.SOURCE_BUILT_IN,
                w2
        );

        long t0 = 1_700_000_000_000L;
        long cooldownMs = 4000L;

        // First trigger succeeds
        AlertEvent firstAlert = gate.evaluateAndGateAlert(match, t0, cooldownMs, "dev_test");
        assertNotNull(firstAlert);
        assertEquals(AlertEvent.CATEGORY_DOORBELL, firstAlert.getCategory());

        // Duplicate trigger 1.2s later within 4s cooldown must be suppressed
        AlertEvent suppressed = gate.evaluateAndGateAlert(match, t0 + 1200L, cooldownMs, "dev_test");
        assertNull(suppressed);

        // Trigger after cooldown window (t0 + 4500ms) succeeds
        AlertEvent afterCooldown = gate.evaluateAndGateAlert(match, t0 + 4500L, cooldownMs, "dev_test");
        assertNotNull(afterCooldown);
    }

    @Test
    public void testCompetingMatchResolutionRules() {
        SoundDetectionGate.CandidateMatch genericBeep = new SoundDetectionGate.CandidateMatch(
                "Appliance Beep (Beep, bleep)",
                "Beep, bleep",
                AlertEvent.CATEGORY_APPLIANCE_BEEP,
                AlertEvent.SOURCE_BUILT_IN,
                0.72f
        );
        SoundDetectionGate.CandidateMatch customMicrowave = new SoundDetectionGate.CandidateMatch(
                "My Microwave Beep",
                "Custom Example Similarity (2 samples)",
                AlertEvent.CATEGORY_CUSTOM,
                AlertEvent.SOURCE_CUSTOM,
                0.88f
        );

        // Rule: User's specific custom sound overrides generic built-in appliance beep
        SoundDetectionGate.CandidateMatch winner1 =
                SoundDetectionGate.resolveCompetingMatches(genericBeep, customMicrowave, 0.45f);
        assertNotNull(winner1);
        assertEquals(AlertEvent.SOURCE_CUSTOM, winner1.source);
        assertEquals("My Microwave Beep", winner1.label);

        // Rule: High-confidence life-safety Smoke/Fire Alarm (>= 0.78) takes priority over moderate custom match (< 0.92)
        SoundDetectionGate.CandidateMatch urgentAlarm = new SoundDetectionGate.CandidateMatch(
                "Alarm (Smoke detector, smoke alarm)",
                "Smoke detector, smoke alarm",
                AlertEvent.CATEGORY_ALARM,
                AlertEvent.SOURCE_BUILT_IN,
                0.86f
        );
        SoundDetectionGate.CandidateMatch winner2 =
                SoundDetectionGate.resolveCompetingMatches(urgentAlarm, customMicrowave, 0.45f);
        assertNotNull(winner2);
        assertEquals(AlertEvent.CATEGORY_ALARM, winner2.category);
    }

    @Test
    public void testEmbeddingExtractionAndCustomSoundCosineSimilarity() {
        float[] doorbellWave1 = SoundClassifierEngine.generateTestSoundWaveform(AlertEvent.CATEGORY_DOORBELL);
        float[] doorbellWave2 = SoundClassifierEngine.generateTestSoundWaveform(AlertEvent.CATEGORY_DOORBELL);
        float[] knockWave = SoundClassifierEngine.generateTestSoundWaveform(AlertEvent.CATEGORY_KNOCK);

        float[] embDoorbell1 = AudioFeatureExtractor.extractEmbedding64D(doorbellWave1);
        float[] embDoorbell2 = AudioFeatureExtractor.extractEmbedding64D(doorbellWave2);
        float[] embKnock = AudioFeatureExtractor.extractEmbedding64D(knockWave);

        assertEquals(AudioFeatureExtractor.EMBEDDING_DIM, embDoorbell1.length);

        float sameSoundSim = AudioFeatureExtractor.cosineSimilarity(embDoorbell1, embDoorbell2);
        float diffSoundSim = AudioFeatureExtractor.cosineSimilarity(embDoorbell1, embKnock);

        assertTrue("Identical acoustic waveforms must have cosine similarity > 0.99", sameSoundSim > 0.99f);
        assertTrue("Same-class similarity must exceed different-class similarity", sameSoundSim > diffSoundSim);

        // Enroll custom sound profile with 2 examples and verify matching
        CustomSoundProfile customProfile = new CustomSoundProfile(
                "cust_test1",
                "Apartment Intercom Chime",
                1700000000000L,
                true,
                0.82f,
                CustomSoundProfile.CURRENT_MODEL_VERSION,
                new ArrayList<>(),
                new ArrayList<>()
        );
        customProfile.addExample(embDoorbell1, "/data/user/0/com.soundbridge.app/files/sample1.wav");
        customProfile.addExample(embDoorbell2, "/data/user/0/com.soundbridge.app/files/sample2.wav");

        List<CustomSoundProfile> profiles = Collections.singletonList(customProfile);
        SoundDetectionGate.CandidateMatch matched =
                SoundDetectionGate.findBestCustomSoundMatch(embDoorbell1, profiles);
        assertNotNull(matched);
        assertEquals("Apartment Intercom Chime", matched.label);
        assertEquals(AlertEvent.SOURCE_CUSTOM, matched.source);

        // Disabled custom profile must not match
        customProfile.setEnabled(false);
        assertNull(SoundDetectionGate.findBestCustomSoundMatch(embDoorbell1, profiles));
    }

    @Test
    public void testDeterministicIdAndBluetoothJsonSerialization() throws JSONException {
        long ts = 1_700_123_456_789L;
        String id1 = AlertEvent.generateStableId(AlertEvent.CATEGORY_ALARM, ts, "pixel_6");
        String id2 = AlertEvent.generateStableId(AlertEvent.CATEGORY_ALARM, ts, "pixel_6");
        assertEquals("Deterministic IDs must match for offline deduplication", id1, id2);

        AlertEvent original = new AlertEvent(
                id1,
                "Alarm (Fire alarm)",
                "Fire alarm",
                AlertEvent.CATEGORY_ALARM,
                AlertEvent.SOURCE_BUILT_IN,
                0.93f,
                ts,
                "pixel_6",
                false,
                AlertEvent.DELIVERY_LOCAL_ONLY,
                AlertEvent.SYNC_PENDING
        );

        String jsonPayload = original.toBluetoothPayloadJson();
        AlertEvent deserialized = AlertEvent.fromBluetoothPayloadJson(jsonPayload);

        assertEquals(original.getId(), deserialized.getId());
        assertEquals(original.getLabel(), deserialized.getLabel());
        assertEquals(original.getCategory(), deserialized.getCategory());
        assertEquals(original.getConfidence(), deserialized.getConfidence(), 0.005f);
        assertEquals(original.getTimestampMs(), deserialized.getTimestampMs());
        assertEquals(AlertEvent.DELIVERY_COMPANION_RECEIVED, deserialized.getDeliveryStatus());
    }

    @Test(expected = JSONException.class)
    public void testMalformedBluetoothJsonRejected() throws JSONException {
        AlertEvent.fromBluetoothPayloadJson("{\"schemaVersion\":1,\"eventId\":\"\",\"label\":\"\"}");
    }

    @Test
    public void testHttpDemoJsonAndXmlPullParser() throws Exception {
        String sampleJson = "{\"id\":1,\"title\":\"YAMNet AudioSet Model\",\"completed\":true}";
        String jsonSummary = HttpDemoClient.parseJsonSummary(sampleJson);
        assertTrue(jsonSummary.contains("JSONObject"));
        assertTrue(jsonSummary.contains("YAMNet AudioSet Model"));

        String sampleXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<note><to>SoundBridge User</to><from>Companion Phone</from><body>Doorbell detected</body></note>";
        String xmlSummary = HttpDemoClient.parseXmlWithPullParser(sampleXml);
        assertTrue(xmlSummary.contains("<note>"));
        assertTrue(xmlSummary.contains("SoundBridge User"));
        assertTrue(xmlSummary.contains("Doorbell detected"));
    }

    @Test
    public void testWavResamplingUtility() {
        float[] src32k = new float[3200];
        for (int i = 0; i < src32k.length; i++) {
            src32k[i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 32000.0);
        }
        float[] resampled16k = WavAudioUtil.resampleLinear(src32k, 32000, 16000);
        assertEquals(1600, resampled16k.length);
        short[] pcm16 = WavAudioUtil.floatsToPcm16(resampled16k);
        assertEquals(1600, pcm16.length);
        assertFalse(pcm16[10] == 0 && pcm16[11] == 0);
    }

    @Test
    public void testReadableAlertDisplayHelpers() {
        AlertEvent builtIn = new AlertEvent(
                null,
                "Doorbell (Ding-dong)",
                "Ding-dong",
                AlertEvent.CATEGORY_DOORBELL,
                AlertEvent.SOURCE_BUILT_IN,
                0.91f,
                1_700_000_000_000L,
                "pixel_7",
                false,
                AlertEvent.DELIVERY_LOCAL_ONLY,
                AlertEvent.SYNC_SYNCED
        );
        assertEquals("Doorbell", builtIn.getDisplayTitle());
        assertEquals("Doorbell", builtIn.getReadableCategory());
        assertEquals("Built-in Detection", builtIn.getReadableSource());
        assertEquals("This phone", builtIn.getReadableDeliveryStatus());
        assertEquals("Backed up to cloud", builtIn.getReadableSyncStatus());

        AlertEvent custom = new AlertEvent(
                null,
                "Kitchen Timer (2 beeps)",
                "Custom Match",
                AlertEvent.CATEGORY_CUSTOM,
                AlertEvent.SOURCE_CUSTOM,
                0.88f,
                1_700_000_005_000L,
                "pixel_7",
                true,
                AlertEvent.DELIVERY_BT_DELIVERED,
                AlertEvent.SYNC_PENDING
        );
        assertEquals("Kitchen Timer (2 beeps)", custom.getDisplayTitle());
        assertEquals("Custom Sound", custom.getReadableCategory());
        assertEquals("Custom Sound", custom.getReadableSource());
        assertEquals("Sent to companion phone", custom.getReadableDeliveryStatus());
    }

    // =========================================================================
    // Acceptance Criteria 1: Admin Correction & Multi-Window Feature Extraction
    // =========================================================================
    @Test
    public void testAdminCorrectionAndMultiWindowFeatureExtraction() {
        // 1. Synthesize 2.0s audio clip (32,000 samples @ 16 kHz) of an appliance chime
        float[] clip2s = new float[AudioFeatureExtractor.ADMIN_CLIP_SAMPLES];
        for (int i = 0; i < clip2s.length; i++) {
            clip2s[i] = 0.5f * (float) Math.sin(2.0 * Math.PI * 1800.0 * i / AudioFeatureExtractor.SAMPLE_RATE);
        }

        // 2. Extract multi-window 64-D acoustic embeddings from clip
        List<float[]> embeddings = AudioFeatureExtractor.extractCalibrationEmbeddingsFromClip(clip2s);
        assertFalse("Should extract reference embeddings from audio clip", embeddings.isEmpty());
        for (float[] vec : embeddings) {
            assertEquals(64, vec.length);
            // Verify L2-normalized
            float norm = 0f;
            for (float val : vec) norm += val * val;
            assertEquals(1.0f, (float) Math.sqrt(norm), 0.05f);
        }

        // 3. Create GlobalCalibrationProfile correcting "Appliance Beep" to canonical "Doorbell"
        long now = System.currentTimeMillis();
        GlobalCalibrationProfile profile = new GlobalCalibrationProfile(
                "cal_test_001",
                "Doorbell",
                AlertEvent.CATEGORY_DOORBELL,
                "Appliance Beep",
                embeddings,
                GlobalCalibrationProfile.EMBEDDING_MODEL_ID,
                GlobalCalibrationProfile.CURRENT_EMBEDDING_VERSION,
                AudioFeatureExtractor.SAMPLE_RATE,
                AudioFeatureExtractor.WINDOW_SAMPLES,
                0.76f,
                now,
                now,
                "admin_uid_42",
                "Admin Farjamul",
                1,
                true
        );

        assertEquals("Doorbell", profile.getCanonicalLabel());
        assertEquals("Appliance Beep", profile.getOriginalPrediction());
        assertTrue(profile.getExemplarCount() >= 1);
        assertEquals(64, profile.getCentroidDescriptor().length);
        assertTrue(profile.isActive());
    }

    // =========================================================================
    // Acceptance Criteria 2: Global Propagation & Matching on User Devices
    // =========================================================================
    @Test
    public void testGlobalPropagationAndMatchingOnUserDevices() {
        // Create 2.0s clip and calibration profile
        float[] clip2s = new float[AudioFeatureExtractor.ADMIN_CLIP_SAMPLES];
        for (int i = 0; i < clip2s.length; i++) {
            clip2s[i] = 0.6f * (float) Math.sin(2.0 * Math.PI * 2200.0 * i / AudioFeatureExtractor.SAMPLE_RATE);
        }
        List<float[]> embeddings = AudioFeatureExtractor.extractCalibrationEmbeddingsFromClip(clip2s);
        GlobalCalibrationProfile profile = new GlobalCalibrationProfile(
                "cal_doorbell_99",
                "Doorbell",
                AlertEvent.CATEGORY_DOORBELL,
                "Appliance Beep",
                embeddings,
                GlobalCalibrationProfile.EMBEDDING_MODEL_ID,
                GlobalCalibrationProfile.CURRENT_EMBEDDING_VERSION,
                16000,
                15600,
                0.75f,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "admin_1",
                "Admin",
                1,
                true
        );

        // Incoming audio frame on another user's device matches the same acoustic profile
        float[] incomingFrame = new float[AudioFeatureExtractor.WINDOW_SAMPLES];
        System.arraycopy(clip2s, 0, incomingFrame, 0, incomingFrame.length);
        float[] incomingEmbedding = AudioFeatureExtractor.extractEmbedding64D(incomingFrame);

        SoundDetectionGate gate = new SoundDetectionGate();
        SoundDetectionGate.CandidateMatch match = gate.findBestGlobalCalibrationMatch(
                incomingEmbedding,
                Collections.singletonList(profile)
        );

        assertNotNull("Matching calibration profile should trigger candidate", match);
        assertEquals("Doorbell", match.label);
        assertEquals(AlertEvent.CATEGORY_DOORBELL, match.category);
        assertEquals(AlertEvent.SOURCE_GLOBAL_CALIBRATED, match.source);
        assertEquals("cal_doorbell_99", match.appliedCalibrationId);
        assertTrue(match.rawModelLabel.contains("Appliance Beep"));
        assertTrue(match.confidence >= 0.75f);

        // Verify alert evaluation retains calibration metadata
        AlertEvent alert = gate.evaluateAndGateAlert(match, System.currentTimeMillis(), 4000L, "pixel_user_dev");
        assertNotNull(alert);
        assertEquals(AlertEvent.SOURCE_GLOBAL_CALIBRATED, alert.getSource());
        assertEquals("cal_doorbell_99", alert.getAppliedCalibrationId());
        assertEquals("Appliance Beep", alert.getOriginalPrediction());
    }

    // =========================================================================
    // Acceptance Criteria 3: Acoustically Unrelated Sound Non-Override
    // =========================================================================
    @Test
    public void testAcousticallyUnrelatedSoundNonOverride() {
        // Calibration profile for high-pitch doorbell chime (3000 Hz)
        float[] chimeClip = new float[AudioFeatureExtractor.ADMIN_CLIP_SAMPLES];
        for (int i = 0; i < chimeClip.length; i++) {
            chimeClip[i] = 0.5f * (float) Math.sin(2.0 * Math.PI * 3000.0 * i / AudioFeatureExtractor.SAMPLE_RATE);
        }
        List<float[]> embeddings = AudioFeatureExtractor.extractCalibrationEmbeddingsFromClip(chimeClip);
        GlobalCalibrationProfile doorbellProfile = new GlobalCalibrationProfile(
                "cal_high_chime",
                "Doorbell",
                AlertEvent.CATEGORY_DOORBELL,
                "Appliance Beep",
                embeddings,
                GlobalCalibrationProfile.EMBEDDING_MODEL_ID,
                GlobalCalibrationProfile.CURRENT_EMBEDDING_VERSION,
                16000,
                15600,
                0.80f,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "admin_1",
                "Admin",
                1,
                true
        );

        // Incoming audio frame is a low-frequency heavy knock (120 Hz)
        float[] knockFrame = new float[AudioFeatureExtractor.WINDOW_SAMPLES];
        for (int i = 0; i < knockFrame.length; i++) {
            knockFrame[i] = 0.7f * (float) Math.sin(2.0 * Math.PI * 120.0 * i / AudioFeatureExtractor.SAMPLE_RATE);
        }
        float[] knockEmbedding = AudioFeatureExtractor.extractEmbedding64D(knockFrame);

        SoundDetectionGate gate = new SoundDetectionGate();
        SoundDetectionGate.CandidateMatch match = gate.findBestGlobalCalibrationMatch(
                knockEmbedding,
                Collections.singletonList(doorbellProfile)
        );

        // Unrelated knock must NOT trigger the doorbell calibration profile!
        assertNull("Acoustically dissimilar sound must not override detection", match);
    }

    // =========================================================================
    // Acceptance Criteria 4: Offline Cached Matching Without Network Calls
    // =========================================================================
    @Test
    public void testOfflineCachedMatchingWithoutNetwork() throws JSONException {
        // Simulate cached SQLite data
        float[] sampleVec = new float[AudioFeatureExtractor.EMBEDDING_DIM];
        sampleVec[0] = 0.6f;
        sampleVec[1] = 0.8f;
        List<float[]> list = Collections.singletonList(sampleVec);

        GlobalCalibrationProfile cachedProfile = new GlobalCalibrationProfile(
                "cal_offline_1",
                "Front Door Knock",
                AlertEvent.CATEGORY_KNOCK,
                "Generic Sound",
                list,
                GlobalCalibrationProfile.EMBEDDING_MODEL_ID,
                GlobalCalibrationProfile.CURRENT_EMBEDDING_VERSION,
                16000,
                15600,
                0.70f,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "admin_offline",
                "Admin Offline",
                1,
                true
        );

        // Serialize and deserialize to ensure 100% offline persistence fidelity
        String jsonEmbeddings = cachedProfile.serializeEmbeddingsJson();
        assertNotNull(jsonEmbeddings);
        List<float[]> restoredList = CustomSoundProfile.deserializeEmbeddingsJson(jsonEmbeddings);
        assertEquals(1, restoredList.size());
        assertEquals(64, restoredList.get(0).length);

        // Cosine match runs purely on local CPU
        float similarity = cachedProfile.computeMaxSimilarity(sampleVec);
        assertEquals(1.0f, similarity, 0.001f);
    }

    // =========================================================================
    // Acceptance Criteria 5: Admin Authorization & Custom Claims Enforcement
    // =========================================================================
    @Test
    public void testAdminAuthorizationAndCustomClaims() throws JSONException {
        // Positive admin custom claims via JWT payload
        assertTrue(AdminClaimsParser.hasAdminCustomClaim(AdminClaimsParser.buildTestJwt("{\"admin\": true}")));
        assertTrue(AdminClaimsParser.hasAdminCustomClaim(AdminClaimsParser.buildTestJwt("{\"role\": \"admin\"}")));
        assertTrue(AdminClaimsParser.hasAdminCustomClaim(AdminClaimsParser.buildTestJwt("{\"admin\": 1}")));

        // Negative non-admin claims
        assertFalse(AdminClaimsParser.hasAdminCustomClaim(AdminClaimsParser.buildTestJwt("{\"admin\": false}")));
        assertFalse(AdminClaimsParser.hasAdminCustomClaim(AdminClaimsParser.buildTestJwt("{\"role\": \"user\"}")));
        assertFalse(AdminClaimsParser.hasAdminCustomClaim(AdminClaimsParser.buildTestJwt("{}")));
        assertFalse(AdminClaimsParser.hasAdminCustomClaim(null));
        assertFalse(AdminClaimsParser.hasAdminCustomClaim("malformed.jwt"));

        // Admin document JSON parsing (adminUsers/{uid} document)
        org.json.JSONObject validDoc = new org.json.JSONObject()
                .put("role", new org.json.JSONObject().put("stringValue", "admin"))
                .put("active", new org.json.JSONObject().put("booleanValue", true));
        assertTrue(AdminClaimsParser.isAdminUserDocument(validDoc));

        org.json.JSONObject inactiveDoc = new org.json.JSONObject()
                .put("role", new org.json.JSONObject().put("stringValue", "admin"))
                .put("active", new org.json.JSONObject().put("booleanValue", false));
        assertFalse(AdminClaimsParser.isAdminUserDocument(inactiveDoc));
    }

    // =========================================================================
    // Acceptance Criteria 6: Deactivation & Reactivation Lifecycle
    // =========================================================================
    @Test
    public void testDeactivationAndReactivationLifecycle() {
        float[] sampleVec = new float[AudioFeatureExtractor.EMBEDDING_DIM];
        sampleVec[10] = 1.0f;
        GlobalCalibrationProfile profile = new GlobalCalibrationProfile(
                "cal_lifecycle",
                "Microwave Beep",
                AlertEvent.CATEGORY_APPLIANCE_BEEP,
                "Unknown Beep",
                Collections.singletonList(sampleVec),
                GlobalCalibrationProfile.EMBEDDING_MODEL_ID,
                GlobalCalibrationProfile.CURRENT_EMBEDDING_VERSION,
                16000,
                15600,
                0.70f,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "admin_1",
                "Admin",
                1,
                true
        );

        SoundDetectionGate gate = new SoundDetectionGate();

        // 1. Active: match triggers
        SoundDetectionGate.CandidateMatch activeMatch = gate.findBestGlobalCalibrationMatch(
                sampleVec,
                Collections.singletonList(profile)
        );
        assertNotNull(activeMatch);

        // 2. Deactivated: match must be rejected
        profile.setActive(false);
        SoundDetectionGate.CandidateMatch inactiveMatch = gate.findBestGlobalCalibrationMatch(
                sampleVec,
                Collections.singletonList(profile)
        );
        assertNull("Deactivated profile must not produce matches", inactiveMatch);

        // 3. Reactivated: match resumes
        profile.setActive(true);
        SoundDetectionGate.CandidateMatch reactivatedMatch = gate.findBestGlobalCalibrationMatch(
                sampleVec,
                Collections.singletonList(profile)
        );
        assertNotNull("Reactivated profile should produce matches", reactivatedMatch);
    }

    // =========================================================================
    // Acceptance Criteria 7: Strict Privacy - Zero Raw Audio in Sync Payloads
    // =========================================================================
    @Test
    public void testStrictPrivacyNoRawAudioInSyncPayloads() throws JSONException {
        float[] sampleVec = new float[AudioFeatureExtractor.EMBEDDING_DIM];
        sampleVec[5] = 1.0f;
        GlobalCalibrationProfile profile = new GlobalCalibrationProfile(
                "cal_privacy_check",
                "Doorbell",
                AlertEvent.CATEGORY_DOORBELL,
                "Appliance Beep",
                Collections.singletonList(sampleVec),
                GlobalCalibrationProfile.EMBEDDING_MODEL_ID,
                GlobalCalibrationProfile.CURRENT_EMBEDDING_VERSION,
                16000,
                15600,
                0.75f,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "admin_sec",
                "Admin Privacy",
                1,
                true
        );

        org.json.JSONObject fields = CalibrationFirestoreCodec.profileToFields(profile);
        String commitBody = fields.toString();

        // Verify strictly that no audio paths, filenames, or raw audio data are contained
        assertFalse("Commit payload must not contain .wav extensions", commitBody.contains(".wav"));
        assertFalse("Commit payload must not contain /admin_recordings", commitBody.contains("admin_recordings"));
        assertFalse("Commit payload must not contain local_clip_path", commitBody.contains("local_clip_path"));
        assertFalse("Commit payload must not contain raw short/byte arrays", commitBody.contains("pcmSamples"));

        // Verify mathematical embeddings are present
        assertTrue("Commit payload must contain embedding dimensions", commitBody.contains("embeddingsJson"));
        assertTrue("Commit payload must contain canonicalLabel", commitBody.contains("canonicalLabel"));
    }

    // =========================================================================
    // Acceptance Criteria 8: Preservation of Existing Features & Three-Tier Priority
    // =========================================================================
    @Test
    public void testPreservationOfExistingFeaturesAndThreeTierPriority() {
        // Built-in candidate: Appliance Beep
        SoundDetectionGate.CandidateMatch builtInMatch = new SoundDetectionGate.CandidateMatch(
                "Appliance Beep (Beep, bleep)",
                "Beep, bleep",
                AlertEvent.CATEGORY_APPLIANCE_BEEP,
                AlertEvent.SOURCE_BUILT_IN,
                0.85f
        );

        // Custom candidate: Kitchen Timer
        SoundDetectionGate.CandidateMatch customMatch = new SoundDetectionGate.CandidateMatch(
                "Kitchen Timer",
                "Kitchen Timer",
                AlertEvent.CATEGORY_CUSTOM,
                AlertEvent.SOURCE_CUSTOM,
                0.86f
        );

        // Calibrated candidate: Doorbell (calibrated from Appliance Beep)
        SoundDetectionGate.CandidateMatch calibratedMatch = new SoundDetectionGate.CandidateMatch(
                "Doorbell",
                "Appliance Beep",
                AlertEvent.CATEGORY_DOORBELL,
                AlertEvent.SOURCE_GLOBAL_CALIBRATED,
                0.88f,
                "cal_doorbell_1"
        );

        // Global calibrated match must take highest recognition priority over built-in
        SoundDetectionGate.CandidateMatch winner = SoundDetectionGate.resolveCompetingMatches(
                calibratedMatch,
                customMatch,
                builtInMatch,
                0.50f
        );
        assertNotNull(winner);
        assertEquals(AlertEvent.SOURCE_GLOBAL_CALIBRATED, winner.source);
        assertEquals("Doorbell", winner.label);

        // Life safety alarm candidate (Smoke Alarm) maintains immediate priority
        SoundDetectionGate.CandidateMatch smokeAlarm = new SoundDetectionGate.CandidateMatch(
                "Smoke detector, smoke alarm",
                "Smoke detector",
                AlertEvent.CATEGORY_ALARM,
                AlertEvent.SOURCE_BUILT_IN,
                0.90f
        );
        SoundDetectionGate.CandidateMatch alarmWinner = SoundDetectionGate.resolveCompetingMatches(
                calibratedMatch,
                customMatch,
                smokeAlarm,
                0.50f
        );
        assertEquals(AlertEvent.CATEGORY_ALARM, alarmWinner.category);
    }
}


