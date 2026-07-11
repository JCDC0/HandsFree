package com.example.handsfree.pipeline

import android.content.Context
import android.util.Log
import com.example.handsfree.util.CrashLog
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
    var onError: ((Throwable) -> Unit)? = null

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var modelPath: String = ""
    /** Currently active language code ("en" or "tl"). */
    var activeLanguage: String = "en"
        private set

    /**
     * Unpacks VOSK models from assets to internal storage if not already present.
     * The Filipino model (~776MB) is too large to load directly from assets.
     * Call this once during app initialization, then again when switching models.
     *
     * @param language "en" (default, ~40MB English model) or "tl" (Filipino ~776MB).
     *                 On success, the [Model] is stored and [onReady] is invoked;
     *                 on failure [onError] is invoked.
     */
    fun unpackModelsIfNeeded(language: String = "en") {
        val modelDirName = dirNameFor(language)
        val targetDir = File(context.filesDir, modelDirName)
        if (!targetDir.exists()) {
            CrashLog.info(context, "unpacking model $language -> ${targetDir.absolutePath}")
            StorageService.unpack(
                context,
                modelDirName,
                targetDir.absolutePath,
                object : StorageService.Callback<Model> {
                    override fun onComplete(model: Model) {
                        this@VoskSpeechEngine.model = model
                        try {
                            initRecognizer(language)
                        } catch (e: Throwable) {
                            Log.e(TAG, "initRecognizer failed after unpack ($language)", e)
                            onError?.invoke(e)
                            return
                        }
                        onReady?.invoke()
                    }
                },
                object : StorageService.Callback<IOException> {
                    override fun onComplete(error: IOException) {
                        Log.e(TAG, "Unpack failed ($language)", error)
                        onError?.invoke(error)
                    }
                }
            )
        } else {
            // Model already unpacked on a previous launch — load it directly.
            try {
                initRecognizer(language)
            } catch (e: Throwable) {
                Log.e(TAG, "initRecognizer failed on cached model ($language)", e)
                onError?.invoke(e)
                return
            }
            onReady?.invoke()
        }
    }

    /**
     * Initialize VOSK Recognizer. If the [Model] was already supplied by
     * [StorageService.unpack] (stored in [model]), it is reused; otherwise the
     * model is loaded from disk by path.
     *
     * Wraps both [Model] and [Recognizer] constructors in try-catch so that
     * a corrupt model, missing libvosk.so, or 16KB-page-alignment
     * UnsatisfiedLinkError surfaces through [onError] instead of crashing the
     * host service.
     */
    fun initRecognizer(language: String = "en") {
        val previousLanguage = activeLanguage
        activeLanguage = language
        modelPath = File(context.filesDir, dirNameFor(language)).absolutePath
        CrashLog.step(context, "STEP_7: Model/Recognizer init START (lang=$language modelPath=$modelPath)")
        // When switching to a different language, drop the cached Model — it
        // points at the other model's directory and must not be reused.
        if (model != null && language != previousLanguage) {
            runCatching { model!!.close() }
            model = null
        }
        try {
            if (model == null) {
                model = Model(modelPath)
            }
        } catch (e: Throwable) {
            CrashLog.exception(context, "STEP_7 Model()", e)
            Log.e(TAG, "VOSK Model() failed: ${e.message}", e)
            throw e
        }
        // Always rebuild the Recognizer so it binds to the active Model.
        runCatching { recognizer?.close() }
        try {
            recognizer = Recognizer(model, 16000.0f)
        } catch (e: Throwable) {
            CrashLog.exception(context, "STEP_7 Recognizer()", e)
            Log.e(TAG, "VOSK Recognizer() failed: ${e.message}", e)
            throw e
        }
        CrashLog.ok(context, "STEP_8: Model/Recognizer init DONE (lang=$language)")
    }

    /**
     * Switch the active recognizer to [language] ("en" or "tl"). Unpacks the
     * model from assets on first use, then re-initializes the [Model] and
     * [Recognizer]. Safe to call while a pipeline is running — the caller
     * ([OverlayService.switchModel]) is responsible for stopping/restarting
     * mic capture around this call so no audio is lost mid-switch.
     *
     * Invokes [onReady] on success or [onError] on failure (same contract as
     * [unpackModelsIfNeeded]).
     */
    fun switchModel(language: String) {
        if (language != "en" && language != "tl") {
            Log.w(TAG, "switchModel: unknown language '$language' — ignoring")
            return
        }
        if (language == activeLanguage && recognizer != null) {
            Log.i(TAG, "switchModel: already on '$language' — nothing to do")
            onReady?.invoke()
            return
        }
        Log.i(TAG, "switchModel: $activeLanguage -> $language")
        unpackModelsIfNeeded(language)
    }

    /**
     * Accepts raw PCM audio buffer from [AudioPreprocessor] and returns
     * transcribed text if a complete utterance was recognized, or null.
     *
     * Even when this returns null (no final result yet), the recognizer has
     * absorbed the audio — call [getPartialResult] afterwards to read the
     * in-flight partial hypothesis for real-time caption display.
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
     * Returns the current partial recognition hypothesis (may be empty when the
     * recognizer has nothing confident yet). Call after every acceptWaveform to
     * surface live partial captions to the user while they are still speaking.
     */
    fun getPartialResult(): String {
        val r = recognizer ?: return ""
        val text = JSONObject(r.partialResult).optString("partial", "").trim()
        return text
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

        /** Internal-storage directory name for the small English model. */
        const val EN_MODEL_DIR = "vosk-model-small-en-us-0.15"
        /** Internal-storage directory name for the Filipino generic model. */
        const val TL_MODEL_DIR = "vosk-model-tl-ph-generic-0.6"

        /** Map a language code ("en"/"tl") to its unpacked model directory name. */
        fun dirNameFor(language: String): String = when (language) {
            "tl" -> TL_MODEL_DIR
            else -> EN_MODEL_DIR
        }
    }
}