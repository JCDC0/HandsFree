package com.example.handsfree.pipeline

/**
 * Captures raw audio from the device microphone using AudioRecord configured at:
 *   - Sample rate: 16000 Hz (required by VOSK)
 *   - Channel config: CHANNEL_IN_MONO
 *   - Audio format: ENCODING_PCM_16BIT
 *
 * Applies Voice Activity Detection (VAD) to isolate speech segments
 * and forwards cleaned audio buffers to [VoskSpeechEngine].
 */
class AudioPreprocessor {

    private var isCapturing = false

    /**
     * Start capturing audio from the microphone.
     * Audio buffers are forwarded to [onAudioBuffer] for downstream processing.
     */
    fun startCapture() {
        // TODO: Initialize AudioRecord(16000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)
        // TODO: Start reading thread, apply spectral subtraction noise reduction
        // TODO: Apply VAD to detect speech vs silence
        isCapturing = true
    }

    /**
     * Stop microphone capture.
     */
    fun stopCapture() {
        // TODO: Stop AudioRecord and release resources
        isCapturing = false
    }
}
