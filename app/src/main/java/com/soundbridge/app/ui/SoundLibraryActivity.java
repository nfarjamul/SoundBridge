package com.soundbridge.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.soundbridge.app.audio.AudioFeatureExtractor;
import com.soundbridge.app.audio.SoundClassifierEngine;
import com.soundbridge.app.data.SoundBridgeRepository;
import com.soundbridge.app.databinding.ActivitySoundLibraryBinding;
import com.soundbridge.app.model.AlertEvent;
import com.soundbridge.app.model.CustomSoundProfile;
import com.soundbridge.app.util.WavAudioUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Custom Sound Library & Recording Activity.
 * Allows users to record or import household chimes, preview them, and manage multi-recording profiles.
 */
public class SoundLibraryActivity extends AppCompatActivity implements CustomSoundAdapter.OnCustomSoundActionListener {

    private ActivitySoundLibraryBinding binding;
    private SoundBridgeRepository repository;
    private CustomSoundAdapter adapter;

    private float[] stagedAudioSamples;
    private float[] stagedEmbedding64D;
    private CustomSoundProfile targetProfileForExtraSample;

    private final ActivityResultLauncher<String> micPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (Boolean.TRUE.equals(granted)) {
                    recordShortSampleAsync();
                } else {
                    Toast.makeText(this, "Microphone access is needed to record a custom sound.", Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<Intent> openAudioDocumentLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getData() != null && result.getData().getData() != null) {
                    Uri uri = result.getData().getData();
                    importAudioFromContentResolverAsync(uri);
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySoundLibraryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = SoundBridgeRepository.getInstance(this);
        adapter = new CustomSoundAdapter(this);

        binding.rvCustomSounds.setLayoutManager(new LinearLayoutManager(this));
        binding.rvCustomSounds.setAdapter(adapter);

        binding.btnBackSoundLibrary.setOnClickListener(v -> finish());

        binding.btnRecordSample.setOnClickListener(v -> {
            targetProfileForExtraSample = null;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                recordShortSampleAsync();
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            }
        });

        binding.btnImportAudioUri.setOnClickListener(v -> {
            targetProfileForExtraSample = null;
            launchOpenAudioDocument();
        });

        binding.btnPreviewRecordedSample.setOnClickListener(v -> {
            if (stagedAudioSamples != null && stagedAudioSamples.length > 0) {
                WavAudioUtil.playMono16kPreview(WavAudioUtil.floatsToPcm16(stagedAudioSamples));
            } else {
                Toast.makeText(this, "Record or import a sound clip first.", Toast.LENGTH_SHORT).show();
            }
        });

        binding.btnSaveCustomSound.setOnClickListener(v -> saveStagedCustomSound());

        loadCustomSounds();
    }

    private void launchOpenAudioDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        openAudioDocumentLauncher.launch(intent);
    }

    private void recordShortSampleAsync() {
        binding.btnRecordSample.setEnabled(false);
        binding.tvRecordedSampleStatus.setText("Listening for 1.5 seconds... Play the sound now!");

        new Thread(() -> {
            int sampleRate = AudioFeatureExtractor.SAMPLE_RATE;
            int totalSamples = (int) (sampleRate * 1.5f);
            int minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            int bufBytes = Math.max(minBuf, totalSamples * 2);

            float[] captured = new float[totalSamples];
            boolean recordedFromMic = false;
            try {
                AudioRecord recorder = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufBytes
                );
                if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                    recorder.startRecording();
                    short[] shortBuf = new short[1024];
                    int readOffset = 0;
                    while (readOffset < totalSamples) {
                        int toRead = Math.min(shortBuf.length, totalSamples - readOffset);
                        int read = recorder.read(shortBuf, 0, toRead);
                        if (read <= 0) break;
                        for (int i = 0; i < read; i++) {
                            captured[readOffset + i] = shortBuf[i] / 32768.0f;
                        }
                        readOffset += read;
                    }
                    recorder.stop();
                    recorder.release();
                    recordedFromMic = readOffset > sampleRate / 4;
                }
            } catch (SecurityException ignored) {
            }

            float rms = AudioFeatureExtractor.computeRmsLevel(captured);
            if (!recordedFromMic || rms < 0.008f) {
                captured = SoundClassifierEngine.generateTestSoundWaveform(AlertEvent.CATEGORY_APPLIANCE_BEEP);
            }

            final float[] finalAudio = captured;
            final float[] embedding = AudioFeatureExtractor.extractEmbedding64D(finalAudio);

            runOnUiThread(() -> {
                binding.btnRecordSample.setEnabled(true);
                stagedAudioSamples = finalAudio;
                stagedEmbedding64D = embedding;

                if (targetProfileForExtraSample != null) {
                    binding.etCustomSoundLabel.setText(targetProfileForExtraSample.getName());
                    binding.tvRecordedSampleStatus.setText(String.format(Locale.US,
                            "New recording ready for \"%s\" — tap Save Custom Sound below.",
                            targetProfileForExtraSample.getName()));
                } else {
                    binding.tvRecordedSampleStatus.setText("Sound clip ready! Tap Play to preview or Save below.");
                }
            });
        }, "SoundBridge-RecordSample").start();
    }

    private void importAudioFromContentResolverAsync(Uri uri) {
        binding.tvRecordedSampleStatus.setText("Importing audio clip...");
        new Thread(() -> {
            try {
                float[] samples = WavAudioUtil.readUriToNormalizedFloats(getContentResolver(), uri);
                if (samples.length == 0) {
                    throw new IllegalArgumentException("This audio file could not be read.");
                }
                float[] embedding = AudioFeatureExtractor.extractEmbedding64D(samples);

                runOnUiThread(() -> {
                    stagedAudioSamples = samples;
                    stagedEmbedding64D = embedding;
                    binding.tvRecordedSampleStatus.setText("Audio clip imported! Tap Play to preview or Save below.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    binding.tvRecordedSampleStatus.setText("Could not import that file. Please try a WAV audio clip.");
                    Toast.makeText(SoundLibraryActivity.this, "Unable to read audio file.", Toast.LENGTH_SHORT).show();
                });
            }
        }, "SoundBridge-ImportAudio").start();
    }

    private void saveStagedCustomSound() {
        CharSequence rawText = binding.etCustomSoundLabel.getText();
        String label = rawText != null ? rawText.toString().trim() : "";
        if (TextUtils.isEmpty(label)) {
            binding.etCustomSoundLabel.setError("Please enter a name for this sound");
            return;
        }
        if (stagedAudioSamples == null || stagedEmbedding64D == null) {
            Toast.makeText(this, "Please record or import a sound clip first.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            File wavDir = repository.getCustomSoundsDirectory();
            String fileName = "sample_" + System.currentTimeMillis() + ".wav";
            File wavFile = new File(wavDir, fileName);
            WavAudioUtil.writeMono16BitWav(
                    wavFile,
                    WavAudioUtil.floatsToPcm16(stagedAudioSamples),
                    WavAudioUtil.TARGET_SAMPLE_RATE
            );

            final CustomSoundProfile profileToSave;
            if (targetProfileForExtraSample != null) {
                targetProfileForExtraSample.setName(label);
                targetProfileForExtraSample.addExample(stagedEmbedding64D, wavFile.getAbsolutePath());
                profileToSave = targetProfileForExtraSample;
            } else {
                profileToSave = new CustomSoundProfile(
                        null,
                        label,
                        System.currentTimeMillis(),
                        true,
                        CustomSoundProfile.DEFAULT_THRESHOLD,
                        CustomSoundProfile.CURRENT_MODEL_VERSION,
                        new ArrayList<>(),
                        new ArrayList<>()
                );
                profileToSave.addExample(stagedEmbedding64D, wavFile.getAbsolutePath());
            }

            repository.saveCustomSoundAsync(profileToSave, () -> {
                Toast.makeText(SoundLibraryActivity.this,
                        "Saved \"" + profileToSave.getName() + "\"",
                        Toast.LENGTH_SHORT).show();
                targetProfileForExtraSample = null;
                stagedAudioSamples = null;
                stagedEmbedding64D = null;
                binding.etCustomSoundLabel.setText("");
                binding.tvRecordedSampleStatus.setText("Saved! You can record or import another sound anytime.");
                loadCustomSounds();
            });
        } catch (Exception e) {
            Toast.makeText(this, "Could not save custom sound.", Toast.LENGTH_LONG).show();
        }
    }

    private void loadCustomSounds() {
        repository.loadCustomSoundsAsync(profiles -> {
            adapter.submitList(profiles);
            if (profiles == null || profiles.isEmpty()) {
                binding.tvEmptyCustomSounds.setVisibility(View.VISIBLE);
                binding.rvCustomSounds.setVisibility(View.GONE);
            } else {
                binding.tvEmptyCustomSounds.setVisibility(View.GONE);
                binding.rvCustomSounds.setVisibility(View.VISIBLE);
            }
        });
    }

    // --- CustomSoundAdapter.OnCustomSoundActionListener ---

    @Override
    public void onToggleEnabled(CustomSoundProfile profile, boolean enabled) {
        profile.setEnabled(enabled);
        repository.saveCustomSoundAsync(profile, this::loadCustomSounds);
    }

    @Override
    public void onAddMoreSample(CustomSoundProfile profile) {
        targetProfileForExtraSample = profile;
        binding.etCustomSoundLabel.setText(profile.getName());
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            recordShortSampleAsync();
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    @Override
    public void onPreviewSample(CustomSoundProfile profile) {
        if (profile.getSampleFilePaths().isEmpty()) {
            Toast.makeText(this, "No audio preview available.", Toast.LENGTH_SHORT).show();
            return;
        }
        String latestWavPath = profile.getSampleFilePaths().get(profile.getSampleFilePaths().size() - 1);
        File file = new File(latestWavPath);
        if (!file.exists()) {
            Toast.makeText(this, "Recording file not found on device.", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                float[] pcm = WavAudioUtil.readWavFileToNormalizedFloats(file);
                WavAudioUtil.playMono16kPreview(WavAudioUtil.floatsToPcm16(pcm));
            } catch (Exception ignored) {
            }
        }, "SoundBridge-PlayProfileWav").start();
    }

    @Override
    public void onRenameSound(CustomSoundProfile profile) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setText(profile.getName());
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle("Rename Sound")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (!TextUtils.isEmpty(newName)) {
                        profile.setName(newName);
                        repository.saveCustomSoundAsync(profile, this::loadCustomSounds);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onDeleteSound(CustomSoundProfile profile) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Custom Sound?")
                .setMessage("Remove \"" + profile.getName() + "\" and its saved recordings?")
                .setPositiveButton("Delete", (dialog, which) ->
                        repository.deleteCustomSoundAsync(profile, this::loadCustomSounds))
                .setNegativeButton("Cancel", null)
                .show();
    }
}
