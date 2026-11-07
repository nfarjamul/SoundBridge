package com.soundbridge.app.util;

import android.content.ContentResolver;
import android.media.AudioFormat;
import android.media.AudioAttributes;
import android.media.AudioTrack;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Handles reading/writing 16 kHz mono 16-bit PCM WAV files in app-private storage,
 * resampling imported WAV/PCM streams from ContentResolver URIs (ACTION_OPEN_DOCUMENT),
 * and playing back preview clips for custom sounds.
 */
public final class WavAudioUtil {
    public static final int TARGET_SAMPLE_RATE = 16000;

    private WavAudioUtil() {
    }

    /**
     * Writes 16-bit mono PCM samples at 16 kHz to a standard 44-byte RIFF WAV file.
     */
    public static void writeMono16BitWav(File file, short[] samples, int sampleRate) throws IOException {
        int byteRate = sampleRate * 2;
        int dataSize = samples.length * 2;
        int totalSize = 36 + dataSize;

        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R', 'I', 'F', 'F'});
        header.putInt(totalSize);
        header.put(new byte[]{'W', 'A', 'V', 'E'});
        header.put(new byte[]{'f', 'm', 't', ' '});
        header.putInt(16); // PCM chunk size
        header.putShort((short) 1); // AudioFormat = 1 (PCM)
        header.putShort((short) 1); // Channels = 1 (Mono)
        header.putInt(sampleRate);
        header.putInt(byteRate);
        header.putShort((short) 2); // BlockAlign
        header.putShort((short) 16); // BitsPerSample
        header.put(new byte[]{'d', 'a', 't', 'a'});
        header.putInt(dataSize);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(header.array());
            ByteBuffer dataBuf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN);
            for (short s : samples) {
                dataBuf.putShort(s);
            }
            fos.write(dataBuf.array());
        }
    }

    /**
     * Reads a WAV or raw 16-bit PCM file into normalized float samples in [-1.0f, 1.0f] resampled to 16 kHz.
     */
    public static float[] readWavFileToNormalizedFloats(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            return readStreamToNormalizedFloats(fis);
        }
    }

    /**
     * Reads an audio URI opened via ContentResolver (ACTION_OPEN_DOCUMENT) and converts/resamples
     * to 16 kHz mono normalized float samples in [-1.0f, 1.0f].
     */
    public static float[] readUriToNormalizedFloats(ContentResolver resolver, Uri uri) throws IOException {
        try (InputStream is = resolver.openInputStream(uri)) {
            if (is == null) {
                throw new IOException("ContentResolver returned null InputStream for URI: " + uri);
            }
            return readStreamToNormalizedFloats(is);
        }
    }

    public static float[] readStreamToNormalizedFloats(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int maxBytes = TARGET_SAMPLE_RATE * 2 * 15; // Cap at ~15 seconds max to prevent OOM
        int totalRead = 0;
        int n;
        while ((n = is.read(buf)) != -1 && totalRead < maxBytes) {
            int toWrite = Math.min(n, maxBytes - totalRead);
            baos.write(buf, 0, toWrite);
            totalRead += toWrite;
        }
        byte[] raw = baos.toByteArray();
        if (raw.length < 44) {
            return new float[0];
        }

        int sampleRate = TARGET_SAMPLE_RATE;
        int channels = 1;
        int bitsPerSample = 16;
        int dataOffset = 0;
        int dataLength = raw.length;

        // Check for RIFF WAVE header
        if (raw[0] == 'R' && raw[1] == 'I' && raw[2] == 'F' && raw[3] == 'F'
                && raw[8] == 'W' && raw[9] == 'A' && raw[10] == 'V' && raw[11] == 'E') {
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            channels = Math.max(1, bb.getShort(22));
            sampleRate = Math.max(4000, bb.getInt(24));
            bitsPerSample = Math.max(8, bb.getShort(34));
            dataOffset = 44;
            // Find 'data' chunk if non-standard header size
            for (int i = 12; i < Math.min(raw.length - 8, 256); i++) {
                if (raw[i] == 'd' && raw[i + 1] == 'a' && raw[i + 2] == 't' && raw[i + 3] == 'a') {
                    int subchunkSize = bb.getInt(i + 4);
                    dataOffset = i + 8;
                    dataLength = Math.min(raw.length - dataOffset, Math.max(0, subchunkSize));
                    break;
                }
            }
            if (dataOffset + dataLength > raw.length) {
                dataLength = raw.length - dataOffset;
            }
        }

        int bytesPerFrame = Math.max(1, (bitsPerSample / 8) * channels);
        int frameCount = dataLength / bytesPerFrame;
        if (frameCount <= 0) {
            return new float[0];
        }

        float[] mono = new float[frameCount];
        ByteBuffer dataBb = ByteBuffer.wrap(raw, dataOffset, dataLength).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frameCount; i++) {
            float sum = 0f;
            for (int ch = 0; ch < channels; ch++) {
                if (bitsPerSample == 16 && dataBb.remaining() >= 2) {
                    short pcm = dataBb.getShort();
                    sum += pcm / 32768.0f;
                } else if (bitsPerSample == 8 && dataBb.remaining() >= 1) {
                    int unsignedByte = dataBb.get() & 0xFF;
                    sum += (unsignedByte - 128) / 128.0f;
                } else if (dataBb.remaining() >= 2) {
                    sum += dataBb.getShort() / 32768.0f;
                }
            }
            mono[i] = Math.max(-1.0f, Math.min(1.0f, sum / channels));
        }

        return resampleLinear(mono, sampleRate, TARGET_SAMPLE_RATE);
    }

    /**
     * Resamples a mono float array from sourceRate to targetRate using linear interpolation.
     */
    public static float[] resampleLinear(float[] input, int sourceRate, int targetRate) {
        if (input == null || input.length == 0) {
            return new float[0];
        }
        if (sourceRate <= 0 || targetRate <= 0 || sourceRate == targetRate) {
            return input.clone();
        }
        double ratio = (double) sourceRate / (double) targetRate;
        int outLen = Math.max(1, (int) Math.round(input.length / ratio));
        float[] output = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            double srcIdx = i * ratio;
            int idx0 = (int) Math.floor(srcIdx);
            int idx1 = Math.min(input.length - 1, idx0 + 1);
            float frac = (float) (srcIdx - idx0);
            if (idx0 >= input.length) {
                output[i] = input[input.length - 1];
            } else {
                output[i] = input[idx0] * (1.0f - frac) + input[idx1] * frac;
            }
        }
        return output;
    }

    public static short[] floatsToPcm16(float[] floats) {
        if (floats == null) return new short[0];
        short[] out = new short[floats.length];
        for (int i = 0; i < floats.length; i++) {
            float clamped = Math.max(-1.0f, Math.min(1.0f, floats[i]));
            out[i] = (short) Math.round(clamped * 32767f);
        }
        return out;
    }

    public static float[] pcm16ToFloats(short[] pcm, int length) {
        if (pcm == null || length <= 0) return new float[0];
        int count = Math.min(pcm.length, length);
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            out[i] = pcm[i] / 32768.0f;
        }
        return out;
    }

    /**
     * Plays a short mono 16 kHz PCM buffer on a background thread for custom sound preview.
     */
    public static void playMono16kPreview(short[] pcmSamples) {
        if (pcmSamples == null || pcmSamples.length == 0) return;
        new Thread(() -> {
            AudioTrack track = null;
            try {
                int minBuf = AudioTrack.getMinBufferSize(
                        TARGET_SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                );
                int bufferBytes = Math.max(minBuf, pcmSamples.length * 2);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(TARGET_SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(bufferBytes)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
                track.write(pcmSamples, 0, pcmSamples.length);
                track.play();
                long durationMs = (pcmSamples.length * 1000L) / TARGET_SAMPLE_RATE + 150L;
                Thread.sleep(Math.min(6000L, durationMs));
            } catch (Exception ignored) {
            } finally {
                if (track != null) {
                    try {
                        track.stop();
                    } catch (Exception ignored) {
                    }
                    track.release();
                }
            }
        }, "SoundBridgePreviewThread").start();
    }
}

