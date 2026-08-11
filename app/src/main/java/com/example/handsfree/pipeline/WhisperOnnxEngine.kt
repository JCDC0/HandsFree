package com.example.handsfree.pipeline

import android.content.Context
import android.util.Log
import com.example.handsfree.util.CrashLog
import java.io.File

/**
 * Offline ASR backed by the team's fine-tuned Whisper-tiny model, exported to
 * ONNX and executed on-device.
 *
 * Model: https://huggingface.co/KarlShane11/handsfree-whisper-tiny-tl
 * Baseline WER 82.72% -> fine-tuned 63.15% (23.7% relative reduction).
 *
 * ============================================================================
 * STATUS: WIRED BUT NOT YET LOADED. Waiting on the ONNX export from Karl.
 * ============================================================================
 *
 * Everything downstream of this class is finished. When the three export
 * artefacts land in `app/src/main/assets/whisper/`, set [MODEL_PRESENT] to true
 * and implement the two marked sections. Nothing else in the app changes.
 *
 * Expected assets, produced by sherpa-onnx's `export-onnx.py`:
 *   assets/whisper/encoder.onnx
 *   assets/whisper/decoder.onnx
 *   assets/whisper/tokens.txt
 *
 * Two things must match Karl's evaluation configuration exactly or output will
 * be wrong in ways that look like a bad model rather than a bad config:
 *   language = "tl" (Tagalog)
 *   task     = "transcribe"   (NOT "translate")
 *
 * Whisper is an utterance-level model, not a streaming one: it consumes a
 * 30-second padded window and emits a whole transcript. That has two
 * consequences the pipeline already accounts for —
 *   1. [getPartialResult] returns "" because there is no live hypothesis.
 *   2. [acceptWaveform] buffers audio and only returns non-null once
 *      [AudioPreprocessor]'s VAD reports end-of-utterance.
 */
class WhisperOnnxEngine(private val context: Context) : SpeechEngine {

    override var onReady: (() -> Unit)? = null
    override var onError: ((Throwable) -> Unit)? = null

    override var activeLanguage: String = "tl"
        private set

    @Volatile
    override var isReady: Boolean = false
        private set

    override val displayName: String
        get() = if (isReady) "Whisper-tiny-tl (ONNX)" else "Whisper-tiny-tl (not loaded)"

    /** Accumulates PCM until the VAD signals the utterance has ended. */
    private val utteranceBuffer = ArrayList<Short>(SAMPLE_RATE * 10)

    // private var encoder: OrtSession? = null
    // private var decoder: OrtSession? = null
    // private var env: OrtEnvironment? = null

    override fun prepare(language: String) {
        activeLanguage = language
        if (!MODEL_PRESENT) {
            val msg = "Whisper ONNX model not bundled yet — awaiting export from Karl. " +
                "Drop encoder.onnx, decoder.onnx and tokens.txt into assets/whisper/ " +
                "and set MODEL_PRESENT = true."
            Log.w(TAG, msg)
            CrashLog.info(context, "WhisperOnnxEngine: $msg")
            onError?.invoke(IllegalStateException(msg))
            return
        }

        Thread {
            try {
                CrashLog.step(context, "WHISPER_1: copying assets to filesDir")
                copyAssetsIfNeeded()

                CrashLog.step(context, "WHISPER_2: creating ONNX sessions")
                // ==================== IMPLEMENT WHEN MODEL LANDS ====================
                // env = OrtEnvironment.getEnvironment()
                // val opts = OrtSession.SessionOptions().apply {
                //     setIntraOpNumThreads(2)
                //     addNnapi()   // optional hardware acceleration; drop if unstable
                // }
                // encoder = env!!.createSession(modelPath(ENCODER_FILE), opts)
                // decoder = env!!.createSession(modelPath(DECODER_FILE), opts)
                // loadTokens(modelPath(TOKENS_FILE))
                // ===================================================================

                isReady = true
                CrashLog.ok(context, "WHISPER_2")
                onReady?.invoke()
            } catch (t: Throwable) {
                Log.e(TAG, "Whisper init failed", t)
                CrashLog.exception(context, "WhisperOnnxEngine.prepare", t)
                onError?.invoke(t)
            }
        }.apply { name = "whisper-init" }.start()
    }

    /** Single-model engine — language is fixed at export time. */
    override fun switchModel(language: String) {
        Log.i(TAG, "switchModel($language) ignored — Whisper build is single-model ($activeLanguage)")
    }

    override fun acceptWaveform(buffer: ShortArray): String? {
        if (!isReady) return null

        synchronized(utteranceBuffer) {
            buffer.forEach { utteranceBuffer.add(it) }
            if (utteranceBuffer.size > SAMPLE_RATE * MAX_UTTERANCE_SECONDS) {
                // Runaway capture guard: force a flush rather than grow forever.
                return flushAndTranscribe()
            }
        }
        return null
    }

    /**
     * Called by [AudioPreprocessor] when its VAD detects the end of an
     * utterance. Runs inference over everything buffered so far.
     */
    fun onUtteranceComplete(): String? = synchronized(utteranceBuffer) { flushAndTranscribe() }

    private fun flushAndTranscribe(): String? {
        if (utteranceBuffer.isEmpty()) return null
        val pcm = utteranceBuffer.toShortArray()
        utteranceBuffer.clear()

        if (pcm.size < SAMPLE_RATE / 4) return null // under 250 ms — noise, not speech

        return try {
            // ==================== IMPLEMENT WHEN MODEL LANDS ====================
            // 1. Convert PCM Short -> Float, normalise to [-1, 1] by / 32768f
            // 2. Compute the 80-bin log-Mel spectrogram, padded to 30 s (3000 frames)
            // 3. encoder.run(mel) -> encoderOut
            // 4. Greedy-decode with forced prefix tokens:
            //      <|startoftranscript|> <|tl|> <|transcribe|> <|notimestamps|>
            //    until <|endoftext|>
            // 5. Detokenise via tokens.txt and strip special tokens
            // ===================================================================
            null
        } catch (t: Throwable) {
            Log.e(TAG, "inference failed", t)
            CrashLog.exception(context, "WhisperOnnxEngine.transcribe", t)
            null
        }
    }

    /** Whisper has no streaming hypothesis — the UI shows a listening state instead. */
    override fun getPartialResult(): String = ""

    override fun release() {
        isReady = false
        synchronized(utteranceBuffer) { utteranceBuffer.clear() }
        // runCatching { encoder?.close(); decoder?.close(); env?.close() }
        Log.i(TAG, "released")
    }

    // ---- asset plumbing ----

    private fun modelPath(name: String) = File(File(context.filesDir, ASSET_DIR), name).absolutePath

    /**
     * ONNX Runtime needs real file paths, so the assets are copied out of the
     * APK once on first run.
     */
    private fun copyAssetsIfNeeded() {
        val outDir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }
        listOf(ENCODER_FILE, DECODER_FILE, TOKENS_FILE).forEach { name ->
            val out = File(outDir, name)
            if (out.exists() && out.length() > 0) return@forEach
            context.assets.open("$ASSET_DIR/$name").use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "copied $name (${out.length()} bytes)")
        }
    }

    companion object {
        private const val TAG = "WhisperOnnx"

        /**
         * Flip to true once the three ONNX artefacts are in assets/whisper/.
         * Until then the app falls back to VOSK so the pipeline stays testable.
         */
        const val MODEL_PRESENT = false

        const val ASSET_DIR = "whisper"
        const val ENCODER_FILE = "encoder.onnx"
        const val DECODER_FILE = "decoder.onnx"
        const val TOKENS_FILE = "tokens.txt"

        private const val SAMPLE_RATE = 16000
        private const val MAX_UTTERANCE_SECONDS = 25
    }
}
