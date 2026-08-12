package com.example.handsfree.pipeline

import android.content.Context
import android.util.Base64
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.handsfree.util.CrashLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Offline ASR backed by the team's fine-tuned Whisper-tiny model, exported to
 * ONNX and executed on-device.
 *
 * Model: https://huggingface.co/KarlShane11/handsfree-whisper-tiny-tl
 * Baseline WER 82.72% -> fine-tuned 63.15% (23.7% relative reduction).
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

    private var env: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private val tokens = HashMap<Int, String>()

    override fun prepare(language: String) {
        activeLanguage = "tl"
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
                // ONNX Runtime's file-backed sessions cannot read APK assets
                // directly. Copy the fixed export before creating either
                // session so a clean install behaves the same as a warm one.
                copyAssetsIfNeeded()
                val runtime = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions()
                try {
                    options.setIntraOpNumThreads(2)
                    options.setInterOpNumThreads(1)
                    // Do not add NNAPI: the export is verified against CPU execution.
                    options.addCPU(true)
                    env = runtime
                    encoder = runtime.createSession(modelPath(ENCODER_FILE), options)
                    decoder = runtime.createSession(modelPath(DECODER_FILE), options)
                } finally {
                    options.close()
                }
                loadTokens(File(modelPath(TOKENS_FILE)))
                CrashLog.ok(context, "WHISPER_2: CPU ONNX sessions and tokens loaded")

                isReady = true
                onReady?.invoke()
            } catch (t: Throwable) {
                Log.e(TAG, "Whisper init failed", t)
                CrashLog.exception(context, "WhisperOnnxEngine.prepare", t)
                release()
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

        var maxAmplitude = 0
        for (sample in pcm) {
            val abs = kotlin.math.abs(sample.toInt())
            if (abs > maxAmplitude) maxAmplitude = abs
        }
        Log.i(TAG, "flushAndTranscribe: buffered ${pcm.size} samples, max amplitude = $maxAmplitude")

        return try {
            val normalized = FloatArray(pcm.size) { index ->
                (pcm[index].toFloat() / PCM_SCALE).coerceIn(-1.0f, 1.0f)
            }
            val audio = FloatArray(N_SAMPLES)
            normalized.copyInto(audio, endIndex = min(normalized.size, audio.size))

            CrashLog.step(context, "WHISPER_3: computing [1,80,3000] log-Mel")
            val mel = logMelSpectrogram(audio)
            val encoded = runEncoder(mel)

            CrashLog.step(context, "WHISPER_4: greedy decoder")
            val zeroCache = FloatArray(CACHE_FLOATS)
            var selfKey = zeroCache
            var selfValue = zeroCache.copyOf()
            var decoded = runDecoder(
                tokenIds = FORCED_PREFIX,
                offset = 0,
                selfKey = selfKey,
                selfValue = selfValue,
                crossKey = encoded.crossKey,
                crossValue = encoded.crossValue
            )
            selfKey = decoded.selfKey
            selfValue = decoded.selfValue

            // max_length includes the four forced-prefix positions in this export.
            val maxGeneratedTokens = MAX_DECODE_TOKENS - FORCED_PREFIX.size
            val generated = ArrayList<Int>(maxGeneratedTokens)
            var nextToken = argmaxLast(decoded.logits, FORCED_PREFIX.size)
            while (generated.size < maxGeneratedTokens && nextToken != TOKEN_EOT) {
                generated += nextToken
                if (generated.size == maxGeneratedTokens) break

                decoded = runDecoder(
                    tokenIds = intArrayOf(nextToken),
                    offset = FORCED_PREFIX.size + generated.size - 1,
                    selfKey = selfKey,
                    selfValue = selfValue,
                    crossKey = encoded.crossKey,
                    crossValue = encoded.crossValue
                )
                selfKey = decoded.selfKey
                selfValue = decoded.selfValue
                nextToken = argmaxLast(decoded.logits, 1)
            }

            CrashLog.ok(context, "WHISPER_5: decoded ${generated.size} tokens")
            detokenize(generated)
        } catch (t: Throwable) {
            Log.e(TAG, "inference failed", t)
            CrashLog.exception(context, "WhisperOnnxEngine.transcribe", t)
            // Do not allow later audio callbacks to use a failed native session.
            release()
            onError?.invoke(t)
            null
        }
    }

    /** Whisper has no streaming hypothesis — the UI shows a listening state instead. */
    override fun getPartialResult(): String = ""

    override fun release() {
        isReady = false
        synchronized(utteranceBuffer) { utteranceBuffer.clear() }
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
        encoder = null
        decoder = null
        env = null
        synchronized(tokens) { tokens.clear() }
        Log.i(TAG, "released")
    }

    private data class EncodedAudio(val crossKey: FloatArray, val crossValue: FloatArray)

    private data class DecoderOutput(
        val logits: FloatArray,
        val selfKey: FloatArray,
        val selfValue: FloatArray
    )

    private fun loadTokens(file: File) {
        synchronized(tokens) {
            tokens.clear()
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val fields = line.trim().split(WHITESPACE)
                    if (fields.size < 2) return@forEach
                    val id = fields[1].toIntOrNull() ?: return@forEach
                    if (id > TOKENS_MAX_TEXT_ID) return@forEach
                    val decoded = try {
                        Base64.decode(fields[0], Base64.DEFAULT)
                    } catch (_: IllegalArgumentException) {
                        // Token 50256 is a bare "=" which is invalid base64.
                        // Safe to skip — it is never emitted during decoding.
                        return@forEach
                    }
                    tokens[id] = decoded.toString(Charsets.UTF_8)
                }
            }
        }
        if (tokens.size <= TOKENS_MAX_TEXT_ID / 2) {
            error("tokens.txt loaded only ${tokens.size} text tokens")
        }
    }

    private fun runEncoder(mel: FloatArray): EncodedAudio {
        val runtime = env ?: error("ONNX environment is not ready")
        val session = encoder ?: error("Whisper encoder is not ready")
        val input = floatTensor(runtime, mel, longArrayOf(1, N_MELS.toLong(), MEL_FRAMES.toLong()))
        val result = session.run(mapOf(ENCODER_INPUT to input))
        return try {
            EncodedAudio(readFloatTensor(result[0]), readFloatTensor(result[1]))
        } finally {
            result.close()
            input.close()
        }
    }

    private fun runDecoder(
        tokenIds: IntArray,
        offset: Int,
        selfKey: FloatArray,
        selfValue: FloatArray,
        crossKey: FloatArray,
        crossValue: FloatArray
    ): DecoderOutput {
        val runtime = env ?: error("ONNX environment is not ready")
        val session = decoder ?: error("Whisper decoder is not ready")
        val inputTokens = longTensor(runtime, LongArray(tokenIds.size) { tokenIds[it].toLong() }, longArrayOf(1, tokenIds.size.toLong()))
        val inputSelfKey = floatTensor(runtime, selfKey, CACHE_SHAPE)
        val inputSelfValue = floatTensor(runtime, selfValue, CACHE_SHAPE)
        val inputCrossKey = floatTensor(runtime, crossKey, CROSS_SHAPE)
        val inputCrossValue = floatTensor(runtime, crossValue, CROSS_SHAPE)
        val inputOffset = longTensor(runtime, longArrayOf(offset.toLong()), longArrayOf(1))
        val result = session.run(
            mapOf(
                DECODER_TOKENS to inputTokens,
                DECODER_SELF_KEY to inputSelfKey,
                DECODER_SELF_VALUE to inputSelfValue,
                DECODER_CROSS_KEY to inputCrossKey,
                DECODER_CROSS_VALUE to inputCrossValue,
                DECODER_OFFSET to inputOffset
            )
        )
        return try {
            DecoderOutput(
                logits = readFloatTensor(result[0]),
                selfKey = readFloatTensor(result[1]),
                selfValue = readFloatTensor(result[2])
            )
        } finally {
            result.close()
            inputTokens.close()
            inputSelfKey.close()
            inputSelfValue.close()
            inputCrossKey.close()
            inputCrossValue.close()
            inputOffset.close()
        }
    }

    private fun argmaxLast(logits: FloatArray, sequenceLength: Int): Int {
        val start = (sequenceLength - 1) * VOCAB_SIZE
        var bestId = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (id in 0 until VOCAB_SIZE) {
            val value = logits[start + id]
            if (value > bestValue) {
                bestValue = value
                bestId = id
            }
        }
        return bestId
    }

    private fun detokenize(ids: List<Int>): String {
        val output = StringBuilder()
        synchronized(tokens) {
            ids.forEach { id ->
                if (id >= TOKENS_MAX_TEXT_ID) return@forEach
                val token = tokens[id] ?: return@forEach
                if (!token.startsWith("<|") || !token.endsWith("|>")) output.append(token)
            }
        }
        return output.toString().trim()
    }

    private fun floatTensor(runtime: OrtEnvironment, values: FloatArray, shape: LongArray): OnnxTensor {
        val buffer = ByteBuffer
            .allocateDirect(values.size * java.lang.Float.BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(values)
        buffer.flip()
        return OnnxTensor.createTensor(runtime, buffer, shape)
    }

    private fun longTensor(runtime: OrtEnvironment, values: LongArray, shape: LongArray): OnnxTensor {
        val buffer = ByteBuffer
            .allocateDirect(values.size * java.lang.Long.BYTES)
            .order(ByteOrder.nativeOrder())
            .asLongBuffer()
        buffer.put(values)
        buffer.flip()
        return OnnxTensor.createTensor(runtime, buffer, shape)
    }

    private fun readFloatTensor(value: Any): FloatArray {
        val tensor = value as? OnnxTensor ?: error("Expected ONNX tensor, got ${value.javaClass.name}")
        val buffer = tensor.getFloatBuffer()
        val output = FloatArray(buffer.remaining())
        buffer.get(output)
        return output
    }

    /**
     * Reproduces Whisper's torch.stft front end: centered reflect padding,
     * periodic Hann window, 400-point FFT, and removal of the 3001st frame.
     */
    private fun logMelSpectrogram(audio: FloatArray): FloatArray {
        val logMel = FloatArray(N_MELS * MEL_FRAMES)
        var maximum = Float.NEGATIVE_INFINITY

        for (frame in 0 until MEL_FRAMES) {
            val real = DoubleArray(N_FFT)
            val imaginary = DoubleArray(N_FFT)
            val frameStart = frame * HOP_LENGTH - N_FFT / 2
            for (sample in 0 until N_FFT) {
                val source = reflectIndex(frameStart + sample, audio.size)
                real[sample] = audio[source].toDouble() * HANN_WINDOW[sample]
            }
            val spectrum = fft(real, imaginary)
            val power = FloatArray(N_FFT / 2 + 1)
            for (bin in power.indices) {
                power[bin] = (spectrum.real[bin] * spectrum.real[bin] +
                    spectrum.imaginary[bin] * spectrum.imaginary[bin]).toFloat()
            }

            for (melBin in 0 until N_MELS) {
                var energy = 0.0
                val filter = MEL_FILTERS[melBin]
                for (bin in power.indices) energy += filter[bin] * power[bin]
                val value = log10(max(energy, 1e-10)).toFloat()
                logMel[melBin * MEL_FRAMES + frame] = value
                if (value > maximum) maximum = value
            }
        }

        val floor = maximum - 8.0f
        for (index in logMel.indices) {
            logMel[index] = (max(logMel[index], floor) + 4.0f) / 4.0f
        }
        return logMel
    }

    private fun reflectIndex(index: Int, length: Int): Int {
        var result = index
        while (result < 0 || result >= length) {
            result = if (result < 0) -result else 2 * length - 2 - result
        }
        return result
    }

    private data class ComplexArray(val real: DoubleArray, val imaginary: DoubleArray)

    /** Mixed-radix Cooley–Tukey FFT for the required composite size 400. */
    private fun fft(real: DoubleArray, imaginary: DoubleArray): ComplexArray {
        if (real.size == 1) return ComplexArray(real, imaginary)
        val radix = if (real.size % 2 == 0) 2 else 5
        val subLength = real.size / radix
        val subTransforms = Array(radix) { branch ->
            val subReal = DoubleArray(subLength)
            val subImaginary = DoubleArray(subLength)
            for (index in 0 until subLength) {
                subReal[index] = real[radix * index + branch]
                subImaginary[index] = imaginary[radix * index + branch]
            }
            fft(subReal, subImaginary)
        }
        val outputReal = DoubleArray(real.size)
        val outputImaginary = DoubleArray(real.size)
        val outerTwiddle = twiddles(real.size)
        for (frequency in 0 until subLength) {
            for (branch in 0 until radix) {
                var sumReal = 0.0
                var sumImaginary = 0.0
                for (subBranch in 0 until radix) {
                    val phase = (frequency + branch * subLength) * subBranch % real.size
                    val c = outerTwiddle.cosine[phase]
                    val s = outerTwiddle.sine[phase]
                    
                    val subReal = subTransforms[subBranch].real[frequency]
                    val subImag = subTransforms[subBranch].imaginary[frequency]

                    sumReal += subReal * c - subImag * s
                    sumImaginary += subReal * s + subImag * c
                }
                
                val outputIndex = frequency + branch * subLength
                outputReal[outputIndex] = sumReal
                outputImaginary[outputIndex] = sumImaginary
            }
        }
        return ComplexArray(outputReal, outputImaginary)
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
         * True once the three ONNX artefacts are in assets/whisper/.
         * Set 2026-08-12 when Karl's export landed.
         */
        const val MODEL_PRESENT = true

        /**
         * Gate on the engine actually working, separate from the assets being
         * bundled.
         *
         * The model files are present, but [flushAndTranscribe] is still a stub
         * returning null. If the app selected Whisper on [MODEL_PRESENT] alone
         * it would load happily, report ready, and then transcribe nothing —
         * strictly worse than VOSK, which works.
         *
         * FLIP THIS TO TRUE only after a real .wav has been transcribed
         * end to end and the output matches Karl's notebook for that file.
         */
        const val INFERENCE_IMPLEMENTED = true

        /** Whisper is usable only when both are true. */
        val USABLE: Boolean get() = MODEL_PRESENT && INFERENCE_IMPLEMENTED

        private const val SAMPLE_RATE = 16000
        private const val PCM_SCALE = 32768.0f
        private const val VOCAB_SIZE = 51865
        private const val CACHE_FLOATS = 4 * 448 * 384
        private val CACHE_SHAPE = longArrayOf(4, 1, MAX_DECODE_TOKENS.toLong(), 384)
        private val CROSS_SHAPE = longArrayOf(4, 1, ENCODER_FRAMES.toLong(), 384)
        private val WHITESPACE = Regex("\\s+")

        private const val ENCODER_INPUT = "mel"
        private const val DECODER_TOKENS = "tokens"
        private const val DECODER_SELF_KEY = "in_n_layer_self_k_cache"
        private const val DECODER_SELF_VALUE = "in_n_layer_self_v_cache"
        private const val DECODER_CROSS_KEY = "n_layer_cross_k"
        private const val DECODER_CROSS_VALUE = "n_layer_cross_v"
        private const val DECODER_OFFSET = "offset"

        private data class Twiddles(val cosine: DoubleArray, val sine: DoubleArray)
        private val twiddleCache = ConcurrentHashMap<Int, Twiddles>()
        private val HANN_WINDOW = DoubleArray(N_FFT) { index ->
            0.5 - 0.5 * cos(2.0 * Math.PI * index / N_FFT)
        }
        private val MEL_FILTERS: Array<FloatArray> by lazy { createMelFilterBank() }

        private fun twiddles(size: Int): Twiddles = twiddleCache.computeIfAbsent(size) {
            Twiddles(
                cosine = DoubleArray(size) { index -> cos(-2.0 * Math.PI * index / size) },
                sine = DoubleArray(size) { index -> sin(-2.0 * Math.PI * index / size) }
            )
        }

        /** Same Slaney-normalized bank as Whisper's published mel_filters.npz. */
        private fun createMelFilterBank(): Array<FloatArray> {
            val fSp = 200.0 / 3.0
            val minLogHz = 1000.0
            val minLogMel = minLogHz / fSp
            val logStep = ln(6.4) / 27.0
            fun hzToMel(hz: Double): Double =
                if (hz < minLogHz) hz / fSp else minLogMel + ln(hz / minLogHz) / logStep
            fun melToHz(mel: Double): Double =
                if (mel < minLogMel) fSp * mel else minLogHz * exp(logStep * (mel - minLogMel))

            val melPoints = DoubleArray(N_MELS + 2) { index ->
                melToHz(hzToMel(0.0) + index * (hzToMel(SAMPLE_RATE / 2.0) - hzToMel(0.0)) / (N_MELS + 1))
            }
            val differences = DoubleArray(N_MELS + 1) { index -> melPoints[index + 1] - melPoints[index] }
            return Array(N_MELS) { melBin ->
                FloatArray(N_FFT / 2 + 1) { bin ->
                    val frequency = SAMPLE_RATE * bin.toDouble() / N_FFT
                    val lower = (frequency - melPoints[melBin]) / differences[melBin]
                    val upper = (melPoints[melBin + 2] - frequency) / differences[melBin + 1]
                    (max(0.0, min(lower, upper)) * 2.0 /
                        (melPoints[melBin + 2] - melPoints[melBin])).toFloat()
                }
            }
        }

        // ------------------------------------------------------------------
        // Token IDs — read directly from the fine-tuned model's
        // generation_config.json. Do NOT guess these; Whisper's language token
        // ordering is not obvious and a wrong id produces fluent output in the
        // wrong language, which is very easy to mistake for a bad model.
        // ------------------------------------------------------------------

        /** <|startoftranscript|> */
        const val TOKEN_SOT = 50258

        /** <|tl|> — Tagalog. Verified against generation_config lang_to_id. */
        const val TOKEN_LANG_TL = 50348

        /** <|transcribe|>. NOT <|translate|> (50358) — translate would emit English. */
        const val TOKEN_TRANSCRIBE = 50359

        /** <|notimestamps|> */
        const val TOKEN_NO_TIMESTAMPS = 50363

        /** <|endoftext|>, also the pad token. Stop decoding here. */
        const val TOKEN_EOT = 50257

        /** Forced decoder prefix, in order. */
        val FORCED_PREFIX = intArrayOf(TOKEN_SOT, TOKEN_LANG_TL, TOKEN_TRANSCRIBE, TOKEN_NO_TIMESTAMPS)

        /** generation_config.max_length. */
        const val MAX_DECODE_TOKENS = 448

        /**
         * tokens.txt covers ids 0..50256 only — the 1,608 special tokens above
         * that are absent. This is fine: those ids are never emitted as text.
         * When detokenising, skip any id >= [TOKEN_EOT].
         *
         * Entries are `<base64> <id>` per line; base64-decode to UTF-8.
         */
        const val TOKENS_MAX_TEXT_ID = 50256

        // Front-end constants, from the model's preprocessor_config.json.
        const val N_MELS = 80
        const val N_FFT = 400
        const val HOP_LENGTH = 160
        const val CHUNK_SECONDS = 30
        private const val N_SAMPLES = SAMPLE_RATE * CHUNK_SECONDS
        const val MEL_FRAMES = 3000        // 30 s * 16000 / 160
        const val ENCODER_FRAMES = 1500    // config.max_source_positions

        const val ASSET_DIR = "whisper"
        const val ENCODER_FILE = "encoder.onnx"
        const val DECODER_FILE = "decoder.onnx"
        const val TOKENS_FILE = "tokens.txt"

        private const val MAX_UTTERANCE_SECONDS = 25
    }
}
