package com.example.handsfree.pipeline

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Lightweight keyword spotter using openWakeWord ONNX models.
 *
 * Three-stage pipeline, all running on the existing onnxruntime-android 1.19.0:
 *
 *   raw PCM (1280 samples, 80ms) → melspectrogram.onnx → embedding_model.onnx → wakeword.onnx
 *
 * Total model size is ~2.5 MB. Inference takes <3 ms per 80 ms frame on a
 * modern phone CPU — orders of magnitude cheaper than Whisper's 37.8M-param
 * full-sentence decode, which is the whole point of this cascade.
 *
 * **Deterministic:** same audio in, same score out. No sampling, no LLM,
 * no probability threshold that varies between runs. The threshold is a
 * fixed constant chosen once and applied identically every time.
 *
 * No network calls. Everything is on-device.
 */
class KeywordSpotter(context: Context, private val threshold: Float = DEFAULT_THRESHOLD) {

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var melSession: OrtSession? = null
    private var embeddingSession: OrtSession? = null
    private var wakeWordSession: OrtSession? = null

    // Rolling mel spectrogram buffer: 76 frames × 32 mel bins
    private val melBuffer = Array(MEL_BUFFER_SIZE) { FloatArray(MEL_BINS) }
    private var melBufferPos = 0
    private var melBufferFilled = false

    // Rolling embedding buffer: N frames × 96 dimensions
    private val embeddingBuffer = Array(EMBEDDING_BUFFER_SIZE) { FloatArray(EMBEDDING_DIM) }
    private var embeddingBufferPos = 0
    private var embeddingBufferFilled = false

    // Refractory period: suppress duplicate triggers on the same utterance
    private var lastTriggerTime = 0L

    @Volatile
    var isReady = false
        private set

    /**
     * Load the three ONNX models from assets/kws/. Call from a background
     * thread — model loading can take 100–200 ms.
     */
    fun prepare(context: Context) {
        try {
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            melSession = ortEnv.createSession(
                context.assets.open("$ASSET_DIR/$MEL_MODEL").readBytes(), opts
            )
            embeddingSession = ortEnv.createSession(
                context.assets.open("$ASSET_DIR/$EMBEDDING_MODEL").readBytes(), opts
            )
            wakeWordSession = ortEnv.createSession(
                context.assets.open("$ASSET_DIR/$WAKEWORD_MODEL").readBytes(), opts
            )

            isReady = true
            Log.i(TAG, "KWS models loaded: mel + embedding + wakeword")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load KWS models", e)
            isReady = false
        }
    }

    /**
     * Process one 80 ms audio chunk (1280 samples at 16 kHz).
     *
     * @param samples 16-bit PCM samples as ShortArray, length 1280
     * @return confidence score 0.0–1.0, or -1.0 if not ready / error
     */
    fun processAudio(samples: ShortArray): Float {
        if (!isReady) return -1f
        if (samples.size < CHUNK_SAMPLES) return -1f

        return try {
            // openWakeWord feeds the melspectrogram RAW int16 magnitudes, NOT
            // samples normalised to [-1, 1]. Measured against the bundled model:
            // dividing by 32768 shifts every mel bin by a constant -90.31, so
            // after the /10+2 transform below the embedding sees ~0.47 where it
            // was trained on ~9.5. The classifier could never have fired even
            // with the cast below fixed.
            val floatSamples = FloatArray(CHUNK_SAMPLES) { i -> samples[i].toFloat() }

            // Stage 1: mel spectrogram
            val melOutput = runMelSpectrogram(floatSamples) ?: return -1f

            // Frame count comes from the model, not from a constant. A
            // 1280-sample chunk yields 5 frames at hop 160 / window 480; the
            // constant claimed 8, which would have over-read the array even
            // once the cast was fixed.
            for (frame in melOutput.indices) {
                val scaledFrame = FloatArray(MEL_BINS) { bin ->
                    (melOutput[frame][bin] / 10f) + 2f
                }
                melBuffer[melBufferPos % MEL_BUFFER_SIZE] = scaledFrame
                melBufferPos++
                if (melBufferPos >= MEL_BUFFER_SIZE) melBufferFilled = true
            }

            // Need enough mel frames before we can compute embeddings
            if (!melBufferFilled) return 0f

            // Stage 2: embedding
            val embedding = runEmbedding() ?: return -1f

            // Buffer the embedding
            embeddingBuffer[embeddingBufferPos % EMBEDDING_BUFFER_SIZE] = embedding
            embeddingBufferPos++
            if (embeddingBufferPos >= EMBEDDING_BUFFER_SIZE) embeddingBufferFilled = true

            // Need enough embeddings before we can classify
            if (!embeddingBufferFilled) return 0f

            // Stage 3: wake word classification
            val score = runWakeWordClassifier() ?: return -1f
            score
        } catch (e: Exception) {
            Log.e(TAG, "KWS inference error", e)
            -1f
        }
    }

    /**
     * Check if a keyword was detected, with refractory period.
     */
    fun isKeywordDetected(score: Float): Boolean {
        if (score < threshold) return false
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < REFRACTORY_MS) return false
        lastTriggerTime = now
        return true
    }

    /** Clear all internal state between activations. */
    fun reset() {
        melBufferPos = 0
        melBufferFilled = false
        embeddingBufferPos = 0
        embeddingBufferFilled = false
        lastTriggerTime = 0L
    }

    fun release() {
        runCatching { melSession?.close() }
        runCatching { embeddingSession?.close() }
        runCatching { wakeWordSession?.close() }
        melSession = null
        embeddingSession = null
        wakeWordSession = null
        isReady = false
    }

    // ---- ONNX inference stages ----

    /**
     * Stage 1: raw PCM [1, 1280] -> mel frames [frames, 32].
     *
     * The model declares its output as **rank 4** ([time, 1, frames, 32]) and
     * this used to cast it straight to `Array<Array<FloatArray>>`, a rank-3
     * type. JVM array types are reified and checked exactly, so that threw
     * `ClassCastException` on every single call -- roughly 12 times a second --
     * and `processAudio`'s outer catch swallowed it and returned -1f. Confirmed
     * on device 144512558D105352: 226 of them in an 18 s capture, and not one
     * "KWS fired". Unwrapped recursively now, exactly as stages 2 and 3 already
     * were; whoever made those two defensive missed this one.
     */
    private fun runMelSpectrogram(samples: FloatArray): Array<FloatArray>? {
        val session = melSession ?: return null
        val inputTensor = OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(samples),
            longArrayOf(1, CHUNK_SAMPLES.toLong())
        )
        return inputTensor.use { input ->
            // Ask the model for its own input name. Hardcoding "input" worked
            // only for the mel stage — the tf2onnx embedding model calls its
            // input "input_1" and the PyTorch classifier calls it "x.1", so two
            // of three stages threw on every frame, were swallowed by the catch
            // below, and the keyword could never fire.
            val result = session.run(mapOf(session.inputNames.first() to input))
            result.use { r -> extractMelFrames(r[0].value) }
        }
    }

    /** Peel wrapper dimensions until the [frames, 32] matrix falls out. */
    private fun extractMelFrames(raw: Any?): Array<FloatArray>? = when {
        raw is Array<*> && raw.isNotEmpty() && raw[0] is FloatArray -> {
            @Suppress("UNCHECKED_CAST")
            raw as Array<FloatArray>
        }
        raw is Array<*> -> extractMelFrames(raw.firstOrNull())
        else -> null
    }

    /** Stage 2: mel buffer [1, 76, 32, 1] → embedding [96] */
    private fun runEmbedding(): FloatArray? {
        val session = embeddingSession ?: return null

        // Build the [1, 76, 32, 1] input from the circular mel buffer
        val input4d = Array(1) { Array(MEL_BUFFER_SIZE) { frame ->
            Array(MEL_BINS) { bin ->
                floatArrayOf(
                    melBuffer[(melBufferPos - MEL_BUFFER_SIZE + frame + MEL_BUFFER_SIZE * 2) % MEL_BUFFER_SIZE][bin]
                )
            }
        }}

        // Flatten to FloatBuffer for ONNX
        val flat = FloatArray(MEL_BUFFER_SIZE * MEL_BINS)
        for (frame in 0 until MEL_BUFFER_SIZE) {
            for (bin in 0 until MEL_BINS) {
                flat[frame * MEL_BINS + bin] = input4d[0][frame][bin][0]
            }
        }

        val inputTensor = OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(flat),
            longArrayOf(1, MEL_BUFFER_SIZE.toLong(), MEL_BINS.toLong(), 1)
        )
        return inputTensor.use { input ->
            // Ask the model for its own input name. Hardcoding "input" worked
            // only for the mel stage — the tf2onnx embedding model calls its
            // input "input_1" and the PyTorch classifier calls it "x.1", so two
            // of three stages threw on every frame, were swallowed by the catch
            // below, and the keyword could never fire.
            val result = session.run(mapOf(session.inputNames.first() to input))
            result.use { r ->
                // Output: [1, 1, 1, 96] — squeeze to [96]
                @Suppress("UNCHECKED_CAST")
                val raw = r[0].value
                extractEmbedding(raw)
            }
        }
    }

    /** Extract the 96-dim vector from whatever shape ONNX returns */
    private fun extractEmbedding(raw: Any): FloatArray {
        return when (raw) {
            is Array<*> -> {
                // Recursively unwrap nested arrays until we find FloatArray
                val first = raw[0]
                if (first is FloatArray) first
                else extractEmbedding(first!!)
            }
            is FloatArray -> raw
            else -> FloatArray(EMBEDDING_DIM)
        }
    }

    /** Stage 3: embeddings [1, N, 96] → score [1, 1] */
    private fun runWakeWordClassifier(): Float? {
        val session = wakeWordSession ?: return null

        // Build [1, N, 96] from the circular embedding buffer
        val flat = FloatArray(EMBEDDING_BUFFER_SIZE * EMBEDDING_DIM)
        for (frame in 0 until EMBEDDING_BUFFER_SIZE) {
            val src = embeddingBuffer[
                (embeddingBufferPos - EMBEDDING_BUFFER_SIZE + frame + EMBEDDING_BUFFER_SIZE * 2) % EMBEDDING_BUFFER_SIZE
            ]
            System.arraycopy(src, 0, flat, frame * EMBEDDING_DIM, EMBEDDING_DIM)
        }

        val inputTensor = OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(flat),
            longArrayOf(1, EMBEDDING_BUFFER_SIZE.toLong(), EMBEDDING_DIM.toLong())
        )
        return inputTensor.use { input ->
            // Ask the model for its own input name. Hardcoding "input" worked
            // only for the mel stage — the tf2onnx embedding model calls its
            // input "input_1" and the PyTorch classifier calls it "x.1", so two
            // of three stages threw on every frame, were swallowed by the catch
            // below, and the keyword could never fire.
            val result = session.run(mapOf(session.inputNames.first() to input))
            result.use { r ->
                @Suppress("UNCHECKED_CAST")
                val raw = r[0].value
                extractScore(raw)
            }
        }
    }

    /** Extract the scalar score from whatever shape ONNX returns */
    private fun extractScore(raw: Any): Float {
        return when (raw) {
            is Float -> raw
            is FloatArray -> raw[0]
            is Array<*> -> {
                val first = raw[0]
                if (first is FloatArray) first[0]
                else if (first is Float) first
                else extractScore(first!!)
            }
            else -> 0f
        }
    }

    companion object {
        private const val TAG = "KeywordSpotter"

        private const val ASSET_DIR = "kws"
        private const val MEL_MODEL = "melspectrogram.onnx"
        private const val EMBEDDING_MODEL = "embedding_model.onnx"
        private const val WAKEWORD_MODEL = "hey_jarvis_v0.1.onnx"

        /** 1280 samples = 80 ms at 16 kHz */
        const val CHUNK_SAMPLES = 1280

        /** 32 Mel frequency bins */
        private const val MEL_BINS = 32

        /** Rolling buffer of 76 mel frames (~760 ms context) */
        private const val MEL_BUFFER_SIZE = 76

        /** 96-dimensional audio embedding */
        private const val EMBEDDING_DIM = 96

        /** Stacked embedding context for the classifier (16 × 80ms = 1.28s) */
        private const val EMBEDDING_BUFFER_SIZE = 16

        /** Default detection threshold */
        private const val DEFAULT_THRESHOLD = 0.5f

        /** Minimum time between triggers to suppress duplicates */
        private const val REFRACTORY_MS = 2000L
    }
}
