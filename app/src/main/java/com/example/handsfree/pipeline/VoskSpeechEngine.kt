package com.example.handsfree.pipeline

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import org.json.JSONObject
import java.io.File
import java.io.IOException

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

    var onReady: (() -> Unit)? = null
    var onError: ((IOException) -> Unit)? = null

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var modelPath: String = ""

    /**
     * Unpacks VOSK models from assets to internal storage if not already present.
     * The Filipino model (~776MB) is too large to load directly from assets.
     * Call this once during app initialization. On success, the returned [Model]
     * is stored and [onReady] is invoked; on failure [onError] is invoked.
     */
    fun unpackModelsIfNeeded() {
        val enDir = File(context.filesDir, "vosk-model-small-en-us-0.15")
        if (!enDir.exists()) {
            StorageService.unpack(
                context,
                "vosk-model-small-en-us-0.15",
                enDir.absolutePath,
                object : StorageService.Callback<Model> {
                    override fun onComplete(model: Model) {
                        this@VoskSpeechEngine.model = model
                        initRecognizer()
                        onReady?.invoke()
                    }
                },
                object : StorageService.Callback<IOException> {
                    override fun onComplete(error: IOException) {
                        Log.e(TAG, "Unpack failed", error)
                        onError?.invoke(error)
                    }
                }
            )
        } else {
            // Model already unpacked on a previous launch — load it directly.
            initRecognizer()
            onReady?.invoke()
        }
    }

    /**
     * Initialize VOSK Recognizer. If the [Model] was already supplied by
     * [StorageService.unpack] (stored in [model]), it is reused; otherwise the
     * model is loaded from disk by path.
     */
    fun initRecognizer(language: String = "en") {
        modelPath = File(context.filesDir, "vosk-model-small-en-us-0.15").absolutePath
        if (model == null) {
            model = Model(modelPath)
        }
        recognizer = Recognizer(model, 16000.0f)
    }

    /**
     * Accepts raw PCM audio buffer from [AudioPreprocessor] and returns
     * transcribed text if a complete utterance was recognized, or null.
     */
    fun acceptWaveform(buffer: ShortArray): String? {
        val isFinal = recognizer!!.acceptWaveForm(buffer, buffer.size)
        if (isFinal) {
            val text = JSONObject(recognizer!!.result).optString("text", "").trim()
            return text.ifEmpty { null }
        }
        return null
    }

    /**
     * Release the VOSK Recognizer and Model resources.
     */
    fun release() {
        recognizer?.close()
        model?.close()
        recognizer = null
        model = null
    }

    companion object {
        private const val TAG = "VoskSpeechEngine"
    }
}