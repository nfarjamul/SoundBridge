# Downloads the official TensorFlow Hub YAMNet AudioSet TFLite model (Apache-2.0 License)
# into app/src/main/assets/yamnet.tflite for full raw-waveform 16 kHz inference.
$ErrorActionPreference = "Stop"
$url = "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/audio_classification/android/lite-model_yamnet_classification_tflite_1.tflite"
$dest = Join-Path $PSScriptRoot "..\app\src\main\assets\yamnet.tflite"
Write-Host "Downloading official YAMNet TFLite model from TensorFlow Hub..."
Invoke-WebRequest -Uri $url -OutFile $dest
Write-Host "Saved to $dest ($((Get-Item $dest).Length) bytes)."

