package com.soundbridge.app.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a user-recorded custom sound profile with one or more stored embedding vectors.
 * Detailed audio samples and individual embeddings stay in app-private local storage by default.
 */
public class CustomSoundProfile {
    public static final String CURRENT_MODEL_VERSION = "YAMNet-AudioSet-v1-64D";
    public static final float DEFAULT_THRESHOLD = 0.82f;

    private final String id;
    private String name;
    private final long createdAtMs;
    private boolean enabled;
    private float matchingThreshold;
    private final String modelVersion;
    private final List<float[]> exampleEmbeddings;
    private final List<String> sampleFilePaths;

    public CustomSoundProfile(
            String id,
            String name,
            long createdAtMs,
            boolean enabled,
            float matchingThreshold,
            String modelVersion,
            List<float[]> exampleEmbeddings,
            List<String> sampleFilePaths
    ) {
        this.id = (id == null || id.trim().isEmpty()) ? "cust_" + UUID.randomUUID().toString().substring(0, 8) : id;
        this.name = name != null ? name.trim() : "Custom Sound";
        this.createdAtMs = createdAtMs;
        this.enabled = enabled;
        this.matchingThreshold = matchingThreshold > 0f ? matchingThreshold : DEFAULT_THRESHOLD;
        this.modelVersion = modelVersion != null ? modelVersion : CURRENT_MODEL_VERSION;
        this.exampleEmbeddings = exampleEmbeddings != null ? new ArrayList<>(exampleEmbeddings) : new ArrayList<>();
        this.sampleFilePaths = sampleFilePaths != null ? new ArrayList<>(sampleFilePaths) : new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.trim().isEmpty()) {
            this.name = name.trim();
        }
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public float getMatchingThreshold() {
        return matchingThreshold;
    }

    public void setMatchingThreshold(float matchingThreshold) {
        this.matchingThreshold = Math.max(0.50f, Math.min(0.98f, matchingThreshold));
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public List<float[]> getExampleEmbeddings() {
        return exampleEmbeddings;
    }

    public List<String> getSampleFilePaths() {
        return sampleFilePaths;
    }

    public int getSampleCount() {
        return exampleEmbeddings.size();
    }

    public void addExample(float[] embedding, String sampleFilePath) {
        if (embedding != null && embedding.length > 0) {
            exampleEmbeddings.add(embedding);
        }
        if (sampleFilePath != null && !sampleFilePath.isEmpty()) {
            sampleFilePaths.add(sampleFilePath);
        }
    }

    /**
     * Computes a compact L2-normalized centroid descriptor for optional consented metadata sync.
     */
    public float[] computeCentroidDescriptor() {
        if (exampleEmbeddings.isEmpty()) {
            return new float[0];
        }
        int dim = exampleEmbeddings.get(0).length;
        float[] mean = new float[dim];
        int count = 0;
        for (float[] emb : exampleEmbeddings) {
            if (emb != null && emb.length == dim) {
                for (int i = 0; i < dim; i++) {
                    mean[i] += emb[i];
                }
                count++;
            }
        }
        if (count == 0) {
            return new float[0];
        }
        double normSq = 0.0;
        for (int i = 0; i < dim; i++) {
            mean[i] /= count;
            normSq += mean[i] * mean[i];
        }
        double norm = Math.sqrt(normSq);
        if (norm > 1e-8) {
            for (int i = 0; i < dim; i++) {
                mean[i] = (float) (mean[i] / norm);
            }
        }
        return mean;
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

    public static List<float[]> deserializeEmbeddingsJson(String json) throws JSONException {
        List<float[]> list = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return list;
        }
        JSONArray outer = new JSONArray(json);
        for (int i = 0; i < outer.length(); i++) {
            JSONArray inner = outer.getJSONArray(i);
            float[] vec = new float[inner.length()];
            for (int j = 0; j < inner.length(); j++) {
                vec[j] = (float) inner.getDouble(j);
            }
            list.add(vec);
        }
        return list;
    }

    public String serializeSamplePathsJson() {
        JSONArray arr = new JSONArray();
        for (String p : sampleFilePaths) {
            arr.put(p);
        }
        return arr.toString();
    }

    public static List<String> deserializeSamplePathsJson(String json) {
        List<String> list = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return list;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getString(i));
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("name", name);
        obj.put("createdAtMs", createdAtMs);
        obj.put("enabled", enabled);
        obj.put("matchingThreshold", (double) matchingThreshold);
        obj.put("modelVersion", modelVersion);
        obj.put("embeddings", new JSONArray(serializeEmbeddingsJson()));
        obj.put("samplePaths", new JSONArray(serializeSamplePathsJson()));
        return obj;
    }

    public static CustomSoundProfile fromJson(JSONObject obj) throws JSONException {
        String id = obj.getString("id");
        String name = obj.getString("name");
        long createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis());
        boolean enabled = obj.optBoolean("enabled", true);
        float threshold = (float) obj.optDouble("matchingThreshold", DEFAULT_THRESHOLD);
        String modelVersion = obj.optString("modelVersion", CURRENT_MODEL_VERSION);
        List<float[]> embeddings = deserializeEmbeddingsJson(obj.optJSONArray("embeddings") != null
                ? obj.getJSONArray("embeddings").toString() : "[]");
        List<String> paths = deserializeSamplePathsJson(obj.optJSONArray("samplePaths") != null
                ? obj.getJSONArray("samplePaths").toString() : "[]");
        return new CustomSoundProfile(id, name, createdAtMs, enabled, threshold, modelVersion, embeddings, paths);
    }
}

