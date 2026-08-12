package com.example.handsfree.pipeline

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.handsfree.util.CrashLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Captures raw audio from the device microphone using [AudioRecord] configured
 * per VOSK requirements:
 *   - Sample rate: 16000 Hz
 *   - Channel config: [AudioFormat.CHANNEL_IN_MONO]
 *   - Audio format: [AudioFormat.ENCODING_PCM_16BIT]
 *
 * Applies a simple energy-threshold Voice Activity Detection (VAD) to skip
 * silent buffers (spectral subtraction is deferred per Phase 1 plan). Buffers
 * containing speech are forwarded to [VoskSpeechEngine] via the callback passed
 * to [startCapture]. All capture work runs on a background coroutine — never
 * on the main thread.
 */
class AudioPreprocessor(private val context: Context? = null) {

    @Volatile private var isCapturing = false
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Start microphone capture. Each non-silent chunk is forwarded to [callback]
     * on a background thread (the preprocessor's IO coroutine). Buffers are
     * fresh [ShortArray] instances — the caller may retain them.
     *
     * @param callback invoked for every audio chunk whose RMS exceeds the VAD
     *        threshold. Receiver is invoked on a background thread.
     * @param onError invoked when the recorder cannot start or the capture loop
     *        encounters an unrecoverable read/callback failure.
     */
    fun startCapture(callback: (ShortArray) -> Unit, onError: ((Throwable) -> Unit)? = null) {
        if (isCapturing) {
            Log.w(TAG, "startCapture: already running")
            return
        }
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
                val error = IllegalStateException(
                    "AudioRecord.getMinBufferSize returned error code $minBuf"
                )
                Log.e(TAG, error.message ?: "AudioRecord buffer-size failure")
                onError?.invoke(error)
                return
            }
            // Buffer must hold at least a few chunks; size in bytes = shorts * 2.
            val bufferSizeInShorts = maxOf(minBuf, MIN_BUFFER_FRAMES)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeInShorts * 2
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                val error = IllegalStateException(
                    "AudioRecord failed to initialize (state=${recorder.state})"
                )
                Log.e(TAG, error.message ?: "AudioRecord initialization failure")
                recorder.release()
                onError?.invoke(error)
                return
            }
            if (context != null) CrashLog.step(context, "STEP_10: AudioRecord.startRecording")
            recorder.startRecording()
            if (context != null) CrashLog.ok(context, "STEP_10")
            audioRecord = recorder
            isCapturing = true
            Log.i(
                TAG,
                "Capture started: ${SAMPLE_RATE}Hz mono 16-bit, " +
                    "buffer=$bufferSizeInShorts shorts, VAD rms threshold=$VAD_THRESHOLD"
            )

            captureJob = scope.launch {
                val buffer = ShortArray(CHUNK_FRAMES)
                while (isActive && isCapturing) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) {
                        if (read < 0) {
                            val error = IllegalStateException("AudioRecord.read error code: $read")
                            Log.e(TAG, error.message ?: "AudioRecord read failure")
                            onError?.invoke(error)
                            isCapturing = false
                            break
                        }
                        continue
                    }
                    val slice = if (read == buffer.size) buffer.copyOf() else buffer.copyOfRange(0, read)
                    if (hasSpeech(slice)) {
                        try {
                            callback(slice)
                        } catch (t: Throwable) {
                            Log.e(TAG, "audio callback failed", t)
                            onError?.invoke(t)
                            isCapturing = false
                            break
                        }
                    }
                }
                Log.i(TAG, "Capture loop exiting")
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission not granted", se)
            audioRecord?.release()
            audioRecord = null
            onError?.invoke(se)
        } catch (t: Throwable) {
            Log.e(TAG, "startCapture failed", t)
            audioRecord?.release()
            audioRecord = null
            onError?.invoke(t)
        }
    }

    /**
     * Stop microphone capture and release the underlying [AudioRecord]. Safe
     * to call when not capturing.
     */
    fun stopCapture() {
        if (!isCapturing && audioRecord == null) return
        isCapturing = false
        captureJob?.let { runCatching { it.cancel() } }
        captureJob = null
        audioRecord?.let { recorder ->
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        audioRecord = null
        Log.i(TAG, "Capture stopped")
    }

    /**
     * Release all coroutine resources. Call from [OverlayService.onDestroy].
     * Also calls [stopCapture] internally if still running.
     */
    fun release() {
        stopCapture()
        scope.cancel()
        Log.i(TAG, "AudioPreprocessor released")
    }

    /**
     * Simple RMS-based VAD. Returns true if the chunk's RMS exceeds the silence
     * threshold, indicating voice activity worth forwarding to the recognizer.
     */
    private fun hasSpeech(buf: ShortArray): Boolean {
        var sumSq = 0L
        for (v in buf) {
            val sample = v.toInt()
            sumSq += sample * sample
        }
        val rms = sqrt(sumSq.toDouble() / buf.size.toDouble())
        return rms >= VAD_THRESHOLD
    }

    companion object {
        private const val TAG = "AudioPreprocessor"
        private const val SAMPLE_RATE = 16000

        // Frames read per loop iteration. ~64ms of audio at 16kHz.
        private const val CHUNK_FRAMES = 1024

        // Minimum internal buffer (shorts) requested from AudioRecord.
        private const val MIN_BUFFER_FRAMES = 4096

        // RMS threshold for VAD (in raw sample units). Silence white-noise RMS
        // typically reads ~30-100 on handheld devices; speech reads ~1000+.
        // Tunable — lower to capture quieter speech, raise to filter noisier rooms.
        private const val VAD_THRESHOLD = 300
    }
}