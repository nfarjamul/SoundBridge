package com.soundbridge.app.model;

import com.soundbridge.app.audio.AudioFeatureExtractor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Globally synchronized acoustic calibration profile published by an authorized administrator.
 *
 * PRIVACY GUARANTEE:
 * Contains ONLY compact numerical 64-D acoustic reference embeddings and calibration metadata.
 * Raw audio recordings and device-local file paths are NEVER stored in or serialized by this class.
 */
public class GlobalCalibrationProfile {
    public static final String EMBEDDING_MODEL_ID = "SoundBridge-YAMNet-Spectral64D";
    public static final String CURRENT_EMBEDDING_VERSION = CustomSoundProfile.CURRENT_MODEL_VERSION;
    public static final float DEFAULT_SIMILARITY_THRESHOLD = 0.88f;
    public static final float MIN_SIMILARITY_THRESHOLD = 0.78f;
    public static final float MAX_SIMILARITY_THRESHOLD = 0.97f;
    public static final int MAX_EXAMPLES_PER_PROFILE = 16;

    private final String calibrationId;
    private String canonicalLabel;
    private String category;
    private String originalModelPrediction;
    private final List<float[]> exampleEmbeddings;
    private final String embeddingModelId;
    private final String embeddingModelVersion;
    private final int sampleRateHz;
    private final int windowSamples;
    private float similarityThreshold;
    private final long createdAtMs;
    private long updatedAtMs;
    private String adminUid;
    private String adminDisplayName;
    private int profileVersion;
    private boolean active;
    private boolean deleted;

    public GlobalCalibrationProfile(
            String calibrationId,
            String canonicalLabel,
            String category,
            String originalModelPrediction,
            List<float[]> exampleEmbeddings,
            String embeddingModelId,
            String embeddingModelVersion,
            int sampleRateHz,
            int windowSamples,
            float similarityThreshold,
            long createdAtMs,
            long updatedAtMs,
            String adminUid,
            String adminDisplayName,
            int profileVersion,
            boolean active
    ) {
        this(
                calibrationId,
                canonicalLabel,
                category,
                originalModelPrediction,
                exampleEmbeddings,
                embeddingModelId,
                embeddingModelVersion,
                sampleRateHz,
                windowSamples,
                similarityThreshold,
                createdAtMs,
                updatedAtMs,
                adminUid,
                adminDisplayName,
                profileVersion,
                active,
                false
        );
    }

    public GlobalCalibrationProfile(
            String calibrationId,
            String canonicalLabel,
            String category,
            String originalModelPrediction,
            List<float[]> exampleEmbeddings,
            String embeddingModelId,
            String embeddingModelVersion,
            int sampleRateHz,
            int windowSamples,
            float similarityThreshold,
            long createdAtMs,
            long updatedAtMs,
            String adminUid,
            String adminDisplayName,
            int profileVersion,
            boolean active,
            boolean deleted
    ) {
        String cleanLabel = (canonicalLabel != null && !canonicalLabel.trim().isEmpty())
                ? canonicalLabel.trim()
                : "Calibrated Sound";
        this.calibrationId = (calibrationId == null || calibrationId.trim().isEmpty())
                ? generateCalibrationId(cleanLabel)
                : calibrationId.trim();
        this.canonicalLabel = cleanLabel;
        this.category = normalizeCategoryCode(category, cleanLabel);
        this.originalModelPrediction = (originalModelPrediction != null && !originalModelPrediction.trim().isEmpty())
                ? originalModelPrediction.trim()
                : "Unknown";
        this.exampleEmbeddings = new ArrayList<>();
        if (exampleEmbeddings != null) {
            for (float[] emb : exampleEmbeddings) {
                if (emb != null && emb.length == AudioFeatureExtractor.EMBEDDING_DIM) {
                    this.exampleEmbeddings.add(AudioFeatureExtractor.l2Normalize(emb));
                }
            }
        }
        this.embeddingModelId = (embeddingModelId != null && !embeddingModelId.trim().isEmpty())
                ? embeddingModelId.trim()
                : EMBEDDING_MODEL_ID;
        this.embeddingModelVersion = (embeddingModelVersion != null && !embeddingModelVersion.trim().isEmpty())
                ? embeddingModelVersion.trim()
                : CURRENT_EMBEDDING_VERSION;
        this.sampleRateHz = sampleRateHz > 0 ? sampleRateHz : AudioFeatureExtractor.SAMPLE_RATE;
        this.windowSamples = windowSamples > 0 ? windowSamples : AudioFeatureExtractor.WINDOW_SAMPLES;
        this.similarityThreshold = clampThreshold(similarityThreshold > 0f ? similarityThreshold : DEFAULT_SIMILARITY_THRESHOLD);
        this.createdAtMs = createdAtMs > 0L ? createdAtMs : System.currentTimeMillis();
        this.updatedAtMs = updatedAtMs > 0L ? updatedAtMs : this.createdAtMs;
        this.adminUid = adminUid != null ? adminUid.trim() : "";
        this.adminDisplayName = adminDisplayName != null ? adminDisplayName.trim() : "Admin";
        this.profileVersion = Math.max(1, profileVersion);
        this.active = active && !deleted;
        this.deleted = deleted;
    }

    public static String generateCalibrationId(String canonicalLabel) {
        String slug = canonicalLabel != null
                ? canonicalLabel.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "")
                : "sound";
        if (slug.isEmpty()) {
            slug = "sound";
        }
        if (slug.length() > 18) {
            slug = slug.substring(0, 18);
        }
        return "cal_" + slug + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String normalizeCategoryCode(String rawCategory, String fallbackLabel) {
        String src = (rawCategory != null && !rawCategory.trim().isEmpty()) ? rawCategory.trim() : fallbackLabel;
        if (src == null || src.trim().isEmpty()) {
            return AlertEvent.CATEGORY_CUSTOM;
        }
        String upper = src.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        if (upper.contains("DOORBELL") || upper.contains("CHIME") || upper.contains("DING")) {
            if (upper.equals(AlertEvent.CATEGORY_DOORBELL)) return AlertEvent.CATEGORY_DOORBELL;
        }
        return upper.isEmpty() ? AlertEvent.CATEGORY_CUSTOM : upper;
    }

    public static String inferCanonicalCategoryFromLabel(String canonicalLabel) {
        if (canonicalLabel == null || canonicalLabel.trim().isEmpty()) {
            return AlertEvent.CATEGORY_CUSTOM;
        }
        String lower = canonicalLabel.trim().toLowerCase(Locale.US);
        if (lower.contains("doorbell") || lower.contains("intercom") || lower.contains("entry chime")) {
            return AlertEvent.CATEGORY_DOORBELL;
        }
        if (lower.contains("knock") || lower.contains("door tap")) {
            return AlertEvent.CATEGORY_KNOCK;
        }
        if (lower.contains("smoke") || lower.contains("fire") || lower.contains("siren") || lower.contains("alarm")) {
            return AlertEvent.CATEGORY_ALARM;
        }
        if (lower.contains("appliance") || lower.contains("microwave") || lower.contains("oven")
                || lower.contains("washer") || lower.contains("dryer") || lower.contains("timer") || lower.contains("beep")) {
            return AlertEvent.CATEGORY_APPLIANCE_BEEP;
        }
        return normalizeCategoryCode(canonicalLabel, canonicalLabel);
    }

    public static float clampThreshold(float threshold) {
        return Math.max(MIN_SIMILARITY_THRESHOLD, Math.min(MAX_SIMILARITY_THRESHOLD, threshold));
    }

    public String getCalibrationId() {
        return calibrationId;
    }

    public String getCanonicalLabel() {
        return canonicalLabel;
    }

    public void setCanonicalLabel(String canonicalLabel) {
        if (canonicalLabel != null && !canonicalLabel.trim().isEmpty()) {
            this.canonicalLabel = canonicalLabel.trim();
        }
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        if (category != null && !category.trim().isEmpty()) {
            this.category = normalizeCategoryCode(category, canonicalLabel);
        }
    }

    public String getOriginalModelPrediction() {
        return originalModelPrediction;
    }

    public String getOriginalPrediction() {
        return getOriginalModelPrediction();
    }

    public void setOriginalModelPrediction(String originalModelPrediction) {
        if (originalModelPrediction != null && !originalModelPrediction.trim().isEmpty()) {
            this.originalModelPrediction = originalModelPrediction.trim();
        }
    }

    public List<float[]> getExampleEmbeddings() {
        return exampleEmbeddings;
    }

    public int getExampleCount() {
        return exampleEmbeddings.size();
    }

    public int getExemplarCount() {
        return getExampleCount();
    }

    public String getEmbeddingModelId() {
        return embeddingModelId;
    }

    public String getEmbeddingModelVersion() {
        return embeddingModelVersion;
    }

    public int getSampleRateHz() {
        return sampleRateHz;
    }

    public int getWindowSamples() {
        return windowSamples;
    }

    public float getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(float similarityThreshold) {
        this.similarityThreshold = clampThreshold(similarityThreshold);
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public long getUpdatedAtMs() {
        return updatedAtMs;
    }

    public void setUpdatedAtMs(long updatedAtMs) {
        this.updatedAtMs = updatedAtMs;
    }

    public String getAdminUid() {
        return adminUid;
    }

    public void setAdminUid(String adminUid) {
        this.adminUid = adminUid != null ? adminUid.trim() : "";
    }

    public String getAdminDisplayName() {
        return adminDisplayName;
    }

    public void setAdminDisplayName(String adminDisplayName) {
        this.adminDisplayName = adminDisplayName != null ? adminDisplayName.trim() : "Admin";
    }

    public int getProfileVersion() {
        return profileVersion;
    }

    public void incrementVersion(long nowMs, String modifierAdminUid, String modifierDisplayName) {
        this.profileVersion = Math.max(1, this.profileVersion + 1);
        this.updatedAtMs = nowMs > 0L ? nowMs : System.currentTimeMillis();
        if (modifierAdminUid != null && !modifierAdminUid.trim().isEmpty()) {
            this.adminUid = modifierAdminUid.trim();
        }
        if (modifierDisplayName != null && !modifierDisplayName.trim().isEmpty()) {
            this.adminDisplayName = modifierDisplayName.trim();
        }
    }

    public boolean isActive() {
        return active && !deleted;
    }

    public void setActive(boolean active) {
        this.active = active && !deleted;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void markDeleted(long nowMs, String modifierAdminUid, String modifierDisplayName) {
        this.deleted = true;
        this.active = false;
        this.exampleEmbeddings.clear();
        incrementVersion(nowMs, modifierAdminUid, modifierDisplayName);
    }

    public boolean isCompatibleWithCurrentEngine() {
        return CURRENT_EMBEDDING_VERSION.equals(embeddingModelVersion)
                && sampleRateHz == AudioFeatureExtractor.SAMPLE_RATE
                && !exampleEmbeddings.isEmpty();
    }

    /**
     * Adds one or more reference embeddings extracted from an approved recording, deduplicating
     * near-identical windows (> 0.992 cosine similarity) and capping total stored vectors.
     */
    public void addReferenceEmbeddings(List<float[]> newEmbeddings) {
        if (newEmbeddings == null) return;
        for (float[] candidate : newEmbeddings) {
            addReferenceEmbedding(candidate);
        }
    }

    public void addReferenceEmbedding(float[] candidate) {
        if (candidate == null || candidate.length != AudioFeatureExtractor.EMBEDDING_DIM) {
            return;
        }
        float[] norm = AudioFeatureExtractor.l2Normalize(candidate);
        double energy = 0.0;
        for (float v : norm) {
            energy += (double) v * v;
        }
        if (energy < 0.5) {
            return; // Reject silent/zero vector
        }
        for (float[] existing : exampleEmbeddings) {
            if (AudioFeatureExtractor.cosineSimilarity(existing, norm) > 0.994f) {
                return; // Already represented
            }
        }
        if (exampleEmbeddings.size() >= MAX_EXAMPLES_PER_PROFILE) {
            exampleEmbeddings.remove(0);
        }
        exampleEmbeddings.add(norm);
    }

    public float[] getCentroidDescriptor() {
        return computeCentroidEmbedding();
    }

    /**
     * Computes the L2-normalized centroid across all reference embeddings in this profile.
     */
    public float[] computeCentroidEmbedding() {
        if (exampleEmbeddings.isEmpty()) {
            return new float[0];
        }
        float[] sum = new float[AudioFeatureExtractor.EMBEDDING_DIM];
        int count = 0;
        for (float[] vec : exampleEmbeddings) {
            if (vec != null && vec.length == AudioFeatureExtractor.EMBEDDING_DIM) {
                for (int i = 0; i < sum.length; i++) {
                    sum[i] += vec[i];
                }
                count++;
            }
        }
        if (count == 0) {
            return new float[0];
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] /= count;
        }
        return AudioFeatureExtractor.l2Normalize(sum);
    }

    public float computeMaxSimilarity(float[] liveEmbedding64D) {
        return evaluateSimilarity(liveEmbedding64D);
    }

    /**
     * Evaluates acoustic cosine similarity in [0.0, 1.0] against this profile's reference embeddings.
     * Returns 0f if inactive, deleted, or incompatible with the current model/preprocessing version.
     */
    public float evaluateSimilarity(float[] liveEmbedding64D) {
        if (!isActive() || !isCompatibleWithCurrentEngine()) {
            return 0f;
        }
        if (liveEmbedding64D == null || liveEmbedding64D.length != AudioFeatureExtractor.EMBEDDING_DIM) {
            return 0f;
        }

        float maxSim = 0f;
        int supportingCount = 0;
        for (float[] ref : exampleEmbeddings) {
            float sim = AudioFeatureExtractor.cosineSimilarity(liveEmbedding64D, ref);
            if (sim > maxSim) {
                maxSim = sim;
            }
            if (sim >= similarityThreshold - 0.03f) {
                supportingCount++;
            }
        }

        // Only grant a small multi-example reinforcement bonus when maxSim ALREADY satisfies threshold
        if (maxSim >= similarityThreshold && supportingCount >= 2) {
            maxSim = Math.min(0.99f, maxSim + 0.015f);
        }
        return Math.max(0f, maxSim);
    }

    public String serializeEmbeddingsJson() throws JSONException {
        JSONArray outer = new JSONArray();
        for (float[] vec : exampleEmbeddings) {
            JSONArray inner = new JSONArray();
            for (float v : vec) {
                inner.put((double) Math.round(v * 10000f) / 10000.0);
            }
            outer.put(inner);
        }
        return outer.toString();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("calibrationId", calibrationId);
        obj.put("canonicalLabel", canonicalLabel);
        obj.put("category", category);
        obj.put("originalModelPrediction", originalModelPrediction);
        obj.put("embeddings", new JSONArray(serializeEmbeddingsJson()));
        obj.put("embeddingModelId", embeddingModelId);
        obj.put("embeddingModelVersion", embeddingModelVersion);
        obj.put("sampleRateHz", sampleRateHz);
        obj.put("windowSamples", windowSamples);
        obj.put("similarityThreshold", (double) Math.round(similarityThreshold * 1000f) / 1000.0);
        obj.put("createdAtMs", createdAtMs);
        obj.put("updatedAtMs", updatedAtMs);
        obj.put("adminUid", adminUid);
        obj.put("adminDisplayName", adminDisplayName);
        obj.put("profileVersion", profileVersion);
        obj.put("active", active);
        obj.put("deleted", deleted);
        obj.put("exampleCount", exampleEmbeddings.size());
        return obj;
    }

    public static GlobalCalibrationProfile fromJson(JSONObject obj) throws JSONException {
        String id = obj.getString("calibrationId");
        String canonicalLabel = obj.getString("canonicalLabel");
        String category = obj.optString("category", inferCanonicalCategoryFromLabel(canonicalLabel));
        String origPred = obj.optString("originalModelPrediction", "Unknown");
        JSONArray embArr = obj.optJSONArray("embeddings");
        List<float[]> embeddings = CustomSoundProfile.deserializeEmbeddingsJson(
                embArr != null ? embArr.toString() : obj.optString("embeddingsJson", "[]")
        );
        String modelId = obj.optString("embeddingModelId", EMBEDDING_MODEL_ID);
        String modelVer = obj.optString("embeddingModelVersion", CURRENT_EMBEDDING_VERSION);
        int sr = obj.optInt("sampleRateHz", AudioFeatureExtractor.SAMPLE_RATE);
        int win = obj.optInt("windowSamples", AudioFeatureExtractor.WINDOW_SAMPLES);
        float threshold = (float) obj.optDouble("similarityThreshold", DEFAULT_SIMILARITY_THRESHOLD);
        long created = obj.optLong("createdAtMs", System.currentTimeMillis());
        long updated = obj.optLong("updatedAtMs", created);
        String adminUid = obj.optString("adminUid", "");
        String adminName = obj.optString("adminDisplayName", "Admin");
        int ver = obj.optInt("profileVersion", 1);
        boolean active = obj.optBoolean("active", true);
        boolean deleted = obj.optBoolean("deleted", false);

        return new GlobalCalibrationProfile(
                id,
                canonicalLabel,
                category,
                origPred,
                embeddings,
                modelId,
                modelVer,
                sr,
                win,
                threshold,
                created,
                updated,
                adminUid,
                adminName,
                ver,
                active,
                deleted
        );
    }
}

