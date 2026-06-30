package com.example.handsfree.pipeline

import android.content.Context

/**
 * Wraps the VOSK Android library for offline speech recognition.
 *
 * Both VOSK models (English ~40MB, Filipino ~776MB) must be unpacked from
 * the assets folder to internal storage on first launch using VOSK's
 * StorageService.unpack() API. Subsequent launches reuse the unpacked models.
 *
 * The Recognizer is initialized at 16000 Hz to match AudioPreprocessor output.
 */
class VoskSpeechEngine(private val context: Context) {

    // TODO: Model paths in internal storage after unpacking
    private var englishModelPath: String = ""
    private var filipinoModelPath: String = ""

    /**
     * Unpacks VOSK models from assets to internal storage if not already present.
     * The Filipino model (~776MB) is too large to load directly from assets.
     * Call this once during app initialization.
     */
    fun unpackModelsIfNeeded() {
        // TODO: Check if model directories exist in filesDir
        // TODO: If not, call StorageService.unpack(context, "vosk-model-small-en-us-0.15", ...)
        // TODO: Same for vosk-model-tl-ph-generic-0.6
        // TODO: Show progress indicator (the Filipino model unpack takes 30-60 seconds)
    }

    /**
     * Initialize VOSK Recognizer with the specified model (English or Filipino).
     * @param language "en" or "tl" — selects which unpacked model to load
     */
    fun initRecognizer(language: String) {
        // TODO: Model(modelPath), Recognizer(model, 16000.0f)
    }

    /**
     * Accepts raw PCM audio buffer from [AudioPreprocessor] and returns
     * transcribed text if a complete utterance was recognized, or null.
     */
    fun acceptWaveform(buffer: ShortArray): String? {
        // TODO: recognizer.acceptWaveForm(buffer, buffer.size)
        // TODO: If true, return recognizer.result
        return null
    }

    /**
     * Release the VOSK Recognizer and Model resources.
     */
    fun release() {
        // TODO: recognizer.close(), model.close()
    }
}
