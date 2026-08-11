package com.example.handsfree.pipeline

/**
 * Contract for an offline speech-to-text engine.
 *
 * HandsFree originally shipped VOSK ([VoskSpeechEngine]). The project has since
 * pivoted to a fine-tuned Whisper-tiny model ([WhisperOnnxEngine]). This
 * interface exists so the rest of the pipeline — [AudioPreprocessor],
 * [TranslationLayer], [LevenshteinMatcher] — never needs to know which engine
 * is behind it, and so the two can be swapped by changing a single line in
 * OverlayService.
 *
 * Every implementation must be fully offline. No network calls. Ever.
 */
interface SpeechEngine {

    /** Fired once the model is loaded and [acceptWaveform] is safe to call. */
    var onReady: (() -> Unit)?

    /** Fired when model loading or inference fails irrecoverably. */
    var onError: ((Throwable) -> Unit)?

    /** Language code currently loaded, e.g. "en" or "tl". */
    val activeLanguage: String

    /** True once the model is loaded and inference can run. */
    val isReady: Boolean

    /**
     * Human-readable engine name for the debug UI and logs,
     * e.g. "VOSK (en)" or "Whisper-tiny-tl (ONNX)".
     */
    val displayName: String

    /**
     * Prepare the model. May unpack assets, copy files, or build an ONNX
     * session. Implementations should do heavy work off the main thread and
     * signal completion through [onReady].
     */
    fun prepare(language: String = "en")

    /**
     * Switch to a different language model. No-op for single-model engines.
     */
    fun switchModel(language: String)

    /**
     * Feed a block of 16 kHz mono 16-bit PCM audio.
     *
     * @return the final transcript when an utterance has completed, or null
     *         if the engine is still mid-utterance.
     */
    fun acceptWaveform(buffer: ShortArray): String?

    /**
     * The in-flight hypothesis while the user is still speaking, or an empty
     * string if the engine does not support partial results.
     */
    fun getPartialResult(): String

    /** Release native resources. Safe to call more than once. */
    fun release()
}
