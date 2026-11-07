package com.soundbridge.app.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Extracts 64-dimensional acoustic feature & embedding vectors from 16 kHz mono PCM windows
 * (log-spaced spectral sub-bands, temporal envelope modulation, zero-crossing rate, crest factor,
 * spectral centroid, spectral rolloff, pitch periodicity, and harmonic ratio).
 *
 * Used across:
 * 1) Bundled TFLite classifier subgraph input ([1, 64] -> [1, 521]),
 * 2) User-specific CustomSoundProfile cosine-similarity matching, and
 * 3) Administrator-approved GlobalCalibrationProfile multi-phase acoustic pattern matching.
 */
public final class AudioFeatureExtractor {
    public static final int SAMPLE_RATE = 16000;
    public static final int WINDOW_SAMPLES = 15600; // 0.975 seconds (standard YAMNet window)
    public static final int ADMIN_CLIP_SAMPLES = SAMPLE_RATE * 2; // 2.0 seconds (32,000 samples @ 16 kHz)
    public static final int EMBEDDING_DIM = 64;

    private AudioFeatureExtractor() {
    }

    /**
     * Computes the root-mean-square (RMS) amplitude in [0.0, 1.0] for live UI meter visualization.
     */
    public static float computeRmsLevel(float[] samples) {
        if (samples == null || samples.length == 0) {
            return 0f;
        }
        return computeRangeRms(samples, 0, samples.length);
    }

    public static float computeRangeRms(float[] samples, int offset, int length) {
        if (samples == null || length <= 0 || offset < 0 || offset >= samples.length) {
            return 0f;
        }
        int end = Math.min(samples.length, offset + length);
        int count = end - offset;
        if (count <= 0) {
            return 0f;
        }
        double sumSq = 0.0;
        for (int i = offset; i < end; i++) {
            float s = samples[i];
            sumSq += (double) s * s;
        }
        float rms = (float) Math.sqrt(sumSq / count);
        return Math.max(0f, Math.min(1f, rms));
    }

    /**
     * Extracts up to 3 phase-aligned 64-D L2-normalized reference embeddings from an administrator's
     * ~2-second (or arbitrary-length) 16 kHz mono recording using the exact same preprocessing window
     * (15,600 samples @ 16 kHz) as live inference:
     * 1) Peak-RMS window (strongest 0.975s acoustic event span),
     * 2) Onset-aligned window (sound starting near t ~ 0.08s of the window),
     * 3) Mid-arrival window (sound entering in the second half of an overlapping hop).
     *
     * This ensures high recognition accuracy across arbitrary microphone hop boundaries without
     * diluting short chimes/knocks with surrounding silence.
     */
    public static List<float[]> extractCalibrationEmbeddingsFromClip(float[] clipSamples16k) {
        List<float[]> results = new ArrayList<>();
        if (clipSamples16k == null || clipSamples16k.length < 256) {
            return results;
        }

        if (clipSamples16k.length <= WINDOW_SAMPLES) {
            float[] single = extractEmbedding64D(clipSamples16k);
            if (isNonZeroEmbedding(single)) {
                results.add(single);
            }
            return results;
        }

        // 1. Locate peak-RMS 15,600-sample window across the clip
        int maxStart = clipSamples16k.length - WINDOW_SAMPLES;
        int step = Math.max(800, WINDOW_SAMPLES / 8); // ~0.12s step
        int bestWinStart = 0;
        float bestWinRms = 0f;

        for (int start = 0; start <= maxStart; start += step) {
            float r = computeRangeRms(clipSamples16k, start, WINDOW_SAMPLES);
            if (r > bestWinRms) {
                bestWinRms = r;
                bestWinStart = start;
            }
        }
        float tailRms = computeRangeRms(clipSamples16k, maxStart, WINDOW_SAMPLES);
        if (tailRms > bestWinRms) {
            bestWinRms = tailRms;
            bestWinStart = maxStart;
        }

        if (bestWinRms < 0.008f) {
            return results; // Clip is silent
        }

        // 2. Locate fine-grained acoustic onset using 100ms (1,600-sample) frames
        int frameSamples = SAMPLE_RATE / 10;
        int numFrames = Math.max(1, clipSamples16k.length / frameSamples);
        float peakFrameRms = 0f;
        int peakFrameIdx = 0;
        float[] frameRms = new float[numFrames];
        for (int f = 0; f < numFrames; f++) {
            frameRms[f] = computeRangeRms(clipSamples16k, f * frameSamples, frameSamples);
            if (frameRms[f] > peakFrameRms) {
                peakFrameRms = frameRms[f];
                peakFrameIdx = f;
            }
        }
        int onsetFrameIdx = peakFrameIdx;
        float onsetFloor = Math.max(0.010f, 0.25f * peakFrameRms);
        for (int f = 0; f <= peakFrameIdx; f++) {
            if (frameRms[f] >= onsetFloor) {
                onsetFrameIdx = f;
                break;
            }
        }
        int onsetSample = onsetFrameIdx * frameSamples;

        int onsetAlignedStart = clampInt(onsetSample - (SAMPLE_RATE / 12), 0, maxStart);
        int arrivalPhaseStart = clampInt(onsetSample - (WINDOW_SAMPLES * 42 / 100), 0, maxStart);

        int[] candidateStarts = new int[]{bestWinStart, onsetAlignedStart, arrivalPhaseStart};
        for (int start : candidateStarts) {
            float winRms = computeRangeRms(clipSamples16k, start, WINDOW_SAMPLES);
            if (winRms < Math.max(0.008f, 0.45f * bestWinRms)) {
                continue;
            }
            float[] slice = Arrays.copyOfRange(clipSamples16k, start, start + WINDOW_SAMPLES);
            float[] emb = extractEmbedding64D(slice);
            if (!isNonZeroEmbedding(emb)) {
                continue;
            }
            boolean duplicate = false;
            for (float[] existing : results) {
                if (cosineSimilarity(existing, emb) > 0.992f) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                results.add(emb);
            }
        }

        return results;
    }

    private static boolean isNonZeroEmbedding(float[] vec) {
        if (vec == null || vec.length != EMBEDDING_DIM) return false;
        double sumSq = 0.0;
        for (float v : vec) {
            sumSq += (double) v * v;
        }
        return sumSq > 0.5;
    }

    private static int clampInt(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    /**
     * Extracts a deterministic 64-D L2-normalized acoustic embedding from a 16 kHz mono float buffer.
     * Layout of the 64 dimensions:
     * - [0..31]: 32 log-spaced frequency band energies (120 Hz to 7600 Hz via Goertzel resonators)
     * - [32..47]: 16 temporal envelope sub-frame energies across the ~1s window (captures cadence: knock pulses, beep-beep-beep, doorbell two-tone)
     * - [48..55]: 8 temporal envelope delta/modulation features (attack rate, decay rate, pulse count, duty cycle)
     * - [56..63]: 8 global spectral shape & harmonicity descriptors (ZCR, crest factor, spectral centroid, spectral spread, spectral flatness, high-to-low ratio, dominant band index, pitch salience)
     */
    public static float[] extractEmbedding64D(float[] rawSamples) {
        float[] vec = new float[EMBEDDING_DIM];
        if (rawSamples == null || rawSamples.length < 256) {
            return vec;
        }

        // Normalize peak amplitude of active signal so embedding is volume-invariant above silence floor
        float rms = computeRmsLevel(rawSamples);
        if (rms < 0.008f) {
            return vec; // Silence
        }

        int n = Math.min(rawSamples.length, WINDOW_SAMPLES);
        float peak = 0.001f;
        for (int i = 0; i < n; i++) {
            float abs = Math.abs(rawSamples[i]);
            if (abs > peak) peak = abs;
        }
        float[] norm = new float[n];
        for (int i = 0; i < n; i++) {
            norm[i] = rawSamples[i] / peak;
        }

        // 1. 32 log-spaced spectral bands from 120 Hz to 7400 Hz evaluated over overlapping 1024-sample frames
        int frameLen = Math.min(1024, n);
        int numSpecFrames = Math.max(1, Math.min(6, n / (frameLen / 2)));
        double minFreq = 120.0;
        double maxFreq = 7400.0;
        double logRatio = Math.log(maxFreq / minFreq);

        double totalSpecEnergy = 1e-9;
        double[] bandPower = new double[32];

        for (int b = 0; b < 32; b++) {
            double freq = minFreq * Math.exp(logRatio * b / 31.0);
            double powerSum = 0.0;
            for (int f = 0; f < numSpecFrames; f++) {
                int start = Math.min(n - frameLen, f * ((n - frameLen) / Math.max(1, numSpecFrames - 1)));
                powerSum += goertzelMagnitude(norm, start, frameLen, freq, SAMPLE_RATE);
            }
            bandPower[b] = powerSum / numSpecFrames;
            totalSpecEnergy += bandPower[b];
        }

        for (int b = 0; b < 32; b++) {
            vec[b] = (float) Math.log1p(12.0 * (bandPower[b] / totalSpecEnergy));
        }

        // 2. 16 temporal envelope sub-frames (captures rhythmic pattern: e.g., knock vs sustained alarm vs ding-dong)
        int subLen = Math.max(1, n / 16);
        float maxEnv = 1e-6f;
        float[] env = new float[16];
        for (int s = 0; s < 16; s++) {
            int start = s * subLen;
            int end = Math.min(n, start + subLen);
            double e = 0.0;
            for (int i = start; i < end; i++) {
                e += norm[i] * norm[i];
            }
            env[s] = (float) Math.sqrt(e / Math.max(1, end - start));
            if (env[s] > maxEnv) maxEnv = env[s];
        }
        for (int s = 0; s < 16; s++) {
            vec[32 + s] = env[s] / maxEnv;
        }

        // 3. 8 temporal envelope modulation & cadence features [48..55]
        int pulseCount = 0;
        int activeFrames = 0;
        float maxAttack = 0f;
        float maxDecay = 0f;
        float envMean = 0f;
        for (int s = 0; s < 16; s++) {
            float e = vec[32 + s];
            envMean += e;
            if (e > 0.35f) activeFrames++;
            if (s > 0) {
                float diff = e - vec[32 + s - 1];
                if (diff > maxAttack) maxAttack = diff;
                if (-diff > maxDecay) maxDecay = -diff;
                if (vec[32 + s - 1] <= 0.35f && e > 0.35f) {
                    pulseCount++;
                }
            } else if (e > 0.35f) {
                pulseCount++;
            }
        }
        envMean /= 16.0f;
        float envVar = 0f;
        for (int s = 0; s < 16; s++) {
            float d = vec[32 + s] - envMean;
            envVar += d * d;
        }
        envVar = (float) Math.sqrt(envVar / 16.0f);

        vec[48] = maxAttack;
        vec[49] = maxDecay;
        vec[50] = Math.min(1.0f, pulseCount / 5.0f);
        vec[51] = activeFrames / 16.0f; // Duty cycle (sustained alarm ~ 0.8-1.0, knock ~ 0.1-0.3)
        vec[52] = envMean;
        vec[53] = envVar;
        // First-half vs second-half energy balance (e.g., Ding-Dong two-tone envelope)
        float firstHalf = 0f;
        float secondHalf = 0f;
        for (int s = 0; s < 8; s++) firstHalf += vec[32 + s];
        for (int s = 8; s < 16; s++) secondHalf += vec[32 + s];
        vec[54] = firstHalf / 8.0f;
        vec[55] = secondHalf / 8.0f;

        // 4. 8 global spectral shape & harmonicity descriptors [56..63]
        int zeroCrossings = 0;
        for (int i = 1; i < n; i++) {
            if ((norm[i - 1] >= 0f && norm[i] < 0f) || (norm[i - 1] < 0f && norm[i] >= 0f)) {
                zeroCrossings++;
            }
        }
        float zcr = (float) zeroCrossings / n;
        float normRms = computeRmsLevel(norm);
        float crestFactor = Math.min(1.0f, (1.0f / Math.max(0.05f, normRms)) / 10.0f);

        double weightedBandSum = 0.0;
        double maxBandVal = 0.0;
        int dominantBand = 0;
        double geomMeanLog = 0.0;
        double arithMean = totalSpecEnergy / 32.0;
        for (int b = 0; b < 32; b++) {
            weightedBandSum += b * bandPower[b];
            geomMeanLog += Math.log(bandPower[b] + 1e-9);
            if (bandPower[b] > maxBandVal) {
                maxBandVal = bandPower[b];
                dominantBand = b;
            }
        }
        float centroidNorm = (float) ((weightedBandSum / totalSpecEnergy) / 31.0);
        double spreadSum = 0.0;
        for (int b = 0; b < 32; b++) {
            double diff = (b / 31.0) - centroidNorm;
            spreadSum += diff * diff * bandPower[b];
        }
        float spreadNorm = (float) Math.sqrt(spreadSum / totalSpecEnergy);
        float spectralFlatness = (float) Math.min(1.0, Math.exp(geomMeanLog / 32.0) / Math.max(1e-9, arithMean));
        float tonalPeakRatio = (float) Math.min(1.0, maxBandVal / totalSpecEnergy * 2.5);

        double lowBandSum = 0.0;
        double highBandSum = 0.0;
        for (int b = 0; b < 12; b++) lowBandSum += bandPower[b];
        for (int b = 16; b < 32; b++) highBandSum += bandPower[b];
        float highRatio = (float) (highBandSum / Math.max(1e-9, lowBandSum + highBandSum));

        vec[56] = Math.min(1.0f, zcr * 4.0f);
        vec[57] = crestFactor;
        vec[58] = centroidNorm;
        vec[59] = spreadNorm;
        vec[60] = spectralFlatness;
        vec[61] = highRatio;
        vec[62] = dominantBand / 31.0f;
        vec[63] = tonalPeakRatio;

        return l2Normalize(vec);
    }

    /**
     * Computes cosine similarity in [-1.0, 1.0] between two embedding vectors.
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0f;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        if (denom < 1e-9) {
            return 0f;
        }
        return (float) Math.max(-1.0, Math.min(1.0, dot / denom));
    }

    public static float[] l2Normalize(float[] input) {
        if (input == null) return new float[0];
        float[] out = new float[input.length];
        double sumSq = 0.0;
        for (float v : input) {
            sumSq += (double) v * v;
        }
        double norm = Math.sqrt(sumSq);
        if (norm < 1e-8) {
            return out;
        }
        for (int i = 0; i < input.length; i++) {
            out[i] = (float) (input[i] / norm);
        }
        return out;
    }

    private static double goertzelMagnitude(float[] samples, int offset, int length, double targetFreq, int sampleRate) {
        int k = (int) Math.round((length * targetFreq) / sampleRate);
        double omega = (2.0 * Math.PI * k) / length;
        double coeff = 2.0 * Math.cos(omega);
        double q0 = 0.0;
        double q1 = 0.0;
        double q2 = 0.0;
        int end = Math.min(samples.length, offset + length);
        for (int i = offset; i < end; i++) {
            // Apply Hann window to reduce spectral leakage
            double win = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * (i - offset) / Math.max(1, length - 1)));
            q0 = coeff * q1 - q2 + samples[i] * win;
            q2 = q1;
            q1 = q0;
        }
        double magSq = q1 * q1 + q2 * q2 - q1 * q2 * coeff;
        return Math.sqrt(Math.max(0.0, magSq)) / length;
    }
}
